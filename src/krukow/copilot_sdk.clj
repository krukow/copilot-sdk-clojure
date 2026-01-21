(ns krukow.copilot-sdk
  "Clojure SDK for programmatic control of GitHub Copilot CLI via JSON-RPC.

   Quick Start:
   ```clojure
   (require '[krukow.copilot-sdk :as copilot])

   ;; Create and start client
   (def client (copilot/client {:log-level :info}))
   (copilot/start! client)

   ;; Create a session
   (def session (copilot/create-session client {:model \"gpt-5.2\"}))

   ;; Send a message and wait for response
   (def response (copilot/send-and-wait! session {:prompt \"What is 2+2?\"}))
   (println (get-in response [:data :content]))

   ;; Clean up
   (copilot/destroy! session)
   (copilot/stop! client)
   ```"
  (:require [krukow.copilot-sdk.client :as client]
            [krukow.copilot-sdk.session :as session]
            [krukow.copilot-sdk.tools :as tools]))

;; =============================================================================
;; Client API
;; =============================================================================

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
   - :env           - Environment variables map

   Example:
   ```clojure
   (def client (copilot/client {:log-level :debug}))
   ```"
  ([]
   (client/client))
  ([opts]
   (client/client opts)))

(defn start!
  "Start the CLI server and establish connection.
   Blocks until connected or throws on error.

   Example:
   ```clojure
   (copilot/start! client)
   ```"
  [client]
  (client/start! client))

(defn stop!
  "Stop the CLI server and close all sessions.
   Returns a vector of any errors encountered during cleanup.

   Example:
   ```clojure
   (let [errors (copilot/stop! client)]
     (when (seq errors)
       (println \"Cleanup errors:\" errors)))
   ```"
  [client]
  (client/stop! client))

(defn force-stop!
  "Force stop the CLI server without graceful cleanup.
   Use when stop! takes too long."
  [client]
  (client/force-stop! client))

(defn state
  "Get the current connection state.
   Returns :disconnected | :connecting | :connected | :error"
  [client]
  (client/state client))

(defmacro with-client
  "Create a client, start it, and ensure stop! on exit.

   Usage:
   (with-client [c {:log-level :info}]
     ...)"
  [[client-sym & [opts]] & body]
  `(let [~client-sym ~(if opts `(client ~opts) `(client))]
     (start! ~client-sym)
     (try
       ~@body
       (finally
         (stop! ~client-sym)))))

(defn notifications
  "Get the channel that receives non-session notifications.
   Notifications are dropped if the channel is full."
  [client]
  (client/notifications client))

(defn ping
  "Ping the server to check connectivity.
   Returns {:message :timestamp :protocol-version}"
  ([client]
   (client/ping client))
  ([client message]
   (client/ping client message)))

;; =============================================================================
;; Session Management
;; =============================================================================

(defn create-session
  "Create a new conversation session.

   Config options:
   - :session-id           - Custom session ID
   - :model                - Model to use (e.g., \"gpt-5.2\", \"claude-sonnet-4.5\")
   - :tools                - Vector of tool definitions (use define-tool)
   - :system-message       - {:mode :append/:replace :content \"...\"}
   - :available-tools      - List of allowed tool names
   - :excluded-tools       - List of excluded tool names
   - :provider             - Custom provider config (BYOK)
   - :on-permission-request - Permission handler function
     Must return a map compatible with the permission result payload.
     The SDK wraps this into the JSON-RPC response as {:result <your-map>}:
     {:kind :approved}
     {:kind :denied-by-rules :rules [{:kind \"shell\" :argument \"echo hi\"}]}
     {:kind :denied-no-approval-rule-and-could-not-request-from-user}
     {:kind :denied-interactively-by-user :feedback \"optional\"}
   - :streaming?           - Enable streaming deltas
   - :mcp-servers          - MCP server configs map (keyed by server ID)
   - :custom-agents        - Custom agent configs
   - :config-dir           - Override config directory for CLI (configDir)
   - :skill-directories    - Additional skill directories to load
   - :disabled-skills      - Disable specific skills by name
   - :large-output         - Tool output handling config {:enabled :max-size-bytes :output-dir}

   Example:
   ```clojure
   (def session (copilot/create-session client {:model \"gpt-5.2\"}))
   ```"
  ([client]
   (client/create-session client))
  ([client config]
   (client/create-session client config)))

(defmacro with-session
  "Create a session and ensure destroy! on exit.

   Usage:
   (with-session [s client {:model \"gpt-5.2\"}]
     ...)"
  [[session-sym client & [config]] & body]
  `(let [~session-sym ~(if config
                         `(create-session ~client ~config)
                         `(create-session ~client))]
     (try
       ~@body
       (finally
         (destroy! ~session-sym)))))

(defmacro with-client-session
  "Create a client + session and ensure cleanup on exit.

   Usage:
   (with-client-session [s {:model \"gpt-5.2\"}]
     ...)

   (with-client-session [client s {:model \"gpt-5.2\"} {:cli-path \"copilot\"}]
     ...)"
  [[a b & more] & body]
  (if (map? b)
    `(with-client [client#]
       (with-session [~a client# ~b]
         ~@body))
    (let [client-sym a
          session-sym b
          session-opts (first more)
          client-opts (second more)]
      `(with-client [~client-sym ~@(when client-opts [client-opts])]
         (with-session [~session-sym ~client-sym ~session-opts]
           ~@body)))))

(defn resume-session
  "Resume an existing session by ID.

   Example:
   ```clojure
   (def session (copilot/resume-session client \"session-123\"))
   ```"
  ([client session-id]
   (client/resume-session client session-id))
  ([client session-id config]
   (client/resume-session client session-id config)))

(defn list-sessions
  "List all available sessions.
   Returns a vector of session metadata maps:
   {:session-id :start-time :modified-time :summary :remote?}"
  [client]
  (client/list-sessions client))

(defn delete-session!
  "Delete a session and its data from disk."
  [client session-id]
  (client/delete-session! client session-id))

(defn get-last-session-id
  "Get the ID of the most recently updated session."
  [client]
  (client/get-last-session-id client))

;; =============================================================================
;; Session Operations
;; =============================================================================

(defn send!
  "Send a message to the session (fire-and-forget).
   Returns the message ID immediately.

   Options:
   - :prompt       - The message text (required)
   - :attachments  - Vector of {:type :file/:directory :path \"...\" :display-name \"...\"}
   - :mode         - :enqueue (default) or :immediate

   Example:
   ```clojure
   (copilot/send! session {:prompt \"Hello!\"})
   ```"
  [session opts]
  (session/send! session opts))

(defn send-and-wait!
  "Send a message and wait until the session becomes idle.
   Returns the final assistant message event, or nil if none received.
   Serialized per session to avoid mixing concurrent sends.

   Options: same as send!, plus:
   - :timeout-ms   - Timeout in milliseconds (default: 180000)

   Example:
   ```clojure
   (let [response (copilot/send-and-wait! session {:prompt \"What is 2+2?\"})]
     (println (get-in response [:data :content])))
   ```"
  ([session opts]
   (session/send-and-wait! session opts))
  ([session opts timeout-ms]
   (session/send-and-wait! session opts timeout-ms)))

(defn send-async
  "Send a message and return a core.async channel that receives events.
   The channel closes after session.idle or session.error.
   Serialized per session to avoid mixing concurrent sends.

   Example:
   ```clojure
   (let [ch (copilot/send-async session {:prompt \"Tell me a story\"})]
     (go-loop []
       (when-let [event (<! ch)]
         (println (:type event))
         (recur))))
   ```"
  [session opts]
  (session/send-async session opts))

(defn send-async-with-id
  "Send a message and return {:message-id :events-ch}."
  [session opts]
  (session/send-async-with-id session opts))

(defn abort!
  "Abort the currently processing message in this session."
  [session]
  (session/abort! session))

(defn get-messages
  "Get all events/messages from this session's history."
  [session]
  (session/get-messages session))

(defn destroy!
  "Destroy the session and free resources."
  [session]
  (session/destroy! session))

(defn events
  "Get the event mult for this session. Use tap/untap to subscribe:
   
   Example:
   ```clojure
   (require '[clojure.core.async :refer [chan tap untap go-loop <!]])
   
   (let [ch (chan 100)]
     (tap (copilot/events session) ch)
     (go-loop []
       (when-let [event (<! ch)]
         (println \"Event:\" (:type event))
         (recur))))
   ```
   
   Remember to untap and close your channel when done."
  [session]
  (session/events session))

(defn subscribe-events
  "Subscribe to session events. Returns a channel that receives events.
   Call unsubscribe-events when done.
   
   This is a convenience wrapper around (tap (copilot/events session) ch).

   Example:
   ```clojure
   (let [ch (copilot/subscribe-events session)]
     (go-loop []
       (when-let [event (<! ch)]
         (println \"Event:\" (:type event))
         (recur))))
   ```"
  [session]
  (session/subscribe-events session))

(defn events->chan
  "Subscribe to session events with options.

   Options:
   - :buffer - Channel buffer size (default 1024)
   - :xf     - Transducer applied to events"
  ([session]
   (session/events->chan session))
  ([session opts]
   (session/events->chan session opts)))

(defn unsubscribe-events
  "Unsubscribe a channel from session events."
  [session ch]
  (session/unsubscribe-events session ch))

(defn session-id
  "Get the session ID."
  [session]
  (session/session-id session))

;; =============================================================================
;; Tool Helpers
;; =============================================================================

(defn define-tool
  "Define a tool with a handler function.

   Arguments:
   - name        - Tool name (string)
   - opts map:
     - :description - Tool description
     - :parameters  - JSON schema for parameters
     - :handler     - Function (fn [args invocation] -> result)

   The handler receives:
   - args       - The parsed arguments from the LLM
   - invocation - Map with :session-id, :tool-call-id, :tool-name, :arguments

   Returns: a tool definition map

   Example:
   ```clojure
   (def weather-tool
     (copilot/define-tool \"get_weather\"
       {:description \"Get weather for a location\"
        :parameters {:type \"object\"
                     :properties {:location {:type \"string\"}}
                     :required [\"location\"]}
        :handler (fn [args _]
                   (str \"Weather in \" (:location args) \": Sunny\"))}))

   (def session (copilot/create-session client {:tools [weather-tool]}))
   ```"
  [name opts]
  (tools/define-tool name opts))

;; Re-export result helpers
(def result-success tools/result-success)
(def result-failure tools/result-failure)
(def result-denied tools/result-denied)
(def result-rejected tools/result-rejected)
