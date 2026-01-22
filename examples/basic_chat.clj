(ns basic-chat
  (:require [krukow.copilot-sdk :as copilot]))

;; See examples/README.md for usage

(def defaults
  {:q1 "What is the capital of France? Please answer in one sentence."
   :q2 "What is its population approximately?"})

(defn run
  [{:keys [q1 q2] :or {q1 (:q1 defaults) q2 (:q2 defaults)}}]
  (copilot/with-client-session [session {:model "gpt-5.2"}]
    (println "Q1:" q1)
    (println "🤖:" (-> (copilot/send-and-wait! session {:prompt q1})
                       (get-in [:data :content])))
    (println)
    (println "Q2:" q2)
    (println "🤖:" (-> (copilot/send-and-wait! session {:prompt q2})
                       (get-in [:data :content])))))
