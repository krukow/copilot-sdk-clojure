(ns basic-chat
  "Example 1: Basic Q&A conversation

   This example demonstrates the simplest use case:
   1. Create a client
   2. Create a session
   3. Send a message and get a response
   4. Clean up

   Run with: clojure -M:examples -m examples.basic-chat"
  (:require [krukow.copilot-sdk :as copilot]))

(defn -main [& _args]
  (println "🚀 Basic Chat Example")
  (println "======================\n")

  ;; Configure CLI path via COPILOT_CLI_PATH env var if needed
  (let [cli-path (or (System/getenv "COPILOT_CLI_PATH") "copilot")]
    (try
      (println "📡 Starting Copilot client...")
      (copilot/with-client [client {:cli-path cli-path
                                    :log-level :info}]
        (println "✅ Connected!\n")

        ;; Create a session with a specific model
        (println "📝 Creating session...")
        (copilot/with-session [session client {:model "gpt-5.2"}]
          (println (str "✅ Session created: " (copilot/session-id session) "\n"))

          ;; Send a simple question and wait for the answer
          (println "💬 Asking: What is the capital of France?")
          (let [response (copilot/send-and-wait! session
                           {:prompt "What is the capital of France? Please answer in one sentence."})]
            (println "\n🤖 Response:")
            (println (get-in response [:data :content])))

          ;; Send a follow-up question (conversation context is maintained)
          (println "\n💬 Follow-up: What is its population?")
          (let [response (copilot/send-and-wait! session
                           {:prompt "What is its population approximately?"})]
            (println "\n🤖 Response:")
            (println (get-in response [:data :content])))

          (println "\n🧹 Cleaning up session...")))

      (println "✅ Done!")

      ;; Ensure JVM exits - background threads from core.async may keep it alive
      (shutdown-agents)
      (System/exit 0)

      (catch Exception e
        (println (str "❌ Error: " (.getMessage e)))
        (shutdown-agents)
        (System/exit 1)))))
