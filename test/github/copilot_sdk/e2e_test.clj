(ns github.copilot-sdk.e2e-test
  "End-to-end tests using real Copilot CLI.
   
   These tests are gated by environment variables:
   - COPILOT_CLI_PATH: Path to copilot CLI executable (required)
   - COPILOT_E2E_TESTS: Set to 'true' to enable these tests
   
   Run with: COPILOT_E2E_TESTS=true COPILOT_CLI_PATH=/path/to/copilot clojure -M:test"
  (:require [clojure.core.async :refer [alts!! timeout]]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.teardown :as teardown])
  (:import [java.nio.file Files]))

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

(defn- await-event-type!
  [events-ch event-type timeout-ms]
  (let [deadline (timeout timeout-ms)]
    (loop []
      (let [[event port] (alts!! [events-ch deadline])]
        (cond
          (= port deadline)
          (throw (ex-info (str "Timed out waiting for " event-type)
                          {:event-type event-type :timeout-ms timeout-ms}))

          (nil? event)
          (throw (ex-info (str "Event channel closed while waiting for " event-type)
                          {:event-type event-type}))

          (= event-type (:type event))
          event

          :else
          (recur))))))

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

(defn- delete-tree!
  [root]
  (when (.exists root)
    (doseq [file (reverse (file-seq root))]
      (io/delete-file file))))

(defn- stop-client-failures
  [resource copilot-client]
  (teardown/attempt-collecting
   {:operation :stop :resource resource}
   (sdk/stop! copilot-client)))

(defn- delete-tree-failures
  [resource root]
  (teardown/collect
   [(teardown/attempt
     {:operation :delete :resource resource}
     (delete-tree! root))]))

(defn- disconnect-session-failures
  [resource copilot-session]
  (teardown/collect
   [(teardown/attempt
     {:operation :disconnect :resource resource}
     (sdk/disconnect! copilot-session))]))

(defn- throw-cleanup-failures!
  [message failures]
  (when (seq failures)
    (let [aggregate (ex-info message
                             {:cleanup-failures failures}
                             (first failures))]
      (doseq [failure (rest failures)]
        (.addSuppressed aggregate failure))
      (throw aggregate))))

(defn with-e2e-client
  "Fixture that creates a real client for E2E tests."
  [test-fn]
  (if e2e-enabled?
    (let [home (.toFile
                (Files/createTempDirectory
                 "copilot-sdk-clojure-e2e-"
                 (make-array java.nio.file.attribute.FileAttribute 0)))
          home-path (.getCanonicalPath home)
          client (atom nil)]
      (teardown/call-with-cleanup
       #(let [copilot-client
              (sdk/client {:cli-path cli-path
                           :use-stdio? true
                           :auto-start? true
                           :copilot-home home-path})]
          (reset! client copilot-client)
          (sdk/start! copilot-client)
          (binding [*e2e-client* copilot-client]
            (test-fn)))
       #(let [failures
              (into []
                    cat
                    [(when-let [copilot-client @client]
                       (stop-client-failures :e2e-client copilot-client))
                     (delete-tree-failures :e2e-home home)])]
          (throw-cleanup-failures!
           "Failed to clean up the E2E fixture"
           failures))))
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
       ;; Upstream PR #1340 / CLI 1.0.51 changed timestamp to ISO 8601 string.
       (is (string? (:timestamp result)))
       (is (some? (java.time.Instant/parse (:timestamp result)))
           ":timestamp parses as ISO 8601 instant on real CLI ≥ 1.0.51")))))

(deftest ^:e2e test-e2e-create-session
  (when-e2e
   (testing "Create session with real CLI"
     (let [session (sdk/create-session *e2e-client* {:on-permission-request sdk/approve-all})]
       (is (some? session))
       (is (string? (sdk/session-id session)))
        ;; Clean up
       (sdk/destroy! session)))))

(deftest ^:e2e test-e2e-simple-conversation
  (when-e2e
   (testing "Simple conversation with real CLI"
     (let [session (sdk/create-session *e2e-client* {:on-permission-request sdk/approve-all})
           result (sdk/send-and-wait! session
                                      {:prompt "What is 2 + 2? Reply with just the number."}
                                      30000)] ; 30 second timeout
       (is (some? result))
       (is (= :copilot/assistant.message (:type result)))
       (is (string? (get-in result [:data :content])))
        ;; Clean up
       (sdk/destroy! session)))))

(deftest ^:e2e test-e2e-list-sessions
  (when-e2e
   (testing "List sessions with real CLI"
     (let [session (sdk/create-session *e2e-client* {:on-permission-request sdk/approve-all})
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
     (let [session (sdk/create-session *e2e-client* {:on-permission-request sdk/approve-all})
           events-ch (sdk/subscribe-events session)]
       (try
         (sdk/send! session
                    {:prompt "run the shell command 'sleep 100' (note this works on both bash and PowerShell)"})
         (await-event-type! events-ch :copilot/tool.execution_start 60000)
         (let [abort-result (sdk/abort! session)]
           (await-event-type! events-ch :copilot/session.idle 30000)
           (is (and (nil? abort-result)
                    (some #(= :copilot/abort (:type %))
                          (sdk/get-messages session)))
               "abort! should return nil and persist an abort event for the active turn"))
         (finally
           (sdk/unsubscribe-events! session events-ch)
           (sdk/destroy! session)))))))

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
           session (sdk/create-session *e2e-client* {:on-permission-request sdk/approve-all :tools [tool]})]
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
     (let [session (sdk/create-session *e2e-client* {:on-permission-request sdk/approve-all})
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
       (is (or (some #(= :copilot/assistant.message (:type %)) @events)
               (some #(= :copilot/session.idle (:type %)) @events)))
        ;; Clean up
       (sdk/destroy! session)))))

(deftest ^:e2e test-e2e-system-message-append
  (when-e2e
   (testing "System message append mode"
     (let [session (sdk/create-session *e2e-client*
                                       {:on-permission-request sdk/approve-all
                                        :system-message {:mode :append
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
                                       {:on-permission-request sdk/approve-all
                                        :system-message {:mode :replace
                                                         :content "You are a helpful assistant named TestBot. Always introduce yourself."}})]
       (let [result (sdk/send-and-wait! session {:prompt "Who are you?"} 30000)]
         (is (some? result))
          ;; Should not mention GitHub Copilot since we replaced the prompt
         (is (string? (get-in result [:data :content]))))
       (sdk/destroy! session)))))

(deftest ^:e2e test-e2e-resume-session
  (when-e2e
   (testing "Resume an active session through a second TCP client"
     (let [home (.toFile
                 (Files/createTempDirectory
                  "copilot-sdk-clojure-resume-e2e-"
                  (make-array java.nio.file.attribute.FileAttribute 0)))
           home-path (.getCanonicalPath home)
           connection-token (str (java.util.UUID/randomUUID))
           owner-client (atom nil)
           resume-client (atom nil)
           sessions (atom [])]
       (teardown/call-with-cleanup
        #(let [client1
               (sdk/client {:cli-path cli-path
                            :use-stdio? false
                            :port 0
                            :tcp-connection-token connection-token
                            :auto-start? false
                            :copilot-home home-path})]
           (reset! owner-client client1)
           (sdk/start! client1)
           (let [session1 (sdk/create-session client1 {})
                 _registered-session1 (swap! sessions conj session1)
                 session-id (sdk/session-id session1)
                 first-response
                 (sdk/send-and-wait!
                  session1
                  {:prompt "What is 1 + 1? Reply with just the number."}
                  60000)
                 first-content (get-in first-response [:data :content])
                 port (:actual-port @(:state client1))
                 client2 (sdk/client {:cli-url (str "localhost:" port)
                                      :tcp-connection-token connection-token
                                      :auto-start? false})]
             (is (and (string? first-content)
                      (re-find #"\b2\b" first-content))
                 "the original session should produce the expected response")
             (reset! resume-client client2)
             (sdk/start! client2)
             (let [session2 (sdk/resume-session client2 session-id {})
                   _registered-session2 (swap! sessions conj session2)
                   history-types (set (map :type (sdk/get-messages session2)))
                   second-response
                   (sdk/send-and-wait!
                    session2
                    {:prompt "Add 2 to your previous answer. Reply with just the number."}
                    60000)
                   second-content (get-in second-response [:data :content])]
               (is (contains? history-types :copilot/user.message)
                   "resumed history should include the original user message")
               (is (contains? history-types :copilot/session.resume)
                   "resumed history should record the resume event")
               (is (and (string? second-content)
                        (re-find #"\b4\b" second-content))
                   "the resumed client should continue the conversation"))))
        #(let [failures
               (into []
                     cat
                     [(into []
                            (mapcat (fn [session]
                                      (disconnect-session-failures
                                       :resumed-session
                                       session)))
                            (reverse @sessions))
                      (when-let [client2 @resume-client]
                        (stop-client-failures :resume-client client2))
                      (when-let [client1 @owner-client]
                        (stop-client-failures :owner-client client1))
                      (delete-tree-failures :resume-home home)])]
           (throw-cleanup-failures!
            "Failed to clean up the TCP resume E2E resources"
            failures)))))))

(deftest ^:e2e test-e2e-multiple-sessions
  (when-e2e
   (testing "Multiple concurrent sessions"
     (let [session1 (sdk/create-session *e2e-client* {:on-permission-request sdk/approve-all})
           session2 (sdk/create-session *e2e-client* {:on-permission-request sdk/approve-all})]
        ;; Should have unique IDs
       (is (not= (sdk/session-id session1) (sdk/session-id session2)))
        ;; Both should work
       (let [r1 (sdk/send-and-wait! session1 {:prompt "Say A"} 30000)
             r2 (sdk/send-and-wait! session2 {:prompt "Say B"} 30000)]
         (is (some? r1))
         (is (some? r2)))
       (sdk/destroy! session1)
       (sdk/destroy! session2)))))

(deftest ^:e2e test-e2e-send-and-wait-timeout
  (when-e2e
   (testing "sendAndWait throws on timeout"
     (let [session (sdk/create-session *e2e-client* {:on-permission-request sdk/approve-all})]
        ;; Use a very short timeout that should fail
        ;; Suppress logs since the expected ERROR message is noisy
       (with-quiet-logs
         (is (thrown-with-msg? Exception #"[Tt]imeout"
                               (sdk/send-and-wait! session
                                                   {:prompt "Write a very long essay about everything."}
                                                   10)))) ; 10ms timeout
       (sdk/destroy! session)))))

(deftest ^:e2e test-e2e-blob-attachment
  (when-e2e
   (testing "send with blob attachment does not throw"
     (let [session (sdk/create-session *e2e-client* {:on-permission-request sdk/approve-all})]
       (let [response (sdk/send-and-wait!
                       session
                       {:prompt "Reply with just the word OK."
                        :attachments [{:type :blob
                                       :data "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
                                       :mime-type "image/png"
                                       :display-name "test-pixel.png"}]}
                       60000)]
         (is (some? response) "should receive a response"))
       (sdk/disconnect! session)))))
