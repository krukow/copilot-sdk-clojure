(ns github.copilot-sdk.integration.session-lifecycle-test
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

(deftest test-create-session
  (testing "Create new session"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :model "gpt-5.4"})]
      (is (some? session))
      (is (string? (sdk/session-id session)))
      ;; Session ID is now generated client-side as a UUID
      (is (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
                      (sdk/session-id session)))))
  (testing "Create session with custom session-id"
    (let [custom-id "my-custom-session-id"
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :session-id custom-id})]
      (is (= custom-id (sdk/session-id session)))))
  (testing "Create session with :on-event captures session.start"
    (let [events (atom [])
          got-start (promise)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :on-event (fn [evt]
                                                   (swap! events conj evt)
                                                   (when (= :copilot/session.start (:type evt))
                                                     (deliver got-start true)))})]
      (is (deref got-start 2000 false)
          "on-event handler should receive session.start event within timeout")
      (is (some #(= :copilot/session.start (:type %)) @events)))))

(deftest test-list-sessions
  (testing "List sessions includes created sessions"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          sessions (sdk/list-sessions *test-client*)]
      (is (seq sessions))
      (is (some #(= (sdk/session-id session) (:session-id %)) sessions)))))

(deftest test-list-sessions-with-context
  (testing "List sessions returns context when present"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          sid (sdk/session-id session)]
      (mock/set-session-context! *mock-server* sid
                                 {:cwd "/home/user/project"
                                  :gitRoot "/home/user/project"
                                  :repository "owner/repo"
                                  :branch "main"})
      (let [sessions (sdk/list-sessions *test-client*)
            found (first (filter #(= sid (:session-id %)) sessions))]
        (is (some? found))
        (is (= "/home/user/project" (get-in found [:context :cwd])))
        (is (= "owner/repo" (get-in found [:context :repository])))
        (is (= "main" (get-in found [:context :branch])))))))

(deftest test-list-sessions-with-filter
  (testing "List sessions filter narrows results"
    (let [s1 (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          s2 (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})]
      (mock/set-session-context! *mock-server* (sdk/session-id s1)
                                 {:cwd "/project-a" :repository "org/repo-a"})
      (mock/set-session-context! *mock-server* (sdk/session-id s2)
                                 {:cwd "/project-b" :repository "org/repo-b"})
      (let [filtered (sdk/list-sessions *test-client* {:repository "org/repo-a"})]
        (is (= 1 (count filtered)))
        (is (= (sdk/session-id s1) (:session-id (first filtered))))))))

(deftest test-get-session-metadata
  (testing "Get metadata for an existing session"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          sid (sdk/session-id session)
          metadata (sdk/get-session-metadata *test-client* sid)]
      (is (some? metadata))
      (is (= sid (:session-id metadata)))
      (is (instance? java.time.Instant (:start-time metadata)))
      (is (instance? java.time.Instant (:modified-time metadata)))
      (is (= false (:remote? metadata))))))

(deftest test-get-session-metadata-with-context
  (testing "Get session metadata includes context when present"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          sid (sdk/session-id session)]
      (mock/set-session-context! *mock-server* sid
                                 {:cwd "/home/user/project"
                                  :gitRoot "/home/user/project"
                                  :repository "owner/repo"
                                  :branch "main"})
      (let [metadata (sdk/get-session-metadata *test-client* sid)]
        (is (some? metadata))
        (is (= "/home/user/project" (get-in metadata [:context :cwd])))
        (is (= "owner/repo" (get-in metadata [:context :repository])))
        (is (= "main" (get-in metadata [:context :branch])))))))

(deftest test-get-session-metadata-not-found
  (testing "Get metadata for non-existent session returns nil"
    (is (nil? (sdk/get-session-metadata *test-client* "non-existent-session-id")))))

(deftest test-list-tools
  (testing "List tools returns tool info"
    (let [tools (sdk/list-tools *test-client*)]
      (is (seq tools))
      (is (every? #(and (:name %) (:description %)) tools))
      (is (some #(= "bash" (:name %)) tools))
      (is (some #(= "builtin/grep" (:namespaced-name %)) tools)))))

(deftest test-get-quota
  (testing "Get quota returns quota snapshots"
    (let [quotas (sdk/get-quota *test-client*)]
      (is (map? quotas))
      (is (contains? quotas "chat"))
      (let [chat (get quotas "chat")]
        (is (= 1000 (:entitlement-requests chat)))
        (is (= 42 (:used-requests chat)))
        (is (number? (:remaining-percentage chat)))
        (is (= false (:overage-allowed-with-exhausted-quota? chat)))))))

(deftest test-get-current-model
  (testing "Get current model for session"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all :model "gpt-5.4"})]
      (is (= "gpt-5.4" (sdk/get-current-model session))))))

(deftest test-switch-model
  (testing "Switch model for session"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all :model "gpt-5.4"})
          new-model (sdk/switch-model! session "claude-sonnet-4.5")]
      (is (= "claude-sonnet-4.5" new-model))
      (is (= "claude-sonnet-4.5" (sdk/get-current-model session))))))

(deftest test-log-returns-event-id
  (testing "log! returns a non-empty event-id string"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})]
      (doseq [[desc args] [["message only" ["Processing started"]]
                           ["with level + ephemeral options" ["Something went wrong" {:level "error" :ephemeral? true}]]]]
        (testing desc
          (let [event-id (apply sdk/log! session args)]
            (is (string? event-id))
            (is (seq event-id))))))))

(deftest test-log-verifies-rpc-params
  (testing "Log sends correct RPC params"
    (let [captured-params (atom nil)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= method "session.log")
                                        (reset! captured-params params))))
          session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          _ (sdk/log! session "test message" {:level "warning" :ephemeral? true})]
      (is (= (:message @captured-params) "test message"))
      (is (= (:level @captured-params) "warning"))
      (is (= (:ephemeral @captured-params) true))
      (is (string? (:sessionId @captured-params))))))

(deftest test-delete-session
  (testing "Delete session removes it from list"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)
          _ (sdk/delete-session! *test-client* session-id)
          sessions (sdk/list-sessions *test-client*)]
      (is (not (some #(= session-id (:session-id %)) sessions))))))

(deftest test-destroy-session
  (testing "Deprecated destroy alias detaches without deleting resumable state"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)]
      (sdk/destroy! session)
      (let [sessions (sdk/list-sessions *test-client*)]
        (is (some #(= session-id (:session-id %)) sessions))))))
