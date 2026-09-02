(ns commands
  "Example: Registering slash commands on a session.

   Commands allow users to invoke named actions via /command-name in chat.
   Each command has a name, optional description, and a handler function
   that receives a CommandContext map with :session-id, :command-name,
   :command (raw text), and :args."
  (:require [github.copilot-sdk :as copilot]))

;; See examples/README.md for usage

(defn handle-status
  "Handler for /status command — prints session info."
  [{:keys [session-id command-name]}]
  (println (str "  [" command-name "] Session: " session-id)))

(defn handle-guide
  "Handler for /guide command — shows available commands."
  [{:keys [command-name]}]
  (println (str "  [" command-name "] Available commands: /status, /guide")))

(defn run
  "Run the commands example."
  [_opts]
  (copilot/with-client-session
    [session {:on-permission-request copilot/approve-all
              :model "claude-haiku-4.5"
              :commands [{:name "status"
                          :description "Show session status"
                          :command-handler handle-status}
                         {:name "guide"
                          :description "List available commands"
                          :command-handler handle-guide}]}]
    (println "Session created with commands: /status, /guide")
    (println "Sending a message...")
    (let [response (copilot/send-and-wait! session
                                           {:prompt "Say hello briefly."})]
      (println "\nAssistant:" (get-in response [:data :content]))
      (println "\nDone."))))
