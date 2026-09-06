(ns github.copilot-sdk.specs
  "Clojure specs for Copilot SDK data structures."
  (:require [clojure.core.async.impl.protocols :as async-protocols]
            [clojure.spec.alpha :as s]
            [clojure.set :as set]
            [clojure.string :as str]
            [github.copilot-sdk.util :as util])
  (:import [java.nio.file InvalidPathException Paths]))

;; -----------------------------------------------------------------------------
;; Common specs
;; -----------------------------------------------------------------------------

(defn- closed-keys
  "Returns a spec that validates the keys spec and rejects unknown keys.
   allowed-keys should be the set of allowed keyword names (unqualified)."
  [keys-spec allowed-keys]
  (s/and keys-spec
         (fn [m]
           (let [unknown (set/difference (set (keys m)) allowed-keys)]
             (empty? unknown)))))

(defn unknown-keys
  "Returns the set of unknown keys in a map, given the allowed keys."
  [m allowed-keys]
  (set/difference (set (keys m)) allowed-keys))

(defn- json-number?
  [value]
  (and (number? value)
       (not (ratio? value))
       (cond
         (instance? Double value) (Double/isFinite ^Double value)
         (instance? Float value) (Float/isFinite ^Float value)
         :else true)))

(defn- json-tree-value?
  [root extra-leaf?]
  (loop [pending [root]]
    (if (empty? pending)
      true
      (let [value (peek pending)
            remaining (pop pending)]
        (cond
          (or (nil? value)
              (string? value)
              (json-number? value)
              (boolean? value)
              (extra-leaf? value))
          (recur remaining)

          (map? value)
          (and (every? #(or (keyword? %) (string? %)) (keys value))
               (recur (into remaining (vals value))))

          (coll? value)
          (recur (into remaining value))

          :else
          false)))))

(defn- opaque-json-value?
  [value]
  (json-tree-value? value (constantly false)))

(defn- optional-field?
  [m key pred]
  (or (not (contains? m key))
      (pred (get m key))))

(defn- required-value?
  [m key pred]
  (and (contains? m key)
       (pred (get m key))))

(defn- vector-of?
  [pred value]
  (and (vector? value)
       (every? pred value)))

(defn- json-object-value?
  [value]
  (and (map? value)
       (every? #(or (keyword? %) (string? %)) (keys value))
       (every? opaque-json-value? (vals value))))

(s/def ::non-blank-string (s/and string? (complement clojure.string/blank?)))
;; ::timestamp accepts both ISO 8601 strings (CLI ≥ 1.0.51, upstream PR #1340)
;; and numeric epoch-millis (older CLIs). Used by event timestamps and ping
;; results; the SDK forwards the server value verbatim. Consumers needing a
;; java.time.Instant should branch on the form: parse the string with
;; Instant/parse, or convert the number with Instant/ofEpochMilli.
(s/def ::timestamp (s/or :iso-string string? :epoch-ms nat-int?))
(s/def ::session-id ::non-blank-string)
(s/def ::workspace-path (s/nilable ::non-blank-string))
(s/def ::instant #(instance? java.time.Instant %))

;; -----------------------------------------------------------------------------
;; Client options
;; -----------------------------------------------------------------------------

(s/def ::cli-path ::non-blank-string)
(s/def ::cli-args (s/coll-of string?))
(s/def ::cli-url ::non-blank-string)
(s/def ::cwd ::non-blank-string)
(s/def ::port (s/and int? #(<= 0 % 65535)))
(s/def ::use-stdio? boolean?)
(s/def ::log-level #{:none :error :warning :info :debug :all})
(s/def ::auto-start? boolean?)
(s/def ::auto-restart? boolean?)
(s/def ::notification-queue-size pos-int?)
(s/def ::router-queue-size pos-int?)
(s/def ::request-handler-threads pos-int?)
(s/def ::request-handler-queue-size pos-int?)
(s/def ::tool-timeout-ms pos-int?)
(s/def ::env (s/map-of string? (s/nilable string?)))
;; Authentication options (PR #237)
(s/def ::github-token ::non-blank-string)
(s/def ::use-logged-in-user? boolean?)
;; Child process mode (upstream PR #737)
(s/def ::is-child-process? boolean?)
;; Custom model listing handler (upstream PR #730)
(s/def ::on-list-models fn?)

;; copilotHome — base directory for Copilot data (sets COPILOT_HOME on spawn).
;; Upstream PR #1191 (@github/copilot-sdk).
(s/def ::copilot-home ::non-blank-string)

;; tcpConnectionToken — connection token sent on the `connect` RPC and via the
;; COPILOT_CONNECTION_TOKEN env var. Auto-generated as a UUID when the SDK
;; spawns the CLI in TCP mode and the caller did not provide one.
;; Upstream PR #1176 (@github/copilot-sdk).
(s/def ::tcp-connection-token ::non-blank-string)
(s/def ::session-idle-timeout-seconds (s/and int? #(>= % 0)))

(defn- absolute-path?
  [path]
  (try
    (.isAbsolute (Paths/get ^String path (make-array String 0)))
    (catch InvalidPathException _
      false)))

(s/def ::builtin-plugin-directories
  (s/coll-of (s/and ::non-blank-string absolute-path?) :kind vector?))

;; OpenTelemetry configuration (upstream PR #785)
(s/def ::otlp-endpoint string?)
(s/def ::exporter-type string?)
(s/def ::source-name string?)
(s/def ::capture-content? boolean?)
;; OTLP HTTP protocol for all signals; sets OTEL_EXPORTER_OTLP_PROTOCOL
;; (upstream commit b5ce1c89).
(s/def ::otlp-protocol #{"http/json" "http/protobuf"})

(s/def ::telemetry
  (s/keys :opt-un [::otlp-endpoint ::file-path ::exporter-type ::source-name
                   ::capture-content? ::otlp-protocol]))

;; Trace context provider: 0-arity fn returning {:traceparent ... :tracestate ...}
(s/def ::on-get-trace-context fn?)

;; GitHub telemetry forwarding callback (upstream PR #1835). @experimental /
;; Internal: a 1-arg fn invoked with each forwarded telemetry notification.
(s/def ::on-github-telemetry fn?)

;; Session filesystem provider (upstream PR #917)
(s/def ::initial-cwd ::non-blank-string)
(s/def ::session-state-path ::non-blank-string)
(s/def ::conventions #{"windows" "posix"})

;; Optional sessionFs provider capabilities advertised on sessionFs.setProvider.
;; Upstream PR #1299 (Node.js SDK v1.0.0-beta.4+): clients with sqlite-capable
;; providers set {:sqlite true} to advertise SQL query/exists support.
;; Kept as an open map so future capability flags pass through automatically.
;; We intentionally do NOT register a global ::capabilities spec since
;; ":capabilities" is reused in several unrelated contexts (session capabilities,
;; model capabilities); locally enforced via the predicate below.
(s/def ::session-fs-capabilities
  (s/and map?
         #(or (not (contains? % :sqlite)) (boolean? (:sqlite %)))))

(s/def ::session-fs
  (s/and map?
         #(let [caps (:capabilities %)]
            (or (nil? caps) (s/valid? ::session-fs-capabilities caps)))
         (s/keys :req-un [::initial-cwd ::session-state-path ::conventions])))

;; SessionFs handler — map of keyword→fn for each FS operation.
;; Each fn receives a params map (with :session-id, :path, etc.) and returns a result map (or nil for void ops).
(s/def ::read-file fn?)
(s/def ::write-file fn?)
(s/def ::append-file fn?)
(s/def ::exists fn?)
(s/def ::stat fn?)
(s/def ::mkdir fn?)
(s/def ::readdir fn?)
(s/def ::readdir-with-types fn?)
(s/def ::rm fn?)
(s/def ::rename fn?)

;; SQLite handler keys (upstream PR #1299). Optional — present only when the
;; provider opts in to SQLite support via nested :sqlite {:query :exists}.
(s/def ::sqlite-query fn?)
(s/def ::sqlite-exists fn?)
(s/def ::sqlite-transaction fn?)

(s/def ::session-fs-handler
  (s/keys :req-un [::read-file ::write-file ::append-file ::exists ::stat
                   ::mkdir ::readdir ::readdir-with-types ::rm ::rename]
          :opt-un [::sqlite-query ::sqlite-exists ::sqlite-transaction]))

(defn- fn-accepts-arity?
  [f n]
  (and (fn? f)
       (let [fixed-arity? (boolean
                           (some (fn [^java.lang.reflect.Method method]
                                   (and (= "invoke" (.getName method))
                                        (= n (.getParameterCount method))))
                                 (.getDeclaredMethods (class f))))
             variadic-min-arity (when (instance? clojure.lang.RestFn f)
                                  (.getRequiredArity ^clojure.lang.RestFn f))]
         (or fixed-arity?
             (and (some? variadic-min-arity)
                  (<= variadic-min-arity n))))))

(def ^:private session-fs-provider-arities
  {:read-file 1
   :write-file 3
   :append-file 3
   :exists 1
   :stat 1
   :mkdir 3
   :readdir 1
   :readdir-with-types 1
   :rm 3
   :rename 2})

(defn- session-fs-provider?
  [provider]
  (and (map? provider)
       (every? (fn [[operation arity]]
                 (fn-accepts-arity? (get provider operation) arity))
               session-fs-provider-arities)
       ;; Optional :sqlite sub-provider — transaction support is optional.
       (let [sql (:sqlite provider)]
         (or (nil? sql)
             (and (map? sql)
                  (fn-accepts-arity? (:query sql) 3)
                  (fn-accepts-arity? (:exists sql) 0)
                  (or (nil? (:transaction sql))
                      (fn-accepts-arity? (:transaction sql) 1)))))))

;; SQLite query type passed to the sqlite provider's :query function.
;; Wire form is the literal string; we expose idiomatic keywords to handlers.
(s/def ::sqlite-query-type #{:exec :query :run})
(s/def ::sqlite-transaction-error-class
  #{:busy-or-locked :post-commit-ambiguous :fatal})

;; Shape returned by an adapted sqlite-query handler (and the wire shape after
;; clj->wire conversion). Mirrors generated SessionFsSqliteQueryResult.
(s/def ::sqlite-rows (s/coll-of map?))
(s/def ::sqlite-columns (s/coll-of string?))
(s/def ::rows-affected (s/and integer? #(<= 0 %)))
(s/def ::last-insert-rowid (s/or :number number? :string string?))

;; Provider-style session filesystem implementation. Same operation keys as
;; ::session-fs-handler, but functions take direct positional arguments and
;; throw on errors instead of returning RPC-shaped success/error maps.
;; May optionally provide a :sqlite sub-provider for SQLite support.
(s/def ::session-fs-provider
  (s/and ::session-fs-handler session-fs-provider?))

;; Factory fn: (session) → session-fs-handler
(s/def ::create-session-fs-handler fn?)

;; Client mode (upstream PR #1428). `:copilot-cli` (the default) preserves
;; the historical behavior; `:empty` enables multitenancy hardening:
;; tenant-scoped storage is required, the system keychain is disabled, and
;; a set of safe session defaults is spread under the caller's config.
;;
;; Note: the unqualified key in client-options is `:mode`, but the existing
;; ::mode spec at line ~899 is already taken by message-options for
;; #{:enqueue :immediate}. We use a uniquely-named ::client-mode spec and
;; validate the unqualified `:mode` key via a predicate in ::client-options
;; (mirrors the ::remote-session-mode pattern further down).
(s/def ::client-mode #{:empty :copilot-cli})

(s/def ::application-name string?)
(s/def ::application-version string?)
(s/def ::integration-name string?)
(s/def ::integration-version string?)

(def ^:private client-info-keys
  #{:application-name :application-version
    :integration-name :integration-version})

(s/def ::client-info
  (closed-keys
   (s/keys :opt-un [::application-name ::application-version
                    ::integration-name ::integration-version])
   client-info-keys))

(def client-options-keys
  #{:cli-path :cli-args :cli-url :cwd :port
    :use-stdio? :log-level :auto-start? :auto-restart?
    :notification-queue-size :router-queue-size
    :request-handler-threads :request-handler-queue-size
    :tool-timeout-ms :env :github-token :use-logged-in-user?
    :is-child-process? :on-list-models :telemetry :on-get-trace-context
    :on-github-telemetry
    :session-fs :copilot-home :tcp-connection-token :remote?
    :session-idle-timeout-seconds :builtin-plugin-directories
    :client-info :mode})

(s/def ::client-options
  (s/and
   (closed-keys
    (s/keys :opt-un [::cli-path ::cli-args ::cli-url ::cwd ::port
                     ::use-stdio? ::log-level ::auto-start? ::auto-restart?
                     ::notification-queue-size ::router-queue-size
                     ::request-handler-threads ::request-handler-queue-size
                     ::tool-timeout-ms ::env ::github-token ::use-logged-in-user?
                     ::is-child-process? ::on-list-models ::telemetry ::on-get-trace-context
                     ::on-github-telemetry
                     ::session-fs ::copilot-home ::tcp-connection-token ::remote?
                     ::session-idle-timeout-seconds ::builtin-plugin-directories
                     ::client-info])
    client-options-keys)
   (fn [m]
     (or (not (contains? m :mode))
         (s/valid? ::client-mode (:mode m))))))

;; -----------------------------------------------------------------------------
;; GitHub telemetry forwarding (upstream PR #1835) — @experimental / Internal
;; -----------------------------------------------------------------------------
;; Idiom-shaped specs for the forwarded telemetry notification. All maps are
;; open (`s/keys`) so upstream additions pass through without a spec change
;; (Key Principle #6). The `:properties`, `:metrics`, and `:features` sub-maps
;; are OPAQUE source-defined data: their keys are preserved verbatim by the
;; protocol escape hatch, so they are typed as plain maps rather than enumerated.

(s/def ::restricted boolean?)
(s/def ::properties map?)
(s/def ::metrics map?)
(s/def ::features map?)

;; Descriptive standalone spec for the nested `:client` map (open). Not wired
;; into `::github-telemetry-event`'s `:opt-un`: the unqualified key would be
;; `:client`, which already names the client-record spec in this namespace.
;; The event map is open, so `:client` passes through unvalidated.
(s/def ::github-telemetry-client-info map?)

(s/def ::github-telemetry-event
  ;; Scalar keys (`:kind`, `:created-at`, `:model-call-id`, `:session-id`,
  ;; `:copilot-tracking-id`, `:exp-assignment-context`) are kebab-cased from the
  ;; wire snake_case. `:client`, when present, is an open client-info map
  ;; (see ::github-telemetry-client-info). `:properties`/`:metrics`/`:features`
  ;; are opaque verbatim passthrough.
  (s/keys :opt-un [::properties ::metrics ::features]))

(s/def ::event ::github-telemetry-event)

(s/def ::github-telemetry-notification
  ;; `:event`, when present, holds a ::github-telemetry-event map.
  (s/keys :opt-un [::session-id ::restricted ::event]))

;; -----------------------------------------------------------------------------
;; Tool definitions
;; -----------------------------------------------------------------------------

(s/def ::tool-name ::non-blank-string)
(s/def ::tool-description (s/nilable string?))
(s/def ::json-schema map?)
(s/def ::tool-parameters (s/nilable ::json-schema))
(s/def ::tool-handler fn?)
(s/def ::overrides-built-in-tool boolean?)
(s/def ::skip-permission? boolean?)
(s/def ::is-terminal? boolean?)
(s/def ::metadata map?)
;; Upstream PR #1632: controls whether a tool may be deferred (loaded lazily via
;; tool search) rather than always pre-loaded. The idiom uses keywords; the
;; value is sent on the wire as the corresponding string ("auto" | "never").
(s/def ::defer #{:auto :never})

(s/def ::tool
  ;; Upstream PR #1308: handler is now optional. Tools without a handler are
  ;; declaration-only — they're surfaced as `external_tool.requested` events
  ;; and the consumer resolves them via `handle-pending-tool-call!`.
  (s/keys :req-un [::tool-name]
          :opt-un [::tool-handler ::tool-description ::tool-parameters
                   ::overrides-built-in-tool ::skip-permission? ::defer
                   ::metadata ::is-terminal?]))

(s/def ::tools (s/coll-of ::tool))

;; -----------------------------------------------------------------------------
;; System message configuration
;; -----------------------------------------------------------------------------

(s/def ::system-message-mode #{:append :replace :customize})
(s/def ::system-message-content string?)

;; System message sections for customize mode (upstream PR #816)
;; Upstream PR #1377 renamed `SystemPromptSection` to `SystemMessageSection`
;; and added the `runtime_instructions` section. The Clojure SDK keeps the
;; historical `system-prompt-sections` var as the canonical name and exposes
;; `system-message-sections` as an alias to match the upstream naming.
(def system-prompt-sections
  "Known system message section identifiers for the customize mode.
   Each section corresponds to a distinct part of the system message.
   Upstream alias: `SystemMessageSection`."
  {:preamble             {:description "Agent identity preamble and mode statement"}
   :identity             {:description "Section group covering the identity preamble and its sibling sub-sections (tone, tool efficiency, etc.)"}
   :tone                 {:description "Response style, conciseness rules, output formatting preferences"}
   :tool-efficiency      {:description "Tool usage patterns, parallel calling, batching guidelines"}
   :environment-context  {:description "CWD, OS, git root, directory listing, available tools"}
   :code-change-rules    {:description "Coding rules, linting/testing, ecosystem tools, style"}
   :guidelines           {:description "Tips, behavioral best practices, behavioral guidelines"}
   :safety               {:description "Environment limitations, prohibited actions, security policies"}
   :tool-instructions    {:description "Per-tool usage instructions"}
   :custom-instructions  {:description "Repository and organization custom instructions"}
   :runtime-instructions {:description "Runtime-provided context and instructions (e.g. system notifications, memories, workspace context, mode-specific instructions, content-exclusion policy)"}
   :last-instructions    {:description "End-of-prompt instructions: parallel tool calling, persistence, task completion"}})

(def system-message-sections
  "Alias for [[system-prompt-sections]] matching the upstream
   `SYSTEM_MESSAGE_SECTIONS` name (upstream PR #1377)."
  system-prompt-sections)

(s/def ::system-prompt-section (set (keys system-prompt-sections)))
(s/def ::system-message-section ::system-prompt-section)

;; Section override: action can be a keyword for static overrides, or a fn for transforms.
;; `:preserve` (upstream PR #1713) is a no-op marker that opts an individually-addressable
;; section out of a group-level `:remove` (e.g. keep `:tone` when removing the `:identity`
;; group). It carries no content.
(s/def ::section-action
  (s/or :static #{:replace :remove :append :prepend :preserve}
        :transform fn?))

(s/def ::section-override
  (s/and map?
         #(contains? % :action)
         #(s/valid? ::section-action (:action %))
         ;; If :content is present, it must be a string
         #(if-let [c (:content %)] (string? c) true)
         ;; For static content-bearing actions, :content is required
         #(if (and (keyword? (:action %))
                   (#{:replace :append :prepend} (:action %)))
            (string? (:content %))
            true)
         ;; The no-op markers carry no payload — reject stray :content so a
         ;; caller mistake fails fast instead of being silently dropped.
         #(if (#{:remove :preserve} (:action %))
            (not (contains? % :content))
            true)))

;; Customize config: mode :customize with optional sections map and content
;; Sections map allows any keyword key — unknown sections gracefully fall back
;; to appending content to additional instructions (upstream behavior).
(s/def ::sections (s/map-of keyword? ::section-override))

;; System message: supports :append (default), :replace, and :customize modes
(s/def ::system-message
  (s/and map?
         #(if-let [m (:mode %)] (#{:append :replace :customize} m) true)
         #(if-let [c (:content %)] (string? c) true)
         ;; :replace mode requires :content
         #(if (= :replace (:mode %)) (string? (:content %)) true)
         #(if (= :customize (:mode %))
            (if-let [s (:sections %)]
              (s/valid? ::sections s)
              true)
            true)))

;; -----------------------------------------------------------------------------
;; MCP Server configuration
;; -----------------------------------------------------------------------------

(s/def ::mcp-server-type #{:local :stdio :http :sse})
(s/def ::mcp-tools (s/or :list (s/coll-of string?) :all #{"*"}))
(s/def ::mcp-timeout pos-int?)
(s/def ::mcp-command ::non-blank-string)
(s/def ::mcp-args (s/coll-of string?))
(s/def ::mcp-url ::non-blank-string)
(s/def ::mcp-headers (s/map-of string? string?))
;; Experimental CLI-only escape hatch. The runtime schema accepts deferTools,
;; but the official Node SDK's public MCPServerConfig does not expose it.
(s/def ::mcp-defer-tools #{:auto :never})

(s/def ::mcp-local-server
  (s/keys :req-un [::mcp-command ::mcp-tools]
          :opt-un [::mcp-args ::mcp-server-type ::mcp-timeout ::env ::cwd ::mcp-defer-tools]))

(s/def ::mcp-remote-server
  (s/keys :req-un [::mcp-server-type ::mcp-url ::mcp-tools]
          :opt-un [::mcp-timeout ::mcp-headers ::mcp-defer-tools]))

;; New canonical names (upstream PR #1051)
(s/def ::mcp-stdio-server ::mcp-local-server)
(s/def ::mcp-http-server ::mcp-remote-server)

(s/def ::mcp-server (s/or :local ::mcp-local-server :remote ::mcp-remote-server))
(s/def ::mcp-servers (s/map-of #(or (keyword? %) (string? %)) ::mcp-server))

;; -----------------------------------------------------------------------------
;; Custom agent configuration
;; -----------------------------------------------------------------------------

(s/def ::reasoning-effort #{"low" "medium" "high" "xhigh" "max"})
(s/def ::agent-name ::non-blank-string)
(s/def ::agent-display-name string?)
(s/def ::agent-description string?)
(s/def ::agent-tools (s/nilable (s/coll-of string?)))
(s/def ::agent-prompt ::non-blank-string)
(s/def ::agent-infer? boolean?)
(s/def ::agent-skills (s/coll-of string?))
;; Model identifier for the agent (e.g. "claude-haiku-4.5"). When set, the
;; runtime will attempt to use this model for the agent, falling back to the
;; parent session model if unavailable. Upstream PR #1309.
(s/def ::agent-model ::non-blank-string)
;; Per-agent override of the model's reasoning effort. The nested API key keeps
;; the established agent prefix; the wire serializer emits `reasoningEffort`.
(s/def ::agent-reasoning-effort ::reasoning-effort)

(s/def ::custom-agent
  (s/keys :req-un [::agent-name ::agent-prompt]
          :opt-un [::agent-display-name ::agent-description ::agent-tools
                   ::mcp-servers ::agent-infer? ::agent-skills ::agent-model
                   ::agent-reasoning-effort]))

(s/def ::custom-agents (s/coll-of ::custom-agent))

;; Default agent configuration (upstream PR #1098). This controls tool
;; visibility for the built-in/default agent independently of custom agents.
(s/def ::default-agent
  (s/keys :opt-un [::excluded-tools]))

;; Agent selection (upstream PR #722)
(s/def ::agent ::non-blank-string)

;; -----------------------------------------------------------------------------
;; Provider configuration (BYOK)
;; -----------------------------------------------------------------------------

(s/def ::provider-type #{:openai :azure :anthropic})
(s/def ::wire-api #{:completions :responses})
(s/def ::base-url ::non-blank-string)
(s/def ::api-key string?)
(s/def ::bearer-token string?)
(s/def ::azure-api-version string?)

;; Provider :transport — selects the request transport for OpenAI-compatible
;; providers running `:wire-api :responses` (upstream PR #1711). Defaults to
;; `:http` runtime-side when omitted; `:websockets` opts into the WebSocket
;; Responses transport. Upstream attaches `transport` to the singular
;; `ProviderConfig` only — the multi-provider `NamedProviderConfig` has no
;; transport field — so this applies to ::provider, not ::named-provider.
(s/def ::transport #{:http :websockets})

;; On-demand bearer-token callback (upstream PR #1748, @experimental). A 1-arg
;; function receiving an idiomatic `ProviderTokenArgs` map
;; `{:provider-name <s> :session-id <s>}`
;; and returning the raw token string (a channel yielding the string is also
;; accepted). Applies to both ::provider and ::named-provider. The fn is never
;; serialized — provider->wire strips it and emits `hasBearerTokenProvider: true`.
(s/def ::bearer-token-provider fn?)

(s/def ::azure-options
  (s/keys :opt-un [::azure-api-version]))

;; Provider :headers — extra HTTP headers forwarded with each model request
;; (upstream PR #1094, available since CLI 1.0.32). Map of header name → value.
(s/def ::headers (s/map-of string? string?))

;; Provider model override (upstream PR #966).
;; Wire model name sent to the provider API for inference; falls back to
;; :model-id, then SessionConfig :model. Note: ::max-input-tokens and
;; ::max-output-tokens are reused here for the new ProviderConfig override
;; fields. ::model-id is *not* reused: the model-info response spec at line
;; ~1237 below registers ::model-id as `(s/nilable string?)` because the
;; current-model getter can return nil. Provider overrides, in contrast,
;; correspond to upstream's `modelId?: string` (optional but never null), so
;; we validate `:model-id` explicitly via an extra predicate below rather
;; than letting the nilable leaf weaken the provider config.
(s/def ::wire-model ::non-blank-string)

(s/def ::provider
  (s/and
   (s/keys :req-un [::base-url]
           :opt-un [::provider-type ::wire-api ::api-key ::bearer-token ::azure-options
                    ::headers ::transport ::bearer-token-provider
                    ;; ProviderConfig overrides (upstream PR #966).
                    ;; ::model-id ↦ wire `modelId` (well-known model name for
                    ;; agent config + token-limit lookup; default wire model
                    ;; when ::wire-model is unset).
                    ;; ::wire-model ↦ wire `wireModel` (provider-API model name).
                    ;; ::max-input-tokens  ↦ wire `maxPromptTokens` (compaction
                    ;; trigger; SDK↔wire key disagreement is handled by
                    ;; provider->wire in client.clj).
                    ;; ::max-output-tokens ↦ wire `maxOutputTokens`.
                    ::model-id ::wire-model
                    ::max-input-tokens ::max-output-tokens])
   ;; Provider override `:model-id` must be a non-blank string when present
   ;; (upstream `modelId?: string`, never null). The shared ::model-id leaf
   ;; is `(s/nilable string?)` for the model-info getter, so we re-validate
   ;; here to avoid sending `modelId: null` on the wire.
   #(or (not (contains? % :model-id))
        (s/valid? ::non-blank-string (:model-id %)))
   ;; Token-limit overrides must be strictly positive when present (upstream
   ;; semantics: a `≤ 0` budget would either disable the feature or trigger
   ;; immediate compaction/truncation, both nonsensical).
   #(or (not (contains? % :max-input-tokens))
        (pos-int? (:max-input-tokens %)))
   #(or (not (contains? % :max-output-tokens))
        (pos-int? (:max-output-tokens %)))))

;; -----------------------------------------------------------------------------
;; Multi-provider BYOK registry (upstream PR #1718, @experimental)
;; -----------------------------------------------------------------------------
;; The singular ::provider above configures one OpenAI-compatible endpoint.
;; The registry lets a session declare several named providers (::providers)
;; plus a model catalog (::models) that references them by name. A model is
;; addressed as "<provider-name>/<id>"; provider names therefore must not
;; contain "/". Upstream's `NamedProviderConfig` has no `transport` and no
;; inline model-override fields (those live on ::provider-model instead).

;; A named provider entry. Like ::provider but `:name` is required (the
;; registry key, no "/") and there are no transport / model-override fields.
(s/def ::named-provider
  (s/and
   (s/keys :req-un [::name ::base-url]
           :opt-un [::provider-type ::wire-api ::api-key ::bearer-token
                    ::azure-options ::headers ::bearer-token-provider])
   #(s/valid? ::non-blank-string (:name %))
   #(not (str/includes? (:name %) "/"))
   ;; `s/keys` is open, so reject the singular-provider-only transport /
   ;; model-override keys explicitly — otherwise they would pass validation and
   ;; silently forward on the wire, contradicting the NamedProviderConfig
   ;; contract (those fields live on ::provider / ::provider-model).
   #(empty? (select-keys % [:transport :model-id :wire-model
                            :max-input-tokens :max-output-tokens]))))

;; ModelCapabilitiesOverride. The canonical idiom mirrors the upstream
;; `supports` / `limits` branches with kebab-case leaves; the serializer keeps
;; the runtime's mixed camelCase/snake_case wire spelling. The published
;; :model-supports / :model-limits shape remains an accepted input alias.
(def ^:private model-capability-top-level-keys
  #{:supports :limits :model-supports :model-limits})
(def ^:private model-capability-support-keys
  #{:vision :reasoning-effort :adaptive-thinking})
(def ^:private legacy-model-capability-support-keys
  #{:supports-vision :supports-reasoning-effort})
(def ^:private model-capability-limit-keys
  #{:max-prompt-tokens :max-output-tokens :max-context-window-tokens :vision})
(def ^:private legacy-model-capability-limit-keys
  #{:max-prompt-tokens :max-context-window-tokens :vision-capabilities})
(def ^:private model-capability-vision-keys
  #{:supported-media-types :max-prompt-images :max-prompt-image-size})

(defn- valid-model-capability-supports?
  [supports legacy?]
  (let [allowed (if legacy?
                  legacy-model-capability-support-keys
                  model-capability-support-keys)
        vision-key (if legacy? :supports-vision :vision)
        reasoning-key (if legacy? :supports-reasoning-effort :reasoning-effort)]
    (and (map? supports)
         (set/subset? (set (keys supports)) allowed)
         (or (not (contains? supports vision-key))
             (boolean? (get supports vision-key)))
         (or (not (contains? supports reasoning-key))
             (boolean? (get supports reasoning-key)))
         (or legacy?
             (not (contains? supports :adaptive-thinking))
             (#{:unsupported :optional :required} (:adaptive-thinking supports))))))

(defn- valid-model-capability-vision?
  [vision]
  (and (map? vision)
       (set/subset? (set (keys vision)) model-capability-vision-keys)
       (or (not (contains? vision :supported-media-types))
           (and (sequential? (:supported-media-types vision))
                (every? string? (:supported-media-types vision))))
       (or (not (contains? vision :max-prompt-images))
           (int? (:max-prompt-images vision)))
       (or (not (contains? vision :max-prompt-image-size))
           (int? (:max-prompt-image-size vision)))))

(defn- valid-model-capability-limits?
  [limits legacy?]
  (let [allowed (if legacy?
                  legacy-model-capability-limit-keys
                  model-capability-limit-keys)
        vision-key (if legacy? :vision-capabilities :vision)
        numeric-keys (if legacy?
                       [:max-prompt-tokens :max-context-window-tokens]
                       [:max-prompt-tokens :max-output-tokens :max-context-window-tokens])]
    (and (map? limits)
         (set/subset? (set (keys limits)) allowed)
         (every? #(or (not (contains? limits %))
                      (int? (get limits %)))
                 numeric-keys)
         (or (not (contains? limits vision-key))
             (valid-model-capability-vision? (get limits vision-key))))))

(s/def ::model-capabilities
  (s/and
   map?
   #(set/subset? (set (keys %)) model-capability-top-level-keys)
   #(not (and (contains? % :supports) (contains? % :model-supports)))
   #(not (and (contains? % :limits) (contains? % :model-limits)))
   #(or (not (contains? % :supports))
        (valid-model-capability-supports? (:supports %) false))
   #(or (not (contains? % :model-supports))
        (valid-model-capability-supports? (:model-supports %) true))
   #(or (not (contains? % :limits))
        (valid-model-capability-limits? (:limits %) false))
   #(or (not (contains? % :model-limits))
        (valid-model-capability-limits? (:model-limits %) true))))

;; Provider-model capability maps historically accepted exact string-keyed wire
;; data. Keep that escape hatch while also accepting the canonical idiom above.
(s/def ::capabilities
  (s/or :wire (s/map-of string? any?)
        :idiom ::model-capabilities))

;; A model in the registry catalog. `:id` is the provider-local model id and
;; `:provider` is a non-blank string referencing a ::named-provider `:name`.
;; `:provider` is enforced via predicate rather than `:req-un [::provider]`
;; because `::provider` is the singular-provider *map* spec, not a string.
(s/def ::provider-model
  (s/and
   (s/keys :req-un [::id]
           :opt-un [::name ::model-id ::wire-model ::capabilities
                    ::max-input-tokens ::max-context-window-tokens
                    ::max-output-tokens])
   #(s/valid? ::non-blank-string (:id %))
   #(s/valid? ::non-blank-string (:provider %))
   #(or (not (contains? % :model-id))
        (s/valid? ::non-blank-string (:model-id %)))
   #(or (not (contains? % :max-input-tokens))
        (pos-int? (:max-input-tokens %)))
   #(or (not (contains? % :max-context-window-tokens))
        (pos-int? (:max-context-window-tokens %)))
   #(or (not (contains? % :max-output-tokens))
        (pos-int? (:max-output-tokens %)))))

(s/def ::providers (s/coll-of ::named-provider))
(s/def ::models (s/coll-of ::provider-model))

;; expAssignments (upstream PR #2033, @internal) uses the PascalCase JSON shape
;; returned by ExP. String keys bypass kebab->camel conversion and are forwarded
;; verbatim to the runtime.
(s/def ::exp-flag-value
  (s/or :string string?
        :number number?
        :boolean boolean?
        :nil nil?))
(s/def ::exp-parameters (s/map-of string? ::exp-flag-value))

(def ^:private exp-config-entry-keys #{"Id" "Parameters"})

(s/def ::exp-config-entry
  (s/and map?
         #(= exp-config-entry-keys (set (keys %)))
         #(string? (get % "Id"))
         #(s/valid? ::exp-parameters (get % "Parameters"))))

(def ^:private exp-assignment-required-keys
  #{"Features" "Flights" "Configs" "AssignmentContext"})
(def ^:private exp-assignment-keys
  (into exp-assignment-required-keys
        ["ParameterGroups" "FlightingVersion" "ImpressionId"]))

(s/def ::exp-assignments
  (s/and map?
         #(set/subset? exp-assignment-required-keys (set (keys %)))
         #(set/subset? (set (keys %)) exp-assignment-keys)
         #(s/valid? (s/coll-of string?) (get % "Features"))
         #(s/valid? (s/map-of string? string?) (get % "Flights"))
         #(s/valid? (s/coll-of ::exp-config-entry) (get % "Configs"))
         #(string? (get % "AssignmentContext"))
         #(or (not (contains? % "FlightingVersion"))
              (number? (get % "FlightingVersion")))
         #(or (not (contains? % "ImpressionId"))
              (string? (get % "ImpressionId")))))

;; -----------------------------------------------------------------------------
;; Session configuration
;; -----------------------------------------------------------------------------

(s/def ::model ::non-blank-string)
(s/def ::available-tools (s/coll-of string?))
(s/def ::excluded-tools (s/coll-of string?))
(s/def ::streaming? boolean?)
(s/def ::on-permission-request fn?)
;; MCP OAuth lifecycle (upstream PR #1669). The :on-mcp-auth-request handler is
;; a fn receiving an McpAuthRequest (the `mcp.oauth_required` event data,
;; kebab-cased: {:request-id :server-name :server-url :reason ...}) and a
;; context map {:session-id ...}. It returns an McpAuthResult — either
;;   {:kind :token :access-token "..." :token-type "Bearer"? :expires-in 3600?}
;; or {:kind :cancelled}. Returning nil or throwing also cancels the request.
(s/def ::on-mcp-auth-request fn?)
(s/def ::config-dir ::non-blank-string)
;; Upstream PR #1482 (post-v1.0.0-beta.4): `configDir` was renamed to
;; `configDirectory` in the official TypeScript SDK API. The wire stays
;; `configDir`; only the idiom-layer key changed. `:config-directory` is the
;; new spelling; `:config-dir` remains accepted as a deprecated alias.
(s/def ::config-directory ::non-blank-string)
(s/def ::skill-directories (s/coll-of ::non-blank-string))
;; instructionDirectories — additional directories to search for custom
;; instruction files. Upstream PR #1190 (@github/copilot-sdk).
(s/def ::instruction-directories (s/coll-of ::non-blank-string))
;; pluginDirectories — extra directories to load plugins from, loaded even
;; when `enableConfigDiscovery` is false (upstream PR #1482).
(s/def ::plugin-directories (s/coll-of ::non-blank-string))
(s/def ::disabled-skills (s/coll-of ::non-blank-string))
(s/def ::enabled boolean?)
(s/def ::defer-threshold integer?)
(s/def ::tool-search
  (closed-keys
   (s/keys :opt-un [::enabled ::defer-threshold])
   #{:enabled :defer-threshold}))
(s/def ::max-size-bytes pos-int?)
(s/def ::output-dir ::non-blank-string)
;; Upstream PR #1482: `outputDir` renamed to `outputDirectory` in the official
;; TS SDK. Wire stays `outputDir`; `:output-directory` is the new idiom key
;; and `:output-dir` remains an accepted deprecated alias.
(s/def ::output-directory ::non-blank-string)
(s/def ::large-output
  (s/keys :opt-un [::enabled ::max-size-bytes ::output-dir ::output-directory]))

;; Working directory
(s/def ::working-directory ::non-blank-string)

;; Infinite sessions configuration
(s/def ::background-compaction-threshold (s/and number? #(<= 0.0 % 1.0)))
(s/def ::buffer-exhaustion-threshold (s/and number? #(<= 0.0 % 1.0)))
(s/def ::infinite-sessions
  (s/keys :opt-un [::enabled ::background-compaction-threshold ::buffer-exhaustion-threshold]))

;; Memory configuration (upstream PR #1617). Lets the agent persist and recall
;; information across turns. `:enabled` is required by MemoryConfiguration.
(s/def ::memory
  (s/keys :req-un [::enabled]))

;; Hooks and user input handlers (PR #269)
(s/def ::on-user-input-request fn?)
(s/def ::on-pre-tool-use fn?)
(s/def ::on-pre-mcp-tool-call fn?)
(s/def ::on-post-tool-use fn?)
(s/def ::on-post-tool-use-failure fn?)
(s/def ::on-user-prompt-submitted fn?)
(s/def ::on-user-prompt-transformed fn?)
(s/def ::on-session-start fn?)
(s/def ::on-session-end fn?)
(s/def ::on-error-occurred fn?)
(s/def ::on-agent-stop fn?)
(s/def ::hooks
  (s/keys :opt-un [::on-pre-tool-use ::on-pre-mcp-tool-call ::on-post-tool-use
                   ::on-post-tool-use-failure
                   ::on-user-prompt-submitted ::on-user-prompt-transformed
                   ::on-session-start ::on-session-end
                   ::on-error-occurred ::on-agent-stop]))

;; Disable resume flag
(s/def ::disable-resume? boolean?)
(s/def ::continue-pending-work? boolean?)

;; Event handler — 1-arity fn receiving event map. Uses fn? for consistency
;; with ::on-permission-request and ::on-user-input-request specs.
(s/def ::on-event fn?)

;; Command definitions — slash commands registered with a session
(s/def ::command-name ::non-blank-string)
(s/def ::command-handler fn?)
(s/def ::command-definition
  (s/and (s/keys :req-un [::name ::command-handler]
                 :opt-un [::description])
         #(not (str/blank? (:name %)))))
(s/def ::commands (s/coll-of ::command-definition))

;; Session capabilities — reported by the CLI host
(s/def ::elicitation boolean?)
(s/def ::ui (s/keys :opt-un [::elicitation]))
(s/def ::session-capabilities (s/keys :opt-un [::ui]))

;; Elicitation types — action values are strings on the wire
(s/def ::elicitation-action #{"accept" "decline" "cancel"})
(s/def ::elicitation-field-value (s/or :string string? :number number? :boolean boolean?
                                       :strings (s/coll-of string?)))
(s/def ::elicitation-content (s/map-of keyword? ::elicitation-field-value))
(s/def ::action ::elicitation-action)
(s/def ::content string?)
(s/def ::elicitation-result
  (s/and (s/keys :req-un [::action])
         #(or (not (contains? % :content))
              (s/valid? ::elicitation-content (:content %)))))
(s/def ::requested-schema map?)
(s/def ::message ::non-blank-string)
(s/def ::elicitation-params
  (s/keys :req-un [::message ::requested-schema]))

;; Elicitation context — passed to :on-elicitation-request handler (upstream PR #960).
;; Single-arg pattern: context includes session-id alongside request fields.
;; Note: :mode here is "form"/"url" (different from message-options ::mode which is :enqueue/:immediate)
(s/def ::elicitation-source string?)
(s/def ::url string?)
(s/def ::elicitation-context
  (s/and (s/keys :req-un [::session-id ::message]
                 :opt-un [::requested-schema ::elicitation-source ::url])
         #(if-let [m (:mode %)]
            (contains? #{"form" "url"} m)
            true)))

(s/def ::on-elicitation-request fn?)

;; Input options for the input! convenience method
(s/def ::title string?)
(s/def ::min-length nat-int?)
(s/def ::max-length nat-int?)
(s/def ::format #{"email" "uri" "date" "date-time"})
(s/def ::default string?)
(s/def ::input-options
  (s/keys :opt-un [::title ::description ::min-length ::max-length ::format ::default]))

(s/def ::client-name ::non-blank-string)

;; enableConfigDiscovery: auto-discover MCP configs, skills, instruction files (upstream PR #1044)
(s/def ::enable-config-discovery boolean?)

;; enableMcpApps: experimental SEP-1865 host opt-in
;; (https://github.com/github/copilot-sdk/pull/1335).
;; Only true is emitted on create/resume as requestMcpApps; false is omitted.
(s/def ::enable-mcp-apps boolean?)

;; includeSubAgentStreamingEvents: forward streaming events from sub-agents to the parent
;; session's event stream (upstream PR #1108). Defaults to true on the wire.
(s/def ::include-sub-agent-streaming-events? boolean?)

;; enableSessionTelemetry: enable/disable internal session telemetry (upstream PR #1224).
;; When omitted (the default) or true, telemetry is enabled for GitHub-authenticated
;; sessions. When false, internal session telemetry is disabled. With a custom
;; ::provider (BYOK), session telemetry is always disabled regardless of this setting.
;; Independent of the OpenTelemetry config in ::telemetry.
(s/def ::enable-session-telemetry? boolean?)

;; Exit Plan Mode handler (upstream PR #1228). Server may ask the client to
;; approve leaving plan mode. Idiom: request map has :summary, optional
;; :plan-content, :actions (vec string), :recommended-action. Result map has
;; :approved? (required boolean), optional :selected-action and :feedback.
(s/def ::plan-content string?)
(s/def ::actions (s/coll-of string? :kind vector?))
(s/def ::recommended-action string?)
(s/def ::exit-plan-mode-request
  (s/keys :req-un [::summary ::actions ::recommended-action]
          :opt-un [::plan-content ::model]))
(s/def ::approved? boolean?)
(s/def ::selected-action string?)
(s/def ::feedback string?)
(s/def ::exit-plan-mode-result
  (s/keys :req-un [::approved?]
          :opt-un [::selected-action ::feedback]))
(s/def ::on-exit-plan-mode fn?)

;; Auto Mode Switch handler (upstream PR #1228). Server may ask the client to
;; switch the agent to auto mode after a rate-limit event. Idiom: request map
;; has optional :error-code and :retry-after-seconds; response is a keyword
;; :yes | :yes-always | :no (or the matching wire string).
(s/def ::error-code string?)
(s/def ::retry-after-seconds (s/and number? #(>= % 0)))
(s/def ::auto-mode-switch-request
  (s/keys :opt-un [::error-code ::retry-after-seconds]))
(s/def ::auto-mode-switch-response
  (s/or :keyword #{:yes :yes-always :no}
        :string  #{"yes" "yes_always" "no"}))
(s/def ::on-auto-mode-switch fn?)

;; modelCapabilities override for session config / setModel (upstream PR #1029).
;; DeepPartial<ModelCapabilities> — same shape as ::model-capabilities since all fields are already optional.

;; -----------------------------------------------------------------------------
;; Round 6 (post-v1.0.0-beta.4) SessionConfigBase additions
;; -----------------------------------------------------------------------------

;; mcpOAuthTokenStorage (PR #1326): controls where MCP OAuth tokens are stored
;; on disk vs in process memory. Idiom uses keywords; wire emits the original
;; hyphenated string (NOT camel-cased — csk would wrongly produce "inMemory").
;; embeddingCacheStorage (PR #1474) shares the same enum.
(s/def ::storage-mode #{:persistent :in-memory})
(s/def ::mcp-oauth-token-storage ::storage-mode)
(s/def ::embedding-cache-storage ::storage-mode)

;; Multitenancy hardening flags (upstream PR #1474). All optional, plain
;; booleans/strings. Application-mode behavior is driven by Client Mode
;; (upstream PR #1428).
(s/def ::skip-embedding-retrieval boolean?)
(s/def ::organization-custom-instructions string?)
(s/def ::enable-on-demand-instruction-discovery boolean?)
(s/def ::enable-file-hooks boolean?)
(s/def ::enable-file-change-tracking? boolean?)
(s/def ::enable-host-git-operations boolean?)
(s/def ::enable-session-store boolean?)
(s/def ::enable-skills boolean?)

;; Client-mode session option flags (upstream PR #1428). Sent via
;; session.options.update after session.create / session.resume. In empty
;; mode these get safe defaults applied beneath the caller's config; in
;; CLI mode they are only emitted when explicitly set.
(s/def ::skip-custom-instructions boolean?)
(s/def ::custom-agents-local-only boolean?)
(s/def ::coauthor-enabled boolean?)
(s/def ::manage-schedule-enabled boolean?)
(s/def ::included-builtin-skills
  (s/coll-of string?
             :kind #(and (coll? %) (not (map? %)))))

;; Reasoning summary mode (upstream PR #813 - pre-existing parity gap).
;; Wire enum: "none" | "concise" | "detailed". Mirrors upstream's ReasoningSummary type.
(s/def ::reasoning-summary #{"none" "concise" "detailed"})

;; Explicit context tier for the selected model (upstream — pre-existing parity gap).
;; Idiom uses keywords; wire emits the underscored enum: "default" | "long_context".
;; The official SDK distinguishes a value from omission and does not expose null.
(s/def ::context-tier #{:default :long-context})

;; CapiSessionOptions (upstream PR #1711). Per-session transport choices for the
;; built-in Copilot API provider. `:enable-web-socket-responses` controls whether
;; the WebSocket transport is used for the CAPI Responses API (default true on the
;; runtime side); set false to force the HTTP Responses transport. Sent verbatim
;; under the `capi` wire key.
(s/def ::enable-web-socket-responses boolean?)
(s/def ::auto-tier #{:efficiency :balance :intelligence})
(s/def ::capi (s/keys :opt-un [::auto-tier ::enable-web-socket-responses]))

;; Selects the model-facing shape of the built-in ask_user tool.
(s/def ::ask-user-variant #{:legacy :elicitation})

;; An explicit empty feature flag map is distinct from omission.
(s/def ::feature-flags (s/map-of string? boolean?))

;; Session-scoped GitHub credential callback (upstream PR #2412).
(s/def ::github-token-provider fn?)
(s/def ::github-token-acquire-reason
  #{:initial :refresh})
(s/def ::github-token-provider-args
  (s/and map?
         #(every? #{:host :session-id :reason} (keys %))
         #(contains? % :host)
         #(contains? % :reason)
         #(s/valid? ::non-blank-string (:host %))
         #(optional-field? % :session-id
                           (partial s/valid? ::non-blank-string))
         #(s/valid? ::github-token-acquire-reason (:reason %))))

(defn- github-token-provider-field-value?
  [value]
  (json-tree-value? value keyword?))

(defn- github-token-provider-result-map?
  [result]
  (map? result))

(defn- github-token-provider-result-kind?
  [result]
  (and (map? result)
       (contains? #{:token :cancelled} (:kind result))))

(defn- github-token-provider-result-keys?
  [result]
  (and (map? result)
       (every? #(or (keyword? %) (string? %)) (keys result))))

(defn- wire-json-member-name
  [key]
  (let [wire-key (first (keys (util/clj->wire {key nil})))]
    (if (keyword? wire-key)
      (name wire-key)
      wire-key)))

(defn- github-token-provider-result-collision-free?
  [value]
  (cond
    (map? value)
    (and
     (every? #(or (keyword? %) (string? %)) (keys value))
     (let [member-names (map wire-json-member-name (keys value))]
       (= (count member-names) (count (set member-names))))
     (every? github-token-provider-result-collision-free? (vals value)))

    (coll? value)
    (every? github-token-provider-result-collision-free? value)

    :else
    true))

(defn- github-token-provider-result-field-values?
  [result]
  (and (map? result)
       (every? github-token-provider-field-value?
               (vals (dissoc result :kind)))))

(defn- github-token-provider-result-access-token?
  [result]
  (and (map? result)
       (or (not= :token (:kind result))
           (s/valid? ::non-blank-string (:access-token result)))))

(defn- github-token-provider-result-expires-in-integer?
  [result]
  (and (map? result)
       (or (not= :token (:kind result))
           (integer? (:expires-in result)))))

(defn- github-token-provider-result-expires-in-minimum?
  [result]
  (and (map? result)
       (or (not= :token (:kind result))
           (let [expires-in (:expires-in result)]
             (and (integer? expires-in)
                  (> expires-in 3600))))))

(defn- github-token-provider-result-token-type?
  [result]
  (and
   (map? result)
   (or
    (not= :token (:kind result))
    (let [token-type-entry
          (some (fn [[key value]]
                  (when (and (or (keyword? key) (string? key))
                             (= "tokenType" (wire-json-member-name key)))
                    [key value]))
                result)]
      (or (nil? token-type-entry)
          (s/valid? ::non-blank-string (second token-type-entry)))))))

(def ^:private github-token-provider-result-constraints
  [[:result-must-be-map
    github-token-provider-result-map?]
   [:kind-must-be-token-or-cancelled
    github-token-provider-result-kind?]
   [:keys-must-be-keywords-or-strings
    github-token-provider-result-keys?]
   [:keys-must-not-collide-after-wire-conversion
    github-token-provider-result-collision-free?]
   [:fields-must-be-json-values
    github-token-provider-result-field-values?]
   [:access-token-must-be-non-blank-string
    github-token-provider-result-access-token?]
   [:expires-in-must-be-integer
    github-token-provider-result-expires-in-integer?]
   [:expires-in-must-exceed-3600
    github-token-provider-result-expires-in-minimum?]
   [:token-type-must-be-non-blank-string
    github-token-provider-result-token-type?]])

(defn ^:no-doc github-token-provider-result-constraint
  "Return the first violated provider-result constraint, or nil when valid.
   Both result variants are open to additional upstream-compatible fields."
  [result]
  (some (fn [[constraint valid?]]
          (when-not (valid? result)
            constraint))
        github-token-provider-result-constraints))

(s/def ::github-token-provider-result
  (s/and github-token-provider-result-map?
         github-token-provider-result-kind?
         github-token-provider-result-keys?
         github-token-provider-result-collision-free?
         github-token-provider-result-field-values?
         github-token-provider-result-access-token?
         github-token-provider-result-expires-in-integer?
         github-token-provider-result-expires-in-minimum?
         github-token-provider-result-token-type?))

;; Session options (upstream PR #1865) — shared by create + resume/join.
;; excludedBuiltinAgents: names of built-in agents to hide from the session.
(s/def ::excluded-builtin-agents (s/coll-of string?))
;; enableCitations (@experimental): enable native model citations.
(s/def ::enable-citations boolean?)
;; sessionLimits (@experimental): per-session accounting limits. maxAiCredits
;; carries exclusiveMinimum 0 on the wire, enforced here as a positive number.
(s/def ::max-ai-credits (s/and number? pos?))
(s/def ::session-limits (s/keys :opt-un [::max-ai-credits]))

;; enableManagedSettings (upstream PR #1925) — opt-in that makes the runtime
;; self-fetch enterprise managed settings (bypass-permissions policy) at session
;; bootstrap using the session's github token. Forwarded on create + resume/join.
(s/def ::enable-managed-settings? boolean?)

;; v1.0.9 session configuration additions.
(s/def ::enable-experimental-mode? boolean?)
(s/def ::additional-directories (s/coll-of ::non-blank-string :kind vector?))
(s/def ::disabled-mcp-servers (s/coll-of ::non-blank-string :kind vector?))

;; Stable extension-host session fields. `::extension-info` is already the
;; richer session.extensions_loaded event item, so config validation uses the
;; distinct `::extension-identity` spec through `valid-extension-identity?`.
(s/def ::request-extensions? boolean?)
(s/def ::extension-sdk-path string?)
(s/def ::extension-identity
  (closed-keys
   (s/keys :req-un [::source ::name])
   #{:source :name}))

(defn- valid-extension-identity?
  [config]
  (or (not (contains? config :extension-info))
      (s/valid? ::extension-identity (:extension-info config))))

(defn- closed-session-config
  [keys-spec allowed-keys]
  (s/and (closed-keys keys-spec allowed-keys)
         valid-extension-identity?
         (complement util/github-token-auth-conflict?)))

(s/def ::enable-all-tools? boolean?)
(s/def ::additional-toolsets (s/coll-of ::non-blank-string :kind vector?))
(s/def ::additional-tools (s/coll-of ::non-blank-string :kind vector?))
(s/def ::enable-insiders-mode? boolean?)
(s/def ::disable-form-deferral? boolean?)
(def ^:private github-mcp-tool-config-keys
  #{:enable-all-tools? :additional-toolsets :additional-tools
    :enable-insiders-mode? :disable-form-deferral?})
(s/def ::github-mcp-tool-config
  (closed-keys
   (s/keys :opt-un [::enable-all-tools? ::additional-toolsets ::additional-tools
                    ::enable-insiders-mode? ::disable-form-deferral?])
   github-mcp-tool-config-keys))

(s/def ::disable-bypass-permissions-mode
  (s/or :wire string?
        :idiom (s/and keyword? #(nil? (namespace %)))))
(s/def ::deny (s/coll-of ::non-blank-string :kind vector?))
(s/def ::ask (s/coll-of ::non-blank-string :kind vector?))
(s/def ::allow (s/coll-of ::non-blank-string :kind vector?))
(def ^:private managed-settings-permissions-keys
  #{:disable-bypass-permissions-mode :deny :ask :allow})
(s/def ::managed-settings-permissions
  (closed-keys
   (s/keys :opt-un [::disable-bypass-permissions-mode ::deny ::ask ::allow])
   managed-settings-permissions-keys))
(s/def ::permissions ::managed-settings-permissions)
(s/def ::managed-settings
  (closed-keys
   (s/keys :opt-un [::permissions])
   #{:permissions}))

(s/def ::factories (s/coll-of any? :kind vector?))

(s/def ::max-concurrent-subagents pos-int?)
(s/def ::max-total-subagents pos-int?)
(s/def ::timeout-seconds (s/and number? pos? #(<= % 2147483.647)))
(s/def ::factory-limits
  (closed-keys
   (s/keys :opt-un [::max-concurrent-subagents ::max-total-subagents
                    ::max-ai-credits ::timeout-seconds])
   #{:max-concurrent-subagents :max-total-subagents
     :max-ai-credits :timeout-seconds}))
(s/def ::detail string?)
(s/def ::factory-phase
  (closed-keys
   (s/keys :req-un [::title] :opt-un [::detail])
   #{:title :detail}))
(s/def ::phases (s/coll-of ::factory-phase :kind vector?))
(s/def ::limits ::factory-limits)
(s/def ::factory-meta
  (closed-keys
   (s/keys :req-un [::name ::description ::phases]
           :opt-un [::limits])
   #{:name :description :phases :limits}))
(s/def ::factory-run-status
  #{:pending :running :completed :halted :cancelled :error})
(s/def ::factory-resume-error-code
  #{:not-found :non-resumable :already-active
    :reapproval-declined :no-approval-provider})

;; canvasProvider (upstream PR #1847) — stable identity for a host/SDK connection
;; that supplies built-in canvases, so canvases declared on a control connection
;; survive stdio reconnect and CLI restart instead of being re-keyed per
;; connection. `:id` is opaque and used verbatim as the canvas extension id;
;; `:name` is an optional display name. Forwarded on create + resume/join. Reuses
;; the generic ::id/::name specs, mirroring ::provider's nested-map convention.
(s/def ::canvas-provider (s/keys :req-un [::id] :opt-un [::name]))

(def session-config-keys
  #{:session-id :client-name :model :tools :commands :system-message
    :available-tools :excluded-tools :tool-search :provider
    :on-permission-request :streaming? :mcp-servers
    :on-mcp-auth-request
    :custom-agents :default-agent
    ;; Directory rename (PR #1482): :config-directory is the new spelling;
    ;; :config-dir is accepted as a deprecated alias.
    :config-dir :config-directory
    :skill-directories
    :instruction-directories
    :plugin-directories
    :disabled-skills :large-output :infinite-sessions
    :memory
    :reasoning-effort :reasoning-summary :context-tier
    :on-user-input-request :on-elicitation-request :hooks
    :on-exit-plan-mode :on-auto-mode-switch
    :working-directory :agent :on-event :create-session-fs-handler
    :enable-config-discovery :enable-mcp-apps :model-capabilities
    :github-token :github-token-provider :ask-user-variant
    :enable-session-telemetry?
    :remote-session
    :cloud
    :mcp-oauth-token-storage
    :embedding-cache-storage
    :skip-embedding-retrieval
    :organization-custom-instructions
    :enable-on-demand-instruction-discovery
    :enable-file-hooks
    :enable-file-change-tracking?
    :enable-host-git-operations
    :enable-session-store
    :enable-skills
    :skip-custom-instructions
    :custom-agents-local-only
    :coauthor-enabled
    :manage-schedule-enabled
    :included-builtin-skills
    :capi
    :excluded-builtin-agents :enable-citations :session-limits
    :providers :models :exp-assignments :feature-flags
    :include-sub-agent-streaming-events?
    :enable-managed-settings? :managed-settings
    :enable-experimental-mode? :additional-directories :disabled-mcp-servers
    :github-mcp-tool-config
    :request-extensions? :extension-sdk-path :extension-info
    :canvas-provider})

(s/def ::session-config
  (closed-session-config
   ;; Upstream PR #1308: :on-permission-request is now optional. When omitted,
   ;; permission requests are surfaced as events and left pending for the
   ;; consumer to resolve via `handle-pending-permission-request!`.
   (s/keys :opt-un [::on-permission-request
                    ::on-mcp-auth-request
                    ::session-id ::client-name ::model ::tools ::commands ::system-message
                    ::available-tools ::excluded-tools ::tool-search ::provider
                    ::streaming? ::mcp-servers
                    ::custom-agents ::default-agent
                    ::config-dir ::config-directory
                    ::skill-directories
                    ::instruction-directories
                    ::plugin-directories
                    ::disabled-skills ::large-output ::infinite-sessions
                    ::memory
                    ::reasoning-effort ::reasoning-summary ::context-tier
                    ::on-user-input-request ::on-elicitation-request ::hooks
                    ::on-exit-plan-mode ::on-auto-mode-switch
                    ::working-directory ::agent ::on-event ::create-session-fs-handler
                    ::enable-config-discovery ::enable-mcp-apps ::model-capabilities
                    ::github-token ::github-token-provider ::ask-user-variant
                    ::enable-session-telemetry?
                    ::remote-session
                    ::cloud
                    ::mcp-oauth-token-storage
                    ::embedding-cache-storage
                    ::skip-embedding-retrieval
                    ::organization-custom-instructions
                    ::enable-on-demand-instruction-discovery
                    ::enable-file-hooks
                    ::enable-file-change-tracking?
                    ::enable-host-git-operations
                    ::enable-session-store
                    ::enable-skills
                    ::skip-custom-instructions
                    ::custom-agents-local-only
                    ::coauthor-enabled
                    ::manage-schedule-enabled
                    ::included-builtin-skills
                    ::capi
                    ::excluded-builtin-agents ::enable-citations ::session-limits
                    ::providers ::models ::exp-assignments ::feature-flags
                    ::include-sub-agent-streaming-events?
                    ::enable-managed-settings? ::managed-settings
                    ::enable-experimental-mode? ::additional-directories ::disabled-mcp-servers
                    ::github-mcp-tool-config
                    ::request-extensions? ::extension-sdk-path
                    ::canvas-provider])
   session-config-keys))

(def ^:private resume-session-config-keys
  #{:client-name :model :tools :commands :system-message :available-tools :excluded-tools :tool-search
    :provider :streaming? :on-permission-request
    :on-mcp-auth-request
    :mcp-servers :custom-agents :default-agent
    :config-dir :config-directory
    :skill-directories
    :instruction-directories
    :plugin-directories
    :disabled-skills :large-output :infinite-sessions
    :memory
    :reasoning-effort :reasoning-summary :context-tier
    :on-user-input-request :on-elicitation-request :hooks :working-directory :disable-resume? :agent :on-event
    :on-exit-plan-mode :on-auto-mode-switch
    :continue-pending-work?
    :create-session-fs-handler :enable-config-discovery :enable-mcp-apps :model-capabilities
    :github-token :github-token-provider :ask-user-variant
    :enable-session-telemetry?
    :remote-session
    :mcp-oauth-token-storage
    :embedding-cache-storage
    :skip-embedding-retrieval
    :organization-custom-instructions
    :enable-on-demand-instruction-discovery
    :enable-file-hooks
    :enable-file-change-tracking?
    :enable-host-git-operations
    :enable-session-store
    :enable-skills
    :skip-custom-instructions
    :custom-agents-local-only
    :coauthor-enabled
    :manage-schedule-enabled
    :included-builtin-skills
    :capi
    :excluded-builtin-agents :enable-citations :session-limits
    :providers :models :exp-assignments :feature-flags
    :include-sub-agent-streaming-events?
    ;; Upstream PR #1604: resume/join may seed the open-canvases snapshot.
    :open-canvases
    :enable-managed-settings? :managed-settings
    :enable-experimental-mode? :additional-directories :disabled-mcp-servers
    :github-mcp-tool-config
    :request-extensions? :extension-sdk-path :extension-info
    :canvas-provider})

(s/def ::resume-session-config
  (closed-session-config
   ;; Upstream PR #1308: :on-permission-request is now optional.
   (s/keys :opt-un [::on-permission-request
                    ::on-mcp-auth-request
                    ::client-name ::model ::tools ::commands ::system-message ::available-tools ::excluded-tools
                    ::tool-search
                    ::provider ::streaming?
                    ::mcp-servers ::custom-agents ::default-agent
                    ::config-dir ::config-directory
                    ::skill-directories
                    ::instruction-directories
                    ::plugin-directories
                    ::disabled-skills ::large-output ::infinite-sessions
                    ::memory
                    ::reasoning-effort ::reasoning-summary ::context-tier
                    ::on-user-input-request ::on-elicitation-request ::hooks ::working-directory ::disable-resume? ::agent
                    ::on-exit-plan-mode ::on-auto-mode-switch
                    ::on-event ::create-session-fs-handler
                    ::enable-config-discovery ::enable-mcp-apps ::model-capabilities
                    ::github-token ::github-token-provider ::ask-user-variant
                    ::continue-pending-work?
                    ::enable-session-telemetry?
                    ::remote-session
                    ::mcp-oauth-token-storage
                    ::embedding-cache-storage
                    ::skip-embedding-retrieval
                    ::organization-custom-instructions
                    ::enable-on-demand-instruction-discovery
                    ::enable-file-hooks
                    ::enable-file-change-tracking?
                    ::enable-host-git-operations
                    ::enable-session-store
                    ::enable-skills
                    ::skip-custom-instructions
                    ::custom-agents-local-only
                    ::coauthor-enabled
                    ::manage-schedule-enabled
                    ::included-builtin-skills
                    ::capi
                    ::excluded-builtin-agents ::enable-citations ::session-limits
                    ::providers ::models ::exp-assignments ::feature-flags
                    ::include-sub-agent-streaming-events?
                    ::open-canvases
                    ::enable-managed-settings? ::managed-settings
                    ::enable-experimental-mode? ::additional-directories ::disabled-mcp-servers
                    ::github-mcp-tool-config
                    ::request-extensions? ::extension-sdk-path
                    ::canvas-provider])
   resume-session-config-keys))

;; join-session config extends resume with extension-authored Agent Factories
;; and the extension-only environment grant request.
;; When omitted, join-session defaults to a handler that returns {:kind :no-result}.
(s/def ::requested-environment-variables
  (s/coll-of ::non-blank-string :kind vector?))
(def ^:private join-session-config-keys
  (-> resume-session-config-keys
      (disj :extension-sdk-path)
      (conj :factories :requested-environment-variables)))
(s/def ::join-session-config
  (closed-session-config
   (s/keys :opt-un [::on-permission-request
                    ::on-mcp-auth-request
                    ::client-name ::model ::tools ::commands ::system-message ::available-tools ::excluded-tools
                    ::tool-search
                    ::provider ::streaming?
                    ::mcp-servers ::custom-agents ::default-agent
                    ::config-dir ::config-directory
                    ::skill-directories
                    ::instruction-directories
                    ::plugin-directories
                    ::disabled-skills ::large-output ::infinite-sessions
                    ::memory
                    ::reasoning-effort ::reasoning-summary ::context-tier
                    ::on-user-input-request ::on-elicitation-request ::hooks ::working-directory ::disable-resume? ::agent
                    ::on-exit-plan-mode ::on-auto-mode-switch
                    ::on-event ::create-session-fs-handler
                    ::enable-config-discovery ::enable-mcp-apps ::model-capabilities
                    ::github-token ::github-token-provider ::ask-user-variant
                    ::continue-pending-work?
                    ::enable-session-telemetry?
                    ::remote-session
                    ::mcp-oauth-token-storage
                    ::embedding-cache-storage
                    ::skip-embedding-retrieval
                    ::organization-custom-instructions
                    ::enable-on-demand-instruction-discovery
                    ::enable-file-hooks
                    ::enable-file-change-tracking?
                    ::enable-host-git-operations
                    ::enable-session-store
                    ::enable-skills
                    ::skip-custom-instructions
                    ::custom-agents-local-only
                    ::coauthor-enabled
                    ::manage-schedule-enabled
                    ::included-builtin-skills
                    ::capi
                    ::excluded-builtin-agents ::enable-citations ::session-limits
                    ::providers ::models ::exp-assignments ::feature-flags
                    ::include-sub-agent-streaming-events?
                    ::open-canvases
                    ::enable-managed-settings? ::managed-settings
                    ::enable-experimental-mode? ::additional-directories ::disabled-mcp-servers
                    ::github-mcp-tool-config
                    ::request-extensions?
                    ::requested-environment-variables
                    ::canvas-provider
                    ::factories])
   join-session-config-keys))

;; -----------------------------------------------------------------------------
;; Message options
;; -----------------------------------------------------------------------------

(s/def ::prompt ::non-blank-string)
(s/def ::attachment-type #{:file :directory :selection :github-reference})
(s/def ::type ::attachment-type)
(s/def ::path ::non-blank-string)
(s/def ::file-path ::non-blank-string)
(s/def ::display-name string?)

;; Selection range (line/character positions)
(s/def ::line nat-int?)
(s/def ::character nat-int?)
(s/def ::position (s/keys :req-un [::line ::character]))
(s/def ::start ::position)
(s/def ::end ::position)
(s/def ::selection-range (s/keys :req-un [::start ::end]))
(s/def ::text string?)

;; Line range for file/directory attachments (simple start/end line numbers)
(s/def ::line-range (s/and map?
                           #(contains? % :start)
                           #(contains? % :end)
                           #(nat-int? (:start %))
                           #(nat-int? (:end %))))

;; File/directory attachment
(s/def ::file-or-directory-attachment
  (s/and (s/keys :req-un [::type ::path]
                 :opt-un [::display-name ::line-range])
         #(#{:file :directory} (:type %))))

;; Selection attachment
(s/def ::selection-attachment
  (s/and (s/keys :req-un [::type ::file-path ::display-name]
                 :opt-un [::selection-range ::text])
         #(= :selection (:type %))))

;; GitHub reference attachment (issue, PR, or discussion)
;; Note: ::state is already defined as (instance? Atom) for the client record,
;; so we cannot use s/keys here — manual predicates validate the :state field instead.
(s/def ::number nat-int?)
(s/def ::reference-type #{"issue" "pr" "discussion"})
(s/def ::url string?)
(s/def ::attachment-state string?)
(s/def ::github-reference-attachment
  (s/and map?
         #(= :github-reference (:type %))
         #(nat-int? (:number %))
         #(string? (:title %))
         #(contains? #{"issue" "pr" "discussion"} (:reference-type %))
         #(string? (:state %))
         #(string? (:url %))))

;; Blob attachment (base64-encoded inline data, received in user.message events)
;; Note: blob uses manual predicates for :data/:mime-type to avoid conflicting with
;; ::data used as event data (map?) in ::session-event.
(s/def ::mime-type string?)
(s/def ::blob-attachment
  (s/and map?
         #(= :blob (:type %))
         #(string? (:data %))
         #(string? (:mime-type %))
         #(or (nil? (:display-name %)) (string? (:display-name %)))))

(s/def ::attachment
  (s/or :file-or-directory ::file-or-directory-attachment
        :selection ::selection-attachment
        :github-reference ::github-reference-attachment
        :blob ::blob-attachment))

;; Inbound attachment (identical to ::attachment, kept as a semantic alias
;; for event data contexts where attachments are received rather than sent)
(s/def ::inbound-attachment
  (s/or :file-or-directory ::file-or-directory-attachment
        :selection ::selection-attachment
        :github-reference ::github-reference-attachment
        :blob ::blob-attachment))

(s/def ::attachments (s/coll-of ::attachment))
(s/def ::inbound-attachments (s/coll-of ::inbound-attachment))
(s/def ::mode #{:enqueue :immediate})

;; Per-request HTTP headers forwarded to the model provider (upstream PR #1094).
;; Map of header name → value, merged with any provider-level headers.
(s/def ::request-headers (s/map-of string? string?))

;; Caller-side agent mode for ::send-options (upstream PR #1438,
;; post-v1.0.0-beta.4). Wire enum: "interactive" | "plan" | "autopilot" |
;; "shell". The SDK API accepts the keyword and session/send! coerces it
;; to the wire string via (name kw). Defaults to the session's current
;; mode when omitted.
;;
;; Note: this is the CALLER-side spec only. Inbound user.message events
;; echo agentMode as the wire string post wire->clj; that's validated by
;; the generated wire spec, not by curated ::user.message-data here.
(s/def ::agent-mode #{:interactive :plan :autopilot :shell})

;; Display-only prompt shown in the timeline instead of the model :prompt
;; (upstream PR #1470, post-v1.0.0-beta.4).
(s/def ::display-prompt string?)

(s/def ::send-options
  (s/keys :req-un [::prompt]
          :opt-un [::attachments ::mode ::timeout-ms ::request-headers
                   ::agent-mode ::display-prompt]))

;; :timeout-ms as used in option maps for send-async / <send! /
;; send-async-with-id / send-and-wait! allows nil to "disable" the timeout per
;; the docstrings. send-and-wait!'s positional 3-arity argument shares the same
;; nilable contract: a nil deadline waits indefinitely rather than calling
;; (async/timeout nil).
(s/def ::timeout-ms (s/nilable pos-int?))

;; -----------------------------------------------------------------------------
;; Connection state
;; -----------------------------------------------------------------------------

(s/def ::connection-state #{:disconnected :connecting :connected :error})

;; -----------------------------------------------------------------------------
;; Session metadata
;; -----------------------------------------------------------------------------

(s/def ::start-time ::instant)
(s/def ::modified-time ::instant)
(s/def ::summary string?)
(s/def ::remote? boolean?)

;; Session context (cwd, git info from session creation)
(s/def ::git-root ::non-blank-string)
(s/def ::repository ::non-blank-string)
(s/def ::branch ::non-blank-string)

(s/def ::session-context
  (s/keys :req-un [::cwd]
          :opt-un [::git-root ::repository ::branch]))

;; Session list filter
(s/def ::session-list-filter
  (s/keys :opt-un [::cwd ::git-root ::repository ::branch]))

(s/def ::context (s/nilable ::session-context))

(s/def ::session-metadata
  (s/keys :req-un [::session-id ::start-time ::modified-time ::remote?]
          :opt-un [::summary ::context]))

;; -----------------------------------------------------------------------------
;; Session lifecycle events (client-level)
;; -----------------------------------------------------------------------------

(s/def ::lifecycle-event-type
  #{:session.created :session.deleted :session.updated
    :session.foreground :session.background})

(s/def ::lifecycle-event
  (s/keys :req-un [::lifecycle-event-type ::session-id]
          :opt-un [::metadata]))

(s/def ::lifecycle-handler fn?)

;; -----------------------------------------------------------------------------
;; Session Events (from generated schema)
;; -----------------------------------------------------------------------------

(s/def ::event-id ::non-blank-string)
(s/def ::event-timestamp ::timestamp)
(s/def ::parent-id (s/nilable ::non-blank-string))
(s/def ::ephemeral? boolean?)
;; agent-id: identifies which (sub-)agent emitted an event. Present on most events
;; once sub-agent streaming is enabled (upstream PR #1108).
(s/def ::agent-id ::non-blank-string)

;; canOfferSessionApproval: writeFile permission requests carry this hint indicating
;; whether the CLI can present a "trust this session" option (upstream CLI 1.0.28).
;; Note: the spec key name omits the `?` suffix because camel-snake-kebab (used
;; by util/wire->clj) converts `canOfferSessionApproval` to
;; `:can-offer-session-approval` (no `?`), and `:opt-un` matches on the exact
;; unqualified keyword — a `?`-suffixed spec would silently never fire.
(s/def ::can-offer-session-approval boolean?)

;; reasoningTokens: per-message / per-session tokens used for reasoning content
;; (upstream CLI 1.0.32). Reported on assistant.usage and session.usage_info events.
(s/def ::reasoning-tokens nat-int?)
(s/def ::reasoning-id ::non-blank-string)
(s/def ::encrypted-content string?)
(s/def ::output-tokens nat-int?)
(s/def ::phase string?)
(s/def ::reasoning-opaque string?)
(s/def ::reasoning-text string?)
(s/def ::request-id ::non-blank-string)

;; Session log specs (upstream PR #737)
(s/def ::level #{"info" "warning" "error"})
(s/def ::log-options (s/keys :opt-un [::level ::ephemeral?]))

(s/def ::base-event
  (s/keys :req-un [::event-id ::event-timestamp ::parent-id]
          :opt-un [::ephemeral? ::agent-id]))

;; Event type enum (namespaced under :copilot/)
(s/def ::event-type
  #{:copilot/session.start :copilot/session.resume :copilot/session.error :copilot/session.idle
    :copilot/session.info :copilot/session.model_change :copilot/session.handoff
    :copilot/session.truncation :copilot/session.snapshot_rewind :copilot/session.usage_info
    :copilot/session.compaction_start :copilot/session.compaction_complete
    :copilot/session.shutdown :copilot/session.task_complete :copilot/session.context_cleared
    :copilot/session.title_changed :copilot/session.warning :copilot/session.context_changed
    :copilot/session.mode_changed :copilot/session.plan_changed :copilot/session.todos_changed
    :copilot/session.workspace_file_changed
    :copilot/user.message :copilot/pending_messages.modified
    :copilot/assistant.turn_start :copilot/assistant.intent :copilot/assistant.reasoning
    :copilot/assistant.reasoning_delta :copilot/assistant.message :copilot/assistant.message_delta
    :copilot/assistant.message_start
    :copilot/assistant.streaming_delta :copilot/assistant.turn_end :copilot/assistant.usage
    :copilot/abort
    :copilot/tool.user_requested :copilot/tool.execution_start :copilot/tool.execution_partial_result
    :copilot/tool.execution_progress :copilot/tool.execution_complete
    :copilot/subagent.started :copilot/subagent.completed :copilot/subagent.failed :copilot/subagent.selected
    :copilot/subagent.deselected
    :copilot/skill.invoked
    :copilot/hook.start :copilot/hook.end
    :copilot/system.message
    :copilot/system.notification
    ;; Interaction broadcast events (permission, user input, elicitation, tool flows)
    :copilot/permission.requested :copilot/permission.completed
    :copilot/user_input.requested :copilot/user_input.completed
    :copilot/elicitation.requested :copilot/elicitation.completed
    :copilot/external_tool.requested :copilot/external_tool.completed
    ;; MCP OAuth events
    :copilot/mcp.oauth_required :copilot/mcp.oauth_completed
    ;; Command events
    :copilot/command.queued :copilot/command.execute :copilot/command.completed
    :copilot/commands.changed
    ;; Plan mode events
    :copilot/exit_plan_mode.requested :copilot/exit_plan_mode.completed
    ;; Auto-mode switch events (upstream PR #1228)
    :copilot/auto_mode_switch.requested :copilot/auto_mode_switch.completed
    ;; Session status events
    :copilot/session.tools_updated :copilot/session.background_tasks_changed
    :copilot/session.skills_loaded :copilot/session.mcp_servers_loaded
    :copilot/session.mcp_server_status_changed
    :copilot/session.mcp_server_removed
    :copilot/session.mcp_server_needs_reconnect
    :copilot/session.extensions_loaded
    :copilot/session.custom_agents_updated
    ;; Custom notification (upstream PR #1292, CLI 1.0.48)
    :copilot/session.custom_notification
    ;; Sampling events (upstream PR #908)
    :copilot/sampling.requested :copilot/sampling.completed
    ;; Session remote steerable (upstream PR #908)
    :copilot/session.remote_steerable_changed
    ;; Capabilities changed (upstream PR #908)
    :copilot/capabilities.changed
    ;; Schedule events (upstream schema 1.0.42)
    :copilot/session.schedule_created :copilot/session.schedule_cancelled
    ;; MCP Apps tool-call complete (upstream schema 1.0.52-4, SEP-1865)
    :copilot/mcp_app.tool_call_complete
    ;; Round 6 (upstream schema 1.0.56-1): autopilot lifecycle, permissions
    ;; mode toggles, and an ephemeral hook-progress event.
    :copilot/session.autopilot_objective_changed
    :copilot/session.permissions_changed
    :copilot/hook.progress
    ;; Remaining schema events (pinned schema 1.0.57): model-call telemetry,
    ;; streaming message start, extension attachment pushes, and canvas events
    ;; (delivered even though the canvas authoring API is out of scope for 1.0.0).
    :copilot/model.call_failure
    :copilot/session.extensions.attachments_pushed
    :copilot/session.canvas.opened
    :copilot/session.canvas.closed
    :copilot/session.canvas.registry_changed
    ;; v1.0.7-preview.2 sync (introduced upstream schema 1.0.70): streaming tool-call
    ;; input delta, MCP list-changed notifications, and experimental auto-mode model
    ;; resolution. (This is the ::event-type idiom enum, a field of ::session-event;
    ;; keep it in step with the curated `github.copilot-sdk/event-types` set.)
    :copilot/assistant.tool_call_delta
    :copilot/mcp.tools.list_changed
    :copilot/mcp.resources.list_changed
    :copilot/mcp.prompts.list_changed
    :copilot/session.auto_mode_resolved
    ;; Post-v1.0.7 schema sync (pinned schema 1.0.73). assistant.turn_retry and
    ;; model.call_start remain generated-only because upstream marks them internal.
    :copilot/assistant.server_tool_progress
    :copilot/session.managed_settings_enforced
    :copilot/session.managed_settings_resolved
    :copilot/tool_search.activated
    ;; v1.0.9 + post-v1.0.9 sync (pinned schema 1.0.79-6).
    :copilot/factory.run_updated
    ;; Stable 2980c78 sync (pinned schema 1.0.83-1). Stable HydraFusion events
    ;; remain internal; routing and phase events remain experimental.
    :copilot/session.mode_notice_delivered
    :copilot/model.call_finished
    :copilot/subagent.configured})

;; Session events
(s/def ::already-in-use? boolean?)
(s/def ::remote-steerable? boolean?)
(s/def ::host-type string?)
(s/def ::head-commit string?)
(s/def ::base-commit string?)

(s/def ::detached-from-spawning-parent-session-id string?)
;; upstream schema 1.0.83-1: `autoTier` echoes the auto-routing preference
;; active at session start/resume. The coercion layer converts the wire enum
;; string to the same idiomatic keyword domain used by ::capi options.
(s/def ::session.start-data
  ;; Note: ::version is intentionally omitted from this hand-written spec.
  ;; The upstream schema types it as `number` while the global `::version`
  ;; spec (used by ::model-info) is `string?`. The generated wire spec
  ;; (github.copilot-sdk.generated.event-specs/session.start-data) is the
  ;; canonical contract for this field.
  (s/keys :req-un [::session-id]
          :opt-un [::producer ::copilot-version ::start-time ::selected-model
                   ::reasoning-effort ::already-in-use? ::remote-steerable? ::host-type ::head-commit ::base-commit
                   ::detached-from-spawning-parent-session-id ::auto-tier]))

(s/def ::event-count nat-int?)
(s/def ::events-file-size-bytes nat-int?)
(s/def ::session.resume-data
  (s/keys :req-un [::event-count]
          :opt-un [::selected-model ::reasoning-effort ::already-in-use? ::remote-steerable?
                   ::host-type ::head-commit ::base-commit ::events-file-size-bytes
                   ::auto-tier]))

(s/def ::status-code integer?)
(s/def ::provider-call-id string?)
(s/def ::error-type string?)
(s/def ::stack string?)
(s/def ::remediation-action
  #{"sign_in"
    "switch_account"
    "show_account"
    "review_sandbox_policy"
    "allow_sandbox_outbound"})
(s/def ::remediation ::remediation-action)
;; Upstream schema 1.0.83-1: `parentToolCallId` links a nested tool-call chain
;; (assistant messages, tool execution, hooks) back to the invoking tool call.
;; It is a plain wire string round-tripped verbatim by wire->clj.
(s/def ::parent-tool-call-id string?)

(s/def ::session.error-data
  (s/keys :req-un [::error-type ::message]
          :opt-un [::stack ::status-code ::provider-call-id ::url
                   ::remediation]))

(def ^:no-doc autopilot-session-mode "autopilot")
(def ^:no-doc session-modes #{"interactive" autopilot-session-mode "plan"})

(s/def ::session-idle-mode session-modes)
(s/def ::session.idle-data
  (s/and
   map?
   #(or (not (contains? % :aborted)) (boolean? (:aborted %)))
   #(or (not (contains? % :mode))
        (s/valid? ::session-idle-mode (:mode %)))))

(s/def ::remote-session-id string?)

(s/def ::session.handoff-data
  (s/keys :opt-un [::remote-session-id ::host]))

;; Remote sessions (Mission Control) — RPC result for session.remote.enable
;; (upstream PR #1192). The wire shape `{ url?, remoteSteerable }` becomes
;; `{ :url, :remote-steerable }` after `util/wire->clj` (camel-snake-kebab
;; does not append `?` for booleans). Note this is distinct from the
;; session-event spec ::remote-steerable? above.
(s/def ::remote-steerable boolean?)
(s/def ::remote-enable-result
  (s/keys :req-un [::remote-steerable]
          :opt-un [::url]))

;; Per-session remote mode (upstream CLI 1.0.48-1, PR #1288). `:off` disables
;; remote, `:export` exports session events to Mission Control without
;; enabling remote steering, `:on` enables both export and remote steering.
(s/def ::remote-session-mode #{:off :export :on})
;; Per-session remote mode set via session config (upstream PR #1295).
;; Same allowed values as the `remote-enable` opts `:mode`.
(s/def ::remote-session ::remote-session-mode)
;; Note: opts uses an unqualified `:mode` key (distinct from ::mode which is
;; reused elsewhere for the send queue mode). A custom predicate validates the
;; value rather than s/keys so we don't collide with the existing ::mode spec.
(s/def ::remote-enable-opts
  (s/and map?
         (fn [m]
           (or (not (contains? m :mode))
               (s/valid? ::remote-session-mode (:mode m))))))

;; Cloud session config (upstream PR #1306). When supplied to create-session,
;; creates a remote session in the cloud instead of a local session. Optional
;; :repository associates the cloud session with a GitHub repository.
;;
;; The literal :repository key inside cloud is a MAP — different from the
;; existing top-level ::repository spec which is a string used elsewhere.
;; Validate :repository against ::cloud-repository here via s/and, avoiding
;; a name collision in s/keys :opt-un.
(s/def ::owner ::non-blank-string)
;; The :name key inside ::cloud-repository must be non-blank. The shared
;; ::name spec is just `string?` (it is reused by many event/data shapes
;; where blanks are valid), so we enforce non-blankness here with a
;; predicate rather than redefining ::name globally.
(s/def ::cloud-repository
  (s/and (s/keys :req-un [::owner ::name]
                 :opt-un [::branch])
         #(not (clojure.string/blank? (:name %)))))
(s/def ::cloud
  (s/and map?
         (fn [m]
           (or (not (contains? m :repository))
               (s/valid? ::cloud-repository (:repository m))))))
(s/def ::interaction-id string?)
(s/def ::source string?)

;; user.message event data — attachments can include blobs (inbound-only types)
;; :is-autopilot-continuation — boolean, added upstream CLI 1.0.47 (PR #1286).
;; True when the message was auto-injected by autopilot's continuation loop
;; rather than typed by the user. Note: NO trailing `?` — camel-snake-kebab
;; converts wire `isAutopilotContinuation` to `:is-autopilot-continuation`
;; (csk does not append `?` for booleans). See `::remote-steerable` for the
;; same precedent.
;;
;; :agent-mode is intentionally NOT in :opt-un here: wire->clj keeps the
;; value as the wire string ("interactive", "plan", ...) on inbound events,
;; while caller-side ::agent-mode is a keyword set used for ::send-options.
;; Enum validation of the wire string is handled by the generated wire spec.
(s/def ::is-autopilot-continuation boolean?)
(s/def ::user.message-data
  (s/and (s/keys :req-un [::content]
                 :opt-un [::transformed-content ::source
                          ::interaction-id ::is-autopilot-continuation
                          ::message-id ::turn-id])
         #(or (not (contains? % :attachments))
              (s/valid? ::inbound-attachments (:attachments %)))))

;; Queued command response (CLI 1.0.45, session.commands.respondToQueuedCommand)
(s/def ::handled? boolean?)
(s/def ::stop-processing-queue? boolean?)

;; :server-tools — added upstream CLI 1.0.63, replacing the removed
;; anthropicAdvisorBlocks/anthropicAdvisorModel fields. Neutral provider-tagged
;; server-side tool-use payload (tool search, advisor) round-tripped verbatim.
;; Open map — passes through opaquely. :service-request-id is the
;; x-copilot-service-request-id header for CAPI log correlation.
(s/def ::server-tools map?)
(s/def ::service-request-id string?)
(s/def ::chunk-index nat-int?)
(s/def ::chunk-count pos-int?)
(s/def ::rte boolean?)
(s/def ::interaction-type string?)
(s/def ::intention-summary (s/nilable string?))
(s/def ::tool-title string?)
(s/def ::caller-id string?)
(s/def ::assistant-message-tool-request-caller-type #{"program"})
(s/def ::assistant-message-tool-request-type #{"function" "custom"})
(s/def ::assistant-message-tool-request-caller
  (s/and
   map?
   #(contains? % :caller-id)
   #(contains? % :type)
   #(string? (:caller-id %))
   #(s/valid? ::assistant-message-tool-request-caller-type (:type %))))
(s/def ::assistant-message-tool-request
  (s/and
   (s/keys :req-un [::tool-call-id ::name]
           :opt-un [::tool-title ::mcp-server-name ::mcp-tool-name
                    ::intention-summary])
   #(optional-field? % :arguments opaque-json-value?)
   #(optional-field? % :caller
                     (partial s/valid?
                              ::assistant-message-tool-request-caller))
   #(or (not (contains? % :type))
        (s/valid? ::assistant-message-tool-request-type (:type %)))))
(s/def ::tool-requests
  (s/coll-of ::assistant-message-tool-request :kind vector?))
(s/def ::assistant.message-data
  (s/keys :req-un [::message-id ::content]
          :opt-un [::tool-requests ::parent-tool-call-id ::encrypted-content
                   ::interaction-id ::output-tokens ::phase ::reasoning-opaque
                   ::reasoning-text ::request-id ::api-call-id
                   ::server-tools ::service-request-id ::turn-id ::model
                   ::chunk-index ::chunk-count ::rte]))

(s/def ::total-response-size-bytes nat-int?)
(s/def ::turn-id ::non-blank-string)

(s/def ::assistant.turn_start-data
  (s/keys :req-un [::turn-id]
          :opt-un [::interaction-id]))

(s/def ::assistant.reasoning-data
  (s/keys :req-un [::reasoning-id ::content]
          :opt-un [::rte]))

(s/def ::delta-content string?)

(s/def ::assistant.reasoning_delta-data
  (s/keys :req-un [::reasoning-id ::delta-content]))

(s/def ::assistant.message_delta-data
  (s/keys :req-un [::message-id ::delta-content]
          :opt-un [::parent-tool-call-id ::interaction-id]))

(s/def ::assistant.streaming_delta-data
  (s/keys :req-un [::total-response-size-bytes]))

(s/def ::api-call-id string?)
(s/def ::cache-expires-at ::instant)
(s/def ::cache-read-tokens nat-int?)
(s/def ::cache-write-tokens nat-int?)
(s/def ::cost number?)
(s/def ::duration nat-int?)
(s/def ::initiator string?)
(s/def ::input-tokens nat-int?)
(s/def ::inter-token-latency-ms nat-int?)
(s/def ::quota-snapshots map?)
;; :time-to-first-token-ms — schema renamed from :ttft-ms in CLI 1.0.51 wire schema
;; (property "ttftMs" → "timeToFirstTokenMs"). Both keys remain listed as :opt-un so
;; events from older CLI versions still validate; the new wire field is preferred.
;; Schema 1.0.70 widened the wire type integer → number (fractional milliseconds are
;; now valid), so the idiom spec accepts any non-negative number. The `<=` predicate
;; also rejects ##NaN (which `neg?` would let through), keeping the value a meaningful
;; duration.
(s/def ::time-to-first-token-ms (s/and json-number? #(<= 0 %)))
(s/def ::ttft-ms (s/and json-number? #(<= 0 %)))
;; :output-ttft-ms — upstream schema 1.0.83-1. Time-to-first-output-token,
;; distinct from ::time-to-first-token-ms; same non-negative-number semantics.
(s/def ::output-ttft-ms (s/and json-number? #(<= 0 %)))
(s/def ::copilot-usage map?)

;; :api-endpoint — open string enum, added upstream CLI 1.0.47 (PR #1286).
;; API endpoint used for this model call, matching CAPI supported_endpoints
;; vocabulary. Known values: "/chat/completions", "/v1/messages", "/responses",
;; "ws:/responses". Modeled as an open string for forward-compatibility — the
;; upstream wire spec restricts the enum, but the idiom spec deliberately
;; doesn't so unknown future values pass through.
(s/def ::api-endpoint string?)

;; :content-filter-triggered, :finish-reason — added upstream CLI 1.0.63.
;; True when the provider's content filter blocked output; finish-reason is the
;; provider's completion reason string (e.g. "stop", "length", "content_filter").
(s/def ::content-filter-triggered boolean?)
(s/def ::finish-reason string?)
(s/def ::accepted-prediction-tokens nat-int?)
(s/def ::rejected-prediction-tokens nat-int?)
(s/def ::is-auto boolean?)
(s/def ::is-byok boolean?)
(s/def ::assistant-usage-transport #{"http" "websocket"})

(s/def ::assistant.usage-data
  (s/and
   (s/keys :req-un [::model]
           :opt-un [::accepted-prediction-tokens ::api-call-id ::api-endpoint
                    ::cache-read-tokens ::cache-write-tokens ::cache-expires-at
                    ::copilot-usage ::cost ::duration ::initiator
                    ::interaction-type ::input-tokens ::inter-token-latency-ms
                    ::is-auto ::is-byok ::max-output-tokens ::max-prompt-tokens
                    ::output-tokens ::parent-tool-call-id ::provider-call-id
                    ::quota-snapshots ::reasoning-effort ::reasoning-tokens
                    ::rejected-prediction-tokens ::service-request-id
                    ::time-to-first-token-ms ::ttft-ms ::output-ttft-ms
                    ::content-filter-triggered ::finish-reason ::rte])
   #(or (not (contains? % :reasoning-summary))
        (contains? #{"none" "concise" "detailed"}
                   (:reasoning-summary %)))
   #(or (not (contains? % :transport))
        (s/valid? ::assistant-usage-transport (:transport %)))))

(s/def ::mcp-server-name string?)
(s/def ::mcp-tool-name string?)

(s/def ::tool.execution_start-data
  (s/and
   (s/keys :req-un [::tool-call-id ::tool-name]
           :opt-un [::parent-tool-call-id ::mcp-server-name ::mcp-tool-name ::model])
   #(optional-field? % :arguments opaque-json-value?)))

(s/def ::progress-message string?)
(s/def ::success boolean?)

(s/def ::tool.execution_progress-data
  (s/keys :req-un [::tool-call-id ::progress-message]))

(defn- tool-execution-complete-error?
  [error]
  (and (map? error)
       (required-value? error :message string?)
       (optional-field? error :code string?)
       (optional-field? error :remediation
                        (partial s/valid? ::remediation-action))))

(defn- tool-execution-complete-result?
  [result]
  (and (map? result)
       (required-value? result :content string?)
       (optional-field? result :detailed-content string?)
       (optional-field? result :contents (partial vector-of? map?))
       (optional-field? result :binary-results-for-llm (partial vector-of? map?))
       (optional-field? result :ui-resource map?)
       (optional-field? result :structured-content opaque-json-value?)
       (optional-field? result :citable-sources (partial vector-of? map?))
       (optional-field? result :mcp-meta opaque-json-value?)))

(defn- tool-execution-complete-telemetry?
  [telemetry]
  (and (map? telemetry)
       (every? string? (keys telemetry))
       (every? opaque-json-value? (vals telemetry))))

(defn- tool-execution-complete-description?
  [description]
  (and (map? description)
       (required-value? description :name string?)
       (optional-field? description :description string?)
       (optional-field? description :meta json-object-value?)))

(defn- fusion-attribution?
  [fusion]
  (and
   (map? fusion)
   (every? #(optional-field? fusion % string?)
           [:commit-id :conversation-scope :phase-id :phase-kind :role
            :source-model :source-phase-id])
   (every? #(required-value? fusion % string?)
           [:fusion-id :synthetic-model :policy :pattern])))

(s/def ::tool.execution_complete-data
  (s/and
   (s/keys :req-un [::tool-call-id ::success])
   #(optional-field? % :result tool-execution-complete-result?)
   #(optional-field? % :error tool-execution-complete-error?)
   #(optional-field? % :fusion fusion-attribution?)
   #(optional-field? % :interaction-id string?)
   #(optional-field? % :is-user-requested boolean?)
   #(optional-field? % :mcp-meta opaque-json-value?)
   #(optional-field? % :model string?)
   #(optional-field? % :parent-tool-call-id string?)
   #(optional-field? % :rte boolean?)
   #(optional-field? % :sandboxed boolean?)
   #(optional-field? % :tool-description tool-execution-complete-description?)
   #(optional-field? % :tool-telemetry tool-execution-complete-telemetry?)
   #(optional-field? % :turn-id string?)))

;; Permission event data — resolved-by-hook indicates the runtime already handled
;; this permission request via a permissionRequest hook (upstream PR #999).
(s/def ::resolved-by-hook boolean?)

;; Session shutdown event
(s/def ::shutdown-type #{"routine" "error"})
(s/def ::error-reason string?)
(s/def ::total-premium-requests nat-int?)
(s/def ::total-api-duration-ms nat-int?)
(s/def ::session-start-time number?)
(s/def ::code-changes map?)
(s/def ::model-metrics map?)
(s/def ::current-model string?)
(s/def ::total-nano-aiu (s/and number? #(<= 0 %)))
(s/def ::shutdown-agent-metric
  (s/keys :req-un [::model-metrics ::total-api-duration-ms ::total-nano-aiu]
          :opt-un [::agent-name ::agent-display-name]))
(s/def ::agent-metrics (s/map-of keyword? ::shutdown-agent-metric))

(s/def ::session.shutdown-data
  (s/keys :req-un [::shutdown-type ::total-api-duration-ms
                   ::session-start-time ::code-changes ::model-metrics]
          :opt-un [::error-reason ::current-model ::total-premium-requests
                   ::events-file-size-bytes ::agent-metrics ::total-nano-aiu]))

;; Session title changed event
(s/def ::title string?)
(s/def ::session.title_changed-data
  (s/keys :req-un [::title]))

;; Session warning event
(s/def ::warning-type string?)
(s/def ::session.warning-data
  (s/keys :req-un [::warning-type ::message]
          :opt-un [::remediation ::url]))

;; Session context changed event
(s/def ::session.context_changed-data
  (s/keys :req-un [::cwd]
          :opt-un [::git-root ::repository ::branch]))

;; Session model change event (upstream PR #796)
(s/def ::previous-model (s/nilable string?))
(s/def ::new-model string?)
(s/def ::previous-reasoning-effort string?)
(s/def ::model-change-source
  #{"model_command" "config_command" "settings_command" "model_picker"
    "plan_mode" "automatic" "startup" "repo_settings" "managed_settings"
    "agent" "sdk"})
(s/def ::session.model_change-data
  (s/and
   (s/keys :req-un [::new-model]
           :opt-un [::previous-model ::previous-reasoning-effort
                    ::reasoning-effort ::source])
   #(or (not (contains? % :source))
        (s/valid? ::model-change-source (:source %)))))

;; Session mode changed event
(s/def ::previous-mode string?)
(s/def ::new-mode string?)
(s/def ::session.mode_changed-data
  (s/keys :req-un [::previous-mode ::new-mode]))

;; This event is experimental upstream. Schema 1.0.81-5 replaced its aggregate
;; allow-all booleans with the authoritative permission-mode transition.
(s/def ::permission-mode #{"manual" "assisted" "allow-all"})
(s/def ::assisted-approval-model string?)
(s/def ::session.permissions_changed-data
  (s/and
   (s/keys :req-un [::previous-mode]
           :opt-un [::assisted-approval-model])
   #(contains? % :mode)
   #(s/valid? ::permission-mode (:mode %))
   #(s/valid? ::permission-mode (:previous-mode %))))

;; Hook progress event (upstream schema 1.0.56-1, round 6 sync). Ephemeral
;; event emitted by hooks during long-running work. Reuses the existing
;; ::message non-blank-string spec. `:temporary` (added v1.0.1, PR #1612)
;; signals a transient progress message that may be replaced.
(s/def ::temporary boolean?)
(s/def ::hook.progress-data
  (s/keys :req-un [::message]
          :opt-un [::temporary]))

;; Hook start/end events (upstream schema 1.0.83-1, stable 2980c78 sync).
;; `:parent-tool-call-id` (both) added on top of already-stable hook events.
(s/def ::hook-invocation-id string?)
(s/def ::hook-type string?)
(s/def ::hook-end-error
  (s/and
   map?
   #(string? (:message %))
   #(or (not (contains? % :stack)) (string? (:stack %)))
   #(or (not (contains? % :source)) (string? (:source %)))))
(s/def ::hook.start-data
  (s/and
   (s/keys :req-un [::hook-invocation-id ::hook-type]
           :opt-un [::parent-tool-call-id])
   #(optional-field? % :input opaque-json-value?)))
(s/def ::hook.end-data
  (s/and
   (s/keys :req-un [::hook-invocation-id ::hook-type ::success]
           :opt-un [::parent-tool-call-id])
   #(optional-field? % :output opaque-json-value?)
   #(or (not (contains? % :error))
        (s/valid? ::hook-end-error (:error %)))))

;; Session plan changed event
(s/def ::operation #{"create" "update" "delete"})
(s/def ::session.plan_changed-data
  (s/keys :req-un [::operation]))

;; Session workspace file changed event
;; ::path already defined above; ::operation reused but constrained to create/update
(s/def ::session.workspace_file_changed-data
  (s/and (s/keys :req-un [::path ::operation])
         #(contains? #{"create" "update"} (:operation %))))

;; Session task complete event
(s/def ::aborted? boolean?)
(s/def ::outcome #{"completed" "continue" "blocked"})
(s/def ::objective-id integer?)
(s/def ::reason string?)
(s/def ::session.task_complete-data
  (s/keys :opt-un [::summary ::aborted? ::success
                   ::outcome ::objective-id ::reason]))

;; Conversation compaction metadata added through schema 1.0.79-6.
(s/def ::trigger
  #{"threshold" "context_limit_retry" "manual" "memory_pressure" "model_switch"})
(s/def ::current-tokens nat-int?)
(s/def ::token-limit nat-int?)
(s/def ::session.compaction_start-data
  (s/keys :opt-un [::model ::current-tokens ::token-limit ::trigger]))
;; :behavior-model-id — upstream schema 1.0.83-1. Identifies the behavior
;; model used to drive compaction, when applicable.
(s/def ::behavior-model-id string?)
(s/def ::session.compaction_complete-data
  (s/and
   (s/keys :req-un [::success]
           :opt-un [::status-code ::token-limit ::trigger ::behavior-model-id])
   #(optional-field? % :error string?)))

;; Context-cleared event (upstream PR #2129).
(s/def ::messages-cleared nat-int?)
(s/def ::initial-message string?)
(s/def ::session.context_cleared-data
  (s/keys :req-un [::messages-cleared]
          :opt-un [::initial-message]))

;; Agent Factory run invalidation event (upstream PR #2114).
(s/def ::run-id ::non-blank-string)
(s/def ::revision nat-int?)
(s/def ::factory.run_updated-data
  (s/keys :req-un [::run-id ::revision]))

;; Schedule events (upstream schema 1.0.42; v1.0.1 added cron/at variants —
;; upstream PR #1597). `:interval-ms` is now optional; cron-only and one-shot
;; (`:at`) schedules are also valid.
(s/def ::interval-ms pos-int?)
(s/def ::at pos-int?)
(s/def ::cron string?)
(s/def ::tz string?)
;; ::prompt is already defined above (::non-blank-string), reused here
;; :recurring — boolean, added upstream CLI 1.0.48-1 (PR #1288). Whether the
;; schedule re-arms after each tick (`/every`) or fires once (`/after`).
;; Note: NO trailing `?` — camel-snake-kebab converts wire `recurring` to
;; `:recurring` (csk does not append `?` for booleans).
(s/def ::recurring boolean?)
(s/def ::session.schedule_created-data
  (s/and (s/keys :req-un [::prompt]
                 :opt-un [::recurring ::interval-ms ::at ::cron ::tz])
         #(contains? % :id)
         #(pos-int? (:id %))))
(s/def ::session.schedule_cancelled-data
  (s/and map?
         #(contains? % :id)
         #(pos-int? (:id %))))

;; Canvas event data and snapshot entries (upstream PR #1604; v1.0.4 dropped
;; reopen/availability). Wire fields: instanceId, extensionId, canvasId,
;; extensionName, status, title, url, input, icon. csk converts to kebab-case.
;; `:input` is opaque caller-supplied data — open map per upstream
;; `{ [k: string]: unknown }`.
(s/def ::instance-id ::non-blank-string)
(s/def ::extension-id ::non-blank-string)
(s/def ::canvas-id ::non-blank-string)
(s/def ::extension-name string?)
(s/def ::input map?)
(s/def ::icon string?)

(s/def ::open-canvas-instance
  (s/keys :req-un [::instance-id ::extension-id ::canvas-id]
          :opt-un [::extension-name ::title ::status ::url ::input ::icon]))

(s/def ::open-canvases (s/coll-of ::open-canvas-instance :kind sequential? :into []))

(s/def ::session.canvas.closed-data
  (s/keys :req-un [::instance-id ::extension-id ::canvas-id]))

;; SkillSource is closed for inventory/configuration records. The
;; skill.invoked event's similarly named :source field is an open string.
(s/def ::allowed-tools (s/coll-of string?))
(s/def ::plugin-name string?)
(s/def ::plugin-version string?)
(s/def ::disable-model-invocation boolean?)
(s/def ::skill-source
  #{"project"
    "inherited"
    "personal-copilot"
    "personal-agents"
    "plugin"
    "custom"
    "builtin"
    "sdk"})
;; ::description already defined above

(s/def ::skill.invoked-data
  (s/and
   (s/keys :req-un [::name ::content]
           :opt-un [::allowed-tools ::plugin-name ::plugin-version ::description
                    ::disable-model-invocation ::source])
   #(required-value? % :path string?)))

;; Subagent event data (upstream PR #916)
(s/def ::agent-display-name string?)
(s/def ::agent-description string?)
(s/def ::total-tool-calls nat-int?)
(s/def ::total-tokens nat-int?)
(s/def ::duration-ms nat-int?)
(s/def ::cancelled boolean?)
(s/def ::factory-run-id ::non-blank-string)

;; Additive subagent fields (upstream schema 1.0.83-1, stable 2980c78 sync).
;; `::subagent-parent-id` is deliberately distinct from the pre-existing
;; `::parent-id` (a nilable non-blank-string used on the session-event
;; envelope itself) -- SubagentStartedData's `parentId` is an unrelated,
;; plain optional string (task-registry id of the spawning sub-agent, no
;; nilable/non-blank constraint), so it cannot be validated via `:opt-un`
;; (which would key on the already-registered, differently-typed
;; `::parent-id`). We validate the `:parent-id` map key with an explicit
;; predicate instead, following the same pattern used for `:mode`/auto-tier.
(s/def ::subagent-parent-id string?)
(s/def ::resumable boolean?)
(s/def ::agent-type string?)
(s/def ::execution-mode string?)
(s/def ::first-dispatched-model string?)
(s/def ::configured-model-preference string?)
(s/def ::explicit-model-override string?)
(s/def ::explicit-model-matches-preference boolean?)
(s/def ::configured-model-matches-actual boolean?)
(s/def ::model-override-reason string?)

(s/def ::subagent.started-data
  (s/and
   (s/keys :req-un [::tool-call-id ::agent-name ::agent-display-name
                    ::agent-description]
           :opt-un [::factory-run-id ::model ::resumable
                    ::agent-type ::execution-mode])
   #(or (not (contains? % :parent-id))
        (s/valid? ::subagent-parent-id (:parent-id %)))))

(s/def ::subagent.completed-data
  (s/keys :req-un [::tool-call-id ::agent-name ::agent-display-name]
          :opt-un [::cancelled ::model ::total-tool-calls ::total-tokens ::duration-ms
                   ::first-dispatched-model ::configured-model-preference
                   ::explicit-model-override ::explicit-model-matches-preference
                   ::configured-model-matches-actual ::model-override-reason]))

(s/def ::subagent.failed-data
  (s/and
   (s/keys :req-un [::tool-call-id ::agent-name ::agent-display-name]
           :opt-un [::model ::total-tool-calls ::total-tokens ::duration-ms
                    ::first-dispatched-model ::configured-model-preference
                    ::explicit-model-override ::explicit-model-matches-preference
                    ::configured-model-matches-actual ::model-override-reason])
   #(and (contains? % :error) (string? (:error %)))))

;; session.custom_agents_updated event data (upstream PR #916)
(s/def ::user-invocable? boolean?)
(s/def ::agent-tool-names (s/coll-of string?))
(s/def ::agent-model-preferences
  (s/coll-of string? :kind vector?))
(s/def ::agent-model-policy #{"preferred" "required"})

(s/def ::custom-agent-info
  (s/and (s/keys :req-un [::id ::name ::display-name ::description ::source ::user-invocable?]
                 :opt-un [::model])
         #(contains? #{"user" "project" "inherited" "remote" "plugin"} (:source %))
         ;; tools is required by the upstream schema but the value may be
         ;; null (`tools: string[] | null` in CustomAgentsUpdatedAgent;
         ;; upstream schema 1.0.41-1). Enforce key presence here, then
         ;; validate the value as either nil or a string-coll.
         #(contains? % :tools)
         #(or (nil? (:tools %)) (s/valid? ::agent-tool-names (:tools %)))
         #(optional-field? % :models
                           (partial s/valid? ::agent-model-preferences))
         #(optional-field? % :model-policy
                           (partial s/valid? ::agent-model-policy))))

(s/def ::agents (s/coll-of ::custom-agent-info))
(s/def ::warnings (s/coll-of string?))
(s/def ::errors (s/coll-of string?))

(s/def ::session.custom_agents_updated-data
  (s/keys :req-un [::agents ::warnings ::errors]))

;; session.custom_notification (upstream PR #1292, CLI 1.0.48). Emitted from
;; Skills via Notify; opaque envelope with extension-supplied payload.
;; Note: the wire schema's :version is a positive integer, distinct from the
;; SDK's global ::version spec (string?) used by ::model-info. We validate
;; :version inline via s/and to avoid collision.
;; `:subject` keys are preserved verbatim (not kebab-cased) by
;; protocol/normalize-incoming — subject identifiers and payload contents are
;; source-defined and opaque. Keys are still keywords (JSON parser uses
;; `:key-fn keyword`) but retain original casing/dots.
(s/def ::payload any?)
(s/def ::subject (s/map-of keyword? string?))
(s/def ::session.custom_notification-data
  (s/and (s/keys :req-un [::source ::name ::payload]
                 :opt-un [::subject])
         #(or (not (contains? % :version))
              (pos-int? (:version %)))))

;; Session status/listing events from generated session-events schema.
(s/def ::mcp-server-status
  #{"connected" "failed" "needs-auth" "pending" "disabled" "stopped"
    "not_configured"})
(s/def ::status string?)
(s/def ::server-metadata
  (s/keys :req-un [::instructions]))
(s/def ::mcp-loaded-server
  (s/and (s/keys :req-un [::name ::status]
                 :opt-un [::source ::plugin-name ::plugin-version
                          ::server-metadata])
         #(optional-field? % :error string?)
         #(s/valid? ::mcp-server-status (:status %))))
(s/def ::servers (s/coll-of ::mcp-loaded-server))
(s/def ::session.mcp_servers_loaded-data
  (s/keys :req-un [::servers]))
(s/def ::server-name string?)
(s/def ::session.mcp_server_status_changed-data
  (s/and (s/keys :req-un [::server-name ::status])
         #(s/valid? ::mcp-server-status (:status %))))
(s/def ::session.mcp_server_removed-data
  (s/keys :req-un [::server-name]))
(s/def ::session.mcp_server_needs_reconnect-data
  (s/keys :req-un [::server-name]))

(s/def ::user-invocable boolean?)
(s/def ::skill-info
  (s/and (s/keys :req-un [::name ::description ::enabled ::source ::user-invocable]
                 :opt-un [::path])
         #(s/valid? ::skill-source (:source %))))
(s/def ::skills (s/coll-of ::skill-info))
(s/def ::session.skills_loaded-data
  (s/keys :req-un [::skills]))

(s/def ::extension-status #{"running" "disabled" "failed" "starting"})
(s/def ::extension-info
  (s/and (s/keys :req-un [::id ::name ::source ::status])
         #(s/valid? ::extension-status (:status %))))
(s/def ::extensions (s/coll-of ::extension-info))
(s/def ::session.extensions_loaded-data
  (s/keys :req-un [::extensions]))

;; Stable host-facing event payloads. These maps remain open so additive wire
;; fields continue to pass through, while the exported fields below retain their
;; caller-facing types.

(s/def ::session.mode_notice_delivered-data
  (s/and map?
         #(required-value? % :mode session-modes)
         #(optional-field? % :content string?)))

(s/def ::model-call-failure-source #{"top_level" "subagent" "mcp_sampling"})
(s/def ::model.call_failure-data
  (s/and (s/keys :req-un [::source]
                 :opt-un [::interaction-type])
         #(s/valid? ::model-call-failure-source (:source %))))

(s/def ::model.call_finished-data
  (s/and map?
         #(required-value? % :turn-id string?)
         #(optional-field? % :interaction-id string?)
         #(required-value? % :dispatch-duration-ms
                           (fn [duration]
                             ;; json-number? rejects NaN/Infinity (non-finite doubles);
                             ;; neg? guards against negative durations.
                             (and (json-number? duration)
                                  (not (neg? duration)))))
         #(required-value? % :outcome #{"success" "error" "cancelled" "rejected"})
         #(optional-field? % :contains-built-in-file-edit-request boolean?)
         #(required-value? % :edit-classifier-version pos-int?)))

(s/def ::subagent.configured-data
  (s/and map?
         #(required-value? % :model string?)
         #(optional-field? % :reasoning-effort string?)
         #(optional-field? % :context-tier string?)
         #(required-value? % :multi-turn boolean?)))

(defn- notification-map?
  [m required allowed]
  (and (map? m)
       (every? #(contains? m %) required)
       (empty? (set/difference (set (keys m)) allowed))))

(defn- system-notification-kind?
  [kind]
  (case (:type kind)
    "agent_completed"
    (and (notification-map?
          kind
          #{:type :agent-id :agent-type :status}
          #{:type :agent-id :display-name :agent-type :status :description :prompt})
         (s/valid? ::non-blank-string (:agent-id kind))
         (string? (:agent-type kind))
         (contains? #{"completed" "failed"} (:status kind))
         (optional-field? kind :display-name string?)
         (optional-field? kind :description string?)
         (optional-field? kind :prompt string?))

    "agent_idle"
    (and (notification-map?
          kind
          #{:type :agent-id :agent-type}
          #{:type :agent-id :display-name :agent-type :description})
         (s/valid? ::non-blank-string (:agent-id kind))
         (string? (:agent-type kind))
         (optional-field? kind :display-name string?)
         (optional-field? kind :description string?))

    "new_inbox_message"
    (and (notification-map?
          kind
          #{:type :entry-id :sender-name :sender-type :summary}
          #{:type :entry-id :sender-name :sender-type :summary})
         (every? string?
                 ((juxt :entry-id :sender-name :sender-type :summary) kind)))

    "shell_completed"
    (and (notification-map?
          kind
          #{:type :shell-id}
          #{:type :shell-id :exit-code :description})
         (string? (:shell-id kind))
         (optional-field? kind :exit-code int?)
         (optional-field? kind :description string?))

    "shell_detached_completed"
    (and (notification-map?
          kind
          #{:type :shell-id}
          #{:type :shell-id :description})
         (string? (:shell-id kind))
         (optional-field? kind :description string?))

    "instruction_discovered"
    (and (notification-map?
          kind
          #{:type :source-path :trigger-file :trigger-tool}
          #{:type :source-path :trigger-file :trigger-tool :description})
         (every? string?
                 ((juxt :source-path :trigger-file :trigger-tool) kind))
         (optional-field? kind :description string?))

    "factory_completed"
    (and (notification-map?
          kind
          #{:type :run-id :factory-name :status :consumed-subagents
            :elapsed-ms :consumed-nano-aiu :attempt}
          #{:type :run-id :factory-name :status :consumed-subagents
            :elapsed-ms :consumed-nano-aiu :attempt :result-preview
            :failure :retry-guidance})
         (string? (:run-id kind))
         (string? (:factory-name kind))
         (contains? #{"completed" "halted" "cancelled" "error"} (:status kind))
         (nat-int? (:consumed-subagents kind))
         (nat-int? (:elapsed-ms kind))
         (nat-int? (:consumed-nano-aiu kind))
         (pos-int? (:attempt kind))
         (optional-field? kind :result-preview
                          #(and (string? %) (<= (count %) 256)))
         (optional-field? kind :failure opaque-json-value?)
         (optional-field? kind :retry-guidance string?))

    "unclassified"
    (and (notification-map? kind #{:type} #{:type :metadata})
         (optional-field? kind :metadata opaque-json-value?))

    false))

(s/def ::system-notification-kind system-notification-kind?)
(s/def ::system.notification-data
  (s/and map?
         #(string? (:content %))
         #(s/valid? ::system-notification-kind (:kind %))))

(s/def ::provider-id (s/nilable string?))
(s/def ::external_tool.requested-data
  (s/keys :req-un [::request-id ::session-id ::tool-call-id ::tool-name]
          :opt-un [::provider-id]))

;; Generic session event
(s/def ::session-event
  (s/merge ::base-event
           (s/keys :req-un [::event-type ::data])))

;; -----------------------------------------------------------------------------
;; Tool call/result types
;; -----------------------------------------------------------------------------

;; Pinned schema (1.0.83-1) declares toolCallId as a plain string with no
;; minLength across every event that carries it; empty string is a valid value.
(s/def ::tool-call-id string?)
(s/def ::result-type
  (s/or :keyword #{:success :failure :rejected :denied :timeout}
        :string #{"success" "failure" "rejected" "denied" "timeout"}))
(s/def ::text-result-for-llm string?)
(s/def ::session-log string?)
(s/def ::json-value
  (s/or :null nil?
        :string string?
        :number json-number?
        :boolean boolean?
        :array (s/coll-of ::json-value :kind vector?)
        :object (s/map-of string? ::json-value)))
(s/def ::json-object (s/map-of string? ::json-value))
(s/def ::tool-telemetry (s/map-of string? ::json-object))
(s/def ::tool-references (s/coll-of ::non-blank-string))

(s/def ::current-tool-metadata
  (s/and
   map?
   #(string? (:name %))
   #(string? (:description %))
   #(or (not (contains? % :namespaced-name))
        (string? (:namespaced-name %)))
   #(or (not (contains? % :mcp-server-name))
        (string? (:mcp-server-name %)))
   #(or (not (contains? % :mcp-tool-name))
        (string? (:mcp-tool-name %)))
   #(or (not (contains? % :input-schema))
        (map? (:input-schema %)))
   #(or (not (contains? % :defer-loading))
        (boolean? (:defer-loading %)))))

(s/def ::current-tool-metadata-list (s/coll-of ::current-tool-metadata))

(s/def ::tool-invocation
  (s/and
   map?
   #(s/valid? ::non-blank-string (:session-id %))
   #(s/valid? ::non-blank-string (:tool-call-id %))
   #(s/valid? ::non-blank-string (:tool-name %))
   #(contains? % :arguments)
   #(or (not (contains? % :available-tools))
        (s/valid? ::current-tool-metadata-list (:available-tools %)))
   #(or (not (contains? % :traceparent))
        (string? (:traceparent %)))
   #(or (not (contains? % :tracestate))
        (string? (:tracestate %)))
   #(or (not (contains? % :cancel-chan))
        (satisfies? async-protocols/Channel (:cancel-chan %)))))

;; Binary result items for tool results (upstream ToolBinaryResult)
;; Each item has :data (base64 string), :mime-type, :type ("image"/"resource"),
;; and optional :description. Uses map? to avoid conflicts with existing specs
;; for ::type (attachment-specific) — binary result items have different semantics.
(s/def ::binary-results-for-llm (s/coll-of map?))

(s/def ::tool-result-object
  (s/and
   (s/keys :req-un [::text-result-for-llm ::result-type]
           :opt-un [::binary-results-for-llm ::session-log ::tool-telemetry
                    ::tool-references])
   #(optional-field? % :error string?)))

(s/def ::tool-result
  (s/or :string string?
        :object ::tool-result-object))

;; -----------------------------------------------------------------------------
;; Permission types
;; -----------------------------------------------------------------------------

(s/def ::permission-kind
  #{:shell :write :mcp :read :url :custom-tool :memory :hook
    :extension-management :extension-permission-access
    :extension-env-access :factory})

;; Memory permission event data fields (CLI 1.0.22, upstream PR #1055)
(s/def ::memory-action #{:store :vote})
(s/def ::memory-direction #{:upvote :downvote})
(s/def ::memory-reason string?)
(s/def ::managed-approval-required boolean?)
(s/def ::command-segments (s/coll-of map? :kind vector?))
(s/def ::redirected-from string?)
(s/def ::factory-operation #{"run" "author"})
(s/def ::factory-permission-phases (s/coll-of ::factory-phase :kind vector?))
(s/def ::approval-key ::non-blank-string)
(s/def ::can-persist-approval boolean?)
(s/def ::declared-max-concurrent-subagents nat-int?)
(s/def ::declared-max-total-subagents nat-int?)
(s/def ::declared-timeout-seconds number?)
(s/def ::declared-max-ai-credits number?)
(s/def ::skip-permission boolean?)
(s/def ::repo-nwo ::non-blank-string)
(s/def ::scope #{"repository" "user"})
(s/def ::environment-variables
  (s/coll-of ::non-blank-string :kind vector? :min-count 1))

;; PermissionPromptRequestMcp additive field (upstream schema 1.0.83-1, stable
;; 2980c78 sync). ::server-name/::tool-name/::tool-title already exist and
;; match the MCP variant's field types exactly.
(s/def ::can-offer-server-wide-approval boolean?)

(s/def ::permission-request
  (s/and
   (s/keys :req-un [::permission-kind]
           :opt-un [::tool-call-id ::memory-action ::memory-direction ::memory-reason
                    ::can-offer-session-approval ::managed-approval-required
                    ::skip-permission ::repo-nwo ::scope
                    ::extension-name ::environment-variables
                    ::command-segments ::redirected-from
                    ::name ::description ::phases ::approval-key ::can-persist-approval
                    ::declared-max-concurrent-subagents
                    ::declared-max-total-subagents
                    ::declared-timeout-seconds ::declared-max-ai-credits
                    ::server-name ::tool-name ::tool-title
                    ::can-offer-server-wide-approval])
   #(or (not= :factory (:permission-kind %))
        (and (s/valid? ::factory-operation (:operation %))
             (s/valid? ::non-blank-string (:name %))
             (s/valid? ::non-blank-string (:description %))
             (s/valid? ::factory-permission-phases (:phases %))
             (s/valid? ::non-blank-string (:approval-key %))
             (boolean? (:can-persist-approval %))
             (or (not (contains? % :max-concurrent-subagents))
                 (nat-int? (:max-concurrent-subagents %)))
             (or (not (contains? % :max-total-subagents))
                 (nat-int? (:max-total-subagents %)))
             (or (not (contains? % :timeout-seconds))
                 (number? (:timeout-seconds %)))
             (or (not (contains? % :max-ai-credits))
                 (number? (:max-ai-credits %)))))
   #(or (not= :extension-env-access (:permission-kind %))
        (and (s/valid? ::non-blank-string (:extension-name %))
             (s/valid? ::environment-variables
                       (:environment-variables %))))))

(s/def ::permission-prompt-request map?)
(s/def ::resolved-by-hook boolean?)
(s/def ::risk-assessment opaque-json-value?)
(s/def ::permission.requested-data
  (s/and
   map?
   #(required-value? % :request-id string?)
   #(required-value? % :permission-request map?)
   #(optional-field? % :prompt-request map?)
   #(optional-field? % :resolved-by-hook boolean?)
   #(optional-field? % :risk-assessment opaque-json-value?)
   #(optional-field? % :agent-mode session-modes)))

(s/def ::permission-result-kind
  #{:approve-once
    :approve-for-session
    :approve-for-location
    :reject
    :user-not-available
    :no-result
    ;; Legacy Clojure aliases accepted at API boundaries and normalized before
    ;; sending decisions to the CLI.
    :approved
    :denied-by-rules
    :denied-no-approval-rule-and-could-not-request-from-user
    :denied-interactively-by-user
    :denied-by-content-exclusion-policy
    :denied-by-permission-request-hook})
(s/def ::approval map?)
(s/def ::location-key ::non-blank-string)
(s/def ::rules (s/coll-of map?))
(s/def ::feedback string?)
(s/def ::kind ::permission-result-kind)

(s/def ::permission-result
  (closed-keys
   (s/keys :req-un [::kind]
           :opt-un [::rules ::approval ::location-key ::feedback])
   #{:kind :rules :approval :location-key :feedback}))

(s/def ::permission-decision-outcome
  #{:auto-approved :autopilot-denied :prompted-user})
(s/def ::permission-decision-source
  #{:assisted-approval :human-response :host-policy :unattended-fallback})
(s/def ::permission-decision-surface
  #{:tui :prompt-mode :copilot-app :sdk :acp})
(s/def ::permission-response-capability
  #{:interactive :headless :none})

(s/def ::permission-decision-context
  (s/and
   (closed-keys map? #{:outcome :source :surface :response-capability})
   #(s/valid? ::permission-decision-outcome (:outcome %))
   #(s/valid? ::permission-decision-source (:source %))
   #(s/valid? ::permission-decision-surface (:surface %))
   #(or (not (contains? % :response-capability))
        (s/valid? ::permission-response-capability (:response-capability %)))))

(s/def ::attributed-permission-result
  (s/and
   (closed-keys map? #{:kind :result :decision-context})
   #(= :attributed (:kind %))
   #(s/valid? ::permission-result (:result %))
   #(s/valid? ::permission-decision-context (:decision-context %))))

(s/def ::permission-handler-result
  (s/or :plain ::permission-result
        :attributed ::attributed-permission-result))

;; -----------------------------------------------------------------------------
;; Client record spec
;; -----------------------------------------------------------------------------

(s/def ::options map?)
(s/def ::external-server? boolean?)
(s/def ::actual-host string?)
(s/def ::state #(instance? clojure.lang.Atom %))

(s/def ::client
  (s/keys :req-un [::options ::state]
          :opt-un [::external-server? ::actual-host ::on-list-models]))

;; -----------------------------------------------------------------------------
;; Session record spec
;; -----------------------------------------------------------------------------

(s/def ::session
  (s/keys :req-un [::session-id ::client]
          :opt-un [::workspace-path]))

(s/def ::granted-environment-variables (s/map-of string? string?))
(s/def ::join-session-result
  (closed-keys
   (s/keys :req-un [::client ::session]
           :opt-un [::granted-environment-variables])
   #{:client :session :granted-environment-variables}))

;; -----------------------------------------------------------------------------
;; API response specs
;; -----------------------------------------------------------------------------

(s/def ::version string?)
(s/def ::protocol-version int?)
(s/def ::authenticated? boolean?)
(s/def ::auth-type keyword?)
(s/def ::host string?)
(s/def ::login string?)
(s/def ::status-message string?)

;; Model capabilities. These three specs describe deprecated input aliases
;; retained by ::model-capabilities for source compatibility.
(s/def ::supports-vision boolean?)
(s/def ::supports-reasoning-effort boolean?)
(s/def ::model-supports
  (s/keys :opt-un [::supports-vision ::supports-reasoning-effort]))

(s/def ::max-prompt-tokens int?)
(s/def ::max-context-window-tokens int?)
(s/def ::supported-media-types (s/coll-of string?))
(s/def ::max-prompt-images int?)
(s/def ::max-prompt-image-size int?)
(s/def ::vision-capabilities
  (s/keys :opt-un [::supported-media-types ::max-prompt-images ::max-prompt-image-size]))
(s/def ::model-limits
  (s/keys :opt-un [::max-prompt-tokens ::max-context-window-tokens ::vision-capabilities]))

;; Model policy
(s/def ::policy-state #{"enabled" "disabled" "unconfigured"})
(s/def ::terms string?)
(s/def ::model-policy
  (s/keys :opt-un [::policy-state ::terms]))

;; Model billing
(s/def ::multiplier number?)
;; :token-prices — added upstream CLI 1.0.63 (AI Credits pricing metadata).
;; All fields are AI Credits per billing batch unless noted. The :long-context
;; tier mirrors the standard tier minus :batch-size and its own :long-context.
(s/def ::input-price number?)
(s/def ::output-price number?)
(s/def ::cache-price number?)
(s/def ::batch-size number?)
(s/def ::context-max number?)
(s/def ::long-context
  (s/keys :opt-un [::input-price ::output-price ::cache-price ::context-max]))
(s/def ::token-prices
  (s/keys :opt-un [::input-price ::output-price ::cache-price ::batch-size
                   ::context-max ::long-context]))
(s/def ::discount-percent
  (s/and number? #(<= 0 % 100)))
(s/def ::ends-at string?)
(s/def ::model-billing-promo
  (s/and (s/keys :opt-un [::ends-at ::id ::discount-percent])
         #(or (not (contains? % :message))
              (string? (:message %)))))
(s/def ::promo ::model-billing-promo)
(s/def ::model-billing
  (s/keys :opt-un [::multiplier ::token-prices ::promo]))

;; Supported reasoning efforts
(s/def ::supported-reasoning-efforts (s/coll-of string?))
(s/def ::default-reasoning-effort string?)

;; Model picker categorization (CLI 1.0.46). Upstream defines closed enums,
;; but the idiom spec keeps these as strings so future categories pass through
;; unchanged on the wire.
(s/def ::model-picker-category string?)
(s/def ::model-picker-price-category string?)

;; Model info
(s/def ::id string?)
(s/def ::name string?)
(s/def ::vendor string?)
(s/def ::family string?)
(s/def ::max-input-tokens int?)
(s/def ::max-output-tokens int?)
(s/def ::preview? boolean?)
(s/def ::model-info
  (s/keys :req-un [::id ::name]
          :opt-un [::vendor ::family ::version ::max-input-tokens ::max-output-tokens
                   ::preview? ::default-temperature ::model-picker-priority
                   ::model-capabilities ::model-policy ::model-billing
                   ::supported-reasoning-efforts ::default-reasoning-effort
                   ::vision-limits
                   ::model-picker-category ::model-picker-price-category]))

;; Misc specs for instrument.clj
(s/def ::message-id string?)
(s/def ::events-ch any?)  ; core.async channel
(s/def ::buffer pos-int?)
(s/def ::xf fn?)
(s/def ::max-events nat-int?)

;; -----------------------------------------------------------------------------
;; Tool listing (tools.list RPC)
;; -----------------------------------------------------------------------------

(s/def ::namespaced-name string?)
(s/def ::description string?)
(s/def ::parameters (s/nilable map?))
(s/def ::instructions (s/nilable string?))

(s/def ::tool-info-entry
  (s/keys :req-un [::name ::description]
          :opt-un [::namespaced-name ::parameters ::instructions]))

;; -----------------------------------------------------------------------------
;; Account quota (account.getQuota RPC)
;; -----------------------------------------------------------------------------

(s/def ::entitlement-requests number?)
(s/def ::used-requests number?)
(s/def ::remaining-percentage number?)
(s/def ::overage number?)
(s/def ::overage-allowed-with-exhausted-quota? boolean?)
(s/def ::reset-date string?)

(s/def ::quota-snapshot
  (s/keys :req-un [::entitlement-requests ::used-requests ::remaining-percentage
                   ::overage ::overage-allowed-with-exhausted-quota?]
          :opt-un [::reset-date]))

(s/def ::quota-snapshots
  (s/map-of string? ::quota-snapshot))

;; -----------------------------------------------------------------------------
;; Session model operations (session.model.getCurrent / switchTo)
;; -----------------------------------------------------------------------------

(s/def ::model string?)
(s/def ::model-id (s/nilable string?))
