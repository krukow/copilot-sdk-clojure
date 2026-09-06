(ns github.copilot-sdk.integration.sessions-commands-test
  "Focused integration tests using the mock JSON-RPC server."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.core.async :as async :refer [<!! >!! chan close! go timeout alts!!]]
            [clojure.core.async.impl.protocols :as async-protocols]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.tools.logging.test :as log-test]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.client :as client]
            [github.copilot-sdk.factory :as factory]
            [github.copilot-sdk.protocol :as protocol]
            [github.copilot-sdk.process :as proc]
            [github.copilot-sdk.session :as session]
            [github.copilot-sdk.specs :as specs]
            [github.copilot-sdk.tools :as tools]
            [github.copilot-sdk.util :as util]
            [github.copilot-sdk.generated.event-specs :as generated-events]
            [github.copilot-sdk.integration.support
             :refer [*mock-server*
                     *test-client*
                     await-value!
                     await-atom!
                     await-event-type!
                     observe-take-attempts
                     with-mock-server]]
            [github.copilot-sdk.mock-server :as mock]))

(use-fixtures :each with-mock-server)

(deftest test-cli-1.0.46-sync-spec-additions
  (testing "::permission-kind accepts new extension-* kinds (CLI 1.0.44-3)"
    (is (s/valid? :github.copilot-sdk.specs/permission-kind :extension-management))
    (is (s/valid? :github.copilot-sdk.specs/permission-kind :extension-permission-access))
    (is (false? (s/valid? :github.copilot-sdk.specs/permission-kind :bogus-kind))))

  (testing "::assistant.message-data accepts :server-tools + :service-request-id (CLI 1.0.63)"
    (is (s/valid? :github.copilot-sdk.specs/assistant.message-data
                  {:message-id "m1"
                   :content "answer"
                   :server-tools {:advisor-model "claude-advisor"}
                   :service-request-id "req-1"
                   :model "gpt-5"}))
    (is (false? (s/valid? :github.copilot-sdk.specs/assistant.message-data
                          {:message-id "m1"
                           :content "answer"
                           :server-tools "not-a-map"})))
    (is (false? (s/valid? :github.copilot-sdk.specs/assistant.message-data
                          {:message-id "m1"
                           :content "answer"
                           :service-request-id 42}))))

  (testing "::session.start-data accepts :detached-from-spawning-parent-session-id (CLI 1.0.44-3)"
    (is (s/valid? :github.copilot-sdk.specs/session.start-data
                  {:session-id "s1"
                   :detached-from-spawning-parent-session-id "parent-s0"})))

  (testing "::model-info accepts model-picker categorization fields (CLI 1.0.46)"
    (is (s/valid? :github.copilot-sdk.specs/model-info
                  {:id "m1"
                   :name "Model 1"
                   :model-picker-category "powerful"
                   :model-picker-price-category "very_high"}))
    ;; Open string enum — unknown values should still validate as strings.
    (is (s/valid? :github.copilot-sdk.specs/model-info
                  {:id "m1"
                   :name "Model 1"
                   :model-picker-category "future-tier-not-yet-defined"}))))

(deftest test-list-models-surfaces-model-picker-fields
  (testing "list-models exposes modelPickerCategory and modelPickerPriceCategory (CLI 1.0.46)"
    (mock/set-request-hook! *mock-server*
                            (fn [method _params]
                              (when (= "models.list" method)
                                {:github.copilot-sdk.mock-server/merge-response
                                 {:models [{:id "m-picker"
                                            :name "Picker Model"
                                            :modelPickerCategory "powerful"
                                            :modelPickerPriceCategory "very_high"}]}})))
    (let [models (sdk/list-models *test-client*)
          m (first models)]
      (is (= "m-picker" (:id m)))
      (is (= "powerful" (:model-picker-category m)))
      (is (= "very_high" (:model-picker-price-category m))))))

(deftest test-respond-to-queued-command
  (testing "respond-to-queued-command! sends correct wire shape with handled=true"
    (let [requests (atom [])
          session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})]
      (mock/set-request-hook! *mock-server*
                              (fn [method params]
                                (swap! requests conj {:method method :params params})
                                nil))
      (session/respond-to-queued-command! session
                                          {:request-id "cmd-q-1"
                                           :handled? true
                                           :stop-processing-queue? true})
      (let [req (first (filter #(= "session.commands.respondToQueuedCommand" (:method %)) @requests))]
        (is (some? req))
        (is (= "cmd-q-1" (:requestId (:params req))))
        (is (= {:handled true :stopProcessingQueue true} (:result (:params req)))))))

  (testing "respond-to-queued-command! sends correct wire shape with handled=false"
    (let [requests (atom [])
          session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})]
      (mock/set-request-hook! *mock-server*
                              (fn [method params]
                                (swap! requests conj {:method method :params params})
                                nil))
      (session/respond-to-queued-command! session
                                          {:request-id "cmd-q-2"
                                           :handled? false})
      (let [req (first (filter #(= "session.commands.respondToQueuedCommand" (:method %)) @requests))]
        (is (some? req))
        (is (= "cmd-q-2" (:requestId (:params req))))
        (is (= {:handled false} (:result (:params req)))))))

  (testing "respond-to-queued-command! forwards explicit stop-processing-queue?=false"
    (let [requests (atom [])
          session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})]
      (mock/set-request-hook! *mock-server*
                              (fn [method params]
                                (swap! requests conj {:method method :params params})
                                nil))
      (session/respond-to-queued-command! session
                                          {:request-id "cmd-q-3"
                                           :handled? true
                                           :stop-processing-queue? false})
      (let [req (first (filter #(= "session.commands.respondToQueuedCommand" (:method %)) @requests))]
        (is (some? req))
        (is (= {:handled true :stopProcessingQueue false} (:result (:params req)))
            "explicit false should be forwarded on the wire (not silently dropped)")))))

(deftest test-send-async-untaps-on-send-failure
  (testing "send-async cleans up tap when RPC fails"
    (log/info "Warnings expected in this test: async send RPC error is deliberate.")
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
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
      ;; <send-async* uses proto/send-request — return an error response
      (with-redefs [protocol/send-request (fn [_ _ _]
                                            (let [ch (async/chan 1)]
                                              (async/put! ch {:error {:code -1 :message "forced failure"}})
                                              (async/close! ch)
                                              ch))]
        (let [events-ch (sdk/send-async session {:prompt "should-fail"})]
          ;; Channel should close without events (error path)
          (is (nil? (<!! events-ch)))))
      (is (= 1 @taps))
      (is (pos? @untaps)))))

(deftest test-get-last-session-id
  (testing "Get last session ID"
    (let [_ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          last-id (sdk/get-last-session-id *test-client*)]
      (is (string? last-id)))))

(deftest test-multiple-sessions
  (testing "Can manage multiple concurrent sessions"
    (let [session1 (sdk/create-session *test-client* {:on-permission-request sdk/approve-all :model "model-1"})
          session2 (sdk/create-session *test-client* {:on-permission-request sdk/approve-all :model "model-2"})
          id1 (sdk/session-id session1)
          id2 (sdk/session-id session2)]
      (is (not= id1 id2))
      (is (= 2 (count (sdk/list-sessions *test-client*))))
      ;; Detaching preserves resumable state; explicit deletion removes it.
      (sdk/destroy! session1)
      (is (= 2 (count (sdk/list-sessions *test-client*))))
      (sdk/delete-session! *test-client* id1)
      (is (= 1 (count (sdk/list-sessions *test-client*)))))))

(deftest test-resume-nonexistent-session
  (testing "Resume nonexistent session throws error"
    (is (thrown-with-msg? Exception #"Session not found"
                          (sdk/resume-session *test-client* "nonexistent-session-id" {:on-permission-request sdk/approve-all})))))

(deftest test-tool-handler-errors
  (testing "Tool handler that throws returns failure result"
    (let [error-tool (sdk/define-tool "error_tool"
                       {:description "A tool that always fails"
                        :parameters {:type "object"
                                     :properties {}}
                        :handler (fn [_ _]
                                   (throw (ex-info "Tool error" {:cause "test"})))})
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :tools [error-tool]})]
      ;; Session should still be usable after tool error
      (is (some? session)))))

(deftest test-session-with-append-system-message
  (testing "Create session with appended system message"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :system-message {:mode :append
                                                        :content "Always end with 'DONE'"}})]
      (is (some? session))
      (is (string? (sdk/session-id session))))))

(deftest test-session-with-replace-system-message
  (testing "Create session with replaced system message"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :system-message {:mode :replace
                                                        :content "You are a test assistant."}})]
      (is (some? session))
      (is (string? (sdk/session-id session))))))

(deftest test-session-with-streaming
  (testing "Create session with streaming enabled"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :streaming? true})]
      (is (some? session))
      ;; Should still work normally
      (let [result (sdk/send-and-wait! session {:prompt "Test"})]
        (is (some? result))))))

(deftest test-resume-session
  (testing "Resume existing session"
    (let [session1 (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session1)
          _ (sdk/send-and-wait! session1 {:prompt "First message"})
          session2 (sdk/resume-session *test-client* session-id {:on-permission-request sdk/approve-all})]
      (is (= session-id (sdk/session-id session2)))
      ;; Should be able to continue conversation
      (let [result (sdk/send-and-wait! session2 {:prompt "Follow up"})]
        (is (some? result))))))

(deftest test-session-snapshot-rewind-event
  (testing "session.snapshot_rewind event is received and parsed correctly"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)
          events-ch (sdk/subscribe-events session)]
      (try
        (mock/send-session-event! *mock-server* session-id
                                  :copilot/session.snapshot_rewind
                                  {:upToEventId "evt-42"
                                   :eventsRemoved 5}
                                  :ephemeral? true)
        ;; The following marker fences all earlier notifications on the same
        ;; ordered transport, making duplicate rewind delivery observable.
        (mock/send-session-event! *mock-server* session-id
                                  :copilot/session.idle
                                  {})
        (let [deadline (timeout 1000)
              rewind-events
              (loop [events []]
                (let [[event port] (alts!! [events-ch deadline])]
                  (cond
                    (= port deadline)
                    (throw (ex-info "Timed out waiting for snapshot rewind marker"
                                    {:timeout-ms 1000}))

                    (nil? event)
                    (throw (ex-info "Event channel closed before snapshot rewind marker" {}))

                    (= :copilot/session.idle (:type event))
                    events

                    (= :copilot/session.snapshot_rewind (:type event))
                    (recur (conj events event))

                    :else
                    (recur events))))
              event (first rewind-events)]
          (is (= 1 (count rewind-events)))
          (is (= :copilot/session.snapshot_rewind (:type event)))
          (is (= "evt-42" (get-in event [:data :up-to-event-id])))
          (is (= 5 (get-in event [:data :events-removed]))))
        (finally
          (sdk/unsubscribe-events! session events-ch))))))

(deftest test-<create-session
  (testing "<create-session creates session asynchronously"
    (let [result-ch (sdk/<create-session *test-client* {:on-permission-request sdk/approve-all :model "gpt-4.1"})
          [session _] (alts!! [result-ch (timeout 5000)])]
      (is (some? session) "<create-session should deliver a session")
      (is (not (instance? Throwable session)) "<create-session should not return an error")
      (is (string? (sdk/session-id session)))
      (sdk/destroy! session))))

(deftest test-<create-session-parallel
  (testing "Multiple <create-session calls run concurrently in go blocks"
    (let [ch1 (sdk/<create-session *test-client* {:on-permission-request sdk/approve-all :model "gpt-4.1"})
          ch2 (sdk/<create-session *test-client* {:on-permission-request sdk/approve-all :model "gpt-4.1"})
          ch3 (sdk/<create-session *test-client* {:on-permission-request sdk/approve-all :model "gpt-4.1"})
          [s1 _] (alts!! [ch1 (timeout 5000)])
          [s2 _] (alts!! [ch2 (timeout 5000)])
          [s3 _] (alts!! [ch3 (timeout 5000)])]
      (is (not (instance? Throwable s1)) "<create-session s1 should not return an error")
      (is (not (instance? Throwable s2)) "<create-session s2 should not return an error")
      (is (not (instance? Throwable s3)) "<create-session s3 should not return an error")
      (is (some? s1))
      (is (some? s2))
      (is (some? s3))
      (is (= 3 (count (set [(sdk/session-id s1) (sdk/session-id s2) (sdk/session-id s3)]))))
      (sdk/destroy! s1)
      (sdk/destroy! s2)
      (sdk/destroy! s3))))

(deftest test-commands-on-wire
  (testing "commands are sent on wire as name+description only"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create" "session.resume"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :commands [{:name "deploy"
                                             :description "Deploy the app"
                                             :command-handler (fn [_ctx] nil)}
                                            {:name "rollback"
                                             :command-handler (fn [_ctx] nil)}]})
          session-id (sdk/get-last-session-id *test-client*)
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :commands [{:name "status"
                                             :description "Check status"
                                             :command-handler (fn [_ctx] nil)}]})
          create-params (get @seen "session.create")
          resume-params (get @seen "session.resume")]
      ;; Commands are sent with name and description only (no handler)
      (is (= [{:name "deploy" :description "Deploy the app"}
              {:name "rollback" :description ""}]
             (:commands create-params)))
      (is (= [{:name "status" :description "Check status"}]
             (:commands resume-params))))))

(deftest test-command-execute-v3
  (testing "v3 command.execute event routes to handler and sends RPC response"
    (let [requests (atom [])
          handler-called (atom nil)
          rpc-latch (java.util.concurrent.CountDownLatch. 1)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})
                                      (when (= "session.commands.handlePendingCommand" method)
                                        (.countDown rpc-latch))))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :commands [{:name "deploy"
                                                   :command-handler (fn [ctx]
                                                                      (reset! handler-called ctx))}]})
          session-id (sdk/session-id session)]
      ;; Force protocol v3
      (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
      (reset! requests [])
      ;; Inject command.execute event
      (mock/send-v3-broadcast-event! *mock-server* session-id
                                     "command.execute"
                                     {:requestId "cmd-req-1"
                                      :command "/deploy production"
                                      :commandName "deploy"
                                      :args "production"})
      (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS))
      ;; Handler was called with context
      (is (some? @handler-called))
      (is (= session-id (:session-id @handler-called)))
      (is (= "/deploy production" (:command @handler-called)))
      (is (= "deploy" (:command-name @handler-called)))
      (is (= "production" (:args @handler-called)))
      ;; handlePendingCommand RPC was sent
      (let [cmd-rpcs (filter #(= "session.commands.handlePendingCommand" (:method %)) @requests)]
        (is (= 1 (count cmd-rpcs)))
        (when (seq cmd-rpcs)
          (is (= "cmd-req-1" (:requestId (:params (first cmd-rpcs)))))
          (is (nil? (:error (:params (first cmd-rpcs))))))))))

(deftest test-command-error-rpc
  (testing "command errors are reported via handlePendingCommand"
    (doseq [[desc commands req-id command command-name expected-error]
            [["unknown command reports an error"
              [{:name "deploy" :command-handler (fn [_] nil)}]
              "cmd-req-2" "/unknown" "unknown" #"Unknown command"]
             ["handler exception reports the exception message"
              [{:name "fail" :command-handler (fn [_] (throw (Exception. "deploy failed")))}]
              "cmd-req-3" "/fail" "fail" "deploy failed"]]]
      (testing desc
        (let [requests (atom [])
              rpc-latch (java.util.concurrent.CountDownLatch. 1)
              _ (mock/set-request-hook! *mock-server*
                                        (fn [method params]
                                          (swap! requests conj {:method method :params params})
                                          (when (= "session.commands.handlePendingCommand" method)
                                            (.countDown rpc-latch))))
              session (sdk/create-session *test-client*
                                          {:on-permission-request sdk/approve-all
                                           :commands commands})
              session-id (sdk/session-id session)]
          (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
          (reset! requests [])
          (mock/send-v3-broadcast-event! *mock-server* session-id
                                         "command.execute"
                                         {:requestId req-id
                                          :command command
                                          :commandName command-name
                                          :args ""})
          (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS))
          (let [cmd-rpcs (filter #(= "session.commands.handlePendingCommand" (:method %)) @requests)
                err (:error (:params (first cmd-rpcs)))]
            (is (= 1 (count cmd-rpcs)))
            (when (seq cmd-rpcs)
              (if (string? expected-error)
                (is (= expected-error err))
                (is (re-find expected-error err))))))))))

(deftest test-session-capabilities
  (testing "capabilities default to empty map when not in response"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (is (= {} (sdk/capabilities session)))
      (is (false? (sdk/elicitation-supported? session))))))

(deftest test-session-capabilities-from-response
  (testing "capabilities stored from session.create response"
    (let [_ (mock/set-request-hook! *mock-server*
                                    (fn [method _params]
                                      (when (= "session.create" method)
                                        {::mock/merge-response {:capabilities {:ui {:elicitation true}}}})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (is (= {:ui {:elicitation true}} (sdk/capabilities session)))
      (is (true? (sdk/elicitation-supported? session))))))

(deftest test-elicitation-throws-when-unsupported
  (testing "elicitation convenience methods throw when not supported"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not supported"
                            (sdk/confirm! session "test")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not supported"
                            (sdk/select! session "test" ["a" "b"])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not supported"
                            (sdk/input! session "test"))))))

(deftest test-session-without-commands
  (testing "session without commands has empty command handlers"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (is (= {} (get-in @(:state *test-client*)
                        [:sessions (sdk/session-id session) :command-handlers]))))))

(deftest test-request-elicitation-wire-flag
  (testing "requestElicitation is true when :on-elicitation-request is provided"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :on-elicitation-request (fn [_ctx] {:action "cancel"})})
          create-rpcs (filter #(= "session.create" (:method %)) @requests)]
      (is (= 1 (count create-rpcs)))
      (when (seq create-rpcs)
        (is (true? (:requestElicitation (:params (first create-rpcs))))))))

  (testing "requestElicitation is false when :on-elicitation-request is not provided"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          create-rpcs (filter #(= "session.create" (:method %)) @requests)]
      (is (= 1 (count create-rpcs)))
      (when (seq create-rpcs)
        (is (false? (:requestElicitation (:params (first create-rpcs)))))))))

(deftest test-elicitation-requested-v3
  (testing "v3 elicitation.requested event routes to handler and sends RPC response"
    (let [requests (atom [])
          handler-called (atom nil)
          rpc-latch (java.util.concurrent.CountDownLatch. 1)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})
                                      (when (= "session.ui.handlePendingElicitation" method)
                                        (.countDown rpc-latch))))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :on-elicitation-request
                                       (fn [context]
                                         (reset! handler-called context)
                                         {:action "accept"
                                          :content {:name "test-value"}})})
          session-id (sdk/session-id session)]
      (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
      (reset! requests [])
      ;; Inject elicitation.requested event
      (mock/send-v3-broadcast-event! *mock-server* session-id
                                     "elicitation.requested"
                                     {:requestId "elicit-req-1"
                                      :message "Enter your name"
                                      :requestedSchema {:type "object"
                                                        :properties {"name" {:type "string"}}}
                                      :mode "form"
                                      :elicitationSource "mcp-server"})
      (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS))
      ;; Handler was called with ElicitationContext (single arg, includes session-id)
      (is (some? @handler-called))
      (is (= "Enter your name" (:message @handler-called)))
      (is (= session-id (:session-id @handler-called)))
      ;; handlePendingElicitation RPC was sent with handler's result
      (let [rpcs (filter #(= "session.ui.handlePendingElicitation" (:method %)) @requests)]
        (is (= 1 (count rpcs)))
        (when (seq rpcs)
          (is (= "elicit-req-1" (:requestId (:params (first rpcs)))))
          (is (= "accept" (get-in (first rpcs) [:params :result :action]))))))))

(deftest test-elicitation-response-omits-nil-content
  (let [requests (atom [])
        rpc-latch (java.util.concurrent.CountDownLatch. 1)
        _ (mock/set-request-hook! *mock-server*
                                  (fn [method params]
                                    (when (= "session.ui.handlePendingElicitation" method)
                                      (swap! requests conj params)
                                      (.countDown rpc-latch))))
        session (sdk/create-session *test-client*
                                    {:on-elicitation-request
                                     (fn [_]
                                       {:action "cancel"
                                        :content nil})})
        session-id (sdk/session-id session)]
    (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
    (mock/send-v3-broadcast-event! *mock-server* session-id
                                   "elicitation.requested"
                                   {:requestId "elicit-without-content"
                                    :message "Cancel"})
    (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS))
    (is (= "cancel" (get-in (first @requests) [:result :action])))
    (is (not (contains? (:result (first @requests)) :content)))))

(deftest test-elicitation-handler-error-sends-cancel
  (testing "handler exception sends cancel response to avoid hanging"
    (let [requests (atom [])
          rpc-latch (java.util.concurrent.CountDownLatch. 1)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})
                                      (when (= "session.ui.handlePendingElicitation" method)
                                        (.countDown rpc-latch))))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :on-elicitation-request
                                       (fn [_ctx]
                                         (throw (Exception. "UI unavailable")))})
          session-id (sdk/session-id session)]
      (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
      (reset! requests [])
      (mock/send-v3-broadcast-event! *mock-server* session-id
                                     "elicitation.requested"
                                     {:requestId "elicit-req-2"
                                      :message "Prompt"})
      (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS))
      (let [rpcs (filter #(= "session.ui.handlePendingElicitation" (:method %)) @requests)]
        (is (= 1 (count rpcs)))
        (when (seq rpcs)
          (is (= "cancel" (get-in (first rpcs) [:params :result :action]))))))))

(deftest test-capabilities-changed-event
  (testing "capabilities.changed broadcast updates session capabilities"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)
          events-ch (sdk/subscribe-events session)]
      (try
        (is (false? (sdk/elicitation-supported? session)))
        ;; Force protocol v3
        (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
        ;; Inject capabilities.changed event
        (mock/send-v3-broadcast-event! *mock-server* session-id
                                       "capabilities.changed"
                                       {:ui {:elicitation true}}
                                       :ephemeral? true)
        (await-event-type! events-ch :copilot/capabilities.changed 1000)
        (is (true? (sdk/elicitation-supported? session)))
        (finally
          (sdk/unsubscribe-events! session events-ch))))))
