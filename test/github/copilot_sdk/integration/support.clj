(ns github.copilot-sdk.integration.support
  "Shared fixture and synchronization helpers for focused integration tests."
  (:require [clojure.core.async :refer [alts!! timeout]]
            [clojure.core.async.impl.protocols :as async-protocols]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.client :as client]
            [github.copilot-sdk.mock-server :as mock]))

(def ^:dynamic *mock-server* nil)

(def ^:dynamic *test-client* nil)

(defn await-value!
  [value-ref label timeout-ms]
  (let [value (deref value-ref timeout-ms :github.copilot-sdk.integration-test/timeout)]
    (when (= :github.copilot-sdk.integration-test/timeout value)
      (throw (ex-info (str "Timed out waiting for " label)
                      {:label label :timeout-ms timeout-ms})))
    value))

(defn await-atom!
  [state pred label timeout-ms]
  (let [observed (promise)
        watch-key (keyword (str (gensym "await-atom-")))]
    (add-watch state watch-key
               (fn [_ _ _ new-value]
                 (when (pred new-value)
                   (deliver observed new-value))))
    (try
      (when (pred @state)
        (deliver observed @state))
      (await-value! observed label timeout-ms)
      (finally
        (remove-watch state watch-key)))))

(defn await-event-type!
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

(defn observe-take-attempts
  [port attempts parked-takes]
  (reify
    async-protocols/ReadPort
    (take! [_ handler]
      (let [result (async-protocols/take! port handler)]
        (.countDown attempts)
        (when (nil? result)
          (.countDown parked-takes))
        result))

    async-protocols/WritePort
    (put! [_ value handler]
      (async-protocols/put! port value handler))

    async-protocols/Channel
    (close! [_]
      (async-protocols/close! port))
    (closed? [_]
      (async-protocols/closed? port))))

(defn with-mock-server
  "Fixture that creates a mock server and client for each test."
  [test-fn]
  (let [server (mock/create-mock-server)
        _ (mock/start-mock-server! server)
        client (sdk/client {:auto-start? false})
        [in out] (mock/client-streams server)]
    ;; Connect client to mock server
    (@#'client/connect-with-streams* client in out false)
    (binding [*mock-server* server
              *test-client* client]
      (try
        (test-fn)
        (finally
          ;; Stop client first to suppress auto-restart during teardown
          (try (sdk/stop! client) (catch Exception _))
          (mock/stop-mock-server! server))))))
