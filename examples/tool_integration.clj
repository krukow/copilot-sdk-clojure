(ns tool-integration
  "Example 2: Custom Tool Integration

   This example demonstrates how to define and use custom tools:
   1. Define a tool with a handler function
   2. Create a session with the tool
   3. Let the LLM invoke your tool multiple times

   Run with: clojure -M:examples -m tool-integration"
  (:require [krukow.copilot-sdk :as copilot]
            [clojure.core.async :as async :refer [go <! >! chan close!
                                                  alts! timeout put! <!!]]))

;; Define a knowledge base for our tool
(def ^:private knowledge-base
  {"clojure" "Clojure is a dynamic, functional programming language that runs on the JVM. Created by Rich Hickey in 2007. It emphasizes immutability and functional programming."
   "rust" "Rust is a systems programming language focused on safety, speed, and concurrency. Created by Mozilla. Known for its ownership model and zero-cost abstractions."
   "python" "Python is a high-level, interpreted programming language known for its readability. Created by Guido van Rossum in 1991. Popular for data science and web development."
   "javascript" "JavaScript is a dynamic scripting language primarily used for web development. Created by Brendan Eich in 1995. The language of the web browser."
   "haskell" "Haskell is a purely functional programming language. Named after Haskell Curry. Known for its strong static typing and lazy evaluation."})

;; Define a lookup tool
(def lookup-tool
  (copilot/define-tool "lookup_language"
    {:description "Look up information about a programming language from our knowledge base. Available languages: clojure, rust, python, javascript, haskell."
     :parameters {:type "object"
                  :properties {:language {:type "string"
                                          :description "The programming language to look up (e.g., 'clojure', 'rust', 'python')"}}
                  :required ["language"]}
     :handler (fn [args _invocation]
                (println (str "  [Tool invoked: lookup_language(" (:language args) ")]"))
                (let [lang (-> args :language str clojure.string/lower-case)
                      info (get knowledge-base lang)]
                  (if info
                    (copilot/result-success info)
                    (copilot/result-failure
                     (str "No information found for language: " lang ". Available: clojure, rust, python, javascript, haskell")
                     "language not in knowledge base"))))}))

(defn -main [& _args]
  (println "🔧 Tool Integration Example")
  (println "============================\n")

  (let [cli-path (or (System/getenv "COPILOT_CLI_PATH") "copilot")]
    (try
      (println "📡 Starting Copilot client...")
      (copilot/with-client [client {:cli-path cli-path
                                    :log-level :info}]
        (println "✅ Connected!\n")

        ;; Create a session with our custom tool
        (println "📝 Creating session with lookup_language tool...")
        (copilot/with-session [session client
                               {:model "gpt-5.2"
                                :tools [lookup-tool]}]
          (println "✅ Session created\n")

          (let [ch (copilot/subscribe-events session)]
            (async/go-loop []
              (when-let [event (<! ch)]
                (println "Event: " (:type event))
                (recur))))

          ;; First lookup - Clojure
          (println "💬 Question 1: Tell me about Clojure")
          (let [response (copilot/send-and-wait! session
                                                 {:prompt "What is Clojure? Use the lookup_language tool to find out."})]
            (println "🤖 Response:")
            (println (str "   " (get-in response [:data :content]) "\n")))

          ;; Second lookup - Python (same tool, different input)
          (println "💬 Question 2: Tell me about Python")
          (let [response (copilot/send-and-wait! session
                                                 {:prompt "Now tell me about Python. Use the lookup_language tool."})]
            (println "🤖 Response:")
            (println (str "   " (get-in response [:data :content]) "\n")))

          ;; Third lookup - Rust
          (println "💬 Question 3: Tell me about Rust")
          (let [response (copilot/send-and-wait! session
                                                 {:prompt "What about Rust? Look it up please."})]
            (println "🤖 Response:")
            (println (str "   " (get-in response [:data :content]) "\n")))

          (println "🧹 Cleaning up...")))

      (println "✅ Done!")

      (catch Exception e
        (println (str "❌ Error: " (.getMessage e)))
        (.printStackTrace e)
        (System/exit 1)))))
