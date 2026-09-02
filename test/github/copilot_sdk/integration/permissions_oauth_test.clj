(ns github.copilot-sdk.integration.permissions-oauth-test
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

(deftest test-request-permission-always-true-on-wire
  (testing "requestPermission is always true on create with handler"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create" "session.resume"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all :model "gpt-5.4"})
          create-params (get @seen "session.create")]
      (is (true? (:requestPermission create-params))
          "requestPermission must be true when handler is configured")))

  (testing "requestPermission is true on create with explicit handler"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:model "gpt-5.4"
                                 :on-permission-request sdk/approve-all})
          create-params (get @seen "session.create")]
      (is (true? (:requestPermission create-params)))))

  (testing "requestPermission is true on resume with handler"
    (let [seen (atom {})
          session-id (sdk/session-id (sdk/create-session *test-client* {:on-permission-request sdk/approve-all}))
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.resume"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id {:on-permission-request sdk/approve-all})
          resume-params (get @seen "session.resume")]
      (is (true? (:requestPermission resume-params))
          "requestPermission must be true on resume with handler"))))

(deftest test-approve-all-returns-approve-once
  (testing "approve-all returns {:kind :approve-once}"
    (let [result (sdk/approve-all {:permission-kind :shell
                                   :tool-call-id "tc-1"}
                                  {:session-id "session-1"})]
      (is (= {:kind :approve-once} result))))

  (testing "approve-all works for any permission kind"
    (doseq [kind [:shell :write :mcp :read :url :custom-tool]]
      (is (= {:kind :approve-once}
             (sdk/approve-all {:permission-kind kind} {:session-id "s1"}))))))

(deftest test-approve-all-managed-settings
  (testing "managed settings disable unconditional approve-all"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"approve-all cannot be used when managed settings are enabled"
         (sdk/approve-all {:permission-kind :shell}
                          {:session-id "s1" :managed-settings-enabled? true}))))

  (testing "managed approval requests are left for an explicit user decision"
    (is (= {:kind :no-result}
           (sdk/approve-all {:permission-kind :shell
                             :managed-approval-required true}
                            {:session-id "s1" :managed-settings-enabled? false})))
    (is (= {:kind :approve-once}
           (sdk/approve-all {:permission-kind :shell
                             :managed-approval-required false}
                            {:session-id "s1" :managed-settings-enabled? false}))))

  (testing "permission handlers receive managed-settings state"
    (doseq [config [{:enable-managed-settings? true}
                    {:managed-settings {:permissions {:deny ["Shell(rm *)"]}}}]]
      (let [invocation (promise)
            copilot-session
            (sdk/create-session
             *test-client*
             (assoc config
                    :on-permission-request
                    (fn [_ ctx]
                      (deliver invocation ctx)
                      {:kind :no-result})))
            response (<!! (session/handle-permission-request!
                           *test-client*
                           (sdk/session-id copilot-session)
                           {:permission-kind :shell}))]
        (is (= {:result :no-result} response))
        (is (true? (:managed-settings-enabled? (deref invocation 1000 nil))))))))

(deftest test-managed-bypass-permission-policies
  (testing "simple keyword policies preserve their exact wire values"
    (doseq [[policy expected] [[:disable "disable"]
                               [:allow-auto-only "allow-auto-only"]
                               [:future-fail-closed-policy
                                "future-fail-closed-policy"]]]
      (let [seen (atom nil)
            _ (mock/set-request-hook! *mock-server*
                                      (fn [method params]
                                        (when (= "session.create" method)
                                          (reset! seen params))))
            _ (sdk/create-session
               *test-client*
               {:on-permission-request sdk/approve-all
                :managed-settings
                {:permissions
                 {:disable-bypass-permissions-mode policy}}})]
        (is (= expected
               (get-in @seen
                       [:managedSettings
                        :permissions
                        :disableBypassPermissionsMode])))))
    (doseq [policy ["disable" :managed/disable (keyword "")]]
      (is (not (s/valid? ::specs/disable-bypass-permissions-mode policy))))))

(deftest test-factory-permission-request-spec
  (let [request {:permission-kind :factory
                 :operation "run"
                 :name "review"
                 :description "Review changed files"
                 :phases [{:title "Review"}]
                 :approval-key "factory:review"
                 :can-persist-approval true
                 :max-concurrent-subagents 0
                 :max-total-subagents 5
                 :timeout-seconds 30.5
                 :max-ai-credits 2}]
    (is (s/valid? ::specs/permission-request request))
    (is (not (s/valid? ::specs/permission-request (dissoc request :name))))
    (is (not (s/valid? ::specs/permission-request (dissoc request :description))))
    (is (not (s/valid? ::specs/permission-request
                       (assoc request :max-total-subagents -1))))))

(deftest test-permission-handler-now-optional
  ;; Upstream PR #1308 made :on-permission-request optional. Previously this
  ;; test asserted that create-session/resume-session threw without it.
  (testing "create-session succeeds without :on-permission-request"
    (is (some? (sdk/create-session *test-client* {}))))

  (testing "resume-session succeeds without :on-permission-request"
    (let [_ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/get-last-session-id *test-client*)]
      (is (some? (sdk/resume-session *test-client* session-id {}))))))

(deftest test-permission-no-result-v3
  ;; Pins the contract the manual_tool_resume example relies on: a deferring
  ;; :on-permission-request handler that returns {:kind :no-result} leaves the
  ;; permission pending (no handlePendingPermissionRequest RPC), so the app can
  ;; resolve it by hand later. (That a handler is present at all also forces
  ;; requestPermission:true on create — pinned by the optional-wire-contract
  ;; matrix's :on-permission-request row.)
  (testing "v3 no-result skips handlePendingPermissionRequest RPC"
    (let [requests (atom [])
          processing-completed (promise)
          real-handle-v3-permission-requested
          (var-get (var client/handle-v3-permission-requested!))
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request
                                       (fn [_request _ctx]
                                         {:kind :no-result})})
          session-id (sdk/session-id session)]
      (with-redefs-fn
        {(var client/handle-v3-permission-requested!)
         (fn [client session-id event]
           (let [result-ch (real-handle-v3-permission-requested client session-id event)]
             (async/take! result-ch
                          (fn [_]
                            (deliver processing-completed true)))
             result-ch))}
        (fn []
           ;; Force protocol v3 so the broadcast path is active
          (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
           ;; Reset captured requests after session creation
          (reset! requests [])
           ;; Inject a v3 permission.requested broadcast event
          (mock/send-v3-broadcast-event! *mock-server* session-id
                                         "permission.requested"
                                         {:requestId "perm-req-1"
                                          :permissionRequest {:permissionKind "shell"
                                                              :fullCommandText "echo test"}})
           ;; The handler returned no-result — no handlePendingPermissionRequest RPC
          (is (and (true? (await-value! processing-completed
                                        "v3 permission processing"
                                        1000))
                   (empty? (filter #(= "session.permissions.handlePendingPermissionRequest"
                                       (:method %))
                                   @requests)))
              "no-result should complete processing without sending the pending-permission RPC"))))))

(deftest test-permission-approved-v3
  (testing "v3 approve-once handler sends handlePendingPermissionRequest RPC"
    (let [requests (atom [])
          rpc-latch (java.util.concurrent.CountDownLatch. 1)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})
                                      (when (= "session.permissions.handlePendingPermissionRequest" method)
                                        (.countDown rpc-latch))))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)]
      ;; Force protocol v3
      (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
      ;; Reset captured requests after session creation
      (reset! requests [])
      ;; Inject a v3 permission.requested broadcast event
      (mock/send-v3-broadcast-event! *mock-server* session-id
                                     "permission.requested"
                                     {:requestId "perm-req-2"
                                      :permissionRequest {:permissionKind "shell"
                                                          :fullCommandText "echo test"}})
      ;; Wait for the RPC to arrive (up to 5 seconds)
      (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS)
      ;; The handler approved — should send handlePendingPermissionRequest RPC
      (let [perm-rpcs (filter #(= "session.permissions.handlePendingPermissionRequest"
                                  (:method %))
                              @requests)]
        (is (= 1 (count perm-rpcs))
            "approved result should send handlePendingPermissionRequest RPC")
        (when (seq perm-rpcs)
          (is (= "perm-req-2" (:requestId (:params (first perm-rpcs))))
              "RPC should include the correct request-id"))))))

(deftest test-legacy-permission-denials-normalize-to-v3-reject
  (testing "legacy denial aliases send upstream reject decisions with feedback"
    (doseq [[legacy-kind expected-feedback]
            [[:denied-by-rules "Denied by rules"]
             [:denied-interactively-by-user "custom feedback"]
             [:denied-by-content-exclusion-policy "Denied by content exclusion policy"]
             [:denied-by-permission-request-hook "Denied by permission request hook"]]]
      (let [requests (atom [])
            rpc-latch (java.util.concurrent.CountDownLatch. 1)
            _ (mock/set-request-hook! *mock-server*
                                      (fn [method params]
                                        (swap! requests conj {:method method :params params})
                                        (when (= "session.permissions.handlePendingPermissionRequest" method)
                                          (.countDown rpc-latch))))
            session (sdk/create-session *test-client*
                                        {:on-permission-request
                                         (fn [_request _ctx]
                                           (cond-> {:kind legacy-kind}
                                             (= legacy-kind :denied-interactively-by-user)
                                             (assoc :feedback "custom feedback")

                                             true
                                             (assoc :rules [{:kind "shell"}]
                                                    :internal true)))})
            session-id (sdk/session-id session)]
        (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
        (reset! requests [])
        (mock/send-v3-broadcast-event! *mock-server* session-id
                                       "permission.requested"
                                       {:requestId (str "perm-" (name legacy-kind))
                                        :permissionRequest {:permissionKind "shell"
                                                            :fullCommandText "echo test"}})
        (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS)
            (str "timed out waiting for legacy denial " legacy-kind))
        (let [decision (->> @requests
                            (filter #(= "session.permissions.handlePendingPermissionRequest"
                                        (:method %)))
                            first
                            :params
                            :result)]
          (is (= "reject" (:kind decision)))
          (is (= expected-feedback (:feedback decision)))
          (is (not (contains? decision :message)))
          (is (not (contains? decision :rules)))
          (is (not (contains? decision :internal))))))))

(deftest test-permission-decisions-drop-legacy-and-extra-fields
  (testing "normalized permission decisions only forward upstream decision fields"
    (let [requests (atom [])
          rpc-latch (java.util.concurrent.CountDownLatch. 1)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})
                                      (when (= "session.permissions.handlePendingPermissionRequest" method)
                                        (.countDown rpc-latch))))
          session (sdk/create-session *test-client*
                                      {:on-permission-request
                                       (fn [_request _ctx]
                                         {:kind :approved
                                          :message "legacy message"
                                          :feedback "extra feedback"
                                          :rules [{:kind "shell"}]
                                          :internal true})})
          session-id (sdk/session-id session)]
      (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
      (reset! requests [])
      (mock/send-v3-broadcast-event! *mock-server* session-id
                                     "permission.requested"
                                     {:requestId "perm-legacy-approved"
                                      :permissionRequest {:permissionKind "shell"
                                                          :fullCommandText "echo test"}})
      (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS))
      (let [decision (->> @requests
                          (filter #(= "session.permissions.handlePendingPermissionRequest"
                                      (:method %)))
                          first
                          :params
                          :result)]
        (is (= {:kind "approve-once"} decision))))))

(deftest test-permission-resolved-by-hook-v3
  (testing "v3 permission.requested with resolvedByHook=true skips handler entirely"
    (let [handler-called? (atom false)
          requests (atom [])
          ;; Use a latch to wait for the event to be delivered to the session
          event-latch (java.util.concurrent.CountDownLatch. 1)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request
                                       (fn [_request _ctx]
                                         (reset! handler-called? true)
                                         {:kind :approve-once})
                                       :on-event
                                       (fn [event]
                                         (when (= :copilot/permission.requested (:type event))
                                           (.countDown event-latch)))})
          session-id (sdk/session-id session)]
      ;; Force protocol v3
      (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
      (reset! requests [])
      ;; Inject permission.requested with resolvedByHook=true
      (mock/send-v3-broadcast-event! *mock-server* session-id
                                     "permission.requested"
                                     {:requestId "perm-hook-1"
                                      :permissionRequest {:permissionKind "shell"
                                                          :fullCommandText "echo test"}
                                      :resolvedByHook true})
      ;; Wait for the event to be delivered (proves routing completed)
      (is (.await event-latch 5 java.util.concurrent.TimeUnit/SECONDS)
          "timed out waiting for permission.requested event delivery")
      ;; Handler should NOT be called
      (is (false? @handler-called?)
          "permission handler should not be invoked when resolvedByHook is true")
      ;; No RPC should be sent
      (is (empty? (filter #(= "session.permissions.handlePendingPermissionRequest"
                              (:method %))
                          @requests))
          "no handlePendingPermissionRequest RPC when resolvedByHook is true")))

  (testing "v3 permission.requested with resolvedByHook=false invokes handler normally"
    (let [handler-called? (atom false)
          rpc-latch (java.util.concurrent.CountDownLatch. 1)
          requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})
                                      (when (= "session.permissions.handlePendingPermissionRequest" method)
                                        (.countDown rpc-latch))))
          session (sdk/create-session *test-client*
                                      {:on-permission-request
                                       (fn [_request _ctx]
                                         (reset! handler-called? true)
                                         {:kind :approve-once})})
          session-id (sdk/session-id session)]
      (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
      (reset! requests [])
      ;; Inject permission.requested with resolvedByHook=false
      (mock/send-v3-broadcast-event! *mock-server* session-id
                                     "permission.requested"
                                     {:requestId "perm-hook-2"
                                      :permissionRequest {:permissionKind "shell"
                                                          :fullCommandText "echo test"}
                                      :resolvedByHook false})
      (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS)
          "timed out waiting for handlePendingPermissionRequest RPC")
      (is (true? @handler-called?)
          "permission handler should be invoked when resolvedByHook is false")
      (is (= 1 (count (filter #(= "session.permissions.handlePendingPermissionRequest"
                                  (:method %))
                              @requests)))
          "handlePendingPermissionRequest RPC should be sent"))))

(deftest test-mcp-oauth-register-interest-on-create
  (testing "create-session with :on-mcp-auth-request registers interest in mcp.oauth_required"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-mcp-auth-request
                                       (fn [_request _ctx] {:kind :cancelled})})
          register-rpcs (filter #(= "session.eventLog.registerInterest" (:method %))
                                @requests)]
      (is (sdk/session-id session))
      (is (= 1 (count register-rpcs))
          "exactly one registerInterest RPC should be sent on create")
      (is (= "mcp.oauth_required" (:eventType (:params (first register-rpcs))))
          "registerInterest should target the mcp.oauth_required event type"))))

(deftest test-mcp-oauth-no-register-interest-without-handler
  (testing "create-session without :on-mcp-auth-request does not register interest"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          _session (sdk/create-session *test-client* {})]
      (is (empty? (filter #(= "session.eventLog.registerInterest" (:method %)) @requests))
          "no registerInterest RPC when no MCP auth handler is configured"))))

(deftest test-mcp-oauth-token-result-v3
  (testing "v3 mcp.oauth_required handler returning a token responds via handlePendingRequest"
    (let [received-request (atom nil)
          requests (atom [])
          rpc-latch (java.util.concurrent.CountDownLatch. 1)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})
                                      (when (= "session.mcp.oauth.handlePendingRequest" method)
                                        (.countDown rpc-latch))))
          session (sdk/create-session *test-client*
                                      {:on-mcp-auth-request
                                       (fn [request _ctx]
                                         (reset! received-request request)
                                         {:kind :token
                                          :access-token "tok-abc"
                                          :token-type "Bearer"
                                          :expires-in 3600})})
          session-id (sdk/session-id session)]
      (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
      (reset! requests [])
      (mock/send-v3-broadcast-event! *mock-server* session-id
                                     "mcp.oauth_required"
                                     {:requestId "mcp-req-1"
                                      :serverName "my-mcp"
                                      :serverUrl "https://mcp.example/sse"
                                      :reason "initial"})
      (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS)
          "timed out waiting for handlePendingRequest RPC")
      ;; Handler received the idiomatic (kebab-cased) request data
      (is (= "mcp-req-1" (:request-id @received-request)))
      (is (= "my-mcp" (:server-name @received-request)))
      (is (= "initial" (:reason @received-request)))
      (let [rpcs (filter #(= "session.mcp.oauth.handlePendingRequest" (:method %)) @requests)]
        (is (= 1 (count rpcs)) "exactly one handlePendingRequest RPC should be sent")
        (let [params (:params (first rpcs))]
          (is (= "mcp-req-1" (:requestId params)) "RPC carries the request-id")
          ;; Result is wire-shaped: string kind, camelCased token fields
          (is (= "token" (get-in params [:result :kind])))
          (is (= "tok-abc" (get-in params [:result :accessToken])))
          (is (= "Bearer" (get-in params [:result :tokenType])))
          (is (= 3600 (get-in params [:result :expiresIn]))))))))

(deftest test-mcp-oauth-cancel-on-nil-v3
  (testing "v3 mcp.oauth_required handler returning nil cancels the request"
    (let [requests (atom [])
          rpc-latch (java.util.concurrent.CountDownLatch. 1)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})
                                      (when (= "session.mcp.oauth.handlePendingRequest" method)
                                        (.countDown rpc-latch))))
          session (sdk/create-session *test-client*
                                      {:on-mcp-auth-request (fn [_request _ctx] nil)})
          session-id (sdk/session-id session)]
      (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
      (reset! requests [])
      (mock/send-v3-broadcast-event! *mock-server* session-id
                                     "mcp.oauth_required"
                                     {:requestId "mcp-req-2"
                                      :serverName "my-mcp"
                                      :serverUrl "https://mcp.example/sse"
                                      :reason "reauth"})
      (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS)
          "timed out waiting for handlePendingRequest RPC")
      (let [rpcs (filter #(= "session.mcp.oauth.handlePendingRequest" (:method %)) @requests)]
        (is (= 1 (count rpcs)))
        (is (= "cancelled" (get-in (:params (first rpcs)) [:result :kind]))
            "nil handler result should cancel the pending request")))))

(deftest test-mcp-oauth-no-handler-leaves-pending-v3
  (testing "v3 mcp.oauth_required without a handler sends no handlePendingRequest RPC"
    (let [requests (atom [])
          event-latch (java.util.concurrent.CountDownLatch. 1)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-event
                                       (fn [event]
                                         (when (= :copilot/mcp.oauth_required (:type event))
                                           (.countDown event-latch)))})
          session-id (sdk/session-id session)]
      (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
      (reset! requests [])
      (mock/send-v3-broadcast-event! *mock-server* session-id
                                     "mcp.oauth_required"
                                     {:requestId "mcp-req-3"
                                      :serverName "my-mcp"
                                      :serverUrl "https://mcp.example/sse"
                                      :reason "initial"})
      (is (.await event-latch 5 java.util.concurrent.TimeUnit/SECONDS)
          "timed out waiting for mcp.oauth_required event delivery")
      (is (empty? (filter #(= "session.mcp.oauth.handlePendingRequest" (:method %)) @requests))
          "no handlePendingRequest RPC when no MCP auth handler is registered"))))

(deftest test-mcp-oauth-register-interest-before-resume
  (testing "resume-session registers interest BEFORE session.resume (upstream client.ts:1578)"
    ;; Upstream registers `mcp.oauth_required` interest *before* the
    ;; `session.resume` RPC so OAuth the runtime needs while processing resume
    ;; (e.g. MCP servers reconnecting) reaches the handler instead of silently
    ;; falling back to a cached token.
    (let [handler (fn [_request _ctx] {:kind :cancelled})
          _ (sdk/create-session *test-client* {:on-mcp-auth-request handler})
          session-id (sdk/get-last-session-id *test-client*)
          order (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method _params]
                                      (when (#{"session.eventLog.registerInterest"
                                               "session.resume"} method)
                                        (swap! order conj method))))
          _ (sdk/resume-session *test-client* session-id {:on-mcp-auth-request handler})
          methods @order
          reg-idx (.indexOf methods "session.eventLog.registerInterest")
          resume-idx (.indexOf methods "session.resume")]
      (is (nat-int? reg-idx) "registerInterest RPC should be sent on resume")
      (is (nat-int? resume-idx) "session.resume RPC should be sent")
      (is (< reg-idx resume-idx)
          "registerInterest must precede session.resume"))))

(deftest test-mcp-oauth-register-interest-before-resume-async
  (testing "<resume-session registers interest BEFORE session.resume"
    (let [handler (fn [_request _ctx] {:kind :cancelled})
          _ (sdk/create-session *test-client* {:on-mcp-auth-request handler})
          session-id (sdk/get-last-session-id *test-client*)
          order (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method _params]
                                      (when (#{"session.eventLog.registerInterest"
                                               "session.resume"} method)
                                        (swap! order conj method))))
          _ (async/<!! (sdk/<resume-session *test-client* session-id
                                            {:on-mcp-auth-request handler}))
          methods @order
          reg-idx (.indexOf methods "session.eventLog.registerInterest")
          resume-idx (.indexOf methods "session.resume")]
      (is (nat-int? reg-idx) "registerInterest RPC should be sent on async resume")
      (is (nat-int? resume-idx) "session.resume RPC should be sent")
      (is (< reg-idx resume-idx)
          "registerInterest must precede session.resume on the async path"))))

(deftest test-mcp-oauth-register-interest-failure-rejects-create
  (testing "a failed registerInterest RPC fails create-session (upstream awaits + rejects)"
    ;; Upstream `await`s registerInterest and lets a transport failure reject
    ;; session creation rather than silently degrading to cached-token fallback.
    (let [_ (mock/set-request-hook! *mock-server*
                                    (fn [method _params]
                                      (when (= "session.eventLog.registerInterest" method)
                                        (throw (ex-info "registerInterest boom"
                                                        {:code -32603})))))]
      (is (thrown? Exception
                   (sdk/create-session *test-client*
                                       {:on-mcp-auth-request (fn [_ _] {:kind :cancelled})}))
          "create-session should propagate a registerInterest RPC failure"))))

(deftest test-mcp-oauth-bare-token-result-v3
  (testing "v3 handler returning a map with :access-token but no :kind still maps to token; explicit nil optional fields are omitted"
    (let [requests (atom [])
          rpc-latch (java.util.concurrent.CountDownLatch. 1)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})
                                      (when (= "session.mcp.oauth.handlePendingRequest" method)
                                        (.countDown rpc-latch))))
          session (sdk/create-session *test-client*
                                      {:on-mcp-auth-request
                                       (fn [_request _ctx] {:access-token "bare-tok"
                                                            :token-type nil
                                                            :expires-in nil})})
          session-id (sdk/session-id session)]
      (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
      (reset! requests [])
      (mock/send-v3-broadcast-event! *mock-server* session-id
                                     "mcp.oauth_required"
                                     {:requestId "mcp-req-bare"
                                      :serverName "my-mcp"
                                      :serverUrl "https://mcp.example/sse"
                                      :reason "initial"})
      (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS)
          "timed out waiting for handlePendingRequest RPC")
      (let [params (:params (first (filter #(= "session.mcp.oauth.handlePendingRequest" (:method %))
                                           @requests)))]
        (is (= "token" (get-in params [:result :kind])))
        (is (= "bare-tok" (get-in params [:result :accessToken])))
        (is (not (contains? (:result params) :tokenType))
            "nil :token-type must not serialize to tokenType: null")
        (is (not (contains? (:result params) :expiresIn))
            "nil :expires-in must not serialize to expiresIn: null")))))

(deftest test-mcp-oauth-thrown-and-non-map-results-cancel-v3
  (testing "v3 handler that throws, returns a non-map, or a nil access token cancels the request"
    (doseq [[label handler] [["thrown" (fn [_ _] (throw (ex-info "nope" {})))]
                             ["non-map" (fn [_ _] "not-a-result")]
                             ["nil-token" (fn [_ _] {:access-token nil})]]]
      (let [requests (atom [])
            rpc-latch (java.util.concurrent.CountDownLatch. 1)
            _ (mock/set-request-hook! *mock-server*
                                      (fn [method params]
                                        (swap! requests conj {:method method :params params})
                                        (when (= "session.mcp.oauth.handlePendingRequest" method)
                                          (.countDown rpc-latch))))
            session (sdk/create-session *test-client* {:on-mcp-auth-request handler})
            session-id (sdk/session-id session)]
        (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
        (reset! requests [])
        (mock/send-v3-broadcast-event! *mock-server* session-id
                                       "mcp.oauth_required"
                                       {:requestId (str "mcp-req-" label)
                                        :serverName "my-mcp"
                                        :serverUrl "https://mcp.example/sse"
                                        :reason "initial"})
        (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS)
            (str label ": timed out waiting for handlePendingRequest RPC"))
        (let [params (:params (first (filter #(= "session.mcp.oauth.handlePendingRequest" (:method %))
                                             @requests)))]
          (is (= "cancelled" (get-in params [:result :kind]))
              (str label " handler result should cancel the pending request")))))))

(deftest test-permission-result-kinds-spec
  (testing "upstream v0.3.0 permission decision kinds are valid"
    (doseq [kind [:approve-once
                  :approve-for-session
                  :approve-for-location
                  :reject
                  :user-not-available
                  :no-result]]
      (is (s/valid? :github.copilot-sdk.specs/permission-result-kind kind)
          (str kind " should be a valid permission result kind"))))
  (testing "legacy Clojure permission result kinds remain accepted for compatibility"
    (doseq [kind [:approved
                  :denied-by-rules
                  :denied-no-approval-rule-and-could-not-request-from-user
                  :denied-interactively-by-user
                  :denied-by-content-exclusion-policy
                  :denied-by-permission-request-hook]]
      (is (s/valid? :github.copilot-sdk.specs/permission-result-kind kind)
          (str kind " should remain accepted")))))

(deftest test-permission-result-spec-uses-kind-key
  (testing "permission result maps use the public :kind key"
    (doseq [result [{:kind :approve-once}
                    {:kind :approve-for-session
                     :approval {:kind :commands
                                :command-identifiers ["echo"]}}
                    {:kind :approve-for-location
                     :approval {:kind :write}
                     :location-key "/workspace"}
                    {:kind :reject
                     :feedback "Not allowed"}
                    {:kind :user-not-available}
                    {:kind :no-result}]]
      (is (s/valid? :github.copilot-sdk.specs/permission-result result)
          (str result " should be a valid permission result"))))
  (testing "internal spec key is not part of the public permission result shape"
    (is (not (s/valid? :github.copilot-sdk.specs/permission-result
                       {:permission-result-kind :approve-once})))))

(deftest test-tool-execution-start-mcp-fields
  (testing "tool.execution_start event data allows MCP fields"
    (is (s/valid? :github.copilot-sdk.specs/tool.execution_start-data
                  {:tool-call-id "tc-1"
                   :tool-name "mcp__server__tool"
                   :mcp-server-name "my-mcp-server"
                   :mcp-tool-name "original-tool"})
        "tool.execution_start-data should accept mcp-server-name and mcp-tool-name"))
  (testing "tool.execution_start event data allows the :model field (upstream 1.0.57)"
    (is (s/valid? :github.copilot-sdk.specs/tool.execution_start-data
                  {:tool-call-id "tc-1"
                   :tool-name "shell"
                   :model "claude-sonnet-4.6"})
        "tool.execution_start-data should accept the model identifier")))

(deftest test-extensions-attachments-pushed-event-generated
  (testing "session.extensions.attachments_pushed is a known generated event type (upstream schema 1.0.57)"
    (is (contains? github.copilot-sdk.generated.event-specs/event-types "session.extensions.attachments_pushed"))
    (is (some? (s/get-spec :github.copilot-sdk.generated.event-specs/session.extensions.attachments_pushed))
        "generated envelope spec should be registered"))
  (testing "the generated event envelope validates a well-formed ephemeral payload"
    (is (s/valid? :github.copilot-sdk.generated.event-specs/session.extensions.attachments_pushed
                  {:type "session.extensions.attachments_pushed"
                   :id "evt-1"
                   :parent-id nil
                   :timestamp "2026-06-02T00:00:00Z"
                   :ephemeral true
                   :data {:attachments [{:type "extension_context"
                                         :title "pill"
                                         :extension-id "ext"
                                         :captured-at "2026-06-02T00:00:00Z"}]}}))))

(deftest test-extension-context-attachment-payload-preserved
  (testing "extension_context attachment :payload survives normalize-incoming on user.message"
    (let [normalize @#'protocol/normalize-incoming
          raw-msg {:jsonrpc "2.0"
                   :method "session.event"
                   :params {:sessionId "abc"
                            :event {:type "user.message"
                                    :data {:content "hi"
                                           :attachments [{:type "file"
                                                          :displayName "a.txt"
                                                          :path "/tmp/a.txt"}
                                                         {:type "extension_context"
                                                          :title "My Pill"
                                                          :extensionId "my-ext"
                                                          :payload {:firstName "Foo"
                                                                    :nested {:userId 42}}}]}}}}
          normalized (normalize raw-msg)
          atts (get-in normalized [:params :event :data :attachments])
          ext (nth atts 1)]
      (is (= "extension_context" (:type ext)))
      (is (= "Foo" (get-in ext [:payload :firstName]))
          "payload top-level keys must not be kebab-cased")
      (is (= 42 (get-in ext [:payload :nested :userId]))
          "payload nested keys must not be kebab-cased")
      (is (= "My Pill" (:title ext))
          "non-opaque attachment fields are still converted/preserved")))
  (testing "payload is preserved in historical events from session.getMessages responses"
    (let [normalize @#'protocol/normalize-incoming
          raw-response {:jsonrpc "2.0"
                        :id 7
                        :result {:events [{:type "user.message"
                                           :data {:content "hi"
                                                  :attachments [{:type "extension_context"
                                                                 :title "P"
                                                                 :extensionId "e"
                                                                 :payload {:nestedKey {:userId 7}}}]}}]}}
          normalized (normalize raw-response)
          ext (get-in normalized [:result :events 0 :data :attachments 0])]
      (is (= 7 (get-in ext [:payload :nestedKey :userId]))
          "historical payload keys must not be kebab-cased")))
  (testing "extension_context payload survives on session.extensions.attachments_pushed events"
    (let [normalize @#'protocol/normalize-incoming
          raw-msg {:jsonrpc "2.0"
                   :method "session.event"
                   :params {:sessionId "abc"
                            :event {:type "session.extensions.attachments_pushed"
                                    :data {:attachments [{:type "extension_context"
                                                          :title "P"
                                                          :extensionId "e"
                                                          :payload {:camelKey 1}}]}}}}
          normalized (normalize raw-msg)
          ext (get-in normalized [:params :event :data :attachments 0])]
      (is (= 1 (get-in ext [:payload :camelKey]))
          "payload keys must not be kebab-cased on attachments_pushed events"))))

(deftest test-upstream-event-data-field-specs
  (testing "assistant reasoning and message fields from generated events are explicitly spec'd"
    (doseq [spec-key [:github.copilot-sdk.specs/reasoning-id
                      :github.copilot-sdk.specs/encrypted-content
                      :github.copilot-sdk.specs/output-tokens
                      :github.copilot-sdk.specs/phase
                      :github.copilot-sdk.specs/reasoning-opaque
                      :github.copilot-sdk.specs/reasoning-text
                      :github.copilot-sdk.specs/request-id]]
      (is (some? (s/get-spec spec-key)) (str spec-key " should exist")))
    (is (s/valid? :github.copilot-sdk.specs/assistant.reasoning-data
                  {:reasoning-id "r1" :content "thinking"}))
    (is (s/valid? :github.copilot-sdk.specs/assistant.message-data
                  {:message-id "m1"
                   :content "answer"
                   :encrypted-content "ciphertext"
                   :output-tokens 42
                   :phase "response"
                   :reasoning-opaque "opaque"
                   :reasoning-text "visible reasoning"
                   :request-id "req-1"}))
    (is (false? (s/valid? :github.copilot-sdk.specs/assistant.reasoning-data
                          {:reasoning-id "r1" :content {:unexpected true}})))
    (is (false? (s/valid? :github.copilot-sdk.specs/assistant.message-data
                          {:message-id "m1" :content {:unexpected true}})))
    (is (false? (s/valid? :github.copilot-sdk.specs/user.message-data
                          {:content {:unexpected true}})))
    (is (s/valid? :github.copilot-sdk.specs/elicitation-result
                  {:action "accept" :content {:name "test-value"}})))

  (testing "status and loaded event data fields from generated events are explicitly spec'd"
    (doseq [spec-key [:github.copilot-sdk.specs/mcp-server-status
                      :github.copilot-sdk.specs/mcp-loaded-server
                      :github.copilot-sdk.specs/session.mcp_servers_loaded-data
                      :github.copilot-sdk.specs/session.mcp_server_status_changed-data
                      :github.copilot-sdk.specs/session.skills_loaded-data
                      :github.copilot-sdk.specs/session.extensions_loaded-data]]
      (is (some? (s/get-spec spec-key)) (str spec-key " should exist")))
    (is (s/valid? :github.copilot-sdk.specs/session.mcp_server_status_changed-data
                  {:server-name "github" :status "needs-auth"}))
    (is (s/valid? :github.copilot-sdk.specs/session.skills_loaded-data
                  {:skills [{:name "skill-a"
                             :description "A skill"
                             :enabled true
                             :source "project"
                             :user-invocable false}]}))
    (is (s/valid? :github.copilot-sdk.specs/session.extensions_loaded-data
                  {:extensions [{:id "project:ext-a"
                                 :name "ext-a"
                                 :source "project"
                                 :status "running"}
                                {:id "user:ext-b"
                                 :name "ext-b"
                                 :source "user"
                                 :status "starting"}]}))
    (is (not (s/valid? :github.copilot-sdk.specs/session.extensions_loaded-data
                       {:extensions [{:id "project:ext-a"
                                      :name "ext-a"
                                      :source "project"
                                      :status "enabled"}]})))))
