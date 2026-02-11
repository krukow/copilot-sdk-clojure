(ns basic-chat
  (:require [github.copilot-sdk :as copilot]
            [github.copilot-sdk.helpers :as h]))

;; See examples/README.md for usage

(def defaults
  {:q1 "What is the capital of France? Please answer in one sentence."
   :q2 "What is its population approximately?"})

(defn run
  [{:keys [q1 q2] :or {q1 (:q1 defaults) q2 (:q2 defaults)}}]
  (copilot/with-client-session [session {:model "gpt-5.2"}]
    (println "Q1:" q1)
    (println "🤖:" (h/query q1 :session session))
    (println)
    (println "Q2:" q2)
    (println "🤖:" (h/query q2 :session session))))
