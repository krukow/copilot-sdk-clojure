(ns github.copilot-sdk.integration.client-test
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

(deftest test-client-connection
  (testing "Client connects to mock server"
    (is (= :connected (sdk/state *test-client*)))
    (is (some? (:connection @(:state *test-client*))))))

(deftest test-auto-restart-deprecated-connection-close
  (testing "auto-restart no longer triggers on connection close (deprecated)"
    (let [starts (atom 0)
          stops (atom 0)
          real-maybe-reconnect (var-get (var client/maybe-reconnect!))
          reconnect-observed (promise)]
      (log/info "Warnings expected in this test: connection close no longer triggers auto-restart.")
      (with-redefs-fn
        {(var client/maybe-reconnect!)
         (fn [c reason]
           (let [result (real-maybe-reconnect c reason)]
             (deliver reconnect-observed reason)
             result))}
        #(with-redefs [client/stop! (fn [c]
                                      (swap! stops inc)
                                      (swap! (:state c) assoc :status :disconnected)
                                      [])
                       client/start! (fn [c]
                                       (swap! starts inc)
                                       (swap! (:state c) assoc :status :connected)
                                       nil)]
           (mock/stop-mock-server! *mock-server*)
           (await-value! reconnect-observed "connection-close handling" 1000)
           (is (zero? @stops) "auto-restart is deprecated; stop! should not be called")
           (is (zero? @starts) "auto-restart is deprecated; start! should not be called"))))))

(deftest test-unexpected-connection-close-disconnects-sessions
  (let [invocation-ready (promise)
        cancellation-observed (promise)
        copilot-session
        (sdk/create-session
         *test-client*
         {:on-permission-request sdk/approve-all
          :tools [{:tool-name "connection-close-tool"
                   :tool-handler
                   (fn [_ {:keys [cancel-chan] :as invocation}]
                     (deliver invocation-ready invocation)
                     (let [[_ port]
                           (alts!! [cancel-chan (timeout 2000)] :priority true)]
                       (deliver cancellation-observed
                                (identical? port cancel-chan)))
                     "late result")}]})
        session-id (sdk/session-id copilot-session)
        invocation
        (do
          (mock/send-v3-broadcast-event!
           *mock-server*
           session-id
           "external_tool.requested"
           {:requestId "connection-close-request"
            :toolName "connection-close-tool"
            :toolCallId "connection-close-call"
            :arguments {}})
          (await-value! invocation-ready "connection-close tool invocation" 1000))]
    (mock/stop-mock-server! *mock-server*)
    (await-atom! (:state *test-client*)
                 #(= :disconnected (:status %))
                 "client disconnection"
                 1000)
    (is (true? (await-value! cancellation-observed
                             "connection-close tool cancellation"
                             1000)))
    (is (async-protocols/closed? (:cancel-chan invocation)))
    (is (not (contains? (:sessions @(:state *test-client*)) session-id)))
    (is (false? (:router-running? @(:state *test-client*))))
    (is (nil? (:router-ch @(:state *test-client*))))
    (is (nil? (:lifecycle-ch @(:state *test-client*))))))

(deftest test-unexpected-connection-close-fails-pending-requests
  (let [request-entered (promise)
        release-request (promise)
        connection-io (:connection-io @(:state *test-client*))
        result (promise)]
    (mock/set-request-hook!
     *mock-server*
     (fn [method _]
       (when (= "models.list" method)
         (deliver request-entered true)
         @release-request)))
    (try
      (future
        (deliver
         result
         (try
           (protocol/send-request!
            connection-io "models.list" {} 5000)
           (catch Throwable failure
             failure))))
      (is (true? (await-value! request-entered
                               "pending request"
                               1000)))
      (mock/stop-mock-server! *mock-server*)
      (await-atom! (:state *test-client*)
                   #(= :disconnected (:status %))
                   "client disconnection"
                   1000)
      (let [failure
            (await-value! result
                          "pending request failure"
                          1000)]
        (is (instance? clojure.lang.ExceptionInfo failure))
        (is (= -32000 (get-in (ex-data failure)
                              [:error :code])))
        (is (re-find #"Connection closed"
                     (ex-message failure))))
      (finally
        (deliver release-request true)))))

(deftest test-session-setup-is-fenced-to-originating-connection
  (doseq [{:keys [label method invoke]}
          [{:label "create"
            :method "session.create"
            :invoke (fn [c session-id]
                      (sdk/create-session
                       c
                       {:session-id session-id
                        :on-permission-request sdk/approve-all}))}
           {:label "resume"
            :method "session.resume"
            :invoke (fn [c session-id]
                      (sdk/resume-session
                       c session-id
                       {:on-permission-request sdk/approve-all}))}]]
    (testing (str label " cannot commit an old response into a replacement connection")
      (let [old-server (mock/create-mock-server)
            new-server (mock/create-mock-server)
            c (sdk/client {:auto-start? false})
            session-id (str "setup-generation-" label)
            replacement (atom nil)
            switched? (atom false)
            real-send-request! (var-get #'protocol/send-request!)]
        (try
          (mock/set-request-hook!
           old-server
           (fn [request-method _]
             (when (#{"session.create" "session.resume"} request-method)
               {::mock/merge-response
                {:workspacePath (str "/old/" label)
                 :capabilities {:generation "old"}}})))
          (mock/set-request-hook!
           new-server
           (fn [request-method _]
             (when (= "session.create" request-method)
               {::mock/merge-response
                {:workspacePath (str "/new/" label)
                 :capabilities {:generation "new"}}})))
          (mock/start-mock-server! old-server)
          (let [[in out] (mock/client-streams old-server)]
            (client/connect-with-streams! c in out))
          (when (= method "session.resume")
            (sdk/create-session
             c
             {:session-id session-id
              :on-permission-request sdk/approve-all}))
          (with-redefs-fn
            {(var protocol/send-request!)
             (fn [connection-io request-method params & args]
               (let [result (apply real-send-request!
                                   connection-io request-method params args)]
                 (when (and (= method request-method)
                            (compare-and-set! switched? false true))
                   (close! (protocol/notifications connection-io))
                   (await-atom! (:state c)
                                #(= :disconnected (:status %))
                                "old connection teardown"
                                1000)
                   (mock/start-mock-server! new-server)
                   (let [[in out] (mock/client-streams new-server)]
                     (client/connect-with-streams! c in out))
                   (reset! replacement
                           (sdk/create-session
                            c
                            {:session-id session-id
                             :on-permission-request sdk/approve-all})))
                 result))}
            #(is (thrown? clojure.lang.ExceptionInfo
                          (invoke c session-id))))
          (is (= (str "/new/" label)
                 (sdk/workspace-path @replacement)))
          (is (= {:generation "new"}
                 (sdk/capabilities @replacement)))
          (finally
            (try
              (sdk/stop! c)
              (catch Throwable _))
            (mock/stop-mock-server! old-server)
            (mock/stop-mock-server! new-server)))))))

(deftest test-async-session-setup-is-fenced-to-originating-connection
  (doseq [{:keys [label method invoke]}
          [{:label "create"
            :method "session.create"
            :invoke (fn [c session-id]
                      (sdk/<create-session
                       c
                       {:session-id session-id
                        :on-permission-request sdk/approve-all}))}
           {:label "resume"
            :method "session.resume"
            :invoke (fn [c session-id]
                      (sdk/<resume-session
                       c session-id
                       {:on-permission-request sdk/approve-all}))}]]
    (testing (str label " cannot commit an old async response into a replacement connection")
      (let [old-server (mock/create-mock-server)
            new-server (mock/create-mock-server)
            c (sdk/client {:auto-start? false})
            session-id (str "async-setup-generation-" label)
            replacement (atom nil)
            switched? (atom false)
            real-send-request (var-get #'protocol/send-request)]
        (try
          (mock/set-request-hook!
           old-server
           (fn [request-method _]
             (when (#{"session.create" "session.resume"} request-method)
               {::mock/merge-response
                {:workspacePath (str "/old/async/" label)
                 :capabilities {:generation "old"}}})))
          (mock/set-request-hook!
           new-server
           (fn [request-method _]
             (when (= "session.create" request-method)
               {::mock/merge-response
                {:workspacePath (str "/new/async/" label)
                 :capabilities {:generation "new"}}})))
          (mock/start-mock-server! old-server)
          (let [[in out] (mock/client-streams old-server)]
            (client/connect-with-streams! c in out))
          (when (= method "session.resume")
            (sdk/create-session
             c
             {:session-id session-id
              :on-permission-request sdk/approve-all}))
          (with-redefs-fn
            {(var protocol/send-request)
             (fn [connection-io request-method params & args]
               (let [response-ch
                     (apply real-send-request
                            connection-io request-method params args)]
                 (if (and (= method request-method)
                          (compare-and-set! switched? false true))
                   (let [result-ch (chan 1)]
                     (async/thread
                       (try
                         (let [response (<!! response-ch)]
                           (close! (protocol/notifications connection-io))
                           (await-atom! (:state c)
                                        #(= :disconnected (:status %))
                                        "old async connection teardown"
                                        1000)
                           (mock/start-mock-server! new-server)
                           (let [[new-in new-out]
                                 (mock/client-streams new-server)]
                             (client/connect-with-streams!
                              c new-in new-out))
                           (reset! replacement
                                   (sdk/create-session
                                    c
                                    {:session-id session-id
                                     :on-permission-request sdk/approve-all}))
                           (>!! result-ch response))
                         (finally
                           (close! result-ch))))
                     result-ch)
                   response-ch)))}
            #(is (instance? Throwable
                            (<!! (invoke c session-id)))))
          (is (= (str "/new/async/" label)
                 (sdk/workspace-path @replacement)))
          (is (= {:generation "new"}
                 (sdk/capabilities @replacement)))
          (finally
            (try
              (sdk/stop! c)
              (catch Throwable _))
            (mock/stop-mock-server! old-server)
            (mock/stop-mock-server! new-server)))))))

(deftest test-failed-session-setup-detaches-only-on-originating-connection
  (let [old-server (mock/create-mock-server)
        new-server (mock/create-mock-server)
        c (sdk/client {:auto-start? false})
        replacement-methods (atom [])
        session-id "captured-cleanup-connection"]
    (try
      (mock/start-mock-server! old-server)
      (let [[in out] (mock/client-streams old-server)]
        (client/connect-with-streams! c in out))
      (let [old-connection (:connection-io @(:state c))]
        (close! (protocol/notifications old-connection))
        (await-atom! (:state c)
                     #(= :disconnected (:status %))
                     "old connection teardown"
                     1000)
        (mock/set-request-hook!
         new-server
         (fn [method _]
           (swap! replacement-methods conj method)))
        (mock/start-mock-server! new-server)
        (let [[in out] (mock/client-streams new-server)]
          (client/connect-with-streams! c in out))
        (@#'client/cleanup-failed-session-setup!
         c session-id
         {:connection-io old-connection
          :remote-accepted? true}))
      (is (empty? (filter #{"session.detach"} @replacement-methods)))
      (finally
        (try
          (sdk/stop! c)
          (catch Throwable _))
        (mock/stop-mock-server! old-server)
        (mock/stop-mock-server! new-server)))))

(deftest test-startup-close-cannot-be-overwritten-by-connected-transition
  (doseq [{:keys [label connect]}
          [{:label "caller-supplied streams"
            :connect
            (fn [c server]
              (let [[in out] (mock/client-streams server)]
                (client/connect-with-streams! c in out)))}
           {:label "SDK-owned stdio"
            :connect
            (fn [c server]
              (let [[in out] (mock/client-streams server)
                    exit-ch (chan)
                    managed-process
                    (proc/map->ManagedProcess
                     {:process nil
                      :stdin out
                      :stdout in
                      :stderr (java.io.ByteArrayInputStream. (byte-array 0))
                      :exit-chan exit-ch})]
                (try
                  (with-redefs [proc/spawn-cli (constantly managed-process)]
                    (client/start! c))
                  (finally
                    (close! exit-ch)))))}]]
    (testing label
      (let [server (mock/create-mock-server)
            c (sdk/client {:auto-start? false
                           :use-stdio? (= label "SDK-owned stdio")})]
        (try
          (mock/start-mock-server! server)
          (with-redefs [client/setup-request-handler!
                        (fn [startup-client]
                          (let [connection-io
                                (:connection-io @(:state startup-client))]
                            (close! (protocol/notifications connection-io))
                            (await-atom! (:state startup-client)
                                         #(nil? (:connection-io %))
                                         "startup connection teardown"
                                         1000)))]
            (is (thrown? clojure.lang.ExceptionInfo
                         (connect c server))))
          (is (not= :connected (:status @(:state c))))
          (is (nil? (:connection-io @(:state c))))
          (finally
            (try
              (sdk/stop! c)
              (catch Throwable _))
            (mock/stop-mock-server! server)))))))

(deftest test-unexpected-close-terminates-the-exact-sdk-owned-process
  (let [server (mock/create-mock-server)
        c (sdk/client {:auto-start? false})
        managed-process (proc/map->ManagedProcess {:process ::process})
        destroyed (promise)]
    (try
      (mock/start-mock-server! server)
      (let [[in out] (mock/client-streams server)]
        (client/connect-with-streams! c in out))
      (swap! (:state c) assoc :process managed-process)
      (with-redefs [proc/destroy!
                    (fn [process]
                      (deliver destroyed process)
                      [])]
        (close! (protocol/notifications
                 (:connection-io @(:state c))))
        (is (identical? managed-process
                        (await-value! destroyed
                                      "owned process teardown"
                                      1000)))
        (await-atom! (:state c)
                     #(nil? (:process %))
                     "owned process release"
                     1000))
      (finally
        (mock/stop-mock-server! server)))))

(deftest test-unexpected-close-preserves-process-handle-when-teardown-fails
  (let [server (mock/create-mock-server)
        c (sdk/client {:auto-start? false})
        managed-process (proc/map->ManagedProcess {:process ::process})
        teardown-attempted (promise)
        failure (ex-info "process survived" {:resource :process})]
    (try
      (mock/start-mock-server! server)
      (let [[in out] (mock/client-streams server)]
        (client/connect-with-streams! c in out))
      (swap! (:state c) assoc :process managed-process)
      (with-redefs [proc/destroy!
                    (fn [process]
                      (deliver teardown-attempted process)
                      [failure])]
        (close! (protocol/notifications
                 (:connection-io @(:state c))))
        (is (identical? managed-process
                        (await-value! teardown-attempted
                                      "failed process teardown"
                                      1000)))
        (await-atom! (:state c)
                     #(= :disconnected (:status %))
                     "connection teardown"
                     1000)
        (is (identical? managed-process
                        (:process @(:state c)))))
      (finally
        (mock/stop-mock-server! server)))))

(deftest test-start-rejects-a-live-process-from-a-lost-transport
  (let [c (sdk/client {:auto-start? false})
        managed-process (proc/map->ManagedProcess {:process ::process})
        spawn-count (atom 0)]
    (swap! (:state c)
           assoc
           :status :disconnected
           :process managed-process)
    (with-redefs [proc/alive? #(identical? managed-process %)
                  proc/spawn-cli (fn [_]
                                   (swap! spawn-count inc)
                                   (throw (ex-info "spawned replacement" {})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"process.*running"
                            (client/start! c)))
      (is (zero? @spawn-count))
      (is (identical? managed-process
                      (:process @(:state c)))))))

(deftest test-auto-restart-deprecated-process-exit
  (testing "auto-restart no longer triggers on process exit (deprecated)"
    (let [starts (atom 0)
          stops (atom 0)
          exit-ch (chan 1)
          real-maybe-reconnect (var-get (var client/maybe-reconnect!))
          reconnect-observed (promise)
          watch-exit (var client/watch-process-exit!)]
      (log/info "Warnings expected in this test: simulated process exit no longer triggers auto-restart.")
      (with-redefs-fn
        {(var client/maybe-reconnect!)
         (fn [c reason]
           (let [result (real-maybe-reconnect c reason)]
             (deliver reconnect-observed reason)
             result))}
        #(with-redefs [client/stop! (fn [c]
                                      (swap! stops inc)
                                      (swap! (:state c) assoc :status :disconnected)
                                      [])
                       client/start! (fn [c]
                                       (swap! starts inc)
                                       (swap! (:state c) assoc :status :connected)
                                       nil)]
           (watch-exit *test-client* {:exit-chan exit-ch})
           (>!! exit-ch {:exit-code 123})
           (close! exit-ch)
           (await-value! reconnect-observed "process-exit handling" 1000)
           (is (zero? @stops) "auto-restart is deprecated; stop! should not be called")
           (is (zero? @starts) "auto-restart is deprecated; start! should not be called"))))))

(deftest test-auto-restart-suppressed-when-stopping
  (testing "auto-restart is suppressed while stopping"
    (let [starts (atom 0)
          stops (atom 0)
          real-maybe-reconnect (var-get (var client/maybe-reconnect!))
          reconnect-observed (promise)]
      (swap! (:state *test-client*) assoc :stopping? true)
      (try
        (with-redefs-fn
          {(var client/maybe-reconnect!)
           (fn [c reason]
             (let [result (real-maybe-reconnect c reason)]
               (deliver reconnect-observed reason)
               result))}
          #(with-redefs [client/stop! (fn [_] (swap! stops inc) [])
                         client/start! (fn [_] (swap! starts inc) nil)]
             (mock/stop-mock-server! *mock-server*)
             (await-value! reconnect-observed "stopping connection-close handling" 1000)
             (is (zero? @stops))
             (is (zero? @starts))))
        (finally
          (swap! (:state *test-client*) assoc :stopping? false))))))

(deftest test-stderr-capture-and-forwarding
  (testing "start-stderr-forwarder! captures stderr lines"
    (let [stderr-content "error line 1\nerror line 2\nwarning: something\n"
          stderr-stream (java.io.ByteArrayInputStream.
                         (.getBytes stderr-content "UTF-8"))
          exit-ch (chan 1)
          fake-mp (github.copilot-sdk.process/map->ManagedProcess
                   {:process nil :stdin nil :stdout nil
                    :stderr stderr-stream :exit-chan exit-ch})
          start-forwarder (var client/start-stderr-forwarder!)
          get-stderr (var client/get-stderr-output)
          client (sdk/client {:auto-start? false})]
      (let [stderr-buffer (start-forwarder client fake-mp)]
        (await-atom! stderr-buffer #(= 3 (count %)) "stderr drain" 1000))
      (let [output (get-stderr client)]
        (is (some? output) "stderr output should be captured")
        (is (clojure.string/includes? output "error line 1"))
        (is (clojure.string/includes? output "error line 2"))
        (is (clojure.string/includes? output "warning: something")))
      ;; Verify buffer atom contains individual lines
      (let [buf @(:stderr-buffer @(:state client))]
        (is (= 3 (count buf)))
        (is (= "error line 1" (first buf))))))

  (testing "get-stderr-output returns nil when no stderr captured"
    (let [client (sdk/client {:auto-start? false})
          get-stderr (var client/get-stderr-output)]
      (is (nil? (get-stderr client))))))

(deftest test-early-process-exit-detected-during-startup
  (testing "verify-protocol-version! detects early process exit with stderr"
    (let [exit-ch (chan 1)
          ;; Inject a fake process and pre-populated stderr buffer
          _ (swap! (:state *test-client*) assoc
                   :process {:exit-chan exit-ch}
                   :stderr-buffer (atom ["fatal: config file not found"
                                         "copilot: exiting"]))
          ;; Signal process exit before the ping can complete
          _ (>!! exit-ch {:exit-code 1})
          _ (close! exit-ch)
          verify-version (var client/verify-protocol-version!)]
      (try
        (verify-version *test-client*)
        (is false "Should have thrown on early process exit")
        (catch clojure.lang.ExceptionInfo e
          (is (clojure.string/includes? (ex-message e) "CLI server exited with code 1"))
          (is (clojure.string/includes? (ex-message e) "fatal: config file not found"))
          (is (= 1 (:exit-code (ex-data e))))
          (is (some? (:stderr (ex-data e)))))))))

(deftest test-ping
  (testing "Ping returns protocol version"
    (let [result (sdk/ping *test-client*)]
      (is (= 3 (:protocol-version result)))
      ;; Upstream PR #1340 / CLI 1.0.51 changed timestamp from epoch number
      ;; to ISO 8601 string (`timestamp: string, format: date-time`).
      (is (string? (:timestamp result)))
      (is (some? (java.time.Instant/parse (:timestamp result)))
          ":timestamp parses as ISO 8601 instant")))
  (testing "::specs/timestamp accepts both ISO string (CLI ≥ 1.0.51) and epoch-millis number (older CLIs)"
    (is (s/valid? :github.copilot-sdk.specs/timestamp "2026-05-21T08:00:00.000Z"))
    (is (s/valid? :github.copilot-sdk.specs/timestamp (System/currentTimeMillis))
        "System/currentTimeMillis-sized long validates as epoch-ms")
    (is (s/valid? :github.copilot-sdk.specs/timestamp 1700000000000)
        "representative epoch-ms long validates")
    (is (not (s/valid? :github.copilot-sdk.specs/timestamp -1))
        "epoch-ms must be non-negative")
    (is (not (s/valid? :github.copilot-sdk.specs/timestamp 1.5))
        "epoch-ms must be an integer, not arbitrary number")))

(deftest test-get-status
  (testing "Get CLI status returns version and protocol"
    (let [result (sdk/get-status *test-client*)]
      (is (string? (:version result)))
      (is (= 3 (:protocol-version result))))))

(deftest test-get-auth-status
  (testing "Get auth status returns authentication info"
    (let [result (sdk/get-auth-status *test-client*)]
      (is (boolean? (:authenticated? result)))
      (when (:authenticated? result)
        (is (keyword? (:auth-type result)))
        (is (string? (:login result)))))))

(deftest test-list-models
  (testing "List models returns available models"
    (let [models (sdk/list-models *test-client*)]
      (is (vector? models))
      (is (pos? (count models)))
      (let [model (first models)]
        (is (string? (:id model)))
        (is (string? (:name model)))
        (is (string? (:vendor model)))
        (is (number? (:max-input-tokens model)))
        (is (number? (:max-output-tokens model)))))))

(deftest test-list-models-with-on-list-models-handler
  (let [call-count (atom 0)
        fake-models [{:id "test-model" :name "Test Model" :vendor "test"
                      :family "test" :version "1" :max-input-tokens 4096
                      :max-output-tokens 1024 :preview? false}]
        handler (fn []
                  (swap! call-count inc)
                  fake-models)
        c (sdk/client {:auto-start? false :on-list-models handler})]
    (testing "returns handler result without requiring start!"
      (let [models (sdk/list-models c)]
        (is (vector? models))
        (is (= 1 (count models)))
        (is (= "test-model" (:id (first models))))))
    (testing "caches result (handler called only once)"
      (let [_m1 (sdk/list-models c)
            _m2 (sdk/list-models c)]
        (is (= 1 @call-count))))))

(deftest test-list-models-uses-canonical-model-capabilities-shape
  (mock/set-request-hook!
   *mock-server*
   (fn [method _params]
     (when (= "models.list" method)
       {:github.copilot-sdk.mock-server/merge-response
        {:models [{:id "capable-model"
                   :name "Capable Model"
                   :capabilities
                   {:supports {:vision true
                               :reasoningEffort false
                               :adaptive_thinking "required"}
                    :limits {:max_prompt_tokens 120000
                             :max_output_tokens 16000
                             :max_context_window_tokens 136000
                             :vision {:supported_media_types ["image/png"]
                                      :max_prompt_images 5
                                      :max_prompt_image_size 1048576}}}}]}})))
  (let [capabilities (:model-capabilities (first (sdk/list-models *test-client*)))]
    (is (= {:vision true
            :reasoning-effort false
            :adaptive-thinking :required}
           (:supports capabilities)))
    (is (= {:max-prompt-tokens 120000
            :max-output-tokens 16000
            :max-context-window-tokens 136000
            :vision {:supported-media-types ["image/png"]
                     :max-prompt-images 5
                     :max-prompt-image-size 1048576}}
           (:limits capabilities)))
    (is (not (contains? capabilities :model-supports)))
    (is (not (contains? capabilities :model-limits)))))
