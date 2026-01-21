(ns streaming-chat
  "Example 4: Streaming responses

   This example demonstrates streaming deltas:
   1. Create a client and session with :streaming? true
   2. Subscribe to events
   3. Print incremental output as deltas arrive

   Run with: clojure -A:examples -M -m streaming-chat"
  (:require [clojure.core.async :as async :refer [chan tap go-loop <!]]
            [krukow.copilot-sdk :as copilot]))

(defn -main [& _args]
  (println "🌊 Streaming Chat Example")
  (println "==========================\n")

  (let [cli-path (or (System/getenv "COPILOT_CLI_PATH") "copilot")]
    (try
      (println "📡 Starting Copilot client...")
      (copilot/with-client [client {:cli-path cli-path
                                    :log-level :info}]
        (println "✅ Connected!\n")

        (println "📝 Creating streaming session...")
        (copilot/with-session [session client {:model "gpt-5"
                                               :streaming? true}]
          (println (str "✅ Session created: " (copilot/session-id session) "\n"))

          (let [events-ch (chan 256)
                done (promise)]
            (tap (copilot/events session) events-ch)
            (go-loop []
              (when-let [event (<! events-ch)]
                (case (:type event)
                  :assistant.message_delta
                  (do
                    (print (get-in event [:data :delta-content]))
                    (flush))

                  :assistant.reasoning_delta
                  (do
                    (binding [*out* *err*]
                      (print (get-in event [:data :delta-content]))
                      (flush)))

                  :assistant.reasoning
                  (binding [*out* *err*]
                    (println "\n\n--- Final Reasoning ---")
                    (println (get-in event [:data :content])))

                  :assistant.message
                  (do
                    (println "\n\n--- Final ---")
                    (println (get-in event [:data :content])))

                  :session.idle
                  (deliver done true)

                  :session.error
                  (deliver done (ex-info "Session error" {:event event}))

                  nil)
                (recur)))

            (println "💬 Prompt: Solve a logic puzzle and show your reasoning.")
            (copilot/send! session {:prompt "Solve this: I have two coins totaling 30 cents. One is not a nickel. What are the coins? Explain your reasoning clearly."})
            (let [result @done]
              (when (instance? Exception result)
                (throw result)))))

        (println "\n✅ Done!"))

      ;; Ensure JVM exits - background threads from core.async may keep it alive
      (shutdown-agents)
      (System/exit 0)

      (catch Exception e
        (println (str "❌ Error: " (.getMessage e)))
        (shutdown-agents)
        (System/exit 1)))))
