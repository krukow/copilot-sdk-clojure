(ns krukow.copilot-sdk.specs
  "Clojure specs for Copilot SDK data structures."
  (:require [clojure.spec.alpha :as s]))

;; -----------------------------------------------------------------------------
;; Common specs
;; -----------------------------------------------------------------------------

(s/def ::non-blank-string (s/and string? (complement clojure.string/blank?)))
(s/def ::timestamp string?)
(s/def ::session-id ::non-blank-string)
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

(s/def ::client-options
  (s/keys :opt-un [::cli-path ::cli-args ::cli-url ::cwd ::port
                   ::use-stdio? ::log-level ::auto-start? ::auto-restart?
                   ::notification-queue-size ::router-queue-size
                   ::tool-timeout-ms ::env]))

;; -----------------------------------------------------------------------------
;; Tool definitions
;; -----------------------------------------------------------------------------

(s/def ::tool-name ::non-blank-string)
(s/def ::tool-description string?)
(s/def ::json-schema map?)
(s/def ::tool-parameters ::json-schema)
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
(s/def ::mode ::system-message-mode)
(s/def ::content ::system-message-content)

(s/def ::system-message
  (s/keys :opt-un [::mode ::content]))

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

(s/def ::session-config
  (s/keys :opt-un [::session-id ::model ::tools ::system-message
                   ::available-tools ::excluded-tools ::provider
                   ::on-permission-request ::streaming? ::mcp-servers
                   ::custom-agents ::config-dir ::skill-directories
                   ::disabled-skills ::large-output]))

(s/def ::resume-session-config
  (s/keys :opt-un [::tools ::provider ::streaming? ::on-permission-request
                   ::mcp-servers ::custom-agents ::skill-directories
                   ::disabled-skills]))

;; -----------------------------------------------------------------------------
;; Message options
;; -----------------------------------------------------------------------------

(s/def ::prompt ::non-blank-string)
(s/def ::attachment-type #{:file :directory})
(s/def ::type ::attachment-type)
(s/def ::path ::non-blank-string)
(s/def ::display-name string?)

(s/def ::attachment
  (s/keys :req-un [::type ::path]
          :opt-un [::display-name]))

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

(s/def ::session-metadata
  (s/keys :req-un [::session-id ::start-time ::modified-time ::remote?]
          :opt-un [::summary]))

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

;; Event type enum
(s/def ::event-type
  #{:session.start :session.resume :session.error :session.idle :session.info
    :session.model_change :session.handoff :session.truncation :session.usage_info
    :session.compaction_start :session.compaction_complete
    :user.message :pending_messages.modified
    :assistant.turn_start :assistant.intent :assistant.reasoning :assistant.reasoning_delta
    :assistant.message :assistant.message_delta :assistant.turn_end :assistant.usage
    :abort
    :tool.user_requested :tool.execution_start :tool.execution_partial_result
    :tool.execution_progress :tool.execution_complete
    :subagent.started :subagent.completed :subagent.failed :subagent.selected
    :hook.start :hook.end
    :system.message})

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
  (s/keys :req-un [::session-id ::client]))
