(ns permission-bash
  (:require [krukow.copilot-sdk :as copilot]
            [clojure.pprint :as pprint]))

;; See examples/README.md for usage

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

(defn run
  [_opts]
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
      (println (-> (copilot/send-and-wait! session
                     {:prompt (str "Run this command with the " tool
                                   "tool, then reply with success(output from tool) if permitted or denied if not:\n\n" command)})
                   (get-in [:data :content])))
      (println (-> (copilot/send-and-wait! session
                     {:prompt (str "Run this command with the " tool
                                   " tool, then reply with success(output from tool) if permitted or denied if not:\n\n" denied-command)})
                   (get-in [:data :content]))))))
