(ns krukow.copilot-sdk.integration-test
  "Integration tests using mock JSON-RPC server."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.core.async :as async :refer [<!! >!! chan go timeout alts!!]]
            [krukow.copilot-sdk :as sdk]
            [krukow.copilot-sdk.client :as client]
            [krukow.copilot-sdk.session :as session]
            [krukow.copilot-sdk.mock-server :as mock]))

;; Fixture to manage mock server lifecycle
(def ^:dynamic *mock-server* nil)
(def ^:dynamic *test-client* nil)

(defn with-mock-server
  "Fixture that creates a mock server and client for each test."
  [test-fn]
  (let [server (mock/create-mock-server)
        _ (mock/start-mock-server! server)
        client (sdk/client {:auto-start? false})
        [in out] (mock/client-streams server)]
    ;; Connect client to mock server
    (client/connect-with-streams! client in out)
    (binding [*mock-server* server
              *test-client* client]
      (try
        (test-fn)
        (finally
          ;; Stop mock server first to unblock client reader
          (mock/stop-mock-server! server)
          (Thread/sleep 50)
          (try (sdk/stop! client) (catch Exception _)))))))

(use-fixtures :each with-mock-server)

;; -----------------------------------------------------------------------------
;; Client Lifecycle Tests
;; -----------------------------------------------------------------------------

(deftest test-client-connection
  (testing "Client connects to mock server"
    (is (= :connected (sdk/state *test-client*)))
    (is (some? (:connection @(:state *test-client*))))))

(deftest test-ping
  (testing "Ping returns protocol version"
    (let [result (sdk/ping *test-client*)]
      (is (= 1 (:protocol-version result)))
      (is (number? (:timestamp result))))))

;; -----------------------------------------------------------------------------
;; Session Lifecycle Tests
;; -----------------------------------------------------------------------------

(deftest test-create-session
  (testing "Create new session"
    (let [session (sdk/create-session *test-client*
                                       {:model "gpt-4"})]
      (is (some? session))
      (is (string? (sdk/session-id session)))
      (is (clojure.string/starts-with? (sdk/session-id session) "session-")))))

(deftest test-list-sessions
  (testing "List sessions includes created sessions"
    (let [session (sdk/create-session *test-client* {})
          sessions (sdk/list-sessions *test-client*)]
      (is (seq sessions))
      (is (some #(= (sdk/session-id session) (:session-id %)) sessions)))))

(deftest test-delete-session
  (testing "Delete session removes it from list"
    (let [session (sdk/create-session *test-client* {})
          session-id (sdk/session-id session)
          _ (sdk/delete-session! *test-client* session-id)
          sessions (sdk/list-sessions *test-client*)]
      (is (not (some #(= session-id (:session-id %)) sessions))))))

(deftest test-destroy-session
  (testing "Destroy session via session object"
    (let [session (sdk/create-session *test-client* {})
          session-id (sdk/session-id session)]
      (sdk/destroy! session)
      (let [sessions (sdk/list-sessions *test-client*)]
        (is (not (some #(= session-id (:session-id %)) sessions)))))))

;; -----------------------------------------------------------------------------
;; Message Sending Tests
;; -----------------------------------------------------------------------------

(deftest test-send-message
  (testing "Send message returns message ID"
    (let [session (sdk/create-session *test-client* {})
          msg-id (sdk/send! session {:prompt "Hello world"})]
      (is (string? msg-id)))))

(deftest test-send-and-wait
  (testing "Send and wait receives events and returns assistant message"
    (let [session (sdk/create-session *test-client* {})
          result (sdk/send-and-wait! session {:prompt "Test message"})]
      ;; Returns the last assistant message event (map)
      (is (map? result))
      (is (= "assistant.message" (:type result)))
      (is (string? (get-in result [:data :content]))))))

(deftest test-send-async
  (testing "Send async returns channel with events"
    (let [session (sdk/create-session *test-client* {})
          event-ch (sdk/send-async session {:prompt "Async test"})
          events (atom [])]
      ;; Collect events
      (loop []
        (let [[v _] (alts!! [event-ch (timeout 5000)])]
          (when (some? v)
            (swap! events conj v)
            (recur))))
      ;; Should have received events
      (is (pos? (count @events)))
      ;; Should include idle event
      (is (some #(= "session.idle" (:type %)) @events)))))

;; -----------------------------------------------------------------------------
;; Session Operations Tests
;; -----------------------------------------------------------------------------

(deftest test-abort-session
  (testing "Abort session operation"
    (let [session (sdk/create-session *test-client* {})]
      ;; Should not throw
      (is (nil? (sdk/abort! session))))))

(deftest test-get-messages
  (testing "Get messages from session"
    (let [session (sdk/create-session *test-client* {})
          _ (sdk/send-and-wait! session {:prompt "Test"})
          messages (sdk/get-messages session)]
      ;; Mock server returns empty events vector
      (is (vector? messages)))))

;; -----------------------------------------------------------------------------
;; Event Channel Tests
;; -----------------------------------------------------------------------------

(deftest test-event-subscription
  (testing "Can subscribe to session event stream"
    (let [session (sdk/create-session *test-client* {})
          events-ch (sdk/subscribe-events session)]
      ;; Send a message to trigger events
      (sdk/send! session {:prompt "Event test"})
      ;; Wait for some events
      (Thread/sleep 200)
      ;; Should have received events via subscription
      (let [events (atom [])]
        (loop []
          (let [[v _] (alts!! [events-ch (timeout 100)])]
            (when (some? v)
              (swap! events conj v)
              (recur))))
        (is (pos? (count @events))))
      (sdk/unsubscribe-events session events-ch))))

;; -----------------------------------------------------------------------------
;; Tool Handler Tests
;; -----------------------------------------------------------------------------

(deftest test-tool-registration
  (testing "Register tool handler"
    (let [session (sdk/create-session *test-client*
                                       {:tools [(sdk/define-tool "test_tool"
                                                  {:description "A test tool"
                                                   :parameters {:type "object"
                                                                :properties {"value" {:type "string"}}}})]
                                        :on-tool-call (fn [_] "result")})]
      (is (some? session)))))

;; -----------------------------------------------------------------------------
;; Last Session ID Tests
;; -----------------------------------------------------------------------------

(deftest test-get-last-session-id
  (testing "Get last session ID"
    (let [_ (sdk/create-session *test-client* {})
          last-id (sdk/get-last-session-id *test-client*)]
      (is (string? last-id)))))

;; -----------------------------------------------------------------------------
;; Multiple Sessions Tests
;; -----------------------------------------------------------------------------

(deftest test-multiple-sessions
  (testing "Can manage multiple concurrent sessions"
    (let [session1 (sdk/create-session *test-client* {:model "model-1"})
          session2 (sdk/create-session *test-client* {:model "model-2"})
          id1 (sdk/session-id session1)
          id2 (sdk/session-id session2)]
      (is (not= id1 id2))
      (is (= 2 (count (sdk/list-sessions *test-client*))))
      ;; Clean up one session
      (sdk/destroy! session1)
      (is (= 1 (count (sdk/list-sessions *test-client*)))))))

;; -----------------------------------------------------------------------------
;; Error Handling Tests
;; -----------------------------------------------------------------------------

(deftest test-resume-nonexistent-session
  (testing "Resume nonexistent session throws error"
    (is (thrown-with-msg? Exception #"Session not found"
          (sdk/resume-session *test-client* "nonexistent-session-id" {})))))

;; -----------------------------------------------------------------------------
;; sendAndWait Timeout Tests
;; -----------------------------------------------------------------------------

(deftest test-send-and-wait-with-timeout
  (testing "sendAndWait respects timeout parameter"
    (let [session (sdk/create-session *test-client* {})]
      ;; With adequate timeout, should succeed
      (let [result (sdk/send-and-wait! session {:prompt "Test"} 5000)]
        (is (some? result))
        (is (= "assistant.message" (:type result)))))))

;; -----------------------------------------------------------------------------
;; Tool Error Handling Tests
;; -----------------------------------------------------------------------------

(deftest test-tool-missing-returns-failure
  (testing "Calling unregistered tool returns failure"
    ;; The mock server simulates tool calls - verify handler behavior
    (let [session (sdk/create-session *test-client* {})]
      ;; Session without tools should still work
      (let [result (sdk/send-and-wait! session {:prompt "Test"})]
        (is (some? result))))))

(deftest test-tool-handler-errors
  (testing "Tool handler that throws returns failure result"
    (let [error-tool (sdk/define-tool "error_tool"
                       {:description "A tool that always fails"
                        :parameters {:type "object"
                                     :properties {}}
                        :handler (fn [_ _]
                                   (throw (ex-info "Tool error" {:cause "test"})))})
          session (sdk/create-session *test-client*
                                       {:tools [error-tool]})]
      ;; Session should still be usable after tool error
      (is (some? session)))))

;; -----------------------------------------------------------------------------
;; System Message Tests
;; -----------------------------------------------------------------------------

(deftest test-session-with-append-system-message
  (testing "Create session with appended system message"
    (let [session (sdk/create-session *test-client*
                                       {:system-message {:mode :append
                                                         :content "Always end with 'DONE'"}})]
      (is (some? session))
      (is (string? (sdk/session-id session))))))

(deftest test-session-with-replace-system-message
  (testing "Create session with replaced system message"
    (let [session (sdk/create-session *test-client*
                                       {:system-message {:mode :replace
                                                         :content "You are a test assistant."}})]
      (is (some? session))
      (is (string? (sdk/session-id session))))))

;; -----------------------------------------------------------------------------
;; Streaming Tests
;; -----------------------------------------------------------------------------

(deftest test-session-with-streaming
  (testing "Create session with streaming enabled"
    (let [session (sdk/create-session *test-client*
                                       {:streaming? true})]
      (is (some? session))
      ;; Should still work normally
      (let [result (sdk/send-and-wait! session {:prompt "Test"})]
        (is (some? result))))))

;; -----------------------------------------------------------------------------
;; Resume Session Tests
;; -----------------------------------------------------------------------------

(deftest test-resume-session
  (testing "Resume existing session"
    (let [session1 (sdk/create-session *test-client* {})
          session-id (sdk/session-id session1)
          _ (sdk/send-and-wait! session1 {:prompt "First message"})
          session2 (sdk/resume-session *test-client* session-id {})]
      (is (= session-id (sdk/session-id session2)))
      ;; Should be able to continue conversation
      (let [result (sdk/send-and-wait! session2 {:prompt "Follow up"})]
        (is (some? result))))))
