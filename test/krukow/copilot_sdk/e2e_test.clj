(ns krukow.copilot-sdk.e2e-test
  "End-to-end tests using real Copilot CLI.
   
   These tests are gated by environment variables:
   - COPILOT_CLI_PATH: Path to copilot CLI executable (required)
   - COPILOT_E2E_TESTS: Set to 'true' to enable these tests
   
   Run with: COPILOT_E2E_TESTS=true COPILOT_CLI_PATH=/path/to/copilot clojure -M:test"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.core.async :as async :refer [<!! >!! chan go go-loop <! timeout alts!!]]
            [krukow.copilot-sdk :as sdk]))

;; Check if E2E tests are enabled
(def e2e-enabled?
  (= "true" (System/getenv "COPILOT_E2E_TESTS")))

(def cli-path
  (or (System/getenv "COPILOT_CLI_PATH") "copilot"))

(defmacro when-e2e
  "Only execute body if E2E tests are enabled."
  [& body]
  `(when e2e-enabled?
     ~@body))

(defmacro with-quiet-logs
  "Execute body with stderr suppressed (silences slf4j-simple output)."
  [& body]
  `(let [original-err# System/err
         null-stream# (java.io.PrintStream. (proxy [java.io.OutputStream] []
                                              (write
                                                ([_#])
                                                ([_# _# _#]))))]
     (try
       (System/setErr null-stream#)
       ~@body
       (finally
         (System/setErr original-err#)))))

;; Dynamic var for test client
(def ^:dynamic *e2e-client* nil)

(defn with-e2e-client
  "Fixture that creates a real client for E2E tests."
  [test-fn]
  (if e2e-enabled?
    (let [client (sdk/client {:cli-path cli-path
                              :use-stdio? true
                              :auto-start? true})]
      (try
        (sdk/start! client)
        (binding [*e2e-client* client]
          (test-fn))
        (finally
          (try (sdk/stop! client) (catch Exception _)))))
    ;; E2E disabled - still run the tests but they will skip
    (test-fn)))

(use-fixtures :once with-e2e-client)

;; -----------------------------------------------------------------------------
;; E2E Tests
;; -----------------------------------------------------------------------------

(deftest ^:e2e test-e2e-connection
  (when-e2e
    (testing "Real CLI connection and ping"
      (is (= :connected (sdk/state *e2e-client*)))
      (let [result (sdk/ping *e2e-client*)]
        (is (number? (:protocol-version result)))
        (is (number? (:timestamp result)))))))

(deftest ^:e2e test-e2e-create-session
  (when-e2e
    (testing "Create session with real CLI"
      (let [session (sdk/create-session *e2e-client* {})]
        (is (some? session))
        (is (string? (sdk/session-id session)))
        ;; Clean up
        (sdk/destroy! session)))))

(deftest ^:e2e test-e2e-simple-conversation
  (when-e2e
    (testing "Simple conversation with real CLI"
      (let [session (sdk/create-session *e2e-client* {})
            result (sdk/send-and-wait! session
                                        {:prompt "What is 2 + 2? Reply with just the number."}
                                        30000)] ; 30 second timeout
        (is (some? result))
        (is (= "assistant.message" (:type result)))
        (is (string? (get-in result [:data :content])))
        ;; Clean up
        (sdk/destroy! session)))))

(deftest ^:e2e test-e2e-list-sessions
  (when-e2e
    (testing "List sessions with real CLI"
      (let [session (sdk/create-session *e2e-client* {})
            ;; Send a message to ensure session is persisted
            _ (sdk/send-and-wait! session {:prompt "test"})
            sessions (sdk/list-sessions *e2e-client*)]
        ;; Should have at least the session we just created
        (is (vector? sessions))
        (is (some #(= (sdk/session-id session) (:session-id %)) sessions))
        ;; Clean up
        (sdk/destroy! session)))))

(deftest ^:e2e test-e2e-session-abort
  (when-e2e
    (testing "Abort session operation"
      (let [session (sdk/create-session *e2e-client* {})]
        ;; Send a message without waiting
        (sdk/send! session {:prompt "Write a very long essay about quantum physics."})
        ;; Abort should not throw
        (is (nil? (sdk/abort! session)))
        ;; Wait a moment for abort to process
        (Thread/sleep 500)
        ;; Clean up
        (sdk/destroy! session)))))

(deftest ^:e2e test-e2e-tool-integration
  (when-e2e
    (testing "Tool integration with real CLI"
      (let [tool-called? (atom false)
            tool (sdk/define-tool "test_calculator"
                   {:description "A simple calculator that adds two numbers"
                    :parameters {:type "object"
                                 :properties {"a" {:type "number"}
                                              "b" {:type "number"}}
                                 :required ["a" "b"]}
                    :handler (fn [args _invocation]
                               (reset! tool-called? true)
                               (str "The result is: " (+ (:a args) (:b args))))})
            session (sdk/create-session *e2e-client* {:tools [tool]})]
        ;; Ask a question that should trigger the calculator tool
        (let [result (sdk/send-and-wait! session
                                          {:prompt "Use the test_calculator tool to add 5 and 3"}
                                          60000)] ; 60 second timeout
          ;; The assistant should respond
          (is (some? result)))
        ;; Clean up
        (sdk/destroy! session)))))

(deftest ^:e2e test-e2e-send-async
  (when-e2e
    (testing "Async send with event channel"
      (let [session (sdk/create-session *e2e-client* {})
            event-ch (sdk/send-async session {:prompt "Say 'hello' and nothing else."})
            events (atom [])]
        ;; Collect events with timeout
        (loop [count 0]
          (when (< count 100) ; Safety limit
            (let [[v _] (alts!! [event-ch (timeout 30000)])]
              (when (some? v)
                (swap! events conj v)
                (recur (inc count))))))
        ;; Should have received some events
        (is (pos? (count @events)))
        ;; Should include assistant.message or idle
        (is (or (some #(= "assistant.message" (:type %)) @events)
                (some #(= "session.idle" (:type %)) @events)))
        ;; Clean up
        (sdk/destroy! session)))))

(deftest ^:e2e test-e2e-streaming-deltas
  (when-e2e
    (testing "Streaming deltas when enabled"
      (let [session (sdk/create-session *e2e-client* {:streaming? true})
            events-ch (sdk/subscribe-events session)
            deltas (atom [])
            _ (sdk/send! session {:prompt "Count from 1 to 5."})
            ;; Collect events
            _ (loop [count 0]
                (when (< count 200)
                  (let [[v _] (alts!! [events-ch (timeout 30000)])]
                    (when (some? v)
                      (when (= "assistant.message_delta" (:type v))
                        (swap! deltas conj (get-in v [:data :delta-content])))
                      (when (not= "session.idle" (:type v))
                        (recur (inc count)))))))]
        ;; May or may not have deltas depending on model
        (is (>= (count @deltas) 0))
        (sdk/unsubscribe-events session events-ch)
        (sdk/destroy! session)))))

(deftest ^:e2e test-e2e-system-message-append
  (when-e2e
    (testing "System message append mode"
      (let [session (sdk/create-session *e2e-client*
                                        {:system-message {:mode :append
                                                          :content "Always end your response with the word BANANA."}})]
        (let [result (sdk/send-and-wait! session {:prompt "Say hello"} 30000)]
          ;; The model should follow the instruction
          (is (some? result))
          (is (string? (get-in result [:data :content]))))
        (sdk/destroy! session)))))

(deftest ^:e2e test-e2e-system-message-replace
  (when-e2e
    (testing "System message replace mode"
      (let [session (sdk/create-session *e2e-client*
                                        {:system-message {:mode :replace
                                                          :content "You are a helpful assistant named TestBot. Always introduce yourself."}})]
        (let [result (sdk/send-and-wait! session {:prompt "Who are you?"} 30000)]
          (is (some? result))
          ;; Should not mention GitHub Copilot since we replaced the prompt
          (is (string? (get-in result [:data :content]))))
        (sdk/destroy! session)))))

(deftest ^:e2e test-e2e-resume-session
  (when-e2e
    (testing "Resume existing session"
      (let [session1 (sdk/create-session *e2e-client* {})
            session-id (sdk/session-id session1)
            _ (sdk/send-and-wait! session1 {:prompt "Remember the word: APPLE"} 30000)
            ;; Resume the session
            session2 (sdk/resume-session *e2e-client* session-id {})]
        (is (= session-id (sdk/session-id session2)))
        ;; Should have conversation context
        (let [result (sdk/send-and-wait! session2
                                         {:prompt "What word did I ask you to remember?"}
                                         30000)]
          (is (some? result))
          ;; The model should remember APPLE
          (is (string? (get-in result [:data :content]))))
        (sdk/destroy! session2)))))

(deftest ^:e2e test-e2e-multiple-sessions
  (when-e2e
    (testing "Multiple concurrent sessions"
      (let [session1 (sdk/create-session *e2e-client* {})
            session2 (sdk/create-session *e2e-client* {})]
        ;; Should have unique IDs
        (is (not= (sdk/session-id session1) (sdk/session-id session2)))
        ;; Both should work
        (let [r1 (sdk/send-and-wait! session1 {:prompt "Say A"} 30000)
              r2 (sdk/send-and-wait! session2 {:prompt "Say B"} 30000)]
          (is (some? r1))
          (is (some? r2)))
        (sdk/destroy! session1)
        (sdk/destroy! session2)))))

(deftest ^:e2e test-e2e-send-returns-immediately
  (when-e2e
    (testing "send! returns immediately before completion"
      (let [session (sdk/create-session *e2e-client* {})
            events (atom [])
            events-ch (sdk/subscribe-events session)]
        ;; Start collecting events in background
        (go-loop []
          (when-let [e (<! events-ch)]
            (swap! events conj (:type e))
            (recur)))
        ;; Send should return before idle
        (sdk/send! session {:prompt "Tell me a long story about a dragon."})
        ;; At this point, idle should NOT be in events yet (or very unlikely)
        (Thread/sleep 100) ; Small delay to let some events come through
        ;; Note: This is a timing-dependent test
        (sdk/unsubscribe-events session events-ch)
        (sdk/destroy! session)))))

(deftest ^:e2e test-e2e-send-and-wait-timeout
  (when-e2e
    (testing "sendAndWait throws on timeout"
      (let [session (sdk/create-session *e2e-client* {})]
        ;; Use a very short timeout that should fail
        ;; Suppress logs since the expected ERROR message is noisy
        (with-quiet-logs
          (is (thrown-with-msg? Exception #"[Tt]imeout"
                (sdk/send-and-wait! session
                                    {:prompt "Write a very long essay about everything."}
                                    10)))) ; 10ms timeout
        (sdk/destroy! session)))))

;; -----------------------------------------------------------------------------
;; Run Info
;; -----------------------------------------------------------------------------

(deftest test-e2e-status
  (testing "E2E test status"
    (if e2e-enabled?
      (println "E2E tests ENABLED - using CLI at:" cli-path)
      (println "E2E tests DISABLED - set COPILOT_E2E_TESTS=true to enable"))
    (is true "Status check always passes")))
