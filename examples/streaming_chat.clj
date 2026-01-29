(ns streaming-chat
  (:require [clojure.core.async :as async :refer [chan tap go-loop <!]]
            [krukow.copilot-sdk :as copilot]))

;; See examples/README.md for usage

(def defaults
  {:prompt "Solve this: I have two coins totaling 30 cents. One is not a nickel. What are the coins? Explain your reasoning clearly."})

(defn run
  [{:keys [prompt] :or {prompt (:prompt defaults)}}]
  (copilot/with-client-session [session {:model "gpt-5.2"
                                         :streaming? true}]
    (let [events-ch (chan 256)
          done (promise)]
      (tap (copilot/events session) events-ch)
      (go-loop []
        (when-let [event (<! events-ch)]
          (case (:type event)
            :copilot/assistant.message_delta
            (do
              (print (get-in event [:data :delta-content]))
              (flush))

            :copilot/assistant.reasoning_delta
            (do
              (binding [*out* *err*]
                (print (get-in event [:data :delta-content]))
                (flush)))

            :copilot/assistant.reasoning
            (binding [*out* *err*]
              (println (get-in event [:data :content])))

            :copilot/assistant.message
            (println (get-in event [:data :content]))

            :copilot/session.idle
            (deliver done true)

            :copilot/session.error
            (deliver done (ex-info "Session error" {:event event}))

            nil)
          (recur)))

      (copilot/send! session {:prompt prompt})
      (let [result @done]
        (when (instance? Exception result)
          (throw result))))))
