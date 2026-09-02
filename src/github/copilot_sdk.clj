(ns github.copilot-sdk
  "Clojure SDK for programmatic control of GitHub Copilot CLI via JSON-RPC.

   Quick Start:
   ```clojure
   (require '[github.copilot-sdk :as copilot])

   ;; Create and start client
   (def client (copilot/client {:log-level :info}))
   (copilot/start! client)

   ;; Create a session
   (def session (copilot/create-session client {:on-permission-request copilot/approve-all
                                                :model \"gpt-5.4\"}))

   ;; Send a message and wait for response
   (def response (copilot/send-and-wait! session {:prompt \"What is 2+2?\"}))
   (println (get-in response [:data :content]))

   ;; Clean up
   (copilot/disconnect! session)
   (copilot/stop! client)
   ```"
  (:require [github.copilot-sdk.client :as client]
            [github.copilot-sdk.factory :as factory]
            [github.copilot-sdk.session :as session]
            [github.copilot-sdk.specs :as specs]
            [github.copilot-sdk.tools :as tools]))

;; =============================================================================
;; System Prompt Sections (customize mode)
;; =============================================================================

(def system-prompt-sections
  "Known system prompt section identifiers for the customize mode.
   Each key is a section keyword, and each value is a map with a :description.
   Use these identifiers in the :sections map of a :customize mode system message."
  specs/system-prompt-sections)

;; =============================================================================
;; Event Types
;; =============================================================================

(def event-types
  "Session event types exposed by the public SDK as namespaced keywords."
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
    :copilot/session.context_cleared
    :copilot/session.title_changed
    :copilot/session.schedule_created
    :copilot/session.schedule_cancelled
    :copilot/session.warning
    :copilot/session.mode_changed
    :copilot/session.plan_changed
    :copilot/session.todos_changed
    :copilot/session.workspace_file_changed
    :copilot/session.task_complete
    :copilot/user.message
    :copilot/pending_messages.modified
    :copilot/assistant.turn_start
    :copilot/assistant.intent
    :copilot/assistant.reasoning
    :copilot/assistant.reasoning_delta
    :copilot/assistant.message
    :copilot/assistant.message_start
    :copilot/assistant.message_delta
    :copilot/assistant.streaming_delta
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
    :copilot/subagent.deselected
    :copilot/skill.invoked
    :copilot/hook.start
    :copilot/hook.end
    :copilot/system.message
    :copilot/system.notification
    :copilot/permission.requested
    :copilot/permission.completed
    :copilot/user_input.requested
    :copilot/user_input.completed
    :copilot/elicitation.requested
    :copilot/elicitation.completed
    :copilot/external_tool.requested
    :copilot/external_tool.completed
    :copilot/mcp.oauth_required
    :copilot/mcp.oauth_completed
    :copilot/command.queued
    :copilot/command.execute
    :copilot/command.completed
    :copilot/commands.changed
    :copilot/exit_plan_mode.requested
    :copilot/exit_plan_mode.completed
    :copilot/auto_mode_switch.requested
    :copilot/auto_mode_switch.completed
    :copilot/session.tools_updated
    :copilot/session.background_tasks_changed
    :copilot/session.skills_loaded
    :copilot/session.mcp_servers_loaded
    :copilot/session.mcp_server_status_changed
    :copilot/session.extensions_loaded
    :copilot/session.custom_agents_updated
    :copilot/session.custom_notification
    :copilot/sampling.requested
    :copilot/sampling.completed
    :copilot/session.remote_steerable_changed
    :copilot/capabilities.changed
    ;; MCP Apps tool-call complete (upstream schema 1.0.52-4, SEP-1865)
    :copilot/mcp_app.tool_call_complete
    ;; Round 6 (upstream schema 1.0.56-1, post-v1.0.0-beta.4): autopilot
    ;; objective lifecycle, allow-all-permissions mode toggles, and an
    ;; ephemeral hook-progress event.
    :copilot/session.autopilot_objective_changed
    :copilot/session.permissions_changed
    :copilot/hook.progress
    ;; Remaining schema events (pinned schema 1.0.57). model.call_failure is an
    ;; ephemeral LLM-call telemetry event; assistant.message_start marks the
    ;; start of a streaming assistant message; extensions.attachments_pushed is
    ;; emitted when an extension pushes attachment pills. Canvas events are
    ;; delivered even though the canvas authoring API is out of scope for 1.0.0
    ;; (see doc limitations) so consumers can still observe them.
    :copilot/model.call_failure
    :copilot/session.extensions.attachments_pushed
    :copilot/session.canvas.opened
    :copilot/session.canvas.closed
    :copilot/session.canvas.registry_changed
    ;; v1.0.4 sync (pinned schema 1.0.65). binary_asset carries persisted /
    ;; omitted binary attachment references; the three new canvas events extend
    ;; the canvas lifecycle (recorded/removed/unavailable) and schedule_rearmed
    ;; fires when a recurring schedule re-arms after a tick. Delivered so
    ;; consumers can observe them even where the authoring API is out of scope.
    :copilot/session.binary_asset
    :copilot/session.canvas.recorded
    :copilot/session.canvas.removed
    :copilot/session.canvas.unavailable
    :copilot/session.schedule_rearmed
    ;; v1.0.5-preview.0 sync (pinned schema 1.0.67). assistant.idle marks the
    ;; assistant becoming idle within a turn; session_limits_changed reports
    ;; updated session-limit budgets; usage_checkpoint reports incremental usage;
    ;; session_limits_exhausted.requested/completed brackets an interactive prompt
    ;; when a session limit is hit; the mcp.headers_refresh_* pair brackets a
    ;; dynamic-header refresh on an MCP server connection.
    :copilot/assistant.idle
    :copilot/session.session_limits_changed
    :copilot/session.usage_checkpoint
    :copilot/session_limits_exhausted.requested
    :copilot/session_limits_exhausted.completed
    :copilot/mcp.headers_refresh_required
    :copilot/mcp.headers_refresh_completed
    ;; v1.0.7-preview.2 sync (pinned schema 1.0.70). assistant.tool_call_delta
    ;; streams incremental tool-call argument input; the mcp.*.list_changed
    ;; trio fire when an MCP server's tools/resources/prompts listing changes;
    ;; session.auto_mode_resolved (experimental) reports the model an auto-mode
    ;; session settled on for its first prompt and why.
    :copilot/assistant.tool_call_delta
    :copilot/mcp.tools.list_changed
    :copilot/mcp.resources.list_changed
    :copilot/mcp.prompts.list_changed
    :copilot/session.auto_mode_resolved
    ;; Post-v1.0.7 sync (pinned schema 1.0.73). assistant.turn_retry and
    ;; model.call_start are generated for wire compatibility but stay internal.
    :copilot/assistant.server_tool_progress
    :copilot/session.managed_settings_enforced
    :copilot/session.managed_settings_resolved
    :copilot/tool_search.activated
    ;; v1.0.9 + post-v1.0.9 sync (pinned schema 1.0.79-6).
    :copilot/factory.run_updated
    ;; Stable events present at upstream pin 2980c78 (schema 1.0.83-1).
    ;; HydraFusion events remain generated-only: upstream marks routing/phase
    ;; signals experimental and handoff/commit checkpoints internal.
    :copilot/session.mode_notice_delivered
    :copilot/model.call_finished
    :copilot/subagent.configured})

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
    :copilot/session.context_cleared
    :copilot/session.title_changed
    :copilot/session.warning
    :copilot/session.mode_changed
    :copilot/session.plan_changed
    :copilot/session.todos_changed
    :copilot/session.workspace_file_changed
    :copilot/session.task_complete
    :copilot/session.schedule_created
    :copilot/session.schedule_cancelled
    :copilot/session.tools_updated
    :copilot/session.background_tasks_changed
    :copilot/session.skills_loaded
    :copilot/session.mcp_servers_loaded
    :copilot/session.mcp_server_status_changed
    :copilot/session.extensions_loaded
    :copilot/session.custom_agents_updated
    :copilot/session.custom_notification
    :copilot/session.remote_steerable_changed
    :copilot/capabilities.changed
    ;; Round 6 additions (upstream schema 1.0.56-1).
    :copilot/session.autopilot_objective_changed
    :copilot/session.permissions_changed
    ;; v1.0.4 sync (pinned schema 1.0.65): recurring schedule re-arm event.
    :copilot/session.schedule_rearmed
    ;; v1.0.5-preview.0 sync (pinned schema 1.0.67): updated session-limit budgets
    ;; and incremental usage checkpoints.
    :copilot/session.session_limits_changed
    :copilot/session.usage_checkpoint
    ;; v1.0.7-preview.2 sync (pinned schema 1.0.70): experimental auto-mode
    ;; model resolution for the first prompt of an auto-mode session.
    :copilot/session.auto_mode_resolved
    ;; Post-v1.0.7 sync (pinned schema 1.0.73): enterprise managed-settings
    ;; resolution and enforcement.
    :copilot/session.managed_settings_enforced
    :copilot/session.managed_settings_resolved
    ;; Upstream pin 2980c78 (schema 1.0.83-1): persisted mid-turn mode notices.
    :copilot/session.mode_notice_delivered})

(def assistant-events
  "Assistant response events."
  #{:copilot/assistant.turn_start
    :copilot/assistant.intent
    :copilot/assistant.reasoning
    :copilot/assistant.reasoning_delta
    :copilot/assistant.message
    :copilot/assistant.message_start
    :copilot/assistant.message_delta
    :copilot/assistant.streaming_delta
    :copilot/assistant.turn_end
    :copilot/assistant.usage
    ;; v1.0.5-preview.0 sync (pinned schema 1.0.66-2): assistant idle within a turn.
    :copilot/assistant.idle
    ;; v1.0.7-preview.2 sync (introduced upstream schema 1.0.69-3): streaming tool-call input delta.
    :copilot/assistant.tool_call_delta
    ;; Post-v1.0.7 sync (pinned schema 1.0.73): server-side tool progress.
    :copilot/assistant.server_tool_progress})

(def tool-events
  "Tool execution events."
  #{:copilot/tool.user_requested
    :copilot/tool.execution_start
    :copilot/tool.execution_partial_result
    :copilot/tool.execution_progress
    :copilot/tool.execution_complete})

(def interaction-events
  "Events related to permission, user input, elicitation, sampling, and external tool flows."
  #{:copilot/permission.requested :copilot/permission.completed
    :copilot/user_input.requested :copilot/user_input.completed
    :copilot/elicitation.requested :copilot/elicitation.completed
    :copilot/external_tool.requested :copilot/external_tool.completed
    :copilot/mcp.oauth_required :copilot/mcp.oauth_completed
    ;; v1.0.5-preview.0 sync (pinned schema 1.0.66-2): MCP dynamic-header refresh lifecycle.
    :copilot/mcp.headers_refresh_required :copilot/mcp.headers_refresh_completed
    :copilot/command.queued :copilot/command.execute :copilot/command.completed
    :copilot/commands.changed
    :copilot/exit_plan_mode.requested :copilot/exit_plan_mode.completed
    :copilot/auto_mode_switch.requested :copilot/auto_mode_switch.completed
    :copilot/sampling.requested :copilot/sampling.completed
    ;; v1.0.5-preview.0 sync (pinned schema 1.0.67): interactive prompt when a
    ;; session limit is exhausted (mirrors the sampling request/complete pair).
    :copilot/session_limits_exhausted.requested :copilot/session_limits_exhausted.completed
    ;; v1.0.7-preview.2 sync (pinned schema 1.0.70): an MCP server's tools /
    ;; resources / prompts listing changed (server-initiated list_changed).
    :copilot/mcp.tools.list_changed
    :copilot/mcp.resources.list_changed
    :copilot/mcp.prompts.list_changed})

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
   - :auto-restart? - **DEPRECATED**: This option has no effect and will be removed in a future release.
   - :env           - Environment variables map
   - :telemetry     - OpenTelemetry config map with :otlp-endpoint, :file-path, :exporter-type, :source-name, :capture-content?
   - :on-get-trace-context - Zero-arg fn returning {:traceparent ... :tracestate ...}
   - :remote?       - **Experimental**. Enable remote session support (Mission Control). (upstream PR #1192)

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
  "Force stop the CLI server without graceful RPCs.

   Releases local session resources before terminating the transport and any
   SDK-owned process.
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
   Returns {:message :timestamp :protocol-version}.

   `:timestamp` is either an ISO 8601 date-time string (CLI ≥ 1.0.51,
   upstream PR #1340) or a numeric epoch-millis value, depending on the
   CLI version; the SDK forwards whatever the server sends."
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
   :model-billing {:multiplier :token-prices
                   :promo {:ends-at :id :discount-percent :message}}
   :vision-limits {:supported-media-types :max-prompt-images :max-prompt-image-size}

   Example:
   ```clojure
   (doseq [model (copilot/list-models client)]
     (println (:name model) \"-\" (:id model)))
   ```"
  [client]
  (client/list-models client))

(defn ^:experimental list-tools
  "List available tools with their metadata.
   Optional model param returns model-specific tool overrides.

   Returns a vector of tool info maps with keys:
   :name :namespaced-name :description :parameters :instructions

   Experimental: not part of the official Copilot SDK API.

   Example:
   ```clojure
   (doseq [tool (copilot/list-tools client)]
     (println (:name tool) \"-\" (:description tool)))
   ```"
  ([client]
   (client/list-tools client))
  ([client model]
   (client/list-tools client model)))

(defn ^:experimental get-quota
  "Get account quota information.
   Returns a map of quota type (string) to quota snapshot maps:
   {:entitlement-requests :used-requests :remaining-percentage
    :overage :overage-allowed-with-exhausted-quota? :reset-date}

   Experimental: not part of the official Copilot SDK API.

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

   `:on-permission-request` is **optional** (since upstream PR #1308) — omit it to
   leave permission requests pending for manual resolution via
   `handle-pending-permission-request!`. Commonly-used config options:
   - :on-permission-request - Permission handler function (optional, e.g. `approve-all`).
                             When omitted, permission requests surface as
                             `:copilot/permission.requested` events and stay pending.
   - :session-id           - Custom session ID
   - :model                - Model to use (e.g., \"gpt-5.4\", \"claude-sonnet-4.5\")
   - :tools                - Vector of tool definitions (use define-tool)
   - :system-message       - {:mode :append/:replace :content \"...\"}
   - :available-tools      - List of allowed tool names
   - :excluded-tools       - List of excluded tool names
   - :tool-search          - Tool discovery config {:enabled :defer-threshold}
   - :provider             - Custom provider config (BYOK)
   - :streaming?           - Enable streaming deltas
   - :mcp-servers          - MCP server configs map (keyed by server ID)
   - :enable-mcp-apps      - Experimental MCP Apps host opt-in. Set true only when
                             the host can render `ui://` bundles; false and omission
                             do not send the wire request.
   - :request-extensions?  - Opt into extension management and dispatch. Explicit
                             false is forwarded; omission sends no wire key.
   - :extension-sdk-path   - String path override for the SDK injected into extension
                             subprocesses. Invalid paths fall back to the bundled SDK.
   - :extension-info       - Stable extension identity `{:source string :name string}`.
   - :custom-agents        - Custom agent configs
   - :default-agent        - Built-in agent config, e.g. {:excluded-tools [\"private_tool\"]}
   - :skill-directories    - Additional skill directories to load
   - :disabled-skills      - Disable specific skills by name
   - :working-directory    - Working directory for the session (tool operations relative to this)

   See the API reference (doc/reference/API.md in this repository) for the complete,
   current option list (including observability/telemetry, trace context, exit-plan-mode /
   auto-mode-switch handlers, skills, plugins, remote, and cloud session options).

   Example:
   ```clojure
   (def session (copilot/create-session client {:on-permission-request copilot/approve-all
                                                :model \"gpt-5.4\"}))
   ```"
  [client config]
  (client/create-session client config))

(defn <create-session
  "Async version of create-session. Returns a channel that delivers a CopilotSession.

   Validation is synchronous (throws immediately on invalid config).
   The RPC call parks instead of blocking, making this safe inside go blocks.
   On RPC error, delivers an ExceptionInfo (check with `(instance? Throwable result)`).

   Example:
   ```clojure
   (go
     (let [result (<! (copilot/<create-session client {:on-permission-request copilot/approve-all
                                                       :model \"gpt-5.4\"}))]
       (when-not (instance? Throwable result)
         (let [answer (<! (copilot/<send! result {:prompt \"Hello\"}))]
           (println answer)))))
   ```"
  [client config]
  (client/<create-session client config))

(defmacro with-session
  "Create a session and ensure disconnect! on exit.

   Usage:
   (with-session [s client {:on-permission-request copilot/approve-all
                            :model \"gpt-5.4\"}]
     ...)"
  [[session-sym client config] & body]
  `(let [~session-sym (create-session ~client ~config)]
     (try
       ~@body
       (finally
         (disconnect! ~session-sym)))))

(defmacro with-client-session
  "Create a client + session and ensure cleanup on exit.
   Automatically calls disconnect! on session and stop! on client.

   Four forms are supported:

   1. [session session-opts] - anonymous client with default options
      ```clojure
      (with-client-session [session {:on-permission-request copilot/approve-all
                                     :model \"gpt-5.4\"}]
        (copilot/send! session {:prompt \"Hi\"}))
      ```

   2. [client-opts session session-opts] - anonymous client with custom options
      ```clojure
      (with-client-session [{:log-level :debug} session {:on-permission-request copilot/approve-all
                                                          :model \"gpt-5.4\"}]
        (copilot/send! session {:prompt \"Hi\"}))
      ```

   3. [client session session-opts] - named client with default options
      ```clojure
      (with-client-session [client session {:on-permission-request copilot/approve-all
                                            :model \"gpt-5.4\"}]
        (println (copilot/client-options client))
        (copilot/send! session {:prompt \"Hi\"}))
      ```

   4. [client client-opts session session-opts] - named client with custom options
      ```clojure
      (with-client-session [client {:log-level :debug} session {:on-permission-request copilot/approve-all
                                                                 :model \"gpt-5.4\"}]
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
   Accepts the same config options as `create-session` (except `:session-id` and `:cloud`),
   including the experimental `:enable-mcp-apps` host opt-in,
   plus:
   - :disable-resume?  - When true, skip emitting the session.resume event (default: false)

   `:on-permission-request` is **optional** (since upstream PR #1308) — omit it to
   leave permission requests pending for manual resolution.

   When `:mcp-servers` is present, the configuration is applied by
   `session.resume`.

   Example:
   ```clojure
   (def session (copilot/resume-session client \"session-123\"
                  {:on-permission-request copilot/approve-all}))
   ;; Resume with different model
   (def session (copilot/resume-session client \"session-123\"
                  {:on-permission-request copilot/approve-all
                   :model \"claude-sonnet-4\"
                   :reasoning-effort \"high\"}))
   ```"
  [client session-id config]
  (client/resume-session client session-id config))

(defn <resume-session
  "Async version of resume-session. Returns a channel that delivers a CopilotSession.

   Validation is synchronous (throws immediately on invalid config).
   The RPC call parks instead of blocking, making this safe inside go blocks.
   On RPC error, delivers an ExceptionInfo (check with `(instance? Throwable result)`).

   Example:
   ```clojure
   (go
     (let [result (<! (copilot/<resume-session client \"session-123\"
                                               {:on-permission-request copilot/approve-all}))]
       (when-not (instance? Throwable result)
         ;; use resumed session
         )))
   ```"
  [client session-id config]
  (client/<resume-session client session-id config))

(defn join-session
  "Join the current foreground session from an extension running as a child process.

   Reads the SESSION_ID environment variable and connects to the parent CLI process
   via stdio. Intended for extensions spawned by the Copilot CLI.

   Config is the same as `resume-session` except `:extension-sdk-path` is not accepted.
   `:request-extensions?`, `:extension-info`, and the join-only
   `:requested-environment-variables` vector are accepted. An omitted or empty
   environment-variable vector sends no request; explicit nil is invalid.
   `:on-permission-request` is **optional**;
   when omitted, join-session uses `default-join-session-permission-handler`.
   The `:disable-resume?` option defaults to true.

   Returns a map with `:client` and `:session` keys. When environment variables
   are requested, the result also includes `:granted-environment-variables`,
   filtered to the exact requested names. Unlike Node.js, the JVM cannot portably
   mutate process environment variables, so extensions read approved values from
   this map. The caller is responsible for stopping the client when done.

   Throws if SESSION_ID is not set in the environment.

   Example:
   ```clojure
   (require '[github.copilot-sdk :as copilot])

   (let [{:keys [client session granted-environment-variables]}
         (copilot/join-session
          {:on-permission-request copilot/approve-all
           :requested-environment-variables [\"GITHUB_TOKEN\"]
           :tools [my-tool]})]
     (when-let [token (get granted-environment-variables \"GITHUB_TOKEN\")]
       ;; Supply token to extension-owned code without logging it.
       (use-token token))
     ;; use session...
     (copilot/stop! client))
   ```"
  [config]
  (client/join-session config))

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

(defn get-session-metadata
  "Gets metadata for a specific session by ID.

   Returns the session metadata map if found, or nil if not found.
   Provides an efficient O(1) lookup instead of listing all sessions.

   Example:
   ```clojure
   (copilot/get-session-metadata client \"session-abc123\")
   ;; => {:session-id \"session-abc123\" :start-time #object[java.time.Instant ...] ...}

   (copilot/get-session-metadata client \"non-existent-id\")
   ;; => nil
   ```"
  [client session-id]
  (client/get-session-metadata client session-id))

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
   An idle event whose `:data :mode` is the string \"autopilot\" is a
   nonterminal turn boundary (the agent keeps working), so the wait
   continues past it to the next session.idle/session.error.

   Options: same as send!, plus:
   - :timeout-ms   - Timeout in milliseconds (default: 60000). Read from `opts`
                     in the 2-arity form; `nil` (in `opts` or as the positional
                     argument) disables the deadline and waits indefinitely.
                     Never forwarded on the underlying `session.send`.

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
   The channel closes after an ordinary session.idle or session.error. An
   idle event whose `:data :mode` is the string \"autopilot\" is a
   nonterminal turn boundary (the agent keeps working) and is delivered on
   the channel without closing it.
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
   As with send-and-wait!, a nonterminal autopilot session.idle (`:data
   :mode` = \"autopilot\") does not end the wait.

   Example:
   ```clojure
   (go
     (let [answer (<! (copilot/<send! session {:prompt \"What is 2+2?\"}))]
       (println answer)))
   ```"
  [session opts]
  (session/<send! session opts))

(defn <send-and-wait!
  "Send a message and return a channel that delivers the final assistant message
   event - the channel-based equivalent of `send-and-wait!`. Use it inside go
   blocks instead of blocking a dispatch thread. Unlike `<send!` (which delivers
   the final content string), this delivers the full assistant message event.
   As with send-and-wait!, an idle event whose `:data :mode` is the string
   \"autopilot\" is a nonterminal turn boundary, so the wait continues past it.

   Options: same as send!, plus:
   - :timeout-ms   - Timeout in milliseconds (default: 60000, set to nil to disable)

   The returned channel delivers a single value (the final assistant message
   event, or nothing if none was received) then closes.

   Example:
   ```clojure
   (go
     (let [event (<! (copilot/<send-and-wait! session {:prompt \"What is 2+2?\"}))]
       (println (get-in event [:data :content]))))
   ```"
  [session opts]
  (session/<send-and-wait! session opts))

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

(defn handle-pending-tool-call!
  "Manually resolve a pending external tool call (upstream PR #1308).

   Use this when a tool was declared without a `:handler` and the runtime
   emitted a `:copilot/external_tool.requested` event. Read the `:request-id`
   from `(:data event)` and supply either `:result` or `:error`.

   Options:
   - :request-id - String request id from the event (required)
   - :result     - Tool result (string, map, or other; normalized for the wire)
   - :error      - Error message (string). Mutually exclusive with :result."
  [session opts]
  (session/handle-pending-tool-call! session opts))

(defn <handle-pending-tool-call!
  "core.async variant of `handle-pending-tool-call!`. Returns a channel."
  [session opts]
  (session/<handle-pending-tool-call! session opts))

(defn handle-pending-permission-request!
  "Manually resolve a pending permission request (upstream PR #1308).

   Use this when no `:on-permission-request` handler was registered and the
   runtime emitted a `:copilot/permission.requested` event. Read the
   `:request-id` from `(:data event)` and supply a decision in `:result`.

   Options:
   - :request-id - String request id from the event (required)
   - :result     - Permission decision map `{:kind k ...}`. `:no-result` is
                   not allowed for the pending RPC — to decline answering,
                   simply don't call this function."
  [session opts]
  (session/handle-pending-permission-request! session opts))

(defn <handle-pending-permission-request!
  "core.async variant of `handle-pending-permission-request!`. Returns a channel."
  [session opts]
  (session/<handle-pending-permission-request! session opts))

(defn disconnect!
  "Disconnects the session and releases in-memory resources (event handlers,
   tool handlers, permission handler). Session data on disk is preserved for
   later resumption via `resume-session`. To permanently remove all session
   data, use `delete-session!` instead."
  [session]
  (session/disconnect! session))

(defn destroy!
  "Deprecated: Use disconnect! instead. This function will be removed in a future release.
   Disconnects the session and releases in-memory resources.
   Session data on disk is preserved for later resumption."
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
   The channel receives nil (close) when the session is disconnected.
   For explicit cleanup, call unsubscribe-events!.
   
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

(defn unsubscribe-events!
  "Unsubscribe a channel from session events.

   Side effects: untaps `ch` from the session's event mult and closes `ch`.
   The caller must not use `ch` after calling this."
  [session ch]
  (session/unsubscribe-events! session ch))

(defn session-id
  "Get the session ID."
  [session]
  (session/session-id session))

(defn workspace-path
  "Get the session workspace path when provided by the CLI."
  [session]
  (session/workspace-path session))

(defn capabilities
  "Get the host capabilities reported when the session was created or resumed.
   Returns a map, e.g. `{:ui {:elicitation true}}`.

   Example:
   ```clojure
   (copilot/capabilities session)
   ;; => {:ui {:elicitation true}}
   ```"
  [session]
  (session/capabilities session))

(defn open-canvases
  "Get the current open-canvases snapshot for `session`. Returns a vector of
  canvas-instance maps. The snapshot is initialized from `session.resume` and
  updated by `:copilot/session.canvas.opened` / `:copilot/session.canvas.closed`
  events. `session.create` does NOT populate it (matches upstream Node.js).

  Each entry has required keys `:instance-id`, `:extension-id`, `:canvas-id`
  and optional `:extension-name`, `:icon`, `:title`, `:status`, `:url`, `:input`.

  Example:
  ```clojure
  (copilot/open-canvases session)
  ;; => [{:instance-id \"i1\" :canvas-id \"diff\" :extension-id \"ext.x\"
  ;;      :title \"Diff\"}]
  ```"
  [session]
  (session/open-canvases session))

(defn elicitation-supported?
  "Check if the CLI host supports interactive elicitation dialogs.

   Example:
   ```clojure
   (when (copilot/elicitation-supported? session)
     (copilot/confirm! session \"Deploy to production?\"))
   ```"
  [session]
  (session/elicitation-supported? session))

(defn ui-elicitation!
  "Request structured user input via an elicitation prompt.
   params is a map with :message and :requested-schema keys.
   Throws if the host does not support elicitation.

   Example:
   ```clojure
   (copilot/ui-elicitation! session
     {:message \"Configure deployment\"
      :requested-schema {:type \"object\"
                         :properties {\"env\" {:type \"string\" :enum [\"staging\" \"production\"]}}
                         :required [\"env\"]}})
   ```"
  [session params]
  (session/ui-elicitation! session params))

(defn confirm!
  "Show a confirmation dialog and return the user's boolean answer.
   Returns false if the user declines or cancels.
   Throws if the host does not support elicitation.

   Example:
   ```clojure
   (when (copilot/confirm! session \"Deploy to production?\")
     (println \"Deploying...\"))
   ```"
  [session message]
  (session/confirm! session message))

(defn select!
  "Show a selection dialog with the given options.
   Returns the selected value as a string, or nil if the user declines/cancels.
   Throws if the host does not support elicitation.

   Example:
   ```clojure
   (when-let [env (copilot/select! session \"Choose environment\" [\"staging\" \"production\"])]
     (println \"Selected:\" env))
   ```"
  [session message options]
  (session/select! session message options))

(defn input!
  "Show a text input dialog. Returns the entered text, or nil if the user
   declines/cancels. opts is an optional map with :title, :description,
   :min-length, :max-length, :format, and :default keys.
   Throws if the host does not support elicitation.

   Example:
   ```clojure
   (when-let [name (copilot/input! session \"Enter your name\")]
     (println \"Hello,\" name))
   ```"
  ([session message]
   (session/input! session message))
  ([session message opts]
   (session/input! session message opts)))

(defn create-session-fs-adapter
  "Adapt a provider-style session filesystem implementation to a sessionFs handler map.

   Provider functions receive direct arguments and throw on errors; the adapter
   returns the structured RPC results expected by the CLI. Session factories may
   return provider-style maps directly because create-session/resume-session
   auto-adapt them. Use this helper when you need the low-level handler map
   yourself.

   Example:
   ```clojure
   (copilot/create-session-fs-adapter
     {:read-file slurp
      :write-file (fn [path content _mode] (spit path content))
      ;; implement the remaining sessionFs operations...
      })
   ```"
  [provider]
  (session/create-session-fs-adapter provider))

(defn session-fs-sqlite-transaction-failure
  "Create a classified SQLite transaction failure."
  ([message]
   (session/session-fs-sqlite-transaction-failure message))
  ([message error-class]
   (session/session-fs-sqlite-transaction-failure message error-class)))

(defn session-fs-sqlite-transaction-failure?
  "Return true for classified SQLite transaction failures."
  [value]
  (session/session-fs-sqlite-transaction-failure? value))

(defn ^:experimental history-clear-context!
  "Clear conversation context and set the prompt used for the new context."
  [session prompt]
  (session/history-clear-context! session prompt))

(defn ^:experimental define-factory
  "Define an extension-authored Agent Factory."
  [definition]
  (factory/define-factory definition))

(defn ^:experimental factory-terminal-status?
  "Return true when a factory run status is terminal."
  [status]
  (factory/terminal-status? status))

(defn ^:experimental run-factory!
  ([session name-or-handle]
   (factory/run! session name-or-handle))
  ([session name-or-handle options]
   (factory/run! session name-or-handle options)))

(defn ^:experimental <run-factory!
  ([session name-or-handle]
   (factory/<run! session name-or-handle))
  ([session name-or-handle options]
   (factory/<run! session name-or-handle options)))

(defn ^:experimental resume-factory!
  ([session run-id]
   (factory/resume! session run-id))
  ([session run-id options]
   (factory/resume! session run-id options)))

(defn ^:experimental <resume-factory!
  ([session run-id]
   (factory/<resume! session run-id))
  ([session run-id options]
   (factory/<resume! session run-id options)))

(defn ^:experimental get-factory-run [session run-id]
  (factory/get-run session run-id))

(defn ^:experimental <get-factory-run [session run-id]
  (factory/<get-run session run-id))

(defn ^:experimental wait-for-factory-run!
  ([session run-id]
   (factory/wait-for-run! session run-id))
  ([session run-id options]
   (factory/wait-for-run! session run-id options)))

(defn ^:experimental <wait-for-factory-run!
  ([session run-id]
   (factory/<wait-for-run! session run-id))
  ([session run-id options]
   (factory/<wait-for-run! session run-id options)))

(defn ^:experimental list-factory-runs [session]
  (factory/list-runs session))

(defn ^:experimental <list-factory-runs [session]
  (factory/<list-runs session))

(defn ^:experimental get-factory-run-detail [session run-id]
  (factory/get-run-detail session run-id))

(defn ^:experimental <get-factory-run-detail [session run-id]
  (factory/<get-run-detail session run-id))

(defn ^:experimental get-factory-run-progress
  ([session run-id]
   (factory/get-run-progress session run-id))
  ([session run-id options]
   (factory/get-run-progress session run-id options)))

(defn ^:experimental <get-factory-run-progress
  ([session run-id]
   (factory/<get-run-progress session run-id))
  ([session run-id options]
   (factory/<get-run-progress session run-id options)))

(defn ^:experimental cancel-factory-run! [session run-id]
  (factory/cancel! session run-id))

(defn ^:experimental <cancel-factory-run! [session run-id]
  (factory/<cancel! session run-id))

(defn ^:experimental get-current-model
  "Get the current model for this session.
   Returns the model ID string, or nil if none set.

   Experimental: not part of the official Copilot SDK API.

   Example:
   ```clojure
   (println \"Current model:\" (copilot/get-current-model session))
   ```"
  [session]
  (session/get-current-model session))

(defn switch-model!
  "Switch the model for this session.
   The new model takes effect for the next message. Conversation history is preserved.
   Optional opts map with `:reasoning-effort` (\"low\", \"medium\", \"high\", \"xhigh\").
   Returns the new model ID string, or nil.

   Example:
   ```clojure
   (copilot/switch-model! session \"claude-sonnet-4.5\")
   (copilot/switch-model! session \"claude-sonnet-4.5\" {:reasoning-effort \"high\"})
   ```"
  ([session model-id]
   (session/switch-model! session model-id))
  ([session model-id opts]
   (session/switch-model! session model-id opts)))

(defn set-model!
  "Alias for switch-model!. Matches the upstream SDK's setModel() API.
   The new model takes effect for the next message. Conversation history is preserved.

   Example:
   ```clojure
   (copilot/set-model! session \"gpt-4.1\")
   (copilot/set-model! session \"gpt-4.1\" {:reasoning-effort \"high\"})
   ```"
  ([session model-id]
   (session/set-model! session model-id))
  ([session model-id opts]
   (session/set-model! session model-id opts)))

(defn log!
  "Log a message to the session timeline.
   Options (optional map):
   - :level      - \"info\", \"warning\", or \"error\" (default: \"info\")
   - :ephemeral? - when true, message is not persisted to disk (default: false)
   Returns the event ID string.

   Example:
   ```clojure
   (copilot/log! session \"Processing started\")
   (copilot/log! session \"Something went wrong\" {:level \"error\"})
   (copilot/log! session \"Temporary note\" {:ephemeral? true})
   ```"
  ([session message] (session/log! session message))
  ([session message opts] (session/log! session message opts)))

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
   ;=> {:model \"gpt-5.4\", :streaming? true, ...}
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
     - :description             - Tool description
     - :parameters              - JSON schema for parameters
     - :handler                 - Function (fn [args invocation] -> result)
     - :overrides-built-in-tool - When true, overrides a built-in tool of the same name
     - :defer                   - `:auto` or `:never` (upstream PR #1632). When `:auto` the tool may be
                                  deferred (loaded lazily via tool search); `:never` forces pre-loading.
                                  Defaults to `:auto`.
     - :metadata                - Opaque host-defined map forwarded to the runtime

   The handler receives:
   - args       - The parsed arguments from the LLM
   - invocation - Map with :session-id, :tool-call-id, :tool-name, :arguments,
                 and optional :available-tools for `tool_search_tool`

   Return a string, or a tool result map with `:text-result-for-llm`,
   `:result-type`, and optional `:tool-references`.

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
   ```

   Override a built-in tool:
   ```clojure
   (def custom-grep
     (copilot/define-tool \"grep\"
       {:description \"Custom grep implementation\"
        :overrides-built-in-tool true
        :parameters {:type \"object\"
                     :properties {:query {:type \"string\"}}
                     :required [\"query\"]}
        :handler (fn [args _]
                   (str \"Custom grep: \" (:query args)))}))
   ```"
  [name opts]
  (tools/define-tool name opts))

;; Re-export result helpers as thin wrappers so the public surface keeps the
;; source docstrings and signatures (a bare `def` alias drops both).
(defn result-success
  "Create a successful tool result.

   `telemetry` is a map of string bucket names to JSON object maps."
  ([text] (tools/result-success text))
  ([text telemetry] (tools/result-success text telemetry)))

(defn result-failure
  "Create a failed tool result.

   `telemetry` is a map of string bucket names to JSON object maps."
  ([text] (tools/result-failure text))
  ([text error] (tools/result-failure text error))
  ([text error telemetry] (tools/result-failure text error telemetry)))

(defn result-denied
  "Create a denied tool result (permission denied).

   `telemetry` is a map of string bucket names to JSON object maps."
  ([text] (tools/result-denied text))
  ([text telemetry] (tools/result-denied text telemetry)))

(defn result-rejected
  "Create a rejected tool result (user rejected).

   `telemetry` is a map of string bucket names to JSON object maps."
  ([text] (tools/result-rejected text))
  ([text telemetry] (tools/result-rejected text telemetry)))

(defn convert-mcp-call-tool-result
  "Convert an MCP CallToolResult into the SDK's ToolResultObject format.
   See `github.copilot-sdk.tools/convert-mcp-call-tool-result`."
  [result]
  (tools/convert-mcp-call-tool-result result))

;; Re-export permission helpers
(defn attributed-permission-result?
  "Return true when `result` is a well-formed attributed permission result.
   See `github.copilot-sdk.client/attributed-permission-result?`."
  [result]
  (client/attributed-permission-result? result))

(defn attributed-permission-result
  "Attach informational decision context to a permission-handler result.
   Reapplying attribution replaces the previous context rather than nesting it.
   See `github.copilot-sdk.client/attributed-permission-result`."
  [result decision-context]
  (client/attributed-permission-result result decision-context))

(def ^{:arglists '([request ctx])} approve-all
  "Permission handler that approves all requests with `{:kind :approve-once}`.
   See `github.copilot-sdk.client/approve-all`."
  client/approve-all)

(def ^{:arglists '([request ctx])} default-join-session-permission-handler
  "Default permission handler for resuming sessions.
  Returns `{:kind :no-result}` — the CLI handles permissions itself.
  When used with `resume-session`, sends `requestPermission: false` on the wire.
  See `github.copilot-sdk.client/default-join-session-permission-handler`."
  client/default-join-session-permission-handler)
