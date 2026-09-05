(ns github.copilot-sdk.teardown-test
  "Teardown diagnostics and failed-connect cleanup (R7: COR-001, IDI-001, IDI-002).

   These tests pin observable behavior: a rejected handshake must retain no
   transport resources, a Throwable must reach the logging backend as a
   Throwable, expected close outcomes must stay quiet, and unexpected ones must
   be reported with the identity of the resource that failed."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging :as ctl]
            [clojure.tools.logging.impl :as impl]
            [clojure.tools.logging.test :as log-test]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.client :as client]
            [github.copilot-sdk.logging :as log]
            [github.copilot-sdk.mock-server :as mock]
            [github.copilot-sdk.process :as proc]
            [github.copilot-sdk.protocol :as protocol]
            [github.copilot-sdk.teardown :as teardown])
  (:import [java.io IOException]
           [java.nio.channels ClosedChannelException ReadableByteChannel WritableByteChannel]))

;; -----------------------------------------------------------------------------
;; COR-001 - a rejected handshake must not retain transport resources
;; -----------------------------------------------------------------------------

(deftest rejected-handshake-releases-connection-and-allows-retry
  (testing "connect-with-streams! releases the connection it built when the
            protocol version is rejected, so a caller can retry without
            performing cleanup of its own"
    (let [server (mock/create-mock-server)
          _ (reset! (:protocol-version server) 2)
          _ (mock/start-mock-server! server)
          c (sdk/client {:auto-start? false})
          [in out] (mock/client-streams server)
          rejected-conn (atom nil)
          real-connect protocol/connect]
      (try
        (with-redefs [protocol/connect (fn [i o state-atom]
                                         (let [conn (real-connect i o state-atom)]
                                           (reset! rejected-conn conn)
                                           conn))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"(?i)protocol.*version"
                                (client/connect-with-streams! c in out))))

        (let [s @(:state c)]
          (is (= :error (:status s)))
          (is (false? (some? (:connection s))) "connection state must be released")
          (is (false? (some? (:connection-io s))) "connection IO must be released"))

        (let [conn @rejected-conn]
          (is (some? conn) "the reproducer must have observed the built connection")
          (is (.isShutdown ^java.util.concurrent.ThreadPoolExecutor
               (:request-executor conn))
              "the reverse-request executor must be shut down")
          (let [^Thread read-thread (:read-thread conn)]
            (.join read-thread 2000)
            (is (not (.isAlive read-thread))
                "the reader thread must have exited")))

        (finally
          (mock/stop-mock-server! server)))

      ;; Retry against a fresh server with no caller-side cleanup at all.
      (let [server2 (mock/start-mock-server! (mock/create-mock-server))
            [in2 out2] (mock/client-streams server2)]
        (try
          (client/connect-with-streams! c in2 out2)
          (is (= :connected (:status @(:state c))))
          (finally
            (try (sdk/disconnect! c) (catch Exception _))
            (mock/stop-mock-server! server2)))))))

;; -----------------------------------------------------------------------------
;; IDI-001 - the logging facade must preserve Throwable-first semantics
;; -----------------------------------------------------------------------------

(deftest throwable-reaches-the-logging-backend-as-a-throwable
  (testing "a leading Throwable is handed to the backend as a throwable, not
            stringified into the message"
    (let [boom (ex-info "boom" {:resource :read-channel})]
      (log-test/with-log
        (log/error boom "failed to close read channel")
        (let [entries (vec (log-test/the-log))]
          (is (= 1 (count entries)))
          (let [entry (first entries)]
            (is (identical? boom (:throwable entry))
                "the backend must receive the Throwable itself")
            (is (= "failed to close read channel" (:message entry))
                "the message must not absorb the Throwable")))))))

(deftest non-throwable-logging-is-unchanged
  (testing "messages without a leading Throwable keep their existing rendering
            and stay attributed to the calling namespace"
    (log-test/with-log
      (log/debug "Writing message: " "id=7")
      (let [entry (first (log-test/the-log))]
        (is (nil? (:throwable entry)))
        (is (= "Writing message: id=7" (:message entry)))
        (is (= (find-ns 'github.copilot-sdk.teardown-test) (:logger-ns entry))
            "the log event must be attributed to the caller, not the facade")))))

(defn- disabled-logger-factory
  "A real LoggerFactory whose loggers report every level as disabled."
  []
  (let [logger (reify impl/Logger
                 (enabled? [_ _level] false)
                 (write! [_ _level _throwable _message] nil))]
    (reify impl/LoggerFactory
      (name [_] "disabled-test-factory")
      (get-logger [_ _logger-ns] logger))))

(deftest disabled-logging-evaluates-no-arguments
  (testing "a disabled level evaluates neither the leading nor the trailing
            arguments, so expensive arguments cost nothing"
    (let [leading-calls (atom 0)
          trailing-calls (atom 0)]
      (binding [ctl/*logger-factory* (disabled-logger-factory)]
        (log/debug (do (swap! leading-calls inc) "leading")
                   (do (swap! trailing-calls inc) "trailing")))
      (is (zero? @leading-calls) "the leading argument must stay unevaluated")
      (is (zero? @trailing-calls) "trailing arguments must stay unevaluated"))))

(deftest enabled-logging-evaluates-each-argument-exactly-once
  (testing "an enabled level evaluates every supplied argument exactly once"
    (let [leading-calls (atom 0)
          trailing-calls (atom 0)]
      (log-test/with-log
        (log/warn (do (swap! leading-calls inc) "leading ")
                  (do (swap! trailing-calls inc) "trailing"))
        (is (= "leading trailing" (:message (first (log-test/the-log))))))
      (is (= 1 @leading-calls) "the leading argument must be evaluated once")
      (is (= 1 @trailing-calls) "trailing arguments must be evaluated once"))))

(deftest throwable-argument-is-evaluated-exactly-once
  (testing "a leading Throwable expression is evaluated once and still reaches
            the backend as a throwable"
    (let [boom (ex-info "boom" {})
          calls (atom 0)]
      (log-test/with-log
        (log/error (do (swap! calls inc) boom) "cleanup failed")
        (let [entry (first (log-test/the-log))]
          (is (identical? boom (:throwable entry)))
          (is (= "cleanup failed" (:message entry)))
          (is (= (find-ns 'github.copilot-sdk.teardown-test) (:logger-ns entry)))))
      (is (= 1 @calls) "the Throwable argument must be evaluated once"))))

(deftest cleanup-failure-is-suppressed-and-logged
  (let [primary (ex-info "primary failure" {:phase :body})
        cleanup (ex-info "cleanup failure" {:phase :cleanup})]
    (log-test/with-log
      (is (identical?
           primary
           (teardown/cleanup-preserving!
            primary
            #(throw cleanup))))
      (is (= [cleanup] (vec (.getSuppressed primary))))
      (let [entry (first (log-test/the-log))]
        (is (identical? cleanup (:throwable entry)))
        (is (= "Cleanup failed while preserving the primary failure"
               (:message entry)))))))

(deftest cleanup-preserves-preexisting-interrupt-status
  (let [cleaned? (atom false)
        thread (Thread/currentThread)]
    (.interrupt thread)
    (try
      (let [result (teardown/cleanup-preserving!
                    nil
                    #(reset! cleaned? true))
            interrupted? (.isInterrupted thread)]
        (Thread/interrupted)
        (is (true? result))
        (is interrupted?))
      (is @cleaned?)
      (finally
        (Thread/interrupted)))))

(deftest primary-interruption-remains-primary-and-restores-interrupt-status
  (let [primary (InterruptedException. "body interrupted")
        cleaned? (atom false)
        caught (try
                 (teardown/call-with-cleanup
                  #(throw primary)
                  #(reset! cleaned? true))
                 nil
                 (catch Throwable failure
                   failure))
        interrupted? (.isInterrupted (Thread/currentThread))]
    (try
      (Thread/interrupted)
      (is (identical? primary caught))
      (is @cleaned?)
      (is interrupted?)
      (finally
        (Thread/interrupted)))))

(deftest cleanup-interruption-is-suppressed-under-a-primary-failure
  (let [primary (ex-info "primary" {})
        cleanup (InterruptedException. "cleanup interrupted")]
    (try
      (let [caught (try
                     (teardown/call-with-cleanup
                      #(throw primary)
                      #(throw cleanup))
                     nil
                     (catch Throwable failure
                       failure))
            interrupted? (.isInterrupted (Thread/currentThread))]
        (Thread/interrupted)
        (is (identical? primary caught))
        (is (= [cleanup] (vec (.getSuppressed primary))))
        (is interrupted?))
      (finally
        (Thread/interrupted)))))

(deftest cleanup-only-failure-is-rethrown
  (let [cleanup (ex-info "cleanup only" {})
        caught (try
                 (teardown/call-with-cleanup
                  (constantly :ok)
                  #(throw cleanup))
                 nil
                 (catch Throwable failure
                   failure))]
    (is (identical? cleanup caught))))

;; -----------------------------------------------------------------------------
;; IDI-002 - expected vs unexpected teardown outcomes
;; -----------------------------------------------------------------------------

(defn- recording-read-channel
  [closed on-close]
  (reify ReadableByteChannel
    (read [_ _buf] -1)
    (isOpen [_] true)
    (close [_]
      (swap! closed conj :read-channel)
      (when on-close (throw (on-close))))))

(defn- recording-write-channel
  [closed]
  (reify WritableByteChannel
    (write [_ buf] (.remaining buf))
    (isOpen [_] true)
    (close [_] (swap! closed conj :write-channel))))

(defn- fault-injecting-conn
  "Build a Connection whose only live resources are the two NIO channels, so a
   teardown failure can be injected without a real peer."
  [closed on-read-close]
  (protocol/map->Connection
   {:read-channel (recording-read-channel closed on-read-close)
    :write-channel (recording-write-channel closed)
    :state-atom (atom {:connection (protocol/initial-connection-state)})
    :outgoing-ch (async/chan 1)}))

(deftest expected-close-failure-stays-quiet
  (testing "an already-closed channel is a normal teardown outcome"
    (let [closed (atom [])
          conn (fault-injecting-conn closed #(ClosedChannelException.))]
      (log-test/with-log
        (let [failures (protocol/disconnect conn)]
          (is (= [] failures) "an expected close must not be reported")
          (is (empty? (filter (comp #{:warn :error} :level) (log-test/the-log)))
              "an expected close must not be logged above debug"))))))

(deftest unexpected-close-failure-is-reported-with-resource-identity
  (testing "a close that genuinely fails is reported, and teardown still
            completes the steps that follow it"
    (let [closed (atom [])
          conn (fault-injecting-conn closed #(IOException. "boom"))
          failures (protocol/disconnect conn)]
      (is (= 1 (count failures)))
      (let [failure (first failures)]
        (is (= {:operation :close :resource :read-channel}
               (select-keys (ex-data failure) [:operation :resource])))
        (is (= "boom" (ex-message (ex-cause failure)))))
      (is (= [:read-channel :write-channel] @closed)
          "a failed step must not short-circuit the remaining teardown"))))

(deftest disconnect-is-idempotent
  (testing "a second disconnect reports nothing new"
    (let [closed (atom [])
          conn (fault-injecting-conn closed nil)]
      (is (= [] (protocol/disconnect conn)))
      (is (= [] (protocol/disconnect conn))))))

(deftest unexpected-process-teardown-failure-is-reported
  (testing "destroy! reports a process that cannot be waited on"
    (let [p (proxy [Process] []
              (destroy [] nil)
              (destroyForcibly [] this)
              (isAlive [] true)
              (waitFor
                ([] 0)
                ([_timeout _unit] (throw (IOException. "waitFor exploded")))))
          failures (proc/destroy! (proc/map->ManagedProcess {:process p}) 10)]
      (is (seq failures) "an unexpected process failure must be reported")
      (is (every? #(= :process (:resource (ex-data %))) failures))
      (is (some #(= :wait-for-exit (:operation (ex-data %))) failures)))))

(deftest process-teardown-without-failure-reports-nothing
  (testing "a process that exits promptly produces no teardown failures"
    (let [p (proxy [Process] []
              (destroy [] nil)
              (destroyForcibly [] this)
              (isAlive [] false)
              (waitFor
                ([] 0)
                ([_timeout _unit] true)))]
      (is (= [] (proc/destroy! (proc/map->ManagedProcess {:process p}) 10))))))

(defn- surviving-process
  "A Process that never dies: every wait reports that it is still running."
  []
  (proxy [Process] []
    (destroy [] nil)
    (destroyForcibly [] this)
    (isAlive [] true)
    (waitFor
      ([] 0)
      ([_timeout _unit] false))
    (exitValue [] (throw (IllegalThreadStateException.)))
    (getInputStream [] nil)
    (getOutputStream [] nil)
    (getErrorStream [] nil)))

(deftest process-surviving-forced-kill-is-reported
  (testing "a child that outlives the forced kill window is an unexpected
            failure, not a clean teardown"
    (let [failures (proc/destroy! (proc/map->ManagedProcess {:process (surviving-process)}) 10)]
      (is (seq failures) "a surviving process must never report clean teardown")
      (let [survival (first (filter #(= :forcible (:stage (ex-data %))) failures))]
        (is (some? survival) "the forced-kill stage must be identified")
        (is (= {:operation :wait-for-exit :resource :process :stage :forcible}
               (select-keys (ex-data survival) [:operation :resource :stage])))
        (is (pos? (:timeout-ms (ex-data survival)))
            "the failure must carry the window that elapsed")))))

(deftest stop-retains-the-handle-of-a-process-it-could-not-kill
  (testing "stop! surfaces the surviving process and keeps the only handle to it"
    (let [c (sdk/client {:auto-start? false})
          mp (proc/map->ManagedProcess {:process (surviving-process)})]
      (swap! (:state c) assoc :status :connected :process mp)
      (let [errors (client/stop! c)]
        (is (some #(= :process (:resource (ex-data %))) errors)
            "the surviving process must reach the caller")
        (is (identical? mp (:process @(:state c)))
            "the handle to a live process must not be discarded")))))

(defn- exiting-process
  "A Process that dies as soon as it is asked to."
  []
  (proxy [Process] []
    (destroy [] nil)
    (destroyForcibly [] this)
    (isAlive [] false)
    (waitFor
      ([] 0)
      ([_timeout _unit] true))
    (exitValue [] 0)
    (getInputStream [] nil)
    (getOutputStream [] nil)
    (getErrorStream [] nil)))

(deftest force-stop-retains-the-handle-of-a-process-that-survives-the-kill
  (testing "force-stop! confirms the forced kill: a child that ignores it is
            logged with its resource identity and its handle is kept"
    (let [c (sdk/client {:auto-start? false})
          mp (proc/map->ManagedProcess {:process (surviving-process)})]
      (swap! (:state c) assoc :status :connected :process mp)
      (log-test/with-log
        (is (nil? (client/force-stop! c)) "force-stop! still returns nil")
        (let [warnings (filter (comp #{:warn :error} :level) (log-test/the-log))]
          (is (seq warnings) "a surviving process must be logged")
          (is (some #(some-> (:throwable %) ex-data :resource (= :process)) warnings)
              "the log entry must carry the failing resource as a Throwable")
          (is (some #(some-> (:throwable %) ex-data :stage (= :forcible)) warnings)
              "the forced-kill stage must be identified")))
      (is (identical? mp (:process @(:state c)))
          "the handle to a live process must not be discarded")
      (is (= :disconnected (:status @(:state c)))))))

(deftest force-stop-clears-the-handle-of-a-process-it-killed
  (testing "a confirmed forced kill releases the handle and stays quiet"
    (let [c (sdk/client {:auto-start? false})
          mp (proc/map->ManagedProcess {:process (exiting-process)})]
      (swap! (:state c) assoc :status :connected :process mp)
      (log-test/with-log
        (is (nil? (client/force-stop! c)) "force-stop! still returns nil")
        (is (empty? (filter (comp #{:warn :error} :level) (log-test/the-log)))
            "a confirmed kill must not be reported as a failure"))
      (is (nil? (:process @(:state c)))
          "a confirmed-dead process must release its handle")
      (is (= :disconnected (:status @(:state c)))))))

(deftest forcible-destroy-confirms-exit
  (testing "destroy-forcibly! reports [] only when the child is confirmed gone"
    (is (= [] (proc/destroy-forcibly!
               (proc/map->ManagedProcess {:process (exiting-process)}))))
    (let [failures (proc/destroy-forcibly!
                    (proc/map->ManagedProcess {:process (surviving-process)}))]
      (is (seq failures) "a signal that was merely sent must not report success")
      (let [survival (first failures)]
        (is (= {:operation :wait-for-exit :resource :process :stage :forcible}
               (select-keys (ex-data survival) [:operation :resource :stage])))
        (is (pos? (:timeout-ms (ex-data survival))))))))

;; -----------------------------------------------------------------------------
;; stderr classification depends on whether the child is still alive
;; -----------------------------------------------------------------------------

(defn- exploding-stderr-stream
  [^IOException boom]
  (proxy [java.io.InputStream] []
    (read
      ([] (throw boom))
      ([_b] (throw boom))
      ([_b _off _len] (throw boom)))))

(defn- read-stderr-until-closed!
  "Drain a stderr channel until the reader thread closes it."
  [ch]
  (loop [] (when (some? (async/<!! ch)) (recur))))

(deftest stderr-failure-while-process-is-alive-is-reported
  (testing "losing stderr while the child still runs hides its diagnostics, so
            it is warned with the Throwable and resource identity"
    (let [boom (IOException. "stderr exploded")
          mp (proc/map->ManagedProcess {:stderr (exploding-stderr-stream boom)
                                        :process (surviving-process)})]
      (log-test/with-log
        (read-stderr-until-closed! (proc/stderr-reader mp))
        (let [warnings (filter (comp #{:warn :error} :level) (log-test/the-log))]
          (is (seq warnings) "a live-process stderr failure must be warned")
          (is (some #(identical? boom (:throwable %)) warnings)
              "the Throwable must reach the backend intact")
          (is (some #(str/includes? (str (:message %)) "stderr") warnings)
              "the log must identify the failing resource"))))))

(deftest stderr-failure-after-process-exit-stays-quiet
  (testing "once the child is gone its stderr is expected to fail"
    (let [dead (proxy [Process] []
                 (isAlive [] false)
                 (destroy [] nil)
                 (destroyForcibly [] this)
                 (waitFor ([] 0) ([_t _u] true)))
          mp (proc/map->ManagedProcess {:stderr (exploding-stderr-stream (IOException. "closed"))
                                        :process dead})]
      (log-test/with-log
        (read-stderr-until-closed! (proc/stderr-reader mp))
        (is (empty? (filter (comp #{:warn :error} :level) (log-test/the-log)))
            "an expected stderr close must not be warned")))))

;; -----------------------------------------------------------------------------
;; Client teardown contract wiring
;; -----------------------------------------------------------------------------

(deftest stop-surfaces-transport-failures-in-its-error-vector
  (testing "stop! reports an unexpected connection teardown failure through its
            existing return contract"
    (let [c (sdk/client {:auto-start? false})
          closed (atom [])]
      (swap! (:state c) assoc
             :status :connected
             :connection-io (fault-injecting-conn closed #(IOException. "boom")))
      (let [errors (client/stop! c)]
        (is (some #(= :read-channel (:resource (ex-data %))) errors)
            "the failure must reach the caller")
        (is (nil? (:connection-io @(:state c)))
            "state must still be released")
        (is (= :disconnected (:status @(:state c))))))))

(deftest force-stop-logs-transport-failures
  (testing "force-stop! keeps returning nil but makes the failure observable"
    (let [c (sdk/client {:auto-start? false})
          closed (atom [])]
      (swap! (:state c) assoc
             :status :connected
             :connection-io (fault-injecting-conn closed #(IOException. "boom")))
      (log-test/with-log
        (is (nil? (client/force-stop! c)))
        (is (seq (filter (comp #{:warn :error} :level) (log-test/the-log)))
            "an unexpected teardown failure must be logged")
        (is (some #(some-> (:throwable %) ex-data :resource (= :read-channel))
                  (log-test/the-log))
            "the log entry must carry the failing resource as a Throwable"))
      (is (nil? (:connection-io @(:state c)))))))
