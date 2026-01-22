(ns multi-agent
  (:require [krukow.copilot-sdk :as copilot]
            [clojure.core.async :as async :refer [go go-loop <! >! chan close! <!!]]))

;; See examples/README.md for usage

(defn create-agent
  "Create an agent with a specific role via system message."
  [client agent-name role-description model]
  (let [session (copilot/create-session client
                  {:model model
                   :system-message {:mode :append
                                    :content role-description}})]
    {:name agent-name
     :session session
     :model model}))

(defn agent-respond!
  "Send a prompt to an agent and return a channel with the response content."
  [agent prompt]
  (let [out-ch (chan 1)]
    (go
      (let [response (copilot/send-and-wait! (:session agent)
                       {:prompt prompt}
                       120000)] ; 2 min timeout for complex tasks
        (>! out-ch {:agent (:name agent)
                    :content (get-in response [:data :content])
                    :success true}))
      (close! out-ch))
    out-ch))

(defn run-parallel-research!
  "Run multiple research queries in parallel and collect results."
  [researcher-agent topics]
  (let [result-ch (chan (count topics))]
    ;; Launch all research tasks in parallel
    (doseq [topic topics]
      (go
        (let [response (<! (agent-respond! researcher-agent
                             (str "Briefly research and summarize key points about: " topic
                                  ". Keep it to 2-3 sentences.")))]
          (>! result-ch (assoc response :topic topic)))))
    
    ;; Collect all results
    (go-loop [results [] 
              remaining (count topics)]
      (if (zero? remaining)
        results
        (let [result (<! result-ch)]
          (recur (conj results result) (dec remaining)))))))

(def defaults
  {:topics ["functional programming benefits"
            "immutable data structures"
            "concurrent programming challenges"]})

(defn run
  [{:keys [topics] :or {topics (:topics defaults)}}]
  (copilot/with-client [client]
    (let [researcher (create-agent client "Researcher"
                       "You are a research assistant. Your job is to gather factual information
                        about topics. Be concise and focus on key facts. Respond in 2-3 sentences."
                       "gpt-5.2")
          
          analyst (create-agent client "Analyst"  
                    "You are an analytical assistant. Your job is to identify patterns,
                     connections, and insights from information provided. Be insightful but concise."
                    "gpt-5.2")
          
          writer (create-agent client "Writer"
                   "You are a professional writer. Your job is to synthesize information
                    into clear, engaging prose. Write in a professional but accessible style."
                   "gpt-5.2")]
      
      (let [research-results (<!! (run-parallel-research! researcher topics))]
        
        (let [research-summary (->> research-results
                                    (filter :success)
                                    (map #(str "- " (:topic %) ": " (:content %)))
                                    (clojure.string/join "\n"))
              
              analysis-prompt (str "Analyze these research findings and identify 2-3 key insights "
                                  "or patterns:\n\n" research-summary)
              
              analysis-response (<!! (agent-respond! analyst analysis-prompt))
              synthesis-prompt (str "Based on the following research and analysis, "
                                     "write a brief (3-4 sentence) executive summary:\n\n"
                                     "RESEARCH:\n" research-summary "\n\n"
                                     "ANALYSIS:\n" (:content analysis-response))
              final-response (<!! (agent-respond! writer synthesis-prompt))]
          (println (:content final-response))))
      
      ;; Clean up all agents
      (copilot/destroy! (:session researcher))
      (copilot/destroy! (:session analyst))
      (copilot/destroy! (:session writer)))))
