(ns github.copilot-sdk.helpers-query-example-test
  (:require [clojure.java.io :as io]
            [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as async-protocols]
            [clojure.test :refer [deftest is]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.session :as session])
  (:import [java.nio.file Files]
           [java.util.concurrent CountDownLatch TimeUnit]))

(def ^:private test-resource-path
  "github/copilot_sdk/helpers_query_example_test.clj")

(defn- resolve-helper-example-file
  []
  (let [resource (or (io/resource test-resource-path)
                     (throw (ex-info "Helper example test resource not found"
                                     {:resource test-resource-path})))]
    (when-not (= "file" (.getProtocol resource))
      (throw (ex-info "Helper example test resource must be a file URL"
                      {:resource test-resource-path
                       :url (str resource)})))
    (let [resource-file (.getCanonicalFile (io/file (.toURI resource)))
          test-root (loop [directory (.getParentFile resource-file)]
                      (cond
                        (nil? directory)
                        (throw (ex-info "Could not derive repository test root"
                                        {:resource-file (.getPath resource-file)}))

                        (= "test" (.getName directory))
                        directory

                        :else
                        (recur (.getParentFile directory))))
          repository-root (.getParentFile test-root)
          example-file (.getCanonicalFile
                        (io/file repository-root "examples" "helpers_query.clj"))]
      (when-not (.isFile example-file)
        (throw (ex-info "Helper example source not found"
                        {:path (.getPath example-file)})))
      example-file)))

(load-file (.getPath (resolve-helper-example-file)))

(defn- example-var
  [sym]
  (ns-resolve 'helpers-query sym))

(deftest helper-example-resolution-is-cwd-independent
  (let [original-user-dir (System/getProperty "user.dir")
        temporary-dir (.toFile (Files/createTempDirectory "copilot-example-test" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (System/setProperty "user.dir" (.getCanonicalPath temporary-dir))
      (let [example-file (resolve-helper-example-file)]
        (is (.isFile example-file))
        (is (= "helpers_query.clj" (.getName example-file)))
        (is (not (.startsWith (.toPath example-file) (.toPath temporary-dir)))))
      (finally
        (System/setProperty "user.dir" original-user-dir)
        (Files/deleteIfExists (.toPath temporary-dir))))
    (is (= original-user-dir (System/getProperty "user.dir")))))

(deftest streaming-timeout-force-stops-its-owned-client
  (let [calls (atom [])
        client-calls (atom 0)
        events-ch (async/chan)
        state (atom {:status :disconnected
                     :sessions {}
                     :process nil
                     :connection-io nil})
        client {:state state}
        outcome
        (with-redefs [sdk/client (fn [_opts]
                                   (swap! client-calls inc)
                                   client)
                      sdk/start! (fn [_client]
                                   (swap! calls conj :start)
                                   (swap! state assoc :status :connected))
                      sdk/create-session (fn [actual-client _config]
                                           (is (identical? client actual-client))
                                           (swap! calls conj :create-session)
                                           (swap! state assoc-in [:sessions "session"] {})
                                           ::session)
                      sdk/subscribe-events (fn [_session]
                                             (swap! calls conj :subscribe)
                                             events-ch)
                      session/send-with-timeout!
                      (fn [_session _message _timeout-ms]
                        (swap! calls conj :send))
                      sdk/force-stop! (fn [actual-client]
                                        (is (identical? client actual-client))
                                        (swap! calls conj :force-stop)
                                        (async/close! events-ch)
                                        (reset! state {:status :disconnected
                                                       :sessions {}
                                                       :process nil
                                                       :connection-io nil}))
                      sdk/disconnect! (fn [_session]
                                        (swap! calls conj :worker-finally)
                                        (swap! state assoc :sessions {}))
                      sdk/stop! (fn [actual-client]
                                  (is (identical? client actual-client))
                                  (swap! calls conj :owner-finally)
                                  (swap! state assoc :status :disconnected)
                                  [])]
          (try
            ((example-var 'run-streaming) {:timeout-ms 10})
            (catch clojure.lang.ExceptionInfo e
              e)))]
    (is (= :example-timeout (:type (ex-data outcome))))
    (is (= 1 @client-calls))
    (is (= [:start :create-session :subscribe :send
            :force-stop :worker-finally :owner-finally]
           @calls))
    (is (true? (async-protocols/closed? events-ch)))
    (is (= {:status :disconnected
            :sessions {}
            :process nil
            :connection-io nil}
           @state))))

(deftest cancellation-failure-still-interrupts-and-confirms-cleanup
  (let [run-bounded! (deref (example-var 'run-bounded!))
        order (atom [])
        latch (CountDownLatch. 1)
        cancel-error (ex-info "cancel failed" {})
        outcome
        (try
          (run-bounded! "controlled" 10
                        #(do
                           (swap! order conj :cancel)
                           (throw cancel-error))
                        #(try
                           (.await latch)
                           (finally
                             (swap! order conj :worker-finally))))
          (catch clojure.lang.ExceptionInfo e
            e))]
    (is (= :example-cancel-failed (:type (ex-data outcome))))
    (is (identical? cancel-error (ex-cause outcome)))
    (is (= [:cancel :worker-finally] @order))))

(deftest incomplete-cleanup-is-loud
  (let [run-bounded! (deref (example-var 'run-bounded!))
        cleanup-timeout-var (example-var 'cleanup-timeout-ms)
        latch (CountDownLatch. 1)
        finished (promise)
        cancel-error (ex-info "cancel failed" {})
        outcome
        (with-redefs-fn
          {cleanup-timeout-var 25}
          #(try
             (run-bounded!
              "controlled"
              10
              (fn [] (throw cancel-error))
              (fn []
                (try
                  (loop []
                    (let [released?
                          (try
                            (.await latch 10 TimeUnit/MILLISECONDS)
                            (catch InterruptedException _
                              false))]
                      (when-not released?
                        (recur))))
                  (finally
                    (deliver finished true)))))
             (catch clojure.lang.ExceptionInfo e
               e)))]
    (is (= :example-cleanup-timeout (:type (ex-data outcome))))
    (is (identical? cancel-error (ex-cause outcome)))
    (.countDown latch)
    (is (true? (deref finished 1000 false)))))
