(ns github.copilot-sdk
  "Clojure SDK for programmatic control of GitHub Copilot CLI via JSON-RPC.

   Quick Start:
   ```clojure
   (require '[github.copilot-sdk :as copilot])

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
  (:require [github.copilot-sdk.client :as client]
            [github.copilot-sdk.session :as session]
            [github.copilot-sdk.tools :as tools]))

;; =============================================================================
;; Event Types
;; =============================================================================

(def event-types
  "All valid session event types as namespaced keywords."
  #{:copilot/session.start
    :copilot/session.resume
    :copilot/session.error
    :copilot/session.idle
    :copilot/session.info
    :copilot/session.model_change
    :copilot/session.handoff
    :copilot/session.truncation
    :copilot/session.snapshot_rewind
    :copilot/session.usage_info
    :copilot/session.compaction_start
    :copilot/session.compaction_complete
    :copilot/session.shutdown
    :copilot/session.context_changed
    :copilot/session.title_changed
    :copilot/session.warning
    :copilot/user.message
    :copilot/pending_messages.modified
    :copilot/assistant.turn_start
    :copilot/assistant.intent
    :copilot/assistant.reasoning
    :copilot/assistant.reasoning_delta
    :copilot/assistant.message
    :copilot/assistant.message_delta
    :copilot/assistant.turn_end
    :copilot/assistant.usage
    :copilot/abort
    :copilot/tool.user_requested
    :copilot/tool.execution_start
    :copilot/tool.execution_partial_result
    :copilot/tool.execution_progress
    :copilot/tool.execution_complete
    :copilot/subagent.started
    :copilot/subagent.completed
    :copilot/subagent.failed
    :copilot/subagent.selected
    :copilot/skill.invoked
    :copilot/hook.start
    :copilot/hook.end
    :copilot/system.message})

(def session-events
  "Session lifecycle and state management events."
  #{:copilot/session.start
    :copilot/session.resume
    :copilot/session.error
    :copilot/session.idle
    :copilot/session.info
    :copilot/session.model_change
    :copilot/session.handoff
    :copilot/session.truncation
    :copilot/session.snapshot_rewind
    :copilot/session.usage_info
    :copilot/session.compaction_start
    :copilot/session.compaction_complete
    :copilot/session.shutdown
    :copilot/session.context_changed
    :copilot/session.title_changed
    :copilot/session.warning})

(def assistant-events
  "Assistant response events."
  #{:copilot/assistant.turn_start
    :copilot/assistant.intent
    :copilot/assistant.reasoning
    :copilot/assistant.reasoning_delta
    :copilot/assistant.message
    :copilot/assistant.message_delta
    :copilot/assistant.turn_end
    :copilot/assistant.usage})

(def tool-events
  "Tool execution events."
  #{:copilot/tool.user_requested
    :copilot/tool.execution_start
    :copilot/tool.execution_partial_result
    :copilot/tool.execution_progress
    :copilot/tool.execution_complete})

(defn evt
  "Convert an unqualified event keyword to a namespace-qualified event keyword.
   
   Example:
   ```clojure
   (evt :session.info)      ;=> :copilot/session.info
   (evt :assistant.message) ;=> :copilot/assistant.message
   ```
   
   Throws IllegalArgumentException if the keyword is not a valid event type."
  [k]
  (let [qualified (keyword "copilot" (name k))]
    (if (event-types qualified)
      qualified
      (throw (IllegalArgumentException.
              (str "Unknown event type: " k ". Valid events: "
                   (pr-str (sort (map #(keyword (name %)) event-types)))))))))

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

(defn client-options
  "Get the options used to create this client.
   
   Returns the user-provided options merged with defaults. This reflects
   what the SDK is configured to use, not necessarily server state.

   Example:
   ```clojure
   (copilot/client-options client)
   ;=> {:log-level :info, :use-stdio? true, ...}
   ```"
  [client]
  (client/options client))

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

(defn on-lifecycle-event
  "Subscribe to session lifecycle events.

   Two arities:
   (on-lifecycle-event client handler)
     Subscribe to ALL lifecycle events. Handler receives an event map
     with keys :lifecycle-event-type, :session-id, and optionally :metadata.

   (on-lifecycle-event client event-type handler)
     Subscribe to a specific event type only.
     event-type is one of: :session.created :session.deleted :session.updated
                           :session.foreground :session.background

   Returns an unsubscribe function (call with no args to remove the handler).

   Example:
   ```clojure
   ;; All events
   (def unsub (copilot/on-lifecycle-event client
                (fn [event]
                  (println (:lifecycle-event-type event) (:session-id event)))))

   ;; Specific event type
   (def unsub (copilot/on-lifecycle-event client :session.created
                (fn [event]
                  (println \"New session:\" (:session-id event)))))

   ;; Unsubscribe
   (unsub)
   ```"
  ([client handler]
   (client/on-lifecycle-event client handler))
  ([client event-type handler]
   (client/on-lifecycle-event client event-type handler)))

(defn ping
  "Ping the server to check connectivity.
   Returns {:message :timestamp :protocol-version}"
  ([client]
   (client/ping client))
  ([client message]
   (client/ping client message)))

(defn get-status
  "Get CLI status including version and protocol information.
   Returns {:version :protocol-version}.

   Example:
   ```clojure
   (let [{:keys [version protocol-version]} (copilot/get-status client)]
     (println \"CLI version:\" version \"protocol:\" protocol-version))
   ```"
  [client]
  (client/get-status client))

(defn get-auth-status
  "Get current authentication status.
   Returns {:authenticated? :auth-type :host :login :status-message}.

   Example:
   ```clojure
   (let [{:keys [authenticated? login]} (copilot/get-auth-status client)]
     (if authenticated?
       (println \"Logged in as\" login)
       (println \"Not authenticated\")))
   ```"
  [client]
  (client/get-auth-status client))

(defn list-models
  "List available models with their metadata.
   Requires authentication.
   
   Returns a vector of model info maps with keys:
   :id :name :vendor :family :version :max-input-tokens :max-output-tokens
   :preview? :default-temperature :model-picker-priority :model-policy
   :vision-limits {:supported-media-types :max-prompt-images :max-prompt-image-size}

   Example:
   ```clojure
   (doseq [model (copilot/list-models client)]
     (println (:name model) \"-\" (:id model)))
   ```"
  [client]
  (client/list-models client))

(defn list-tools
  "List available tools with their metadata.
   Optional model param returns model-specific tool overrides.

   Returns a vector of tool info maps with keys:
   :name :namespaced-name :description :parameters :instructions

   Example:
   ```clojure
   (doseq [tool (copilot/list-tools client)]
     (println (:name tool) \"-\" (:description tool)))
   ```"
  ([client]
   (client/list-tools client))
  ([client model]
   (client/list-tools client model)))

(defn get-quota
  "Get account quota information.
   Returns a map of quota type (string) to quota snapshot maps:
   {:entitlement-requests :used-requests :remaining-percentage
    :overage :overage-allowed-with-exhausted-quota? :reset-date}

   Example:
   ```clojure
   (let [quotas (copilot/get-quota client)]
     (doseq [[type snapshot] quotas]
       (println type \":\" (:remaining-percentage snapshot) \"% remaining\")))
   ```"
  [client]
  (client/get-quota client))

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
   - :large-output         - (Experimental) Tool output handling config {:enabled :max-size-bytes :output-dir}
                             Note: CLI protocol feature, not in official SDK. outputDir may be ignored.
   - :working-directory    - Working directory for the session (tool operations relative to this)

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
   Automatically calls destroy! on session and stop! on client.

   Four forms are supported:

   1. [session session-opts] - anonymous client with default options
      ```clojure
      (with-client-session [session {:model \"gpt-5.2\"}]
        (copilot/send! session {:prompt \"Hi\"}))
      ```

   2. [client-opts session session-opts] - anonymous client with custom options
      ```clojure
      (with-client-session [{:log-level :debug} session {:model \"gpt-5.2\"}]
        (copilot/send! session {:prompt \"Hi\"}))
      ```

   3. [client session session-opts] - named client with default options
      ```clojure
      (with-client-session [client session {:model \"gpt-5.2\"}]
        (println (copilot/client-options client))
        (copilot/send! session {:prompt \"Hi\"}))
      ```

   4. [client client-opts session session-opts] - named client with custom options
      ```clojure
      (with-client-session [client {:log-level :debug} session {:model \"gpt-5.2\"}]
        (println (copilot/client-options client))
        (copilot/send! session {:prompt \"Hi\"}))
      ```"
  [[a b & more] & body]
  (cond
    ;; Form 1: [session session-opts] - 2 args, second is map
    (and (nil? more) (map? b))
    `(with-client [client#]
       (with-session [~a client# ~b]
         ~@body))

    ;; Form 2: [client-opts session session-opts] - 3 args, first is map
    (and (= 1 (count more)) (map? a))
    (let [client-opts a
          session-sym b
          session-opts (first more)]
      `(with-client [client# ~client-opts]
         (with-session [~session-sym client# ~session-opts]
           ~@body)))

    ;; Form 3: [client session session-opts] - 3 args, first two are symbols
    (and (= 1 (count more)) (symbol? a) (symbol? b))
    (let [client-sym a
          session-sym b
          session-opts (first more)]
      `(with-client [~client-sym]
         (with-session [~session-sym ~client-sym ~session-opts]
           ~@body)))

    ;; Form 4: [client client-opts session session-opts] - 4 args
    (= 2 (count more))
    (let [client-sym a
          client-opts b
          session-sym (first more)
          session-opts (second more)]
      `(with-client [~client-sym ~client-opts]
         (with-session [~session-sym ~client-sym ~session-opts]
           ~@body)))

    :else
    (throw (IllegalArgumentException.
            "Invalid with-client-session form. Expected one of:
             [session session-opts]
             [client-opts session session-opts]
             [client session session-opts]
             [client client-opts session session-opts]"))))

(defn resume-session
  "Resume an existing session by ID.
   Accepts the same config options as `create-session` (except `:session-id`),
   plus:
   - :disable-resume?  - When true, skip emitting the session.resume event (default: false)

   Example:
   ```clojure
   (def session (copilot/resume-session client \"session-123\"))
   ;; Resume with different model
   (def session (copilot/resume-session client \"session-123\"
                  {:model \"claude-sonnet-4\"
                   :reasoning-effort \"high\"}))
   ```"
  ([client session-id]
   (client/resume-session client session-id))
  ([client session-id config]
   (client/resume-session client session-id config)))

(defn list-sessions
  "List all available sessions.
   Returns a vector of session metadata maps:
   {:session-id :start-time :modified-time :summary :remote? :context}

   Optional filter map narrows results by context fields:
   {:cwd :git-root :repository :branch}

   Example:
   ```clojure
   ;; List all sessions
   (copilot/list-sessions client)

   ;; Filter by repository
   (copilot/list-sessions client {:repository \"owner/repo\"})
   ```"
  ([client]
   (client/list-sessions client))
  ([client filter-opts]
   (client/list-sessions client filter-opts)))

(defn delete-session!
  "Delete a session and its data from disk."
  [client session-id]
  (client/delete-session! client session-id))

(defn get-last-session-id
  "Get the ID of the most recently updated session."
  [client]
  (client/get-last-session-id client))

(defn get-foreground-session-id
  "Get the foreground session ID (TUI+server mode).
   Returns the session ID or nil if none."
  [client]
  (client/get-foreground-session-id client))

(defn set-foreground-session-id!
  "Set the foreground session (TUI+server mode).
   Requests the TUI to switch to displaying the specified session."
  [client session-id]
  (client/set-foreground-session-id! client session-id))

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

(defn <send!
  "Send a message and return a channel that delivers the final content string.
   This is the async equivalent of send-and-wait! - use inside go blocks.

   Example:
   ```clojure
   (go
     (let [answer (<! (copilot/<send! session {:prompt \"What is 2+2?\"}))]
       (println answer)))
   ```"
  [session opts]
  (session/<send! session opts))

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
  "Subscribe to session events. Returns a channel (buffer 1024) that receives events.
   The channel receives nil (close) when the session is destroyed.
   For explicit cleanup, call unsubscribe-events.
   
   This is a convenience wrapper around (tap (copilot/events session) ch).

   Drop behavior: Events are delivered via core.async mult. If this subscriber's
   buffer is full when mult delivers an event, that specific event is silently
   dropped for this subscriber only. Other subscribers with available buffer space
   still receive the event. With 1024 buffer, drops are unlikely unless the
   subscriber stops reading entirely.

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
   - :xf     - Transducer applied to events

   Drop behavior: If this subscriber's buffer is full when mult delivers an event,
   that specific event is silently dropped for this subscriber only. Other
   subscribers with available buffer space still receive the event."
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

(defn workspace-path
  "Get the session workspace path when provided by the CLI."
  [session]
  (session/workspace-path session))

(defn get-current-model
  "Get the current model for this session.
   Returns the model ID string, or nil if none set.

   Example:
   ```clojure
   (println \"Current model:\" (copilot/get-current-model session))
   ```"
  [session]
  (session/get-current-model session))

(defn switch-model!
  "Switch the model for this session.
   Returns the new model ID string, or nil.

   Example:
   ```clojure
   (copilot/switch-model! session \"claude-sonnet-4.5\")
   ```"
  [session model-id]
  (session/switch-model! session model-id))

(defn session-config
  "Get the configuration that was passed to create this session.
   
   Returns the user-provided config map. This reflects what was requested,
   not necessarily what the server is using (e.g., if a model was unavailable,
   the server may have selected a different one).
   
   The session.start event contains the actual selected model if validation
   is needed.

   Example:
   ```clojure
   (copilot/session-config session)
   ;=> {:model \"gpt-5.2\", :streaming? true, ...}
   ```"
  [session]
  (session/config session))

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
