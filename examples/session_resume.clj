(ns session-resume
  "Demonstrates session resume: create a session, teach it a code word,
   then resume the session by ID and verify the model remembers it."
  (:require [github.copilot-sdk :as copilot]))

;; See examples/README.md for usage

(def defaults
  {:code-word "PINEAPPLE"
   :prompt "What was the code word I told you? Reply with just the word."})

(defn run
  [{:keys [code-word prompt]
    :or {code-word (:code-word defaults) prompt (:prompt defaults)}}]
  (copilot/with-client [client {}]
    (println "Creating session...")
    (let [session (copilot/create-session
                   client
                   {:on-permission-request copilot/approve-all
                    :model "claude-haiku-4.5"
                    :available-tools []})]
      (println "Sending code word:" code-word)
      (let [result (copilot/send-and-wait!
                    session
                    {:prompt (str "Remember this code word: " code-word
                                  ". Just confirm by repeating it back.")})]
        (println "🤖:" (get-in result [:data :content])))

      (let [session-id (:session-id session)]
        (copilot/disconnect! session)
        (println "\nResuming session" session-id "...")
        (let [resumed (copilot/resume-session
                       client session-id
                       {:on-permission-request copilot/approve-all
                        :available-tools []})]
          (try
            (println "Asking:" prompt)
            (let [result (copilot/send-and-wait! resumed {:prompt prompt})]
              (println "🤖:" (get-in result [:data :content])))
            (finally
              (copilot/disconnect! resumed))))))))
