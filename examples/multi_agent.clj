(ns multi-agent
  "Multi-agent pipeline: 3 researchers (parallel) → analyst → writer"
  (:require [krukow.copilot-sdk :as copilot]
            [krukow.copilot-sdk.helpers :as h]
            [clojure.core.async :as async :refer [go <! <!!]]))

;; See examples/README.md for usage

(def defaults
  {:topics ["functional programming benefits"
            "immutable data structures"
            "concurrent programming challenges"]})

(declare research-phase analysis-phase synthesis-phase)

(defn run
  [{:keys [topics] :or {topics (:topics defaults)}}]
  (copilot/with-client [client {:model "gpt-5.2"}]
    (->> (research-phase client topics)
         (analysis-phase client)
         (synthesis-phase client))))

(defmacro ^:private with-timing
  "Execute body, print elapsed time, and return result."
  [& body]
  `(let [start# (System/currentTimeMillis)
         result# (do ~@body)]
     (println (str "  (completed in " (- (System/currentTimeMillis) start#) "ms)"))
     result#))

(def researcher-prompt "You are a research assistant. Be concise: 2-3 bullet points.")
(defn- research-topic
  "Start async research on a topic. Returns channel yielding {:topic :findings}."
  [client topic]
  (let [session (copilot/create-session
                 client
                 {:system-message {:mode :append :content researcher-prompt}})]
    (go {:topic topic
         :findings (<! (copilot/<send! session {:prompt (str "Research: " topic)}))})))

(defn research-phase
  "Run parallel research on topics. Returns formatted summary string."
  [client topics]
  (println "📚 Research Phase (parallel)")
  (with-timing
    (let [result-chan (async/merge (mapv #(research-topic client %) topics))
          results (doall (repeatedly (count topics) #(<!! result-chan)))
          format-research (fn [results]
                            (->> results
                                 (map #(str "• " (:topic %) ": " (:findings %)))
                                 (clojure.string/join "\n\n")))]
      (doto (format-research results) println))))

(def analyst-prompt "You are an analyst. Identify patterns and insights. Be concise: 2-3 sentences.")
(defn analysis-phase
  "Analyze research findings. Returns analysis string."
  [client research-summary]
  (println "\n🔍 Analysis Phase")
  (with-timing
    (doto (h/query (str "Analyze these findings:\n\n" research-summary)
                   :client client
                   :session {:system-prompt analyst-prompt})
      (->> (println "Analysis:\n")))))

(def synthesis-prompt "You are a writer. Create clear, engaging prose.")
(defn synthesis-phase
  "Write executive summary from analysis. Returns summary string."
  [client analysis]
  (println "\n📋 FINAL SUMMARY:")
  (with-timing
    (doto (h/query (str "Write a 3-4 sentence executive summary. ANALYSIS: " analysis)
                   :client client
                   :session {:system-prompt synthesis-prompt})
      println)))
