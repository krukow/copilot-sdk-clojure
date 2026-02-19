(ns helpers-query
  (:require [clojure.core.async :refer [<!! go-loop <!]]
            [github.copilot-sdk :as copilot :refer [evt]]
            [github.copilot-sdk.helpers :as h]))

;; See examples/README.md for usage

(def defaults
  {:prompt "What is the capital of Japan? Answer in one sentence."})

(def session-config
  {:model "gpt-5.2"})

(defn run
  [{:keys [prompt] :or {prompt (:prompt defaults)}}]
  (println "Query:" prompt)
  (println "🤖:" (h/query prompt :session session-config)))

(defn run-multi
  [{:keys [questions] :or {questions ["What is 2+2? Just the number."
                                      "What is the capital of France? Just the city."
                                      "Who wrote Hamlet? Just the name."]}}]
  (doseq [q questions]
    (println "Q:" q)
    (println "A:" (h/query q :session session-config))
    (println)))

;; Define a multimethod for handling events by type
(defmulti handle-event :type)
(defmethod handle-event :default [_] nil)
(defmethod handle-event (evt :assistant.message_delta) [{{:keys [delta-content]} :data}]
  (print delta-content)
  (flush))
(defmethod handle-event (evt :assistant.message) [_] (println))

(defn run-streaming
  [{:keys [prompt] :or {prompt "Explain the concept of immutability in 2-3 sentences."}}]
  (println "Query:" prompt)
  (println)
  (run! handle-event (h/query-seq! prompt :session {:model "gpt-5.2" :streaming? true})))

(defn run-async
  [{:keys [prompt] :or {prompt "Tell me a short joke."}}]
  (println "Query:" prompt)
  (println)
  (let [ch (h/query-chan prompt :session {:model "gpt-5.2" :streaming? true})]
    (<!! (go-loop []
           (when-let [event (<! ch)]
             (handle-event event)
             (recur))))))
