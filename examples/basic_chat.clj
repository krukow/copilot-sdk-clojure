(ns basic-chat
  (:require [krukow.copilot-sdk :as copilot]))

(defn -main [& _args]
  (copilot/with-client-session [session {:model "gpt-5.2"}]
    (let [response (copilot/send-and-wait! session
                     {:prompt "What is the capital of France? Please answer in one sentence."})]
      (println (get-in response [:data :content])))

    (let [response (copilot/send-and-wait! session
                     {:prompt "What is its population approximately?"})]
      (println (get-in response [:data :content])))))
