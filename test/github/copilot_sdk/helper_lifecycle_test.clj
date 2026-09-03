(ns github.copilot-sdk.helper-lifecycle-test
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as async-protocols]
            [clojure.spec.test.alpha :as stest]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.client :as client]
            [github.copilot-sdk.helpers :as h]
            [github.copilot-sdk.mock-server :as mock]
            [github.copilot-sdk.session :as session]))

(def ^:dynamic *mock-server* nil)

(defn- with-mock-server
  [test-fn]
  (let [server (mock/create-mock-server)
        _ (mock/start-mock-server! server)]
    (stest/unstrument)
    (binding [*mock-server* server]
      (try
        (test-fn)
        (finally
          (stest/unstrument)
          (h/shutdown!)
          (mock/stop-mock-server! server))))))

(use-fixtures :each with-mock-server)

(defn- await-closed
  [ch]
  (loop []
    (let [[value port] (async/alts!! [ch (async/timeout 500)])]
      (cond
        (not= port ch) false
        (nil? value) true
        :else (recur)))))

(def ^:private read-timeout-ms
  "Bound for any single blocking channel read in these tests, so a stalled
  producer or a broken close/terminal invariant surfaces as a normal test
  failure instead of hanging the test process."
  1000)

(defn- read-within
  "Reads one value from `ch`, bounded by `read-timeout-ms`. Returns `::timeout`
  instead of blocking forever when nothing (not even a close) arrives in time."
  [ch]
  (let [[value port] (async/alts!! [ch (async/timeout read-timeout-ms)])]
    (if (identical? port ch)
      value
      ::timeout)))

(defn- cleaned-up?
  [copilot-client]
  (let [{:keys [sessions session-io]} @(:state copilot-client)]
    (and (seq sessions)
         (every? :destroyed? (vals sessions))
         (every? #(await-closed (:event-chan %)) (vals session-io)))))

(defn- instrumentation-rejected?
  [f]
  (try
    (f)
    false
    (catch clojure.lang.ExceptionInfo e
      (= :instrument (:clojure.spec.alpha/failure (ex-data e))))))

(defn- connect-helper-to-server!
  []
  (let [copilot-client (sdk/client {:auto-start? false})
        [in out] (mock/client-streams *mock-server*)]
    (client/connect-with-streams! copilot-client in out)
    copilot-client))

(defn- call-with-single-helper-client
  [f]
  (let [copilot-client (connect-helper-to-server!)]
    (try
      (with-redefs-fn {(requiring-resolve 'github.copilot-sdk.helpers/ensure-client!)
                       (fn [_client-opts] copilot-client)}
        #(f copilot-client))
      (finally
        (try (sdk/stop! copilot-client) (catch Exception _))))))

(defmacro with-single-helper-client
  [[client-binding] & body]
  `(call-with-single-helper-client
    (fn [~client-binding]
      ~@body)))

(defn- observe-parked-put
  [ch parked-put event-to-observe put-count producer-finished close-count]
  (reify
    async-protocols/ReadPort
    (take! [_ handler]
      (async-protocols/take! ch handler))

    async-protocols/WritePort
    (put! [_ value handler]
      (swap! put-count inc)
      (let [result (async-protocols/put! ch value handler)]
        (when (and (identical? value event-to-observe)
                   (nil? result))
          (deliver parked-put true))
        result))

    async-protocols/Channel
    (close! [_]
      (let [closes (swap! close-count inc)
            result (async-protocols/close! ch)]
        (when (= 2 closes)
          (deliver producer-finished true))
        result))
    (closed? [_]
      (async-protocols/closed? ch))))

(defn- call-with-controlled-query
  [{:keys [events-ch disconnect-fn local-teardown-fn subscribe-fn send-fn
           send-with-timeout-fn chan-fn]}
   test-fn]
  (let [send-fn (or send-fn (fn [_session _message] ::message-id))]
    (with-redefs-fn
      (cond-> {(requiring-resolve 'github.copilot-sdk.helpers/ensure-client!)
               (fn [_client-opts] ::client)
               #'sdk/create-session
               (fn [_client _session-config] ::session)
               #'sdk/subscribe-events
               (or subscribe-fn (fn [_session] events-ch))
               #'sdk/send!
               send-fn
               #'session/send-with-timeout!
               (or send-with-timeout-fn
                   (fn [copilot-session message _timeout-ms]
                     (send-fn copilot-session message)))
               #'sdk/disconnect!
               (or disconnect-fn (fn [_session] nil))
               #'session/teardown-local!
               (or local-teardown-fn
                   (fn
                     ([_client _session-id] :claimed)
                     ([_client _session-id _provider-scope] :claimed)))}
        chan-fn (assoc #'async/chan chan-fn))
      test-fn)))

(deftest query-chan-close-releases-a-put-parked-on-a-full-buffer
  (let [events-ch (async/chan 3)
        first-event {:type :copilot/assistant.turn_start}
        parked-event {:type :copilot/assistant.message_delta}
        unread-event {:type :copilot/assistant.message}
        parked-put (promise)
        producer-finished (promise)
        disconnected (promise)
        disconnects (atom 0)
        output-puts (atom 0)
        output-closes (atom 0)
        query-ch (atom nil)
        original-chan async/chan
        output-created? (atom false)
        observed-chan (fn [& args]
                        (let [ch (apply original-chan args)]
                          (if (and (= [1] args)
                                   (compare-and-set! output-created? false true))
                            (observe-parked-put ch parked-put parked-event output-puts
                                                producer-finished output-closes)
                            ch)))]
    (doseq [event [first-event parked-event unread-event]]
      (is (true? (async/>!! events-ch event))))
    (call-with-controlled-query
     {:events-ch events-ch
      :disconnect-fn (fn [_session]
                       (swap! disconnects inc)
                       (deliver disconnected true))
      :chan-fn observed-chan}
     (fn []
       (try
         (reset! query-ch (h/query-chan "park" :buffer 1))
         (is (true? (deref parked-put 1000 false)))

         (async/close! @query-ch)

         (is (true? (deref disconnected 500 false)))
         (is (true? (deref producer-finished 500 false)))
         (is (= 1 @disconnects))
         (is (= first-event (async/<!! @query-ch)))
         (is (nil? (async/<!! @query-ch)))
         (is (= 2 @output-puts))
         (finally
           (async/close! events-ch)
           (when-let [ch @query-ch]
             (loop []
               (when (some? (async/<!! ch))
                 (recur))))
           (deref disconnected 1000 nil)))))))

(deftest query-chan-preserves-bounded-order-through-natural-terminal
  (let [events-ch (async/chan)
        first-event {:type :copilot/assistant.turn_start}
        second-event {:type :copilot/assistant.message}
        terminal-event {:type :copilot/session.idle}
        disconnects (atom 0)]
    (call-with-controlled-query
     {:events-ch events-ch
      :disconnect-fn (fn [_session] (swap! disconnects inc))}
     (fn []
       (let [query-ch (h/query-chan "ordered" :buffer 1)]
         (is (true? (async/>!! events-ch first-event)))
         (is (true? (async/>!! events-ch second-event)))
         (is (= first-event (async/<!! query-ch)))
         (is (= second-event (async/<!! query-ch)))
         (is (true? (async/>!! events-ch terminal-event)))
         (is (= terminal-event (async/<!! query-ch)))
         (is (nil? (async/<!! query-ch)))
         (is (= 1 @disconnects)))))))

(deftest query-stream-helpers-remain-open-across-autopilot-idle
  (let [autopilot-idle {:type :copilot/session.idle
                        :data {:mode "autopilot"}}
        terminal-idle {:type :copilot/session.idle
                       :data {}}]
    (testing "query-chan forwards autopilot idle and closes after terminal idle"
      (let [events-ch (async/chan 2)
            disconnects (atom 0)]
        (call-with-controlled-query
         {:events-ch events-ch
          :disconnect-fn (fn [_session] (swap! disconnects inc))}
         (fn []
           (let [query-ch (h/query-chan "autopilot channel" :buffer 2)]
             (is (true? (async/>!! events-ch autopilot-idle)))
             (is (true? (async/>!! events-ch terminal-idle)))
             (is (= autopilot-idle (read-within query-ch)))
             (is (= terminal-idle (read-within query-ch)))
             (is (nil? (read-within query-ch)))
             (is (= 1 @disconnects)))))))

    (testing "query-seq! includes autopilot idle and realizes through terminal idle"
      (let [events-ch (async/chan 2)
            disconnects (atom 0)]
        (is (true? (async/>!! events-ch autopilot-idle)))
        (is (true? (async/>!! events-ch terminal-idle)))
        (call-with-controlled-query
         {:events-ch events-ch
          :disconnect-fn (fn [_session] (swap! disconnects inc))}
         (fn []
           (let [realized (deref (future (doall (h/query-seq! "autopilot sequence")))
                                 read-timeout-ms
                                 ::timeout)]
             (is (= [autopilot-idle terminal-idle] realized))
             (is (= 1 @disconnects)))))))))

(deftest query-seq-retains-one-deadline-across-autopilot-idle
  (let [events-ch (async/chan 1)
        deadline-ch (async/chan)
        timeout-calls (atom [])
        disconnects (atom 0)
        local-teardowns (atom [])
        cleanup-error (ex-info "disconnect failed" {:phase :cleanup})
        autopilot-idle {:type :copilot/session.idle
                        :data {:mode "autopilot"}}]
    (is (true? (async/>!! events-ch autopilot-idle)))
    (call-with-controlled-query
     {:events-ch events-ch
      :disconnect-fn (fn [_session]
                       (swap! disconnects inc)
                       (throw cleanup-error))
      :local-teardown-fn
      (fn [client session-id]
        (swap! local-teardowns conj [client session-id])
        :claimed)}
     (fn []
       (with-redefs [async/timeout
                     (fn [^long timeout-ms]
                       (swap! timeout-calls conj timeout-ms)
                       deadline-ch)]
         (let [events (h/query-seq! "fixed deadline" :timeout-ms 1234)]
           (is (= autopilot-idle (first events)))
           (async/close! deadline-ch)
           (let [caught (try
                          (second events)
                          ::no-error
                          (catch Throwable error
                            error))]
             (is (instance? clojure.lang.ExceptionInfo caught))
             (is (= :query-timeout (:type (ex-data caught))))
             (is (= 1234 (:timeout-ms (ex-data caught))))
             (is (= [cleanup-error]
                    (vec (.getSuppressed ^Throwable caught)))))))))
    (is (= [1234] @timeout-calls))
    (is (= 1 @disconnects))
    (is (= [[nil nil]] @local-teardowns))
    (async/close! events-ch)))

(deftest query-seq-validates-and-disables-event-deadline
  (testing "invalid values are rejected before setup"
    (let [setup-called? (atom false)]
      (with-redefs-fn
        {(requiring-resolve 'github.copilot-sdk.helpers/ensure-client!)
         (fn [_client-opts]
           (reset! setup-called? true)
           (throw (ex-info "setup should not run" {})))}
        (fn []
          (doseq [timeout-ms [0 -1 1.5 "1000"]]
            (is (thrown-with-msg?
                 clojure.lang.ExceptionInfo
                 #":timeout-ms must be a positive integer or nil"
                 (h/with-query-seq [events "invalid timeout"
                                    :timeout-ms timeout-ms]
                   (doall events)))))))
      (is (false? @setup-called?))))

  (testing "nil omits the event-consumption deadline"
    (let [events-ch (async/chan 1)
          terminal-event {:type :copilot/session.idle}
          timeout-calls (atom 0)]
      (is (true? (async/>!! events-ch terminal-event)))
      (call-with-controlled-query
       {:events-ch events-ch}
       (fn []
         (with-redefs [async/timeout
                       (fn [_timeout-ms]
                         (swap! timeout-calls inc)
                         (throw (ex-info "deadline should be disabled" {})))]
           (is (= [terminal-event]
                  (doall
                   (h/query-seq! "no deadline" :timeout-ms nil)))))))
      (is (zero? @timeout-calls))
      (async/close! events-ch))))

(deftest query-chan-source-close-disconnects-once
  (let [events-ch (async/chan)
        disconnects (atom 0)]
    (call-with-controlled-query
     {:events-ch events-ch
      :disconnect-fn (fn [_session] (swap! disconnects inc))}
     (fn []
       (let [query-ch (h/query-chan "source close")]
         (async/close! events-ch)
         (is (nil? (async/<!! query-ch)))
         (is (= 1 @disconnects)))))))

(deftest query-chan-close-initiates-disconnect-while-producer-takes-source
  (let [events-ch (async/chan)
        disconnected (promise)
        disconnects (atom 0)]
    (call-with-controlled-query
     {:events-ch events-ch
      :disconnect-fn (fn [_session]
                       (swap! disconnects inc)
                       (deliver disconnected true))}
     (fn []
       (let [query-ch (h/query-chan "cancel source take")]
         (try
           (async/close! query-ch)
           (is (true? (deref disconnected 500 false)))
           (async/close! query-ch)
           (is (= 1 @disconnects))
           (is (nil? (async/<!! query-ch)))
           (finally
             (async/close! events-ch))))))))

(deftest query-chan-concurrent-close-starts-one-disconnect
  (let [events-ch (async/chan)
        disconnect-entered (promise)
        release-disconnect (promise)
        disconnect-finished (promise)
        disconnects (atom 0)]
    (call-with-controlled-query
     {:events-ch events-ch
      :disconnect-fn (fn [_session]
                       (swap! disconnects inc)
                       (deliver disconnect-entered true)
                       @release-disconnect
                       (deliver disconnect-finished true))}
     (fn []
       (let [query-ch (h/query-chan "concurrent close")
             closes (doall (repeatedly 8 #(future (async/close! query-ch))))]
         (try
           (is (true? (deref disconnect-entered 1000 false)))
           (doseq [close-result closes]
             (is (nil? (deref close-result 1000 ::timeout))))
           (is (= 1 @disconnects))
           (async/close! query-ch)
           (is (= 1 @disconnects))
           (finally
             (deliver release-disconnect true)
             (async/close! events-ch)
             (deref disconnect-finished 1000 nil))))))))

(deftest query-chan-terminal-cleanup-races-with-close-exactly-once
  (let [events-ch (async/chan)
        terminal-event {:type :copilot/session.idle}
        disconnect-entered (promise)
        release-disconnect (promise)
        disconnect-finished (promise)
        disconnects (atom 0)]
    (call-with-controlled-query
     {:events-ch events-ch
      :disconnect-fn (fn [_session]
                       (swap! disconnects inc)
                       (deliver disconnect-entered true)
                       @release-disconnect
                       (deliver disconnect-finished true))}
     (fn []
       (let [query-ch (h/query-chan "terminal close race")]
         (try
           (is (true? (async/>!! events-ch terminal-event)))
           (is (= terminal-event (async/<!! query-ch)))
           (is (true? (deref disconnect-entered 1000 false)))
           (async/close! query-ch)
           (is (= 1 @disconnects))
           (finally
             (deliver release-disconnect true)
             (deref disconnect-finished 1000 nil)))
         (is (nil? (async/<!! query-ch)))
         (is (= 1 @disconnects)))))))

(deftest query-chan-validates-buffer-before-setup
  (let [setup-called? (atom false)]
    (with-redefs-fn {(requiring-resolve 'github.copilot-sdk.helpers/ensure-client!)
                     (fn [_client-opts]
                       (reset! setup-called? true)
                       ::client)}
      (fn []
        (doseq [buffer [0 -1 nil "1"]]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #":buffer must be a positive integer"
               (h/query-chan "invalid" :buffer buffer))))))
    (is (false? @setup-called?))))

(deftest query-chan-post-session-setup-failures-disconnect-once
  (testing "channel construction"
    (let [disconnects (atom 0)
          original-chan async/chan
          failed? (atom false)]
      (call-with-controlled-query
       {:disconnect-fn (fn [_session] (swap! disconnects inc))
        :chan-fn (fn [& args]
                   (if (compare-and-set! failed? false true)
                     (throw (ex-info "channel failed" {}))
                     (apply original-chan args)))}
       #(is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"channel failed"
             (h/query-chan "channel failure"))))
      (is (= 1 @disconnects))))

  (testing "event subscription"
    (let [disconnects (atom 0)]
      (call-with-controlled-query
       {:disconnect-fn (fn [_session] (swap! disconnects inc))
        :subscribe-fn (fn [_session] (throw (ex-info "subscribe failed" {})))}
       #(is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"subscribe failed"
             (h/query-chan "subscribe failure"))))
      (is (= 1 @disconnects))))

  (testing "send"
    (let [events-ch (async/chan)
          disconnects (atom 0)]
      (call-with-controlled-query
       {:events-ch events-ch
        :disconnect-fn (fn [_session] (swap! disconnects inc))
        :send-fn (fn [_session _message] (throw (ex-info "send failed" {})))}
       #(is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"send failed"
             (h/query-chan "send failure"))))
      (is (= 1 @disconnects))
      (async/close! events-ch))))

(deftest query-chan-throwing-disconnect-does-not-hang-cleanup
  (testing "setup failure"
    (let [disconnects (atom 0)]
      (call-with-controlled-query
       {:disconnect-fn (fn [_session]
                         (swap! disconnects inc)
                         (throw (ex-info "disconnect failed" {})))
        :send-fn (fn [_session _message] (throw (ex-info "send failed" {})))}
       #(is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"send failed"
             (h/query-chan "setup and disconnect failure"))))
      (is (= 1 @disconnects))))

  (testing "consumer cancellation"
    (let [events-ch (async/chan)
          disconnect-entered (promise)
          disconnect-finished (promise)
          cleanup-error (ex-info "disconnect failed" {})
          disconnects (atom 0)]
      (call-with-controlled-query
       {:events-ch events-ch
        :disconnect-fn (fn [_session]
                         (swap! disconnects inc)
                         (deliver disconnect-entered true)
                         (try
                           (throw cleanup-error)
                           (finally
                             (deliver disconnect-finished true))))}
       (fn []
         (let [query-ch (h/query-chan "cancel and disconnect failure")
               close-result (future (async/close! query-ch))]
           (try
             (is (nil? (deref close-result 1000 ::timeout)))
             (is (true? (deref disconnect-entered 1000 false)))
             (is (true? (deref disconnect-finished 1000 false)))
             (is (= 1 @disconnects))
             (is (nil? (async/<!! query-ch)))
             (finally
               (async/close! events-ch)))))))))

(deftest query-chan-surfaces-natural-cleanup-failure-before-closing
  (let [events-ch (async/chan 1)
        terminal-event {:type :copilot/session.idle}
        cleanup-error (ex-info "disconnect failed" {:phase :cleanup})
        disconnects (atom 0)]
    (call-with-controlled-query
     {:events-ch events-ch
      :disconnect-fn (fn [_session]
                       (swap! disconnects inc)
                       (throw cleanup-error))}
     (fn []
       (let [query-ch (h/query-chan "natural cleanup failure" :buffer 2)]
         (is (true? (async/>!! events-ch terminal-event)))
         (is (= terminal-event (read-within query-ch)))
         (let [cleanup-event (read-within query-ch)]
           (is (= :copilot/session.error (:type cleanup-event)))
           (is (= "Failed to disconnect helper-owned query session"
                  (get-in cleanup-event [:data :message])))
           (is (identical? cleanup-error
                           (get-in cleanup-event [:data :cause]))))
         (is (nil? (read-within query-ch)))
         (is (= 1 @disconnects)))))))

(deftest query-preserves-body-and-owned-session-cleanup-failures
  (let [owned-session {:client ::client :session-id "owned-query"}
        body-error (ex-info "send failed" {:phase :body})
        cleanup-error (ex-info "disconnect failed" {:phase :cleanup})
        local-teardowns (atom [])]
    (with-redefs-fn
      {(requiring-resolve 'github.copilot-sdk.helpers/ensure-client!)
       (fn [_client-opts] ::client)
       #'sdk/create-session
       (fn [_client _session-config] owned-session)
       #'sdk/send-and-wait!
       (fn [_session _message _timeout-ms] (throw body-error))
       #'sdk/disconnect!
       (fn [_session] (throw cleanup-error))
       #'session/teardown-local!
       (fn [client session-id]
         (swap! local-teardowns conj [client session-id])
         :claimed)}
      (fn []
        (let [caught (try
                       (h/query "body and cleanup fail")
                       ::no-error
                       (catch Throwable failure
                         failure))]
          (is (identical? body-error caught))
          (is (= [cleanup-error]
                 (vec (.getSuppressed ^Throwable caught)))))))
    (is (= [[::client "owned-query"]] @local-teardowns))))

(deftest query-surfaces-owned-session-cleanup-failure
  (let [owned-session {:client ::client :session-id "owned-query"}
        cleanup-error (ex-info "disconnect failed" {:phase :cleanup})
        local-teardowns (atom [])]
    (with-redefs-fn
      {(requiring-resolve 'github.copilot-sdk.helpers/ensure-client!)
       (fn [_client-opts] ::client)
       #'sdk/create-session
       (fn [_client _session-config] owned-session)
       #'sdk/send-and-wait!
       (fn [_session _message _timeout-ms]
         {:data {:content "response"}})
       #'sdk/disconnect!
       (fn [_session] (throw cleanup-error))
       #'session/teardown-local!
       (fn [client session-id]
         (swap! local-teardowns conj [client session-id])
         :claimed)}
      #(is (identical?
            cleanup-error
            (try
              (h/query "cleanup fails")
              ::no-error
              (catch Throwable failure
                failure)))))
    (is (= [[::client "owned-query"]] @local-teardowns))))

(deftest query-chan-remains-a-core-async-channel
  (let [events-ch (async/chan)
        first-event {:type :copilot/assistant.turn_start}
        second-event {:type :copilot/assistant.message}
        terminal-event {:type :copilot/session.idle}
        external-value {:type ::external}
        disconnects (atom 0)]
    (call-with-controlled-query
     {:events-ch events-ch
      :disconnect-fn (fn [_session] (swap! disconnects inc))}
     (fn []
       (let [query-ch (h/query-chan "protocols" :buffer 2)]
         (is (satisfies? async-protocols/ReadPort query-ch))
         (is (satisfies? async-protocols/WritePort query-ch))
         (is (satisfies? async-protocols/Channel query-ch))
         (is (false? (async-protocols/closed? query-ch)))

         (is (true? (async/offer! query-ch external-value)))
         (is (= external-value (async/<!! query-ch)))

         (is (true? (async/>!! events-ch first-event)))
         (let [[value port] (async/alts!! [query-ch (async/timeout 1000)])]
           (is (= first-event value))
           (is (identical? query-ch port)))

         (let [piped-ch (async/chan 2)]
           (async/pipe query-ch piped-ch)
           (is (true? (async/>!! events-ch second-event)))
           (is (true? (async/>!! events-ch terminal-event)))
           (is (= second-event (async/<!! piped-ch)))
           (is (= terminal-event (async/<!! piped-ch)))
           (is (nil? (async/<!! piped-ch))))

         (is (true? (async-protocols/closed? query-ch)))
         (is (= 1 @disconnects)))))))

(deftest with-query-seq-compiles-and-runs-from-a-separate-namespace
  (with-single-helper-client [copilot-client]
    (let [events (h/with-query-seq [events "hello"]
                   (doall events))]
      (is (some #(= :copilot/session.idle (:type %)) events))
      (is (cleaned-up? copilot-client)))))

(deftest with-query-seq-cleans-up-when-body-returns-early
  (with-single-helper-client [copilot-client]
    (let [event (h/with-query-seq [events "early"]
                  (first events))]
      (is (map? event))
      (is (cleaned-up? copilot-client)))))

(deftest with-query-seq-cleans-up-after-positive-max-events-body-exit
  (with-single-helper-client [copilot-client]
    (let [events (h/with-query-seq [events "bounded" :max-events 1]
                   (doall events))]
      (is (= 1 (count events)))
      (is (cleaned-up? copilot-client)))))

(deftest with-query-seq-cleans-up-when-body-throws
  (with-single-helper-client [copilot-client]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"body failed"
         (h/with-query-seq [events "boom"]
           (first events)
           (throw (ex-info "body failed" {})))))
    (is (cleaned-up? copilot-client))))

(deftest query-seq-uses-an-explicit-client-without-starting-a-shared-client
  (let [copilot-client (connect-helper-to-server!)
        ensure-calls (atom [])
        instrument-all! (requiring-resolve 'github.copilot-sdk.instrument/instrument-all!)
        unstrument-all! (requiring-resolve 'github.copilot-sdk.instrument/unstrument-all!)]
    (instrument-all!)
    (try
      (with-redefs-fn
        {(requiring-resolve 'github.copilot-sdk.helpers/ensure-client!)
         (fn [client-opts]
           (swap! ensure-calls conj client-opts)
           (throw (ex-info "shared client should not start" {})))}
        #(let [events (h/with-query-seq [events "explicit client"
                                         :client copilot-client]
                        (doall events))]
           (is (some (comp #{:copilot/session.idle} :type) events))
           (is (cleaned-up? copilot-client))
           (is (= :connected (sdk/state copilot-client)))))
      (is (empty? @ensure-calls))
      (is (nil? (h/client-info)))
      (finally
        (unstrument-all!)
        (sdk/stop! copilot-client)))))

(deftest query-seq-client-options-still-use-the-shared-client-path
  (let [copilot-client (connect-helper-to-server!)
        client-opts {:log-level :debug}
        ensure-calls (atom [])
        instrument-all! (requiring-resolve 'github.copilot-sdk.instrument/instrument-all!)
        unstrument-all! (requiring-resolve 'github.copilot-sdk.instrument/unstrument-all!)]
    (instrument-all!)
    (try
      (with-redefs-fn
        {(requiring-resolve 'github.copilot-sdk.helpers/ensure-client!)
         (fn [opts]
           (swap! ensure-calls conj opts)
           copilot-client)}
        #(let [events (h/with-query-seq [events "client options"
                                         :client client-opts]
                        (doall events))]
           (is (some (comp #{:copilot/session.idle} :type) events))
           (is (cleaned-up? copilot-client))))
      (is (= [client-opts] @ensure-calls))
      (finally
        (unstrument-all!)
        (sdk/stop! copilot-client)))))

(deftest helper-option-instrumentation-matches-each-function-contract
  (let [copilot-client (connect-helper-to-server!)
        session (sdk/create-session copilot-client {})
        client-opts {:log-level :debug}
        session-opts {:model "gpt-5.4"}
        ensure-calls (atom [])
        instrument-all! (requiring-resolve 'github.copilot-sdk.instrument/instrument-all!)
        unstrument-all! (requiring-resolve 'github.copilot-sdk.instrument/unstrument-all!)]
    (instrument-all!)
    (try
      (with-redefs-fn
        {(requiring-resolve 'github.copilot-sdk.helpers/ensure-client!)
         (fn [opts]
           (swap! ensure-calls conj opts)
           copilot-client)}
        (fn []
           ;; query accepts owned instances or option maps for both resources.
          (is (string? (h/query "query client"
                                :client copilot-client
                                :session session-opts)))
          (is (string? (h/query "query options"
                                :client client-opts
                                :session session-opts)))
          (is (string? (h/query "query session" :session session)))

           ;; Seq helpers accept either client form, but session config only.
          (is (empty? (h/query-seq! "seq client"
                                    :client copilot-client
                                    :session session-opts
                                    :max-events 0)))
          (is (empty? (h/query-seq! "seq options"
                                    :client client-opts
                                    :session session-opts
                                    :max-events 0)))
          (is (empty? (h/query-seq! "unknown option"
                                    :unknown-option :accepted
                                    :max-events 0)))

           ;; Channel helper retains its map-only client/session contract.
          (let [events (h/query-chan "chan options"
                                     :client client-opts
                                     :session session-opts
                                     :buffer 1)]
            (async/close! events))

           ;; Wrong record/map forms are rejected before function execution.
          (is (instrumentation-rejected?
               #(h/query "bad client record" :client session)))
          (is (instrumentation-rejected?
               #(h/query "bad session record" :session copilot-client)))
          (is (instrumentation-rejected?
               #(h/query "bad client map" :client {:bogus true})))
          (is (instrumentation-rejected?
               #(h/query "bad session map" :session {:bogus true})))
          (is (instrumentation-rejected?
               #(h/query-seq! "seq session record"
                              :session session
                              :max-events 0)))
          (is (instrumentation-rejected?
               #(h/query-seq! "seq client map"
                              :client {:bogus true}
                              :max-events 0)))
          (is (instrumentation-rejected?
               #(h/query-chan "chan client record"
                              :client copilot-client)))
          (is (instrumentation-rejected?
               #(h/query-chan "chan session record"
                              :session session)))
          (is (instrumentation-rejected?
               #(h/query-chan "chan client map"
                              :client {:bogus true})))
          (is (instrumentation-rejected?
               #(h/query-chan "chan session map"
                              :session {:bogus true})))))
      (is (some #{client-opts} @ensure-calls))
      (finally
        (unstrument-all!)
        (sdk/disconnect! session)
        (sdk/stop! copilot-client)))))

(deftest query-seq-setup-failure-disconnects-created-session-once
  (with-single-helper-client [copilot-client]
    (let [disconnects (atom [])]
      (with-redefs [session/send-with-timeout!
                    (fn [_session _opts _timeout-ms]
                      (throw (ex-info "send failed" {})))
                    sdk/disconnect!
                    (fn [session-or-client & maybe-session-id]
                      (swap! disconnects conj [session-or-client maybe-session-id])
                      nil)]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"send failed"
             (h/query-seq! "setup failure")))
        (is (= 1 (count @disconnects)))))))

(deftest query-seq-send-uses-the-remaining-fixed-deadline
  (let [events-ch (async/chan)
        clock (atom [0 40000000])
        send-timeout (atom nil)
        disconnects (atom 0)]
    (call-with-controlled-query
     {:events-ch events-ch
      :send-with-timeout-fn
      (fn [_session _message timeout-ms]
        (reset! send-timeout timeout-ms)
        (throw
         (ex-info "session.send timed out"
                  {:method "session.send"
                   :timeout-ms timeout-ms})))
      :disconnect-fn (fn [_session] (swap! disconnects inc))}
     #(with-redefs-fn
        {(requiring-resolve 'github.copilot-sdk.helpers/monotonic-nanos)
         (fn []
           (let [value (first @clock)]
             (swap! clock subvec 1)
             value))}
        (fn []
          (let [failure
                (try
                  (h/query-seq! "fixed deadline" :timeout-ms 100)
                  nil
                  (catch Throwable error
                    error))]
            (is (= 60 @send-timeout))
            (is (= {:type :query-timeout
                    :timeout-ms 100}
                   (ex-data failure)))
            (is (= 1 @disconnects))))))))

(deftest query-seq-source-rejects-invalid-max-events-before-setup
  (let [setup-called? (atom false)]
    (with-redefs-fn {(requiring-resolve 'github.copilot-sdk.helpers/ensure-client!)
                     (fn [_client-opts]
                       (reset! setup-called? true)
                       (throw (ex-info "setup should not run" {})))}
      (fn []
        (doseq [max-events [-1 nil "1"]]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #":max-events must be a non-negative integer"
               (h/with-query-seq [events "invalid" :max-events max-events]
                 (doall events)))))))
    (is (false? @setup-called?))))

(deftest query-seq-natural-terminal-cleanup-is-idempotent
  (with-single-helper-client [copilot-client]
    (let [disconnects (atom 0)]
      (with-redefs [sdk/disconnect!
                    (fn [_session]
                      (swap! disconnects inc)
                      nil)]
        (let [events (doall (h/query-seq! "natural"))]
          (is (some #(= :copilot/session.idle (:type %)) events))
          (is (= 1 @disconnects)))))))

(deftest query-seq-source-finish-is-thread-safe
  (with-single-helper-client [_copilot-client]
    (let [source-var (requiring-resolve 'github.copilot-sdk.helpers/query-seq-source)
          disconnects (atom 0)
          disconnect-entered (promise)
          release-disconnect (promise)]
      (with-redefs [sdk/disconnect!
                    (fn [_session]
                      (deliver disconnect-entered true)
                      @release-disconnect
                      (swap! disconnects inc)
                      nil)]
        (let [[_events finish!] (source-var "concurrent finish")
              first-call (future (finish!))]
          (is (true? (deref disconnect-entered 1000 false)))
          (let [second-call (future (finish!))]
            (is (nil? (deref second-call 1000 ::timeout))))
          (deliver release-disconnect true)
          (is (nil? (deref first-call 1000 ::timeout)))
          (is (= 1 @disconnects)))))))

(deftest max-events-zero-is-valid-under-instrumentation
  (with-single-helper-client [copilot-client]
    (let [instrument-all! (requiring-resolve 'github.copilot-sdk.instrument/instrument-all!)
          unstrument-all! (requiring-resolve 'github.copilot-sdk.instrument/unstrument-all!)]
      (instrument-all!)
      (try
        (let [setup-called? (atom false)]
          (with-redefs-fn {(requiring-resolve 'github.copilot-sdk.helpers/ensure-client!)
                           (fn [_client-opts]
                             (reset! setup-called? true)
                             copilot-client)}
            #(is (thrown? clojure.lang.ExceptionInfo
                          (h/with-query-seq [events "bad" :max-events -1]
                            (doall events)))))
          (is (false? @setup-called?)))
        (is (empty? (h/query-seq! "none" :max-events 0)))
        (is (empty?
             (h/with-query-seq [events "none" :max-events 0]
               (doall events))))
        (finally
          (unstrument-all!))))))
