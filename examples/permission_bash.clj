(ns permission-bash
  (:require [krukow.copilot-sdk :as copilot]
            [clojure.pprint :as pprint]))

(defn- windows?
  []
  (boolean (re-find #"(?i)windows" (System/getProperty "os.name"))))

(defn- shell-config
  []
  (if (windows?)
    {:tool "powershell"
     :command "Write-Output 'hello from powershell'"}
    {:tool "bash"
     :command "echo 'hello from bash'"}))

(defn -main [& _args]
  (let [{:keys [tool command]} (shell-config)
        denied-command (str command " && echo 'denied'")
        allowed-commands #{command}]
    (copilot/with-client-session [session {:model "gpt-5.2"
                                           :available-tools [tool]
                                           :on-permission-request (fn [request _ctx]
                                                                    (pprint/pprint request)
                                                                    (if (contains? allowed-commands (:full-command-text request))
                                                                      {:kind :approved}
                                                                      {:kind :denied-by-rules
                                                                       :rules [{:kind "shell"
                                                                                :argument (:full-command-text request)}]}))}]
      (let [response (copilot/send-and-wait!
                      session
                      {:prompt (str "Run this command with the "
                                    tool
                                    " tool, then reply with just DONE:\n\n"
                                    command)})]
        (println (get-in response [:data :content])))
      (let [response (copilot/send-and-wait!
                      session
                      {:prompt (str "Run this command with the "
                                    tool
                                    " tool, then reply with just DONE:\n\n"
                                    denied-command)})]
        (println (get-in response [:data :content]))))))
