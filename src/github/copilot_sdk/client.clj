(ns github.copilot-sdk.client
  "CopilotClient - manages connection to the Copilot CLI server."
  (:require [clojure.core.async :as async :refer [go go-loop <! >! >!! chan close!]]
            [clojure.core.async.impl.protocols :as async-protocols]
            [clojure.spec.alpha :as s]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [github.copilot-sdk.factory :as factory]
            [github.copilot-sdk.protocol :as proto]
            [github.copilot-sdk.process :as proc]
            [github.copilot-sdk.specs :as specs]
            [github.copilot-sdk.session :as session]
            [github.copilot-sdk.util :as util]
            [github.copilot-sdk.teardown :as td]
            [github.copilot-sdk.logging :as log])
  (:import [java.io BufferedInputStream]
           [java.net Socket]
           [java.util.concurrent LinkedBlockingQueue RejectedExecutionException
            ThreadFactory ThreadPoolExecutor ThreadPoolExecutor$AbortPolicy
            TimeUnit]
           [java.util.concurrent.atomic AtomicLong]))

(def ^:private sdk-protocol-version-max 3)
;; Upstream PR #1378 raised the minimum protocol version to 3 by removing
;; the v2 back-compat shims (the `tool.call` and `permission.request`
;; server→client RPCs). Servers reporting a version below 3 are now rejected.
(def ^:private sdk-protocol-version-min 3)

(def ^:private github-token-provider-thread-count 4)
(def ^:private github-token-provider-queue-size 64)
(def ^:private github-token-provider-shutdown-timeout-ms 1000)
(def ^:private github-token-provider-saturation-count-max 1000000)

(defn- get-trace-context
  "Call the user-provided trace context provider. Returns {} when no provider
   is configured or returns a non-map value. Only :traceparent and :tracestate
   keys are retained to prevent accidental override of RPC params."
  [provider]
  (if-not provider
    {}
    (try
      (let [ctx (provider)]
        (if (map? ctx)
          (select-keys ctx [:traceparent :tracestate])
          {}))
      (catch Throwable _
        {}))))

(defn- parse-cli-url
  "Parse CLI URL into {:host :port}."
  [url]
  (let [clean (str/replace url #"(?i)^https?://" "")]
    (if (re-matches #"\d+" clean)
      ;; Port only
      (let [port (parse-long clean)]
        (when (or (nil? port) (<= port 0) (> port 65535))
          (throw (ex-info "Invalid port in cli-url" {:url url :port port})))
        {:host "localhost" :port port})
      ;; host:port
      (let [[_ bracketed-host bracketed-port]
            (re-matches #"^\[([^\]]+)\]:(-?\d+)$" clean)
            [_ plain-host plain-port]
            (when-not bracketed-host
              (re-matches #"^([^:]*):(-?\d+)$" clean))
            host (or bracketed-host plain-host)
            port-str (or bracketed-port plain-port)]
        (when (or (nil? host) (str/blank? port-str))
          (throw (ex-info "Invalid cli-url format" {:url url})))
        (let [port (parse-long port-str)]
          (when (or (nil? port) (<= port 0) (> port 65535))
            (throw (ex-info "Invalid port in cli-url" {:url url :port port})))
          {:host (if (str/blank? host) "localhost" host)
           :port port})))))

(defn- ensure-github-token-provider-transport!
  [client config]
  (when (and (:github-token-provider config)
             (get-in client [:options :cli-url]))
    (throw
     (ex-info
      (str ":github-token-provider requires an SDK-owned transport or "
           "child-process stdio; external cli-url servers cannot be authenticated")
      {:type :github-token-provider-unsafe-transport
       :cli-url (get-in client [:options :cli-url])}))))

(defn- mask-secret
  "Replace `v` with \"***\" only when it is a non-blank string (i.e. an actual
   secret value). nil, blank, and non-string values are returned unchanged: they
   carry no secret to leak, and preserving them keeps spec validation errors
   accurate (masking a blank/invalid value to \"***\" could otherwise make an
   invalid map look valid and suppress the real `s/explain` output)."
  [v]
  (if (and (string? v) (not (str/blank? v))) "***" v))

(defn- mask-present
  "Replace the value of each key in `ks` with \"***\" when the key is present in
   `m` and its value is a secret (see [[mask-secret]]). Absent keys stay absent;
   nil/blank/non-string values are left unchanged."
  [m ks]
  (reduce (fn [acc k] (if (contains? acc k) (update acc k mask-secret) acc)) m ks))

(defn- mask-all-values
  "Mask every secret value in map `m` (see [[mask-secret]]). Returns `m`
   unchanged if not a map. Used for header/env maps where every value is
   potentially a secret; nil/blank/non-string values are left unchanged."
  [m]
  (if (map? m) (into {} (map (fn [[k v]] [k (mask-secret v)])) m) m))

(defn- redact-mcp-servers
  "Mask secret-bearing fields (`:mcp-headers`, `:env`) in an MCP servers config map
   (keyed by server id) before embedding it in exception data. Non-map inputs and
   non-map server entries pass through unchanged."
  [servers]
  (if-not (map? servers)
    servers
    (into {}
          (map (fn [[id server]]
                 [id (if (map? server)
                       (cond-> server
                         (map? (:mcp-headers server)) (update :mcp-headers mask-all-values)
                         (map? (:env server)) (update :env mask-all-values))
                       server)]))
          servers)))

(defn- mask-provider
  "Mask the secret-bearing fields of a single BYOK provider map: the `:api-key`
   and `:bearer-token` auth fields, plus every value in a custom request
   `:headers` map. Non-map inputs pass through unchanged."
  [p]
  (if-not (map? p)
    p
    (-> p
        (mask-present [:api-key :bearer-token])
        (cond-> (map? (:headers p)) (update :headers mask-all-values)))))

(defn- mask-providers
  "Mask every provider in a `:providers` registry value. The valid shape is a
   sequential collection of provider maps, but redact-secrets runs on
   already-invalid configs, so a caller may have supplied a map (name->config)
   or any other collection — mask the secret-bearing fields in every case so
   nothing leaks into thrown ex-data."
  [ps]
  (cond
    (map? ps)  (reduce-kv (fn [acc k v] (assoc acc k (mask-provider v))) (empty ps) ps)
    (coll? ps) (mapv mask-provider ps)
    :else      ps))

(defn- redact-secrets
  "Mask secret values in a caller-supplied options/config map so it can be embedded
   in exception data and messages without leaking credentials. Masks the top-level
   auth tokens, the `:env` map (merged into the spawned CLI environment, so it
   can carry credentials), the nested BYOK `:provider` credentials and every
   entry of the `:providers` registry (api/bearer keys and any custom request
   `:headers`), and any `:mcp-servers` header/env values. Non-map inputs pass
   through unchanged."
  [m]
  (if-not (map? m)
    m
    (cond-> (mask-present m [:github-token :tcp-connection-token])
      (map? (:env m))
      (update :env mask-all-values)

      (map? (:provider m))
      (update :provider mask-provider)

      ;; ::providers is a `coll-of` — the valid shape is sequential, but
      ;; redact-secrets runs on *already-invalid* configs in the error path, so
      ;; masking must cover any collection (vector, list, set) AND an erroneous
      ;; map-valued registry. mask-providers dispatches on the shape.
      (coll? (:providers m))
      (update :providers mask-providers)

      (contains? m :mcp-servers)
      (update :mcp-servers redact-mcp-servers))))

(defn- default-options
  "Return default client options."
  []
  {:cli-path "copilot"
   :cli-args []
   :cwd (System/getProperty "user.dir")
   :port 0
   :use-stdio? true
   :log-level :info
   :auto-start? true
   :auto-restart? false
   :notification-queue-size 4096
   :router-queue-size 4096
   :request-handler-threads proto/default-request-handler-threads
   :request-handler-queue-size proto/default-request-handler-queue-size
   :tool-timeout-ms 120000
   :env nil})

(defn- ensure-valid-mcp-servers!
  [servers]
  (when-not (s/valid? ::specs/mcp-servers servers)
    (let [safe-servers (redact-mcp-servers servers)]
      (throw (ex-info "Invalid :mcp-servers config (expected map keyed by server ID)."
                      {:spec ::specs/mcp-servers
                       :mcp-servers safe-servers
                       :explain (s/explain-data ::specs/mcp-servers safe-servers)})))))

;; Client is a simple map with a single state atom
;; The state atom contains all mutable state as an immutable map:
;; {:status :disconnected/:connecting/:connected/:error
;;  :connection {:running? :pending-requests :request-handler :writer-thread}
;;  :connection-io nil or protocol/Connection (IO resources only)
;;  :process nil or process/ManagedProcess  
;;  :socket nil or Socket (for TCP mode)
;;  :sessions {session-id -> {:tool-handlers :permission-handler :destroyed?}}
;;  :session-io {session-id -> {:event-chan :event-mult}} (IO resources)
;;  :actual-port nil or int
;;  :router-ch nil or channel
;;  :stopping? false
;;  :restarting? false
;;  :models-cache nil|promise|vector (list-models cache)
;;  :lifecycle-handlers {handler-id -> {:handler fn :event-type type-or-nil}}}

(defn- initial-state
  "Create initial client state."
  [port]
  {:status :disconnected
   :connection nil           ; protocol state (when connected)
   :connection-io nil        ; protocol/Connection record (IO resources)
   :process nil
   :socket nil
   :sessions {}              ; session state by session-id
   :session-io {}            ; session IO resources by session-id
   :disconnecting-session-ids #{}
   :actual-port port
   :router-ch nil
   :router-queue nil
   :router-thread nil
   :router-running? false
   :stopping? false
   :models-cache nil         ; nil, promise, or vector of models (cleared on stop)
   :lifecycle-handlers {}
   :github-token-providers {}
   :github-token-provider-invocations {}
   :github-token-provider-executor nil
   :github-token-provider-runtime-generation 0
   :github-token-provider-saturation-count 0
   :lifecycle-ch nil         ; serial dispatch channel for lifecycle handlers
   :stderr-buffer nil         ; atom of recent stderr lines (for error context)
   :negotiated-protocol-version 0})

(defn client
  "Create a new CopilotClient.
   
   Options:
   - :cli-path      - Path to CLI executable (default: \"copilot\")
   - :cli-args      - Extra arguments for CLI
   - :cli-url       - URL of existing server (e.g., \"localhost:8080\")
   - :cwd           - Working directory for CLI process
   - :port          - TCP port (default: 0 for random)
    - :use-stdio?    - Use stdio transport (default: true)
    - :log-level     - :none :error :warning :info :debug :all
    - :auto-start?   - Auto-start on first use (default: true)
    - :auto-restart? - **DEPRECATED**: This option has no effect and will be removed in a future release.
    - :notification-queue-size - Max queued protocol notifications (default: 4096)
    - :router-queue-size - Max queued non-session notifications (default: 4096)
    - :request-handler-threads - Max reverse-RPC handlers (hooks, sessionFs, factories,
                       user input, etc.) executing concurrently (default: 16). Handlers run on
                       a bounded worker pool owned by the connection, never on core.async
                       dispatch, so a handler that blocks cannot grow threads without limit.
    - :request-handler-queue-size - Max reverse RPCs queued once every worker is busy
                       (default: 256). Beyond `threads + queue-size` outstanding requests the
                       runtime receives an explicit `-32000` `request_handler_saturated`
                       error rather than the request being dropped or stalled.
    - :tool-timeout-ms - Timeout for tool calls that return a channel (default: 120000)
    - :env           - Environment variables map
    - :github-token  - GitHub token for authentication (sets COPILOT_SDK_AUTH_TOKEN env var)
    - :use-logged-in-user? - Whether to use logged-in user auth (default: true, false when github-token provided)
    - :is-child-process? - When true, SDK is a child of an existing Copilot CLI process and uses stdio to communicate with it (no process spawning)
    - :on-list-models - Zero-arg fn returning a seq of model info maps; bypasses the RPC call and does not require start!
    - :telemetry     - OpenTelemetry config map with optional keys :otlp-endpoint, :otlp-protocol, :file-path, :exporter-type, :source-name, :capture-content?. :otlp-protocol is \"http/json\" or \"http/protobuf\" and sets OTEL_EXPORTER_OTLP_PROTOCOL.
    - :on-get-trace-context - Zero-arg fn returning {:traceparent ... :tracestate ...} for distributed trace propagation
    - :on-github-telemetry - **Experimental / Internal**. One-arg fn invoked with each GitHub telemetry
                       notification (upstream PR #1835). Registering a handler opts the connection into
                       `enableGitHubTelemetryForwarding` on the `connect` handshake (so the first
                       session's un-replayable `session.start` telemetry is forwarded, upstream PR #1909)
                       as well as on `session.create` and `session.resume`;
                       the runtime then emits connection-global `gitHubTelemetry.event` notifications,
                       each passed to this handler. The notification is an idiom-shaped map with keys
                       :session-id, :restricted, and :event; the event's :properties, :metrics, and
                       :features sub-maps are opaque and preserved verbatim. Not a stable public SDK
                       surface. A throwing handler is caught and logged (WARN) and cannot corrupt dispatch.
    - :session-idle-timeout-seconds - Server-wide session idle timeout in seconds. When > 0,
                       the SDK appends `--session-idle-timeout <n>` to the spawned CLI so idle
                       sessions are cleaned up after the given duration. Default: disabled.
    - :builtin-plugin-directories - Vector of absolute paths to trusted plugin directories
                       bundled by the host. The complete non-empty set is registered once
                       during startup before sessions can be created.
    - :remote?       - **Experimental**. Enable remote session support (Mission Control). When true,
                       the SDK appends `--remote` to the spawned CLI; sessions in a GitHub repository
                       working directory become accessible from GitHub web and mobile. Ignored when
                       `:cli-url` is set. (upstream PR #1192)
   - :mode          - Client mode (upstream PR #1428). One of:
                       - `:copilot-cli` (default) — preserves historical CLI behavior.
                       - `:empty` — multitenancy hardening for hosts running sessions on behalf of
                         multiple users. Empty mode REQUIRES one of `:copilot-home`, `:session-fs`,
                         `:cli-url`, or `:is-child-process?` so the runtime has a tenant-scoped
                         storage root. It also forces `COPILOT_DISABLE_KEYTAR=1` on the spawned
                         CLI, spreads safe defaults under each session's config (telemetry off,
                         host-git operations disabled, ...), strips the `:environment-context`
                         section from system messages unless the caller already controls it,
                         and requires every session to specify `:available-tools`."
  ([]
   (client {}))
  ([opts]
   (when (and (:cli-url opts) (= true (:use-stdio? opts)))
     (throw (ex-info "cli-url is mutually exclusive with use-stdio?" (redact-secrets opts))))
   (when (and (:cli-url opts) (:cli-path opts))
     (throw (ex-info "cli-url is mutually exclusive with cli-path" (redact-secrets opts))))
   ;; Validation: github-token and use-logged-in-user? cannot be used with cli-url
   (when (and (:cli-url opts) (or (:github-token opts) (some? (:use-logged-in-user? opts))))
     (throw (ex-info "github-token and use-logged-in-user? cannot be used with cli-url (external server manages its own auth)"
                     {:cli-url (:cli-url opts)
                      :github-token (when (:github-token opts) "***")
                      :use-logged-in-user? (:use-logged-in-user? opts)})))
   ;; Validation: is-child-process? is mutually exclusive with cli-url
   (when (and (:is-child-process? opts) (:cli-url opts))
     (throw (ex-info "is-child-process? is mutually exclusive with cli-url"
                     {:is-child-process? true :cli-url (:cli-url opts)})))
   ;; Validation: is-child-process? requires stdio transport
   (when (and (:is-child-process? opts) (= false (:use-stdio? opts)))
     (throw (ex-info "is-child-process? requires use-stdio? to be true (or unset)"
                     {:is-child-process? true :use-stdio? false})))
   ;; tcp-connection-token cannot be combined with use-stdio?:true (upstream PR #1176).
   ;; cli-url is fine — cli-url implies TCP regardless of :use-stdio? (see above).
   (when (and (some? (:tcp-connection-token opts)) (= true (:use-stdio? opts)))
     (throw (ex-info "tcp-connection-token cannot be used with use-stdio? true (stdio is pre-authenticated by transport)"
                     {:tcp-connection-token "***" :use-stdio? true})))
   ;; Empty mode requires a tenant-scoped storage root (upstream PR #1428).
   (when (and (= :empty (:mode opts))
              (not (or (:copilot-home opts)
                       (:session-fs opts)
                       (:cli-url opts)
                       (:is-child-process? opts))))
     (throw (ex-info
             (str "Mode :empty requires one of :copilot-home, :session-fs, "
                  ":cli-url, or :is-child-process? so the spawned CLI has a "
                  "tenant-scoped storage root.")
             {:mode :empty
              :provided-storage-keys (select-keys opts [:copilot-home :session-fs
                                                        :cli-url :is-child-process?])})))
   (when (and (contains? opts :builtin-plugin-directories)
              (not (s/valid? ::specs/builtin-plugin-directories
                             (:builtin-plugin-directories opts))))
     (throw (ex-info
             "builtin-plugin-directories must be a vector of absolute paths"
             {:builtin-plugin-directories (:builtin-plugin-directories opts)})))
   (when-not (s/valid? ::specs/client-options opts)
     (let [safe-opts (redact-secrets opts)
           unknown (specs/unknown-keys opts specs/client-options-keys)
           explain (s/explain-data ::specs/client-options safe-opts)
           msg (if (seq unknown)
                 (format "Invalid client options: unknown keys %s. Valid keys are: %s"
                         (pr-str unknown)
                         (pr-str (sort specs/client-options-keys)))
                 (format "Invalid client options: %s"
                         (with-out-str (s/explain ::specs/client-options safe-opts))))]
       (throw (ex-info msg {:options safe-opts :unknown-keys unknown :explain explain}))))
   (when-let [size (:notification-queue-size opts)]
     (when (<= size 0)
       (throw (ex-info "notification-queue-size must be > 0" {:notification-queue-size size}))))
   (when-let [size (:router-queue-size opts)]
     (when (<= size 0)
       (throw (ex-info "router-queue-size must be > 0" {:router-queue-size size}))))
   (when-let [threads (:request-handler-threads opts)]
     (when (<= threads 0)
       (throw (ex-info "request-handler-threads must be > 0"
                       {:request-handler-threads threads}))))
   (when-let [size (:request-handler-queue-size opts)]
     (when (<= size 0)
       (throw (ex-info "request-handler-queue-size must be > 0"
                       {:request-handler-queue-size size}))))
   (when-let [timeout (:tool-timeout-ms opts)]
     (when (<= timeout 0)
       (throw (ex-info "tool-timeout-ms must be > 0" {:tool-timeout-ms timeout}))))

   (let [;; Default use-logged-in-user? to false when github-token is provided, otherwise true
         opts-with-defaults (cond-> opts
                              (and (:github-token opts) (nil? (:use-logged-in-user? opts)))
                              (assoc :use-logged-in-user? false))
         merged (merge (default-options) opts-with-defaults)
         ;; COPILOT_CLI_PATH env var fallback: when no explicit :cli-path or :cli-url,
         ;; check effective env for COPILOT_CLI_PATH before using default "copilot"
         ;; Merge system env with user overrides so COPILOT_CLI_PATH is visible
         ;; even when :env provides additional overrides
         effective-env (if (:env opts)
                         (merge (into {} (System/getenv)) (:env opts))
                         (into {} (System/getenv)))
         merged (if (and (not (:cli-path opts))
                         (not (:cli-url opts))
                         (get effective-env "COPILOT_CLI_PATH"))
                  (assoc merged :cli-path (get effective-env "COPILOT_CLI_PATH"))
                  merged)
         child-process? (:is-child-process? opts)
         cli-url? (boolean (:cli-url opts))
         external? (boolean (or cli-url? child-process?))
         {:keys [host port]} (when cli-url?
                               (parse-cli-url (:cli-url opts)))
         ;; tcp-connection-token (upstream PR #1176): use the user-provided
         ;; token when present; else auto-generate a UUID when the SDK spawns
         ;; the CLI in TCP mode (i.e. not stdio, not cli-url, not child-process).
         ;; Note: `merged` already has :use-stdio? defaulted to true, so the
         ;; "TCP spawn" predicate looks at the *resolved* transport.
         resolved-use-stdio? (cond
                               cli-url? false
                               (some? (:use-stdio? merged)) (:use-stdio? merged)
                               :else true)
         sdk-spawns-cli? (and (not resolved-use-stdio?)
                              (not cli-url?)
                              (not child-process?))
         effective-connection-token (or (:tcp-connection-token opts)
                                        (when sdk-spawns-cli?
                                          (str (java.util.UUID/randomUUID))))
         final-opts (cond-> merged
                      cli-url? (-> (assoc :use-stdio? false)
                                   (assoc :host host)
                                   (assoc :port port)
                                   (assoc :external-server? true))
                      child-process? (assoc :external-server? true)
                      effective-connection-token (assoc :tcp-connection-token
                                                        effective-connection-token))]
     (cond-> {:options final-opts
              :external-server? external?
              :actual-host (or host "localhost")
              :state (atom (assoc (initial-state port) :options final-opts))}
       (:on-list-models opts)
       (assoc :on-list-models (:on-list-models opts))
       (:on-get-trace-context opts)
       (assoc :on-get-trace-context (:on-get-trace-context opts))
       (:session-fs opts)
       (assoc :session-fs (:session-fs opts))
       (:on-github-telemetry opts)
       (assoc :on-github-telemetry (:on-github-telemetry opts))))))

(defn state
  "Get the current connection state."
  [client]
  (:status @(:state client)))

(defn options
  "Get the client options that were used to create this client.
   Returns the user-provided options merged with defaults.
   Note: This reflects SDK configuration, not necessarily server state."
  [client]
  (:options @(:state client)))

(declare stop!)
(declare start!)
(declare force-stop!)

(declare purge-all-github-token-provider-resources!)
(declare maybe-reconnect!)
(declare negotiated-protocol-version)

;; ---------------------------------------------------------------------------
;; Protocol v3 broadcast event handlers
;; ---------------------------------------------------------------------------

(defn- handle-v3-tool-requested!
  "Handle v3 external_tool.requested broadcast event.
   When a tool handler is registered for `tool-name`, calls it and
   responds via `session.tools.handlePendingToolCall`.
   When no handler is registered (upstream PR #1308 — declaration-only tool),
   leaves the call PENDING — the consumer is expected to resolve it via
   `handle-pending-tool-call!`."
  [client session-id event]
  (let [data (:data event)
        request-id (:request-id data)
        tool-name (:tool-name data)
        tool-call-id (:tool-call-id data)
        arguments (:arguments data)
        traceparent (:traceparent data)
        tracestate (:tracestate data)
        ;; Upstream PR #1308: skip auto-resolution for declaration-only tools.
        handler-registered? (some? (get-in @(:state client)
                                           [:sessions session-id :tool-handlers tool-name]))]
    (when (and request-id tool-name handler-registered?)
      (go
        (try
          (let [tool-response (<! (session/handle-tool-call!
                                   client session-id tool-call-id tool-name arguments
                                   :traceparent traceparent :tracestate tracestate))
                result (:result tool-response)
                conn (:connection-io @(:state client))]
            (when conn
              (<! (proto/send-request conn "session.tools.handlePendingToolCall"
                                      {:session-id session-id
                                       :request-id request-id
                                       :result result}))))
          (catch Exception e
            (log/debug "v3 tool call error for " request-id ": " (ex-message e))
            (try
              (let [conn (:connection-io @(:state client))]
                (when conn
                  (<! (proto/send-request conn "session.tools.handlePendingToolCall"
                                          {:session-id session-id
                                           :request-id request-id
                                           :result {:text-result-for-llm
                                                    "Invoking this tool produced an error."
                                                    :result-type "failure"
                                                    :error (ex-message e)
                                                    :tool-telemetry {}}}))))
              (catch Exception _ nil))))))))

(defn- handle-v3-permission-requested!
  "Handle v3 permission.requested broadcast event.
   When a permission handler is registered, calls it and responds via
   `session.permissions.handlePendingPermissionRequest`. When no handler is
   registered (upstream PR #1308 — optional callbacks), leaves the request
   PENDING for the consumer to resolve via `handle-pending-permission-request!`.
   When the handler returns :no-result, the RPC call is skipped so the
   extension does not answer this permission request.
   When :resolved-by-hook is true, the runtime already resolved
   this permission via a permissionRequest hook — skip the handler
   entirely (the event is still published to subscribers)."
  [client session-id event]
  (let [data (:data event)
        request-id (:request-id data)
        permission-request (:permission-request data)
        ;; Upstream PR #1308: skip auto-resolution when no permission handler.
        handler-registered? (some? (get-in @(:state client)
                                           [:sessions session-id :permission-handler]))]
    (when (and request-id permission-request
               handler-registered?
               (not (:resolved-by-hook data)))
      (go
        (try
          (let [perm-response (<! (session/handle-permission-request!
                                   client session-id permission-request))
                result (:result perm-response)]
            ;; :no-result — extension declines to answer; skip the RPC call
            (when-not (= :no-result result)
              (let [conn (:connection-io @(:state client))]
                (when conn
                  (<! (proto/send-request conn "session.permissions.handlePendingPermissionRequest"
                                          (cond-> {:session-id session-id
                                                   :request-id request-id
                                                   :result result}
                                            (contains? perm-response :decision-context)
                                            (assoc :decision-context
                                                   (:decision-context
                                                    perm-response)))))))))
          (catch Exception e
            (log/debug "v3 permission request error for " request-id ": " (ex-message e))
            (try
              (let [conn (:connection-io @(:state client))]
                (when conn
                  (<! (proto/send-request conn "session.permissions.handlePendingPermissionRequest"
                                          {:session-id session-id
                                           :request-id request-id
                                           :result {:kind :user-not-available}}))))
              (catch Exception _ nil))))))))

(defn- handle-v3-command-execute!
  "Handle v3 command.execute broadcast event.
   Calls the session's command handler and responds via the
   session.commands.handlePendingCommand RPC method."
  [client session-id event]
  (let [data (:data event)
        request-id (:request-id data)
        command-name (:command-name data)
        command (:command data)
        args (:args data)]
    (when (and request-id command-name)
      (go
        (try
          (let [cmd-response (<! (session/handle-command-execute!
                                  client session-id
                                  {:command-name command-name
                                   :command command
                                   :args args}))]
            (let [conn (:connection-io @(:state client))]
              (when conn
                (if (:error cmd-response)
                  (<! (proto/send-request conn "session.commands.handlePendingCommand"
                                          {:session-id session-id
                                           :request-id request-id
                                           :error (:error cmd-response)}))
                  (<! (proto/send-request conn "session.commands.handlePendingCommand"
                                          {:session-id session-id
                                           :request-id request-id}))))))
          (catch Exception e
            (log/debug "v3 command execute error for " request-id ": " (ex-message e))
            (try
              (let [conn (:connection-io @(:state client))]
                (when conn
                  (<! (proto/send-request conn "session.commands.handlePendingCommand"
                                          {:session-id session-id
                                           :request-id request-id
                                           :error (ex-message e)}))))
              (catch Exception _ nil))))))))

(defn- handle-v3-elicitation-requested!
  "Handle v3 elicitation.requested broadcast event.
   Builds an ElicitationContext (single-arg, includes session-id) and calls
   the session's elicitation handler. Responds via handlePendingElicitation RPC.
   If the handler fails, sends a cancel response to avoid hanging."
  [client session-id event]
  (let [data (:data event)
        request-id (:request-id data)]
    (when request-id
      (go
        (try
          (let [context (cond-> {:session-id session-id
                                 :message (:message data)}
                          (some? (:requested-schema data)) (assoc :requested-schema (:requested-schema data))
                          (some? (:mode data)) (assoc :mode (:mode data))
                          (some? (:elicitation-source data)) (assoc :elicitation-source (:elicitation-source data))
                          (some? (:url data)) (assoc :url (:url data)))
                result (<! (session/handle-elicitation-request! client session-id context))]
            (when result
              (let [conn (:connection-io @(:state client))]
                (when conn
                  (<! (proto/send-request conn "session.ui.handlePendingElicitation"
                                          {:session-id session-id
                                           :request-id request-id
                                           :result
                                           (cond-> {:action (:action result)}
                                             (some? (:content result))
                                             (assoc :content (:content result)))}))))))
          (catch Exception e
            (log/debug "v3 elicitation request error for " request-id ": " (ex-message e))
            (try
              (let [conn (:connection-io @(:state client))]
                (when conn
                  (<! (proto/send-request conn "session.ui.handlePendingElicitation"
                                          {:session-id session-id
                                           :request-id request-id
                                           :result {:action "cancel"}}))))
              (catch Exception _ nil))))))))

(defn- handle-v3-mcp-auth-required!
  "Handle v3 mcp.oauth_required broadcast event (upstream PR #1669).
   When an :on-mcp-auth-request handler is registered, invokes it and responds
   via `session.mcp.oauth.handlePendingRequest`. When no handler is registered,
   leaves the request PENDING — the runtime falls back to a browserless cached
   token. The event is published to subscribers either way. Mirrors upstream
   session.ts: a request without a :request-id is ignored."
  [client session-id event]
  (let [data (:data event)
        request-id (:request-id data)
        handler-registered? (some? (get-in @(:state client)
                                           [:sessions session-id :mcp-auth-handler]))]
    (when (and request-id handler-registered?)
      (go
        (try
          (let [result (<! (session/handle-mcp-auth-request! client session-id data))
                conn (:connection-io @(:state client))]
            (when conn
              (<! (proto/send-request conn "session.mcp.oauth.handlePendingRequest"
                                      {:session-id session-id
                                       :request-id request-id
                                       :result result}))))
          (catch Exception e
            (log/debug "v3 mcp auth request error for " request-id ": " (ex-message e))
            (try
              (let [conn (:connection-io @(:state client))]
                (when conn
                  (<! (proto/send-request conn "session.mcp.oauth.handlePendingRequest"
                                          {:session-id session-id
                                           :request-id request-id
                                           :result {:kind "cancelled"}}))))
              (catch Exception _ nil))))))))

(defn- handle-v3-broadcast-event!
  "Protocol v3: intercept broadcast events for external tools, permissions,
   commands, elicitation, and capabilities.
   In v3, tool.call and permission.request server→client RPC methods are replaced
   by broadcast events that the SDK handles and responds to via new RPC methods."
  [client session-id event]
  (let [event-type (:type event)]
    (case event-type
      :copilot/external_tool.requested
      (handle-v3-tool-requested! client session-id event)

      :copilot/permission.requested
      (handle-v3-permission-requested! client session-id event)

      :copilot/command.execute
      (handle-v3-command-execute! client session-id event)

      :copilot/elicitation.requested
      (handle-v3-elicitation-requested! client session-id event)

      :copilot/mcp.oauth_required
      (handle-v3-mcp-auth-required! client session-id event)

      ;; capabilities.changed state update is applied before event publish
      ;; in the notification router (so observers see consistent state)
      :copilot/capabilities.changed nil

      nil)))

(defn- start-notification-router!
  "Route notifications to appropriate sessions."
  [client]
  (let [{:keys [connection-io]} @(:state client)
        notif-ch (proto/notifications connection-io)
        router-ch (chan 1024)
        ;; Serial lifecycle dispatch: a single per-client worker thread drains
        ;; this channel and invokes registered lifecycle handlers off the
        ;; notification router's go-loop. This keeps a slow or blocking handler
        ;; from stalling ALL notification routing (issue #126), while the single
        ;; worker preserves in-order, one-at-a-time delivery. A sliding buffer
        ;; bounds memory: if handlers cannot keep up, oldest lifecycle events are
        ;; dropped rather than growing without limit. The worker snapshots the
        ;; handler map per event at dispatch time, so a handler
        ;; registered/unregistered mid-stream affects every event dispatched
        ;; after the change — including events already queued but not yet drained.
        lifecycle-ch (chan (async/sliding-buffer 1024))
        queue-size (or (:router-queue-size (:options client)) 4096)
        router-queue (LinkedBlockingQueue. queue-size)
        router-thread (Thread.
                       (fn []
                         (log/debug "Notification router dispatcher started")
                         (try
                           (loop []
                             (when (:router-running? @(:state client))
                               (when-let [notif (.poll router-queue 100 java.util.concurrent.TimeUnit/MILLISECONDS)]
                                 (>!! router-ch notif))
                               (recur)))
                           (catch InterruptedException _
                             (log/debug "Notification router dispatcher interrupted"))
                           (catch Exception e
                             (log/error "Notification router dispatcher exception: " (ex-message e)))
                           (finally
                             (log/debug "Notification router dispatcher ending")))))]
    ;; Serial lifecycle worker — drains lifecycle-ch and dispatches to handlers.
    ;; Observes lifecycle-ch closure between events (at the next <!!), so it
    ;; winds down on teardown; a handler that blocks indefinitely can still keep
    ;; this thread parked in that handler until it returns.
    (async/thread
      (loop []
        (when-let [lifecycle-event (async/<!! lifecycle-ch)]
          (let [event-type-kw (:lifecycle-event-type lifecycle-event)
                handlers (vals (:lifecycle-handlers @(:state client)))]
            (doseq [{:keys [handler event-type]} handlers]
              (when (or (nil? event-type) (= event-type event-type-kw))
                (try
                  (handler lifecycle-event)
                  (catch Throwable e
                    (log/error "Lifecycle handler error: " (ex-message e)))))))
          (recur))))
    ;; Store the router channel
    (swap! (:state client) assoc
           :router-ch router-ch
           :router-queue router-queue
           :router-thread router-thread
           :lifecycle-ch lifecycle-ch
           :router-running? true)
    (.setDaemon router-thread true)
    (.setName router-thread "notification-router-dispatcher")
    (.start router-thread)

    ;; Simple routing - read from notification-chan and dispatch
    (go-loop []
      (if-let [notif (<! notif-ch)]
        (do
          (case (:method notif)
            "session.event"
            (let [{:keys [session-id event]} (:params notif)
                  ;; Apply wire→idiom coercion + :type normalization with
                  ;; fail-open semantics. Shared with `session/get-messages`
                  ;; via `session/coerce+normalize-event` so live and
                  ;; historical events have identical shape and error
                  ;; behavior. Coercion is keyed by the original wire-shape
                  ;; `:type` string (e.g. "session.start") and runs BEFORE
                  ;; type normalization to keyword.
                  normalized-event (session/coerce+normalize-event event session-id)
                  event-type (:type normalized-event)]
              (log/debug "Routing event to session " session-id ": type=" event-type)
              ;; Validate model selection on session.start
              (when (= event-type :copilot/session.start)
                (let [selected-model (get-in normalized-event [:data :selected-model])
                      requested-model (get-in @(:state client) [:sessions session-id :config :model])]
                  (when (and requested-model selected-model
                             (not= requested-model selected-model))
                    (log/warn "Model mismatch for session " session-id
                              ": requested " requested-model ", server selected " selected-model))))
              (when-not (:destroyed? (get-in @(:state client) [:sessions session-id]))
                ;; Canvas state (upstream PR #1604) — apply at all protocol
                ;; versions, before publishing, so observers see a consistent
                ;; snapshot. These are session events, not v3 broadcasts.
                (case event-type
                  :copilot/session.canvas.opened
                  (session/upsert-open-canvas! client session-id (:data normalized-event))
                  :copilot/session.canvas.closed
                  (session/remove-open-canvas! client session-id (:data normalized-event))
                  nil)
                ;; Protocol v3: apply state-mutating broadcast handlers before publishing,
                ;; so event observers see consistent state (e.g. capabilities.changed)
                (when (>= (negotiated-protocol-version client) 3)
                  (when (= event-type :copilot/capabilities.changed)
                    (session/update-capabilities! client session-id (:data normalized-event))))
                (when-let [{:keys [event-chan]} (get-in @(:state client) [:session-io session-id])]
                  (>! event-chan normalized-event))
                ;; Protocol v3: handle broadcast events for tools, permissions, elicitation
                (when (>= (negotiated-protocol-version client) 3)
                  (handle-v3-broadcast-event! client session-id normalized-event))))

            "session.lifecycle"
            (let [params (util/wire->clj (:params notif))
                  event-type-str (:type params)
                  event-type-kw (when event-type-str (keyword event-type-str))
                  lifecycle-event (-> params
                                      (dissoc :type)
                                      (assoc :lifecycle-event-type event-type-kw))
                  dispatch-inline!
                  (fn []
                    (doseq [{:keys [handler event-type]} (vals (:lifecycle-handlers @(:state client)))]
                      (when (or (nil? event-type) (= event-type event-type-kw))
                        (try
                          (handler lifecycle-event)
                          (catch Throwable e
                            (log/error "Lifecycle handler error: " (ex-message e)))))))]
              (log/debug "Lifecycle event: " event-type-kw " session=" (:session-id lifecycle-event))
              ;; Hand off to the serial lifecycle worker so a slow/blocking
              ;; handler can't stall notification routing (issue #126). Fall
              ;; back to inline dispatch when the worker channel is absent (not
              ;; yet started / torn down) OR already closed — `async/put!`
              ;; returns false on a closed channel, so honoring its result
              ;; keeps a dropped hand-off from silently losing the event.
              (let [lifecycle-ch (:lifecycle-ch @(:state client))]
                (when-not (and lifecycle-ch (async/put! lifecycle-ch lifecycle-event))
                  (dispatch-inline!))))

            ;; GitHub telemetry forwarding (upstream PR #1835, @experimental).
            ;; Client-global, id-less notification. `:params` is already
            ;; idiom-shaped with opaque `:properties`/`:metrics`/`:features`
            ;; preserved verbatim by `protocol/normalize-incoming`. Invoke the
            ;; registered callback in try/catch so a throwing consumer can't
            ;; corrupt JSON-RPC dispatch. Not surfaced on the public
            ;; `notifications` channel (mirrors Node/Python).
            "gitHubTelemetry.event"
            (when-let [handler (:on-github-telemetry client)]
              (try
                (handler (:params notif))
                (catch Throwable e
                  (log/warn "Error handling gitHubTelemetry.event notification: "
                            (ex-message e)))))

            ;; default: other notifications go to the router queue
            (when-not (.offer router-queue notif)
              (log/debug "Dropping notification due to full router queue")))
          (recur))
        (do
          (log/debug "Notification channel closed")
          (purge-all-github-token-provider-resources! client)
          (maybe-reconnect! client "connection-closed"))))))

(defn notifications
  "Get the channel that receives non-session notifications.
   Notifications are dropped if the channel is full."
  [client]
  (:router-ch @(:state client)))

(let [counter (atom 0)]
  (defn on-lifecycle-event
    "Subscribe to session lifecycle events.

     Two arities:
     (on-lifecycle-event client handler)
       Subscribe to ALL lifecycle events. Handler receives the full event map
       with keys :lifecycle-event-type, :session-id, and optionally :metadata.

     (on-lifecycle-event client event-type handler)
       Subscribe to a specific event type only.
       event-type is a keyword like :session.created, :session.deleted, etc.

     Returns an unsubscribe function (call with no args to remove the handler)."
    ([client handler]
     (let [id (keyword (str "lh-" (swap! counter inc)))]
       (swap! (:state client) assoc-in [:lifecycle-handlers id]
              {:handler handler :event-type nil})
       (fn [] (swap! (:state client) update :lifecycle-handlers dissoc id))))
    ([client event-type handler]
     (let [id (keyword (str "lh-" (swap! counter inc)))]
       (swap! (:state client) assoc-in [:lifecycle-handlers id]
              {:handler handler :event-type event-type})
       (fn [] (swap! (:state client) update :lifecycle-handlers dissoc id))))))

(defn- maybe-reconnect!
  "No-op: auto-restart is deprecated and has no effect (upstream PR #803)."
  [_client _reason]
  nil)

(defn- watch-process-exit!
  "Trigger auto-restart when the managed CLI process exits."
  [client mp]
  (when-let [exit-ch (:exit-chan mp)]
    (go
      (when-let [{:keys [exit-code]} (<! exit-ch)]
        (let [stopping? (:stopping? @(:state client))]
          (if stopping?
            (log/debug "CLI process exited with code" exit-code "(expected during stop)")
            (log/warn "CLI process exited with code" exit-code))
          (maybe-reconnect! client (str "cli-process-exit-" exit-code))
          (when stopping?
            (swap! (:state client) assoc :stopping? false)))))))

(def ^:private stderr-buffer-max-lines
  "Maximum number of stderr lines to retain for error context."
  100)

(defn- start-stderr-forwarder!
  "Start reading stderr from the CLI process, forwarding lines to logging
   and capturing recent output for error context. Returns the stderr buffer atom."
  [client mp]
  (let [stderr-buf (atom [])
        stderr-ch (proc/stderr-reader mp)]
    (swap! (:state client) assoc :stderr-buffer stderr-buf)
    (go-loop []
      (when-let [{:keys [line]} (<! stderr-ch)]
        (log/debug "[cli-stderr]" line)
        (swap! stderr-buf (fn [buf]
                            (let [buf (conj buf line)]
                              (if (> (count buf) stderr-buffer-max-lines)
                                (subvec buf (- (count buf) stderr-buffer-max-lines))
                                buf))))
        (recur)))
    stderr-buf))

(defn- get-stderr-output
  "Get captured stderr output as a single string, or nil if empty."
  [client]
  (when-let [buf (:stderr-buffer @(:state client))]
    (let [lines @buf]
      (when (seq lines)
        (str/join "\n" lines)))))

(defn- delivered-chan
  "A channel already holding `v`, matching the reverse-request handler contract
   `(fn [method params] -> channel)`."
  [v]
  (doto (chan 1) (async/put! v) (async/close!)))

(defn- config-validation-message
  [base-message config]
  (if (util/github-token-auth-conflict? config)
    (str base-message
         ": :github-token and :github-token-provider are mutually exclusive")
    base-message))

(defn- register-github-token-provider!
  [client provider session-id]
  (when provider
    (let [registration-id (str (java.util.UUID/randomUUID))]
      (swap! (:state client) assoc-in
             [:github-token-providers registration-id]
             {:provider provider
              :session-id session-id
              :committed? false})
      registration-id)))

(defn- register-session-github-token-provider
  [client config session-id]
  (let [registration-id
        (register-github-token-provider!
         client (:github-token-provider config) session-id)]
    [(cond-> config
       registration-id
       (assoc :github-token-provider-registration-id registration-id))
     registration-id]))

(defn- assign-github-token-provider!
  [client registration-id session-id]
  (when registration-id
    (swap! (:state client)
           (fn [state]
             (if (get-in state [:github-token-providers registration-id])
               (assoc-in state
                         [:github-token-providers registration-id :session-id]
                         session-id)
               state)))))

(defn- close-removed-github-token-provider-invocations!
  [old-state new-state]
  (session/close-removed-github-token-provider-invocations!
   old-state new-state))

(defn- purge-all-github-token-provider-resources!
  [client]
  (let [[old-state new-state]
        (swap-vals! (:state client)
                    session/purge-all-github-token-provider-resources)]
    (close-removed-github-token-provider-invocations!
     old-state new-state)))

(defn- commit-github-token-provider!
  [client session-id registration-id]
  (let [[old-state new-state]
        (swap-vals!
         (:state client)
         (fn [state]
           (if registration-id
             (if-let [registration
                      (get-in state [:github-token-providers registration-id])]
               (-> (session/purge-github-token-provider-resources
                    state session-id :committed-only)
                   (assoc-in
                    [:github-token-providers registration-id]
                    (assoc registration
                           :session-id session-id
                           :committed? true)))
               state)
             (session/purge-github-token-provider-resources
              state session-id :committed-only))))]
    (close-removed-github-token-provider-invocations!
     old-state new-state)))

(defn- rollback-github-token-provider!
  [client registration-id]
  (when registration-id
    (let [[old-state new-state]
          (swap-vals!
           (:state client)
           session/purge-github-token-provider-registration
           registration-id)]
      (close-removed-github-token-provider-invocations!
       old-state new-state))))

(defn- create-github-token-provider-executor
  []
  (let [thread-sequence (AtomicLong.)
        thread-factory
        (reify ThreadFactory
          (newThread [_ task]
            (doto
             (Thread. ^Runnable task
                      (str "copilot-github-token-provider-"
                           (.incrementAndGet thread-sequence)))
              (.setDaemon true))))]
    (ThreadPoolExecutor.
     github-token-provider-thread-count
     github-token-provider-thread-count
     0
     TimeUnit/MILLISECONDS
     (LinkedBlockingQueue. github-token-provider-queue-size)
     thread-factory
     (ThreadPoolExecutor$AbortPolicy.))))

(defn- github-token-provider-executor!
  [client generation]
  (let [state-atom (:state client)]
    (loop []
      (let [state @state-atom]
        (cond
          (not= generation (:github-token-provider-runtime-generation state))
          nil

          (:github-token-provider-executor state)
          (:github-token-provider-executor state)

          :else
          (let [executor (create-github-token-provider-executor)
                next-state (assoc state :github-token-provider-executor executor)]
            (if (compare-and-set! state-atom state next-state)
              executor
              (do
                (.shutdownNow ^ThreadPoolExecutor executor)
                (recur)))))))))

(defn- release-github-token-provider-runtime!
  [client]
  (let [[old-state new-state]
        (swap-vals!
         (:state client)
         (fn [state]
           (-> (session/purge-all-github-token-provider-resources state)
               (assoc :github-token-provider-executor nil
                      :github-token-provider-saturation-count 0)
               (update :github-token-provider-runtime-generation inc))))
        executor (:github-token-provider-executor old-state)]
    (close-removed-github-token-provider-invocations! old-state new-state)
    (if-not executor
      []
      (td/collect
       [(td/attempt
         {:operation :terminate
          :resource :github-token-provider-executor
          :timeout-ms github-token-provider-shutdown-timeout-ms}
         (.shutdownNow ^ThreadPoolExecutor executor)
         (when-not
          (.awaitTermination
           ^ThreadPoolExecutor executor
           github-token-provider-shutdown-timeout-ms
           TimeUnit/MILLISECONDS)
           (throw
            (IllegalStateException.
             "GitHub token provider executor did not terminate"))))]))))

(defn- begin-github-token-provider-invocation!
  [client {:keys [registration-id host session-id reason] :as request}]
  (let [invocation-id (str (java.util.UUID/randomUUID))
        cancel-chan (chan)
        cancelled? (atom false)
        task (atom nil)]
    (loop []
      (let [state-atom (:state client)
            state @state-atom
            {:keys [provider] :as registration}
            (get-in state [:github-token-providers registration-id])]
        (when-not registration
          (throw
           (ex-info
            "No GitHub token provider registered"
            {:type :github-token-provider-registration-not-found
             :registration-id registration-id})))
        (let [registered-session-id (:session-id registration)
              supplied-session-id? (contains? request :session-id)
              effective-session-id (if supplied-session-id?
                                     session-id
                                     registered-session-id)
              args (cond-> {:host host
                            :reason (when (or (string? reason)
                                              (keyword? reason))
                                      (keyword reason))}
                     (or supplied-session-id? registered-session-id)
                     (assoc :session-id effective-session-id))]
          (when-not (s/valid? ::specs/github-token-provider-args args)
            (throw
             (ex-info
              "Invalid GitHub token provider request"
              {:type :github-token-provider-invalid-request
               :registration-id registration-id
               :session-id (or registered-session-id
                               effective-session-id)})))
          (when (and registered-session-id
                     supplied-session-id?
                     (not= registered-session-id session-id))
            (throw
             (ex-info
              "GitHub token provider request session does not match registration"
              {:type :github-token-provider-session-mismatch
               :registration-id registration-id
               :session-id registered-session-id})))
          (let [invocation
                {:registration-id registration-id
                 :session-id effective-session-id
                 :host host
                 :reason (:reason args)
                 :generation
                 (:github-token-provider-runtime-generation state)
                 :cancel-chan cancel-chan
                 :cancelled? cancelled?
                 :task task}
                next-state
                (assoc-in state
                          [:github-token-provider-invocations invocation-id]
                          invocation)]
            (if (compare-and-set! state-atom state next-state)
              {:args args
               :provider provider
               :invocation-id invocation-id
               :invocation invocation}
              (recur))))))))

(defn- record-github-token-provider-saturation!
  [client generation]
  (let [state-atom (:state client)]
    (loop []
      (let [state @state-atom]
        (when (= generation
                 (:github-token-provider-runtime-generation state))
          (let [count (min github-token-provider-saturation-count-max
                           (inc (:github-token-provider-saturation-count state)))
                next-state
                (assoc state :github-token-provider-saturation-count count)]
            (if (compare-and-set! state-atom state next-state)
              count
              (recur))))))))

(defn- github-token-provider-log-metadata
  [{:keys [registration-id session-id host reason]}]
  {:registration-id registration-id
   :session-id session-id
   :host host
   :reason reason})

(defn- throwable-class-name
  [failure]
  (some-> failure class .getName))

(defn- bounded-executor-facts
  [^ThreadPoolExecutor executor]
  {:active-count (min github-token-provider-thread-count
                      (.getActiveCount executor))
   :pool-size (min github-token-provider-thread-count
                   (.getPoolSize executor))
   :queue-depth (min github-token-provider-queue-size
                     (.size (.getQueue executor)))
   :thread-limit github-token-provider-thread-count
   :queue-limit github-token-provider-queue-size})

(defn- claim-github-token-provider-invocation!
  [client invocation-id]
  (let [[old-state _]
        (swap-vals! (:state client)
                    update
                    :github-token-provider-invocations
                    dissoc
                    invocation-id)]
    (get-in old-state
            [:github-token-provider-invocations invocation-id])))

(defn- invoke-github-token-provider
  [provider args cancel-chan]
  (try
    (let [returned (provider args)
          [result port]
          (if (satisfies? async-protocols/ReadPort returned)
            (async/alts!! [returned cancel-chan] :priority true)
            [returned nil])]
      (cond
        (identical? port cancel-chan)
        {:outcome :cancelled}

        (instance? Throwable result)
        {:outcome :failed :failure result}

        :else
        {:outcome :returned :result result}))
    (catch Throwable failure
      (when (instance? InterruptedException failure)
        (.interrupt (Thread/currentThread)))
      {:outcome :failed :failure failure})))

(defn- provider-cancelled-response
  []
  {:result {:kind :cancelled}})

(defn- provider-failure-response
  [message]
  {:error {:code -32603
           :message (str "Internal error: " message)}})

(defn- attach-github-token-provider-task!
  [{:keys [cancelled? task]} future]
  (reset! task future)
  (when @cancelled?
    (.cancel ^java.util.concurrent.Future future true)))

(defn- github-token-provider-outcome-response
  [client invocation-id invocation {:keys [outcome result failure]}]
  (if (or @(:cancelled? invocation)
          (= :cancelled outcome)
          (nil? (claim-github-token-provider-invocation!
                 client invocation-id)))
    (provider-cancelled-response)
    (case outcome
      :failed
      (do
        (log/warn
         "GitHub token provider callback failed"
         (assoc
          (github-token-provider-log-metadata invocation)
          :exception-class (throwable-class-name failure)
          :cause-class (throwable-class-name (.getCause ^Throwable failure))))
        (provider-failure-response
         "GitHub token provider callback failed"))

      :returned
      (if-let [constraint
               (specs/github-token-provider-result-constraint result)]
        (do
          (log/warn
           "GitHub token provider returned an invalid result"
           (assoc
            (github-token-provider-log-metadata invocation)
            :constraint constraint))
          (provider-failure-response
           "GitHub token provider returned an invalid result"))
        {:result result}))))

(defn- github-token-provider-response
  [client request]
  (let [{:keys [args provider invocation-id invocation]}
        (begin-github-token-provider-invocation! client request)
        result-chan (chan 1)
        response-chan (chan 1)
        await-result?
        (if-let [executor
                 (github-token-provider-executor!
                  client (:generation invocation))]
          (try
            (let [future
                  (.submit
                   ^ThreadPoolExecutor executor
                   ^Runnable
                   (reify Runnable
                     (run [_]
                       (>!!
                        result-chan
                        (invoke-github-token-provider
                         provider args (:cancel-chan invocation))))))]
              (attach-github-token-provider-task! invocation future)
              true)
            (catch RejectedExecutionException _
              (let [claimed
                    (claim-github-token-provider-invocation!
                     client invocation-id)
                    saturation-count
                    (when (and claimed
                               (not @(:cancelled? invocation)))
                      (record-github-token-provider-saturation!
                       client (:generation invocation)))
                    cancelled? (nil? saturation-count)]
                (when-not cancelled?
                  (log/warn
                   "GitHub token provider executor saturated"
                   (merge
                    (github-token-provider-log-metadata invocation)
                    (bounded-executor-facts executor)
                    {:saturation-count saturation-count})))
                (async/put!
                 response-chan
                 (if cancelled?
                   (provider-cancelled-response)
                   {:error
                    {:code -32000
                     :message
                     "GitHub token provider executor saturated"}}))
                (close! response-chan)
                false)))
          (do
            (claim-github-token-provider-invocation!
             client invocation-id)
            (async/put! response-chan (provider-cancelled-response))
            (close! response-chan)
            false))]
    (when await-result?
      (go
        (let [[outcome port]
              (async/alts!
               [result-chan (:cancel-chan invocation)]
               :priority true)
              response
              (if (identical? port (:cancel-chan invocation))
                (do
                  (claim-github-token-provider-invocation!
                   client invocation-id)
                  (provider-cancelled-response))
                (github-token-provider-outcome-response
                 client invocation-id invocation outcome))]
          (>! response-chan response)
          (close! response-chan))))
    response-chan))

(defn- setup-request-handler!
  "Set up handler for incoming requests (hooks, user input, sessionFs, etc.).

   Protocol v3 (the only supported version, see [[sdk-protocol-version-min]])
   delivers tool calls and permission requests as broadcast events on
   `session.event` — see [[handle-v3-tool-requested!]] and
   [[handle-v3-permission-requested!]] — not as server→client RPCs. The v2
   `tool.call` / `permission.request` dispatcher cases were removed
   alongside upstream PR #1378.

   This is a plain dispatch function, not a `go` block: `protocol` invokes it on
   its bounded reverse-request worker pool, so branches that do ordinary
   blocking work (e.g. `systemMessage.transform` callbacks) run under that
   bound instead of on core.async dispatch. Branches that delegate to a
   `session/handle-*` function return its channel directly rather than parking
   on it."
  [client]
  (let [{:keys [connection-io]} @(:state client)
        session? (fn [session-id] (get-in @(:state client) [:sessions session-id]))
        unknown-session (fn [session-id]
                          (delivered-chan
                           {:error {:code -32001 :message (str "Unknown session: " session-id)}}))]
    (proto/set-request-handler!
     connection-io
     (fn [method params]
       (case method
         ;; Exit Plan Mode request (upstream PR #1228)
         "exitPlanMode.request"
         (let [{:keys [session-id]} params
               request (dissoc params :session-id)]
           (if-not (session? session-id)
             (unknown-session session-id)
             (session/handle-exit-plan-mode-request! client session-id request)))

         ;; Auto Mode Switch request (upstream PR #1228)
         "autoModeSwitch.request"
         (let [{:keys [session-id]} params
               request (dissoc params :session-id)]
           (if-not (session? session-id)
             (unknown-session session-id)
             (session/handle-auto-mode-switch-request! client session-id request)))

         ;; User input request (PR #269)
         "userInput.request"
         (let [{:keys [session-id question choices allow-freeform]} params]
           (log/debug "User input request for session " session-id ": " question)
           (if-not (session? session-id)
             (unknown-session session-id)
             (session/handle-user-input-request! client session-id
                                                 {:question question
                                                  :choices choices
                                                  :allow-freeform allow-freeform})))

         ;; Bearer-token provider request (PR #1748)
         "providerToken.getToken"
         (let [{:keys [session-id provider-name]} params]
           (if-not (session? session-id)
             (unknown-session session-id)
             (session/handle-provider-token-request! client session-id provider-name)))

          ;; Session-scoped GitHub token acquisition (upstream PR #2412). This
          ;; is client-global because cloud creation may request a token before
          ;; the runtime has assigned a session ID.
         "gitHubToken.getToken"
         (github-token-provider-response client params)

         ;; Hooks invocation (PR #269)
         "hooks.invoke"
         (let [{:keys [session-id hook-type input]} params]
           (if-not (session? session-id)
             (unknown-session session-id)
             (session/handle-hooks-invoke! client session-id hook-type input)))

         "factory.execute"
         (let [{:keys [session-id]} params]
           (if-not (session? session-id)
             (unknown-session session-id)
             (session/handle-factory-execute! client session-id params)))

         "factory.abort"
         (let [{:keys [session-id run-id]} params]
           (if-not (session? session-id)
             (unknown-session session-id)
             (session/handle-factory-abort! client session-id run-id)))

         ;; System message transform (PR #816). Runs inline on the reverse-request
         ;; worker, so the registered transform callbacks are covered by the
         ;; handler concurrency bound.
         "systemMessage.transform"
         (let [{:keys [session-id sections]} params]
           (if-not (session? session-id)
             (unknown-session session-id)
             (delivered-chan
              {:result (session/handle-system-message-transform
                        client session-id sections)})))

         ;; SessionFs operations (upstream PR #917, sqlite added in #1299)
         ("sessionFs.readFile" "sessionFs.writeFile" "sessionFs.appendFile"
                               "sessionFs.exists" "sessionFs.stat" "sessionFs.mkdir"
                               "sessionFs.readdir" "sessionFs.readdirWithTypes"
                               "sessionFs.rm" "sessionFs.rename"
                               "sessionFs.sqliteQuery"
                               "sessionFs.sqliteTransaction"
                               "sessionFs.sqliteExists")
         (let [{:keys [session-id]} params]
           (if-not (session? session-id)
             (unknown-session session-id)
             (session/handle-session-fs-request! client session-id method params)))

         (delivered-chan
          {:error {:code -32601 :message (str "Unknown method: " method)}}))))))

(defn- connect-stdio!
  "Connect via stdio to the CLI process."
  [client]
  (let [{:keys [process]} @(:state client)]
    ;; Initialize connection state before connecting
    (swap! (:state client) assoc :connection (proto/initial-connection-state))
    (let [conn (proto/connect (:stdout process) (:stdin process) (:state client))]
      (swap! (:state client) assoc :connection-io conn))))

(defn- non-closing-input-stream
  "Wrap an InputStream so that .close is a no-op.
   Prevents proto/disconnect from closing System/in."
  ^java.io.InputStream [^java.io.InputStream in]
  (proxy [java.io.FilterInputStream] [in]
    (close [] nil)))

(defn- non-closing-output-stream
  "Wrap an OutputStream so that .close flushes but does not close.
   Prevents proto/disconnect from closing System/out while ensuring
   buffered bytes are sent."
  ^java.io.OutputStream [^java.io.OutputStream out]
  (proxy [java.io.FilterOutputStream] [out]
    (close [] (.flush ^java.io.OutputStream out))))

(defn- connect-parent-stdio!
  "Connect via own stdio to a parent Copilot CLI process (child process mode).
   Wraps System/in and System/out in non-closing wrappers so that
   proto/disconnect does not close the JVM's global stdio streams."
  [client]
  (swap! (:state client) assoc :connection (proto/initial-connection-state))
  (let [in  (non-closing-input-stream System/in)
        out (non-closing-output-stream System/out)
        conn (proto/connect in out (:state client))]
    (swap! (:state client) assoc :connection-io conn)))

(defn- connect-tcp!
  "Connect via TCP to the CLI server."
  [client]
  (let [host (:actual-host client)
        {:keys [actual-port]} @(:state client)
        socket (proc/connect-tcp host actual-port 10000)]
    ;; Transfer socket ownership before stream/protocol initialization so a
    ;; failure below remains recoverable through release-transport!.
    (swap! (:state client) assoc
           :connection (proto/initial-connection-state)
           :socket socket)
    (let [input (BufferedInputStream. (.getInputStream socket) 65536)
          conn (proto/connect input (.getOutputStream socket) (:state client))]
      (swap! (:state client) assoc :connection-io conn))))

(defn- verify-protocol-version!
  "Verify the server's protocol version matches ours.

   Sends the `connect` handshake (upstream PR #1176) carrying the optional
   tcp-connection-token, and races it against process exit to detect early
   CLI failures. If the server returns a JSON-RPC `MethodNotFound` (-32601)
   error, OR an error whose message is exactly \"Unhandled method connect\"
   (matching upstream Node parity, client.ts:1132-1135), falls back to `ping`
   for compatibility with legacy servers."
  [client]
  (let [{:keys [connection-io process options]} @(:state client)
        token (:tcp-connection-token options)
        ;; Opt in to GitHub telemetry forwarding at the connection level when a
        ;; handler is registered (upstream PR #1909). The runtime reads this flag
        ;; on the `connect` handshake so the first session's un-replayable
        ;; `session.start` telemetry is forwarded; it is also sent on
        ;; session.create/resume for older CLIs. csk camelCases "github" ->
        ;; "Github", so assoc the exact wire keyword (clj->wire is idempotent).
        connect-params (cond-> {}
                         token (assoc :token token)
                         (some? (:on-github-telemetry client))
                         (assoc :enableGitHubTelemetryForwarding true))
        exit-ch (:exit-chan process)
        timeout-ch (async/timeout 60000)
        await-rpc (fn [rpc-ch method-name]
                    (if exit-ch
                      (let [exit-poll (async/poll! exit-ch)]
                        (if exit-poll
                          (let [stderr (get-stderr-output client)]
                            (throw (ex-info
                                    (cond-> (str "CLI server exited with code " (:exit-code exit-poll))
                                      stderr (str "\nstderr: " stderr))
                                    {:exit-code (:exit-code exit-poll) :stderr stderr})))
                          (let [[v ch] (async/alts!! [rpc-ch exit-ch timeout-ch])]
                            (cond
                              (identical? ch timeout-ch)
                              (let [stderr (get-stderr-output client)]
                                (throw (ex-info
                                        (cond-> (str method-name " request timed out")
                                          stderr (str "\nstderr: " stderr))
                                        {:method method-name :timeout-ms 60000 :stderr stderr})))

                              (identical? ch rpc-ch)
                              v

                              :else
                              (let [stderr (get-stderr-output client)]
                                (throw (ex-info
                                        (cond-> (str "CLI server exited with code " (:exit-code v))
                                          stderr (str "\nstderr: " stderr))
                                        {:exit-code (:exit-code v) :stderr stderr})))))))
                      (let [[v ch] (async/alts!! [rpc-ch timeout-ch])]
                        (cond
                          (identical? ch timeout-ch)
                          (throw (ex-info (str method-name " request timed out")
                                          {:method method-name :timeout-ms 60000}))
                          :else v))))
        ;; Try `connect` first; on -32601 fall back to `ping`.
        connect-resp (await-rpc (proto/send-request connection-io "connect" connect-params)
                                "connect")
        result (cond
                 (nil? connect-resp)
                 (throw (ex-info "Connect channel closed unexpectedly" {:method "connect"}))

                 (and (:error connect-resp)
                      (or (= -32601 (get-in connect-resp [:error :code]))
                          ;; Some legacy servers return a non-MethodNotFound code
                          ;; but a message of exactly "Unhandled method connect".
                          ;; Match upstream Node parity (client.ts:1132-1135).
                          (= "Unhandled method connect"
                             (get-in connect-resp [:error :message]))))
                 ;; Legacy server without `connect`; fall back to `ping`. The
                 ;; token, if any, is silently dropped — the legacy server can't
                 ;; enforce one.
                 (let [ping-resp (await-rpc (proto/send-request connection-io "ping" {})
                                            "ping")]
                   (cond
                     (nil? ping-resp)
                     (throw (ex-info "Ping channel closed unexpectedly" {:method "ping"}))
                     (:error ping-resp)
                     (throw (ex-info (get-in ping-resp [:error :message] "RPC error")
                                     {:error (:error ping-resp) :method "ping"}))
                     :else (:result ping-resp)))

                 (:error connect-resp)
                 (throw (ex-info (get-in connect-resp [:error :message] "RPC error")
                                 {:error (:error connect-resp) :method "connect"}))

                 :else (:result connect-resp))
        server-version (:protocol-version result)]
    (when (nil? server-version)
      (throw (ex-info
              (str "SDK protocol version mismatch: SDK supports versions "
                   sdk-protocol-version-min "-" sdk-protocol-version-max
                   ", but server does not report a protocol version.")
              {:min sdk-protocol-version-min :max sdk-protocol-version-max :actual nil})))
    (when (or (< server-version sdk-protocol-version-min)
              (> server-version sdk-protocol-version-max))
      (throw (ex-info
              (str "SDK protocol version mismatch: SDK supports versions "
                   sdk-protocol-version-min "-" sdk-protocol-version-max
                   ", but server reports version " server-version)
              {:min sdk-protocol-version-min :max sdk-protocol-version-max :actual server-version})))
    ;; Store the negotiated version for conditional v2/v3 behavior
    (swap! (:state client) assoc :negotiated-protocol-version server-version)))

(defn- negotiated-protocol-version
  "Get the negotiated protocol version from client state."
  [client]
  (:negotiated-protocol-version @(:state client) 0))

;; ---------------------------------------------------------------------------
;; Permission helpers
;; ---------------------------------------------------------------------------

(defn attributed-permission-result?
  "Return true when `result` is a well-formed attributed permission result."
  [result]
  (s/valid? ::specs/attributed-permission-result result))

(defn attributed-permission-result
  "Attach informational decision context to a permission-handler result.

   `result` is a permission decision such as `{:kind :approve-once}`. If it is
   already attributed, the existing context is replaced rather than nested.
   `decision-context` contains `:outcome`, `:source`, and `:surface`; see
   `github.copilot-sdk.specs/permission-decision-context` for allowed values."
  [result decision-context]
  {:kind :attributed
   :result (if (attributed-permission-result? result)
             (:result result)
             result)
   :decision-context decision-context})

(defn approve-all
  "Permission handler that approves all permission requests.

  The SDK uses a **deny-by-default** permission model: all permission requests
  (file writes, shell commands, URL fetches, etc.) are denied unless your
  session config provides an `:on-permission-request` handler.

  Use `approve-all` to opt into approving everything (equivalent to the
  upstream `approveAll` export):

    (copilot/create-session client {:on-permission-request copilot/approve-all})

  Throws when managed settings are active because host-side auto-approval
  cannot bypass managed policy. Returns `{:kind :no-result}` when a request
  explicitly requires managed approval; otherwise returns
  `{:kind :approve-once}`.

  For fine-grained control, provide your own handler function instead."
  [request ctx]
  (when (:managed-settings-enabled? ctx)
    (throw (ex-info "approve-all cannot be used when managed settings are enabled"
                    {:session-id (:session-id ctx)})))
  (if (and (contains? request :managed-approval-required)
           (not= false (:managed-approval-required request)))
    {:kind :no-result}
    {:kind :approve-once}))

(defn default-join-session-permission-handler
  "Default permission handler for resuming sessions.

  Returns `{:kind :no-result}` — the CLI handles permission decisions itself.
  When used with `resume-session`, tells the CLI that this client does NOT
  want to handle permission requests (`requestPermission: false` on the wire).

  Use this when reconnecting to a session where the original client already
  established permission handling:

    (copilot/resume-session client session-id
      {:on-permission-request copilot/default-join-session-permission-handler})

  Equivalent to the upstream `defaultJoinSessionPermissionHandler` export."
  [_request _ctx]
  {:kind :no-result})

(defn- release-router!
  "Stop notification routing and release its thread and channels.

   Returns a vector of unexpected teardown failures. Idempotent: the state keys
   are cleared, so a second call is a no-op."
  [client]
  (swap! (:state client) assoc :router-running? false)
  (let [{:keys [router-thread router-ch lifecycle-ch]} @(:state client)
        failures (td/collect
                  [(when-let [^Thread t router-thread]
                     (td/attempt {:operation :join :resource :router-thread}
                                 (.interrupt t)
                                 (.join t 500)))])]
    (when router-ch (close! router-ch))
    (when lifecycle-ch (close! lifecycle-ch))
    (swap! (:state client) assoc
           :router-ch nil :router-queue nil :router-thread nil :lifecycle-ch nil)
    failures))

(defn- release-transport!
  "Release the client-owned transport: notification router, JSON-RPC
   connection, GitHub token-provider executor, socket, and — per `:process` —
   an SDK-owned child process.

   `:process` is `:none` (the caller owns the process, or there is none),
   `:graceful` (SIGTERM, then SIGKILL if it overstays), or `:forcible`
   (SIGKILL). When `:wait-for-exit-ms` is set, a process that has already been
   asked to shut down is first given that long to exit on its own.

   Every step runs even when an earlier one fails, and the whole sequence is
   idempotent. Returns a vector of unexpected teardown failures.

   The process handle is cleared only once the child is confirmed gone: a kill
   that failed leaves `:process` in place so the host keeps its only reference
   to a live process."
  [client {:keys [process wait-for-exit-ms] :or {process :none}}]
  (let [provider-failures (release-github-token-provider-runtime! client)
        router-failures (release-router! client)
        state @(:state client)
        {:keys [connection-io socket]} state
        proc-handle (:process state)
        own-process? (boolean (and (not= process :none)
                                   (not (:external-server? client))
                                   proc-handle))
        process-failures
        (if-not own-process?
          []
          (if (= process :forcible)
            (td/attempt-collecting {:operation :destroy-forcibly :resource :process}
                                   (proc/destroy-forcibly! proc-handle))
            (td/attempt-collecting {:operation :destroy :resource :process}
                                   ;; A runtime that already accepted
                                   ;; `runtime.shutdown` gets to exit on its own
                                   ;; before we signal it.
                                   (if (and wait-for-exit-ms
                                            (proc/wait-for-exit! proc-handle wait-for-exit-ms))
                                     []
                                     (proc/destroy! proc-handle)))))
        failures
        (td/collect
         (concat
          provider-failures
          router-failures
          (when connection-io
            (td/attempt-collecting {:operation :disconnect :resource :connection}
                                   (proto/disconnect connection-io)))
          [(when socket
             (td/attempt {:operation :close :resource :socket}
                         (.close ^Socket socket)))]
          process-failures))]
    (swap! (:state client) assoc
           :connection nil
           :connection-io nil
           :socket nil)
    ;; Only drop the process handle once the child is confirmed gone. Clearing
    ;; it after a failed kill would discard the only reference to a live
    ;; process, leaving the host no way to retry or report it.
    (when (and own-process? (empty? process-failures))
      (swap! (:state client) assoc :process nil))
    failures))

(defn- log-teardown-failures!
  "Report teardown failures that no caller-visible return value carries."
  [failures]
  (doseq [^Throwable failure failures]
    (log/warn failure "Client teardown step failed"))
  failures)

(defn- register-builtin-plugin-directories!
  "Register the complete host-bundled plugin directory set before sessions."
  [client]
  (when-let [directories
             (seq (get-in @(:state client)
                          [:options :builtin-plugin-directories]))]
    (try
      (proto/send-request! (:connection-io @(:state client))
                           "plugins.builtin.set"
                           {:paths (vec directories)})
      (catch Exception error
        (when-let [cleanup-failure
                   (td/attempt {:operation :force-stop
                                :resource :client-startup}
                               (force-stop! client))]
          (.addSuppressed ^Throwable error cleanup-failure)
          (log/warn cleanup-failure
                    "Client force-stop failed after plugin registration error"))
        (throw error))))
  nil)

(defn start!
  "Start the CLI server and establish connection.
   Blocks until connected or throws on error.

   Thread safety: concurrent start! calls are safe — an atomic compare-and-set
   on :status ensures only one caller spawns the process; the others no-op.
   Do not, however, call start! and stop! concurrently from different threads."
  [client]
  (let [[old _] (swap-vals! (:state client)
                            (fn [s]
                              (if (#{:connecting :connected} (:status s))
                                s
                                (assoc s :stopping? false :status :connecting))))]
    (when-not (#{:connecting :connected} (:status old))
      (log/info "Starting Copilot client...")

      ;; Set log level from options
      (when-let [level (:log-level (:options client))]
        (log/set-log-level! level))

      (try
      ;; Start CLI process if not connecting to external server
        (when-not (:external-server? client)
          (log/debug "Spawning CLI process")
          (let [opts (:options client)
                mp (proc/spawn-cli opts)]
            (swap! (:state client) assoc :process mp)
            (start-stderr-forwarder! client mp)
            (watch-process-exit! client mp)

          ;; For TCP mode, wait for port announcement
            (when-not (:use-stdio? opts)
              (let [port (proc/wait-for-port mp 10000)]
                (swap! (:state client) assoc :actual-port port)))))

      ;; Connect to server
        (cond
        ;; Child process mode: use own stdin/stdout to talk to parent
          (:is-child-process? (:options client))
          (do
            (log/debug "Connecting via parent stdio (child process mode)")
            (connect-parent-stdio! client))

        ;; External server (cli-url) or TCP mode
          (or (:external-server? client)
              (not (:use-stdio? (:options client))))
          (do
            (log/debug "Connecting via TCP")
            (connect-tcp! client))

        ;; Normal stdio to spawned process
          :else
          (do
            (log/debug "Connecting via stdio")
            (connect-stdio! client)))

      ;; Verify protocol version
        (verify-protocol-version! client)

      ;; Register trusted built-in plugins before any session can be created.
        (register-builtin-plugin-directories! client)

      ;; Register sessionFs provider if configured
        (when-let [sf-config (:session-fs client)]
          (let [{:keys [connection-io]} @(:state client)]
            (proto/send-request! connection-io "sessionFs.setProvider"
                                 (cond-> {:initial-cwd (:initial-cwd sf-config)
                                          :session-state-path (:session-state-path sf-config)
                                          :conventions (:conventions sf-config)}
                                 ;; Upstream PR #1299: forward provider capabilities (e.g., {:sqlite true}).
                                   (:capabilities sf-config)
                                   (assoc :capabilities (:capabilities sf-config))))))

      ;; Set up notification routing and request handling
        (start-notification-router! client)
        (setup-request-handler! client)

        (swap! (:state client) assoc :status :connected)
        (log/info "Copilot client connected")
        nil

        (catch Exception e
          (let [already-stopped? (:stopping? @(:state client))
                stderr (get-stderr-output client)
                msg (cond-> (str "Failed to start client: " (ex-message e))
                      stderr (str "\nstderr: " stderr))]
            (log/error msg)
            ;; Release resources unless a nested startup step already force-stopped
            ;; the client. Status remains :error so callers can distinguish a
            ;; failed start from a clean stop.
            (when-not already-stopped?
              (swap! (:state client) assoc :stopping? true)
              (log-teardown-failures!
               (release-transport! client {:process :graceful})))
            (swap! (:state client) assoc :status :error :actual-port nil)
            (throw e)))))))

(defn stop!
  "Stop the CLI server and close all sessions.
   Returns a vector of any errors encountered during cleanup.

   Performs graceful cleanup in order: disconnect sessions, request
   `runtime.shutdown` for SDK-owned CLI processes (bounded by a 10s timeout),
   close the connection and socket, then terminate the process. When the
   `runtime.shutdown` request succeeds, the child is given up to 10s to exit on
   its own before being force-killed. External runtimes (`:external-server?`)
   are never asked to shut down — only the connection to them is closed. Use
   force-stop! to skip graceful shutdown.

   Thread safety: do not call start! and stop! concurrently from different
   threads. Auto-restart is suppressed via the :stopping? flag, but explicit
   concurrent calls are unsupported."
  [client]
  (log/info "Stopping Copilot client...")
  (swap! (:state client) assoc :stopping? true)
  (let [errors (atom [])
        runtime-shutdown-completed? (atom false)
        {:keys [sessions process connection-io]} @(:state client)]
    (try
      ;; 0. Stop notification routing. Done before the session disconnects and
      ;; the runtime.shutdown RPC below; release-transport! then finds it
      ;; already released.
      (swap! errors into (release-router! client))

      ;; 1. Disconnect all sessions
      (doseq [[session-id _] sessions]
        (try
          (session/disconnect! client session-id)
          (catch Exception e
            (swap! errors conj
                   (ex-info (str "Failed to disconnect session " session-id)
                            {:session-id session-id} e)))))
      (swap! (:state client) assoc :sessions {} :session-io {})

      ;; 1b. Ask SDK-owned runtimes to flush and clean up before tearing down
      ;; their transport/process. External runtimes may be shared, so we only
      ;; close our connection to them (see release-transport!). Bounded by a
      ;; 10s timeout.
      (when (and connection-io process (not (:external-server? client)))
        (try
          (proto/send-request! connection-io "runtime.shutdown" {} 10000)
          (reset! runtime-shutdown-completed? true)
          (catch Exception e
            (swap! errors conj
                   (ex-info "Failed to gracefully shut down runtime" {} e)))))

      ;; 2. Release connection, socket, and the SDK-owned process. When
      ;; runtime.shutdown succeeded, give the child up to 10s to exit on its own
      ;; before signalling it.
      (swap! errors into
             (release-transport! client
                                 {:process :graceful
                                  :wait-for-exit-ms (when @runtime-shutdown-completed? 10000)}))

      (swap! (:state client) assoc :status :disconnected :actual-port nil
             :models-cache nil :lifecycle-handlers {}
             :stderr-buffer nil)  ; reset caches, handlers, and stderr

      (log/info "Copilot client stopped")
      @errors)))

(defn force-stop!
  "Force stop the CLI server without graceful RPCs.

   Releases locally-owned session resources before dropping client ownership, then
   closes the transport and terminates an SDK-owned process.

   The forced kill is confirmed: the child is signalled and then waited on for a
   bounded window, because a signal that was merely sent proves nothing. A child
   that survives is logged with its resource identity and its handle is kept in
   client state, so the host is never left holding no reference to a live
   process. The wait ends as soon as the child dies, so it is a worst-case
   bound rather than a fixed delay."
  [client]
  (swap! (:state client) assoc :stopping? true :lifecycle-handlers {})

  (let [session-ids (keys (:sessions @(:state client)))]
    ;; Release event roots and send locks while their state is still reachable.
    (doseq [session-id session-ids]
      (session/teardown-local! client session-id))
    (swap! (:state client) assoc :sessions {} :session-io {})

    (log-teardown-failures! (release-transport! client {:process :forcible})))

  (swap! (:state client) merge
         {:status :disconnected
          :connection nil
          :connection-io nil
          :socket nil
          :actual-port nil
          :router-ch nil
          :router-queue nil
          :router-thread nil
          :router-running? false
          :lifecycle-ch nil
          :models-cache nil
          :lifecycle-handlers {}
          :stderr-buffer nil}) ; reset caches, handlers, and stderr
  nil)

(defn- ensure-connected!
  "Ensure client is connected, auto-starting if configured."
  [client]
  (when-not (= :connected (:status @(:state client)))
    (if (:auto-start? (:options client))
      (start! client)
      (throw (ex-info "Client not connected. Call start! first." {})))))

(defn ping
  "Ping the server to check connectivity.
   Returns {:message :timestamp :protocol-version}.

   `:timestamp` is either an ISO 8601 date-time string (e.g.
   \"2026-05-21T08:00:00Z\"; CLI ≥ 1.0.51, upstream PR #1340) or a numeric
   epoch-millis value, depending on the CLI version; the SDK forwards
   whatever the server sends."
  ([client]
   (ping client nil))
  ([client message]
   (ensure-connected! client)
   (let [{:keys [connection-io]} @(:state client)
         result (proto/send-request! connection-io "ping" {:message message})]
     {:message (:message result)
      :timestamp (:timestamp result)
      :protocol-version (:protocol-version result)})))

(defn get-status
  "Get CLI status including version and protocol information.
   Returns {:version :protocol-version}."
  [client]
  (ensure-connected! client)
  (let [{:keys [connection-io]} @(:state client)
        result (proto/send-request! connection-io "status.get" {})]
    {:version (:version result)
     :protocol-version (:protocol-version result)}))

(defn get-auth-status
  "Get current authentication status.
   Returns {:authenticated? :auth-type :host :login :status-message}."
  [client]
  (ensure-connected! client)
  (let [{:keys [connection-io]} @(:state client)
        result (proto/send-request! connection-io "auth.getStatus" {})]
    {:authenticated? (:is-authenticated result)
     :auth-type (some-> (:auth-type result) keyword)
     :host (:host result)
     :login (:login result)
     :status-message (:status-message result)}))

(defn- parse-model-info
  "Parse a model info map from wire format to Clojure format."
  [m]
  (let [base {:id (:id m)
              :name (:name m)
              :vendor (:vendor m)
              :family (:family m)
              :version (:version m)
              :max-input-tokens (:max-input-tokens m)
              :max-output-tokens (:max-output-tokens m)
              :preview? (:preview m)}
        caps (when-let [c (:capabilities m)]
               {:model-capabilities
                (cond-> {}
                  (:supports c)
                  (assoc :supports
                         (cond-> (:supports c)
                           (contains? (:supports c) :adaptive-thinking)
                           (update :adaptive-thinking keyword)))
                  (:limits c)
                  (assoc :limits (:limits c)))})
        optional (merge
                  (select-keys m [:default-temperature
                                  :model-picker-priority
                                  :default-reasoning-effort
                                  :model-picker-category
                                  :model-picker-price-category])
                  (when (contains? m :policy)
                    (let [mp (:policy m)]
                      (cond
                        (map? mp)
                        {:model-policy mp}

                        (or (string? mp) (keyword? mp) (symbol? mp))
                        {:model-policy {:policy-state (name mp)}}

                        :else
                        (do
                          (log/warn "Unexpected model policy value for model "
                                    (or (:id m) (:name m) "<unknown>")
                                    ": " (pr-str mp))
                          nil))))
                  (when (contains? m :supported-reasoning-efforts)
                    {:supported-reasoning-efforts (vec (:supported-reasoning-efforts m))})
                  (when (contains? m :vision-limits)
                    {:vision-limits (select-keys (:vision-limits m)
                                                 [:supported-media-types
                                                  :max-prompt-images
                                                  :max-prompt-image-size])})
                  ;; Legacy flat key for backward compat
                  (when (contains? (get-in m [:capabilities :supports] {}) :reasoning-effort)
                    {:supports-reasoning-effort (get-in m [:capabilities :supports :reasoning-effort])})
                  (when-let [b (:billing m)]
                    {:model-billing b})
                  caps)]
    (merge base optional)))

(defn- fetch-models!
  "Fetch models from the server (no caching)."
  [client]
  (let [{:keys [connection-io]} @(:state client)
        result (proto/send-request! connection-io "models.list" {})
        models (:models result)]
    (mapv parse-model-info models)))

(defn list-models
  "List available models with their metadata.
   Results are cached per client connection to prevent rate limiting under concurrency.
   Cache is cleared on stop!/force-stop!.
   When :on-list-models handler is provided in client options, calls the handler
   instead of the RPC method. The handler does not require a CLI connection.
   Requires authentication (unless :on-list-models handler is provided).
   Returns a vector of model info maps with keys:
   :id :name :vendor :family :version :max-input-tokens :max-output-tokens
   :preview? :default-temperature :model-picker-priority
   :model-picker-category :model-picker-price-category
   :model-capabilities {:supports {:vision :reasoning-effort :adaptive-thinking}
                        :limits {:max-prompt-tokens :max-output-tokens
                                 :max-context-window-tokens
                                 :vision {:supported-media-types
                                          :max-prompt-images
                                          :max-prompt-image-size}}}
   :model-policy {:policy-state :terms}
   :model-billing {:multiplier
                   :token-prices {:input-price :output-price :cache-price
                                  :batch-size :context-max
                                  :long-context {:input-price :output-price
                                                 :cache-price :context-max}}
                   :promo {:ends-at :id :discount-percent :message}}
   :supported-reasoning-efforts :default-reasoning-effort
   :supports-reasoning-effort (legacy flat key)
   :vision-limits {:supported-media-types :max-prompt-images :max-prompt-image-size} (legacy)"
  [client]
  (let [handler (:on-list-models client)]
    (when-not handler (ensure-connected! client))
    (let [p (promise)
          entry (swap! (:state client) update :models-cache #(or % p))
          cached (:models-cache entry)]
      (cond
        ;; Already cached result (immutable, no need to copy)
        (vector? cached)
        cached

        ;; We won the race and must fetch
        (identical? cached p)
        (try
          (let [models (if handler
                         (vec (handler))
                         (fetch-models! client))]
            (deliver p models)
            (swap! (:state client) assoc :models-cache models)
            models)
          (catch Exception e
            (deliver p e)
            (swap! (:state client) assoc :models-cache nil)
            (throw e)))

        ;; Another thread is fetching, wait on promise
        :else
        (let [result @cached]
          (if (instance? Exception result)
            (throw result)
            result))))))

(defn ^:experimental list-tools
  "List available tools with their metadata.
   Optional :model param returns model-specific tool overrides.
   Returns a vector of tool info maps with keys:
   :name :namespaced-name :description :parameters :instructions

   Experimental: not part of the official Copilot SDK API; the wire RPC
   (`tools.list`) is exposed for convenience and may change."
  ([client]
   (list-tools client nil))
  ([client model]
   (ensure-connected! client)
   (let [{:keys [connection-io]} @(:state client)
         result (proto/send-request! connection-io "tools.list"
                                     (cond-> {}
                                       model (assoc :model model)))
         tools (:tools result)]
     (mapv (fn [t]
             (cond-> {:name (:name t)
                      :description (:description t)}
               (:namespaced-name t) (assoc :namespaced-name (:namespaced-name t))
               (:parameters t) (assoc :parameters (:parameters t))
               (:instructions t) (assoc :instructions (:instructions t))))
           tools))))

(defn ^:experimental get-quota
  "Get account quota information.
   Returns a map of quota type (string) to quota snapshot maps:
   {:entitlement-requests :used-requests :remaining-percentage
    :overage :overage-allowed-with-exhausted-quota? :reset-date}

   Experimental: not part of the official Copilot SDK API; the wire RPC
   (`account.getQuota`) is exposed for convenience and may change."
  [client]
  (ensure-connected! client)
  (let [{:keys [connection-io]} @(:state client)
        result (proto/send-request! connection-io "account.getQuota" {})
        snapshots (:quota-snapshots result)]
    (reduce-kv (fn [m k v]
                 (assoc m (name k)
                        (cond-> {:entitlement-requests (:entitlement-requests v)
                                 :used-requests (:used-requests v)
                                 :remaining-percentage (:remaining-percentage v)
                                 :overage (:overage v)
                                 :overage-allowed-with-exhausted-quota?
                                 (:overage-allowed-with-exhausted-quota v)}
                          (:reset-date v) (assoc :reset-date (:reset-date v)))))
               {} snapshots)))

;; ---------------------------------------------------------------------------
;; MCP Config RPCs (server-level, not session-scoped)
;; ---------------------------------------------------------------------------

(defn ^:experimental mcp-config-list
  "List all MCP server configurations.
   Returns a map with :servers (vector of server config maps)."
  [client]
  (ensure-connected! client)
  (let [conn (:connection-io @(:state client))]
    (util/wire->clj
     (proto/send-request! conn "mcp.config.list" {}))))

(defn ^:experimental mcp-config-add!
  "Add an MCP server configuration.
   params is a map with server config using plain keys (:name, :command, :args,
   :tools, etc.) — NOT the :mcp-prefixed keys used in session config :mcp-servers."
  [client params]
  (ensure-connected! client)
  (let [conn (:connection-io @(:state client))]
    (util/wire->clj
     (proto/send-request! conn "mcp.config.add" params))))

(defn ^:experimental mcp-config-update!
  "Update an MCP server configuration.
   params is a map with server config using plain keys (see mcp-config-add!)."
  [client params]
  (ensure-connected! client)
  (let [conn (:connection-io @(:state client))]
    (util/wire->clj
     (proto/send-request! conn "mcp.config.update" params))))

(defn ^:experimental mcp-config-remove!
  "Remove an MCP server configuration.
   params is a map identifying the server using plain keys (see mcp-config-add!)."
  [client params]
  (ensure-connected! client)
  (let [conn (:connection-io @(:state client))]
    (util/wire->clj
     (proto/send-request! conn "mcp.config.remove" params))))

(defn- validate-provider-config!
  "Validate singular-`:provider` BYOK constraints shared by create and resume:
   `:model` is required when `:provider` is set, and `:provider` cannot be
   combined with the multi-provider registry — neither `:providers` nor
   `:models` (upstream documents combining either with the singular `provider`
   as rejected)."
  [config]
  (when (and (:provider config) (not (:model config)))
    (throw (ex-info "Invalid session config: :model is required when :provider (BYOK) is specified"
                    {:config (redact-secrets config)})))
  (when (and (:provider config) (or (:providers config) (:models config)))
    (throw (ex-info "Invalid session config: :provider cannot be combined with the :providers/:models registry (use one or the other)"
                    {:config (redact-secrets config)}))))

(defn- validate-session-config!
  "Validate session config, throwing on invalid input."
  [config]
  (when-not (s/valid? ::specs/session-config config)
    (let [safe-config (redact-secrets config)
          unknown (specs/unknown-keys config specs/session-config-keys)
          explain (s/explain-data ::specs/session-config safe-config)
          msg (cond
                (seq unknown)
                (format "Invalid session config: unknown keys %s. Valid keys are: %s"
                        (pr-str unknown)
                        (pr-str (sort specs/session-config-keys)))

                (util/github-token-auth-conflict? config)
                (config-validation-message "Invalid session config" config)

                :else
                (format "Invalid session config: %s"
                        (with-out-str (s/explain ::specs/session-config safe-config))))]
      (throw (ex-info msg {:config safe-config :unknown-keys unknown :explain explain}))))
  (validate-provider-config! config))

(defn- validate-tool-filter-list!
  "Reject bare `\"*\"` in a tool filter list (mirrors upstream
   `validateToolFilterList`). The runtime treats a bare wildcard as a
   literal name match for a tool whose name is the single character `*`,
   which never matches anything — so a silent empty filter is worse than
   an explicit error pointing the developer at the source-qualified
   `tool-set/builtin`/`mcp`/`custom` helpers."
  [field tools]
  (when tools
    (doseq [entry tools]
      (when (= "*" entry)
        (throw (ex-info
                (format "Invalid %s entry '*': there is no bare wildcard. Use a source-qualified pattern like \"builtin:*\", \"mcp:*\", or \"custom:*\" (see github.copilot-sdk.tool-set)."
                        field)
                {:field field :entry entry :tools tools}))))))

(defn- validate-tool-filters!
  "Run [[validate-tool-filter-list!]] on both `:available-tools` and
   `:excluded-tools`."
  [config]
  (validate-tool-filter-list! "availableTools" (:available-tools config))
  (validate-tool-filter-list! "excludedTools" (:excluded-tools config)))

(defn- validate-empty-mode-session-requirements!
  "Empty mode requires every session to opt into its tools explicitly. The
   caller may pass `:available-tools []` to mean \"no tools\", but the key
   must be PRESENT — undefined is rejected so silently-empty filters can't
   happen (upstream PR #1428, mirrors `resolveToolFilterOptions`)."
  [client config]
  (when (and (= :empty (:mode (options client)))
             (not (contains? config :available-tools)))
    (throw (ex-info
            (str "Mode :empty requires every session to specify :available-tools. "
                 "Pass an explicit list of source-qualified patterns (e.g. "
                 "tool-set/isolated, or [\"builtin:*\"]) — or [] to opt into no tools.")
            {:mode :empty
             :session-config-keys (vec (keys config))}))))

;; Section keyword → wire string mapping for system message customize mode.
;; Delegates to shared mapping in util.clj.

(defn- extract-transform-callbacks
  "Extract transform callbacks from a customize-mode system message config.
   Returns {:wire-payload <system-message for wire> :transform-callbacks <map or nil>}.
   
   For customize mode, function-valued :action entries are extracted into a
   callbacks map (keyed by wire section ID) and replaced with {:action \"transform\"}
   in the wire payload. Static actions are converted to wire format directly."
  [system-message]
  (if (or (not system-message)
          (not= :customize (:mode system-message)))
    {:wire-payload system-message :transform-callbacks nil}
    (let [sections (:sections system-message)
          callbacks (atom {})
          wire-sections
          (when (seq sections)
            (reduce-kv
             (fn [acc section-kw override]
               (let [wire-id (util/section-kw->wire-id section-kw)
                     action (:action override)]
                 (if (fn? action)
                   (do (swap! callbacks assoc wire-id action)
                       (assoc acc wire-id {:action "transform"}))
                   (assoc acc wire-id
                          (cond-> {:action (name action)}
                            (:content override)
                            (assoc :content (:content override)))))))
             {}
             sections))
          cbs @callbacks]
      {:wire-payload (cond-> {:mode "customize"}
                       (seq wire-sections) (assoc :sections wire-sections)
                       (:content system-message)
                       (assoc :content (:content system-message)))
       :transform-callbacks (when (seq cbs) cbs)})))

(defn- system-message->wire
  "Convert a system-message config to wire format.
   For :append and :replace modes, returns a simple map while preserving
   omitted optional fields exactly.
   For :customize mode, returns only the static wire payload
   (transform callbacks are extracted separately)."
  [sm]
  (case (:mode sm)
    :replace {:mode "replace" :content (:content sm)}
    :customize (let [{:keys [wire-payload]} (extract-transform-callbacks sm)]
                 wire-payload)
    :append (cond-> {:mode "append"}
              (some? (:content sm)) (assoc :content (:content sm)))
    (cond-> {}
      (some? (:content sm)) (assoc :content (:content sm)))))

(defn- apply-empty-mode-system-message
  "In :empty mode, normalize the session config's :system-message so the
   `environment_context` section is always stripped (CWD/OS/git-root leak)
   unless the app has already taken control of it. No-op in :copilot-cli
   mode. Mirrors upstream `getSystemMessageConfigForMode` (PR #1428).

   - No caller :system-message → emit `:customize` with
     `:environment-context {:action :remove}`.
   - Caller `:replace` → pass through (app fully replaces system message).
   - Caller `:customize` → add `:environment-context {:action :remove}` to
     :sections unless the app already specified an env-context override.
   - Caller `:append` (or `:mode` absent) → promote to `:customize`,
     preserve `:content` (still appended as additional instructions),
     and strip `environment_context`."
  [client config]
  (if (not= :empty (:mode (options client)))
    config
    (let [sm (:system-message config)
          ec-remove {:environment-context {:action :remove}}]
      (cond
        (nil? sm)
        (assoc config :system-message {:mode :customize :sections ec-remove})

        (= :replace (:mode sm))
        config

        (= :customize (:mode sm))
        (if (contains? (:sections sm) :environment-context)
          config
          (update-in config [:system-message :sections]
                     (fnil assoc {}) :environment-context {:action :remove}))

        :else
        (assoc config :system-message
               (cond-> {:mode :customize :sections ec-remove}
                 (:content sm) (assoc :content (:content sm))))))))

(defn- rename-keys-present
  "Rename keys of `m` per the `old->new` map, only for keys actually present.
   Leaves all other entries untouched."
  [m old->new]
  (reduce-kv (fn [acc old new]
               (if (contains? acc old)
                 (-> acc (dissoc old) (assoc new (get acc old)))
                 acc))
             m old->new))

(defn- provider->wire
  "Convert a Clojure ProviderConfig map to its JSON-RPC wire shape.

   `util/clj->wire` camelCases keyword keys, but several SDK property names are
   chosen for Clojure clarity and differ from the upstream `ProviderConfig` field
   names, which the runtime reads verbatim (`provider: config.provider` in
   nodejs/src/client.ts). This helper restores the upstream wire names so BYOK
   works for every provider type: `providerType`->`type`, `azureOptions`->`azure`,
   `maxInputTokens`->`maxPromptTokens`, and the nested `azureApiVersion`->`apiVersion`.
   Mirrors the `ProviderConfig` shape in nodejs/src/types.ts.

   `:bearer-token-provider` (upstream PR #1748; public name settled in #1796) is
   an on-demand token callback, not wire data: the fn is stripped and replaced
   with a boolean `:hasBearerTokenProvider` flag so the runtime knows to issue
   `providerToken.getToken` requests instead of reading a static credential. The
   callback receives the originating `:session-id` (upstream PR #1796)."
  [provider]
  (let [has-btp (some? (:bearer-token-provider provider))
        wire (rename-keys-present (util/clj->wire (dissoc provider :bearer-token-provider))
                                  {:providerType :type
                                   :azureOptions :azure
                                   :maxInputTokens :maxPromptTokens})]
    (cond-> wire
      (contains? wire :azure)
      (update :azure rename-keys-present {:azureApiVersion :apiVersion})
      has-btp (assoc :hasBearerTokenProvider true))))

(defn- named-provider->wire
  "Convert a Clojure NamedProviderConfig map to its JSON-RPC wire shape
   (upstream PR #1718). Like `provider->wire` for the shared provider fields
   (`providerType`->`type`, `azureOptions`->`azure`, nested
   `azureApiVersion`->`apiVersion`), but a named provider carries no transport
   or inline model-override fields: `name`, `baseUrl`, `apiKey`, `bearerToken`,
   `wireApi`, `azure`, and `headers` all camelCase cleanly. Mirrors the
   `NamedProviderConfig` shape in nodejs/src/types.ts.

   `:bearer-token-provider` (upstream PR #1748) is stripped and replaced with a
   boolean `:hasBearerTokenProvider` flag, exactly as in `provider->wire`."
  [np]
  (let [has-btp (some? (:bearer-token-provider np))
        wire (rename-keys-present (util/clj->wire (dissoc np :bearer-token-provider))
                                  {:providerType :type
                                   :azureOptions :azure})]
    (cond-> wire
      (contains? wire :azure)
      (update :azure rename-keys-present {:azureApiVersion :apiVersion})
      has-btp (assoc :hasBearerTokenProvider true))))

(defn- provider-model->wire
  "Convert a Clojure ProviderModelConfig map to its JSON-RPC wire shape
   (upstream PR #1718). `id`, `provider`, `wireModel`, `modelId`, `name`,
   `maxContextWindowTokens`, and `maxOutputTokens` camelCase cleanly; only
   `maxInputTokens`->`maxPromptTokens` needs a rename (mirroring
   `provider->wire`). `:capabilities` is an opaque ModelCapabilitiesOverride
   forwarded verbatim — it is pulled aside before `clj->wire` and re-attached
   so its source-defined string keys (a mix of camelCase and snake_case) are
   never run through kebab->camel conversion. Mirrors the `ProviderModelConfig`
   shape in nodejs/src/types.ts."
  [pm]
  (let [capabilities (:capabilities pm)
        wire-capabilities (when (some? capabilities)
                            (if (every? string? (keys capabilities))
                              capabilities
                              (util/model-capabilities->wire capabilities)))
        wire (rename-keys-present (util/clj->wire (dissoc pm :capabilities))
                                  {:maxInputTokens :maxPromptTokens})]
    (cond-> wire
      (some? wire-capabilities) (assoc :capabilities wire-capabilities))))

(defn- large-output->wire
  "Convert a :large-output config map to wire shape, accepting both the
   pre-existing `:output-dir` key and the upstream-aligned alias
   `:output-directory` (the wire field stays `outputDir`)."
  [lo]
  (let [dir (or (:output-directory lo) (:output-dir lo))]
    (cond-> (dissoc lo :output-directory)
      dir (assoc :output-dir dir))))

(defn- tool-def->wire
  "Convert a single tool definition to its wire shape for session.create /
   session.resume. Shared by both builders so the two paths cannot drift.
   `:defer` (upstream PR #1632) is an idiom keyword (:auto | :never) sent as
   its wire string. `:metadata` is opaque host-defined data and is forwarded
   without interpretation."
  [t]
  (cond-> {:name (:tool-name t)
           :description (:tool-description t)
           :parameters (:tool-parameters t)}
    (some? (:overrides-built-in-tool t))
    (assoc :overridesBuiltInTool (:overrides-built-in-tool t))
    (some? (:skip-permission? t))
    (assoc :skipPermission (:skip-permission? t))
    (some? (:defer t))
    (assoc :defer (name (:defer t)))
    (some? (:metadata t))
    (assoc :metadata (:metadata t))
    (some? (:is-terminal? t))
    (assoc :isTerminal (:is-terminal? t))))

(defn- custom-agent->wire
  "Convert a custom agent to its wire shape for session.create / session.resume.
   Clojure's disambiguating `:agent-*` keys map to the unprefixed upstream
   CustomAgentConfig fields. Nested MCP servers use their shared wire adapter."
  [agent]
  (let [reasoning-effort (:agent-reasoning-effort agent)
        mcp-servers (:mcp-servers agent)
        key-renames {:agent-name :name
                     :agent-display-name :display-name
                     :agent-description :description
                     :agent-tools :tools
                     :agent-prompt :prompt
                     :agent-infer? :infer
                     :agent-skills :skills
                     :agent-model :model}
        renamed (reduce-kv (fn [result key value]
                             (assoc result (get key-renames key key) value))
                           {}
                           (dissoc agent :agent-reasoning-effort :mcp-servers))]
    (cond-> (util/clj->wire renamed)
      (some? mcp-servers) (assoc :mcpServers (util/mcp-servers->wire mcp-servers))
      (some? reasoning-effort) (assoc :reasoningEffort reasoning-effort))))

(defn- github-mcp-tool-config->wire
  "Convert the built-in GitHub MCP configuration to its exact wire shape."
  [config]
  (cond-> {}
    (some? (:enable-all-tools? config))
    (assoc :enableAllTools (:enable-all-tools? config))
    (contains? config :additional-toolsets)
    (assoc :additionalToolsets (:additional-toolsets config))
    (contains? config :additional-tools)
    (assoc :additionalTools (:additional-tools config))
    (some? (:enable-insiders-mode? config))
    (assoc :enableInsidersMode (:enable-insiders-mode? config))
    (some? (:disable-form-deferral? config))
    (assoc :disableFormDeferral (:disable-form-deferral? config))))

(defn- managed-settings->wire
  "Convert host-injected managed settings to the runtime wire shape."
  [settings]
  (cond-> {}
    (contains? settings :permissions)
    (assoc :permissions
           (let [permissions (:permissions settings)]
             (cond-> {}
               (some? (:disable-bypass-permissions-mode permissions))
               (assoc :disableBypassPermissionsMode
                      (name (:disable-bypass-permissions-mode permissions)))
               (contains? permissions :deny) (assoc :deny (:deny permissions))
               (contains? permissions :ask) (assoc :ask (:ask permissions))
               (contains? permissions :allow) (assoc :allow (:allow permissions)))))))

(defn- config-defaults-for-mode
  "Mode-specific session config defaults spread UNDER the caller's config
   (caller's values always win). Mirrors upstream `configDefaultsForMode`
   in `client.ts` (upstream PR #1428).

   In `:empty` mode this returns the 10 safe defaults that enable
   multitenancy hardening (no telemetry, in-memory token + embedding
   storage, no host filesystem / git / skills, no persistent memory). In
   `:copilot-cli` mode this returns `{}` so the historical behavior is
   preserved."
  [mode]
  (if (= mode :empty)
    {:enable-session-telemetry? false
     :mcp-oauth-token-storage :in-memory
     :skip-embedding-retrieval true
     :embedding-cache-storage :in-memory
     :enable-on-demand-instruction-discovery false
     :enable-file-hooks false
     :enable-host-git-operations false
     :enable-session-store false
     :enable-skills false
     :enable-experimental-mode? false
     :custom-agents-local-only true
     :memory {:enabled false}}
    {}))

(defn- normalize-config-for-mode
  "Apply mode-specific normalizations to session config (upstream PR #1428):
   1. Spread `config-defaults-for-mode` UNDER caller config (caller wins).
   2. In `:empty` mode, normalize `:system-message` so the
      `environment_context` section is stripped unless the app has taken
      control of it (see `apply-empty-mode-system-message`).
   3. Stamp `:enable-github-telemetry-forwarding? true` when the client
      carries an `:on-github-telemetry` handler (upstream PR #1835), so both
      `session.create` and `session.resume` builders emit the wire opt-in.
   Steps 1 and 3 apply in every mode; step 2 fires only in `:empty` mode."
  [client config]
  (cond-> (->> config
               (merge (config-defaults-for-mode (-> client options :mode)))
               (apply-empty-mode-system-message client))
    (some? (:on-github-telemetry client))
    (assoc :enable-github-telemetry-forwarding? true)))

(defn- build-session-options-update-patch
  "Compute the params for session.options.update (upstream PR #1428).

   In `:empty` mode, defaults the 4 overridable feature flags and built-in
   skill allowlist to safe values (caller wins), and forces
   `installedPlugins: []`. In `:copilot-cli` mode, only forwards values the
   caller explicitly set.

   Returns nil if the resulting patch is empty (no RPC needed)."
  [client config]
  (let [mode (-> client options :mode)
        with-flag (fn [patch wire-key kw default-empty]
                    (cond
                      (contains? config kw) (assoc patch wire-key (get config kw))
                      (= mode :empty) (assoc patch wire-key default-empty)
                      :else patch))
        patch (-> {}
                  (with-flag :skip-custom-instructions :skip-custom-instructions true)
                  (with-flag :custom-agents-local-only :custom-agents-local-only true)
                  (with-flag :coauthor-enabled :coauthor-enabled false)
                  (with-flag :manage-schedule-enabled :manage-schedule-enabled false))
        patch (cond-> patch
                (= mode :empty)
                (assoc :installed-plugins [])

                (or (= mode :empty)
                    (contains? config :included-builtin-skills))
                (assoc :included-builtin-skills
                       (get config :included-builtin-skills [])))]
    (when (seq patch) patch)))

(defn- cleanup-failed-session-setup!
  "Release every locally owned resource from a failed session setup.

   `destroy-runtime?` is true only after create/resume has succeeded remotely.
   `provider-scope` is nil for a failed resume before that point so the
   previously committed provider remains usable. After remote success, the
   committed provider is purged; the operation's provisional registration is
   rolled back separately.
   Returns unexpected cleanup failures without replacing the setup failure."
  [client session-id {:keys [destroy-runtime? provider-scope]
                      :or {destroy-runtime? false
                           provider-scope :committed-only}}]
  (td/collect
   [(when (and destroy-runtime? session-id)
      (td/attempt
       {:operation :destroy :resource :failed-session-setup
        :session-id session-id}
       (when-let [connection-io (:connection-io @(:state client))]
         (proto/send-request! connection-io
                              "session.destroy"
                              {:session-id session-id}
                              5000))))
    (when session-id
      (td/attempt
       {:operation :teardown :resource :failed-session-setup
        :session-id session-id}
       (session/teardown-local! client session-id provider-scope)))
    (when session-id
      (td/attempt
       {:operation :remove :resource :failed-session-setup
        :session-id session-id}
       (session/remove-session! client session-id)))]))

(defn- preserve-setup-failure!
  [^Throwable failure cleanup-failures]
  (doseq [cleanup-failure cleanup-failures]
    (.addSuppressed failure cleanup-failure))
  (log-teardown-failures! cleanup-failures)
  failure)

(defn- session-registration-snapshot
  [client session-id]
  (let [state @(:state client)]
    (when (contains? (:sessions state) session-id)
      {:session (get-in state [:sessions session-id])
       :session-io-present? (contains? (:session-io state) session-id)
       :session-io (get-in state [:session-io session-id])})))

(defn- restore-session-registration!
  [client session-id snapshot]
  (when snapshot
    (swap! (:state client)
           (fn [state]
             (cond-> (assoc-in state [:sessions session-id] (:session snapshot))
               (:session-io-present? snapshot)
               (assoc-in [:session-io session-id] (:session-io snapshot))

               (not (:session-io-present? snapshot))
               (update :session-io dissoc session-id))))))

(defn- fail-session-setup!
  [client session-id registration-id remote-accepted? snapshot failure]
  (let [cleanup-failures
        (td/collect
         (concat
          (cleanup-failed-session-setup!
           client
           session-id
           {:destroy-runtime? remote-accepted?
            :provider-scope (when remote-accepted? :committed-only)})
          [(td/attempt
            {:operation :rollback :resource :github-token-provider
             :registration-id registration-id}
            (rollback-github-token-provider! client registration-id))
           (when-not remote-accepted?
             (td/attempt
              {:operation :restore :resource :session-registration
               :session-id session-id}
              (restore-session-registration! client session-id snapshot)))]))]
    (preserve-setup-failure! failure cleanup-failures)))

(defn- fail-session-setup-async
  [client session-id registration-id remote-accepted? snapshot failure]
  (async/thread
    (fail-session-setup!
     client session-id registration-id remote-accepted? snapshot failure)))

(defn- apply-session-options-update!
  "Issue session.options.update with the mode-derived patch. No-op when empty."
  [client session config]
  (when-let [patch (build-session-options-update-patch client config)]
    (let [session-id (:session-id session)
          {:keys [connection-io]} @(:state client)
          params (assoc patch :session-id session-id)]
      (proto/send-request! connection-io "session.options.update" params 30000))))

(defn- <apply-session-options-update!
  "Async variant of `apply-session-options-update!`. Returns a channel that
   yields `:ok` on success (including the no-op case) or a Throwable on failure.
   The channel always closes after one value."
  [client session config]
  (let [out (async/chan 1)]
    (if-let [patch (build-session-options-update-patch client config)]
      (let [session-id (:session-id session)
            {:keys [connection-io]} @(:state client)
            params (assoc patch :session-id session-id)
            rpc-ch (proto/send-request connection-io "session.options.update" params)]
        (go
          (let [response (<! rpc-ch)
                err (cond
                      (nil? response)
                      (ex-info "session.options.update failed: RPC channel closed"
                               {:session-id session-id})
                      (:error response)
                      (ex-info (str "session.options.update failed: "
                                    (get-in response [:error :message] "RPC error"))
                               {:session-id session-id :error (:error response)}))]
            (if err
              (>! out err)
              (>! out :ok))
            (close! out))))
      (do (async/put! out :ok)
          (close! out)))
    out))

(defn- request-mcp-apps?
  "True only for the official SDK's explicit experimental MCP Apps opt-in."
  [config]
  (true? (:enable-mcp-apps config)))

(defn- commands->wire
  [commands]
  (mapv (fn [{:keys [name description]}]
          {:name name
           :description (or description "")})
        commands))

(defn- build-create-session-params
  "Build wire params for session.create from config."
  [config]
  (when-let [servers (:mcp-servers config)]
    (ensure-valid-mcp-servers! servers))
  (let [wire-tools (when (:tools config)
                     (mapv tool-def->wire (:tools config)))
        wire-sys-msg (when-let [sm (:system-message config)]
                       (system-message->wire sm))
        wire-provider (when-let [provider (:provider config)]
                        (provider->wire provider))
        wire-mcp-servers (when-let [servers (:mcp-servers config)]
                           (util/mcp-servers->wire servers))
        wire-custom-agents (when-let [agents (:custom-agents config)]
                             (mapv custom-agent->wire agents))
        wire-default-agent (when-let [agent (:default-agent config)]
                             (util/clj->wire agent))
        wire-infinite-sessions (when-let [is (:infinite-sessions config)]
                                 (util/clj->wire is))
        wire-commands (some-> (:commands config) commands->wire)
        config-dir (or (:config-directory config) (:config-dir config))
        wire-large-output (some-> (:large-output config) large-output->wire)
        wire-memory (when-let [m (:memory config)]
                      (util/clj->wire m))
        wire-providers (when-let [ps (:providers config)]
                         (mapv named-provider->wire ps))
        wire-models (when-let [ms (:models config)]
                      (mapv provider-model->wire ms))]
    (cond-> {}
      (:session-id config) (assoc :session-id (:session-id config))
      (:client-name config) (assoc :client-name (:client-name config))
      (:model config) (assoc :model (:model config))
      (:github-token config) (assoc :git-hub-token (:github-token config))
      (:github-token-provider-registration-id config)
      (assoc :git-hub-token-provider-registration-id
             (:github-token-provider-registration-id config))
      wire-tools (assoc :tools wire-tools)
      wire-commands (assoc :commands wire-commands)
      wire-sys-msg (assoc :system-message wire-sys-msg)
      (:available-tools config) (assoc :available-tools (:available-tools config))
      (:excluded-tools config) (assoc :excluded-tools (:excluded-tools config))
      (:tool-search config) (assoc :tool-search (:tool-search config))
      ;; SDK always sends `toolFilterPrecedence: "excluded"` so callers can
      ;; compose include + exclude lists naturally (upstream PR #1428).
      ;; Behavioral change for CLI mode: prior to PR #1428 the CLI default
      ;; precedence was forwarded as-is. Allowlist-precedence is not exposed
      ;; on the SDK boundary by design (mirrors upstream `resolveToolFilterOptions`).
      true (assoc :tool-filter-precedence "excluded")
      wire-provider (assoc :provider wire-provider)
      wire-providers (assoc :providers wire-providers)
      wire-models (assoc :models wire-models)
      (:exp-assignments config) (assoc :exp-assignments (:exp-assignments config))
      (some? (:enable-experimental-mode? config))
      (assoc :isExperimentalMode (:enable-experimental-mode? config))
      true (assoc :request-permission (boolean (:on-permission-request config)))
      (:capi config) (assoc :capi (util/clj->wire (:capi config)))

      ;; Session options (upstream PR #1865).
      (:excluded-builtin-agents config)
      (assoc :excluded-builtin-agents (:excluded-builtin-agents config))

      (some? (:enable-citations config))
      (assoc :enable-citations (:enable-citations config))

      (:session-limits config)
      (assoc :session-limits (util/clj->wire (:session-limits config)))

      (some? (:streaming? config)) (assoc :streaming (:streaming? config))
      wire-mcp-servers (assoc :mcp-servers wire-mcp-servers)
      (contains? config :disabled-mcp-servers)
      (assoc :disabled-mcp-servers (:disabled-mcp-servers config))
      (:github-mcp-tool-config config)
      (assoc :githubMcpToolConfig
             (github-mcp-tool-config->wire (:github-mcp-tool-config config)))
      wire-custom-agents (assoc :custom-agents wire-custom-agents)
      (some? (:custom-agents-local-only config))
      (assoc :custom-agents-local-only (:custom-agents-local-only config))
      wire-default-agent (assoc :default-agent wire-default-agent)
      config-dir (assoc :config-dir config-dir)
      (:skill-directories config) (assoc :skill-directories (:skill-directories config))
      (:instruction-directories config) (assoc :instruction-directories (:instruction-directories config))
      (:disabled-skills config) (assoc :disabled-skills (:disabled-skills config))
      (:plugin-directories config) (assoc :plugin-directories (:plugin-directories config))
      wire-large-output (assoc :large-output wire-large-output)
      (:working-directory config) (assoc :working-directory (:working-directory config))
      (contains? config :additional-directories)
      (assoc :additional-directories (:additional-directories config))
      wire-infinite-sessions (assoc :infinite-sessions wire-infinite-sessions)
      wire-memory (assoc :memory wire-memory)
      (:reasoning-effort config) (assoc :reasoning-effort (:reasoning-effort config))
      (:reasoning-summary config) (assoc :reasoning-summary (:reasoning-summary config))
      (contains? config :context-tier)
      (assoc :context-tier (util/context-tier->wire (:context-tier config)))
      (:agent config) (assoc :agent (:agent config))
      true (assoc :request-user-input (boolean (:on-user-input-request config)))
      true (assoc :request-elicitation (boolean (:on-elicitation-request config)))
      (contains? config :ask-user-variant)
      (assoc :ask-user-variant (name (:ask-user-variant config)))
      (request-mcp-apps? config) (assoc :requestMcpApps true)
      true (assoc :request-exit-plan-mode (boolean (:on-exit-plan-mode config)))
      true (assoc :request-auto-mode-switch (boolean (:on-auto-mode-switch config)))
      true (assoc :hooks (boolean (some identity (vals (:hooks config)))))
      (some? (:enable-config-discovery config))
      (assoc :enable-config-discovery (:enable-config-discovery config))
      (some? (:enable-session-telemetry? config))
      (assoc :enable-session-telemetry (:enable-session-telemetry? config))
      (:remote-session config)
      (assoc :remote-session (name (:remote-session config)))
      (:cloud config)
      (assoc :cloud (util/clj->wire (:cloud config)))
      (contains? config :feature-flags)
      (assoc :feature-flags (:feature-flags config))
      (:model-capabilities config)
      (assoc :model-capabilities
             (util/model-capabilities->wire (:model-capabilities config)))
      ;; Round 6 (upstream schema 1.0.56-1): MCP OAuth token storage mode and
      ;; embedding cache storage mode are hyphen-preserving string enums
      ;; (`"persistent"` / `"in-memory"`). The wire key for the first is
      ;; `mcpOAuthTokenStorage` (capital `OA` in `OAuth`) — csk on
      ;; `:mcp-oauth-token-storage` would emit `mcpOauthTokenStorage` instead,
      ;; so we forward via a string key to bypass key conversion. The value
      ;; uses `(name kw)` so `:in-memory` survives as `"in-memory"`
      ;; (csk would mangle it to `"inMemory"`).
      (:mcp-oauth-token-storage config)
      (assoc "mcpOAuthTokenStorage" (name (:mcp-oauth-token-storage config)))
      (:embedding-cache-storage config)
      (assoc :embedding-cache-storage (name (:embedding-cache-storage config)))
      (some? (:skip-embedding-retrieval config))
      (assoc :skip-embedding-retrieval (:skip-embedding-retrieval config))
      (:organization-custom-instructions config)
      (assoc :organization-custom-instructions (:organization-custom-instructions config))
      (some? (:enable-on-demand-instruction-discovery config))
      (assoc :enable-on-demand-instruction-discovery (:enable-on-demand-instruction-discovery config))
      (some? (:enable-file-hooks config))
      (assoc :enable-file-hooks (:enable-file-hooks config))
      (some? (:enable-file-change-tracking? config))
      (assoc :enable-file-change-tracking (:enable-file-change-tracking? config))
      (some? (:enable-host-git-operations config))
      (assoc :enable-host-git-operations (:enable-host-git-operations config))
      (some? (:enable-session-store config))
      (assoc :enable-session-store (:enable-session-store config))
      (some? (:enable-skills config))
      (assoc :enable-skills (:enable-skills config))
      true (assoc :include-sub-agent-streaming-events
                  (if (some? (:include-sub-agent-streaming-events? config))
                    (:include-sub-agent-streaming-events? config)
                    true))
      ;; csk camelCases "github" -> "Github", but upstream expects the
      ;; "GitHub" acronym (enableGitHubTelemetryForwarding). Assoc the exact
      ;; wire keyword; clj->wire's camelCasing is idempotent on it.
      (true? (:enable-github-telemetry-forwarding? config))
      (assoc :enableGitHubTelemetryForwarding true)
      ;; enableManagedSettings (upstream PR #1925): opt-in for runtime self-fetch
      ;; of enterprise managed settings. Upstream spreads `config.enableManagedSettings`
      ;; verbatim, so forward the actual boolean (an explicit false is sent).
      (some? (:enable-managed-settings? config))
      (assoc :enable-managed-settings (:enable-managed-settings? config))
      (:managed-settings config)
      (assoc :managed-settings (managed-settings->wire (:managed-settings config)))
      (some? (:request-extensions? config))
      (assoc :request-extensions (:request-extensions? config))
      (some? (:extension-sdk-path config))
      (assoc :extension-sdk-path (:extension-sdk-path config))
      (some? (:extension-info config))
      (assoc :extension-info (:extension-info config))
      ;; canvasProvider (upstream PR #1847): stable canvas-provider identity.
      ;; The nested {:id :name} map's kebab keys are camelCased by clj->wire.
      (:canvas-provider config)
      (assoc :canvas-provider (:canvas-provider config))
      true (assoc :env-value-mode "direct"))))

(defn- stringify-keys-deep
  "Recursively convert keyword keys to strings in a value (used for opaque
  payloads). Strings, sequences, and primitives pass through. csk's recursive
  transform-keys leaves non-keyword keys untouched, so stringified maps
  round-trip through `util/clj->wire` unchanged.

  Namespaced keywords are preserved as `\"ns/name\"` so caller-supplied keys
  (e.g. `:my.app/user_id`) are not silently truncated to their local name."
  [v]
  (cond
    (map? v)         (into {} (map (fn [[k vv]]
                                     [(if (keyword? k)
                                        (subs (str k) 1)
                                        k)
                                      (stringify-keys-deep vv)]))
                           v)
    (sequential? v)  (mapv stringify-keys-deep v)
    :else            v))

(defn- canvas-instance->wire
  "Convert an open-canvas instance to its wire shape. Required fields stay in
  idiomatic kebab-case so `util/clj->wire` does the camelCase conversion once;
  the optional `:input` map's keys are stringified so they round-trip through
  `util/clj->wire` verbatim — caller keys (e.g. `:user_id`) are NOT camelCased.

  Used to seed `openCanvases` on `session.resume` / `session.join` (upstream
  PR #1604, ResumeSessionConfig.openCanvases)."
  [canvas]
  (cond-> {}
    (some? (:instance-id canvas))    (assoc :instance-id    (:instance-id canvas))
    (some? (:extension-id canvas))   (assoc :extension-id   (:extension-id canvas))
    (some? (:canvas-id canvas))      (assoc :canvas-id      (:canvas-id canvas))
    (some? (:extension-name canvas)) (assoc :extension-name (:extension-name canvas))
    (some? (:title canvas))          (assoc :title          (:title canvas))
    (some? (:status canvas))         (assoc :status         (:status canvas))
    (some? (:url canvas))            (assoc :url            (:url canvas))
    (contains? canvas :input)        (assoc :input          (stringify-keys-deep
                                                             (:input canvas)))))

(defn- build-resume-session-params
  "Build wire params for session.resume from session-id and config."
  [session-id config]
  (when-let [servers (:mcp-servers config)]
    (ensure-valid-mcp-servers! servers))
  (let [wire-tools (when (:tools config)
                     (mapv tool-def->wire (:tools config)))
        wire-sys-msg (when-let [sm (:system-message config)]
                       (system-message->wire sm))
        wire-provider (when-let [provider (:provider config)]
                        (provider->wire provider))
        wire-mcp-servers (when-let [servers (:mcp-servers config)]
                           (util/mcp-servers->wire servers))
        wire-custom-agents (when-let [agents (:custom-agents config)]
                             (mapv custom-agent->wire agents))
        wire-default-agent (when-let [agent (:default-agent config)]
                             (util/clj->wire agent))
        wire-infinite-sessions (when-let [is (:infinite-sessions config)]
                                 (util/clj->wire is))
        wire-commands (some-> (:commands config) commands->wire)
        config-dir (or (:config-directory config) (:config-dir config))
        wire-large-output (some-> (:large-output config) large-output->wire)
        wire-memory (when-let [m (:memory config)]
                      (util/clj->wire m))
        wire-providers (when-let [ps (:providers config)]
                         (mapv named-provider->wire ps))
        wire-models (when-let [ms (:models config)]
                      (mapv provider-model->wire ms))
        wire-factories (when (contains? config :factories)
                         (mapv factory/factory-meta (:factories config)))]
    (cond-> {:session-id session-id}
      (:client-name config) (assoc :client-name (:client-name config))
      (:model config) (assoc :model (:model config))
      (:github-token config) (assoc :git-hub-token (:github-token config))
      (:github-token-provider-registration-id config)
      (assoc :git-hub-token-provider-registration-id
             (:github-token-provider-registration-id config))
      wire-tools (assoc :tools wire-tools)
      wire-commands (assoc :commands wire-commands)
      wire-sys-msg (assoc :system-message wire-sys-msg)
      (:available-tools config) (assoc :available-tools (:available-tools config))
      (:excluded-tools config) (assoc :excluded-tools (:excluded-tools config))
      (:tool-search config) (assoc :tool-search (:tool-search config))
      ;; SDK always sends `toolFilterPrecedence: "excluded"` (upstream PR #1428).
      ;; See `build-create-session-params` for rationale.
      true (assoc :tool-filter-precedence "excluded")
      wire-provider (assoc :provider wire-provider)
      wire-providers (assoc :providers wire-providers)
      wire-models (assoc :models wire-models)
      wire-factories (assoc :factories wire-factories)
      (:exp-assignments config) (assoc :exp-assignments (:exp-assignments config))
      (some? (:enable-experimental-mode? config))
      (assoc :isExperimentalMode (:enable-experimental-mode? config))
      true (assoc :request-permission
                  (not (identical? (:on-permission-request config)
                                   default-join-session-permission-handler)))
      (:capi config) (assoc :capi (util/clj->wire (:capi config)))

      ;; Session options (upstream PR #1865).
      (:excluded-builtin-agents config)
      (assoc :excluded-builtin-agents (:excluded-builtin-agents config))

      (some? (:enable-citations config))
      (assoc :enable-citations (:enable-citations config))

      (:session-limits config)
      (assoc :session-limits (util/clj->wire (:session-limits config)))

      (some? (:streaming? config)) (assoc :streaming (:streaming? config))
      wire-mcp-servers (assoc :mcp-servers wire-mcp-servers)
      (contains? config :disabled-mcp-servers)
      (assoc :disabled-mcp-servers (:disabled-mcp-servers config))
      (:github-mcp-tool-config config)
      (assoc :githubMcpToolConfig
             (github-mcp-tool-config->wire (:github-mcp-tool-config config)))
      wire-custom-agents (assoc :custom-agents wire-custom-agents)
      (some? (:custom-agents-local-only config))
      (assoc :custom-agents-local-only (:custom-agents-local-only config))
      wire-default-agent (assoc :default-agent wire-default-agent)
      config-dir (assoc :config-dir config-dir)
      (:skill-directories config) (assoc :skill-directories (:skill-directories config))
      (:instruction-directories config) (assoc :instruction-directories (:instruction-directories config))
      (:disabled-skills config) (assoc :disabled-skills (:disabled-skills config))
      (:plugin-directories config) (assoc :plugin-directories (:plugin-directories config))
      wire-large-output (assoc :large-output wire-large-output)
      wire-infinite-sessions (assoc :infinite-sessions wire-infinite-sessions)
      wire-memory (assoc :memory wire-memory)
      (:reasoning-effort config) (assoc :reasoning-effort (:reasoning-effort config))
      (:reasoning-summary config) (assoc :reasoning-summary (:reasoning-summary config))
      (contains? config :context-tier)
      (assoc :context-tier (util/context-tier->wire (:context-tier config)))
      (:agent config) (assoc :agent (:agent config))
      true (assoc :request-user-input (boolean (:on-user-input-request config)))
      true (assoc :request-elicitation (boolean (:on-elicitation-request config)))
      (contains? config :ask-user-variant)
      (assoc :ask-user-variant (name (:ask-user-variant config)))
      (request-mcp-apps? config) (assoc :requestMcpApps true)
      true (assoc :request-exit-plan-mode (boolean (:on-exit-plan-mode config)))
      true (assoc :request-auto-mode-switch (boolean (:on-auto-mode-switch config)))
      true (assoc :hooks (boolean (some identity (vals (:hooks config)))))
      (:working-directory config) (assoc :working-directory (:working-directory config))
      (contains? config :additional-directories)
      (assoc :additional-directories (:additional-directories config))
      (some? (:disable-resume? config)) (assoc :disable-resume (:disable-resume? config))
      (some? (:continue-pending-work? config))
      (assoc :continue-pending-work (:continue-pending-work? config))
      (some? (:enable-config-discovery config))
      (assoc :enable-config-discovery (:enable-config-discovery config))
      (some? (:enable-session-telemetry? config))
      (assoc :enable-session-telemetry (:enable-session-telemetry? config))
      (:remote-session config)
      (assoc :remote-session (name (:remote-session config)))
      (contains? config :feature-flags)
      (assoc :feature-flags (:feature-flags config))
      (:model-capabilities config)
      (assoc :model-capabilities
             (util/model-capabilities->wire (:model-capabilities config)))
      ;; Round 6 (upstream schema 1.0.56-1) per-session multitenancy /
      ;; storage knobs — mirror the set forwarded on session.create. The
      ;; `mcpOAuthTokenStorage` wire key (capital `OA`) bypasses csk via
      ;; a string key (see `build-create-session-params` for rationale).
      (:mcp-oauth-token-storage config)
      (assoc "mcpOAuthTokenStorage" (name (:mcp-oauth-token-storage config)))
      (:embedding-cache-storage config)
      (assoc :embedding-cache-storage (name (:embedding-cache-storage config)))
      (some? (:skip-embedding-retrieval config))
      (assoc :skip-embedding-retrieval (:skip-embedding-retrieval config))
      (:organization-custom-instructions config)
      (assoc :organization-custom-instructions (:organization-custom-instructions config))
      (some? (:enable-on-demand-instruction-discovery config))
      (assoc :enable-on-demand-instruction-discovery (:enable-on-demand-instruction-discovery config))
      (some? (:enable-file-hooks config))
      (assoc :enable-file-hooks (:enable-file-hooks config))
      (some? (:enable-file-change-tracking? config))
      (assoc :enable-file-change-tracking (:enable-file-change-tracking? config))
      (some? (:enable-host-git-operations config))
      (assoc :enable-host-git-operations (:enable-host-git-operations config))
      (some? (:enable-session-store config))
      (assoc :enable-session-store (:enable-session-store config))
      (some? (:enable-skills config))
      (assoc :enable-skills (:enable-skills config))
      ;; Upstream PR #1604: seed the open-canvases snapshot on resume/join.
      ;; Per-canvas wire conversion is explicit so the opaque `:input` map is
      ;; passed through verbatim (see `canvas-instance->wire`). Mirrors upstream
      ;; client.ts which sends `openCanvases: config.openCanvases` directly: an
      ;; explicit empty vector is forwarded so callers can deliberately clear/
      ;; seed an empty snapshot, while a missing `:open-canvases` key omits the
      ;; param entirely.
      (contains? config :open-canvases)
      (assoc :open-canvases (mapv canvas-instance->wire (:open-canvases config)))
      true (assoc :include-sub-agent-streaming-events
                  (if (some? (:include-sub-agent-streaming-events? config))
                    (:include-sub-agent-streaming-events? config)
                    true))
      ;; csk camelCases "github" -> "Github", but upstream expects the
      ;; "GitHub" acronym (enableGitHubTelemetryForwarding). Assoc the exact
      ;; wire keyword; clj->wire's camelCasing is idempotent on it.
      (true? (:enable-github-telemetry-forwarding? config))
      (assoc :enableGitHubTelemetryForwarding true)
      ;; enableManagedSettings + canvasProvider are SessionConfigBase fields
      ;; (upstream PRs #1925, #1847), honored on resume/join as well as create.
      (some? (:enable-managed-settings? config))
      (assoc :enable-managed-settings (:enable-managed-settings? config))
      (:managed-settings config)
      (assoc :managed-settings (managed-settings->wire (:managed-settings config)))
      (some? (:request-extensions? config))
      (assoc :request-extensions (:request-extensions? config))
      (some? (:extension-sdk-path config))
      (assoc :extension-sdk-path (:extension-sdk-path config))
      (some? (:extension-info config))
      (assoc :extension-info (:extension-info config))
      (:canvas-provider config)
      (assoc :canvas-provider (:canvas-provider config))
      (seq (:requested-environment-variables config))
      (assoc :requested-environment-variables
             (:requested-environment-variables config))
      true (assoc :env-value-mode "direct"))))

(defn- pre-register-session
  "Create and register a session in client state before the RPC call.
   This ensures early events (e.g. session.start) are not dropped.
   Returns the CopilotSession handle."
  [client session-id config]
  (session/create-session client session-id
                          {:tools (:tools config)
                           :commands (:commands config)
                           :on-permission-request (:on-permission-request config)
                           :on-user-input-request (:on-user-input-request config)
                           :on-elicitation-request (:on-elicitation-request config)
                           :on-exit-plan-mode (:on-exit-plan-mode config)
                           :on-auto-mode-switch (:on-auto-mode-switch config)
                           :on-mcp-auth-request (:on-mcp-auth-request config)
                           :hooks (:hooks config)
                           :on-event (:on-event config)
                           :config config}))

(defn- ensure-session-fs-handler-factory!
  "When the client has sessionFs enabled, validate that the config provides
   `:create-session-fs-handler` (which builds the per-session handler).
   Throws fail-fast with a clear message; allows the cloud-no-id flow to
   bail out BEFORE issuing the RPC instead of inside the reader-thread
   callback."
  [client config]
  (when (:session-fs client)
    (when-not (:create-session-fs-handler config)
      (throw (ex-info (str ":create-session-fs-handler is required in session config "
                           "when :session-fs is enabled in client options.")
                      {:config (redact-secrets config)})))))

(defn- prepare-session-fs-handler
  "Construct the per-session sessionFs handler. Caller is responsible for
   sessionFs validation (use `ensure-session-fs-handler-factory!` before
   calling). Returns nil when sessionFs is disabled on the client."
  [client session config]
  (when (:session-fs client)
    (when-let [factory (:create-session-fs-handler config)]
      (session/adapt-session-fs-handler (factory session)))))

(defn- install-session-fs-handler!
  [client session-id handler]
  (when (:session-fs client)
    (session/set-session-fs-handler! client session-id handler)))

(defn- prepare-local-session-setup!
  "Prepare every local session resource before issuing a create/resume RPC.
   Any failure is thrown synchronously and rolls back the complete provisional
   transaction, restoring a prior session registration when resuming."
  [client session-id config transform-callbacks build-params]
  (let [snapshot (session-registration-snapshot client session-id)
        [wire-config registration-id]
        (register-session-github-token-provider client config session-id)
        rollback-provider
        (fn [failure]
          (preserve-setup-failure!
           failure
           (td/collect
            [(td/attempt
              {:operation :rollback
               :resource :github-token-provider
               :session-id session-id}
              (rollback-github-token-provider!
               client registration-id))])))
        params
        (try
          (build-params wire-config)
          (catch Throwable failure
            (throw (rollback-provider failure))))
        session
        (try
          (pre-register-session client session-id config)
          (catch Throwable failure
            (throw (rollback-provider failure))))]
    (try
      (session/register-transform-callbacks!
       client session-id transform-callbacks)
      (let [fs-handler (prepare-session-fs-handler client session config)]
        (install-session-fs-handler! client session-id fs-handler))
      {:params params
       :registration-id registration-id
       :session session
       :snapshot snapshot
       :wire-config wire-config}
      (catch Throwable failure
        (throw
         (fail-session-setup!
          client session-id registration-id false snapshot failure))))))

(defn- prepare-deferred-session-request!
  "Register provider state and build RPC params when the runtime assigns the
   session ID. Parameter failures roll back the provisional provider."
  [client config build-params]
  (let [registration-id* (atom nil)]
    (try
      (let [[wire-config registration-id]
            (register-session-github-token-provider client config nil)
            _ (reset! registration-id* registration-id)]
        {:params (build-params wire-config)
         :registration-id registration-id
         :wire-config wire-config})
      (catch Throwable failure
        (throw
         (preserve-setup-failure!
          failure
          (td/collect
           [(td/attempt
             {:operation :rollback
              :resource :github-token-provider}
             (rollback-github-token-provider!
              client @registration-id*))])))))))

(defn- register-mcp-auth-interest!
  "Register interest in `mcp.oauth_required` events for this session (upstream
   PR #1669). Without registered interest the runtime delegates OAuth to a
   browserless cached-token fallback and never broadcasts the event, so the
   `:on-mcp-auth-request` handler would silently never fire. No-op when the
   session has no handler. Mirrors upstream `client.ts`, which awaits this RPC
   (before `session.resume`, and after `session.create` but before the
   mode-options patch) and lets a failure reject the session rather than
   degrade silently. Sync variant."
  [client session-id config]
  (when (:on-mcp-auth-request config)
    (let [{:keys [connection-io]} @(:state client)]
      (proto/send-request! connection-io "session.eventLog.registerInterest"
                           {:session-id session-id
                            :event-type "mcp.oauth_required"}
                           30000))))

(defn- <register-mcp-auth-interest!
  "Async variant of `register-mcp-auth-interest!`. Returns a channel that yields
   `:ok` on success (including the no-op case) or a Throwable on failure. The
   channel always closes after one value."
  [client session-id config]
  (let [out (async/chan 1)]
    (if (:on-mcp-auth-request config)
      (let [{:keys [connection-io]} @(:state client)
            rpc-ch (proto/send-request connection-io "session.eventLog.registerInterest"
                                       {:session-id session-id
                                        :event-type "mcp.oauth_required"})]
        (go
          (let [response (<! rpc-ch)
                err (cond
                      (nil? response)
                      (ex-info "registerInterest (mcp.oauth_required) failed: RPC channel closed"
                               {:session-id session-id})
                      (:error response)
                      (ex-info (str "registerInterest (mcp.oauth_required) failed: "
                                    (get-in response [:error :message] "RPC error"))
                               {:session-id session-id :error (:error response)}))]
            (if err
              (>! out err)
              (>! out :ok))
            (close! out))))
      (do (async/put! out :ok)
          (close! out)))
    out))

(defn- make-create-session-inline-callback
  "Build the inline-response callback used by the cloud-no-id session
   creation path. The callback runs in the JSON-RPC reader thread; keep
   it minimal and non-blocking. On any failure it removes any partially
   registered session and delivers the error to `result-promise`; on
   success it delivers the live session.

   Note: `prepare-session-fs-handler` invokes a user-supplied
   `:create-session-fs-handler` factory. Document and rely on the
   convention that the factory is a fast, non-blocking constructor —
   if it issues another RPC it will deadlock the reader thread.
   `ensure-session-fs-handler-factory!` is called BEFORE the RPC so a
   missing factory cannot reach the callback."
  [client config transform-callbacks registration-id assigned-session-id
   result-promise]
  (let [deliver-error! #(deliver result-promise %)]
    (fn [result]
      (try
        (let [assigned-id (:session-id result)]
          (if (or (not (string? assigned-id)) (str/blank? assigned-id))
            (deliver-error!
             (ex-info "session.create response did not include a sessionId for cloud session"
                      {:result result}))
            (do
              (reset! assigned-session-id assigned-id)
              (let [session (pre-register-session client assigned-id config)]
                (assign-github-token-provider! client registration-id assigned-id)
                (session/register-transform-callbacks! client assigned-id transform-callbacks)
                (install-session-fs-handler!
                 client assigned-id
                 (prepare-session-fs-handler client session config))
                (deliver result-promise session)))))
        (catch Throwable t
          (deliver-error! t))))))

(defn create-session
  "Create a new conversation session.
   
   Config options (`:on-permission-request` is **optional** since upstream
   PR #1308 — omit it to leave permission requests pending for manual
   resolution via `copilot/handle-pending-permission-request!`):
   - :on-permission-request - Permission handler function (optional, e.g. `approve-all`).
                              When omitted, permission requests are surfaced as
                              `:copilot/permission.requested` events and remain
                              pending until resolved by the application.
   - :session-id         - Custom session ID
   - :client-name        - Client name to identify the application (included in User-Agent header)
   - :model              - Model to use (e.g., \"gpt-5.4\")
   - :tools              - Vector of tool definitions
   - :commands           - Vector of command definitions (slash commands for TUI).
                           An omitted command :description is sent as an empty string.
   - :system-message     - System message config
   - :available-tools    - List of allowed tool names
   - :excluded-tools     - List of excluded tool names
   - :tool-search        - Tool discovery config {:enabled :defer-threshold}
   - :provider           - Custom provider config (BYOK)
   - :capi               - Copilot API options {:enable-web-socket-responses boolean
                                                :auto-tier :efficiency|:balance|:intelligence}.
                           Auto-tier applies on create and cold resume; a warm
                           resume cannot change an already-resident session.
   - :feature-flags      - String-to-boolean feature flag map. Omission and {} are distinct.
   - :streaming?         - Enable streaming
   - :mcp-servers        - MCP server configs map
   - :custom-agents      - Custom agent configs
   - :default-agent      - Built-in agent config, e.g. {:excluded-tools [\"private_tool\"]}
   - :config-directory   - Override config directory for CLI (configDir).
                           :config-dir remains a deprecated alias.
   - :skill-directories  - Additional skill directories to load
   - :disabled-skills    - Disable specific skills by name
   - :included-builtin-skills - Allowlist of runtime-bundled skill names. Empty client mode
                                defaults to []; CLI mode omits it unless explicitly provided.
   - :large-output       - Tool output handling config
                           {:enabled :max-size-bytes :output-directory}.
                           :output-dir remains a deprecated alias.
   - :working-directory  - Working directory for the session (tool operations relative to this)
   - :infinite-sessions  - Infinite session config for automatic context compaction
                           {:enabled (default true)
                            :background-compaction-threshold (0.0-1.0, default 0.80)
                            :buffer-exhaustion-threshold (0.0-1.0, default 0.95)}
   - :memory             - Persistent memory config {:enabled boolean} (upstream PR #1617).
                           Sent on both create and resume; omitted when unset.
   - :reasoning-effort   - Reasoning effort level: \"low\", \"medium\", \"high\", or \"xhigh\" (PR #302)
   - :github-token       - Static GitHub token for this session (sent as gitHubToken).
                           Mutually exclusive with :github-token-provider.
   - :github-token-provider - Refreshable session credential callback. Receives
                              {:host :session-id? :reason keyword}; known reasons
                              are :initial and :refresh, while future nonblank
                              reasons pass through for forward compatibility.
                              Returns
                              {:kind :token :access-token :expires-in :token-type?} or
                              {:kind :cancelled}, directly or on a core.async channel.
                              Both result variants may contain extension fields.
                              Only an opaque registration id is sent in session
                              configuration, but acquired credentials cross the
                              JSON-RPC connection to the CLI. Provider work runs
                              on a bounded client-owned executor. Requires
                              SDK-owned child-process stdio or TCP; every explicit
                              external :cli-url is rejected.
   - :on-user-input-request - Handler for ask_user requests (PR #269)
   - :ask-user-variant  - Built-in ask_user shape: :legacy or :elicitation.
   - :on-elicitation-request - Handler for elicitation requests from the agent (upstream PRs #908, #960).
                               When provided, sends requestElicitation=true and enables the
                               elicitation capability. Single-arg handler receives an ElicitationContext
                               map with :session-id, :message, :requested-schema, :mode,
                               :elicitation-source, :url. Returns an ElicitationResult map; omit
                               :content for decline/cancel results.
   - :on-mcp-auth-request - Handler for interactive MCP OAuth requests (upstream PR #1669).
                            When provided, the SDK registers interest in `mcp.oauth_required`
                            so the runtime delegates browser-based OAuth to this handler instead
                            of silently using a cached token. The 2-arg handler receives an
                            McpAuthRequest map ({:request-id :server-name :server-url :reason
                            :www-authenticate-params :resource-metadata :static-client-config})
                            and a context map {:session-id}; it may return a channel. Return a
                            map with :access-token (plus optional :token-type, :expires-in) to
                            answer with a token; return nil, {:kind :cancelled}, or throw to cancel.
   - :hooks              - Lifecycle hooks map (PR #269):
                           {:on-pre-tool-use, :on-pre-mcp-tool-call,
                            :on-post-tool-use, :on-post-tool-use-failure,
                            :on-user-prompt-submitted,
                            :on-session-start, :on-session-end, :on-error-occurred}
                           See `doc/reference/API.md` for hook input/output shapes.
                           `:on-pre-mcp-tool-call` (upstream PR #1366) fires before
                           an MCP tool call is dispatched; handler returning
                           `{:meta-to-use {...}}` replaces the request `_meta`,
                           `{:meta-to-use nil}` removes it, and an empty / missing
                           `:meta-to-use` preserves the existing `_meta`.
                           `:on-post-tool-use-failure` (upstream PR #1421) fires
                           after a tool execution whose `:result-type` was
                           `\"failure\"`; `:on-post-tool-use` only fires for
                           successful results. Handler input has `:tool-name`,
                           `:tool-args`, `:error` (string), plus base hook fields.
                           Optional output: `{:additional-context \"...\"}`.
   - :on-event           - Event handler (1-arg fn) registered before the RPC call.
                           Guarantees early events like session.start are not missed.
   - :enable-config-discovery - Boolean. Auto-discover .mcp.json, .vscode/mcp.json, skills, etc.
                                Instruction files are always loaded regardless. (upstream PR #1044)
   - :enable-mcp-apps    - Boolean (@experimental). Set true only when the host can render
                           `ui://` MCP App bundles. Explicit true sends `requestMcpApps: true`
                           on create; false and omission do not send the wire key.
                           (https://github.com/github/copilot-sdk/pull/1335)
   - :model-capabilities - Model capabilities override map (upstream PR #1029).
                           DeepPartial of model capabilities, e.g.
                           {:supports {:vision true}
                            :limits {:max-prompt-tokens 128000}}
                           Stable public-SDK fields: :supports {:vision :reasoning-effort},
                           :limits {:max-prompt-tokens :max-context-window-tokens :vision {..}}.
                           :adaptive-thinking and :max-output-tokens are experimental
                           CLI-protocol extras (not in the public Node SDK type).
                           Deprecated :model-supports / :model-limits aliases are still accepted.
   - :include-sub-agent-streaming-events? - Boolean. When true (default), streaming events from
                                            sub-agents are forwarded to this session's event stream.
                                            (upstream PR #1108)
   - :excluded-builtin-agents - Vector of strings. Names of built-in agents to exclude/hide from the
                                session. Forwarded as `excludedBuiltinAgents`. (upstream PR #1865)
   - :enable-citations - Boolean (@experimental). Enable native model citations. Forwarded as
                         `enableCitations`. (upstream PR #1865)
   - :enable-file-change-tracking? - Boolean. Opt into capturing file changes from the first
                                    tracked turn. Omission sends no wire key; explicit false
                                    or true is forwarded as `enableFileChangeTracking`.
                                    Tracking enables stable file-change and snapshot events;
                                    low-level rewind RPCs remain experimental and are not exposed.
   - :session-limits     - Map (@experimental). Per-session accounting limits, e.g.
                           {:max-ai-credits 100}. Forwarded as `sessionLimits`. (upstream PR #1865)
   - :enable-managed-settings? - Boolean (opt-in). When true, the runtime self-fetches enterprise
                           managed settings (bypass-permissions policy) at session bootstrap using the
                           session's `:github-token`, which must be set (the runtime fails closed
                           otherwise). Forwarded verbatim as `enableManagedSettings` — an explicit
                           `false` is sent on the wire. (upstream PR #1925)
   - :managed-settings   - Caller-supplied enterprise policy. Optional
                           {:permissions {:disable-bypass-permissions-mode keyword
                                          :deny [...] :ask [...] :allow [...]}}.
                           The bypass-policy value is a simple nonblank keyword;
                           known values are :disable and :allow-auto-only.
   - :request-extensions? - Boolean. Opt into extension management and dispatch for this
                            connection. Explicit false is preserved; omission sends no key.
   - :extension-sdk-path - String path override for the SDK injected into extension
                           subprocesses. Invalid paths fall back to the bundled SDK.
   - :extension-info     - Stable extension identity `{:source string :name string}`.
   - :canvas-provider    - Map `{:id \"...\" :name \"...\"}` (`:name` optional). Identifies the canvas
                           provider for the session. Forwarded as `canvasProvider`. (upstream PR #1847)
   - :remote-session     - Keyword. Per-session Mission Control remote mode: :off, :export, or :on.
                           Forwarded as `remoteSession` on session.create. When omitted, the CLI
                           applies its default. (upstream PR #1295, CLI 1.0.48)
   - :cloud              - Map. Creates a remote session in the cloud instead of a local session.
                           Optional `:repository` associates the cloud session with a GitHub
                           repository: `{:repository {:owner \"...\" :name \"...\" :branch \"...\"}}`.
                           `:branch` is optional. Forwarded as `cloud` on session.create.
                           (upstream PR #1306)

                           When `:cloud` is set and no `:session-id` is provided, the server
                           assigns a sessionId and the SDK registers the session under that
                           id; the session is registered synchronously before any subsequent
                           server-initiated requests can be dispatched (upstream PR #1479).

                           **Important — sessionFs + cloud-no-id contract:** when the client
                           is constructed with `:session-fs` enabled AND this call defers the
                           session id to the server (cloud + no `:session-id`), the
                           `:create-session-fs-handler` factory is invoked **on the JSON-RPC
                           reader thread** inside an inline-response callback (so the session
                           is registered before subsequent notifications). The factory MUST be
                           a fast, non-blocking constructor and MUST NOT call back into the
                           SDK (no further RPCs, no waiting on session events) — doing so
                           deadlocks the reader thread. The factory call is unconstrained on
                           every other path (standard local sessions, cloud-with-caller-id,
                           and resume), where it runs on the caller's thread.
   
   Returns a CopilotSession."
  [client config]
  (log/debug "Creating session with config: " (select-keys config [:model :session-id]))
  (validate-session-config! config)
  (validate-tool-filters! config)
  (validate-empty-mode-session-requirements! client config)
  (ensure-github-token-provider-transport! client config)
  (ensure-connected! client)
  (ensure-session-fs-handler-factory! client config)
  (let [config (normalize-config-for-mode client config)
        {:keys [connection-io]} @(:state client)
        trace-ctx (get-trace-context (:on-get-trace-context client))
        {:keys [transform-callbacks]} (extract-transform-callbacks (:system-message config))
        cloud? (some? (:cloud config))
        caller-session-id (:session-id config)
        defer-session-id? (and cloud? (not caller-session-id))
        local-session-id (when-not defer-session-id?
                           (or caller-session-id
                               (str (java.util.UUID/randomUUID))))]
    (if defer-session-id?
      ;; Cloud session without a caller-supplied id (upstream PR #1479): omit
      ;; `sessionId` from the wire params and let the server assign one. The
      ;; SDK registers the session under the server-returned id inside an
      ;; inline-response callback so any session-scoped notifications that
      ;; arrive after the response are routed to the correct session.
      (let [{:keys [params registration-id]}
            (prepare-deferred-session-request!
             client config
             #(merge trace-ctx (build-create-session-params %)))
            assigned-session-id (atom nil)
            remote-accepted? (atom false)
            session-promise (promise)
            on-inline (make-create-session-inline-callback
                       client config transform-callbacks registration-id
                       assigned-session-id session-promise)]
        (try
          (let [result (proto/send-request! connection-io "session.create" params 60000
                                            {:on-response-inline on-inline})
                _ (reset! remote-accepted? true)
                registered (deref session-promise 0 :not-delivered)]
            (cond
              (= registered :not-delivered)
              (throw (ex-info "Internal error: inline-response callback did not run for session.create"
                              {:result result}))

              (instance? Throwable registered)
              (throw registered)

              :else
              (let [session registered
                    session-id (:session-id session)]
                (session/set-workspace-path! client session-id (:workspace-path result))
                (session/set-capabilities! client session-id (:capabilities result))
                (register-mcp-auth-interest! client session-id config)
                (apply-session-options-update! client session config)
                (commit-github-token-provider! client session-id registration-id)
                (log/info "Session created (cloud, server-assigned id): " session-id)
                session)))
          (catch Throwable t
            (throw
             (fail-session-setup!
              client @assigned-session-id registration-id @remote-accepted?
              nil t)))))
      ;; Standard path: client supplies (or generates) the sessionId up front.
      (let [session-id local-session-id
            {:keys [params registration-id session snapshot]}
            (prepare-local-session-setup!
             client session-id config transform-callbacks
             #(merge trace-ctx
                     (assoc (build-create-session-params %)
                            :session-id session-id)))
            remote-accepted? (atom false)]
        (try
          (let [result (proto/send-request! connection-io "session.create" params)
                _ (reset! remote-accepted? true)
                returned-id (:session-id result)]
            (when (and (string? returned-id)
                       (not (str/blank? returned-id))
                       (not= returned-id session-id))
              (throw (ex-info "session.create returned a sessionId that differs from the requested id"
                              {:requested session-id :returned returned-id})))
            (session/set-workspace-path! client session-id (:workspace-path result))
            (session/set-capabilities! client session-id (:capabilities result))
            (register-mcp-auth-interest! client session-id config)
            (apply-session-options-update! client session config)
            (commit-github-token-provider! client session-id registration-id)
            (log/info "Session created: " session-id)
            session)
          (catch Throwable t
            (throw
             (fail-session-setup!
              client session-id registration-id @remote-accepted?
              snapshot t))))))))

(defn- resume-session-result*
  [client session-id config]
  (validate-provider-config! config)
  (validate-tool-filters! config)
  (validate-empty-mode-session-requirements! client config)
  (ensure-github-token-provider-transport! client config)
  (ensure-connected! client)
  (ensure-session-fs-handler-factory! client config)
  (let [config (normalize-config-for-mode client config)
        {:keys [connection-io]} @(:state client)
        trace-ctx (get-trace-context (:on-get-trace-context client))
        {:keys [transform-callbacks]} (extract-transform-callbacks (:system-message config))
        {:keys [params registration-id session snapshot]}
        (prepare-local-session-setup!
         client session-id config transform-callbacks
         #(merge trace-ctx
                 (build-resume-session-params session-id %)))
        remote-accepted? (atom false)]
    (try
      (register-mcp-auth-interest! client session-id config)
      (let [result (proto/send-request! connection-io "session.resume" params)
            _ (reset! remote-accepted? true)]
        (session/set-workspace-path! client session-id (:workspace-path result))
        (session/set-capabilities! client session-id (:capabilities result))
        (session/set-open-canvases! client session-id (:open-canvases result))
        (apply-session-options-update! client session config)
        (commit-github-token-provider! client session-id registration-id)
        {:session session
         :result result})
      (catch Throwable t
        (throw
         (fail-session-setup!
          client session-id registration-id @remote-accepted?
          snapshot t))))))

(defn- resume-session*
  [client session-id config]
  (:session (resume-session-result* client session-id config)))

(defn resume-session
  "Resume an existing session by ID.
   
   Config options (`:on-permission-request` is **optional** since upstream
   PR #1308 — omit it to leave permission requests pending for manual
   resolution via `copilot/handle-pending-permission-request!`):
   - :on-permission-request - Permission handler function (optional, e.g. `approve-all`).
                              When omitted, permission requests are surfaced as
                              `:copilot/permission.requested` events and remain
                              pending until resolved by the application.
   - :client-name        - Client name to identify the application (included in User-Agent header)
   - :model              - Change the model for the resumed session
   - :tools              - Tools exposed to the CLI server
   - :system-message     - System message configuration {:mode :content}
   - :available-tools    - List of tool names to allow
   - :excluded-tools     - List of tool names to disable
   - :tool-search        - Tool discovery config {:enabled :defer-threshold}
   - :provider           - Custom provider configuration (BYOK)
   - :capi               - Copilot API options {:enable-web-socket-responses boolean
                                                :auto-tier :efficiency|:balance|:intelligence}.
                           Auto-tier applies to a cold resume; a warm resume
                           cannot change an already-resident session.
   - :feature-flags      - String-to-boolean feature flag map. Omission and {} are distinct.
   - :streaming?         - Enable streaming responses
   - :mcp-servers        - MCP server configurations, applied as part of session.resume.
   - :custom-agents      - Custom agent configurations
   - :default-agent      - Built-in agent config, e.g. {:excluded-tools [\"private_tool\"]}
   - :config-directory   - Override configuration directory.
                           :config-dir remains a deprecated alias.
   - :skill-directories  - Directories to load skills from
   - :disabled-skills    - Skills to disable
   - :included-builtin-skills - Allowlist of runtime-bundled skill names. Empty client mode
                                defaults to []; CLI mode omits it unless explicitly provided.
   - :infinite-sessions  - Infinite session configuration
   - :memory             - Persistent memory config {:enabled boolean} (upstream PR #1617).
                           Parity with create-session; omitted when unset.
   - :reasoning-effort   - Reasoning effort level: \"low\", \"medium\", \"high\", or \"xhigh\"
   - :github-token       - Static GitHub token for this session (sent as gitHubToken).
                           Mutually exclusive with :github-token-provider.
   - :github-token-provider - Refreshable session credential callback. Same shape,
                              lifecycle, and transport-security requirements as
                              create-session; a successful resume replaces the
                              session's previous provider.
   - :on-user-input-request - Handler for ask_user requests
   - :ask-user-variant  - Built-in ask_user shape: :legacy or :elicitation.
   - :on-elicitation-request - Handler for elicitation requests (upstream PRs #908, #960).
                               Single-arg handler receives an ElicitationContext map with
                               :session-id, :message, :requested-schema, :mode,
                               :elicitation-source, :url. Returns an ElicitationResult map; omit
                               :content for decline/cancel results.
   - :on-mcp-auth-request - Handler for interactive MCP OAuth requests (upstream PR #1669).
                            Same shape as `create-session`. On resume, interest in
                            `mcp.oauth_required` is registered before the resume RPC so OAuth
                            needed while the runtime replays state reaches the handler.
   - :hooks              - Lifecycle hooks map
   - :on-event           - Event handler (1-arg fn) registered before the RPC call.
                           Guarantees early events like session.start are not missed.
   - :enable-config-discovery - Boolean. Auto-discover .mcp.json, skills, etc. (upstream PR #1044)
   - :enable-mcp-apps    - Boolean (@experimental). See `create-session`; explicit true sends
                           `requestMcpApps: true` on resume, while false and omission send nothing.
                           (https://github.com/github/copilot-sdk/pull/1335)
   - :model-capabilities - Model capabilities override map (upstream PR #1029).
                           Same shape as `create-session`; :adaptive-thinking and
                           :max-output-tokens are experimental CLI-protocol extras.
   - :include-sub-agent-streaming-events? - Boolean. When true (default), streaming events from
                                            sub-agents are forwarded to this session's event stream.
                                            (upstream PR #1108)
   - :enable-session-telemetry? - Boolean. See `create-session` (upstream PR #1224).
   - :excluded-builtin-agents - Vector of strings. See `create-session` (upstream PR #1865).
   - :enable-citations - Boolean (@experimental). See `create-session` (upstream PR #1865).
   - :enable-file-change-tracking? - Boolean. See `create-session`. On resume, tracking can
                                     start only when the runtime still has a valid baseline;
                                     earlier untracked turns cannot be reconstructed.
   - :session-limits     - Map (@experimental). See `create-session` (upstream PR #1865).
   - :enable-managed-settings? - Boolean. See `create-session` (upstream PR #1925).
   - :managed-settings   - Structured enterprise policy. See `create-session`.
   - :request-extensions? - Boolean. See `create-session`; explicit false is forwarded.
   - :extension-sdk-path - String path override for extension subprocesses. See `create-session`.
   - :extension-info     - Stable extension identity `{:source string :name string}`.
   - :canvas-provider    - Map `{:id .. :name ..}`. See `create-session` (upstream PR #1847).
   - :on-exit-plan-mode  - Handler for exitPlanMode.request RPCs. See `create-session`
                           (upstream PR #1228).
   - :on-auto-mode-switch - Handler for autoModeSwitch.request RPCs. See `create-session`
                            (upstream PR #1228).
   
   Returns a CopilotSession."
  [client session-id config]
  (when-not (s/valid? ::specs/resume-session-config config)
    (let [safe-config (redact-secrets config)]
      (throw (ex-info (config-validation-message
                       "Invalid resume session config"
                       config)
                      {:config safe-config
                       :explain (s/explain-data ::specs/resume-session-config safe-config)}))))
  (resume-session* client session-id config))

(defn <create-session
  "Async version of create-session. Returns a channel that delivers a CopilotSession.

   Same config options as create-session (`:on-permission-request` is **optional**
   since upstream PR #1308).
   Configuration validation, connection setup, and local preparation are
   performed synchronously. For every path whose session id is known before the
   RPC, this includes construction of the session filesystem handler. Failures
   in those steps throw before a result channel is returned. A cloud create
   without a caller-supplied session id defers filesystem-handler construction
   until the server returns the id, so that failure is delivered on the channel.
   The RPC call parks instead of blocking, making the returned channel safe to
   consume inside go blocks.

   On RPC or later setup error, delivers an ExceptionInfo to the channel (not nil).
   Callers should check the result with (instance? Throwable result).

   Note: the sessionFs + cloud-no-id constraint described on `create-session`
   applies symmetrically here — when `:cloud` is set without a `:session-id`
   and the client has `:session-fs` enabled, the `:create-session-fs-handler`
   factory runs on the reader thread inside an inline-response callback and
   must be non-blocking (no further RPCs / no session-event waits).

   Usage:
     (go
       (let [result (<! (<create-session client {:on-permission-request copilot/approve-all
                                                 :model \"gpt-5.4\"}))]
         (if (instance? Throwable result)
           (println \"Error:\" (ex-message result))
           ;; use result as session
           )))"
  [client config]
  (log/debug "Creating session (async) with config: " (select-keys config [:model :session-id]))
  (validate-session-config! config)
  (validate-tool-filters! config)
  (validate-empty-mode-session-requirements! client config)
  (ensure-github-token-provider-transport! client config)
  (ensure-connected! client)
  (ensure-session-fs-handler-factory! client config)
  (let [config (normalize-config-for-mode client config)
        {:keys [connection-io]} @(:state client)
        trace-ctx (get-trace-context (:on-get-trace-context client))
        {:keys [transform-callbacks]} (extract-transform-callbacks (:system-message config))
        cloud? (some? (:cloud config))
        caller-session-id (:session-id config)
        defer-session-id? (and cloud? (not caller-session-id))
        local-session-id (when-not defer-session-id?
                           (or caller-session-id
                               (str (java.util.UUID/randomUUID))))]
    (if defer-session-id?
      ;; Cloud session, server-assigned id (upstream PR #1479). Register the
      ;; session inside an inline-response callback so the registration is
      ;; ordered before any subsequent session-scoped notifications.
      (let [{:keys [params registration-id]}
            (prepare-deferred-session-request!
             client config
             #(merge trace-ctx (build-create-session-params %)))
            fail-ch (fn [session-id remote-accepted? snapshot failure]
                      (fail-session-setup-async
                       client session-id registration-id remote-accepted?
                       snapshot failure))
            assigned-session-id (atom nil)
            remote-accepted? (atom false)
            session-promise (promise)
            on-inline (make-create-session-inline-callback
                       client config transform-callbacks registration-id
                       assigned-session-id session-promise)
            rpc-ch
            (try
              (proto/send-request connection-io "session.create" params
                                  {:on-response-inline on-inline})
              (catch Throwable t
                (fail-session-setup!
                 client nil registration-id false nil t)))]
        (if (instance? Throwable rpc-ch)
          (delivered-chan rpc-ch)
          (go
            (try
              (let [response (<! rpc-ch)]
                (cond
                  (nil? response)
                  (<! (fail-ch @assigned-session-id false nil
                               (ex-info "Session creation failed: RPC channel closed (cloud)" {})))

                  (:error response)
                  (let [err (:error response)]
                    (log/error "<create-session RPC error: " err)
                    (<! (fail-ch
                         @assigned-session-id false nil
                         (ex-info (str "Failed to create session: " (:message err))
                                  {:error err}))))

                  :else
                  (do
                    (reset! remote-accepted? true)
                    (let [registered (deref session-promise 0 :not-delivered)
                          result (:result response)]
                      (cond
                        (= registered :not-delivered)
                        (<! (fail-ch
                             @assigned-session-id true nil
                             (ex-info "Internal error: inline-response callback did not run for session.create"
                                      {:result result})))

                        (instance? Throwable registered)
                        (<! (fail-ch @assigned-session-id true nil registered))

                        :else
                        (let [session registered
                              session-id (:session-id session)]
                          (session/set-workspace-path! client session-id (:workspace-path result))
                          (session/set-capabilities! client session-id (:capabilities result))
                         ;; Register MCP-auth interest before the mode-options patch
                         ;; (upstream client.ts:1477). Each helper returns a Throwable
                         ;; on failure; the outer transaction owns cleanup.
                          (let [reg (<! (<register-mcp-auth-interest!
                                         client session-id config))]
                            (if (instance? Throwable reg)
                              (<! (fail-ch session-id true nil reg))
                              (let [r (<! (<apply-session-options-update!
                                           client session config))]
                                (if (instance? Throwable r)
                                  (<! (fail-ch session-id true nil r))
                                  (do
                                    (commit-github-token-provider! client session-id registration-id)
                                    (log/info "Session created (async, cloud, server-assigned id): " session-id)
                                    session)))))))))))
              (catch Throwable t
                (<! (fail-ch
                     @assigned-session-id @remote-accepted? nil t)))))))
      ;; Standard path: client supplies (or generates) the sessionId up front.
      (let [session-id local-session-id
            {:keys [params registration-id session snapshot]}
            (prepare-local-session-setup!
             client session-id config transform-callbacks
             #(merge trace-ctx
                     (assoc (build-create-session-params %)
                            :session-id session-id)))
            fail-ch (fn [session-id remote-accepted? snapshot failure]
                      (fail-session-setup-async
                       client session-id registration-id remote-accepted?
                       snapshot failure))
            remote-accepted? (atom false)
            rpc-ch
            (try
              (proto/send-request
               connection-io "session.create" params)
              (catch Throwable t
                (fail-session-setup!
                 client session-id registration-id false snapshot t)))]
        (if (instance? Throwable rpc-ch)
          (delivered-chan rpc-ch)
          (go
            (try
              (let [response (<! rpc-ch)]
                (if (nil? response)
                  (<! (fail-ch
                       session-id false snapshot
                       (ex-info "Session creation failed: RPC channel closed"
                                {:session-id session-id})))
                  (if-let [err (:error response)]
                    (do
                      (log/error "<create-session RPC error: " err)
                      (<! (fail-ch
                           session-id false snapshot
                           (ex-info (str "Failed to create session: " (:message err))
                                    {:error err}))))
                    (let [result (:result response)
                          returned-id (:session-id result)]
                      (reset! remote-accepted? true)
                      (if (and (string? returned-id)
                               (not (str/blank? returned-id))
                               (not= returned-id session-id))
                        (<! (fail-ch
                             session-id true snapshot
                             (ex-info "session.create returned a sessionId that differs from the requested id"
                                      {:requested session-id :returned returned-id})))
                        (do
                          (session/set-workspace-path! client session-id (:workspace-path result))
                          (session/set-capabilities! client session-id (:capabilities result))
                           ;; Register MCP-auth interest before the mode-options patch
                           ;; (upstream client.ts:1477); short-circuit on the first
                           ;; helper that returns a Throwable. The outer transaction
                           ;; owns cleanup.
                          (let [reg (<! (<register-mcp-auth-interest!
                                         client session-id config))]
                            (if (instance? Throwable reg)
                              (<! (fail-ch session-id true snapshot reg))
                              (let [r (<! (<apply-session-options-update!
                                           client session config))]
                                (if (instance? Throwable r)
                                  (<! (fail-ch session-id true snapshot r))
                                  (do
                                    (commit-github-token-provider! client session-id registration-id)
                                    (log/info "Session created (async): " session-id)
                                    session)))))))))))
              (catch Throwable t
                (<! (fail-ch
                     session-id @remote-accepted? snapshot t))))))))))
(defn <resume-session
  "Async version of resume-session. Returns a channel that delivers a CopilotSession.

   Same config options as resume-session (`:on-permission-request` is **optional**
   since upstream PR #1308).
   Configuration validation, connection setup, and local preparation, including
   session filesystem handler construction, are performed synchronously and
   throw before a result channel is returned. The RPC call parks instead of
   blocking, making the returned channel safe to consume inside go blocks.

   On RPC or later setup error, delivers an ExceptionInfo to the channel (not nil).
   Callers should check the result with (instance? Throwable result).

   Usage:
     (go
       (let [result (<! (<resume-session client session-id
                                         {:on-permission-request copilot/approve-all
                                          :model \"gpt-5.4\"}))]
         (if (instance? Throwable result)
           (println \"Error:\" (ex-message result))
           ;; use result as session
           )))"
  [client session-id config]
  (when-not (s/valid? ::specs/resume-session-config config)
    (let [safe-config (redact-secrets config)]
      (throw (ex-info (config-validation-message
                       "Invalid resume session config"
                       config)
                      {:config safe-config
                       :explain (s/explain-data ::specs/resume-session-config safe-config)}))))
  (validate-provider-config! config)
  (validate-tool-filters! config)
  (validate-empty-mode-session-requirements! client config)
  (ensure-github-token-provider-transport! client config)
  (ensure-connected! client)
  (ensure-session-fs-handler-factory! client config)
  (let [config (normalize-config-for-mode client config)
        {:keys [connection-io]} @(:state client)
        trace-ctx (get-trace-context (:on-get-trace-context client))
        {:keys [transform-callbacks]} (extract-transform-callbacks (:system-message config))
        {:keys [params registration-id session snapshot]}
        (prepare-local-session-setup!
         client session-id config transform-callbacks
         #(merge trace-ctx
                 (build-resume-session-params session-id %)))
        remote-accepted? (atom false)
        fail-ch (fn [remote-accepted? failure]
                  (fail-session-setup-async
                   client session-id registration-id remote-accepted? snapshot failure))]
    (go
      (try
          ;; Register MCP-auth interest BEFORE session.resume (upstream
          ;; client.ts:1578) so OAuth the runtime needs while processing resume
          ;; reaches the handler instead of silently using a cached token.
        (let [reg (<! (<register-mcp-auth-interest!
                       client session-id config))]
          (if (instance? Throwable reg)
            (<! (fail-ch false reg))
            (let [response
                  (<! (proto/send-request
                       connection-io "session.resume" params))]
              (if (nil? response)
                (<! (fail-ch
                     false
                     (ex-info "Session resume failed: RPC channel closed"
                              {:session-id session-id})))
                (if-let [err (:error response)]
                  (do
                    (log/error "<resume-session RPC error: " err)
                    (<! (fail-ch
                         false
                         (ex-info (str "Failed to resume session: " (:message err))
                                  {:error err :session-id session-id}))))
                  (let [result (:result response)]
                    (reset! remote-accepted? true)
                    (session/set-workspace-path! client session-id (:workspace-path result))
                    (session/set-capabilities! client session-id (:capabilities result))
                    (session/set-open-canvases! client session-id (:open-canvases result))
                    (let [r (<! (<apply-session-options-update!
                                 client session config))]
                      (if (instance? Throwable r)
                        (<! (fail-ch true r))
                        (do
                          (commit-github-token-provider! client session-id registration-id)
                          session)))))))))
        (catch Throwable t
          (<! (fail-ch @remote-accepted? t)))))))

(defn- project-granted-environment-variables
  [requested-names grants]
  (let [grants (or grants {})]
    (when-not (map? grants)
      (throw
       (ex-info "session.resume returned invalid environment variable grants" {})))
    (reduce
     (fn [projected requested-name]
       (let [grant-key (keyword requested-name)]
         (if-not (contains? grants grant-key)
           projected
           (let [value (get grants grant-key)]
             (when-not (string? value)
               (throw
                (ex-info
                 "session.resume returned a non-string environment variable grant"
                 {:environment-variable requested-name})))
             (assoc projected requested-name value)))))
     {}
     requested-names)))

(defn- foreground-session-id
  []
  (System/getenv "SESSION_ID"))

(defn join-session
  "Join the current foreground session from an extension running as a child process.

   Reads the SESSION_ID environment variable and connects to the parent CLI process
   via stdio. This is intended for extensions spawned by the Copilot CLI.

   Config is the same as resume-session except `:extension-sdk-path` is not accepted.
   `:request-extensions?` and `:extension-info` are accepted.
   `:github-token-provider` is accepted with the same callback contract,
   lifecycle, and transport-security requirements as resume-session.
   `:on-permission-request` is **optional**;
   when omitted, a default handler is used that returns `{:kind :no-result}`, leaving any
   pending permission request unanswered (appropriate for most extensions that use
   `:skip-permission?` on their tools or do not require permission handling).
   The `:disable-resume?` option defaults to true.

   :requested-environment-variables is a join-only vector of non-blank environment
   variable names. An omitted or empty vector sends no request; explicit nil is
   invalid.

   Returns a map with :client and :session keys. When
   :requested-environment-variables is non-empty, the map also includes
   :granted-environment-variables with only the requested names granted by the
   parent CLI. Unlike Node.js, the JVM cannot portably mutate process environment
   variables, so extensions must read approved values from this returned map.
   The caller is responsible for stopping the client when done.

   Throws if SESSION_ID is not set in the environment."
  [config]
  (when-not (s/valid? ::specs/join-session-config config)
    (throw (ex-info (config-validation-message
                     "Invalid join session config"
                     config)
                    {:config (redact-secrets config)
                     :explain (s/explain-data ::specs/join-session-config
                                              (redact-secrets config))})))
  (let [session-id (foreground-session-id)]
    (when-not session-id
      (throw (ex-info (str "join-session is intended for extensions running as child processes "
                           "of the Copilot CLI. SESSION_ID environment variable is not set.")
                      {})))
    (let [c (client {:is-child-process? true})
          merged-config (cond-> config
                          (not (contains? config :on-permission-request))
                          (assoc :on-permission-request default-join-session-permission-handler)
                          (not (contains? config :disable-resume?))
                          (assoc :disable-resume? true))]
      (try
        (let [{:keys [session result]}
              (resume-session-result* c session-id merged-config)
              requested-names (:requested-environment-variables config)]
          (cond-> {:client c :session session}
            (seq requested-names)
            (assoc :granted-environment-variables
                   (project-granted-environment-variables
                    requested-names
                    (:granted-environment-variables result)))))
        (catch Throwable t
          (try (stop! c) (catch Throwable _))
          (throw t))))))

(defn- wire->session-metadata
  "Convert a wire-format session map to the Clojure session-metadata shape."
  [s]
  (let [ctx (:context s)
        cwd (:cwd ctx)
        base (cond-> {:session-id (:session-id s)
                      :start-time (java.time.Instant/parse (:start-time s))
                      :modified-time (java.time.Instant/parse (:modified-time s))
                      :remote? (:is-remote s)}
               (:summary s) (assoc :summary (:summary s)))]
    (cond-> base
      (and ctx (seq cwd))
      (assoc :context (cond-> {:cwd cwd}
                        (:git-root ctx) (assoc :git-root (:git-root ctx))
                        (:repository ctx) (assoc :repository (:repository ctx))
                        (:branch ctx) (assoc :branch (:branch ctx)))))))

(defn list-sessions
  "List all available sessions.
   Returns a vector of session metadata maps.
   Optional filter map narrows results by context fields:
   {:cwd :git-root :repository :branch}"
  ([client]
   (list-sessions client nil))
  ([client filter-opts]
   (ensure-connected! client)
   (let [{:keys [connection-io]} @(:state client)
         wire-filter (when filter-opts
                       (cond-> {}
                         (:cwd filter-opts) (assoc :cwd (:cwd filter-opts))
                         (:git-root filter-opts) (assoc :gitRoot (:git-root filter-opts))
                         (:repository filter-opts) (assoc :repository (:repository filter-opts))
                         (:branch filter-opts) (assoc :branch (:branch filter-opts))))
         result (proto/send-request! connection-io "session.list"
                                     (cond-> {}
                                       wire-filter (assoc :filter wire-filter)))
         sessions (:sessions result)]
     (mapv wire->session-metadata sessions))))

(defn get-session-metadata
  "Gets metadata for a specific session by ID.

   Provides an efficient O(1) lookup of a single session's metadata instead
   of listing all sessions and filtering client-side.

   Returns the session metadata map if found, or nil if not found.

   The returned map has keys:
   - :session-id — the session ID string
   - :start-time — java.time.Instant when the session was created
   - :modified-time — java.time.Instant of last modification
   - :remote? — boolean, true if the session is remote
   - :summary — optional summary string
   - :context — optional map with :cwd and optional :git-root, :repository, :branch"
  [client session-id]
  (ensure-connected! client)
  (let [{:keys [connection-io]} @(:state client)
        result (proto/send-request! connection-io "session.getMetadata" {:session-id session-id})]
    (some-> (:session result) wire->session-metadata)))

(defn delete-session!
  "Permanently deletes a session and all its data from disk, including
   conversation history, planning state, and artifacts. Unlike `disconnect!`,
   which only releases in-memory resources and preserves session data for
   later resumption, this method is irreversible."
  [client session-id]
  (ensure-connected! client)
  (let [{:keys [connection-io]} @(:state client)
        result (proto/send-request! connection-io "session.delete" {:session-id session-id})]
    (when-not (:success result)
      (throw (ex-info (str "Failed to delete session: " (:error result))
                      {:session-id session-id :error (:error result)})))
    (session/teardown-local! client session-id)
    (session/remove-session! client session-id)
    nil))

(defn get-last-session-id
  "Get the ID of the most recently updated session."
  [client]
  (ensure-connected! client)
  (let [{:keys [connection-io]} @(:state client)
        result (proto/send-request! connection-io "session.getLastId" {})]
    (:session-id result)))

(defn get-foreground-session-id
  "Get the foreground session ID (TUI+server mode).
   Returns the session ID or nil if none."
  [client]
  (ensure-connected! client)
  (let [{:keys [connection-io]} @(:state client)
        result (proto/send-request! connection-io "session.getForeground" {})]
    (:session-id result)))

(defn set-foreground-session-id!
  "Set the foreground session (TUI+server mode).
   Requests the TUI to switch to displaying the specified session."
  [client session-id]
  (ensure-connected! client)
  (let [{:keys [connection-io]} @(:state client)
        result (proto/send-request! connection-io "session.setForeground" {:session-id session-id})]
    (when-not (:success result)
      (throw (ex-info (str "Failed to set foreground session: " (:error result))
                      {:session-id session-id :error (:error result)})))
    nil))

;; -----------------------------------------------------------------------------
;; Testing Utilities
;; -----------------------------------------------------------------------------

(defn connect-with-streams!
  "Connect to a server using pre-existing input/output streams.
   For testing purposes only."
  [client in out]
  (let [[old _] (swap-vals! (:state client)
                            (fn [s]
                              (if (#{:connecting :connected} (:status s))
                                s
                                (assoc s :status :connecting))))]
    (when-not (#{:connecting :connected} (:status old))
      (try
        ;; Initialize connection state before connecting
        (swap! (:state client) assoc :connection (proto/initial-connection-state))
        (let [conn (proto/connect in out (:state client))]
          (swap! (:state client) assoc :connection-io conn))
        (verify-protocol-version! client)
        ;; Register sessionFs provider if configured
        (when-let [sf-config (:session-fs client)]
          (let [{:keys [connection-io]} @(:state client)]
            (proto/send-request! connection-io "sessionFs.setProvider"
                                 (cond-> {:initial-cwd (:initial-cwd sf-config)
                                          :session-state-path (:session-state-path sf-config)
                                          :conventions (:conventions sf-config)}
                                   (:capabilities sf-config)
                                   (assoc :capabilities (:capabilities sf-config))))))
        (start-notification-router! client)
        (setup-request-handler! client)
        (swap! (:state client) assoc :status :connected)
        nil
        (catch Exception e
          ;; Reuse the same teardown as a failed start! so a rejected handshake
          ;; leaves no connection, router, or reverse-request executor behind
          ;; and the caller can retry without cleanup of its own. The streams
          ;; belong to the caller, so no process is touched.
          (log-teardown-failures! (release-transport! client {:process :none}))
          (swap! (:state client) assoc :status :error)
          (throw e))))))
