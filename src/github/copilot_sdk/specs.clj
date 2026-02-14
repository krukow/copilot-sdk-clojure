(ns github.copilot-sdk.specs
  "Clojure specs for Copilot SDK data structures."
  (:require [clojure.spec.alpha :as s]
            [clojure.set :as set]))

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

(s/def ::non-blank-string (s/and string? (complement clojure.string/blank?)))
(s/def ::timestamp string?)
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
(s/def ::tool-timeout-ms pos-int?)
(s/def ::env (s/map-of string? (s/nilable string?)))
;; Authentication options (PR #237)
(s/def ::github-token ::non-blank-string)
(s/def ::use-logged-in-user? boolean?)

(def client-options-keys
  #{:cli-path :cli-args :cli-url :cwd :port
    :use-stdio? :log-level :auto-start? :auto-restart?
    :notification-queue-size :router-queue-size
    :tool-timeout-ms :env :github-token :use-logged-in-user?})

(s/def ::client-options
  (closed-keys
   (s/keys :opt-un [::cli-path ::cli-args ::cli-url ::cwd ::port
                    ::use-stdio? ::log-level ::auto-start? ::auto-restart?
                    ::notification-queue-size ::router-queue-size
                    ::tool-timeout-ms ::env ::github-token ::use-logged-in-user?])
   client-options-keys))

;; -----------------------------------------------------------------------------
;; Tool definitions
;; -----------------------------------------------------------------------------

(s/def ::tool-name ::non-blank-string)
(s/def ::tool-description (s/nilable string?))
(s/def ::json-schema map?)
(s/def ::tool-parameters (s/nilable ::json-schema))
(s/def ::tool-handler fn?)

(s/def ::tool
  (s/keys :req-un [::tool-name ::tool-handler]
          :opt-un [::tool-description ::tool-parameters]))

(s/def ::tools (s/coll-of ::tool))

;; -----------------------------------------------------------------------------
;; System message configuration
;; -----------------------------------------------------------------------------

(s/def ::system-message-mode #{:append :replace})
(s/def ::system-message-content string?)

;; System message uses :mode and :content keys directly
(s/def ::system-message
  (s/and map?
         #(if-let [m (:mode %)] (#{:append :replace} m) true)
         #(if-let [c (:content %)] (string? c) true)))

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

(s/def ::mcp-local-server
  (s/keys :req-un [::mcp-command ::mcp-args ::mcp-tools]
          :opt-un [::mcp-server-type ::mcp-timeout ::env ::cwd]))

(s/def ::mcp-remote-server
  (s/keys :req-un [::mcp-server-type ::mcp-url ::mcp-tools]
          :opt-un [::mcp-timeout ::mcp-headers]))

(s/def ::mcp-server (s/or :local ::mcp-local-server :remote ::mcp-remote-server))
(s/def ::mcp-servers (s/map-of #(or (keyword? %) (string? %)) ::mcp-server))

;; -----------------------------------------------------------------------------
;; Custom agent configuration
;; -----------------------------------------------------------------------------

(s/def ::agent-name ::non-blank-string)
(s/def ::agent-display-name string?)
(s/def ::agent-description string?)
(s/def ::agent-tools (s/nilable (s/coll-of string?)))
(s/def ::agent-prompt ::non-blank-string)
(s/def ::agent-infer? boolean?)

(s/def ::custom-agent
  (s/keys :req-un [::agent-name ::agent-prompt]
          :opt-un [::agent-display-name ::agent-description ::agent-tools
                   ::mcp-servers ::agent-infer?]))

(s/def ::custom-agents (s/coll-of ::custom-agent))

;; -----------------------------------------------------------------------------
;; Provider configuration (BYOK)
;; -----------------------------------------------------------------------------

(s/def ::provider-type #{:openai :azure :anthropic})
(s/def ::wire-api #{:completions :responses})
(s/def ::base-url ::non-blank-string)
(s/def ::api-key string?)
(s/def ::bearer-token string?)
(s/def ::azure-api-version string?)

(s/def ::azure-options
  (s/keys :opt-un [::azure-api-version]))

(s/def ::provider
  (s/keys :req-un [::base-url]
          :opt-un [::provider-type ::wire-api ::api-key ::bearer-token ::azure-options]))

;; -----------------------------------------------------------------------------
;; Session configuration
;; -----------------------------------------------------------------------------

(s/def ::model ::non-blank-string)
(s/def ::available-tools (s/coll-of string?))
(s/def ::excluded-tools (s/coll-of string?))
(s/def ::streaming? boolean?)
(s/def ::on-permission-request fn?)
(s/def ::config-dir ::non-blank-string)
(s/def ::skill-directories (s/coll-of ::non-blank-string))
(s/def ::disabled-skills (s/coll-of ::non-blank-string))
(s/def ::enabled boolean?)
(s/def ::max-size-bytes pos-int?)
(s/def ::output-dir ::non-blank-string)
(s/def ::large-output
  (s/keys :opt-un [::enabled ::max-size-bytes ::output-dir]))

;; Working directory
(s/def ::working-directory ::non-blank-string)

;; Infinite sessions configuration
(s/def ::background-compaction-threshold (s/and number? #(<= 0.0 % 1.0)))
(s/def ::buffer-exhaustion-threshold (s/and number? #(<= 0.0 % 1.0)))
(s/def ::infinite-sessions
  (s/keys :opt-un [::enabled ::background-compaction-threshold ::buffer-exhaustion-threshold]))

;; Reasoning effort support (PR #302)
(s/def ::reasoning-effort #{"low" "medium" "high" "xhigh"})

;; Hooks and user input handlers (PR #269)
(s/def ::on-user-input-request fn?)
(s/def ::on-pre-tool-use fn?)
(s/def ::on-post-tool-use fn?)
(s/def ::on-user-prompt-submitted fn?)
(s/def ::on-session-start fn?)
(s/def ::on-session-end fn?)
(s/def ::on-error-occurred fn?)
(s/def ::hooks
  (s/keys :opt-un [::on-pre-tool-use ::on-post-tool-use ::on-user-prompt-submitted
                   ::on-session-start ::on-session-end ::on-error-occurred]))

;; Disable resume flag
(s/def ::disable-resume? boolean?)

(def session-config-keys
  #{:session-id :model :tools :system-message
    :available-tools :excluded-tools :provider
    :on-permission-request :streaming? :mcp-servers
    :custom-agents :config-dir :skill-directories
    :disabled-skills :large-output :infinite-sessions
    :reasoning-effort :on-user-input-request :hooks
    :working-directory})

(s/def ::session-config
  (closed-keys
   (s/keys :opt-un [::session-id ::model ::tools ::system-message
                    ::available-tools ::excluded-tools ::provider
                    ::on-permission-request ::streaming? ::mcp-servers
                    ::custom-agents ::config-dir ::skill-directories
                    ::disabled-skills ::large-output ::infinite-sessions
                    ::reasoning-effort ::on-user-input-request ::hooks
                    ::working-directory])
   session-config-keys))

(def ^:private resume-session-config-keys
  #{:model :tools :system-message :available-tools :excluded-tools
    :provider :streaming? :on-permission-request
    :mcp-servers :custom-agents :config-dir :skill-directories
    :disabled-skills :infinite-sessions :reasoning-effort
    :on-user-input-request :hooks :working-directory :disable-resume?})

(s/def ::resume-session-config
  (closed-keys
   (s/keys :opt-un [::model ::tools ::system-message ::available-tools ::excluded-tools
                    ::provider ::streaming? ::on-permission-request
                    ::mcp-servers ::custom-agents ::config-dir ::skill-directories
                    ::disabled-skills ::infinite-sessions ::reasoning-effort
                    ::on-user-input-request ::hooks ::working-directory ::disable-resume?])
   resume-session-config-keys))

;; -----------------------------------------------------------------------------
;; Message options
;; -----------------------------------------------------------------------------

(s/def ::prompt ::non-blank-string)
(s/def ::attachment-type #{:file :directory :selection})
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

;; File/directory attachment
(s/def ::file-or-directory-attachment
  (s/and (s/keys :req-un [::type ::path]
                 :opt-un [::display-name])
         #(#{:file :directory} (:type %))))

;; Selection attachment
(s/def ::selection-attachment
  (s/and (s/keys :req-un [::type ::file-path ::display-name]
                 :opt-un [::selection-range ::text])
         #(= :selection (:type %))))

(s/def ::attachment
  (s/or :file-or-directory ::file-or-directory-attachment
        :selection ::selection-attachment))

(s/def ::attachments (s/coll-of ::attachment))
(s/def ::mode #{:enqueue :immediate})

(s/def ::send-options
  (s/keys :req-un [::prompt]
          :opt-un [::attachments ::mode ::timeout-ms]))

(s/def ::timeout-ms pos-int?)

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

(s/def ::base-event
  (s/keys :req-un [::event-id ::event-timestamp ::parent-id]
          :opt-un [::ephemeral?]))

;; Event type enum (namespaced under :copilot/)
(s/def ::event-type
  #{:copilot/session.start :copilot/session.resume :copilot/session.error :copilot/session.idle
    :copilot/session.info :copilot/session.model_change :copilot/session.handoff
    :copilot/session.truncation :copilot/session.snapshot_rewind :copilot/session.usage_info
    :copilot/session.compaction_start :copilot/session.compaction_complete
    :copilot/session.shutdown
    :copilot/session.title_changed :copilot/session.warning :copilot/session.context_changed
    :copilot/user.message :copilot/pending_messages.modified
    :copilot/assistant.turn_start :copilot/assistant.intent :copilot/assistant.reasoning
    :copilot/assistant.reasoning_delta :copilot/assistant.message :copilot/assistant.message_delta
    :copilot/assistant.turn_end :copilot/assistant.usage
    :copilot/abort
    :copilot/tool.user_requested :copilot/tool.execution_start :copilot/tool.execution_partial_result
    :copilot/tool.execution_progress :copilot/tool.execution_complete
    :copilot/subagent.started :copilot/subagent.completed :copilot/subagent.failed :copilot/subagent.selected
    :copilot/skill.invoked
    :copilot/hook.start :copilot/hook.end
    :copilot/system.message})

;; Session events
(s/def ::session.start-data
  (s/keys :req-un [::session-id]
          :opt-un [::version ::producer ::copilot-version ::start-time ::selected-model]))

(s/def ::session.error-data
  (s/keys :req-un [::error-type ::message]
          :opt-un [::stack]))

(s/def ::session.idle-data map?)

(s/def ::user.message-data
  (s/keys :req-un [::content]
          :opt-un [::transformed-content ::attachments ::source]))

(s/def ::assistant.message-data
  (s/keys :req-un [::message-id ::content]
          :opt-un [::tool-requests ::parent-tool-call-id]))

(s/def ::assistant.message_delta-data
  (s/keys :req-un [::message-id ::delta-content]
          :opt-un [::total-response-size-bytes ::parent-tool-call-id]))

(s/def ::tool.execution_start-data
  (s/keys :req-un [::tool-call-id ::tool-name]
          :opt-un [::arguments ::parent-tool-call-id]))

(s/def ::progress-message string?)

(s/def ::tool.execution_progress-data
  (s/keys :req-un [::tool-call-id ::progress-message]))

(s/def ::tool.execution_complete-data
  (s/keys :req-un [::tool-call-id ::success?]
          :opt-un [::is-user-requested? ::result ::error ::tool-telemetry ::parent-tool-call-id]))

;; Session shutdown event
(s/def ::shutdown-type #{"routine" "error"})
(s/def ::error-reason string?)
(s/def ::total-premium-requests nat-int?)
(s/def ::total-api-duration-ms nat-int?)
(s/def ::session-start-time number?)
(s/def ::code-changes map?)
(s/def ::model-metrics map?)
(s/def ::current-model string?)

(s/def ::session.shutdown-data
  (s/keys :req-un [::shutdown-type ::total-premium-requests ::total-api-duration-ms
                   ::session-start-time ::code-changes ::model-metrics]
          :opt-un [::error-reason ::current-model]))

;; Session title changed event
(s/def ::title string?)
(s/def ::session.title_changed-data
  (s/keys :req-un [::title]))

;; Session warning event
(s/def ::warning-type string?)
(s/def ::session.warning-data
  (s/keys :req-un [::warning-type ::message]))

;; Session context changed event
(s/def ::session.context_changed-data
  (s/keys :req-un [::cwd]
          :opt-un [::git-root ::repository ::branch]))

;; Skill invoked event
(s/def ::allowed-tools (s/coll-of string?))

(s/def ::skill.invoked-data
  (s/keys :req-un [::name ::path ::content]
          :opt-un [::allowed-tools]))

;; Generic session event
(s/def ::session-event
  (s/merge ::base-event
           (s/keys :req-un [::event-type ::data])))

;; -----------------------------------------------------------------------------
;; Tool call/result types
;; -----------------------------------------------------------------------------

(s/def ::tool-call-id ::non-blank-string)
(s/def ::result-type
  (s/or :keyword #{:success :failure :rejected :denied}
        :string #{"success" "failure" "rejected" "denied"}))
(s/def ::text-result-for-llm string?)
(s/def ::session-log string?)
(s/def ::tool-telemetry map?)

(s/def ::tool-result-object
  (s/keys :req-un [::text-result-for-llm ::result-type]
          :opt-un [::binary-results-for-llm ::error ::session-log ::tool-telemetry]))

(s/def ::tool-result
  (s/or :string string?
        :object ::tool-result-object))

;; -----------------------------------------------------------------------------
;; Permission types
;; -----------------------------------------------------------------------------

(s/def ::permission-kind #{:shell :write :mcp :read :url})

(s/def ::permission-request
  (s/keys :req-un [::permission-kind]
          :opt-un [::tool-call-id]))

(s/def ::permission-result-kind
  #{:approved
    :denied-by-rules
    :denied-no-approval-rule-and-could-not-request-from-user
    :denied-interactively-by-user})

(s/def ::permission-result
  (s/keys :req-un [::permission-result-kind]
          :opt-un [::rules]))

;; -----------------------------------------------------------------------------
;; Client record spec
;; -----------------------------------------------------------------------------

(s/def ::options map?)
(s/def ::external-server? boolean?)
(s/def ::actual-host string?)
(s/def ::state #(instance? clojure.lang.Atom %))

(s/def ::client
  (s/keys :req-un [::options ::state]
          :opt-un [::external-server? ::actual-host]))

;; -----------------------------------------------------------------------------
;; Session record spec
;; -----------------------------------------------------------------------------

(s/def ::session
  (s/keys :req-un [::session-id ::client]
          :opt-un [::workspace-path]))

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

;; Model capabilities
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

(s/def ::model-capabilities
  (s/keys :opt-un [::model-supports ::model-limits]))

;; Model policy
(s/def ::policy-state #{"enabled" "disabled" "unconfigured"})
(s/def ::terms string?)
(s/def ::model-policy
  (s/keys :opt-un [::policy-state ::terms]))

;; Model billing
(s/def ::multiplier number?)
(s/def ::model-billing
  (s/keys :opt-un [::multiplier]))

;; Supported reasoning efforts
(s/def ::supported-reasoning-efforts (s/coll-of string?))
(s/def ::default-reasoning-effort string?)

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
                   ::vision-limits]))

;; Misc specs for instrument.clj
(s/def ::message-id string?)
(s/def ::events-ch any?)  ; core.async channel
(s/def ::buffer pos-int?)
(s/def ::xf fn?)
(s/def ::max-events pos-int?)

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

(s/def ::model-id (s/nilable string?))
