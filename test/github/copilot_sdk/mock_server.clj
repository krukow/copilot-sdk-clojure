(ns github.copilot-sdk.mock-server
  "Mock JSON-RPC server for integration testing.
   Simulates the Copilot CLI server behavior."
  (:require [clojure.data.json :as json]
            [clojure.core.async :as async :refer [go go-loop <! >! chan close! put!]]
            [clojure.set :as set]
            [github.copilot-sdk :as sdk])
  (:import [java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter
            PipedInputStream PipedOutputStream]
           [java.util.concurrent.atomic AtomicLong]))

(def ^:private DEFAULT_PROTOCOL_VERSION 3)

(defn- write-message
  "Write a JSON-RPC message with Content-Length framing."
  [^BufferedWriter writer msg]
  (let [json-str (json/write-str msg)
        content-length (count (.getBytes json-str "UTF-8"))]
    (locking writer
      (.write writer (str "Content-Length: " content-length "\r\n\r\n"))
      (.write writer json-str)
      (.flush writer))))

(defn- read-headers
  "Read headers until empty line. Returns nil on EOF/closed."
  [^BufferedReader reader]
  (try
    (loop [headers {}]
      (let [line (.readLine reader)]
        (cond
          (nil? line) nil
          (clojure.string/blank? line) headers
          :else
          (let [[k v] (clojure.string/split line #": " 2)]
            (recur (assoc headers (clojure.string/lower-case k) v))))))
    (catch java.io.IOException _ nil)))

(defn- read-content
  "Read exactly n bytes."
  [^BufferedReader reader n]
  (let [buf (char-array n)
        read-count (.read reader buf 0 n)]
    (when (= read-count n)
      (String. buf))))

(defn- read-message
  "Read a JSON-RPC message."
  [^BufferedReader reader]
  (when-let [headers (read-headers reader)]
    (when-let [content-length (some-> (get headers "content-length") parse-long)]
      (when-let [content (read-content reader content-length)]
        (json/read-str content :key-fn keyword)))))

(defrecord MockServer
           [;; Pipes for communication
            client-in          ; PipedInputStream - client reads from this
            client-out         ; PipedOutputStream - client writes to this  
            server-in          ; PipedInputStream - server reads from this (connected to client-out)
            server-out         ; PipedOutputStream - server writes to this (connected to client-in)
     ;; Server I/O
            reader             ; BufferedReader for server
            writer             ; BufferedWriter for server
     ;; State
            running?           ; atom boolean
            sessions           ; atom {session-id -> session-state}
            message-id         ; AtomicLong for generating IDs
     ;; Hooks for testing
            on-request         ; atom fn - called for each request
            expected-token     ; atom string-or-nil - when set, `connect` validates token
            supports-connect?  ; atom boolean - when false, `connect` returns -32601 (legacy fallback)
            pending-events     ; atom - events to send on next opportunity
            pending-responses  ; atom {id -> chan} - responses to server→client RPCs
            protocol-version   ; atom Long - protocol version reported by ping/connect/status
            resume-response-extras ; atom map - extra fields to merge into session.resume responses
            current-tool-metadata-response]) ; atom map-or-Throwable

(defn- generate-id [^AtomicLong counter]
  (str "evt-" (.incrementAndGet counter)))

(defn- make-event [server type data & {:keys [ephemeral?]}]
  {:id (generate-id (:message-id server))
   :timestamp (.toString (java.time.Instant/now))
   :parentId nil
   :ephemeral (boolean ephemeral?)
   :type type
   :data data})

(defn- send-notification [server method params]
  (write-message (:writer server)
                 {:jsonrpc "2.0"
                  :method method
                  :params params}))

(defn- send-session-event [server session-id event]
  (send-notification server "session.event"
                     {:sessionId session-id
                      :event event}))

(defn- handle-ping [server params]
  {:message (:message params)
   :timestamp (.toString (java.time.Instant/now))
   :protocolVersion (or (some-> server :protocol-version deref) DEFAULT_PROTOCOL_VERSION)})

(defn- handle-connect
  "Handle the `connect` handshake (upstream PR #1176). When :expected-token is
   set on the server, validate the supplied token; otherwise accept any token.
   Returns {:ok true :protocolVersion N :version \"...\"}."
  [server params]
  (let [expected (some-> server :expected-token deref)
        provided (:token params)]
    (when (and expected (not= expected provided))
      (throw (ex-info "Invalid connection token"
                      {:code -32603 :method "connect"})))
    {:ok true
     :protocolVersion (or (some-> server :protocol-version deref) DEFAULT_PROTOCOL_VERSION)
     :version "0.0.389-mock"}))

(defn- handle-status-get [server params]
  {:version "0.0.389-mock"
   :protocolVersion (or (some-> server :protocol-version deref) DEFAULT_PROTOCOL_VERSION)})

(defn- handle-auth-get-status [server params]
  {:isAuthenticated true
   :authType "user"
   :host "github.com"
   :login "test-user"
   :statusMessage "Authenticated as test-user"})

(defn- handle-models-list [server params]
  {:models [{:id "gpt-5.4"
             :name "GPT-5.4"
             :vendor "openai"
             :family "gpt-5.4"
             :version "gpt-5.4"
             :max_input_tokens 128000
             :max_output_tokens 16384
             :preview false}
            {:id "claude-sonnet-4.5"
             :name "Claude Sonnet 4.5"
             :vendor "anthropic"
             :family "claude-sonnet"
             :version "claude-sonnet-4.5"
             :max_input_tokens 200000
             :max_output_tokens 8192
             :preview false
             :vision_limits {:supported_media_types ["image/png" "image/jpeg" "image/gif" "image/webp"]
                             :max_prompt_images 20
                             :max_prompt_image_size 20971520}}]})

(defn- handle-session-create [server params]
  (let [session-id (or (:sessionId params) (str "session-" (generate-id (:message-id server))))
        session-state {:id session-id
                       :model (:model params)
                       :created-at (java.time.Instant/now)}]
    (swap! (:sessions server) assoc session-id session-state)
    ;; Send session.start event
    (send-session-event server session-id
                        (make-event server "session.start"
                                    {:sessionId session-id
                                     :version 1
                                     :producer "mock-server"
                                     :copilotVersion "mock-1.0.0"
                                     :startTime (.toString (java.time.Instant/now))
                                     :selectedModel (:model params)}))
    {:sessionId session-id}))

(defn- handle-session-resume [server params]
  (let [session-id (:sessionId params)]
    (if (get @(:sessions server) session-id)
      (do
        (send-session-event server session-id
                            (make-event server "session.resume"
                                        {:resumeTime (.toString (java.time.Instant/now))
                                         :eventCount 0}))
        (merge @(:resume-response-extras server)
               {:sessionId session-id}))
      (throw (ex-info "Session not found" {:code -32001 :session-id session-id})))))

(defn- handle-session-send [server params]
  (let [session-id (:sessionId params)
        prompt (:prompt params)
        message-id (generate-id (:message-id server))]
    (if-not (get @(:sessions server) session-id)
      (throw (ex-info "Session not found" {:code -32001 :session-id session-id}))
      (do
        ;; Send user.message event
        (send-session-event server session-id
                            (make-event server "user.message"
                                        {:content prompt}))
        ;; Send assistant.turn_start
        (let [turn-id (generate-id (:message-id server))]
          (send-session-event server session-id
                              (make-event server "assistant.turn_start"
                                          {:turnId turn-id}))
          ;; Simulate assistant response
          (send-session-event server session-id
                              (make-event server "assistant.message"
                                          {:messageId message-id
                                           :content (str "Mock response to: " prompt)}))
          ;; Send turn end
          (send-session-event server session-id
                              (make-event server "assistant.turn_end"
                                          {:turnId turn-id}))
          ;; Send idle
          (send-session-event server session-id
                              (make-event server "session.idle" {} :ephemeral? true)))
        {:messageId message-id}))))

(defn- handle-session-destroy [server params]
  (let [session-id (:sessionId params)]
    (swap! (:sessions server) dissoc session-id)
    {:success true}))

(defn- handle-session-detach [_server _params]
  {:success true})

(defn- handle-session-abort [server params]
  (let [session-id (:sessionId params)]
    {:success true}))

(defn- handle-session-get-messages [server params]
  (let [session-id (:sessionId params)
        session-state (get @(:sessions server) session-id)]
    {:events (or (:messages session-state) [])}))

(defn- handle-session-list [server params]
  (let [filter-opts (:filter params)
        sessions (mapv (fn [[id state]]
                         (cond-> {:sessionId id
                                  :startTime (.toString (:created-at state))
                                  :modifiedTime (.toString (java.time.Instant/now))
                                  :isRemote false}
                           (:context state) (assoc :context (:context state))))
                       @(:sessions server))
        filtered (if filter-opts
                   (filterv (fn [s]
                              (let [ctx (:context s)]
                                (and (or (nil? (:cwd filter-opts)) (= (:cwd filter-opts) (:cwd ctx)))
                                     (or (nil? (:gitRoot filter-opts)) (= (:gitRoot filter-opts) (:gitRoot ctx)))
                                     (or (nil? (:repository filter-opts)) (= (:repository filter-opts) (:repository ctx)))
                                     (or (nil? (:branch filter-opts)) (= (:branch filter-opts) (:branch ctx))))))
                            sessions)
                   sessions)]
    {:sessions filtered}))

(defn- handle-session-delete [server params]
  (let [session-id (:sessionId params)]
    (swap! (:sessions server) dissoc session-id)
    {:success true}))

(defn- handle-session-get-metadata [server params]
  (let [session-id (:sessionId params)
        session-state (get @(:sessions server) session-id)]
    (if session-state
      {:session (cond-> {:sessionId session-id
                         :startTime (.toString (:created-at session-state))
                         :modifiedTime (.toString (java.time.Instant/now))
                         :isRemote false}
                  (:context session-state) (assoc :context (:context session-state)))}
      {:session nil})))

(defn- handle-session-get-last-id [server params]
  (let [sessions @(:sessions server)]
    (if (empty? sessions)
      {:sessionId nil}
      {:sessionId (first (keys sessions))})))

(defn- handle-tools-list [server params]
  {:tools [{:name "bash"
            :description "Run a shell command"
            :parameters {:type "object"
                         :properties {:command {:type "string"}}
                         :required ["command"]}}
           {:name "grep"
            :namespacedName "builtin/grep"
            :description "Search files for patterns"
            :parameters {:type "object"
                         :properties {:pattern {:type "string"}}
                         :required ["pattern"]}
            :instructions "Use for searching file contents"}]})

(defn- handle-account-get-quota [server params]
  {:quotaSnapshots {:chat {:entitlementRequests 1000
                           :usedRequests 42
                           :remainingPercentage 95.8
                           :overage 0
                           :overageAllowedWithExhaustedQuota false
                           :resetDate "2026-03-01T00:00:00Z"}
                    :premium_interactions {:entitlementRequests 500
                                           :usedRequests 10
                                           :remainingPercentage 98.0
                                           :overage 0
                                           :overageAllowedWithExhaustedQuota true}}})

(defn- handle-session-model-get-current [server params]
  (let [session-id (:sessionId params)
        session-state (get @(:sessions server) session-id)]
    (if session-state
      {:modelId (:model session-state)}
      (throw (ex-info "Session not found" {:code -32001 :session-id session-id})))))

(defn- handle-session-model-switch-to [server params]
  (let [session-id (:sessionId params)
        model-id (:modelId params)]
    (if (get @(:sessions server) session-id)
      (do
        (swap! (:sessions server) assoc-in [session-id :model] model-id)
        {:modelId model-id})
      (throw (ex-info "Session not found" {:code -32001 :session-id session-id})))))

(defn- handle-session-log [server params]
  (let [session-id (:sessionId params)]
    (if (get @(:sessions server) session-id)
      {:eventId (str (java.util.UUID/randomUUID))}
      (throw (ex-info "Session not found" {:code -32001 :session-id session-id})))))

(defn- handle-request [server msg]
  (let [method (:method msg)
        params (:params msg)
        ;; Call hook if set — hooks can optionally return a map with ::merge-response
        ;; whose value will be merged into the handler result (see merge logic below).
        hook-result (when-let [hook @(:on-request server)]
                      (hook method params))
        result (case method
                 "ping" (handle-ping server params)
                 "connect" (case @(:supports-connect? server)
                             true (handle-connect server params)
                             false (throw (ex-info "Method not found" {:code -32601 :method method}))
                             :legacy-message (throw (ex-info "Unhandled method connect"
                                                             {:code -32603 :method method})))
                 "status.get" (handle-status-get server params)
                 "auth.getStatus" (handle-auth-get-status server params)
                 "models.list" (handle-models-list server params)
                 "session.create" (handle-session-create server params)
                 "session.resume" (handle-session-resume server params)
                 "session.mcp.reloadWithConfig" {}
                 "session.send" (handle-session-send server params)
                 "session.detach" (handle-session-detach server params)
                 "session.destroy" (handle-session-destroy server params)
                 "session.abort" (handle-session-abort server params)
                 "session.getMessages" (handle-session-get-messages server params)
                 "session.list" (handle-session-list server params)
                 "session.getMetadata" (handle-session-get-metadata server params)
                 "session.delete" (handle-session-delete server params)
                 "session.getLastId" (handle-session-get-last-id server params)
                 "tools.list" (handle-tools-list server params)
                 "account.getQuota" (handle-account-get-quota server params)
                 "session.model.getCurrent" (handle-session-model-get-current server params)
                 "session.model.switchTo" (handle-session-model-switch-to server params)
                 "session.log" (handle-session-log server params)
                 "session.permissions.handlePendingPermissionRequest" {:ok true}
                 "session.mcp.oauth.handlePendingRequest" {:ok true}
                 "session.eventLog.registerInterest" {:handle (str (java.util.UUID/randomUUID))}
                 "session.eventLog.releaseInterest" {:ok true}
                 "session.commands.handlePendingCommand" {:ok true}
                 "session.commands.respondToQueuedCommand" {:success true}
                 "session.tools.handlePendingToolCall" {:ok true}
                 "session.tools.getCurrentMetadata"
                 (let [response @(:current-tool-metadata-response server)]
                   (if (instance? Throwable response)
                     (throw response)
                     response))
                 "session.ui.handlePendingElicitation" {:ok true}
                 "session.mode.get" {:mode "interactive"}
                 "session.mode.set" {:mode (get params :mode "interactive")}
                 "session.plan.read" {:exists false :content nil :filePath nil}
                 "session.plan.update" {:success true}
                 "session.plan.delete" {:success true}
                 "session.workspace.listFiles" {:files []}
                 "session.workspace.readFile" {:content ""}
                 "session.workspace.createFile" {:success true}
                 "session.agent.list" {:agents []}
                 "session.agent.getCurrent" {:name nil}
                 "session.agent.select" {:success true}
                 "session.agent.deselect" {:success true}
                 "session.agent.reload" {:success true}
                 "session.fleet.start" {:success true}
                 "session.skills.list" {:skills []}
                 "session.skills.enable" {:success true}
                 "session.skills.disable" {:success true}
                 "session.skills.reload" {:success true}
                 "session.mcp.list" {:servers []}
                 "session.mcp.enable" {:success true}
                 "session.mcp.disable" {:success true}
                 "session.mcp.reload" {:success true}
                 "session.extensions.list" {:extensions []}
                 "session.extensions.enable" {:success true}
                 "session.extensions.disable" {:success true}
                 "session.extensions.reload" {:success true}
                 "session.plugins.list" {:plugins []}
                 "session.options.update" {:ok true}
                 "session.compaction.compact" {:success true}
                 "session.history.compact" {:success true}
                 "session.history.truncate" {:success true}
                 "session.history.clearContext" {:messagesCleared 3}
                 "session.factory.run" {:runId "run-1"
                                        :name (or (:name params) "factory")
                                        :status "completed"
                                        :result {:snake_key 1}
                                        :snapshot {:snapshot_key true}}
                 "session.factory.resume" {:run {:runId (:runId params)
                                                 :name "factory"
                                                 :status "completed"
                                                 :result {:resumed_key true}
                                                 :snapshot {:resume_snapshot_key true}}}
                 "session.factory.getRun" {:runId (:runId params)
                                           :name "factory"
                                           :status "completed"
                                           :result {:durable_key true}
                                           :snapshot {:get_snapshot_key true}}
                 "session.factory.listRuns" {:runs [{:runId "run-1"
                                                     :name "factory"
                                                     :status "completed"}]}
                 "session.factory.getRunDetail" {:runId (:runId params)
                                                 :phases []
                                                 :agents []
                                                 :progress {:lines []}}
                 "session.factory.getRunProgress" {:lines [] :hasMore false}
                 "session.factory.cancel" {:runId (:runId params)
                                           :name "factory"
                                           :status "cancelled"
                                           :snapshot {:cancel_snapshot_key true}}
                 "session.factory.log" {}
                 "session.factory.agent" {:result {:agent_key "ok"}}
                 "session.factory.journal.get" {:hit false}
                 "session.factory.journal.put" {}
                 "sessions.fork" {:sessionId (str (java.util.UUID/randomUUID))}
                 "session.shell.exec" {:exitCode 0 :stdout "" :stderr ""}
                 "session.shell.kill" {:success true}
                 "session.ui.elicitation" {:action "accept" :content {}}
                 "mcp.config.list" {:servers []}
                 "mcp.config.add" {:success true}
                 "mcp.config.update" {:success true}
                 "mcp.config.remove" {:success true}
                 "session.name.get" {:name nil}
                 "session.name.set" {:success true}
                 "session.workspaces.getWorkspace" {:workspace nil}
                 "mcp.discover" {:servers []}
                 "session.usage.getMetrics" {}
                 "session.remote.enable" {:url "https://copilot-remote.test/abc" :remoteSteerable true}
                 "session.remote.disable" {}
                 "sessionFs.setProvider" {:ok true}
                 "runtime.shutdown" {}
                 (throw (ex-info "Method not found" {:code -32601 :method method})))
        ;; Merge hook-provided data into result only when hook returns ::merge-response
        ;; This prevents accidental response mutation from spy hooks (e.g. swap! return values)
        result (let [extra (when (map? hook-result) (::merge-response hook-result))]
                 (cond
                   (nil? extra) result
                   (map? extra) (merge result extra)
                   :else (throw (ex-info "::merge-response value must be a map"
                                         {:code -32603 :method method :extra-value extra}))))]
    {:jsonrpc "2.0"
     :id (:id msg)
     :result result}))

(defn- server-loop [server]
  (try
    (while @(:running? server)
      (if-let [msg (read-message (:reader server))]
        (if (:method msg)
          (if (contains? msg :id)
            ;; It's a request (has :method and :id) — handle it and respond
            (try
              (let [response (handle-request server msg)]
                (write-message (:writer server) response))
              (catch Exception e
                (let [error-data (ex-data e)]
                  (write-message (:writer server)
                                 {:jsonrpc "2.0"
                                  :id (:id msg)
                                  :error (cond-> {:code (or (:code error-data) -32603)
                                                  :message (.getMessage e)}
                                           (contains? error-data :data)
                                           (assoc :data (:data error-data)))}))))
            ;; It's a notification (has :method but no :id) — process silently
            (try
              (handle-request server msg)
              (catch Exception _ nil)))
          ;; It's a response to a server→client RPC — deliver to pending promise
          (when-let [id (:id msg)]
            (when-let [response-ch (get @(:pending-responses server) id)]
              (put! response-ch msg)
              (swap! (:pending-responses server) dissoc id))))
        ;; EOF or closed - exit loop
        (reset! (:running? server) false)))
    (catch Exception e
      (when @(:running? server)
        (println "Mock server error:" (.getMessage e))))))

(defn create-mock-server
  "Create a mock server with piped streams for testing.
   Returns a MockServer record."
  []
  (let [;; Create two pairs of piped streams
        ;; Pair 1: server writes -> client reads
        client-in (PipedInputStream. 65536)
        server-out (PipedOutputStream. client-in)
        ;; Pair 2: client writes -> server reads
        server-in (PipedInputStream. 65536)
        client-out (PipedOutputStream. server-in)]
    (map->MockServer
     {:client-in client-in
      :client-out client-out
      :server-in server-in
      :server-out server-out
      :reader (BufferedReader. (InputStreamReader. server-in "UTF-8"))
      :writer (BufferedWriter. (OutputStreamWriter. server-out "UTF-8"))
      :running? (atom false)
      :sessions (atom {})
      :message-id (AtomicLong. 0)
      :on-request (atom nil)
      :expected-token (atom nil)
      :supports-connect? (atom true)
      :pending-events (atom [])
      :pending-responses (atom {})
      :protocol-version (atom DEFAULT_PROTOCOL_VERSION)
      :resume-response-extras (atom {})
      :current-tool-metadata-response (atom {:tools []})})))

(defn start-mock-server!
  "Start the mock server in a background thread."
  [server]
  (reset! (:running? server) true)
  (let [thread (Thread. #(server-loop server) "mock-server")]
    (.setDaemon thread true)
    (.start thread)
    server))

(defn stop-mock-server!
  "Stop the mock server."
  [server]
  (reset! (:running? server) false)
  ;; Close pipes in order that unblocks the reader first
  (try (.close ^PipedOutputStream (:client-out server)) (catch Exception _))
  (try (.close ^PipedOutputStream (:server-out server)) (catch Exception _))
  (try (.close ^PipedInputStream (:client-in server)) (catch Exception _))
  (try (.close ^PipedInputStream (:server-in server)) (catch Exception _))
  server)

(defn client-streams
  "Get the streams that a client should use to connect to this mock server.
   Returns [input-stream output-stream]."
  [server]
  [(:client-in server) (:client-out server)])

(defn set-request-hook!
  "Set a hook function that's called for each request.
   Hook receives (method params)."
  [server hook-fn]
  (reset! (:on-request server) hook-fn))

(defn set-resume-response-extras!
  "Set extra wire-shape (camelCase) fields the mock server merges into
   `session.resume` RPC responses. Lets tests inject fields like
   `:openCanvases` without changing the default success path. Pass `{}`
   to clear."
  [server extras]
  (reset! (:resume-response-extras server) (or extras {})))

(defn set-current-tool-metadata-response!
  "Set the response or Throwable for `session.tools.getCurrentMetadata`."
  [server response]
  (reset! (:current-tool-metadata-response server) response))

(defn inject-tool-call!
  "Inject a tool call request from the mock server.
   This simulates the CLI requesting a tool invocation."
  [server session-id tool-name arguments]
  (let [tool-call-id (generate-id (:message-id server))]
    ;; Send tool execution start event
    (send-session-event server session-id
                        (make-event server "tool.execution_start"
                                    {:toolCallId tool-call-id
                                     :toolName tool-name
                                     :arguments arguments}))
    ;; Send the actual tool call request
    (write-message (:writer server)
                   {:jsonrpc "2.0"
                    :id (generate-id (:message-id server))
                    :method "tool.call"
                    :params {:sessionId session-id
                             :toolCallId tool-call-id
                             :toolName tool-name
                             :arguments arguments}})
    tool-call-id))

(defn send-notification!
  "Send a generic notification to the client."
  [server method params]
  (write-message (:writer server)
                 {:jsonrpc "2.0"
                  :method method
                  :params params}))

;; -----------------------------------------------------------------------------
;; Event-type registries
;; -----------------------------------------------------------------------------
;;
;; Mock-side validation of injected event types catches typos that would
;; otherwise produce silently-ignored notifications (e.g. "session.startt"
;; → no observable session.start event → confusing test failure).
;;
;; Two complementary registries:
;;
;; * `known-event-types`  — derived from the SDK's public `event-types` set
;;   (`github.copilot-sdk/event-types`). This is the canonical
;;   human-curated list of every event the SDK recognises and includes
;;   protocol-level events that are not in the upstream session-events
;;   schema (e.g. `permission.requested`, `mcp.oauth_required`,
;;   `commands.changed`).
;;
;; * `v3-broadcast-event-types` — small hand-curated subset of the v3
;;   broadcast events that act as RPC replacements (the SDK intercepts
;;   them in `client/handle-v3-broadcast-event!`). Tests that inject these
;;   should prefer `send-v3-broadcast-event!` to make the intent explicit.
;;   Kept in sync with the dispatch table in `client.clj` by hand; a
;;   sanity invariant below enforces it remains a subset of
;;   `known-event-types`.

(def ^:private v3-broadcast-event-types
  "Protocol v3 broadcast events the SDK treats as RPC replacements. Keep in
   sync with the case dispatch in
   `github.copilot-sdk.client/handle-v3-broadcast-event!`."
  #{"external_tool.requested"
    "external_tool.completed"
    "permission.requested"
    "command.execute"
    "elicitation.requested"
    "mcp.oauth_required"
    "capabilities.changed"})

(def ^:private known-event-types
  "All event types the mock server will emit. Sourced from the SDK's
   public `event-types` registry — the canonical human-curated list of
   every event the SDK recognises."
  (into #{} (map name) sdk/event-types))

;; Sanity invariant — fails at namespace load time if our hand-curated
;; v3 set drifts away from the canonical SDK registry. Uses an explicit
;; throw rather than `assert` so the check runs regardless of `*assert*`.
;; We do NOT assert `event-specs/event-types ⊆ known-event-types`
;; because schema/SDK drift is a known pre-existing issue tracked
;; separately by the codegen pipeline (e.g. `session.import_legacy` is
;; in the upstream schema but not yet exposed by the SDK).
(when-not (set/subset? v3-broadcast-event-types known-event-types)
  (let [missing-types (set/difference v3-broadcast-event-types known-event-types)]
    (throw (ex-info (str "v3-broadcast-event-types must be a subset of known-event-types; "
                         "missing: " missing-types)
                    {:v3-broadcast-event-types v3-broadcast-event-types
                     :known-event-types known-event-types
                     :missing missing-types}))))

(defn- validate-event-type! [event-type valid-set context]
  (let [type-str (name event-type)]
    (when-not (contains? valid-set type-str)
      (throw (ex-info (str "Unknown event type for " context ": " (pr-str type-str))
                      {:event-type type-str
                       :context context
                       :valid-count (count valid-set)})))
    type-str))

(defn send-session-event!
  "Send a session event to the client.

   Arguments:
   - `event-type`: event type keyword/string recognised by the SDK;
     its `name` must match a known SDK event type string (i.e. the
     unqualified name from `github.copilot-sdk/event-types`)
   - `event-data`: payload map for the event
   - optional `:ephemeral?`: when truthy, marks the event as ephemeral

   Throws if `event-type` is unknown — fails fast on typos instead of
   silently dropping the event on the client side. For protocol v3
   broadcast events, prefer `send-v3-broadcast-event!` to make the
   intent explicit.

   For negative tests that need to inject deliberately-unknown event
   types, use `send-notification!` to bypass validation."
  [server session-id event-type event-data & {:keys [ephemeral?]}]
  (let [type-str (validate-event-type! event-type known-event-types "send-session-event!")]
    (send-session-event server session-id
                        (make-event server type-str event-data :ephemeral? ephemeral?))))

(defn send-v3-broadcast-event!
  "Send a protocol v3 broadcast event (an RPC-replacement event the SDK
   intercepts in `client/handle-v3-broadcast-event!`).

   Like `send-session-event!` but restricts `event-type` to the v3
   broadcast set — passing a regular schema event here surfaces the
   intent mismatch immediately. Use this for v3-specific tests; use
   `send-session-event!` for non-v3 events."
  [server session-id event-type event-data & {:keys [ephemeral?]}]
  (let [type-str (validate-event-type! event-type v3-broadcast-event-types "send-v3-broadcast-event!")]
    (send-session-event server session-id
                        (make-event server type-str event-data :ephemeral? ephemeral?))))

(defn inject-permission-request!
  "Inject a permission request from the mock server.
   Simulates the CLI asking for permission to execute a tool.
   Returns a channel that delivers the permission response."
  [server session-id permission-request]
  (let [request-id (generate-id (:message-id server))
        response-ch (chan 1)]
    ;; We need to read the response after sending the request.
    ;; Use the on-request hook to capture the response.
    (write-message (:writer server)
                   {:jsonrpc "2.0"
                    :id request-id
                    :method "permission.request"
                    :params {:sessionId session-id
                             :permissionRequest permission-request}})
    ;; Return request-id so caller can match the response
    request-id))

(defn set-session-context!
  "Set context on a mock session (for testing list-sessions with context)."
  [server session-id context]
  (swap! (:sessions server) assoc-in [session-id :context] context))

(defn set-session-messages!
  "Seed historical events on a mock session for testing session.getMessages.
   Events should be in wire shape (camelCase keyword keys like
   :sessionId/:startTime, and `:type` as a string event identifier).
   The mock serializes them with `clojure.data.json/write-str` which turns
   camelCase keyword keys into camelCase JSON strings on the wire."
  [server session-id events]
  (swap! (:sessions server) assoc-in [session-id :messages] events))

(defn send-rpc-request!
  "Send a JSON-RPC request to the client and wait for the response.
   Simulates server→client RPCs like hooks.invoke, userInput.request,
   systemMessage.transform. Blocks until the client responds or timeout.
   Must NOT be called from the mock server's server-loop thread.
   Returns the full JSON-RPC response map on success.
   Throws ex-info on timeout or when the client responds with :error."
  [server method params & {:keys [timeout-ms] :or {timeout-ms 5000}}]
  (let [id (generate-id (:message-id server))
        response-ch (chan 1)]
    (swap! (:pending-responses server) assoc id response-ch)
    (write-message (:writer server)
                   {:jsonrpc "2.0"
                    :id id
                    :method method
                    :params params})
    (let [[result _] (async/alts!! [response-ch (async/timeout timeout-ms)])]
      (swap! (:pending-responses server) dissoc id)
      (close! response-ch)
      (if (nil? result)
        (throw (ex-info "Timed out waiting for RPC response"
                        {:method method :timeout-ms timeout-ms}))
        (if (:error result)
          (throw (ex-info (get-in result [:error :message] "RPC error")
                          {:code (get-in result [:error :code])
                           :data (get-in result [:error :data])}))
          result)))))
