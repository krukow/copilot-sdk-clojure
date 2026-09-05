(ns github.copilot-sdk.process-test
  "Unit tests for github.copilot-sdk.process — focused on the pure helpers
   that compute the env-var contract for the spawned CLI process. We test
   the helper directly rather than spawning a real process."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :as async]
            [github.copilot-sdk.process :as proc]
            [github.copilot-sdk.specs :as specs]
            [clojure.spec.alpha :as s])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream InputStream
            PipedInputStream PipedOutputStream SequenceInputStream]))

(defn- fake-process
  [alive exit-code]
  (proxy [Process] []
    (isAlive [] @alive)
    (exitValue [] exit-code)
    (waitFor
      ([] exit-code)
      ([_timeout _unit] (not @alive)))
    (destroy [])
    (destroyForcibly [] this)
    (getOutputStream [] (ByteArrayOutputStream.))
    (getInputStream [] (ByteArrayInputStream. (byte-array 0)))
    (getErrorStream [] (ByteArrayInputStream. (byte-array 0)))))

(defn- blocking-input-stream
  []
  (let [started (promise)
        closed (promise)
        finished (promise)
        close-count (atom 0)
        read! (fn []
                (deliver started true)
                (try
                  @closed
                  -1
                  (finally
                    (deliver finished true))))
        stream
        (proxy [InputStream] []
          (read
            ([] (read!))
            ([_buffer] (read!))
            ([_buffer _offset _length] (read!)))
          (close []
            (swap! close-count inc)
            (deliver closed true)))]
    {:stream stream
     :started started
     :closed closed
     :finished finished
     :close-count close-count}))

(defn- exploding-input-stream
  [failure closed]
  (let [read! #(throw failure)]
    (proxy [InputStream] []
      (read
        ([] (read!))
        ([_buffer] (read!))
        ([_buffer _offset _length] (read!)))
      (close []
        (deliver closed true)))))

(defn- split-input-stream
  [prefix suffix]
  (let [chunks (mapv #(.getBytes ^String % "UTF-8") [prefix suffix])
        next-chunk (atom 0)
        waiting-for-suffix (promise)
        release-suffix (promise)
        stream
        (proxy [InputStream] []
          (available [] 0)
          (close []
            (deliver release-suffix true))
          (read
            ([]
             (let [buffer (byte-array 1)
                   read-count (.read ^InputStream this buffer 0 1)]
               (if (neg? read-count)
                 -1
                 (bit-and 0xff (aget buffer 0)))))
            ([buffer]
             (.read ^InputStream this buffer 0 (alength buffer)))
            ([buffer buffer-offset length]
             (let [index @next-chunk]
               (if (< index (count chunks))
                 (do
                   (when (= index 1)
                     (deliver waiting-for-suffix true)
                     @release-suffix)
                   (let [chunk (nth chunks index)
                         chunk-length (alength chunk)]
                     (assert (<= chunk-length length))
                     (System/arraycopy
                      chunk 0 buffer buffer-offset chunk-length)
                     (swap! next-chunk inc)
                     chunk-length))
                 -1)))))]
    {:stream stream
     :waiting-for-suffix waiting-for-suffix
     :release-suffix release-suffix}))

(deftest wait-for-port-reads-the-complete-announced-port
  (doseq [announcement ["CLI server listening on port 63234\n"
                        "CLI server listening on port 63234\r"
                        "CLI server listening on port 63234\r\n"
                        "CLI server listening on port 63234"
                        "CLI server listening on port 63234 (localhost only)\n"
                        "CLI server listening on port 63234\u001b[0m\n"]]
    (let [process (proxy [Process] []
                    (isAlive [] true))
          managed-process
          (proc/map->ManagedProcess
           {:process process
            :stdout (ByteArrayInputStream. (.getBytes announcement "UTF-8"))})]
      (is (= 63234 (proc/wait-for-port managed-process 1000))
          announcement))))

(deftest wait-for-port-does-not-accept-a-digit-prefix-at-a-read-boundary
  (let [{:keys [stream waiting-for-suffix release-suffix]}
        (split-input-stream "CLI server listening on port 63" "234")
        process (fake-process (atom true) 0)
        managed-process (proc/map->ManagedProcess
                         {:process process
                          :stdout stream})
        outcome (future (proc/wait-for-port managed-process 1000))]
    (try
      (is (true? (deref waiting-for-suffix 500 false)))
      (deliver release-suffix true)
      (is (= 63234 (deref outcome 500 ::timeout)))
      (finally
        (deliver release-suffix true)
        (.close stream)
        (future-cancel outcome)))))

(deftest wait-for-port-keeps-draining-stdout-after-announcement
  (let [{:keys [stream started closed finished]} (blocking-input-stream)
        announcement (ByteArrayInputStream.
                      (.getBytes "CLI server listening on port 63234\n" "UTF-8"))
        stdout (SequenceInputStream. announcement stream)
        process (fake-process (atom true) 0)
        managed-process (proc/map->ManagedProcess
                         {:process process
                          :stdout stdout})]
    (try
      (is (= 63234 (proc/wait-for-port managed-process 1000)))
      (is (true? (deref started 500 false)))
      (finally
        (.close stdout)
        (is (true? (deref closed 500 false)))
        (is (true? (deref finished 500 false)))))))

(deftest wait-for-port-recognizes-a-live-flushed-announcement-without-newline
  (let [stdout (PipedInputStream.)
        writer (PipedOutputStream. stdout)
        process (fake-process (atom true) 0)
        managed-process (proc/map->ManagedProcess
                         {:process process
                          :stdout stdout})
        outcome (future (proc/wait-for-port managed-process 1000))]
    (try
      (.write writer (.getBytes "CLI server listening on port 63234" "UTF-8"))
      (.flush writer)
      (is (= 63234 (deref outcome 500 ::timeout)))
      (finally
        (.close writer)
        (.close stdout)
        (future-cancel outcome)))))

(deftest wait-for-port-reports-stdout-eof
  (let [process (fake-process (atom true) 0)
        managed-process (proc/map->ManagedProcess
                         {:process process
                          :stdout (ByteArrayInputStream. (byte-array 0))})
        caught (try
                 (proc/wait-for-port managed-process 1000)
                 nil
                 (catch Throwable failure
                   failure))]
    (is (= "CLI stdout closed before announcing port"
           (ex-message caught)))))

(deftest wait-for-port-propagates-reader-exceptions
  (let [failure (java.io.IOException. "reader failed")
        closed (promise)
        process (fake-process (atom true) 0)
        managed-process
        (proc/map->ManagedProcess
         {:process process
          :stdout (exploding-input-stream failure closed)})
        caught (try
                 (proc/wait-for-port managed-process 1000)
                 nil
                 (catch Throwable error
                   error))]
    (is (identical? failure caught))
    (is (true? (deref closed 500 false)))))

(deftest wait-for-port-timeout-stops-its-reader
  (let [{:keys [stream started closed finished close-count]}
        (blocking-input-stream)
        process (fake-process (atom true) 0)
        managed-process (proc/map->ManagedProcess
                         {:process process
                          :stdout stream})
        caught (try
                 (proc/wait-for-port managed-process 25)
                 nil
                 (catch Throwable failure
                   failure))]
    (is (= "Timeout waiting for CLI server to start"
           (ex-message caught)))
    (is (= {:timeout-ms 25} (ex-data caught)))
    (is (true? (deref started 500 false)))
    (is (true? (deref closed 500 false)))
    (is (true? (deref finished 500 false)))
    (is (pos? @close-count))))

(deftest wait-for-port-observes-process-exit-while-stdout-is-open
  (let [{:keys [stream started closed finished]} (blocking-input-stream)
        alive (atom true)
        exit-ch (async/promise-chan)
        process (fake-process alive 23)
        managed-process (proc/map->ManagedProcess
                         {:process process
                          :stdout stream
                          :exit-chan exit-ch})
        outcome (future
                  (try
                    (proc/wait-for-port managed-process 5000)
                    nil
                    (catch Throwable failure
                      failure)))]
    (is (true? (deref started 500 false)))
    (reset! alive false)
    (async/>!! exit-ch {:exit-code 23})
    (async/close! exit-ch)
    (let [caught (deref outcome 1000 ::timeout)]
      (is (not= ::timeout caught))
      (is (= "CLI process exited before announcing port"
             (ex-message caught)))
      (is (= 23 (:exit-code (ex-data caught)))))
    (is (true? (deref closed 500 false)))
    (is (true? (deref finished 500 false)))))

(deftest wait-for-port-reports-an-already-dead-process-immediately
  (let [process (fake-process (atom false) 17)
        managed-process (proc/map->ManagedProcess
                         {:process process
                          :stdout (ByteArrayInputStream. (byte-array 0))})
        caught (try
                 (proc/wait-for-port managed-process 5000)
                 nil
                 (catch Throwable failure
                   failure))]
    (is (= "CLI process exited before announcing port"
           (ex-message caught)))
    (is (= 17 (:exit-code (ex-data caught))))))

(deftest wait-for-port-interruption-stops-its-reader
  (let [{:keys [stream started closed finished]} (blocking-input-stream)
        process (fake-process (atom true) 0)
        managed-process (proc/map->ManagedProcess
                         {:process process
                          :stdout stream})
        outcome (promise)
        waiter (Thread.
                ^Runnable
                (reify Runnable
                  (run [_]
                    (try
                      (proc/wait-for-port managed-process 60000)
                      (deliver outcome {:returned? true})
                      (catch Throwable failure
                        (deliver outcome
                                 {:failure failure
                                  :interrupted?
                                  (.isInterrupted
                                   (Thread/currentThread))}))))))]
    (.start waiter)
    (is (true? (deref started 500 false)))
    (.interrupt waiter)
    (.join waiter 1000)
    (let [{:keys [failure interrupted? returned?]}
          (deref outcome 1000 {})]
      (is (not returned?))
      (is (instance? InterruptedException failure))
      (is interrupted?))
    (is (false? (.isAlive waiter)))
    (is (true? (deref closed 500 false)))
    (is (true? (deref finished 500 false)))))

(deftest spawned-process-exit-is-observable-by-multiple-consumers
  (let [java-command
        (.orElse (.command (.info (java.lang.ProcessHandle/current))) "java")
        managed-process (proc/spawn-cli {:cli-path java-command
                                         :cli-args ["-version"]})
        first-result (future (async/<!! (:exit-chan managed-process)))
        second-result (future (async/<!! (:exit-chan managed-process)))]
    (is (= {:exit-code 0} (deref first-result 1000 ::timeout)))
    (is (= {:exit-code 0} (deref second-result 1000 ::timeout)))))

(deftest cli-env-overrides-defaults
  (testing "by default only NODE_DEBUG is in :defaults (removed; user :env can re-add)"
    (let [{:keys [defaults overrides]} (proc/cli-env-overrides {})]
      (is (contains? defaults "NODE_DEBUG"))
      (is (nil? (get defaults "NODE_DEBUG"))
          "NODE_DEBUG must be a default removal (nil value)")
      (is (= 1 (count defaults)))
      (is (= {} overrides) "no overrides without options"))))

(deftest cli-env-overrides-github-token
  (testing ":github-token sets COPILOT_SDK_AUTH_TOKEN as a strict override (PR #237)"
    (let [{:keys [overrides]} (proc/cli-env-overrides {:github-token "tok-1"})]
      (is (= "tok-1" (get overrides "COPILOT_SDK_AUTH_TOKEN"))))))

(deftest cli-env-overrides-copilot-home
  (testing ":copilot-home sets COPILOT_HOME as a strict override (upstream PR #1191)"
    (is (= "/tmp/my-home"
           (get-in (proc/cli-env-overrides {:copilot-home "/tmp/my-home"})
                   [:overrides "COPILOT_HOME"]))))
  (testing "no override when :copilot-home is absent"
    (is (not (contains? (:overrides (proc/cli-env-overrides {}))
                        "COPILOT_HOME")))))

(deftest cli-env-overrides-tcp-connection-token
  (testing ":tcp-connection-token sets COPILOT_CONNECTION_TOKEN as a strict override (upstream PR #1176)"
    (is (= "abc-123"
           (get-in (proc/cli-env-overrides {:tcp-connection-token "abc-123"})
                   [:overrides "COPILOT_CONNECTION_TOKEN"]))))
  (testing "no override when :tcp-connection-token is absent"
    (is (not (contains? (:overrides (proc/cli-env-overrides {}))
                        "COPILOT_CONNECTION_TOKEN")))))

(deftest cli-env-overrides-telemetry
  (testing "telemetry options map onto OTEL_* / COPILOT_OTEL_* override vars (PR #785)"
    (let [{:keys [overrides]} (proc/cli-env-overrides
                               {:telemetry {:otlp-endpoint "http://localhost:4318"
                                            :file-path "/tmp/otel.json"
                                            :exporter-type "otlp"
                                            :source-name "my-app"
                                            :capture-content? true}})]
      (is (= "true" (get overrides "COPILOT_OTEL_ENABLED")))
      (is (= "http://localhost:4318" (get overrides "OTEL_EXPORTER_OTLP_ENDPOINT")))
      (is (= "/tmp/otel.json" (get overrides "COPILOT_OTEL_FILE_EXPORTER_PATH")))
      (is (= "otlp" (get overrides "COPILOT_OTEL_EXPORTER_TYPE")))
      (is (= "my-app" (get overrides "COPILOT_OTEL_SOURCE_NAME")))
      (is (= "true" (get overrides "OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT"))))))

(deftest cli-env-overrides-otlp-protocol
  (testing ":otlp-protocol maps onto OTEL_EXPORTER_OTLP_PROTOCOL (upstream b5ce1c89)"
    (doseq [proto ["http/json" "http/protobuf"]]
      (let [{:keys [overrides]} (proc/cli-env-overrides
                                 {:telemetry {:otlp-endpoint "http://localhost:4318"
                                              :otlp-protocol proto}})]
        (is (= proto (get overrides "OTEL_EXPORTER_OTLP_PROTOCOL"))))))
  (testing "OTEL_EXPORTER_OTLP_PROTOCOL is omitted when :otlp-protocol unset"
    (let [{:keys [overrides]} (proc/cli-env-overrides
                               {:telemetry {:otlp-endpoint "http://localhost:4318"}})]
      (is (not (contains? overrides "OTEL_EXPORTER_OTLP_PROTOCOL"))))))

(deftest cli-env-defaults-can-be-overridden-by-user-env
  (testing "NODE_DEBUG is a default — user :env should be able to re-enable it"
    ;; This locks in the precedence contract: defaults are applied BEFORE user :env
    ;; in spawn-cli, so a user-provided NODE_DEBUG value survives.
    (let [{:keys [defaults overrides]} (proc/cli-env-overrides {})]
      (is (contains? defaults "NODE_DEBUG"))
      (is (not (contains? overrides "NODE_DEBUG"))
          "NODE_DEBUG must NOT be a strict override — user :env wins"))))

;; -----------------------------------------------------------------------------
;; Spec coverage — make sure the new option keys are part of ::client-options.
;; -----------------------------------------------------------------------------

(deftest copilot-home-accepted-by-client-options-spec
  (is (s/valid? ::specs/client-options {:copilot-home "/tmp/x"}))
  (testing "must be non-blank"
    (is (not (s/valid? ::specs/client-options {:copilot-home ""})))
    (is (not (s/valid? ::specs/client-options {:copilot-home "   "})))))

(deftest tcp-connection-token-accepted-by-client-options-spec
  (is (s/valid? ::specs/client-options {:tcp-connection-token "abc"}))
  (testing "must be non-blank"
    (is (not (s/valid? ::specs/client-options {:tcp-connection-token ""})))
    (is (not (s/valid? ::specs/client-options {:tcp-connection-token "   "})))))

(deftest remote-accepted-by-client-options-spec
  (testing ":remote? accepted by ::client-options (upstream PR #1192)"
    (is (s/valid? ::specs/client-options {:remote? true}))
    (is (s/valid? ::specs/client-options {:remote? false}))
    (testing "must be boolean"
      (is (not (s/valid? ::specs/client-options {:remote? "yes"}))))))

(deftest build-cli-args-remote-flag
  (testing ":remote? true appends --remote to the spawned CLI args (upstream PR #1192)"
    (let [build-cli-args @#'proc/build-cli-args
          args (build-cli-args {:use-stdio? true :remote? true})]
      (is (some #{"--remote"} args)
          "--remote must be present when :remote? is true")))
  (testing ":remote? false (or unset) does NOT append --remote"
    (let [build-cli-args @#'proc/build-cli-args]
      (is (not (some #{"--remote"} (build-cli-args {:use-stdio? true})))
          "--remote must NOT be present by default")
      (is (not (some #{"--remote"} (build-cli-args {:use-stdio? true :remote? false})))
          "--remote must NOT be present when :remote? is explicitly false"))))

(deftest build-cli-args-session-idle-timeout
  (testing ":session-idle-timeout-seconds > 0 appends --session-idle-timeout <n>"
    (let [build-cli-args @#'proc/build-cli-args
          args (build-cli-args {:use-stdio? true :session-idle-timeout-seconds 300})]
      (is (= ["--session-idle-timeout" "300"]
             (->> args (drop-while #(not= % "--session-idle-timeout")) (take 2)))
          "--session-idle-timeout must be followed by the seconds value")))
  (testing ":session-idle-timeout-seconds of 0 or unset does NOT append the flag"
    (let [build-cli-args @#'proc/build-cli-args]
      (is (not (some #{"--session-idle-timeout"} (build-cli-args {:use-stdio? true})))
          "absent by default")
      (is (not (some #{"--session-idle-timeout"} (build-cli-args {:use-stdio? true :session-idle-timeout-seconds 0})))
          "absent when 0 (disabled), matching upstream's > 0 guard"))))

;; -----------------------------------------------------------------------------
;; Client mode (upstream PR #1428)
;; -----------------------------------------------------------------------------

(deftest cli-env-overrides-empty-mode-disables-keytar
  (testing ":mode :empty sets COPILOT_DISABLE_KEYTAR=1 as a strict override"
    (let [{:keys [overrides]} (proc/cli-env-overrides {:mode :empty})]
      (is (= "1" (get overrides "COPILOT_DISABLE_KEYTAR"))
          "COPILOT_DISABLE_KEYTAR must be 1 in :empty mode")))
  (testing ":mode :copilot-cli does NOT set COPILOT_DISABLE_KEYTAR"
    (let [{:keys [overrides]} (proc/cli-env-overrides {:mode :copilot-cli})]
      (is (not (contains? overrides "COPILOT_DISABLE_KEYTAR"))
          "KEYTAR override must be absent in :copilot-cli mode")))
  (testing "absent :mode does NOT set COPILOT_DISABLE_KEYTAR"
    (let [{:keys [overrides]} (proc/cli-env-overrides {})]
      (is (not (contains? overrides "COPILOT_DISABLE_KEYTAR"))
          "KEYTAR override must be absent when :mode is unset"))))

(deftest cli-env-overrides-empty-mode-user-env-cannot-override-keytar
  (testing "KEYTAR override is in :overrides slot, so user :env cannot win"
    ;; Lock in the precedence contract documented on cli-env-overrides:
    ;; values returned in :overrides are applied AFTER user :env in spawn-cli,
    ;; so an attempt to set COPILOT_DISABLE_KEYTAR=0 via :env would be clobbered.
    (let [{:keys [defaults overrides]} (proc/cli-env-overrides {:mode :empty})]
      (is (= "1" (get overrides "COPILOT_DISABLE_KEYTAR")))
      (is (not (contains? defaults "COPILOT_DISABLE_KEYTAR"))
          "KEYTAR must NOT be a default — defaults can be overridden by :env"))))
