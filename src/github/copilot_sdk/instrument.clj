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
            [github.copilot-sdk.specs :as specs]))

;; -----------------------------------------------------------------------------
;; Function specs for client namespace
;; -----------------------------------------------------------------------------

(s/fdef github.copilot-sdk.client/client
  :args (s/cat :opts (s/? ::specs/client-options))
  :ret ::specs/client)

(s/fdef github.copilot-sdk.client/state
  :args (s/cat :client ::specs/client)
  :ret ::specs/connection-state)

(s/fdef github.copilot-sdk.client/start!
  :args (s/cat :client ::specs/client)
  :ret nil?)

(s/fdef github.copilot-sdk.client/stop!
  :args (s/cat :client ::specs/client)
  :ret (s/coll-of any?))

(s/fdef github.copilot-sdk.client/ping
  :args (s/cat :client ::specs/client
               :message (s/? (s/nilable string?)))
  :ret (s/keys :opt-un [::specs/message ::specs/timestamp]))

(s/fdef github.copilot-sdk.client/create-session
  :args (s/cat :client ::specs/client
               :config (s/? ::specs/session-config))
  :ret ::specs/session)

(s/fdef github.copilot-sdk.client/resume-session
  :args (s/cat :client ::specs/client
               :session-id ::specs/session-id
               :config (s/? ::specs/resume-session-config))
  :ret ::specs/session)

(s/fdef github.copilot-sdk.client/list-sessions
  :args (s/cat :client ::specs/client
               :filter (s/? (s/nilable ::specs/session-list-filter)))
  :ret (s/coll-of ::specs/session-metadata))

(s/fdef github.copilot-sdk.client/delete-session!
  :args (s/cat :client ::specs/client
               :session-id ::specs/session-id)
  :ret nil?)

(s/fdef github.copilot-sdk.client/get-last-session-id
  :args (s/cat :client ::specs/client)
  :ret (s/nilable ::specs/session-id))

(s/fdef github.copilot-sdk.client/get-foreground-session-id
  :args (s/cat :client ::specs/client)
  :ret (s/nilable ::specs/session-id))

(s/fdef github.copilot-sdk.client/set-foreground-session-id!
  :args (s/cat :client ::specs/client :session-id ::specs/session-id)
  :ret nil?)

(s/fdef github.copilot-sdk.client/get-status
  :args (s/cat :client ::specs/client)
  :ret (s/keys :req-un [::specs/version ::specs/protocol-version]))

(s/fdef github.copilot-sdk.client/get-auth-status
  :args (s/cat :client ::specs/client)
  :ret (s/keys :req-un [::specs/authenticated?]
               :opt-un [::specs/auth-type ::specs/host ::specs/login ::specs/status-message]))

(s/fdef github.copilot-sdk.client/list-models
  :args (s/cat :client ::specs/client)
  :ret (s/coll-of ::specs/model-info))

(s/fdef github.copilot-sdk.client/list-tools
  :args (s/cat :client ::specs/client
               :model (s/? (s/nilable string?)))
  :ret (s/coll-of ::specs/tool-info-entry))

(s/fdef github.copilot-sdk.client/get-quota
  :args (s/cat :client ::specs/client)
  :ret ::specs/quota-snapshots)

(s/fdef github.copilot-sdk.client/force-stop!
  :args (s/cat :client ::specs/client)
  :ret any?)

(s/fdef github.copilot-sdk.client/notifications
  :args (s/cat :client ::specs/client)
  :ret any?)  ; core.async channel

(s/fdef github.copilot-sdk.client/on-lifecycle-event
  :args (s/alt :wildcard (s/cat :client ::specs/client
                                :handler ::specs/lifecycle-handler)
               :typed    (s/cat :client ::specs/client
                                :event-type ::specs/lifecycle-event-type
                                :handler ::specs/lifecycle-handler))
  :ret fn?)

;; -----------------------------------------------------------------------------
;; Function specs for session namespace
;; -----------------------------------------------------------------------------

(s/fdef github.copilot-sdk.session/send!
  :args (s/cat :session ::specs/session
               :opts ::specs/send-options)
  :ret string?)  ; message-id

(s/fdef github.copilot-sdk.session/send-and-wait!
  :args (s/cat :session ::specs/session
               :opts ::specs/send-options
               :timeout-ms (s/? ::specs/timeout-ms))
  :ret (s/nilable map?))

(s/fdef github.copilot-sdk.session/send-async
  :args (s/cat :session ::specs/session
               :opts (s/merge ::specs/send-options
                              (s/keys :opt-un [::specs/timeout-ms])))
  :ret any?)  ; core.async channel

(s/fdef github.copilot-sdk.session/send-async-with-id
  :args (s/cat :session ::specs/session
               :opts (s/merge ::specs/send-options
                              (s/keys :opt-un [::specs/timeout-ms])))
  :ret (s/keys :req-un [::specs/message-id ::specs/events-ch]))

(s/fdef github.copilot-sdk.session/<send!
  :args (s/cat :session ::specs/session
               :opts (s/merge ::specs/send-options
                              (s/keys :opt-un [::specs/timeout-ms])))
  :ret any?)  ; core.async channel

(s/fdef github.copilot-sdk.session/abort!
  :args (s/cat :session ::specs/session)
  :ret nil?)

(s/fdef github.copilot-sdk.session/get-messages
  :args (s/cat :session ::specs/session)
  :ret (s/coll-of map?))

(s/fdef github.copilot-sdk.session/destroy!
  :args (s/alt :handle (s/cat :session ::specs/session)
               :explicit (s/cat :client ::specs/client
                                :session-id ::specs/session-id))
  :ret nil?)

(s/fdef github.copilot-sdk.session/session-id
  :args (s/cat :session ::specs/session)
  :ret ::specs/session-id)

(s/fdef github.copilot-sdk.session/workspace-path
  :args (s/cat :session ::specs/session)
  :ret ::specs/workspace-path)

(s/fdef github.copilot-sdk.session/events
  :args (s/cat :session ::specs/session)
  :ret any?)  ; core.async mult

(s/fdef github.copilot-sdk.session/subscribe-events
  :args (s/cat :session ::specs/session)
  :ret any?)  ; core.async channel

(s/fdef github.copilot-sdk.session/unsubscribe-events
  :args (s/cat :session ::specs/session
               :ch any?)
  :ret nil?)

(s/fdef github.copilot-sdk.session/events->chan
  :args (s/cat :session ::specs/session
               :opts (s/? (s/keys :opt-un [::specs/buffer ::specs/xf])))
  :ret any?)  ; core.async channel

(s/fdef github.copilot-sdk.session/get-current-model
  :args (s/cat :session ::specs/session)
  :ret ::specs/model-id)

(s/fdef github.copilot-sdk.session/switch-model!
  :args (s/cat :session ::specs/session
               :model-id string?)
  :ret ::specs/model-id)

;; -----------------------------------------------------------------------------
;; Function specs for helpers namespace
;; -----------------------------------------------------------------------------

(s/fdef github.copilot-sdk.helpers/query
  :args (s/cat :prompt string?
               :opts (s/keys* :opt-un [::specs/client ::specs/session ::specs/timeout-ms]))
  :ret (s/nilable string?))

(s/fdef github.copilot-sdk.helpers/query-seq!
  :args (s/cat :prompt string?
               :opts (s/keys* :opt-un [::specs/client ::specs/session ::specs/max-events]))
  :ret seqable?)

(s/fdef github.copilot-sdk.helpers/query-chan
  :args (s/cat :prompt string?
               :opts (s/keys* :opt-un [::specs/client ::specs/session ::specs/buffer]))
  :ret any?)  ; core.async channel

(s/fdef github.copilot-sdk.helpers/shutdown!
  :args (s/cat)
  :ret nil?)

;; -----------------------------------------------------------------------------
;; Instrument all public API functions
;; -----------------------------------------------------------------------------

(defn instrument-all!
  "Instrument all public API functions with spec checking."
  []
  (stest/instrument '[github.copilot-sdk.client/client
                      github.copilot-sdk.client/state
                      github.copilot-sdk.client/start!
                      github.copilot-sdk.client/stop!
                      github.copilot-sdk.client/force-stop!
                      github.copilot-sdk.client/ping
                      github.copilot-sdk.client/get-status
                      github.copilot-sdk.client/get-auth-status
                      github.copilot-sdk.client/list-models
                      github.copilot-sdk.client/list-tools
                      github.copilot-sdk.client/get-quota
                      github.copilot-sdk.client/create-session
                      github.copilot-sdk.client/resume-session
                      github.copilot-sdk.client/list-sessions
                      github.copilot-sdk.client/delete-session!
                      github.copilot-sdk.client/get-last-session-id
                      github.copilot-sdk.client/get-foreground-session-id
                      github.copilot-sdk.client/set-foreground-session-id!
                      github.copilot-sdk.client/notifications
                      github.copilot-sdk.client/on-lifecycle-event
                      github.copilot-sdk.session/send!
                      github.copilot-sdk.session/send-and-wait!
                      github.copilot-sdk.session/send-async
                      github.copilot-sdk.session/send-async-with-id
                      github.copilot-sdk.session/<send!
                      github.copilot-sdk.session/abort!
                      github.copilot-sdk.session/get-messages
                      github.copilot-sdk.session/destroy!
                      github.copilot-sdk.session/session-id
                      github.copilot-sdk.session/workspace-path
                      github.copilot-sdk.session/get-current-model
                      github.copilot-sdk.session/switch-model!
                      github.copilot-sdk.session/events
                      github.copilot-sdk.session/subscribe-events
                      github.copilot-sdk.session/unsubscribe-events
                      github.copilot-sdk.session/events->chan
                      github.copilot-sdk.helpers/query
                      github.copilot-sdk.helpers/query-seq!
                      github.copilot-sdk.helpers/query-chan
                      github.copilot-sdk.helpers/shutdown!]))

(defn unstrument-all!
  "Remove instrumentation from all public API functions."
  []
  (stest/unstrument '[github.copilot-sdk.client/client
                      github.copilot-sdk.client/state
                      github.copilot-sdk.client/start!
                      github.copilot-sdk.client/stop!
                      github.copilot-sdk.client/force-stop!
                      github.copilot-sdk.client/ping
                      github.copilot-sdk.client/get-status
                      github.copilot-sdk.client/get-auth-status
                      github.copilot-sdk.client/list-models
                      github.copilot-sdk.client/list-tools
                      github.copilot-sdk.client/get-quota
                      github.copilot-sdk.client/create-session
                      github.copilot-sdk.client/resume-session
                      github.copilot-sdk.client/list-sessions
                      github.copilot-sdk.client/delete-session!
                      github.copilot-sdk.client/get-last-session-id
                      github.copilot-sdk.client/get-foreground-session-id
                      github.copilot-sdk.client/set-foreground-session-id!
                      github.copilot-sdk.client/notifications
                      github.copilot-sdk.client/on-lifecycle-event
                      github.copilot-sdk.session/send!
                      github.copilot-sdk.session/send-and-wait!
                      github.copilot-sdk.session/send-async
                      github.copilot-sdk.session/send-async-with-id
                      github.copilot-sdk.session/<send!
                      github.copilot-sdk.session/abort!
                      github.copilot-sdk.session/get-messages
                      github.copilot-sdk.session/destroy!
                      github.copilot-sdk.session/session-id
                      github.copilot-sdk.session/workspace-path
                      github.copilot-sdk.session/get-current-model
                      github.copilot-sdk.session/switch-model!
                      github.copilot-sdk.session/events
                      github.copilot-sdk.session/subscribe-events
                      github.copilot-sdk.session/unsubscribe-events
                      github.copilot-sdk.session/events->chan
                      github.copilot-sdk.helpers/query
                      github.copilot-sdk.helpers/query-seq!
                      github.copilot-sdk.helpers/query-chan
                      github.copilot-sdk.helpers/shutdown!]))

;; Auto-instrument when this namespace is loaded
(instrument-all!)
