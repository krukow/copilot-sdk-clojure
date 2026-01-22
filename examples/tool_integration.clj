(ns tool-integration
  (:require [krukow.copilot-sdk :as copilot]))

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
                (let [lang (-> args :language str clojure.string/lower-case)
                      info (get knowledge-base lang)]
                  (if info
                    (copilot/result-success info)
                    (copilot/result-failure
                     (str "No information found for language: " lang ". Available: clojure, rust, python, javascript, haskell")
                     "language not in knowledge base"))))}))

(defn -main [& _args]
  (copilot/with-client-session [session {:model "gpt-5.2"
                                         :tools [lookup-tool]}]
    (println (-> (copilot/send-and-wait! session
                   {:prompt "What is Clojure? Use the lookup_language tool to find out."})
                 (get-in [:data :content])))
    (println (-> (copilot/send-and-wait! session
                   {:prompt "Now tell me about Python. Use the lookup_language tool."})
                 (get-in [:data :content])))
    (println (-> (copilot/send-and-wait! session
                   {:prompt "What about Rust? Look it up please."})
                 (get-in [:data :content])))))
