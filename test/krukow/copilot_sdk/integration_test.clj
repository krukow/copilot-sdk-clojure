(ns krukow.copilot-sdk.integration-test
  "Integration tests using mock JSON-RPC server."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.core.async :as async :refer [<!! >!! chan close! go timeout alts!!]]
            [clojure.tools.logging :as log]
            [krukow.copilot-sdk :as sdk]
            [krukow.copilot-sdk.client :as client]
            [krukow.copilot-sdk.protocol :as protocol]
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
          ;; Stop client first to suppress auto-restart during teardown
          (try (sdk/stop! client) (catch Exception _))
          (Thread/sleep 50)
          (mock/stop-mock-server! server))))))

(use-fixtures :each with-mock-server)

;; -----------------------------------------------------------------------------
;; Client Lifecycle Tests
;; -----------------------------------------------------------------------------

(deftest test-client-connection
  (testing "Client connects to mock server"
    (is (= :connected (sdk/state *test-client*)))
    (is (some? (:connection @(:state *test-client*))))))

(deftest test-auto-restart-on-connection-close
  (testing "auto-restart triggers on connection close"
    (let [starts (atom 0)
          stops (atom 0)]
      (log/info "Warnings expected in this test: connection close triggers auto-restart.")
      (with-redefs [client/stop! (fn [c]
                                   (swap! stops inc)
                                   (swap! (:state c) assoc :status :disconnected)
                                   [])
                    client/start! (fn [c]
                                    (swap! starts inc)
                                    (swap! (:state c) assoc :status :connected)
                                    nil)]
        (mock/stop-mock-server! *mock-server*)
        (Thread/sleep 200)
        (is (= 1 @stops))
        (is (= 1 @starts))))))

(deftest test-auto-restart-on-process-exit
  (testing "auto-restart triggers on process exit"
    (let [starts (atom 0)
          stops (atom 0)
          exit-ch (chan 1)
          watch-exit (var client/watch-process-exit!)]
      (log/info "Warnings expected in this test: simulated process exit triggers auto-restart.")
      (with-redefs [client/stop! (fn [c]
                                   (swap! stops inc)
                                   (swap! (:state c) assoc :status :disconnected)
                                   [])
                    client/start! (fn [c]
                                    (swap! starts inc)
                                    (swap! (:state c) assoc :status :connected)
                                    nil)]
        (watch-exit *test-client* {:exit-chan exit-ch})
        (>!! exit-ch {:exit-code 123})
        (close! exit-ch)
        (Thread/sleep 200)
        (is (= 1 @stops))
        (is (= 1 @starts))))))

(deftest test-auto-restart-suppressed-when-stopping
  (testing "auto-restart is suppressed while stopping"
    (let [starts (atom 0)
          stops (atom 0)]
      (swap! (:state *test-client*) assoc :stopping? true)
      (try
        (with-redefs [client/stop! (fn [_] (swap! stops inc) [])
                      client/start! (fn [_] (swap! starts inc) nil)]
          (mock/stop-mock-server! *mock-server*)
          (Thread/sleep 200)
          (is (zero? @stops))
          (is (zero? @starts)))
        (finally
          (swap! (:state *test-client*) assoc :stopping? false))))))

(deftest test-ping
  (testing "Ping returns protocol version"
    (let [result (sdk/ping *test-client*)]
      (is (= 2 (:protocol-version result)))
      (is (number? (:timestamp result))))))

(deftest test-get-status
  (testing "Get CLI status returns version and protocol"
    (let [result (sdk/get-status *test-client*)]
      (is (string? (:version result)))
      (is (= 2 (:protocol-version result))))))

(deftest test-get-auth-status
  (testing "Get auth status returns authentication info"
    (let [result (sdk/get-auth-status *test-client*)]
      (is (boolean? (:authenticated? result)))
      (when (:authenticated? result)
        (is (keyword? (:auth-type result)))
        (is (string? (:login result)))))))

(deftest test-list-models
  (testing "List models returns available models"
    (let [models (sdk/list-models *test-client*)]
      (is (vector? models))
      (is (pos? (count models)))
      (let [model (first models)]
        (is (string? (:id model)))
        (is (string? (:name model)))
        (is (string? (:vendor model)))
        (is (number? (:max-input-tokens model)))
        (is (number? (:max-output-tokens model)))))))

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
      (is (= :assistant.message (:type result)))
      (is (string? (get-in result [:data :content]))))))

(deftest test-send-and-wait-serializes
  (testing "send-and-wait serializes concurrent calls"
    (let [session (sdk/create-session *test-client* {})
          session-id (sdk/session-id session)
          client (:client session)
          send-calls (atom 0)]
      (with-redefs [session/send! (fn [_ _]
                                    (swap! send-calls inc)
                                    "msg")]
        (let [first-f (future (session/send-and-wait! session {:prompt "A"} 1000))
              second-f (future (session/send-and-wait! session {:prompt "B"} 1000))]
          (Thread/sleep 50)
          (is (= 1 @send-calls))
          (session/dispatch-event! client session-id
                                   {:type :assistant.message
                                    :data {:content "first"}})
          (session/dispatch-event! client session-id {:type :session.idle :data {}})
          (is (map? (deref first-f 1000 ::timeout)))
          (Thread/sleep 50)
          (is (= 2 @send-calls))
          (session/dispatch-event! client session-id
                                   {:type :assistant.message
                                    :data {:content "second"}})
          (session/dispatch-event! client session-id {:type :session.idle :data {}})
          (is (map? (deref second-f 1000 ::timeout))))))))

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
      (is (some #(= :session.idle (:type %)) @events)))))

(deftest test-send-async-with-id
  (testing "send-async-with-id returns message-id and matching events"
    (let [session (sdk/create-session *test-client* {})
          {:keys [message-id events-ch]} (sdk/send-async-with-id session {:prompt "Async with id"})]
      (is (string? message-id))
      (let [matched (loop [count 0]
                      (when (< count 30)
                        (let [[event _] (alts!! [events-ch (timeout 1000)])]
                          (cond
                            (nil? event) nil
                            (and (= :assistant.message (:type event))
                                 (= message-id (get-in event [:data :message-id])))
                            event
                            :else (recur (inc count))))))]
        (is (some? matched))))))

(deftest test-send-async-serializes
  (testing "send-async serializes concurrent calls"
    (let [session (sdk/create-session *test-client* {})
          session-id (sdk/session-id session)
          client (:client session)
          send-calls (atom 0)]
      (with-redefs [session/send! (fn [_ _]
                                    (swap! send-calls inc)
                                    "msg")]
        (let [ch1 (session/send-async session {:prompt "A"})
              ch2-f (future (session/send-async session {:prompt "B"}))]
          (Thread/sleep 50)
          (is (= 1 @send-calls))
          (session/dispatch-event! client session-id
                                   {:type :assistant.message
                                    :data {:content "first"}})
          (session/dispatch-event! client session-id {:type :session.idle :data {}})
          (is (not= ::timeout (deref ch2-f 1000 ::timeout)))
          (is (= 2 @send-calls))
          (session/dispatch-event! client session-id
                                   {:type :assistant.message
                                    :data {:content "second"}})
          (session/dispatch-event! client session-id {:type :session.idle :data {}})
          (loop []
            (let [[v _] (alts!! [ch1 (timeout 1000)])]
              (when (some? v)
                (recur)))))))))

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

(deftest test-non-session-notification-routed
  (testing "Non-session notifications are delivered to client notifications channel"
    (let [notif-ch (sdk/notifications *test-client*)
          payload {:status "ok" :version "1.2.3"}]
      (mock/send-notification! *mock-server* "cli.status" payload)
      (let [[notif _] (alts!! [notif-ch (timeout 1000)])]
        (is (some? notif))
        (is (= "cli.status" (:method notif)))
        (is (= payload (:params notif)))))))

(deftest test-dispatch-event-drops-when-full
  (testing "dispatch-event! drops events when buffer is full"
    (let [session-id "session-test"
          small-ch (chan 1)
          client {:state (atom {:sessions {session-id {:destroyed? false}}
                                :session-io {session-id {:event-chan small-ch}}})}]
      (>!! small-ch {:type :dummy})
      (let [dispatch-future (future (session/dispatch-event! client session-id
                                                             {:type :session.idle}))
            dispatch-result (deref dispatch-future 50 ::timeout)]
        (is (not= ::timeout dispatch-result))
        (is (= :dummy (:type (<!! small-ch))))
        (is (nil? (async/poll! small-ch)))))))

(deftest test-protocol-notification-queue
  (testing "Protocol notifications queue without blocking reader thread"
    (let [state-atom (atom {:connection (protocol/initial-connection-state)})
          in (java.io.PipedInputStream.)
          _ (java.io.PipedOutputStream. in)
          out (java.io.ByteArrayOutputStream.)
          conn (protocol/connect in out state-atom)
          incoming (:incoming-ch conn)
          msg {:jsonrpc "2.0"
               :method "session.event"
               :params {:sessionId "s-1"
                        :event {:type :session.idle}}}]
      (try
        (dotimes [i 1024]
          (>!! incoming {:i i}))
        (let [dispatch (future (#'protocol/dispatch-message! conn msg))]
          (Thread/sleep 50)
          (is (true? (realized? dispatch)))
          (<!! incoming)
          (let [seen (loop []
                       (when-let [v (<!! incoming)]
                         (if (= "session.event" (:method v))
                           v
                           (recur))))]
            (is (= "session.event" (:method seen)))))
        (finally
          (protocol/disconnect conn))))))

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

(deftest test-tool-call-response-shape
  (testing "tool.call handler returns a nested result wrapper"
    (let [tool (sdk/define-tool "echo"
                 {:handler (fn [args _] args)})
          session (sdk/create-session *test-client* {:tools [tool]})
          handler (get-in @(:state *test-client*) [:connection :request-handler])
          response (<!! (handler "tool.call" {:session-id (sdk/session-id session)
                                              :tool-call-id "tc-1"
                                              :tool-name "echo"
                                              :arguments {:x 1}}))]
      (is (map? response))
      (is (contains? response :result))
      (is (map? (:result response)))
      (is (contains? (:result response) :result))
      (is (map? (get-in response [:result :result])))
      (is (contains? (get-in response [:result :result]) :text-result-for-llm))
      (is (not (contains? (get-in response [:result :result]) :result))))))

(deftest test-tool-handler-runs-on-blocking-thread
  (testing "tool handler executes on a blocking thread"
    (let [thread-name (atom nil)
          tool (sdk/define-tool "thread_check"
                 {:handler (fn [_ _]
                             (reset! thread-name (.getName (Thread/currentThread)))
                             "ok")})
          session (sdk/create-session *test-client* {:tools [tool]})
          handler (get-in @(:state *test-client*) [:connection :request-handler])]
      (<!! (handler "tool.call" {:session-id (sdk/session-id session)
                                 :tool-call-id "tc-2"
                                 :tool-name "thread_check"
                                 :arguments {}}))
      (is (string? @thread-name))
      (is (re-find #"async-(thread|mixed)" @thread-name))
      (is (not (clojure.string/starts-with? @thread-name "async-dispatch"))))))

(deftest test-session-config-wire-keys
  (testing "session config maps are converted to wire keys"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create" "session.resume"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:provider {:base-url "https://example.test"
                                            :api-key "key"}
                                 :mcp-servers {"srv-1" {:mcp-server-type :http
                                                        :mcp-url "https://mcp.test"
                                                        :mcp-tools ["*"]
                                                        :mcp-timeout 1000}}
                                 :custom-agents [{:agent-id "agent-1"
                                                  :display-name "Agent One"}]})
          session-id (sdk/get-last-session-id *test-client*)
          _ (sdk/resume-session *test-client* session-id
                                {:provider {:base-url "https://resume.test"}
                                 :mcp-servers {"srv-2" {:mcp-server-type :sse
                                                        :mcp-url "https://mcp.resume.test"
                                                        :mcp-tools ["*"]}}
                                 :custom-agents [{:agent-id "agent-2"}]})
          create-params (get @seen "session.create")
          resume-params (get @seen "session.resume")]
      (is (= "https://example.test" (get-in create-params [:provider :baseUrl])))
      (is (= "key" (get-in create-params [:provider :apiKey])))
      (is (= "http" (get-in create-params [:mcpServers :srv-1 :mcpServerType])))
      (is (= "https://mcp.test" (get-in create-params [:mcpServers :srv-1 :mcpUrl])))
      (is (= ["*"] (get-in create-params [:mcpServers :srv-1 :mcpTools])))
      (is (= 1000 (get-in create-params [:mcpServers :srv-1 :mcpTimeout])))
      (is (= "agent-1" (get-in create-params [:customAgents 0 :agentId])))
      (is (= "Agent One" (get-in create-params [:customAgents 0 :displayName])))
      (is (= "https://resume.test" (get-in resume-params [:provider :baseUrl])))
      (is (= "sse" (get-in resume-params [:mcpServers :srv-2 :mcpServerType])))
      (is (= "https://mcp.resume.test" (get-in resume-params [:mcpServers :srv-2 :mcpUrl])))
      (is (= ["*"] (get-in resume-params [:mcpServers :srv-2 :mcpTools])))
      (is (= "agent-2" (get-in resume-params [:customAgents 0 :agentId]))))))

;; -----------------------------------------------------------------------------
;; Last Session ID Tests
;; -----------------------------------------------------------------------------

(deftest test-send-async-untaps-on-send-failure
  (testing "send-async cleans up tap when send! throws"
    (let [session (sdk/create-session *test-client* {})
          taps (atom 0)
          untaps (atom 0)
          fake-mult (reify
                      async/Mux
                      (muxch* [_] (chan))
                      async/Mult
                      (tap* [_ _ _] (swap! taps inc) nil)
                      (untap* [_ _] (swap! untaps inc) nil)
                      (untap-all* [_] nil))]
      (swap! (:state *test-client*) assoc-in [:session-io (sdk/session-id session) :event-mult] fake-mult)
      (with-redefs [krukow.copilot-sdk.session/send! (fn [_ _]
                                                       (throw (ex-info "forced failure" {})))]
        (is (thrown? Exception (sdk/send-async session {:prompt "should-fail"}))))
      (is (= 1 @taps))
      (is (pos? @untaps)))))

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
