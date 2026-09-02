(ns github.copilot-sdk.protocol-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :as async :refer [>!! <!!]]
            [github.copilot-sdk.protocol :as protocol])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream
            PipedInputStream PipedOutputStream]))

(defn- wait-for
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (< (System/currentTimeMillis) deadline) (do (Thread/sleep 5) (recur))
        :else false))))

(deftest test-read-loop-stops-on-eof
  (testing "EOF stops read loop and fails pending requests"
    (let [state-atom (atom {:connection (protocol/initial-connection-state)})
          pending-ch (async/chan 1)
          _ (swap! state-atom assoc-in [:connection :pending-requests 1] {:ch pending-ch})
          in (ByteArrayInputStream. (byte-array 0))
          out (ByteArrayOutputStream.)
          conn (protocol/connect in out state-atom)]
      (try
        (is (true? (wait-for #(false? (get-in @state-atom [:connection :running?])) 200)))
        (let [result (<!! pending-ch)]
          (is (some? result))
          (is (= -32000 (get-in result [:error :code]))))
        (finally
          (protocol/disconnect conn))))))

(deftest test-send-request-timeout-clears-pending
  (testing "Timeout removes pending request entry"
    (let [state-atom (atom {:connection (protocol/initial-connection-state)})
          in (PipedInputStream.)
          _ (PipedOutputStream. in)
          out (ByteArrayOutputStream.)
          conn (protocol/connect in out state-atom)]
      (try
        (try
          (protocol/send-request! conn "ping" {} 10)
          (is false "Expected request timeout")
          (catch clojure.lang.ExceptionInfo e
            (is (re-find #"Request timeout" (ex-message e)))))
        (is (empty? (get-in @state-atom [:connection :pending-requests])))
        (finally
          (protocol/disconnect conn))))))

(deftest test-async-send-request-timeout-clears-pending
  (testing "Async timeout resolves with an error and removes the pending request"
    (let [state-atom (atom {:connection (protocol/initial-connection-state)})
          in (PipedInputStream.)
          _ (PipedOutputStream. in)
          out (ByteArrayOutputStream.)
          conn (protocol/connect in out state-atom)]
      (try
        (let [result (<!! (protocol/send-request-with-timeout conn "ping" {} 10))]
          (is (= -32000 (get-in result [:error :code])))
          (is (= "Request timeout" (get-in result [:error :message])))
          (is (= {:method "ping" :timeout-ms 10}
                 (get-in result [:error :data]))))
        (is (empty? (get-in @state-atom [:connection :pending-requests])))
        (finally
          (protocol/disconnect conn))))))

(deftest test-disconnect-resolves-pending-requests
  (testing "disconnect fails in-flight requests instead of hanging (A3)"
    (let [state-atom (atom {:connection (protocol/initial-connection-state)})
          in (PipedInputStream.)
          _ (PipedOutputStream. in)
          out (ByteArrayOutputStream.)
          conn (protocol/connect in out state-atom)
          resp-ch (protocol/send-request conn "ping" {})]
      (is (true? (wait-for #(seq (get-in @state-atom [:connection :pending-requests])) 200))
          "request should be registered while running")
      (protocol/disconnect conn)
      (let [result (async/alt!! resp-ch ([v] v)
                                (async/timeout 500) ([_] ::timeout))]
        (is (not= ::timeout result) "response channel must resolve on disconnect")
        (is (= -32000 (get-in result [:error :code]))))
      (is (empty? (get-in @state-atom [:connection :pending-requests]))))))

(deftest test-send-request-after-disconnect-fails-fast
  (testing "send-request after disconnect resolves with error, never hangs (A4)"
    (let [state-atom (atom {:connection (protocol/initial-connection-state)})
          in (PipedInputStream.)
          _ (PipedOutputStream. in)
          out (ByteArrayOutputStream.)
          conn (protocol/connect in out state-atom)]
      (protocol/disconnect conn)
      (let [resp-ch (protocol/send-request conn "ping" {})
            result (async/alt!! resp-ch ([v] v)
                                (async/timeout 500) ([_] ::timeout))]
        (is (not= ::timeout result) "send-request after disconnect must resolve")
        (is (= -32000 (get-in result [:error :code]))))
      (is (empty? (get-in @state-atom [:connection :pending-requests]))
          "no pending entry should be left registered"))))
