(ns github.copilot-sdk.instrument
  "Spec instrumentation for development/testing.
   
   Require this namespace to enable spec checking on public API functions.
   This adds runtime validation which is useful during development but may
   impact performance in production.
   
   Usage:
     (require '[github.copilot-sdk.instrument])
     ;; Now all public API calls are spec-checked
   
   To disable:
     (require '[clojure.spec.test.alpha :as stest])
     (stest/unstrument)"
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [github.copilot-sdk.factory :as factory]
            [github.copilot-sdk.helpers]
            [github.copilot-sdk.specs :as specs]
            ;; Ensure namespaces hosting public fns referenced by `register-fdef!`
            ;; are loaded before `stest/instrument` runs (otherwise the missing
            ;; var would be silently skipped, leaving an instrumentation gap).
            [github.copilot-sdk.tool-set]
            [github.copilot-sdk.tools]))

;; -----------------------------------------------------------------------------
;; Single-source registry for fdefs
;; -----------------------------------------------------------------------------
;;
;; All public-API fdefs in this namespace are declared via `register-fdef!`,
;; which both registers the spec (delegating to `s/fdef`) and records the
;; fully-qualified symbol in `registered-fdefs`. `instrument-all!` and
;; `unstrument-all!` derive their target symbol set from this single registry,
;; eliminating the previous triplication (one fdef + two parallel symbol
;; lists). Generated fdefs (planned for future schema-driven codegen) use the
;; same macro by construction.

(def ^:private registered-fdefs
  "Set of fully-qualified symbols whose fdefs were declared via
   `register-fdef!`. `def` (not `defonce`) so REPL reloads of this namespace
   reset and repopulate the registry deterministically."
  (atom #{}))

(defmacro ^:private register-fdef!
  "Like `s/fdef` but also records the fully-qualified symbol in
   `registered-fdefs`. Returns whatever `s/fdef` returns. Throws at
   macroexpansion time if `sym` is not fully qualified, so a stale or
   accidentally unqualified symbol is caught immediately rather than silently
   leaving an instrumentation gap."
  [sym & body]
  (when-not (and (symbol? sym) (namespace sym))
    (throw (ex-info "register-fdef! requires a fully-qualified symbol"
                    {:sym sym})))
  ;; Reject alias-qualified symbols (e.g. `client/start!` where `client` is
  ;; an `(:require [... :as client])` alias). `s/fdef` would resolve the
  ;; alias when registering the spec, but `'~sym` below records the
  ;; UNRESOLVED alias-qualified symbol; `stest/instrument` then receives a
  ;; symbol that doesn't match any var and silently skips it — exactly the
  ;; instrumentation gap this macro is meant to prevent. Insist on the full
  ;; namespace name.
  (when (contains? (ns-aliases *ns*) (symbol (namespace sym)))
    (throw (ex-info "register-fdef! requires a fully-qualified namespace, not an alias"
                    {:sym sym :alias (namespace sym)})))
  `(let [ret# (s/fdef ~sym ~@body)]
     (swap! registered-fdefs conj '~sym)
     ret#))

;; -----------------------------------------------------------------------------
;; Function specs for client namespace
;; -----------------------------------------------------------------------------

(register-fdef! github.copilot-sdk.client/client
                :args (s/cat :opts (s/? ::specs/client-options))
                :ret ::specs/client)

(register-fdef! github.copilot-sdk.client/state
                :args (s/cat :client ::specs/client)
                :ret ::specs/connection-state)

(register-fdef! github.copilot-sdk.client/start!
                :args (s/cat :client ::specs/client)
                :ret nil?)

(register-fdef! github.copilot-sdk.client/approve-all
                :args (s/cat :request ::specs/permission-request
                             :ctx map?)
                :ret ::specs/permission-result)

(register-fdef! github.copilot-sdk.client/attributed-permission-result?
                :args (s/cat :result any?)
                :ret boolean?)

(register-fdef! github.copilot-sdk.client/attributed-permission-result
                :args (s/cat :result ::specs/permission-handler-result
                             :decision-context ::specs/permission-decision-context)
                :ret ::specs/attributed-permission-result)

(register-fdef! github.copilot-sdk/attributed-permission-result?
                :args (s/cat :result any?)
                :ret boolean?)

(register-fdef! github.copilot-sdk/attributed-permission-result
                :args (s/cat :result ::specs/permission-handler-result
                             :decision-context ::specs/permission-decision-context)
                :ret ::specs/attributed-permission-result)

(register-fdef! github.copilot-sdk.client/stop!
                :args (s/cat :client ::specs/client)
                :ret (s/coll-of any?))

(register-fdef! github.copilot-sdk.client/ping
                :args (s/cat :client ::specs/client
                             :message (s/? (s/nilable string?)))
                :ret (s/keys :opt-un [::specs/message ::specs/timestamp]))

(register-fdef! github.copilot-sdk.client/create-session
                :args (s/cat :client ::specs/client
                             :config ::specs/session-config)
                :ret ::specs/session)

(register-fdef! github.copilot-sdk.client/resume-session
                :args (s/cat :client ::specs/client
                             :session-id ::specs/session-id
                             :config ::specs/resume-session-config)
                :ret ::specs/session)

(register-fdef! github.copilot-sdk.client/<create-session
                :args (s/cat :client ::specs/client
                             :config ::specs/session-config)
                :ret ::specs/events-ch)

(register-fdef! github.copilot-sdk.client/<resume-session
                :args (s/cat :client ::specs/client
                             :session-id ::specs/session-id
                             :config ::specs/resume-session-config)
                :ret ::specs/events-ch)

(register-fdef! github.copilot-sdk.client/join-session
                :args (s/cat :config ::specs/join-session-config)
                :ret ::specs/join-session-result)

(register-fdef! github.copilot-sdk.client/list-sessions
                :args (s/cat :client ::specs/client
                             :filter (s/? (s/nilable ::specs/session-list-filter)))
                :ret (s/coll-of ::specs/session-metadata))

(register-fdef! github.copilot-sdk.client/get-session-metadata
                :args (s/cat :client ::specs/client
                             :session-id ::specs/session-id)
                :ret (s/nilable ::specs/session-metadata))

(register-fdef! github.copilot-sdk.client/delete-session!
                :args (s/cat :client ::specs/client
                             :session-id ::specs/session-id)
                :ret nil?)

(register-fdef! github.copilot-sdk.client/get-last-session-id
                :args (s/cat :client ::specs/client)
                :ret (s/nilable ::specs/session-id))

(register-fdef! github.copilot-sdk.client/get-foreground-session-id
                :args (s/cat :client ::specs/client)
                :ret (s/nilable ::specs/session-id))

(register-fdef! github.copilot-sdk.client/set-foreground-session-id!
                :args (s/cat :client ::specs/client :session-id ::specs/session-id)
                :ret nil?)

(register-fdef! github.copilot-sdk.client/get-status
                :args (s/cat :client ::specs/client)
                :ret (s/keys :req-un [::specs/version ::specs/protocol-version]))

(register-fdef! github.copilot-sdk.client/get-auth-status
                :args (s/cat :client ::specs/client)
                :ret (s/keys :req-un [::specs/authenticated?]
                             :opt-un [::specs/auth-type ::specs/host ::specs/login ::specs/status-message]))

(register-fdef! github.copilot-sdk.client/list-models
                :args (s/cat :client ::specs/client)
                :ret (s/coll-of ::specs/model-info))

(register-fdef! github.copilot-sdk.client/list-tools
                :args (s/cat :client ::specs/client
                             :model (s/? (s/nilable string?)))
                :ret (s/coll-of ::specs/tool-info-entry))

(register-fdef! github.copilot-sdk.client/get-quota
                :args (s/cat :client ::specs/client)
                :ret ::specs/quota-snapshots)

(register-fdef! github.copilot-sdk.client/force-stop!
                :args (s/cat :client ::specs/client)
                :ret any?)

(register-fdef! github.copilot-sdk.client/notifications
                :args (s/cat :client ::specs/client)
                :ret any?)  ; core.async channel

(register-fdef! github.copilot-sdk.client/on-lifecycle-event
                :args (s/alt :wildcard (s/cat :client ::specs/client
                                              :handler ::specs/lifecycle-handler)
                             :typed    (s/cat :client ::specs/client
                                              :event-type ::specs/lifecycle-event-type
                                              :handler ::specs/lifecycle-handler))
                :ret fn?)

;; -----------------------------------------------------------------------------
;; Function specs for session namespace
;; -----------------------------------------------------------------------------

(register-fdef! github.copilot-sdk.session/send!
                :args (s/cat :session ::specs/session
                             :opts ::specs/send-options)
                :ret string?)  ; message-id

(register-fdef! github.copilot-sdk.session/send-and-wait!
                :args (s/cat :session ::specs/session
                             :opts ::specs/send-options
                             :timeout-ms (s/? ::specs/timeout-ms))
                :ret (s/nilable map?))

(register-fdef! github.copilot-sdk.session/send-async
                :args (s/cat :session ::specs/session
                             :opts (s/merge ::specs/send-options
                                            (s/keys :opt-un [::specs/timeout-ms])))
                :ret any?)  ; core.async channel

(register-fdef! github.copilot-sdk.session/send-async-with-id
                :args (s/cat :session ::specs/session
                             :opts (s/merge ::specs/send-options
                                            (s/keys :opt-un [::specs/timeout-ms])))
                :ret (s/keys :req-un [::specs/message-id ::specs/events-ch]))

(register-fdef! github.copilot-sdk.session/<send!
                :args (s/cat :session ::specs/session
                             :opts (s/merge ::specs/send-options
                                            (s/keys :opt-un [::specs/timeout-ms])))
                :ret any?)  ; core.async channel

(register-fdef! github.copilot-sdk.session/<send-and-wait!
                :args (s/cat :session ::specs/session
                             :opts (s/merge ::specs/send-options
                                            (s/keys :opt-un [::specs/timeout-ms])))
                :ret any?)  ; core.async channel

(register-fdef! github.copilot-sdk.session/abort!
                :args (s/cat :session ::specs/session)
                :ret nil?)

(register-fdef! github.copilot-sdk.session/get-messages
                :args (s/cat :session ::specs/session)
                :ret (s/coll-of map?))

;; Upstream PR #1308: manual resolution of pending tool calls and permission
;; requests. Inner functions perform more thorough validation (e.g. permission
;; :kind enum, mutual exclusivity of :result/:error). The fdef only enforces
;; the universally-required :request-id; the rest is checked at call time so
;; the spec doesn't have to mirror the full result/decision shape here.
(register-fdef! github.copilot-sdk.session/handle-pending-tool-call!
                :args (s/cat :session ::specs/session
                             :opts (s/keys :req-un [::specs/request-id]))
                :ret any?)

(register-fdef! github.copilot-sdk.session/<handle-pending-tool-call!
                :args (s/cat :session ::specs/session
                             :opts (s/keys :req-un [::specs/request-id]))
                :ret any?)

(register-fdef! github.copilot-sdk.session/handle-pending-permission-request!
                :args (s/cat :session ::specs/session
                             :opts (s/keys :req-un [::specs/request-id]))
                :ret any?)

(register-fdef! github.copilot-sdk.session/<handle-pending-permission-request!
                :args (s/cat :session ::specs/session
                             :opts (s/keys :req-un [::specs/request-id]))
                :ret any?)

(register-fdef! github.copilot-sdk.session/destroy!
                :args (s/alt :handle (s/cat :session ::specs/session)
                             :explicit (s/cat :client ::specs/client
                                              :session-id ::specs/session-id))
                :ret nil?)

(register-fdef! github.copilot-sdk.session/disconnect!
                :args (s/alt :handle (s/cat :session ::specs/session)
                             :explicit (s/cat :client ::specs/client
                                              :session-id ::specs/session-id))
                :ret nil?)

(register-fdef! github.copilot-sdk.session/session-id
                :args (s/cat :session ::specs/session)
                :ret ::specs/session-id)

(register-fdef! github.copilot-sdk.session/workspace-path
                :args (s/cat :session ::specs/session)
                :ret ::specs/workspace-path)

(register-fdef! github.copilot-sdk.session/events
                :args (s/cat :session ::specs/session)
                :ret any?)  ; core.async mult

(register-fdef! github.copilot-sdk.session/subscribe-events
                :args (s/cat :session ::specs/session)
                :ret any?)  ; core.async channel

(register-fdef! github.copilot-sdk.session/unsubscribe-events!
                :args (s/cat :session ::specs/session
                             :ch any?)
                :ret nil?)

(register-fdef! github.copilot-sdk.session/events->chan
                :args (s/cat :session ::specs/session
                             :opts (s/? (s/keys :opt-un [::specs/buffer ::specs/xf])))
                :ret any?)  ; core.async channel

(register-fdef! github.copilot-sdk.session/get-current-model
                :args (s/cat :session ::specs/session)
                :ret ::specs/model-id)

(s/def ::context-tier (s/nilable ::specs/context-tier))
(s/def ::model-switch-options
  (s/keys :opt-un [::specs/reasoning-effort
                   ::specs/reasoning-summary
                   ::context-tier
                   ::specs/model-capabilities]))

(register-fdef! github.copilot-sdk.session/switch-model!
                :args (s/cat :session ::specs/session
                             :model-id string?
                             :opts (s/? (s/nilable ::model-switch-options)))
                :ret (s/nilable ::specs/model-id))

(register-fdef! github.copilot-sdk.session/set-model!
                :args (s/cat :session ::specs/session
                             :model-id string?
                             :opts (s/? (s/nilable ::model-switch-options)))
                :ret (s/nilable ::specs/model-id))

(register-fdef! github.copilot-sdk.session/log!
                :args (s/cat :session ::specs/session
                             :message string?
                             :opts (s/? (s/nilable ::specs/log-options)))
                :ret ::specs/event-id)

(register-fdef! github.copilot-sdk.session/create-session-fs-adapter
                :args (s/cat :provider ::specs/session-fs-provider)
                :ret ::specs/session-fs-handler)

(register-fdef! github.copilot-sdk.session/session-fs-sqlite-transaction-failure
                :args (s/cat :message string?
                             :error-class (s/? ::specs/sqlite-transaction-error-class))
                :ret #(instance? clojure.lang.ExceptionInfo %))

(register-fdef! github.copilot-sdk.session/session-fs-sqlite-transaction-failure?
                :args (s/cat :value any?)
                :ret boolean?)

(register-fdef! github.copilot-sdk.session/adapt-session-fs-handler
                :args (s/cat :handler-or-provider (s/or :handler ::specs/session-fs-handler
                                                        :provider ::specs/session-fs-provider))
                :ret ::specs/session-fs-handler)

;; -- Experimental RPC method specs -------------------------------------------

(register-fdef! github.copilot-sdk.session/skills-list
                :args (s/cat :session ::specs/session)
                :ret map?)

(register-fdef! github.copilot-sdk.session/skills-enable!
                :args (s/cat :session ::specs/session :skill-name string?)
                :ret any?)

(register-fdef! github.copilot-sdk.session/skills-disable!
                :args (s/cat :session ::specs/session :skill-name string?)
                :ret any?)

(register-fdef! github.copilot-sdk.session/skills-reload!
                :args (s/cat :session ::specs/session)
                :ret any?)

(register-fdef! github.copilot-sdk.session/respond-to-queued-command!
                :args (s/cat :session ::specs/session
                             :params (s/and
                                      (s/keys :req-un [::specs/request-id ::specs/handled?]
                                              :opt-un [::specs/stop-processing-queue?])
                        ;; Wire schema: stopProcessingQueue is only valid on the
                        ;; handled=true branch (QueuedCommandHandled). Reject
                        ;; stop-processing-queue? alongside handled?=false.
                                      #(or (:handled? %)
                                           (not (contains? % :stop-processing-queue?)))))
                :ret any?)

(register-fdef! github.copilot-sdk.session/mcp-list
                :args (s/cat :session ::specs/session)
                :ret map?)

(register-fdef! github.copilot-sdk.session/mcp-enable!
                :args (s/cat :session ::specs/session :server-name string?)
                :ret any?)

(register-fdef! github.copilot-sdk.session/mcp-disable!
                :args (s/cat :session ::specs/session :server-name string?)
                :ret any?)

(register-fdef! github.copilot-sdk.session/mcp-reload!
                :args (s/cat :session ::specs/session)
                :ret any?)

(register-fdef! github.copilot-sdk.session/extensions-list
                :args (s/cat :session ::specs/session)
                :ret map?)

(register-fdef! github.copilot-sdk.session/extensions-enable!
                :args (s/cat :session ::specs/session :extension-id string?)
                :ret any?)

(register-fdef! github.copilot-sdk.session/extensions-disable!
                :args (s/cat :session ::specs/session :extension-id string?)
                :ret any?)

(register-fdef! github.copilot-sdk.session/extensions-reload!
                :args (s/cat :session ::specs/session)
                :ret any?)

(register-fdef! github.copilot-sdk.session/plugins-list
                :args (s/cat :session ::specs/session)
                :ret map?)

(register-fdef! github.copilot-sdk.session/compaction-compact!
                :args (s/cat :session ::specs/session)
                :ret map?)

(register-fdef! github.copilot-sdk.session/history-truncate!
                :args (s/cat :session ::specs/session)
                :ret map?)

(register-fdef! github.copilot-sdk.session/history-clear-context!
                :args (s/cat :session ::specs/session
                             :prompt ::specs/non-blank-string)
                :ret (s/keys :req-un [::specs/messages-cleared]))

;; -----------------------------------------------------------------------------
;; Function specs for Agent Factories
;; -----------------------------------------------------------------------------

(register-fdef! github.copilot-sdk.factory/define-factory
                :args (s/cat :definition map?)
                :ret factory/factory-handle?)

(register-fdef! github.copilot-sdk.factory/factory-handle?
                :args (s/cat :value any?)
                :ret boolean?)

(register-fdef! github.copilot-sdk.factory/factory-meta
                :args (s/cat :handle factory/factory-handle?)
                :ret map?)

(register-fdef! github.copilot-sdk.factory/factory-run-function
                :args (s/cat :handle factory/factory-handle?)
                :ret fn?)

(register-fdef! github.copilot-sdk.factory/definitions-by-name
                :args (s/cat :handles (s/nilable sequential?))
                :ret map?)

(register-fdef! github.copilot-sdk.factory/terminal-status?
                :args (s/cat :status (s/or :keyword keyword? :string string?))
                :ret boolean?)

(register-fdef! github.copilot-sdk.factory/run!
                :args (s/cat :session ::specs/session
                             :identifier any?
                             :options (s/? map?))
                :ret map?)

(register-fdef! github.copilot-sdk.factory/resume!
                :args (s/cat :session ::specs/session
                             :run-id ::specs/run-id
                             :options (s/? map?))
                :ret map?)

(register-fdef! github.copilot-sdk.factory/wait-for-run!
                :args (s/cat :session ::specs/session
                             :run-id ::specs/run-id
                             :options (s/? map?))
                :ret map?)

(register-fdef! github.copilot-sdk.factory/<run!
                :args (s/cat :session ::specs/session
                             :identifier any?
                             :options (s/? map?))
                :ret ::specs/events-ch)

(register-fdef! github.copilot-sdk.factory/<resume!
                :args (s/cat :session ::specs/session
                             :run-id ::specs/run-id
                             :options (s/? map?))
                :ret ::specs/events-ch)

(register-fdef! github.copilot-sdk.factory/<wait-for-run!
                :args (s/cat :session ::specs/session
                             :run-id ::specs/run-id
                             :options (s/? map?))
                :ret ::specs/events-ch)

(register-fdef! github.copilot-sdk.factory/get-run
                :args (s/cat :session ::specs/session :run-id ::specs/run-id)
                :ret map?)

(register-fdef! github.copilot-sdk.factory/get-run-detail
                :args (s/cat :session ::specs/session :run-id ::specs/run-id)
                :ret map?)

(register-fdef! github.copilot-sdk.factory/cancel!
                :args (s/cat :session ::specs/session :run-id ::specs/run-id)
                :ret map?)

(register-fdef! github.copilot-sdk.factory/<get-run
                :args (s/cat :session ::specs/session :run-id ::specs/run-id)
                :ret ::specs/events-ch)

(register-fdef! github.copilot-sdk.factory/<get-run-detail
                :args (s/cat :session ::specs/session :run-id ::specs/run-id)
                :ret ::specs/events-ch)

(register-fdef! github.copilot-sdk.factory/<cancel!
                :args (s/cat :session ::specs/session :run-id ::specs/run-id)
                :ret ::specs/events-ch)

(register-fdef! github.copilot-sdk.factory/list-runs
                :args (s/cat :session ::specs/session)
                :ret vector?)

(register-fdef! github.copilot-sdk.factory/<list-runs
                :args (s/cat :session ::specs/session)
                :ret ::specs/events-ch)

(register-fdef! github.copilot-sdk.factory/get-run-progress
                :args (s/cat :session ::specs/session
                             :run-id ::specs/run-id
                             :options (s/? map?))
                :ret map?)

(register-fdef! github.copilot-sdk.factory/<get-run-progress
                :args (s/cat :session ::specs/session
                             :run-id ::specs/run-id
                             :options (s/? map?))
                :ret ::specs/events-ch)

(register-fdef! github.copilot-sdk.session/sessions-fork!
                :args (s/cat :session ::specs/session)
                :ret map?)

(register-fdef! github.copilot-sdk.session/shell-exec!
                :args (s/cat :session ::specs/session :command string?)
                :ret map?)

(register-fdef! github.copilot-sdk.session/shell-kill!
                :args (s/cat :session ::specs/session :process-id string?)
                :ret any?)

(register-fdef! github.copilot-sdk.session/ui-elicitation!
                :args (s/cat :session ::specs/session :params ::specs/elicitation-params)
                :ret map?)

(register-fdef! github.copilot-sdk.session/capabilities
                :args (s/cat :session ::specs/session)
                :ret map?)

(register-fdef! github.copilot-sdk.session/open-canvases
                :args (s/cat :session ::specs/session)
                :ret ::specs/open-canvases)

(register-fdef! github.copilot-sdk.session/elicitation-supported?
                :args (s/cat :session ::specs/session)
                :ret boolean?)

(register-fdef! github.copilot-sdk.session/confirm!
                :args (s/cat :session ::specs/session :message string?)
                :ret boolean?)

(register-fdef! github.copilot-sdk.session/select!
                :args (s/cat :session ::specs/session :message string? :options (s/coll-of string?))
                :ret (s/nilable string?))

(register-fdef! github.copilot-sdk.session/input!
                :args (s/cat :session ::specs/session :message string?
                             :opts (s/? (s/nilable ::specs/input-options)))
                :ret (s/nilable string?))

;; -----------------------------------------------------------------------------
;; Function specs for helpers namespace
;; -----------------------------------------------------------------------------

(s/def ::helper-client
  (s/or :instance ::specs/client
        :options ::specs/client-options))

(s/def ::helper-session
  (s/or :instance ::specs/session
        :config ::specs/session-config))

(defn- options-valid?
  [options option-specs]
  (and (map? options)
       (every? (fn [[key spec]]
                 (or (not (contains? options key))
                     (s/valid? spec (get options key))))
               option-specs)))

(defn- query-options?
  [options]
  (options-valid? options
                  {:client ::helper-client
                   :session ::helper-session
                   :timeout-ms ::specs/timeout-ms}))

(defn- query-seq-options?
  [options]
  (options-valid? options
                  {:client ::helper-client
                   :session ::specs/session-config
                   :max-events ::specs/max-events
                   :timeout-ms ::specs/timeout-ms}))

(defn- query-chan-options?
  [options]
  (options-valid? options
                  {:client ::specs/client-options
                   :session ::specs/session-config
                   :buffer ::specs/buffer}))

(s/def ::helper-query-options query-options?)
(s/def ::helper-query-seq-options query-seq-options?)
(s/def ::helper-query-chan-options query-chan-options?)

(register-fdef! github.copilot-sdk.helpers/query
                :args (s/cat :prompt string?
                             :opts (s/? (s/& (s/keys*) ::helper-query-options)))
                :ret (s/nilable string?))

(register-fdef! github.copilot-sdk.helpers/query-seq-source
                :args (s/cat :prompt string?
                             :opts (s/? (s/& (s/keys*) ::helper-query-seq-options)))
                :ret (s/tuple seqable? ifn?))

(register-fdef! github.copilot-sdk.helpers/query-seq!
                :args (s/cat :prompt string?
                             :opts (s/? (s/& (s/keys*) ::helper-query-seq-options)))
                :ret seqable?)

(register-fdef! github.copilot-sdk.helpers/query-chan
                :args (s/cat :prompt string?
                             :opts (s/? (s/& (s/keys*) ::helper-query-chan-options)))
                :ret any?)  ; core.async channel

(register-fdef! github.copilot-sdk.helpers/shutdown!
                :args (s/cat)
                :ret nil?)

;; -----------------------------------------------------------------------------
;; Session RPC wrapper function specs (experimental)
;; -----------------------------------------------------------------------------

(register-fdef! github.copilot-sdk.session/mode-get
                :args (s/cat :session ::specs/session)
                :ret map?)

(register-fdef! github.copilot-sdk.session/mode-set!
                :args (s/cat :session ::specs/session :mode string?)
                :ret map?)

(register-fdef! github.copilot-sdk.session/plan-read
                :args (s/cat :session ::specs/session)
                :ret map?)

(register-fdef! github.copilot-sdk.session/plan-update!
                :args (s/cat :session ::specs/session :content string?)
                :ret map?)

(register-fdef! github.copilot-sdk.session/plan-delete!
                :args (s/cat :session ::specs/session)
                :ret map?)

(register-fdef! github.copilot-sdk.session/workspace-list-files
                :args (s/cat :session ::specs/session)
                :ret map?)

(register-fdef! github.copilot-sdk.session/workspace-read-file
                :args (s/cat :session ::specs/session :path string?)
                :ret map?)

(register-fdef! github.copilot-sdk.session/workspace-create-file!
                :args (s/cat :session ::specs/session :path string? :content string?)
                :ret map?)

(register-fdef! github.copilot-sdk.session/agent-list
                :args (s/cat :session ::specs/session)
                :ret map?)

(register-fdef! github.copilot-sdk.session/agent-get-current
                :args (s/cat :session ::specs/session)
                :ret map?)

(register-fdef! github.copilot-sdk.session/agent-select!
                :args (s/cat :session ::specs/session :agent-name string?)
                :ret map?)

(register-fdef! github.copilot-sdk.session/agent-deselect!
                :args (s/cat :session ::specs/session)
                :ret map?)

(register-fdef! github.copilot-sdk.session/agent-reload!
                :args (s/cat :session ::specs/session)
                :ret map?)

(register-fdef! github.copilot-sdk.session/fleet-start!
                :args (s/cat :session ::specs/session :params map?)
                :ret map?)

;; Session name RPC function specs (upstream CLI 1.0.26, PR #1076)
(register-fdef! github.copilot-sdk.session/session-name-get
                :args (s/cat :session ::specs/session)
                :ret map?)

(register-fdef! github.copilot-sdk.session/session-name-set!
                :args (s/cat :session ::specs/session :name string?)
                :ret map?)

;; Workspace extended RPC function specs (upstream CLI 1.0.26, PR #1076)
(register-fdef! github.copilot-sdk.session/workspace-get-workspace
                :args (s/cat :session ::specs/session)
                :ret map?)

;; MCP discovery RPC function spec (upstream CLI 1.0.22, PR #1055)
(register-fdef! github.copilot-sdk.session/mcp-discover
                :args (s/cat :session ::specs/session :opts (s/? map?))
                :ret map?)

;; Usage metrics RPC function spec (upstream CLI 1.0.22, PR #1055)
(register-fdef! github.copilot-sdk.session/usage-get-metrics
                :args (s/cat :session ::specs/session)
                :ret map?)

;; Remote sessions RPC function specs (upstream PR #1192)
(register-fdef! github.copilot-sdk.session/remote-enable
                :args (s/cat :session ::specs/session
                             :opts (s/? (s/nilable ::specs/remote-enable-opts)))
                :ret ::specs/remote-enable-result)

(register-fdef! github.copilot-sdk.session/remote-disable
                :args (s/cat :session ::specs/session)
                :ret nil?)

;; Tool helper function specs
(register-fdef! github.copilot-sdk.tools/define-tool
                :args (s/cat :name ::specs/tool-name
                             :opts map?)
                :ret ::specs/tool)

(register-fdef! github.copilot-sdk.tools/define-tool-from-spec
                :args (s/cat :name ::specs/tool-name
                             :opts map?)
                :ret ::specs/tool)

(def ^:private result-success-args
  (s/alt :text (s/cat :text string?)
         :text+telemetry (s/cat :text string? :telemetry ::specs/tool-telemetry)))

(def ^:private result-failure-args
  (s/alt :text (s/cat :text string?)
         :text+error (s/cat :text string? :error (s/nilable string?))
         :text+error+telemetry
         (s/cat :text string?
                :error (s/nilable string?)
                :telemetry ::specs/tool-telemetry)))

(register-fdef! github.copilot-sdk.tools/result-success
                :args result-success-args
                :ret ::specs/tool-result-object)
(register-fdef! github.copilot-sdk/result-success
                :args result-success-args
                :ret ::specs/tool-result-object)
(register-fdef! github.copilot-sdk.tools/result-denied
                :args result-success-args
                :ret ::specs/tool-result-object)
(register-fdef! github.copilot-sdk/result-denied
                :args result-success-args
                :ret ::specs/tool-result-object)
(register-fdef! github.copilot-sdk.tools/result-rejected
                :args result-success-args
                :ret ::specs/tool-result-object)
(register-fdef! github.copilot-sdk/result-rejected
                :args result-success-args
                :ret ::specs/tool-result-object)
(register-fdef! github.copilot-sdk.tools/result-failure
                :args result-failure-args
                :ret ::specs/tool-result-object)
(register-fdef! github.copilot-sdk/result-failure
                :args result-failure-args
                :ret ::specs/tool-result-object)

;; convert-mcp-call-tool-result function spec (upstream PR #1049)
(register-fdef! github.copilot-sdk.tools/convert-mcp-call-tool-result
                :args (s/cat :call-result map?)
                :ret ::specs/tool-result-object)

;; Client-level MCP config function specs
(register-fdef! github.copilot-sdk.client/mcp-config-list
                :args (s/cat :client ::specs/client)
                :ret map?)

(register-fdef! github.copilot-sdk.client/mcp-config-add!
                :args (s/cat :client ::specs/client :params map?)
                :ret map?)

(register-fdef! github.copilot-sdk.client/mcp-config-update!
                :args (s/cat :client ::specs/client :params map?)
                :ret map?)

(register-fdef! github.copilot-sdk.client/mcp-config-remove!
                :args (s/cat :client ::specs/client :params map?)
                :ret map?)

;; -----------------------------------------------------------------------------
;; Tool filter helpers (upstream PR #1428)
;; -----------------------------------------------------------------------------

(register-fdef! github.copilot-sdk.tool-set/valid-name?
                :args (s/cat :name any?)
                :ret boolean?)

(register-fdef! github.copilot-sdk.tool-set/builtin
                :args (s/cat :name string?)
                :ret string?)

(register-fdef! github.copilot-sdk.tool-set/mcp
                :args (s/cat :name string?)
                :ret string?)

(register-fdef! github.copilot-sdk.tool-set/custom
                :args (s/cat :name string?)
                :ret string?)

(register-fdef! github.copilot-sdk.tool-set/builtins
                :args (s/cat :names (s/coll-of string?))
                :ret (s/coll-of string? :kind vector?))

;; -----------------------------------------------------------------------------
;; Instrument all public API functions
;; -----------------------------------------------------------------------------

(defn instrument-all!
  "Instrument every fdef registered via `register-fdef!`."
  []
  (stest/instrument (vec @registered-fdefs)))

(defn unstrument-all!
  "Remove instrumentation from every fdef registered via `register-fdef!`."
  []
  (stest/unstrument (vec @registered-fdefs)))

;; Auto-instrument when this namespace is loaded
(instrument-all!)
