(ns github.copilot-sdk.client
  "CopilotClient - manages connection to the Copilot CLI server."
  (:require [clojure.core.async :as async :refer [go go-loop <! >! >!! chan close!]]
            [clojure.spec.alpha :as s]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [github.copilot-sdk.protocol :as proto]
            [github.copilot-sdk.process :as proc]
            [github.copilot-sdk.specs :as specs]
            [github.copilot-sdk.session :as session]
            [github.copilot-sdk.util :as util]
            [github.copilot-sdk.logging :as log])
  (:import [java.net Socket]
           [java.util.concurrent LinkedBlockingQueue]))

(def ^:private sdk-protocol-version 2)

(defn- parse-cli-url
  "Parse CLI URL into {:host :port}."
  [url]
  (let [clean (str/replace url #"^https?://" "")]
    (if (re-matches #"\d+" clean)
      ;; Port only
      (let [port (parse-long clean)]
        (when (or (nil? port) (<= port 0) (> port 65535))
          (throw (ex-info "Invalid port in cli-url" {:url url :port port})))
        {:host "localhost" :port port})
      ;; host:port
      (let [[host port-str] (str/split clean #":" 2)]
        (when (or (str/blank? port-str) (not (re-matches #"-?\d+" port-str)))
          (throw (ex-info "Invalid cli-url format" {:url url})))
        (let [port (parse-long port-str)]
          (when (or (nil? port) (<= port 0) (> port 65535))
            (throw (ex-info "Invalid port in cli-url" {:url url :port port})))
          {:host (if (str/blank? host) "localhost" host)
           :port port})))))

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
   :auto-restart? true
   :notification-queue-size 4096
   :router-queue-size 4096
   :tool-timeout-ms 120000
   :env nil})

(defn- ensure-valid-mcp-servers!
  [servers]
  (when-not (s/valid? ::specs/mcp-servers servers)
    (throw (ex-info "Invalid :mcp-servers config (expected map keyed by server ID)."
                    {:spec ::specs/mcp-servers
                     :mcp-servers servers
                     :explain (s/explain-data ::specs/mcp-servers servers)}))))

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
;;  :force-stopping? false
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
   :actual-port port
   :router-ch nil
   :router-queue nil
   :router-thread nil
   :router-running? false
   :stopping? false
   :restarting? false
   :force-stopping? false
   :models-cache nil         ; nil, promise, or vector of models (cleared on stop)
   :lifecycle-handlers {}
   :stderr-buffer nil})      ; atom of recent stderr lines (for error context)

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
    - :auto-restart? - Auto-restart on crash (default: true)
    - :notification-queue-size - Max queued protocol notifications (default: 4096)
    - :router-queue-size - Max queued non-session notifications (default: 4096)
    - :tool-timeout-ms - Timeout for tool calls that return a channel (default: 120000)
    - :env           - Environment variables map
    - :github-token  - GitHub token for authentication (sets COPILOT_SDK_AUTH_TOKEN env var)
    - :use-logged-in-user? - Whether to use logged-in user auth (default: true, false when github-token provided)"
  ([]
   (client {}))
  ([opts]
   (when (and (:cli-url opts) (= true (:use-stdio? opts)))
     (throw (ex-info "cli-url is mutually exclusive with use-stdio?" opts)))
   (when (and (:cli-url opts) (:cli-path opts))
     (throw (ex-info "cli-url is mutually exclusive with cli-path" opts)))
   ;; Validation: github-token and use-logged-in-user? cannot be used with cli-url
   (when (and (:cli-url opts) (or (:github-token opts) (some? (:use-logged-in-user? opts))))
     (throw (ex-info "github-token and use-logged-in-user? cannot be used with cli-url (external server manages its own auth)"
                     {:cli-url (:cli-url opts)
                      :github-token (when (:github-token opts) "***")
                      :use-logged-in-user? (:use-logged-in-user? opts)})))
   (when-not (s/valid? ::specs/client-options opts)
     (let [unknown (specs/unknown-keys opts specs/client-options-keys)
           explain (s/explain-data ::specs/client-options opts)
           msg (if (seq unknown)
                 (format "Invalid client options: unknown keys %s. Valid keys are: %s"
                         (pr-str unknown)
                         (pr-str (sort specs/client-options-keys)))
                 (format "Invalid client options: %s"
                         (with-out-str (s/explain ::specs/client-options opts))))]
       (throw (ex-info msg {:options opts :unknown-keys unknown :explain explain}))))
   (when-let [size (:notification-queue-size opts)]
     (when (<= size 0)
       (throw (ex-info "notification-queue-size must be > 0" {:notification-queue-size size}))))
   (when-let [size (:router-queue-size opts)]
     (when (<= size 0)
       (throw (ex-info "router-queue-size must be > 0" {:router-queue-size size}))))
   (when-let [timeout (:tool-timeout-ms opts)]
     (when (<= timeout 0)
       (throw (ex-info "tool-timeout-ms must be > 0" {:tool-timeout-ms timeout}))))

   (let [;; Default use-logged-in-user? to false when github-token is provided, otherwise true
         opts-with-defaults (cond-> opts
                              (and (:github-token opts) (nil? (:use-logged-in-user? opts)))
                              (assoc :use-logged-in-user? false))
         merged (merge (default-options) opts-with-defaults)
         external? (boolean (:cli-url opts))
         {:keys [host port]} (when (:cli-url opts)
                               (parse-cli-url (:cli-url opts)))
         final-opts (cond-> merged
                      external? (-> (assoc :use-stdio? false)
                                    (assoc :host host)
                                    (assoc :port port)
                                    (assoc :external-server? true)))]
     {:options final-opts
      :external-server? external?
      :actual-host (or host "localhost")
      :state (atom (assoc (initial-state port) :options final-opts))})))

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
(declare maybe-reconnect!)

(defn- start-notification-router!
  "Route notifications to appropriate sessions."
  [client]
  (let [{:keys [connection-io]} @(:state client)
        notif-ch (proto/notifications connection-io)
        router-ch (chan 1024)
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
    ;; Store the router channel
    (swap! (:state client) assoc
           :router-ch router-ch
           :router-queue router-queue
           :router-thread router-thread
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
                  normalized-event (update event :type util/event-type->keyword)
                  event-type (:type normalized-event)]
              (log/debug "Routing event to session " session-id ": type=" event-type)
              ;; Validate model selection on session.start
              (when (= event-type :copilot/session.start)
                (let [selected-model (get-in event [:data :selectedModel])
                      requested-model (get-in @(:state client) [:sessions session-id :config :model])]
                  (when (and requested-model selected-model
                             (not= requested-model selected-model))
                    (log/warn "Model mismatch for session " session-id
                              ": requested " requested-model ", server selected " selected-model))))
              (when-not (:destroyed? (get-in @(:state client) [:sessions session-id]))
                (when-let [{:keys [event-chan]} (get-in @(:state client) [:session-io session-id])]
                  (>! event-chan normalized-event))))

            "session.lifecycle"
            (let [params (util/wire->clj (:params notif))
                  event-type-str (:type params)
                  event-type-kw (when event-type-str (keyword event-type-str))
                  lifecycle-event (-> params
                                      (dissoc :type)
                                      (assoc :lifecycle-event-type event-type-kw))
                  handlers (vals (:lifecycle-handlers @(:state client)))]
              (log/debug "Lifecycle event: " event-type-kw " session=" (:session-id lifecycle-event))
              (doseq [{:keys [handler event-type]} handlers]
                (when (or (nil? event-type) (= event-type event-type-kw))
                  (try
                    (handler lifecycle-event)
                    (catch Exception e
                      (log/error "Lifecycle handler error: " (ex-message e)))))))

            ;; default: other notifications go to the router queue
            (when-not (.offer router-queue notif)
              (log/debug "Dropping notification due to full router queue")))
          (recur))
        (do
          (log/debug "Notification channel closed")
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

(defn- mark-restarting!
  "Atomically mark the client as restarting. Returns true if this caller won."
  [client]
  (let [state-atom (:state client)]
    (loop []
      (let [state @state-atom]
        (if (or (:stopping? state) (:restarting? state))
          false
          (if (compare-and-set! state-atom state (assoc state :restarting? true))
            true
            (recur)))))))

(defn- maybe-reconnect!
  "Attempt a stop/start cycle when auto-restart is enabled."
  [client reason]
  (let [state @(:state client)]
    (when (and (:auto-restart? (:options client))
               (= :connected (:status state))
               (not (:stopping? state)))
      (when (mark-restarting! client)
        (log/warn "Auto-restart triggered:" reason)
        (async/thread
          (try
            (stop! client)
            (start! client)
            (catch Exception e
              (log/error "Auto-restart failed: " (ex-message e)))
            (finally
              (swap! (:state client) assoc :restarting? false))))))))

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

(defn- setup-request-handler!
  "Set up handler for incoming requests (tool calls, permission requests, hooks, user input)."
  [client]
  (let [{:keys [connection-io]} @(:state client)]
    (proto/set-request-handler! connection-io
                                (fn [method params]
                                  (go
                                    (case method
                                      "tool.call"
                                      (let [{:keys [session-id tool-call-id tool-name arguments]} params]
                                        (if-not (get-in @(:state client) [:sessions session-id])
                                          {:error {:code -32001 :message (str "Unknown session: " session-id)}}
                                          {:result (<! (session/handle-tool-call! client session-id tool-call-id tool-name arguments))}))

                                      "permission.request"
                                      (let [{:keys [session-id permission-request]} params]
                                        (if-not (get-in @(:state client) [:sessions session-id])
                                          {:result {:kind :denied-no-approval-rule-and-could-not-request-from-user}}
                                          (let [result (<! (session/handle-permission-request! client session-id permission-request))]
                                            (log/debug "Permission response for session " session-id ": " result)
                                            {:result result})))

                                      ;; User input request (PR #269)
                                      "userInput.request"
                                      (let [{:keys [session-id question choices allow-freeform]} params]
                                        (log/debug "User input request for session " session-id ": " question)
                                        (if-not (get-in @(:state client) [:sessions session-id])
                                          {:error {:code -32001 :message (str "Unknown session: " session-id)}}
                                          (<! (session/handle-user-input-request! client session-id
                                                                                  {:question question
                                                                                   :choices choices
                                                                                   :allow-freeform allow-freeform}))))

                                      ;; Hooks invocation (PR #269)
                                      "hooks.invoke"
                                      (let [{:keys [session-id hook-type input]} params]
                                        (if-not (get-in @(:state client) [:sessions session-id])
                                          {:result nil}
                                          (<! (session/handle-hooks-invoke! client session-id hook-type input))))

                                      {:error {:code -32601 :message (str "Unknown method: " method)}}))))))

(defn- connect-stdio!
  "Connect via stdio to the CLI process."
  [client]
  (let [{:keys [process]} @(:state client)]
    ;; Initialize connection state before connecting
    (swap! (:state client) assoc :connection (proto/initial-connection-state))
    (let [conn (proto/connect (:stdout process) (:stdin process) (:state client))]
      (swap! (:state client) assoc :connection-io conn))))

(defn- connect-tcp!
  "Connect via TCP to the CLI server."
  [client]
  (let [host (:actual-host client)
        {:keys [actual-port]} @(:state client)
        socket (proc/connect-tcp host actual-port 10000)]
    ;; Initialize connection state before connecting
    (swap! (:state client) assoc :connection (proto/initial-connection-state))
    (let [conn (proto/connect (.getInputStream socket) (.getOutputStream socket) (:state client))]
      (swap! (:state client) assoc :socket socket :connection-io conn))))

(defn- verify-protocol-version!
  "Verify the server's protocol version matches ours.
   Races the ping against process exit to detect early CLI failures."
  [client]
  (let [{:keys [connection-io process]} @(:state client)
        ping-ch (proto/send-request connection-io "ping" {})
        exit-ch (:exit-chan process)
        timeout-ch (async/timeout 60000)
        ;; Race: wait for ping result or process exit (whichever comes first)
        result (if exit-ch
                 (let [exit-poll (async/poll! exit-ch)]
                   (if exit-poll
                     ;; Process already exited before we even sent the ping
                     (let [stderr (get-stderr-output client)]
                       (throw (ex-info
                               (cond-> (str "CLI server exited with code " (:exit-code exit-poll))
                                 stderr (str "\nstderr: " stderr))
                               {:exit-code (:exit-code exit-poll) :stderr stderr})))
                     ;; Process still running — race ping against exit and timeout
                     (let [[v ch] (async/alts!! [ping-ch exit-ch timeout-ch])]
                       (cond
                         ;; Timeout
                         (identical? ch timeout-ch)
                         (let [stderr (get-stderr-output client)]
                           (throw (ex-info
                                   (cond-> "Ping request timed out"
                                     stderr (str "\nstderr: " stderr))
                                   {:method "ping" :timeout-ms 60000 :stderr stderr})))

                         ;; Ping completed
                         (identical? ch ping-ch)
                         (cond
                           (nil? v)
                           (throw (ex-info "Ping channel closed unexpectedly" {:method "ping"}))

                           (:error v)
                           (throw (ex-info (get-in v [:error :message] "RPC error")
                                           {:error (:error v) :method "ping"}))

                           :else (:result v))

                         ;; Process exited first
                         :else
                         (let [stderr (get-stderr-output client)]
                           (throw (ex-info
                                   (cond-> (str "CLI server exited with code " (:exit-code v))
                                     stderr (str "\nstderr: " stderr))
                                   {:exit-code (:exit-code v) :stderr stderr})))))))
                 ;; No process (external server) — just wait for ping with timeout
                 (let [[v ch] (async/alts!! [ping-ch timeout-ch])]
                   (cond
                     (identical? ch timeout-ch)
                     (throw (ex-info "Ping request timed out"
                                     {:method "ping" :timeout-ms 60000}))
                     (nil? v)
                     (throw (ex-info "Ping channel closed" {:method "ping"}))
                     (:error v)
                     (throw (ex-info (get-in v [:error :message] "RPC error")
                                     {:error (:error v) :method "ping"}))
                     :else (:result v))))
        server-version (:protocol-version result)]
    (when (nil? server-version)
      (throw (ex-info
              (str "SDK protocol version mismatch: SDK expects version "
                   sdk-protocol-version ", but server does not report a protocol version.")
              {:expected sdk-protocol-version :actual nil})))
    (when (not= server-version sdk-protocol-version)
      (throw (ex-info
              (str "SDK protocol version mismatch: SDK expects version "
                   sdk-protocol-version ", but server reports version " server-version)
              {:expected sdk-protocol-version :actual server-version})))))

;; ---------------------------------------------------------------------------
;; Permission helpers
;; ---------------------------------------------------------------------------

(defn approve-all
  "Permission handler that approves all permission requests.

  The SDK uses a **deny-by-default** permission model: all permission requests
  (file writes, shell commands, URL fetches, etc.) are denied unless your
  session config provides an `:on-permission-request` handler.

  Use `approve-all` to opt into approving everything (equivalent to the
  upstream `approveAll` export):

    (copilot/create-session client {:on-permission-request copilot/approve-all})

  For fine-grained control, provide your own handler function instead."
  [_request _ctx]
  {:kind :approved})

(defn start!
  "Start the CLI server and establish connection.
   Blocks until connected or throws on error.

   Thread safety: do not call start! and stop! concurrently from different
   threads. The :stopping? and :restarting? flags guard against concurrent
   auto-restart, but explicit concurrent calls are unsupported."
  [client]
  (when-not (= :connected (:status @(:state client)))
    (log/info "Starting Copilot client...")
    (swap! (:state client) assoc :stopping? false :status :connecting)

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
      (if (or (:external-server? client)
              (not (:use-stdio? (:options client))))
        (do
          (log/debug "Connecting via TCP")
          (connect-tcp! client))
        (do
          (log/debug "Connecting via stdio")
          (connect-stdio! client)))

      ;; Verify protocol version
      (verify-protocol-version! client)

      ;; Set up notification routing and request handling
      (start-notification-router! client)
      (setup-request-handler! client)

      (swap! (:state client) assoc :status :connected)
      (log/info "Copilot client connected")
      nil

      (catch Exception e
        (let [stderr (get-stderr-output client)
              msg (cond-> (str "Failed to start client: " (ex-message e))
                    stderr (str "\nstderr: " stderr))]
          (log/error msg)
          (swap! (:state client) assoc :status :error)
          (throw e))))))

(defn stop!
  "Stop the CLI server and close all sessions.
   Returns a vector of any errors encountered during cleanup.

   Thread safety: do not call start! and stop! concurrently from different
   threads. Auto-restart is suppressed via the :stopping? flag, but explicit
   concurrent calls are unsupported."
  [client]
  (log/info "Stopping Copilot client...")
  (swap! (:state client) assoc :stopping? true)
  (let [errors (atom [])
        {:keys [sessions session-io process connection-io socket]} @(:state client)]
    (try
      ;; 0. Stop notification routing
      (swap! (:state client) assoc :router-running? false)
      (when-let [^Thread router-thread (:router-thread @(:state client))]
        (.interrupt router-thread)
        (try (.join router-thread 500) (catch Exception _)))
      (when-let [router-ch (:router-ch @(:state client))]
        (close! router-ch))
      (swap! (:state client) assoc :router-ch nil :router-queue nil :router-thread nil)

      ;; 1. Destroy all sessions
      (doseq [[session-id _] sessions]
        (try
          (session/destroy! client session-id)
          (catch Exception e
            (swap! errors conj
                   (ex-info (str "Failed to destroy session " session-id)
                            {:session-id session-id} e)))))
      (swap! (:state client) assoc :sessions {} :session-io {})

      ;; 2. Close connection (non-blocking, may leave read thread blocked for stdio)
      (when connection-io
        (try
          (proto/disconnect connection-io)
          (catch Exception e
            (swap! errors conj
                   (ex-info "Failed to close connection" {} e))))
        (swap! (:state client) assoc :connection nil :connection-io nil))

      ;; 3. Close socket (TCP mode)
      (when socket
        (try
          (.close ^Socket socket)
          (catch Exception e
            (swap! errors conj
                   (ex-info "Failed to close socket" {} e))))
        (swap! (:state client) assoc :socket nil))

      ;; 4. Kill CLI process (this also unblocks any stdio read thread)
      (when (and (not (:external-server? client)) process)
        (try
          (proc/destroy! process)
          (catch Exception e
            (swap! errors conj
                   (ex-info "Failed to kill CLI process" {} e))))
        (swap! (:state client) assoc :process nil))

      (swap! (:state client) assoc :status :disconnected :actual-port nil
             :models-cache nil :lifecycle-handlers {}
             :stderr-buffer nil)  ; reset caches, handlers, and stderr

      (log/info "Copilot client stopped")
      @errors)))

(defn force-stop!
  "Force stop the CLI server without graceful cleanup."
  [client]
  (swap! (:state client) assoc :force-stopping? true :stopping? true)

  (let [{:keys [connection-io socket process]} @(:state client)]
    (try
      ;; Clear sessions without destroying
      (swap! (:state client) assoc :sessions {} :session-io {})

      ;; Stop notification routing
      (swap! (:state client) assoc :router-running? false)
      (when-let [^Thread router-thread (:router-thread @(:state client))]
        (.interrupt router-thread)
        (try (.join router-thread 500) (catch Exception _)))
      (when-let [router-ch (:router-ch @(:state client))]
        (close! router-ch))
      (swap! (:state client) assoc :router-ch nil :router-queue nil :router-thread nil)

      ;; Force close connection
      (when connection-io
        (try (proto/disconnect connection-io) (catch Exception _)))

      ;; Force close socket
      (when socket
        (try (.close ^Socket socket) (catch Exception _)))

      ;; Force kill process
      (when (and (not (:external-server? client)) process)
        (try (proc/destroy-forcibly! process) (catch Exception _)))
      (finally
        nil)))

  (swap! (:state client) merge
         {:status :disconnected
          :connection nil
          :connection-io nil
          :socket nil
          :process nil
          :actual-port nil
          :router-ch nil
          :router-queue nil
          :router-thread nil
          :router-running? false
          :force-stopping? false
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
   Returns {:message :timestamp :protocol-version}."
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
                  (assoc :model-supports
                         (cond-> {}
                           (contains? (:supports c) :vision)
                           (assoc :supports-vision (:vision (:supports c)))
                           (contains? (:supports c) :reasoning-effort)
                           (assoc :supports-reasoning-effort (:reasoning-effort (:supports c)))))
                  (:limits c)
                  (assoc :model-limits
                         (cond-> {}
                           (:max-prompt-tokens (:limits c))
                           (assoc :max-prompt-tokens (:max-prompt-tokens (:limits c)))
                           (:max-context-window-tokens (:limits c))
                           (assoc :max-context-window-tokens (:max-context-window-tokens (:limits c)))
                           (:vision (:limits c))
                           (assoc :vision-capabilities
                                  (select-keys (:vision (:limits c))
                                               [:supported-media-types :max-prompt-images :max-prompt-image-size])))))})
        optional (merge
                  (select-keys m [:default-temperature
                                  :model-picker-priority
                                  :default-reasoning-effort])
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
   Requires authentication.
   Returns a vector of model info maps with keys:
   :id :name :vendor :family :version :max-input-tokens :max-output-tokens
   :preview? :default-temperature :model-picker-priority
   :model-capabilities {:model-supports {:supports-vision :supports-reasoning-effort}
                        :model-limits {:max-prompt-tokens :max-context-window-tokens
                                       :vision-capabilities {:supported-media-types
                                                             :max-prompt-images
                                                             :max-prompt-image-size}}}
   :model-policy {:policy-state :terms}
   :model-billing {:multiplier}
   :supported-reasoning-efforts :default-reasoning-effort
   :supports-reasoning-effort (legacy flat key)
   :vision-limits {:supported-media-types :max-prompt-images :max-prompt-image-size} (legacy)"
  [client]
  (ensure-connected! client)
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
        (let [models (fetch-models! client)]
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
          result)))))

(defn list-tools
  "List available tools with their metadata.
   Optional :model param returns model-specific tool overrides.
   Returns a vector of tool info maps with keys:
   :name :namespaced-name :description :parameters :instructions"
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

(defn get-quota
  "Get account quota information.
   Returns a map of quota type (string) to quota snapshot maps:
   {:entitlement-requests :used-requests :remaining-percentage
    :overage :overage-allowed-with-exhausted-quota? :reset-date}"
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

(defn- validate-session-config!
  "Validate session config, throwing on invalid input."
  [config]
  (when-not (s/valid? ::specs/session-config config)
    (let [unknown (specs/unknown-keys config specs/session-config-keys)
          explain (s/explain-data ::specs/session-config config)
          msg (if (seq unknown)
                (format "Invalid session config: unknown keys %s. Valid keys are: %s"
                        (pr-str unknown)
                        (pr-str (sort specs/session-config-keys)))
                (format "Invalid session config: %s"
                        (with-out-str (s/explain ::specs/session-config config))))]
      (throw (ex-info msg {:config config :unknown-keys unknown :explain explain}))))
  (when (and (:provider config) (not (:model config)))
    (throw (ex-info "Invalid session config: :model is required when :provider (BYOK) is specified"
                    {:config config}))))

(defn- build-create-session-params
  "Build wire params for session.create from config."
  [config]
  (when-let [servers (:mcp-servers config)]
    (ensure-valid-mcp-servers! servers))
  (let [wire-tools (when (:tools config)
                     (mapv (fn [t]
                             {:name (:tool-name t)
                              :description (:tool-description t)
                              :parameters (:tool-parameters t)})
                           (:tools config)))
        wire-sys-msg (when-let [sm (:system-message config)]
                       (cond
                         (= :replace (:mode sm))
                         {:mode "replace" :content (:content sm)}
                         :else
                         {:mode "append" :content (:content sm)}))
        wire-provider (when-let [provider (:provider config)]
                        (util/clj->wire provider))
        wire-mcp-servers (when-let [servers (:mcp-servers config)]
                           (util/mcp-servers->wire servers))
        wire-custom-agents (when-let [agents (:custom-agents config)]
                             (mapv util/clj->wire agents))
        wire-infinite-sessions (when-let [is (:infinite-sessions config)]
                                 (util/clj->wire is))]
    (cond-> {}
      (:session-id config) (assoc :session-id (:session-id config))
      (:client-name config) (assoc :client-name (:client-name config))
      (:model config) (assoc :model (:model config))
      wire-tools (assoc :tools wire-tools)
      wire-sys-msg (assoc :system-message wire-sys-msg)
      (:available-tools config) (assoc :available-tools (:available-tools config))
      (:excluded-tools config) (assoc :excluded-tools (:excluded-tools config))
      wire-provider (assoc :provider wire-provider)
      true (assoc :request-permission true)
      (:streaming? config) (assoc :streaming (:streaming? config))
      wire-mcp-servers (assoc :mcp-servers wire-mcp-servers)
      wire-custom-agents (assoc :custom-agents wire-custom-agents)
      (:config-dir config) (assoc :config-dir (:config-dir config))
      (:skill-directories config) (assoc :skill-directories (:skill-directories config))
      (:disabled-skills config) (assoc :disabled-skills (:disabled-skills config))
      (:large-output config) (assoc :large-output (:large-output config))
      (:working-directory config) (assoc :working-directory (:working-directory config))
      wire-infinite-sessions (assoc :infinite-sessions wire-infinite-sessions)
      (:reasoning-effort config) (assoc :reasoning-effort (:reasoning-effort config))
      true (assoc :request-user-input (boolean (:on-user-input-request config)))
      true (assoc :hooks (boolean (:hooks config)))
      true (assoc :env-value-mode "direct"))))

(defn- build-resume-session-params
  "Build wire params for session.resume from session-id and config."
  [session-id config]
  (when-let [servers (:mcp-servers config)]
    (ensure-valid-mcp-servers! servers))
  (let [wire-tools (when (:tools config)
                     (mapv (fn [t]
                             {:name (:tool-name t)
                              :description (:tool-description t)
                              :parameters (:tool-parameters t)})
                           (:tools config)))
        wire-sys-msg (when-let [sm (:system-message config)]
                       (cond
                         (= :replace (:mode sm))
                         {:mode "replace" :content (:content sm)}
                         :else
                         {:mode "append" :content (:content sm)}))
        wire-provider (when-let [provider (:provider config)]
                        (util/clj->wire provider))
        wire-mcp-servers (when-let [servers (:mcp-servers config)]
                           (util/mcp-servers->wire servers))
        wire-custom-agents (when-let [agents (:custom-agents config)]
                             (mapv util/clj->wire agents))
        wire-infinite-sessions (when-let [is (:infinite-sessions config)]
                                 (util/clj->wire is))]
    (cond-> {:session-id session-id}
      (:client-name config) (assoc :client-name (:client-name config))
      (:model config) (assoc :model (:model config))
      wire-tools (assoc :tools wire-tools)
      wire-sys-msg (assoc :system-message wire-sys-msg)
      (:available-tools config) (assoc :available-tools (:available-tools config))
      (:excluded-tools config) (assoc :excluded-tools (:excluded-tools config))
      wire-provider (assoc :provider wire-provider)
      true (assoc :request-permission true)
      (:streaming? config) (assoc :streaming (:streaming? config))
      wire-mcp-servers (assoc :mcp-servers wire-mcp-servers)
      wire-custom-agents (assoc :custom-agents wire-custom-agents)
      (:config-dir config) (assoc :config-dir (:config-dir config))
      (:skill-directories config) (assoc :skill-directories (:skill-directories config))
      (:disabled-skills config) (assoc :disabled-skills (:disabled-skills config))
      wire-infinite-sessions (assoc :infinite-sessions wire-infinite-sessions)
      (:reasoning-effort config) (assoc :reasoning-effort (:reasoning-effort config))
      true (assoc :request-user-input (boolean (:on-user-input-request config)))
      true (assoc :hooks (boolean (:hooks config)))
      (:working-directory config) (assoc :working-directory (:working-directory config))
      (:disable-resume? config) (assoc :disable-resume (:disable-resume? config))
      true (assoc :env-value-mode "direct"))))

(defn- finalize-session
  "Create a CopilotSession from an RPC result and config."
  [client result config]
  (let [session-id (:session-id result)
        workspace-path (:workspace-path result)]
    (session/create-session client session-id
                            {:tools (:tools config)
                             :on-permission-request (:on-permission-request config)
                             :on-user-input-request (:on-user-input-request config)
                             :hooks (:hooks config)
                             :workspace-path workspace-path
                             :config config})))

(defn create-session
  "Create a new conversation session.
   
   Config options:
   - :session-id         - Custom session ID
   - :client-name        - Client name to identify the application (included in User-Agent header)
   - :model              - Model to use (e.g., \"gpt-5.2\")
   - :tools              - Vector of tool definitions
   - :system-message     - System message config
   - :available-tools    - List of allowed tool names
   - :excluded-tools     - List of excluded tool names
   - :provider           - Custom provider config (BYOK)
   - :on-permission-request - Permission handler function
   - :streaming?         - Enable streaming
   - :mcp-servers        - MCP server configs map
   - :custom-agents      - Custom agent configs
   - :config-dir         - Override config directory for CLI (configDir)
   - :skill-directories  - Additional skill directories to load
   - :disabled-skills    - Disable specific skills by name
   - :large-output       - (Experimental) Tool output handling config {:enabled :max-size-bytes :output-dir}
                           Note: CLI protocol feature, not in official SDK. outputDir may be ignored.
   - :working-directory  - Working directory for the session (tool operations relative to this)
   - :infinite-sessions  - Infinite session config for automatic context compaction
                           {:enabled (default true)
                            :background-compaction-threshold (0.0-1.0, default 0.80)
                            :buffer-exhaustion-threshold (0.0-1.0, default 0.95)}
   - :reasoning-effort   - Reasoning effort level: \"low\", \"medium\", \"high\", or \"xhigh\" (PR #302)
   - :on-user-input-request - Handler for ask_user requests (PR #269)
   - :hooks              - Lifecycle hooks map (PR #269):
                           {:on-pre-tool-use, :on-post-tool-use, :on-user-prompt-submitted,
                            :on-session-start, :on-session-end, :on-error-occurred}
   
   Returns a CopilotSession."
  ([client]
   (create-session client {}))
  ([client config]
   (log/debug "Creating session with config: " (select-keys config [:model :session-id]))
   (validate-session-config! config)
   (ensure-connected! client)
   (let [{:keys [connection-io]} @(:state client)
         params (build-create-session-params config)
         result (proto/send-request! connection-io "session.create" params)
         session (finalize-session client result config)]
     (log/info "Session created: " (:session-id result))
     session)))

(defn resume-session
  "Resume an existing session by ID.
   
   Config options (parity with create-session, upstream PR #376):
   - :client-name        - Client name to identify the application (included in User-Agent header)
   - :model              - Change the model for the resumed session
   - :tools              - Tools exposed to the CLI server
   - :system-message     - System message configuration {:mode :content}
   - :available-tools    - List of tool names to allow
   - :excluded-tools     - List of tool names to disable
   - :provider           - Custom provider configuration (BYOK)
   - :streaming?         - Enable streaming responses
   - :on-permission-request - Permission handler
   - :mcp-servers        - MCP server configurations
   - :custom-agents      - Custom agent configurations
   - :config-dir         - Override configuration directory
   - :skill-directories  - Directories to load skills from
   - :disabled-skills    - Skills to disable
   - :infinite-sessions  - Infinite session configuration
   - :reasoning-effort   - Reasoning effort level: \"low\", \"medium\", \"high\", or \"xhigh\"
   - :on-user-input-request - Handler for ask_user requests
   - :hooks              - Lifecycle hooks map
   
   Returns a CopilotSession."
  ([client session-id]
   (resume-session client session-id {}))
  ([client session-id config]
   (when-not (s/valid? ::specs/resume-session-config config)
     (throw (ex-info "Invalid resume session config"
                     {:config config
                      :explain (s/explain-data ::specs/resume-session-config config)})))
   (when (and (:provider config) (not (:model config)))
     (throw (ex-info "Invalid session config: :model is required when :provider (BYOK) is specified"
                     {:config config})))
   (ensure-connected! client)
   (let [{:keys [connection-io]} @(:state client)
         params (build-resume-session-params session-id config)
         result (proto/send-request! connection-io "session.resume" params)
         session (finalize-session client result config)]
     session)))

(defn <create-session
  "Async version of create-session. Returns a channel that delivers a CopilotSession.

   Same config options as create-session. Validation is performed synchronously
   (throws immediately on invalid config). The RPC call parks instead of blocking,
   making this safe to use inside go blocks.

   On RPC error, delivers an ExceptionInfo to the channel (not nil).
   Callers should check the result with (instance? Throwable result).

   Usage:
     (go
       (let [result (<! (<create-session client {:model \"gpt-5.2\"}))]
         (if (instance? Throwable result)
           (println \"Error:\" (ex-message result))
           ;; use result as session
           )))"
  ([client]
   (<create-session client {}))
  ([client config]
   (log/debug "Creating session (async) with config: " (select-keys config [:model :session-id]))
   (validate-session-config! config)
   (ensure-connected! client)
   (let [{:keys [connection-io]} @(:state client)
         params (build-create-session-params config)
         rpc-ch (proto/send-request connection-io "session.create" params)]
     (go
       (when-let [response (<! rpc-ch)]
         (if-let [err (:error response)]
           (do (log/error "<create-session RPC error: " err)
               (ex-info (str "Failed to create session: " (:message err))
                        {:error err}))
           (let [result (:result response)
                 session (finalize-session client result config)]
             (log/info "Session created (async): " (:session-id result))
             session)))))))

(defn <resume-session
  "Async version of resume-session. Returns a channel that delivers a CopilotSession.

   Same config options as resume-session. Validation is performed synchronously
   (throws immediately on invalid config). The RPC call parks instead of blocking,
   making this safe to use inside go blocks.

   On RPC error, delivers an ExceptionInfo to the channel (not nil).
   Callers should check the result with (instance? Throwable result).

   Usage:
     (go
       (let [result (<! (<resume-session client session-id {:model \"gpt-5.2\"}))]
         (if (instance? Throwable result)
           (println \"Error:\" (ex-message result))
           ;; use result as session
           )))"
  ([client session-id]
   (<resume-session client session-id {}))
  ([client session-id config]
   (when-not (s/valid? ::specs/resume-session-config config)
     (throw (ex-info "Invalid resume session config"
                     {:config config
                      :explain (s/explain-data ::specs/resume-session-config config)})))
   (when (and (:provider config) (not (:model config)))
     (throw (ex-info "Invalid session config: :model is required when :provider (BYOK) is specified"
                     {:config config})))
   (ensure-connected! client)
   (let [{:keys [connection-io]} @(:state client)
         params (build-resume-session-params session-id config)
         rpc-ch (proto/send-request connection-io "session.resume" params)]
     (go
       (when-let [response (<! rpc-ch)]
         (if-let [err (:error response)]
           (do (log/error "<resume-session RPC error: " err)
               (ex-info (str "Failed to resume session: " (:message err))
                        {:error err :session-id session-id}))
           (let [result (:result response)
                 session (finalize-session client result config)]
             session)))))))

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
     (mapv (fn [s]
             (let [ctx (:context s)]
               (cond-> {:session-id (:session-id s)
                        :start-time (java.time.Instant/parse (:start-time s))
                        :modified-time (java.time.Instant/parse (:modified-time s))
                        :summary (:summary s)
                        :remote? (:is-remote s)}
                 ctx (assoc :context (cond-> {:cwd (:cwd ctx)}
                                      (:git-root ctx) (assoc :git-root (:git-root ctx))
                                      (:repository ctx) (assoc :repository (:repository ctx))
                                      (:branch ctx) (assoc :branch (:branch ctx)))))))
           sessions))))

(defn delete-session!
  "Delete a session and its data from disk."
  [client session-id]
  (ensure-connected! client)
  (let [{:keys [connection-io]} @(:state client)
        result (proto/send-request! connection-io "session.delete" {:session-id session-id})]
    (when-not (:success result)
      (throw (ex-info (str "Failed to delete session: " (:error result))
                      {:session-id session-id :error (:error result)})))
    ;; Remove from local sessions and IO
    (swap! (:state client) (fn [s]
                             (-> s
                                 (update :sessions dissoc session-id)
                                 (update :session-io dissoc session-id))))
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
  (when-not (= :connected (:status @(:state client)))
    (swap! (:state client) assoc :status :connecting)
    (try
      ;; Initialize connection state before connecting
      (swap! (:state client) assoc :connection (proto/initial-connection-state))
      (let [conn (proto/connect in out (:state client))]
        (swap! (:state client) assoc :connection-io conn))
      (verify-protocol-version! client)
      (start-notification-router! client)
      (setup-request-handler! client)
      (swap! (:state client) assoc :status :connected)
      nil
      (catch Exception e
        (swap! (:state client) assoc :status :error)
        (throw e)))))
