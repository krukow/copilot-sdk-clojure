(ns ask-user-failure
  "1:1 port of copilot-sdk/nodejs/examples/basic-example.ts
   Demonstrates user cancellation (simulating Esc) with full event tracing."
  (:require [clojure.core.async :as async :refer [chan tap go-loop <!]]
            [github.copilot-sdk :as copilot]))

(defn run
  "Port of basic-example.ts — user cancels ask_user (simulates Esc).

   Usage:
     clojure -A:examples -X basic-example/run"
  [_]
  (println "🚀 Starting Copilot SDK ask_user Example\n")
  (println "\n═══ Case: User cancels (simulate Esc) ═══\n")

  (let [cancelled-requests (atom [])]
    (copilot/with-client [client]
      (copilot/with-session [session client
                             {:model "gpt-5.2"
                              :on-user-input-request
                              (fn [request _invocation]
                                (swap! cancelled-requests conj request)
                                (println (str "  📋 Received question: \"" (:question request) "\""))
                                (println "  ❌ Simulating Esc (cancel)...")
                                ;; Throwing simulates what happens when the user presses Esc in the CLI.
                                ;; In the CLI UI: onCancel() → reject(new Error("User skipped question"))
                                ;; The error propagates via JSON-RPC back to the ask_user tool's catch block,
                                ;; which returns { resultType: "failure", error: "User skipped question" }.
                                (throw (RuntimeException. "User skipped question")))}]
        (println (str "✅ Session created: " (copilot/session-id session) "\n"))

        ;; Subscribe to all events — mirror session2.on((event) => console.log(event))
        (let [events-ch (chan 256)
              done (promise)]
          (tap (copilot/events session) events-ch)

          (go-loop []
            (when-let [event (<! events-ch)]
              (println event)
              (when (= (:type event) :copilot/session.idle)
                (deliver done true))
              (when (= (:type event) :copilot/session.error)
                (deliver done (ex-info "Session error" {:event event})))
              (recur)))

          (println "💬 Sending message that triggers ask_user...")
          (let [result (copilot/send-and-wait! session
                         {:prompt (str "Use the ask_user tool to ask me to pick between 'Red' and 'Blue'. "
                                       "Wait for my answer. If asking fails, say exactly 'The user skipped the question'.")})]
            (println (str "📝 Response: " (get-in result [:data :content]) "\n"))
            (println (str "📊 Questions received: " (count @cancelled-requests)))))))))
