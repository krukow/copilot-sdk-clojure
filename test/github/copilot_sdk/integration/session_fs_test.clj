(ns github.copilot-sdk.integration.session-fs-test
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

(deftest test-session-fs-handler-stored
  (testing "session-fs-handler is stored in session state when provided"
    (let [fs-handler {:read-file (fn [_] {:content "hello"})
                      :write-file (fn [_] nil)
                      :append-file (fn [_] nil)
                      :exists (fn [_] {:exists true})
                      :stat (fn [_] {:is-file true :is-directory false :size 5 :mtime "2026-01-01T00:00:00Z"})
                      :mkdir (fn [_] nil)
                      :readdir (fn [_] {:entries []})
                      :readdir-with-types (fn [_] {:entries []})
                      :rm (fn [_] nil)
                      :rename (fn [_] nil)}
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)]
      ;; Set handler directly (as client code would do after factory call)
      (session/set-session-fs-handler! (:client session) session-id fs-handler)
      (let [stored (get-in @(:state *test-client*)
                           [:sessions session-id :session-fs-handler])]
        (is (some? stored))
        (is (fn? (:read-file stored)))
        (is (= {:content "hello"} ((:read-file stored) {:path "/test.txt"})))))))

(deftest test-create-session-fs-adapter
  (testing "provider-style functions are adapted to sessionFs handler results"
    (let [provider {:read-file (fn [path]
                                 (is (= "/ok.txt" path))
                                 "hello")
                    :write-file (fn [path content mode]
                                  (is (= "/ok.txt" path))
                                  (is (= "updated" content))
                                  (is (= 420 mode)))
                    :exists (fn [_path] true)
                    :stat (fn [_path] {:is-file true
                                       :is-directory false
                                       :size 5
                                       :mtime "2026-01-01T00:00:00Z"
                                       :birthtime "2026-01-01T00:00:00Z"})
                    :readdir (fn [_path] ["a.txt"])
                    :readdir-with-types (fn [_path] [{:name "a.txt" :is-file true :is-directory false}])
                    :append-file (fn [_path _content _mode] nil)
                    :mkdir (fn [_path _recursive _mode] nil)
                    :rm (fn [_path _recursive _force] nil)
                    :rename (fn [_src _dest] nil)}
          handler (session/create-session-fs-adapter provider)]
      (is (= {:content "hello"} ((:read-file handler) {:path "/ok.txt"})))
      (is (nil? ((:write-file handler) {:path "/ok.txt"
                                        :content "updated"
                                        :mode 420})))
      (is (= {:exists true} ((:exists handler) {:path "/ok.txt"})))
      (is (= {:entries ["a.txt"]} ((:readdir handler) {:path "/"})))))

  (testing "provider exceptions become structured sessionFs errors"
    (let [missing (ex-info "missing file" {:code "ENOENT"})
          provider {:read-file (fn [_path] (throw missing))
                    :write-file (fn [_path _content _mode]
                                  (throw (ex-info "disk full" {})))
                    :exists (fn [_path] (throw (ex-info "boom" {})))
                    :stat (fn [_path] (throw missing))
                    :readdir (fn [_path] (throw missing))
                    :readdir-with-types (fn [_path] (throw missing))
                    :append-file (fn [_path _content _mode] nil)
                    :mkdir (fn [_path _recursive _mode] nil)
                    :rm (fn [_path _recursive _force] nil)
                    :rename (fn [_src _dest] nil)}
          handler (session/create-session-fs-adapter provider)]
      (is (= {:content ""
              :error {:code "ENOENT" :message "missing file"}}
             ((:read-file handler) {:path "/missing.txt"})))
      (is (= {:code "UNKNOWN" :message "disk full"}
             ((:write-file handler) {:path "/x" :content "data"})))
      (is (= {:exists false} ((:exists handler) {:path "/x"})))
      (is (= "ENOENT" (get-in ((:stat handler) {:path "/x"}) [:error :code])))
      (is (= {:entries []
              :error {:code "ENOENT" :message "missing file"}}
             ((:readdir handler) {:path "/x"})))))

  (testing "provider async results are realized before normalization"
    (letfn [(value-chan [value]
              (let [ch (chan 1)]
                (>!! ch value)
                (close! ch)
                ch))
            (promise-value [value]
              (let [ch (async/promise-chan)]
                (>!! ch value)
                ch))]
      (let [writes (atom [])
            provider {:read-file (fn [_path] (value-chan "async content"))
                      :write-file (fn [path content mode]
                                    (future (swap! writes conj [path content mode])))
                      :exists (fn [_path] (promise-value false))
                      :stat (fn [_path] (future {:is-file true
                                                 :is-directory false
                                                 :size 13}))
                      :readdir (fn [_path] (value-chan ["async.txt"]))
                      :readdir-with-types (fn [_path] (future [{:name "async.txt"
                                                                :is-file true
                                                                :is-directory false}]))
                      :append-file (fn [_path _content _mode] (value-chan nil))
                      :mkdir (fn [_path _recursive _mode] (future nil))
                      :rm (fn [_path _recursive _force] (value-chan nil))
                      :rename (fn [_src _dest] (future nil))}
            handler (session/create-session-fs-adapter provider)]
        (is (= {:content "async content"}
               ((:read-file handler) {:path "/async.txt"})))
        (is (nil? ((:write-file handler) {:path "/async.txt"
                                          :content "updated"
                                          :mode 420})))
        (is (= [["/async.txt" "updated" 420]] @writes))
        (is (= {:exists false} ((:exists handler) {:path "/async.txt"})))
        (is (= {:is-file true :is-directory false :size 13}
               ((:stat handler) {:path "/async.txt"})))
        (is (= {:entries ["async.txt"]} ((:readdir handler) {:path "/"})))
        (is (= {:entries [{:name "async.txt" :is-file true :is-directory false}]}
               ((:readdir-with-types handler) {:path "/"}))))))

  (testing "one-arg fallback exceptions are converted to sessionFs errors"
    (let [provider {:read-file (fn [_path] "ok")
                    :write-file (fn [& args]
                                  (if (= 1 (count args))
                                    (throw (ex-info "fallback missing" {:code "ENOENT"}))
                                    (throw (clojure.lang.ArityException. 3 "write-file"))))
                    :exists (fn [_path] true)
                    :stat (fn [_path] {:is-file true :is-directory false :size 2})
                    :readdir (fn [_path] [])
                    :readdir-with-types (fn [_path] [])
                    :append-file (fn [_path _content _mode] nil)
                    :mkdir (fn [_path _recursive _mode] nil)
                    :rm (fn [_path _recursive _force] nil)
                    :rename (fn [_src _dest] nil)}
          handler (session/create-session-fs-adapter provider)]
      (is (= {:code "ENOENT" :message "fallback missing"}
             ((:write-file handler) {:path "/x" :content "data"})))))

  (testing "provider spec distinguishes provider-style maps from low-level handlers"
    (let [provider {:read-file (fn [_path] "ok")
                    :write-file (fn [_path _content _mode] nil)
                    :exists (fn [_path] true)
                    :stat (fn [_path] {:is-file true :is-directory false :size 2})
                    :readdir (fn [_path] [])
                    :readdir-with-types (fn [_path] [])
                    :append-file (fn [_path _content _mode] nil)
                    :mkdir (fn [_path _recursive _mode] nil)
                    :rm (fn [_path _recursive _force] nil)
                    :rename (fn [_src _dest] nil)}
          low-level-handler {:read-file (fn [_params] {:content "ok"})
                             :write-file (fn [_params] nil)
                             :exists (fn [_params] {:exists true})
                             :stat (fn [_params] {:is-file true :is-directory false :size 2})
                             :readdir (fn [_params] {:entries []})
                             :readdir-with-types (fn [_params] {:entries []})
                             :append-file (fn [_params] nil)
                             :mkdir (fn [_params] nil)
                             :rm (fn [_params] nil)
                             :rename (fn [_params] nil)}]
      (is (s/valid? :github.copilot-sdk.specs/session-fs-provider provider))
      (is (not (s/valid? :github.copilot-sdk.specs/session-fs-provider low-level-handler)))
      (is (s/valid? :github.copilot-sdk.specs/session-fs-handler low-level-handler))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid sessionFs provider"
                            (session/create-session-fs-adapter low-level-handler)))))

  (testing "adapter detection is nil-safe for incomplete maps"
    (is (= {:read-file :github.copilot-sdk.integration-test/only}
           (session/adapt-session-fs-handler
            {:read-file :github.copilot-sdk.integration-test/only})))))

(deftest test-create-session-fs-handler-factory-auto-adapts-provider
  (testing "create-session auto-adapts provider-style factory return like upstream Node"
    (let [calls (atom [])
          client-with-fs (assoc *test-client* :session-fs {:initial-cwd "/workspace"
                                                           :session-state-path "/state"
                                                           :conventions "posix"})
          session (sdk/create-session client-with-fs
                                      {:on-permission-request sdk/approve-all
                                       :create-session-fs-handler
                                       (fn [_session]
                                         {:read-file (fn [path]
                                                       (swap! calls conj [:read-file path])
                                                       "content")
                                          :write-file (fn [path content mode]
                                                        (swap! calls conj [:write-file path content mode]))
                                          :append-file (fn [_path _content _mode] nil)
                                          :exists (fn [_path] true)
                                          :stat (fn [_path] {:is-file true :is-directory false :size 7})
                                          :mkdir (fn [_path _recursive _mode] nil)
                                          :readdir (fn [_path] [])
                                          :readdir-with-types (fn [_path] [])
                                          :rm (fn [_path _recursive _force] nil)
                                          :rename (fn [_src _dest] nil)})})
          session-id (sdk/session-id session)
          read-response (mock/send-rpc-request! *mock-server*
                                                "sessionFs.readFile"
                                                {:sessionId session-id :path "/file.txt"})
          write-response (mock/send-rpc-request! *mock-server*
                                                 "sessionFs.writeFile"
                                                 {:sessionId session-id
                                                  :path "/file.txt"
                                                  :content "updated"
                                                  :mode 420})]
      (is (= {:content "content"} (:result read-response)))
      (is (nil? (:result write-response)))
      (is (= [[:read-file "/file.txt"]
              [:write-file "/file.txt" "updated" 420]]
             @calls)))))

(deftest test-create-session-fs-handler-factory-preserves-low-level-handler
  (testing "create-session still accepts existing one-arg RPC-shaped handler maps"
    (let [client-with-fs (assoc *test-client* :session-fs {:initial-cwd "/workspace"
                                                           :session-state-path "/state"
                                                           :conventions "posix"})
          session (sdk/create-session client-with-fs
                                      {:on-permission-request sdk/approve-all
                                       :create-session-fs-handler
                                       (fn [_session]
                                         {:read-file (fn [{:keys [path]}]
                                                       {:content (str "read " path)})
                                          :write-file (fn [_params] nil)
                                          :append-file (fn [_params] nil)
                                          :exists (fn [_params] {:exists true})
                                          :stat (fn [_params] {:is-file true :is-directory false :size 7})
                                          :mkdir (fn [_params] nil)
                                          :readdir (fn [_params] {:entries []})
                                          :readdir-with-types (fn [_params] {:entries []})
                                          :rm (fn [_params] nil)
                                          :rename (fn [_params] nil)})})
          response (mock/send-rpc-request! *mock-server*
                                           "sessionFs.readFile"
                                           {:sessionId (sdk/session-id session)
                                            :path "/legacy.txt"})]
      (is (= {:content "read /legacy.txt"} (:result response))))))

(deftest test-create-session-fs-handler-factory-validates-return
  (let [client-with-fs (assoc *test-client* :session-fs {:initial-cwd "/workspace"
                                                         :session-state-path "/state"
                                                         :conventions "posix"})
        requests (atom [])
        _ (mock/set-request-hook! *mock-server*
                                  (fn [method _]
                                    (when (#{"session.create" "session.resume"} method)
                                      (swap! requests conj method))))
        invalid-factory (fn [_session]
                          {:read-file (fn [_params] {:content "partial"})})
        config {:on-permission-request sdk/approve-all
                :github-token-provider (fn [_] {:kind :cancelled})
                :create-session-fs-handler invalid-factory}]
    (testing "create-session fails before storing an invalid handler"
      (let [session-id "invalid-fs-create"]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Invalid sessionFs handler"
                              (sdk/create-session client-with-fs
                                                  (assoc config :session-id session-id))))
        (is (nil? (get-in @(:state client-with-fs) [:sessions session-id])))))
    (testing "<create-session fails synchronously before returning a channel"
      (let [session-id "invalid-fs-create-async"]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Invalid sessionFs handler"
                              (sdk/<create-session client-with-fs
                                                   (assoc config :session-id session-id))))
        (is (nil? (get-in @(:state client-with-fs) [:sessions session-id])))))
    (testing "resume-session fails before storing an invalid handler"
      (let [session-id "invalid-fs-resume"]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Invalid sessionFs handler"
                              (sdk/resume-session client-with-fs session-id config)))
        (is (nil? (get-in @(:state client-with-fs) [:sessions session-id])))))
    (testing "<resume-session fails synchronously before returning a channel"
      (let [session-id "invalid-fs-resume-async"]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Invalid sessionFs handler"
                              (sdk/<resume-session client-with-fs session-id config)))
        (is (nil? (get-in @(:state client-with-fs) [:sessions session-id])))))
    (is (empty? @requests)
        "local handler preparation must complete before any session RPC")
    (is (empty?
         (get-in @(:state client-with-fs)
                 [:github-token-provider-runtime :registrations]))
        "pre-RPC setup failure must roll back provisional provider state")))

(deftest test-resume-pre-registration-failure-preserves-existing-session
  (let [existing (sdk/create-session
                  *test-client*
                  {:on-permission-request sdk/approve-all})
        session-id (sdk/session-id existing)
        state-before @(:state *test-client*)
        existing-state (get-in state-before [:sessions session-id])
        existing-io (get-in state-before [:session-io session-id])
        providers-before
        (get-in state-before
                [:github-token-provider-runtime :registrations])]
    (with-redefs [session/create-session
                  (fn [& _]
                    (throw (ex-info "pre-registration failed" {})))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"pre-registration failed"
           (sdk/resume-session
            *test-client*
            session-id
            {:on-permission-request sdk/approve-all
             :github-token-provider (fn [_] {:kind :cancelled})}))))
    (let [state-after @(:state *test-client*)
          restored-state (get-in state-after [:sessions session-id])
          restored-io (get-in state-after [:session-io session-id])]
      (is (identical? existing-state restored-state))
      (is (identical? existing-io restored-io))
      (doseq [channel-key [:event-chan :send-lock]]
        (is (identical? (get existing-io channel-key)
                        (get restored-io channel-key)))
        (is (false? (async-protocols/closed?
                     (get restored-io channel-key)))))
      (is (= providers-before
             (get-in state-after
                     [:github-token-provider-runtime :registrations]))))))

(defn- test-session-fs-provider [sqlite]
  {:read-file (fn [_] "x")
   :write-file (fn [_ _ _] nil)
   :append-file (fn [_ _ _] nil)
   :exists (fn [_] true)
   :stat (fn [_] {:is-file true :is-directory false :size 1 :mtime "x" :birthtime "x"})
   :mkdir (fn [_ _ _] nil)
   :readdir (fn [_] [])
   :readdir-with-types (fn [_] [])
   :rm (fn [_ _ _] nil)
   :rename (fn [_ _] nil)
   :sqlite sqlite})

(deftest test-create-session-fs-adapter-sqlite
  (testing "provider with nested :sqlite map is adapted to flat :sqlite-query and :sqlite-exists handler keys"
    (let [provider {:read-file (fn [_] "x")
                    :write-file (fn [_ _ _] nil)
                    :append-file (fn [_ _ _] nil)
                    :exists (fn [_] true)
                    :stat (fn [_] {:is-file true :is-directory false :size 1 :mtime "2026-01-01T00:00:00Z" :birthtime "2026-01-01T00:00:00Z"})
                    :mkdir (fn [_ _ _] nil)
                    :readdir (fn [_] [])
                    :readdir-with-types (fn [_] [])
                    :rm (fn [_ _ _] nil)
                    :rename (fn [_ _] nil)
                    :sqlite {:query (fn [query-type sql params]
                                      (is (= :query query-type))
                                      (is (= "SELECT 1" sql))
                                      (is (= {:$id 7} params))
                                      {:rows [{:n 1}] :columns ["n"] :rows-affected 0})
                             :exists (fn [] true)}}
          handler (session/create-session-fs-adapter provider)]
      (is (fn? (:sqlite-query handler)))
      (is (fn? (:sqlite-exists handler)))
      (is (= {:rows [{:n 1}] :columns ["n"] :rows-affected 0}
             ((:sqlite-query handler) {:query-type :query :query "SELECT 1" :params {:$id 7}})))
      (is (= {:exists true} ((:sqlite-exists handler) {})))))

  (testing "provider without :sqlite gets no sqlite handlers"
    (let [provider {:read-file (fn [_] "x")
                    :write-file (fn [_ _ _] nil)
                    :append-file (fn [_ _ _] nil)
                    :exists (fn [_] true)
                    :stat (fn [_] {:is-file true :is-directory false :size 1 :mtime "x" :birthtime "x"})
                    :mkdir (fn [_ _ _] nil)
                    :readdir (fn [_] [])
                    :readdir-with-types (fn [_] [])
                    :rm (fn [_ _ _] nil)
                    :rename (fn [_ _] nil)}
          handler (session/create-session-fs-adapter provider)]
      (is (not (contains? handler :sqlite-query)))
      (is (not (contains? handler :sqlite-exists)))))

  (testing "sqlite.query returning nil defaults to {:rows [] :columns [] :rows-affected 0}"
    (let [provider {:read-file (fn [_] "x")
                    :write-file (fn [_ _ _] nil)
                    :append-file (fn [_ _ _] nil)
                    :exists (fn [_] true)
                    :stat (fn [_] {:is-file true :is-directory false :size 1 :mtime "x" :birthtime "x"})
                    :mkdir (fn [_ _ _] nil)
                    :readdir (fn [_] [])
                    :readdir-with-types (fn [_] [])
                    :rm (fn [_ _ _] nil)
                    :rename (fn [_ _] nil)
                    :sqlite {:query (fn [_ _ _] nil)
                             :exists (fn [] false)}}
          handler (session/create-session-fs-adapter provider)]
      (is (= {:rows [] :columns [] :rows-affected 0}
             ((:sqlite-query handler) {:query-type :exec :query "CREATE TABLE t (x INT)"}))))))

(deftest test-create-session-fs-adapter-sqlite-transaction
  (testing "transaction executes statements atomically and returns ordered results"
    (let [received (atom nil)
          handler (session/create-session-fs-adapter
                   (test-session-fs-provider
                    {:query (fn [_ _ _] nil)
                     :exists (fn [] true)
                     :transaction
                     (fn [statements]
                       (reset! received statements)
                       [{:rows [{:user_id 7}]
                         :columns ["user_id"]
                         :rows-affected 0}
                        {:rows [] :columns [] :rows-affected 1}])}))
          statements [{:query-type :query
                       :query "SELECT user_id FROM users WHERE id = $user_id"
                       :params {:$user_id 7}}
                      {:query-type :run
                       :query "UPDATE users SET active = 1"}]]
      (is (fn? (:sqlite-transaction handler)))
      (is (= {:results [{:rows [{:user_id 7}]
                         :columns ["user_id"]
                         :rows-affected 0}
                        {:rows [] :columns [] :rows-affected 1}]}
             ((:sqlite-transaction handler) {:statements statements})))
      (is (= statements @received))))

  (testing "missing transaction support returns a classified fatal result"
    (let [handler (session/create-session-fs-adapter
                   (test-session-fs-provider
                    {:query (fn [_ _ _] nil)
                     :exists (fn [] true)}))]
      (is (= {:results []
              :error {:error-class "fatal"
                      :message "SQLite transactions are not supported by this provider"}}
             ((:sqlite-transaction handler) {:statements []})))))

  (testing "public helper constructs classified transaction failures"
    (let [ctor (ns-resolve 'github.copilot-sdk 'session-fs-sqlite-transaction-failure)
          pred (ns-resolve 'github.copilot-sdk 'session-fs-sqlite-transaction-failure?)]
      (is (some? ctor))
      (is (some? pred))
      (when (and ctor pred)
        (let [failure (ctor "database busy" :busy-or-locked)]
          (is (pred failure))
          (is (= :busy-or-locked (:error-class (ex-data failure)))))))))

(deftest test-session-fs-sqlite-transaction-rpc
  (testing "transaction RPC preserves bind names and result row keys"
    (let [received (atom nil)
          client-with-fs (assoc *test-client* :session-fs {:initial-cwd "/workspace"
                                                           :session-state-path "/state"
                                                           :conventions "posix"
                                                           :capabilities {:sqlite true}})
          copilot-session
          (sdk/create-session
           client-with-fs
           {:on-permission-request sdk/approve-all
            :create-session-fs-handler
            (fn [_]
              (test-session-fs-provider
               {:query (fn [_ _ _] nil)
                :exists (fn [] true)
                :transaction
                (fn [statements]
                  (reset! received statements)
                  [{:rows [{:user_id 7}]
                    :columns ["user_id"]
                    :rows-affected 0}])}))})
          response
          (mock/send-rpc-request!
           *mock-server*
           "sessionFs.sqliteTransaction"
           {:sessionId (sdk/session-id copilot-session)
            :statements [{:queryType "query"
                          :query "SELECT user_id FROM users WHERE id = $user_id"
                          :params {:$user_id 7}}]})]
      (is (= [{:query-type :query
               :query "SELECT user_id FROM users WHERE id = $user_id"
               :params {:$user_id 7}}]
             @received))
      (is (= {:results [{:rows [{:user_id 7}]
                         :columns ["user_id"]
                         :rowsAffected 0}]}
             (:result response)))
      (is (not (contains? (get-in response [:result :results 0 :rows 0]) :userId)))))

  (testing "classified provider failures are returned as result-level errors"
    (let [client-with-fs (assoc *test-client* :session-fs {:initial-cwd "/workspace"
                                                           :session-state-path "/state"
                                                           :conventions "posix"
                                                           :capabilities {:sqlite true}})
          copilot-session
          (sdk/create-session
           client-with-fs
           {:on-permission-request sdk/approve-all
            :create-session-fs-handler
            (fn [_]
              (test-session-fs-provider
               {:query (fn [_ _ _] nil)
                :exists (fn [] true)
                :transaction
                (fn [_]
                  (throw (ex-info "database busy"
                                  {:type :session-fs-sqlite-transaction-failure
                                   :error-class :busy-or-locked})))}))})
          response
          (mock/send-rpc-request!
           *mock-server*
           "sessionFs.sqliteTransaction"
           {:sessionId (sdk/session-id copilot-session)
            :statements []})]
      (is (= {:results []
              :error {:errorClass "busyOrLocked"
                      :message "database busy"}}
             (:result response))))))

(deftest test-session-fs-sqlite-rpc-dispatch
  (testing "sessionFs.sqliteQuery RPC dispatches to handler with :query-type coerced to keyword"
    (let [received (atom nil)
          client-with-fs (assoc *test-client* :session-fs {:initial-cwd "/workspace"
                                                           :session-state-path "/state"
                                                           :conventions "posix"
                                                           :capabilities {:sqlite true}})
          session (sdk/create-session client-with-fs
                                      {:on-permission-request sdk/approve-all
                                       :create-session-fs-handler
                                       (fn [_session]
                                         {:read-file (fn [_] "x")
                                          :write-file (fn [_ _ _] nil)
                                          :append-file (fn [_ _ _] nil)
                                          :exists (fn [_] true)
                                          :stat (fn [_] {:is-file true :is-directory false :size 1 :mtime "x" :birthtime "x"})
                                          :mkdir (fn [_ _ _] nil)
                                          :readdir (fn [_] [])
                                          :readdir-with-types (fn [_] [])
                                          :rm (fn [_ _ _] nil)
                                          :rename (fn [_ _] nil)
                                          :sqlite {:query (fn [qtype sql params]
                                                            (reset! received {:query-type qtype :query sql :params params})
                                                            {:rows [{:c 42}] :columns ["c"] :rows-affected 0})
                                                   :exists (fn [] true)}})})
          response (mock/send-rpc-request! *mock-server*
                                           "sessionFs.sqliteQuery"
                                           {:sessionId (sdk/session-id session)
                                            :query "SELECT c FROM t WHERE id = $userId"
                                            :queryType "query"
                                            :params {:$userId "abc"}})]
      (is (= :query (:query-type @received)))
      (is (= "SELECT c FROM t WHERE id = $userId" (:query @received)))
      ;; Opaque bind params preserved verbatim (no kebab-case mangling)
      (is (= {:$userId "abc"} (:params @received)))
      (is (= {:rows [{:c 42}] :columns ["c"] :rowsAffected 0}
             (:result response)))))

  (testing "sessionFs.sqliteQuery preserves snake_case column-name keys in result rows (review feedback)"
    ;; Without an outgoing escape hatch, `util/clj->wire` would convert
    ;; `:user_id` → `:userId`, producing rows whose keys no longer match
    ;; the `columns` array. Upstream Node forwards row maps verbatim.
    (let [client-with-fs (assoc *test-client* :session-fs {:initial-cwd "/workspace"
                                                           :session-state-path "/state"
                                                           :conventions "posix"
                                                           :capabilities {:sqlite true}})
          session (sdk/create-session client-with-fs
                                      {:on-permission-request sdk/approve-all
                                       :create-session-fs-handler
                                       (fn [_session]
                                         {:read-file (fn [_] "x")
                                          :write-file (fn [_ _ _] nil)
                                          :append-file (fn [_ _ _] nil)
                                          :exists (fn [_] true)
                                          :stat (fn [_] {:is-file true :is-directory false :size 1 :mtime "x" :birthtime "x"})
                                          :mkdir (fn [_ _ _] nil)
                                          :readdir (fn [_] [])
                                          :readdir-with-types (fn [_] [])
                                          :rm (fn [_ _ _] nil)
                                          :rename (fn [_ _] nil)
                                          :sqlite {:query (fn [_ _ _]
                                                            {:rows [{:user_id 1 :created_at "2026-01-01"}
                                                                    {:user_id 2 :created_at "2026-01-02"}]
                                                             :columns ["user_id" "created_at"]
                                                             :rows-affected 0})
                                                   :exists (fn [] true)}})})
          response (mock/send-rpc-request! *mock-server*
                                           "sessionFs.sqliteQuery"
                                           {:sessionId (sdk/session-id session)
                                            :query "SELECT user_id, created_at FROM users"
                                            :queryType "query"})
          result (:result response)]
      ;; Row keys must round-trip verbatim
      (is (= [{:user_id 1 :created_at "2026-01-01"}
              {:user_id 2 :created_at "2026-01-02"}]
             (:rows result)))
      ;; Columns array (strings) is unchanged
      (is (= ["user_id" "created_at"] (:columns result)))
      ;; Sibling SDK fields still get kebab→camelCase converted
      (is (= 0 (:rowsAffected result)))))

  (testing "sessionFs.sqliteExists RPC dispatches to handler"
    (let [client-with-fs (assoc *test-client* :session-fs {:initial-cwd "/workspace"
                                                           :session-state-path "/state"
                                                           :conventions "posix"
                                                           :capabilities {:sqlite true}})
          session (sdk/create-session client-with-fs
                                      {:on-permission-request sdk/approve-all
                                       :create-session-fs-handler
                                       (fn [_session]
                                         {:read-file (fn [_] "x")
                                          :write-file (fn [_ _ _] nil)
                                          :append-file (fn [_ _ _] nil)
                                          :exists (fn [_] true)
                                          :stat (fn [_] {:is-file true :is-directory false :size 1 :mtime "x" :birthtime "x"})
                                          :mkdir (fn [_ _ _] nil)
                                          :readdir (fn [_] [])
                                          :readdir-with-types (fn [_] [])
                                          :rm (fn [_ _ _] nil)
                                          :rename (fn [_ _] nil)
                                          :sqlite {:query (fn [_ _ _] {:rows [] :columns [] :rows-affected 0})
                                                   :exists (fn [] true)}})})
          response (mock/send-rpc-request! *mock-server*
                                           "sessionFs.sqliteExists"
                                           {:sessionId (sdk/session-id session)})]
      (is (= {:exists true} (:result response))))))

(deftest test-session-fs-capabilities-forwarded-on-wire
  (testing ":capabilities is forwarded on sessionFs.setProvider when configured (upstream PR #1299)"
    ;; Build a fresh server + client so we can intercept the setProvider call sent during connect.
    (let [server (mock/create-mock-server)
          _ (mock/start-mock-server! server)
          seen (atom {})
          _ (mock/set-request-hook! server (fn [method params]
                                             (when (= "sessionFs.setProvider" method)
                                               (swap! seen assoc method params))))
          client (sdk/client {:auto-start? false
                              :session-fs {:initial-cwd "/workspace"
                                           :session-state-path "/state"
                                           :conventions "posix"
                                           :capabilities {:sqlite true}}})
          [in out] (mock/client-streams server)]
      (try
        (client/connect-with-streams! client in out)
        (let [params (get @seen "sessionFs.setProvider")]
          (is (= {:sqlite true} (:capabilities params)))
          (is (= "/workspace" (:initialCwd params))))
        (finally
          (try (sdk/stop! client) (catch Exception _))
          (mock/stop-mock-server! server)))))

  (testing ":capabilities is omitted when not configured"
    (let [server (mock/create-mock-server)
          _ (mock/start-mock-server! server)
          seen (atom {})
          _ (mock/set-request-hook! server (fn [method params]
                                             (when (= "sessionFs.setProvider" method)
                                               (swap! seen assoc method params))))
          client (sdk/client {:auto-start? false
                              :session-fs {:initial-cwd "/workspace"
                                           :session-state-path "/state"
                                           :conventions "posix"}})
          [in out] (mock/client-streams server)]
      (try
        (client/connect-with-streams! client in out)
        (let [params (get @seen "sessionFs.setProvider")]
          (is (not (contains? params :capabilities))))
        (finally
          (try (sdk/stop! client) (catch Exception _))
          (mock/stop-mock-server! server))))))

(deftest test-session-fs-sqlite-capability-validation
  (testing "create-session throws when capabilities.sqlite is declared but provider lacks :sqlite"
    (let [client-with-fs (assoc *test-client* :session-fs {:initial-cwd "/workspace"
                                                           :session-state-path "/state"
                                                           :conventions "posix"
                                                           :capabilities {:sqlite true}})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"capabilities\.sqlite"
           (sdk/create-session client-with-fs
                               {:on-permission-request sdk/approve-all
                                :create-session-fs-handler
                                (fn [_session]
                                  {:read-file (fn [_] "x")
                                   :write-file (fn [_ _ _] nil)
                                   :append-file (fn [_ _ _] nil)
                                   :exists (fn [_] true)
                                   :stat (fn [_] {:is-file true :is-directory false :size 1 :mtime "x" :birthtime "x"})
                                   :mkdir (fn [_ _ _] nil)
                                   :readdir (fn [_] [])
                                   :readdir-with-types (fn [_] [])
                                   :rm (fn [_ _ _] nil)
                                   :rename (fn [_ _] nil)})})))))

  (testing "create-session throws when capabilities.sqlite is declared with only :sqlite-query (review feedback)"
    ;; Low-level handler shape: presence of :sqlite-query alone must NOT pass
    ;; validation, since sessionFs.sqliteExists would route to a missing key
    ;; and surface as an opaque \"Unknown sessionFs method\" error at runtime.
    (let [client-with-fs (assoc *test-client* :session-fs {:initial-cwd "/workspace"
                                                           :session-state-path "/state"
                                                           :conventions "posix"
                                                           :capabilities {:sqlite true}})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"capabilities\.sqlite"
           (sdk/create-session client-with-fs
                               {:on-permission-request sdk/approve-all
                                :create-session-fs-handler
                                (fn [_session]
                                  {:read-file (fn [_] "x")
                                   :write-file (fn [_ _ _] nil)
                                   :append-file (fn [_ _ _] nil)
                                   :exists (fn [_] true)
                                   :stat (fn [_] {:is-file true :is-directory false :size 1 :mtime "x" :birthtime "x"})
                                   :mkdir (fn [_ _ _] nil)
                                   :readdir (fn [_] [])
                                   :readdir-with-types (fn [_] [])
                                   :rm (fn [_ _ _] nil)
                                   :rename (fn [_ _] nil)
                                   ;; only :sqlite-query, missing :sqlite-exists
                                   :sqlite-query (fn [_] {:rows [] :columns [] :rows-affected 0})})}))))))
