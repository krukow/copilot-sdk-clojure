(ns krukow.copilot-sdk.instrument
  "Spec instrumentation for development/testing.
   
   Require this namespace to enable spec checking on public API functions.
   This adds runtime validation which is useful during development but may
   impact performance in production.
   
   Usage:
     (require '[krukow.copilot-sdk.instrument])
     ;; Now all public API calls are spec-checked
   
   To disable:
     (require '[clojure.spec.test.alpha :as stest])
     (stest/unstrument)"
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [krukow.copilot-sdk.specs :as specs]))

;; -----------------------------------------------------------------------------
;; Function specs for client namespace
;; -----------------------------------------------------------------------------

(s/fdef krukow.copilot-sdk.client/client
  :args (s/cat :opts (s/? ::specs/client-options))
  :ret ::specs/client)

(s/fdef krukow.copilot-sdk.client/state
  :args (s/cat :client ::specs/client)
  :ret ::specs/connection-state)

(s/fdef krukow.copilot-sdk.client/start!
  :args (s/cat :client ::specs/client)
  :ret nil?)

(s/fdef krukow.copilot-sdk.client/stop!
  :args (s/cat :client ::specs/client)
  :ret (s/coll-of any?))

(s/fdef krukow.copilot-sdk.client/ping
  :args (s/cat :client ::specs/client
               :message (s/? (s/nilable string?)))
  :ret (s/keys :opt-un [::specs/message ::specs/timestamp]))

(s/fdef krukow.copilot-sdk.client/create-session
  :args (s/cat :client ::specs/client
               :config (s/? ::specs/session-config))
  :ret ::specs/session)

(s/fdef krukow.copilot-sdk.client/resume-session
  :args (s/cat :client ::specs/client
               :session-id ::specs/session-id
               :config (s/? ::specs/resume-session-config))
  :ret ::specs/session)

(s/fdef krukow.copilot-sdk.client/list-sessions
  :args (s/cat :client ::specs/client)
  :ret (s/coll-of ::specs/session-metadata))

(s/fdef krukow.copilot-sdk.client/delete-session!
  :args (s/cat :client ::specs/client
               :session-id ::specs/session-id)
  :ret nil?)

(s/fdef krukow.copilot-sdk.client/get-last-session-id
  :args (s/cat :client ::specs/client)
  :ret (s/nilable ::specs/session-id))

;; -----------------------------------------------------------------------------
;; Function specs for session namespace
;; -----------------------------------------------------------------------------

(s/fdef krukow.copilot-sdk.session/send!
  :args (s/cat :session ::specs/session
               :opts ::specs/send-options)
  :ret string?)  ; message-id

(s/fdef krukow.copilot-sdk.session/send-and-wait!
  :args (s/cat :session ::specs/session
               :opts ::specs/send-options
               :timeout-ms (s/? ::specs/timeout-ms))
  :ret (s/nilable map?))

(s/fdef krukow.copilot-sdk.session/abort!
  :args (s/cat :session ::specs/session)
  :ret nil?)

(s/fdef krukow.copilot-sdk.session/get-messages
  :args (s/cat :session ::specs/session)
  :ret (s/coll-of map?))

(s/fdef krukow.copilot-sdk.session/destroy!
  :args (s/alt :handle (s/cat :session ::specs/session)
               :explicit (s/cat :client ::specs/client
                                :session-id ::specs/session-id))
  :ret nil?)

(s/fdef krukow.copilot-sdk.session/session-id
  :args (s/cat :session ::specs/session)
  :ret ::specs/session-id)

;; -----------------------------------------------------------------------------
;; Instrument all public API functions
;; -----------------------------------------------------------------------------

(defn instrument-all!
  "Instrument all public API functions with spec checking."
  []
  (stest/instrument '[krukow.copilot-sdk.client/client
                      krukow.copilot-sdk.client/state
                      krukow.copilot-sdk.client/start!
                      krukow.copilot-sdk.client/stop!
                      krukow.copilot-sdk.client/ping
                      krukow.copilot-sdk.client/create-session
                      krukow.copilot-sdk.client/resume-session
                      krukow.copilot-sdk.client/list-sessions
                      krukow.copilot-sdk.client/delete-session!
                      krukow.copilot-sdk.client/get-last-session-id
                      krukow.copilot-sdk.session/send!
                      krukow.copilot-sdk.session/send-and-wait!
                      krukow.copilot-sdk.session/abort!
                      krukow.copilot-sdk.session/get-messages
                      krukow.copilot-sdk.session/destroy!
                      krukow.copilot-sdk.session/session-id]))

(defn unstrument-all!
  "Remove instrumentation from all public API functions."
  []
  (stest/unstrument '[krukow.copilot-sdk.client/client
                      krukow.copilot-sdk.client/state
                      krukow.copilot-sdk.client/start!
                      krukow.copilot-sdk.client/stop!
                      krukow.copilot-sdk.client/ping
                      krukow.copilot-sdk.client/create-session
                      krukow.copilot-sdk.client/resume-session
                      krukow.copilot-sdk.client/list-sessions
                      krukow.copilot-sdk.client/delete-session!
                      krukow.copilot-sdk.client/get-last-session-id
                      krukow.copilot-sdk.session/send!
                      krukow.copilot-sdk.session/send-and-wait!
                      krukow.copilot-sdk.session/abort!
                      krukow.copilot-sdk.session/get-messages
                      krukow.copilot-sdk.session/destroy!
                      krukow.copilot-sdk.session/session-id]))

;; Auto-instrument when this namespace is loaded
(instrument-all!)
