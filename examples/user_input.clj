(ns user-input
  (:require [clojure.core.async :as async :refer [chan tap go-loop <!]]
            [github.copilot-sdk :as copilot :refer [evt]]))

;; This example demonstrates handling user input requests (ask_user).
;; When the agent needs clarification or input from the user, it invokes the ask_user
;; tool, which triggers our :on-user-input-request handler.
;;
;; See examples/README.md for usage

(defn prompt-user
  "Prompt the user for input. Shows question and optional choices."
  [{:keys [question choices allow-freeform]}]
  (println "\n🤔 Agent asks:" question)
  (when (seq choices)
    (println "   Choices:")
    (doseq [[i choice] (map-indexed vector choices)]
      (println (str "   " (inc i) ". " choice))))
  (when allow-freeform
    (println "   (You can also type a custom response)"))
  (print "   Your answer: ")
  (flush)
  (if-let [input (read-line)]
    (do
      ;; If user entered a number and we have choices, use that choice
      (if-let [idx (and (seq choices)
                        (re-matches #"\d+" input)
                        (dec (parse-long input)))]
        (if (< -1 idx (count choices))
          (nth choices idx)
          input)
        input))
    (do
      (println "   (EOF received - using empty response)")
      "")))

(defn run
  "Run a session that may trigger ask_user requests.

   The prompt asks the agent to help with a decision, which typically
   causes it to use ask_user to gather more information."
  [_]
  (println "=== User Input Example ===")
  (println "This example shows how to handle ask_user requests from the agent.\n")

  (copilot/with-client-session [session {:model "gpt-5.2"
                                         :on-user-input-request
                                         (fn [request _invocation]
                                           ;; request has :question, :choices, :allow-freeform
                                           (let [answer (prompt-user request)]
                                             {:answer answer}))}]
    (let [events-ch (chan 256)
          done (promise)]
      (tap (copilot/events session) events-ch)

      ;; Event handler
      (go-loop []
        (when-let [event (<! events-ch)]
          (condp = (:type event)
            (evt :assistant.message)
            (println "\n🤖 Agent:" (get-in event [:data :content]))

            (evt :session.idle)
            (deliver done true)

            (evt :session.error)
            (do
              (println "❌ Error:" (get-in event [:data :message]))
              (deliver done (ex-info "Session error" {:event event})))

            ;;:else
            (println (:type event) (:data event)))
          (recur)))

      ;; Send a prompt that encourages the agent to ask questions
      (let [prompt (str "I need help choosing a programming language for a new project. "
                        "Ask me questions to understand my requirements, then give a recommendation. "
                        "Use the ask_user tool to ask me questions.")]
        (println "📤 You:" prompt)
        (copilot/send! session {:prompt prompt}))

      ;; Wait for completion
      (let [result @done]
        (when (instance? Exception result)
          (throw result))
        (println "\n=== Session Complete ===")))))

(defn run-simple
  "A simpler example demonstrating user input handling.

   Note: The agent may not always use ask_user - it's the model's choice.
   This example auto-responds to any user input requests for script compatibility."
  [_]
  (println "=== Simple User Input Example ===\n")
  (println "Note: User input requests are handled automatically in this example.")
  (println "      The agent may or may not choose to use ask_user.\n")

  (let [input-requested? (atom false)]
    (copilot/with-client-session [session {:model "gpt-5.2"
                                           :on-user-input-request
                                           (fn [{:keys [question choices]} _]
                                             (reset! input-requested? true)
                                             (println "\n✅ User input requested!")
                                             (println "   Question:" question)
                                             (when (seq choices)
                                               (println "   Choices:" (vec choices)))
                                             ;; Auto-respond with first choice or "concise"
                                             (let [response (if (seq choices)
                                                              (first choices)
                                                              "concise")]
                                               (println "   Auto-responding:" response)
                                               {:response response}))}]
      (let [result (copilot/send-and-wait! session
                     {:prompt "You MUST use the ask_user tool to ask me whether I prefer 'verbose' or 'concise' output before responding. Then say hello in that style."})]
        (println "\n🤖" (get-in result [:data :content]))
        (when-not @input-requested?
          (println "\n⚠️  Note: The agent chose not to use ask_user this time.")
          (println "   This is normal - the agent decides when to request input."))))))
