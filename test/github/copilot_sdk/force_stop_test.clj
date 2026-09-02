(ns github.copilot-sdk.force-stop-test
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as async-protocols]
            [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.protocol :as protocol]
            [github.copilot-sdk.session :as session]))

(defn- await-port
  [ch]
  (let [deadline (async/timeout 500)
        [value port] (async/alts!! [ch deadline])]
    {:value value
     :closed? (and (= port ch) (nil? value))}))

(deftest force-stop-releases-session-owned-resources-without-rpcs
  (let [client (sdk/client {:auto-start? false})
        first-session (session/create-session client "first-session" {})
        second-session (session/create-session client "second-session" {})
        events-ch (session/subscribe-events first-session)
        event-root (get-in @(:state client) [:session-io "first-session" :event-chan])
        send-lock (get-in @(:state client) [:session-io "second-session" :send-lock])
        cancelled? (atom false)
        cancel-ch (async/chan)
        lock-waiter (promise)
        send-started (promise)
        rpc-methods (atom [])]
    (swap! (:state client)
           assoc-in
           [:sessions "first-session" :factory-executions "run-1" "execution-1"]
           {:cancelled? cancelled?
            :cancel-chan cancel-ch})
    (async/<!! send-lock)
    (async/take! send-lock #(deliver lock-waiter [:released %]))
    (with-redefs [protocol/send-request!
                  (fn [_ method _ & _]
                    (swap! rpc-methods conj method)
                    (deliver send-started true)
                    {:message-id "message-1"})]
      (let [in-flight-send
            (future
              (try
                (session/send-and-wait! first-session {:prompt "wait"} 30000)
                :completed
                (catch Exception error
                  [:failed (ex-message error)])))]
        @send-started
        (sdk/force-stop! client)
        (try
          (testing "event subscriptions and in-flight sends are released"
            (is (:closed? (await-port events-ch)))
            (let [result (deref in-flight-send 500 ::pending)]
              (is (not= ::pending result))
              (is (and (vector? result)
                       (re-find #"Event channel closed" (second result)))))
            (is (= [:released nil] (deref lock-waiter 500 ::pending))))
          (testing "local session teardown cancels factory execution and rejects handles"
            (is @cancelled?)
            (is (:closed? (await-port cancel-ch)))
            (is (thrown-with-msg?
                 clojure.lang.ExceptionInfo
                 #"Session has been disconnected"
                 (session/send! first-session {:prompt "after force-stop"}))))
          (testing "force stop remains local-only and idempotent"
            (is (not-any? #{"session.destroy" "runtime.shutdown"} @rpc-methods))
            (is (empty? (:sessions @(:state client))))
            (is (empty? (:session-io @(:state client))))
            (is (nil? (sdk/force-stop! client))))
          (finally
            (async/close! event-root)
            (async/close! send-lock)
            (deref in-flight-send 1000 nil)))))))

(deftest force-stop-prevents-factory-registration-after-session-teardown
  (let [client (sdk/client {:auto-start? false})
        copilot-session (session/create-session client "factory-session" {})]
    (sdk/force-stop! client)
    (is (nil? (#'session/register-factory-execution!
               client
               (sdk/session-id copilot-session)
               "run-1"
               "execution-1")))
    (is (empty? (:sessions @(:state client))))))

(deftest force-stop-clears-lifecycle-handlers-before-transport-teardown
  (let [client (sdk/client {:auto-start? false})
        handlers-at-disconnect (atom nil)]
    (swap! (:state client)
           assoc
           :connection-io :connection
           :lifecycle-handlers {:handler {:handler identity}})
    (with-redefs [protocol/disconnect
                  (fn [_]
                    (reset! handlers-at-disconnect
                            (:lifecycle-handlers @(:state client)))
                    [])]
      (sdk/force-stop! client))
    (is (= {} @handlers-at-disconnect))))

(deftest disconnect-untracked-session-still-notifies-the-runtime
  (let [client (sdk/client {:auto-start? false})
        rpc-calls (atom [])]
    (with-redefs [protocol/send-request!
                  (fn [_ method params & _]
                    (swap! rpc-calls conj [method params]))]
      (is (nil? (session/disconnect! client "runtime-only-session"))))
    (is (= [["session.destroy" {:session-id "runtime-only-session"}]]
           @rpc-calls))
    (is (empty? (:sessions @(:state client))))))

(deftest disconnect-notifies-runtime-before-releasing-local-resources
  (let [client (sdk/client {:auto-start? false})
        copilot-session (session/create-session client "ordered-disconnect" {})
        session-id (sdk/session-id copilot-session)
        event-root (get-in @(:state client) [:session-io session-id :event-chan])
        state-during-rpc (atom nil)]
    (with-redefs [protocol/send-request!
                  (fn [_ method params & _]
                    (reset! state-during-rpc
                            {:method method
                             :params params
                             :session (get-in @(:state client)
                                              [:sessions session-id])
                             :disconnecting?
                             (contains?
                              (:disconnecting-session-ids
                               @(:state client))
                              session-id)
                             :event-closed?
                             (async-protocols/closed? event-root)}))]
      (is (nil? (session/disconnect! client session-id))))
    (is (= "session.destroy" (:method @state-during-rpc)))
    (is (= {:session-id session-id} (:params @state-during-rpc)))
    (is (true? (:disconnecting? @state-during-rpc)))
    (is (false? (get-in @state-during-rpc
                        [:session :destroyed?])))
    (is (false? (:event-closed? @state-during-rpc)))
    (is (true? (get-in @(:state client)
                       [:sessions session-id :destroyed?])))
    (is (async-protocols/closed? event-root))))

(deftest disconnect-preserves-local-resources-after-runtime-failure
  (let [client (sdk/client {:auto-start? false})
        copilot-session (session/create-session client "failed-disconnect" {})
        session-id (sdk/session-id copilot-session)
        events-ch (session/subscribe-events copilot-session)]
    (with-redefs [protocol/send-request!
                  (fn [& _]
                    (throw (ex-info "runtime destroy failed" {})))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"runtime destroy failed"
           (session/disconnect! client session-id))))
    (is (false? (get-in @(:state client)
                        [:sessions session-id :destroyed?])))
    (is (false? (contains? (:disconnecting-session-ids @(:state client))
                           session-id)))
    (is (= ::open (async/alt!!
                    events-ch ([event] event)
                    (async/timeout 25) ::open)))))

(deftest client-stop-forces-local-teardown-after-runtime-destroy-failure
  (let [client (sdk/client {:auto-start? false})
        copilot-session (session/create-session client "failed-stop-disconnect" {})
        session-id (sdk/session-id copilot-session)
        events-ch (session/subscribe-events copilot-session)
        destroy-error (ex-info "runtime destroy failed" {:phase :destroy})
        calls (atom [])]
    (with-redefs [protocol/send-request!
                  (fn [_ method params & _]
                    (swap! calls conj [method params])
                    (when (= "session.destroy" method)
                      (throw destroy-error)))]
      (let [errors (sdk/stop! client)]
        (is (= [["session.destroy" {:session-id session-id}]] @calls))
        (is (= 1 (count errors)))
        (is (identical? destroy-error (ex-cause (first errors))))))
    (is (:closed? (await-port events-ch)))
    (is (empty? (:sessions @(:state client))))
    (is (empty? (:session-io @(:state client))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Session has been disconnected"
         (session/send! copilot-session {:prompt "after failed stop"})))))

(deftest session-registration-rejects-an-in-progress-disconnect
  (let [client (sdk/client {:auto-start? false})
        copilot-session (session/create-session client "disconnect-race" {})
        session-id (sdk/session-id copilot-session)
        destroy-started (promise)
        finish-destroy (promise)]
    (with-redefs [protocol/send-request!
                  (fn [_ method _ & _]
                    (when (= "session.destroy" method)
                      (deliver destroy-started true)
                      @finish-destroy))]
      (let [disconnect (future (session/disconnect! client session-id))]
        (is (= true (deref destroy-started 500 ::pending)))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"disconnect is in progress"
             (session/create-session client session-id {})))
        (deliver finish-destroy {:success true})
        (is (nil? (deref disconnect 500 ::pending)))))
    (is (true? (get-in @(:state client)
                       [:sessions session-id :destroyed?])))))

(deftest local-teardown-does-not-recreate-a-session-after-claim
  (let [client (sdk/client {:auto-start? false})
        copilot-session (session/create-session client "race-session" {})
        events-ch (session/subscribe-events copilot-session)
        original-swap-vals! swap-vals!
        claimed? (atom false)]
    (with-redefs [clojure.core/swap-vals!
                  (fn [state transition]
                    (let [[old new] (original-swap-vals! state transition)]
                      (if (compare-and-set! claimed? false true)
                        (do
                          (reset! state (assoc new :sessions {} :session-io {}))
                          [old new])
                        [old new])))]
      (is (= :claimed
             (session/teardown-local! client (sdk/session-id copilot-session)))))
    (is (:closed? (await-port events-ch)))
    (is (empty? (:sessions @(:state client))))
    (is (empty? (:session-io @(:state client))))))

(deftest session-state-writers-do-not-resurrect-a-force-stopped-session
  (let [client (sdk/client {:auto-start? false})
        copilot-session (session/create-session client "notification-session" {})
        session-id (sdk/session-id copilot-session)]
    (sdk/force-stop! client)
    (session/update-capabilities! client session-id {:supports-canvases true})
    (session/upsert-open-canvas!
     client
     session-id
     {:instance-id "canvas-1" :extension-id "extension-1" :canvas-id "canvas-1"})
    (is (empty? (:sessions @(:state client))))
    (is (empty? (:session-io @(:state client))))))

(deftest session-registration-rejects-a-stopping-client
  (let [client (sdk/client {:auto-start? false})]
    (sdk/force-stop! client)
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Client is stopping"
         (session/create-session client "late-session" {})))
    (is (empty? (:sessions @(:state client))))
    (is (empty? (:session-io @(:state client))))))
