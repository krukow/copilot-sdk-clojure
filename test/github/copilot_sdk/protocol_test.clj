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
          pending-promise (promise)
          _ (swap! state-atom assoc-in [:connection :pending-requests 1] {:promise pending-promise})
          in (ByteArrayInputStream. (byte-array 0))
          out (ByteArrayOutputStream.)
          conn (protocol/connect in out state-atom)]
      (try
        (is (true? (wait-for #(false? (get-in @state-atom [:connection :running?])) 200)))
        (let [result (deref pending-promise 200 ::timeout)]
          (is (not= ::timeout result))
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
