(ns github.copilot-sdk.protocol-test
  (:require [clojure.core.async :as async :refer [>!! <!!]]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk.protocol :as protocol])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream
            InputStream PipedInputStream PipedOutputStream]
           [java.nio.charset StandardCharsets]))

(defn- wait-for
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (< (System/currentTimeMillis) deadline) (do (Thread/sleep 5) (recur))
        :else false))))

(defn- controlled-exploding-input-stream
  [entered release-read failure]
  (let [read! (fn []
                (deliver entered true)
                @release-read
                (throw failure))]
    (proxy [InputStream] []
      (read
        ([] (read!))
        ([_buffer] (read!))
        ([_buffer _offset _length] (read!)))
      (close []
        (deliver release-read true)))))

(defn- write-framed-json!
  [out value]
  (let [body (.getBytes (json/write-str value) StandardCharsets/UTF_8)
        header (.getBytes (str "Content-Length: " (alength body) "\r\n\r\n")
                          StandardCharsets/UTF_8)]
    (.write out header)
    (.write out body)
    (.flush out)))

(deftest generated-opaque-event-paths-preserve-source-key-spelling
  (let [arguments {:snake_key {:mixedCase true}}
        event {:id "event-1"
               :timestamp "2026-08-13T08:00:00Z"
               :type "assistant.message"
               :data {:content "done"
                      :toolRequests
                      [{:toolCallId "tool-1"
                        :name "example"
                        :arguments arguments}]}}
        live (#'protocol/normalize-incoming
              {:method "session.event" :params {:event event}})
        historical (#'protocol/normalize-incoming
                    {:id "response-1" :result {:events [event]}})]
    (is (= arguments
           (get-in live
                   [:params :event :data :tool-requests 0 :arguments])))
    (is (= arguments
           (get-in historical
                   [:result :events 0 :data :tool-requests 0 :arguments])))))

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

(deftest test-send-request-with-nil-timeout-waits-unbounded
  (testing "nil disables the blocking request deadline"
    (let [state-atom (atom {:connection (protocol/initial-connection-state)})
          in (PipedInputStream.)
          server-out (PipedOutputStream. in)
          out (ByteArrayOutputStream.)
          conn (protocol/connect in out state-atom)
          result (future (protocol/send-request! conn "ping" {} nil))]
      (try
        (is (true? (wait-for #(seq (get-in @state-atom
                                           [:connection :pending-requests]))
                             500)))
        (let [request-id (first (keys (get-in @state-atom
                                              [:connection :pending-requests])))]
          (write-framed-json! server-out
                              {:jsonrpc "2.0"
                               :id request-id
                               :result {:messageId "unbounded"}}))
        (is (= {:message-id "unbounded"}
               (deref result 1000 ::timeout)))
        (finally
          (.close server-out)
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

(deftest test-generic-reader-failure-resolves-pending-requests
  (testing "a non-IO reader exception fails every in-flight request"
    (let [entered (promise)
          release-read (promise)
          failure (IllegalStateException. "reader exploded")
          state-atom (atom {:connection (protocol/initial-connection-state)})
          in (controlled-exploding-input-stream entered release-read failure)
          out (ByteArrayOutputStream.)
          conn (protocol/connect in out state-atom)]
      (try
        (is (true? (deref entered 500 false)))
        (let [response-ch (protocol/send-request conn "ping" {})]
          (is (seq (get-in @state-atom [:connection :pending-requests])))
          (deliver release-read true)
          (let [result (async/alt!! response-ch ([value] value)
                                    (async/timeout 500) ([_] ::timeout))]
            (is (not= ::timeout result))
            (is (= -32000 (get-in result [:error :code])))
            (is (= "Connection error: reader exploded"
                   (get-in result [:error :message])))))
        (is (empty? (get-in @state-atom [:connection :pending-requests])))
        (finally
          (protocol/disconnect conn))))))

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
