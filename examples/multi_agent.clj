(ns multi-agent
  "Example 3: Multi-Agent Orchestration
   
   This example demonstrates concurrent agent orchestration using core.async:
   1. Create multiple sessions with different roles/models
   2. Use core.async channels to coordinate between agents
   3. Implement a simple research workflow:
      - Researcher agent gathers information
      - Analyst agent processes findings
      - Writer agent synthesizes the final output
   
   Run with: clojure -A:examples -M -m multi-agent"
  (:require [krukow.copilot-sdk :as copilot]
            [clojure.core.async :as async :refer [go go-loop <! >! chan close! 
                                                   alts! timeout put! <!!]]))

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
      (try
        (let [response (copilot/send-and-wait! (:session agent) 
                         {:prompt prompt}
                         120000)] ; 2 min timeout for complex tasks
          (>! out-ch {:agent (:name agent)
                      :content (get-in response [:data :content])
                      :success true}))
        (catch Exception e
          (>! out-ch {:agent (:name agent)
                      :error (.getMessage e)
                      :success false})))
      (close! out-ch))
    out-ch))

(defn run-parallel-research!
  "Run multiple research queries in parallel and collect results."
  [researcher-agent topics]
  (let [result-ch (chan (count topics))]
    ;; Launch all research tasks in parallel
    (doseq [topic topics]
      (go
        (println (str "  📚 Researching: " topic))
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

(defn -main [& _args]
  (println "🤖 Multi-Agent Orchestration Example")
  (println "=====================================\n")
  (println "This example creates 3 specialized agents that work together:")
  (println "  1. Researcher - gathers information on topics")
  (println "  2. Analyst - identifies patterns and insights")
  (println "  3. Writer - synthesizes a final summary\n")
  
  (let [cli-path (or (System/getenv "COPILOT_CLI_PATH") "copilot")]
    (try
      (println "📡 Starting Copilot client...")
      (copilot/with-client [client {:cli-path cli-path
                                    :log-level :info}]
        (println "✅ Connected!\n")
        
        ;; Create our specialized agents
        (println "🎭 Creating specialized agents...")
        
        (let [researcher (create-agent client "Researcher"
                           "You are a research assistant. Your job is to gather factual information
                            about topics. Be concise and focus on key facts. Respond in 2-3 sentences."
                           "gpt-5.2")
              _ (println "  ✓ Researcher agent ready")
              
              analyst (create-agent client "Analyst"  
                        "You are an analytical assistant. Your job is to identify patterns,
                         connections, and insights from information provided. Be insightful but concise."
                        "gpt-5.2")
              _ (println "  ✓ Analyst agent ready")
              
              writer (create-agent client "Writer"
                       "You are a professional writer. Your job is to synthesize information
                        into clear, engaging prose. Write in a professional but accessible style."
                       "gpt-5.2")
              _ (println "  ✓ Writer agent ready\n")]
          
          ;; Phase 1: Parallel Research
          (println "📖 Phase 1: Parallel Research")
          (println "   Researching multiple topics concurrently...\n")
          
          (let [topics ["functional programming benefits"
                        "immutable data structures"
                        "concurrent programming challenges"]
                
                ;; Run research in parallel and wait for all results
                research-results (<!! (run-parallel-research! researcher topics))]
            
            (println "\n   Research Results:")
            (doseq [result research-results]
              (if (:success result)
                (println (str "   • " (:topic result) ": " 
                             (subs (:content result) 0 (min 100 (count (:content result)))) "..."))
                (println (str "   • " (:topic result) ": ERROR - " (:error result)))))
            
            ;; Phase 2: Analysis
            (println "\n🔍 Phase 2: Analysis")
            (println "   Sending research findings to Analyst...\n")
            
            (let [research-summary (->> research-results
                                        (filter :success)
                                        (map #(str "- " (:topic %) ": " (:content %)))
                                        (clojure.string/join "\n"))
                  
                  analysis-prompt (str "Analyze these research findings and identify 2-3 key insights "
                                      "or patterns:\n\n" research-summary)
                  
                  analysis-response (<!! (agent-respond! analyst analysis-prompt))]
              
              (println "   Analysis Complete:")
              (println (str "   " (:content analysis-response)))
              
              ;; Phase 3: Synthesis
              (println "\n✍️  Phase 3: Synthesis")
              (println "   Writer is creating final summary...\n")
              
              (let [synthesis-prompt (str "Based on the following research and analysis, "
                                         "write a brief (3-4 sentence) executive summary:\n\n"
                                         "RESEARCH:\n" research-summary "\n\n"
                                         "ANALYSIS:\n" (:content analysis-response))
                    
                    final-response (<!! (agent-respond! writer synthesis-prompt))]
                
                (println "═══════════════════════════════════════════════════")
                (println "📋 FINAL SUMMARY")
                (println "═══════════════════════════════════════════════════")
                (println (:content final-response))
                (println "═══════════════════════════════════════════════════"))))
          
          ;; Clean up all agents
          (println "\n🧹 Cleaning up agents...")
          (copilot/destroy! (:session researcher))
          (copilot/destroy! (:session analyst))
          (copilot/destroy! (:session writer)))
        
        (println "\n✅ Multi-agent workflow complete!"))
      
      (catch Exception e
        (println (str "\n❌ Error: " (.getMessage e)))
        (.printStackTrace e)
        (System/exit 1)))))
