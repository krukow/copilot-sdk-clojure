(ns github.copilot-sdk.integration.telemetry-callbacks-test
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

(defn- with-telemetry-client*
  "Create a mock server + client (optionally with :on-github-telemetry),
   connect them over in-memory streams, run (f server client), then tear
   down. Used for telemetry-forwarding tests that need a client carrying an
   :on-github-telemetry handler (the shared *test-client* has none)."
  [client-opts f]
  (let [server (mock/create-mock-server)
        _ (mock/start-mock-server! server)
        client (sdk/client (merge {:auto-start? false} client-opts))
        [in out] (mock/client-streams server)]
    (client/connect-with-streams! client in out)
    (try
      (f server client)
      (finally
        (try (sdk/stop! client) (catch Exception _))
        (mock/stop-mock-server! server)))))

(deftest test-on-github-telemetry-client-option-accepted
  (testing ":on-github-telemetry client option is accepted and stored on the client (upstream PR #1835)"
    (let [c (sdk/client {:auto-start? false :on-github-telemetry (fn [_])})]
      (is (fn? (:on-github-telemetry c))))))

(deftest test-github-telemetry-forwarding-flag-on-wire
  (testing "enableGitHubTelemetryForwarding=true in session.create when handler is set (upstream PR #1835)"
    (with-telemetry-client*
      {:on-github-telemetry (fn [_])}
      (fn [server client]
        (let [seen (atom {})
              _ (mock/set-request-hook! server (fn [method params]
                                                 (when (= "session.create" method)
                                                   (swap! seen assoc method params))))
              _ (sdk/create-session client {:on-permission-request sdk/approve-all})
              create-params (get @seen "session.create")]
          (is (true? (:enableGitHubTelemetryForwarding create-params)))))))

  (testing "enableGitHubTelemetryForwarding=true in session.resume when handler is set (upstream PR #1835)"
    (with-telemetry-client*
      {:on-github-telemetry (fn [_])}
      (fn [server client]
        (let [session-id (sdk/session-id (sdk/create-session client {:on-permission-request sdk/approve-all}))
              seen (atom {})
              _ (mock/set-request-hook! server (fn [method params]
                                                 (when (= "session.resume" method)
                                                   (swap! seen assoc method params))))
              _ (sdk/resume-session client session-id {:on-permission-request sdk/approve-all})
              resume-params (get @seen "session.resume")]
          (is (true? (:enableGitHubTelemetryForwarding resume-params)))))))

  (testing "enableGitHubTelemetryForwarding is omitted from session.create when no handler (upstream PR #1835)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= "session.create" method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          create-params (get @seen "session.create")]
      (is (not (contains? create-params :enableGitHubTelemetryForwarding)))))

  (testing "enableGitHubTelemetryForwarding is omitted from session.resume when no handler (upstream PR #1835)"
    (let [session-id (sdk/session-id (sdk/create-session *test-client* {:on-permission-request sdk/approve-all}))
          seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= "session.resume" method)
                                                      (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id {:on-permission-request sdk/approve-all})
          resume-params (get @seen "session.resume")]
      (is (not (contains? resume-params :enableGitHubTelemetryForwarding)))))

  (testing "wire builders emit enableGitHubTelemetryForwarding only for an explicit true config value (upstream PR #1835)"
    ;; Guard the emit on `true?`, not `some?`: an explicit `false` in config
    ;; must be omitted from the wire, never stamped as `false`.
    (let [create @#'client/build-create-session-params
          resume #(#'client/build-resume-session-params %1 %2)]
      (is (true? (:enableGitHubTelemetryForwarding
                  (create {:enable-github-telemetry-forwarding? true}))))
      (is (not (contains? (create {:enable-github-telemetry-forwarding? false})
                          :enableGitHubTelemetryForwarding)))
      (is (not (contains? (create {}) :enableGitHubTelemetryForwarding)))
      (is (true? (:enableGitHubTelemetryForwarding
                  (resume "s-1" {:enable-github-telemetry-forwarding? true}))))
      (is (not (contains? (resume "s-1" {:enable-github-telemetry-forwarding? false})
                          :enableGitHubTelemetryForwarding)))
      (is (not (contains? (resume "s-1" {}) :enableGitHubTelemetryForwarding))))))

(deftest test-github-telemetry-forwarding-on-connect-handshake
  (testing "enableGitHubTelemetryForwarding=true on the `connect` handshake when a handler is set (upstream PR #1909)"
    (with-telemetry-client*
      {:on-github-telemetry (fn [_])}
      (fn [server client]
        (let [seen (atom [])
              _ (mock/set-request-hook! server (fn [method params]
                                                 (when (= "connect" method)
                                                   (swap! seen conj params))))
              _ (#'client/verify-protocol-version! client)]
          (is (some (fn [p] (true? (:enableGitHubTelemetryForwarding p))) @seen)
              "connect should carry the telemetry opt-in when a handler is registered")))))

  (testing "enableGitHubTelemetryForwarding is omitted from `connect` when no handler is set (upstream PR #1909)"
    (let [seen (atom [])
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= "connect" method)
                                                      (swap! seen conj params))))
          _ (#'client/verify-protocol-version! *test-client*)]
      (is (seq @seen) "connect should have been sent")
      (is (not-any? #(contains? % :enableGitHubTelemetryForwarding) @seen)
          "connect must not stamp the flag without a handler"))))

(deftest test-enable-managed-settings-forwarded
  (testing "enableManagedSettings forwarded on session.create + session.resume wire params (upstream PR #1925)"
    (let [create @#'client/build-create-session-params
          resume #(#'client/build-resume-session-params %1 %2)]
      (is (true? (:enable-managed-settings (create {:enable-managed-settings? true}))))
      (is (false? (:enable-managed-settings (create {:enable-managed-settings? false})))
          "an explicit false is forwarded (matches upstream spread of config.enableManagedSettings)")
      (is (not (contains? (create {}) :enable-managed-settings))
          "omitted when the caller did not set the option")
      (is (true? (:enable-managed-settings (resume "s-1" {:enable-managed-settings? true}))))
      (is (false? (:enable-managed-settings (resume "s-1" {:enable-managed-settings? false}))))
      (is (not (contains? (resume "s-1" {}) :enable-managed-settings)))))

  (testing "enableManagedSettings reaches the wire (camelCase) on create when set"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= "session.create" method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all
                                               :enable-managed-settings? true})]
      (is (true? (:enableManagedSettings (get @seen "session.create")))))))

(deftest test-v1-0-9-session-config-parity
  (let [config {:enable-experimental-mode? true
                :additional-directories []
                :disabled-mcp-servers []
                :github-mcp-tool-config {:enable-all-tools? true
                                         :additional-toolsets ["repos"]
                                         :additional-tools ["get_me"]
                                         :enable-insiders-mode? false
                                         :disable-form-deferral? true}
                :managed-settings {:permissions {:disable-bypass-permissions-mode :disable
                                                 :deny ["Shell(rm -rf *)"]
                                                 :ask ["Shell(git push *)"]
                                                 :allow ["Read(**)"]}}
                :reasoning-effort "max"
                :custom-agents-local-only false}
        create-wire (util/clj->wire (@#'client/build-create-session-params config))
        resume-wire (util/clj->wire (#'client/build-resume-session-params "s-1" config))]
    (testing "new config fields are accepted on create, resume, and join"
      (is (s/valid? ::specs/session-config config))
      (is (s/valid? ::specs/resume-session-config config))
      (is (s/valid? ::specs/join-session-config config)))

    (testing "create and resume emit the exact upstream wire shape"
      (doseq [wire [create-wire resume-wire]]
        (is (true? (:isExperimentalMode wire)))
        (is (= [] (:additionalDirectories wire)))
        (is (= [] (:disabledMcpServers wire)))
        (is (= {:enableAllTools true
                :additionalToolsets ["repos"]
                :additionalTools ["get_me"]
                :enableInsidersMode false
                :disableFormDeferral true}
               (:githubMcpToolConfig wire)))
        (is (= {:permissions {:disableBypassPermissionsMode "disable"
                              :deny ["Shell(rm -rf *)"]
                              :ask ["Shell(git push *)"]
                              :allow ["Read(**)"]}}
               (:managedSettings wire)))
        (is (= "max" (:reasoningEffort wire)))
        (is (false? (:customAgentsLocalOnly wire)))))

    (testing "invalid managed-policy and effort values fail closed"
      (is (not (s/valid? ::specs/session-config
                         (assoc-in config
                                   [:managed-settings :permissions :disable-bypass-permissions-mode]
                                   :policy/enabled))))
      (is (not (s/valid? ::specs/session-config
                         (assoc config :reasoning-effort "ultra")))))

    (testing "empty mode supplies startup defaults while normal mode omits them"
      (let [empty-client (sdk/client {:auto-start? false
                                      :mode :empty
                                      :copilot-home "/tmp/copilot-sdk-empty-mode-test"})
            empty-config (#'client/normalize-config-for-mode empty-client {})
            empty-wire (util/clj->wire (@#'client/build-create-session-params empty-config))
            normal-wire (util/clj->wire (@#'client/build-create-session-params {}))]
        (is (false? (:isExperimentalMode empty-wire)))
        (is (true? (:customAgentsLocalOnly empty-wire)))
        (is (not (contains? normal-wire :isExperimentalMode)))
        (is (not (contains? normal-wire :customAgentsLocalOnly)))))))

(deftest test-canvas-provider-forwarded
  (testing "canvasProvider forwarded on session.create + session.resume wire params (upstream PR #1847)"
    (let [create @#'client/build-create-session-params
          resume #(#'client/build-resume-session-params %1 %2)
          cp {:id "app:builtin:win-1" :name "My App"}]
      (is (= cp (:canvas-provider (create {:canvas-provider cp}))))
      (is (not (contains? (create {}) :canvas-provider)))
      (is (= cp (:canvas-provider (resume "s-1" {:canvas-provider cp}))))
      (is (not (contains? (resume "s-1" {}) :canvas-provider)))))

  (testing "canvasProvider reaches the wire (camelCase, nested id/name) on create when set"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= "session.create" method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all
                                               :canvas-provider {:id "app:builtin:win-1" :name "My App"}})
          cp (:canvasProvider (get @seen "session.create"))]
      (is (= "app:builtin:win-1" (:id cp)))
      (is (= "My App" (:name cp))))))

(deftest test-github-telemetry-event-invokes-callback
  (testing "gitHubTelemetry.event notification invokes :on-github-telemetry with idiom-shaped params; opaque sub-maps pass through verbatim (upstream PR #1835)"
    (let [received (promise)]
      (with-telemetry-client*
        {:on-github-telemetry (fn [notif] (deliver received notif))}
        (fn [server _client]
          (mock/send-notification! server "gitHubTelemetry.event"
                                   {:sessionId "sess-1"
                                    :restricted false
                                    :event {:kind "model_call"
                                            :created_at "2024-01-01T00:00:00Z"
                                            :model_call_id "mc-123"
                                            :session_id "sess-1"
                                            :copilot_tracking_id "trk-9"
                                            :properties {:someWeirdKey "v" :another_Key "w"}
                                            :metrics {:someMetricKey 1 :another_Metric 2}
                                            :features {:someFeatureKey "on"}
                                            :client {:cli_version "1.0.0"
                                                     :os_platform "darwin"}}})
          (let [notif (deref received 1000 :timeout)]
            (is (not= :timeout notif) "callback should be invoked within 1s")
            (when (map? notif)
              ;; Top-level notification scalars: snake/camel -> kebab
              (is (= "sess-1" (:session-id notif)))
              (is (= false (:restricted notif)))
              (let [event (:event notif)]
                ;; Event scalars: snake_case -> kebab-case
                (is (= "model_call" (:kind event)))
                (is (= "mc-123" (:model-call-id event)))
                (is (= "sess-1" (:session-id event)))
                (is (= "trk-9" (:copilot-tracking-id event)))
                ;; Opaque sub-maps: keys preserved VERBATIM (not kebab-cased)
                (is (= {:someWeirdKey "v" :another_Key "w"} (:properties event)))
                (is (= {:someMetricKey 1 :another_Metric 2} (:metrics event)))
                (is (= {:someFeatureKey "on"} (:features event)))
                ;; Nested client info: snake_case scalars -> kebab-case
                (is (= "1.0.0" (get-in event [:client :cli-version])))
                (is (= "darwin" (get-in event [:client :os-platform])))))))))))

(deftest test-github-telemetry-handler-throwable-does-not-kill-router
  (testing "a telemetry handler throwing a non-Exception Throwable (e.g. AssertionError) must not kill the notification router; a later notification still dispatches (upstream PR #1835, regression guard)"
    (let [calls (atom 0)
          first-observed (promise)
          second-received (promise)
          handler (fn [_notif]
                    (if (= 1 (swap! calls inc))
                      ;; AssertionError is a Throwable but NOT an Exception —
                      ;; a `catch Exception` would let it escape and unwind the
                      ;; notification go-loop, killing dispatch for all sessions.
                      (do
                        (deliver first-observed true)
                        (throw (AssertionError. "boom")))
                      (deliver second-received :ok)))]
      (with-telemetry-client*
        {:on-github-telemetry handler}
        (fn [server _client]
          (mock/send-notification! server "gitHubTelemetry.event"
                                   {:sessionId "s1" :restricted false :event {:kind "k"}})
          (await-value! first-observed "first telemetry handler invocation" 1000)
          (mock/send-notification! server "gitHubTelemetry.event"
                                   {:sessionId "s2" :restricted false :event {:kind "k"}})
          (is (= :ok (deref second-received 1000 :timeout))
              "router must survive a Throwable from the handler and dispatch later notifications"))))))

(deftest test-lifecycle-handler-blocking-does-not-stall-router
  (testing "a slow/blocking lifecycle handler dispatched on the serial worker must not stall the notification router; non-lifecycle notifications still route promptly (issue #126)"
    (let [release (promise)
          started (promise)
          fired (promise)
          unsub (sdk/on-lifecycle-event
                 *test-client*
                 (fn [event]
                   (deliver started :ok)
                   ;; Block the serial lifecycle worker until released — this
                   ;; simulates a slow/blocking handler. If dispatch ran inline
                   ;; on the router go-loop, this would stall ALL routing.
                   (deref release 2000 :timeout)
                   (deliver fired (:lifecycle-event-type event))))]
      (try
        (mock/send-notification! *mock-server* "session.lifecycle"
                                 {:type "session.created" :sessionId "s1"})
        (is (= :ok (deref started 1000 :timeout))
            "lifecycle handler should be invoked on the serial worker")
        ;; While the lifecycle handler is still blocked, an unrelated
        ;; notification must still route through the go-loop promptly.
        (let [notif-ch (sdk/notifications *test-client*)]
          (mock/send-notification! *mock-server* "cli.status" {:status "ok"})
          (let [[notif _] (alts!! [notif-ch (timeout 1000)])]
            (is (some? notif)
                "router must not be stalled by the blocked lifecycle handler")
            (is (= "cli.status" (:method notif)))))
        ;; Release the handler and confirm the lifecycle event was delivered.
        (deliver release :go)
        (is (= :session.created (deref fired 1000 :timeout))
            "lifecycle handler should complete once unblocked")
        (finally
          (unsub))))))

(deftest test-send-request-headers-on-wire
  (testing "send! forwards :request-headers as wire :requestHeaders (upstream PR #1094)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.send"} method)
                                                      (swap! seen assoc method params))))
          session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          _ (sdk/send! session {:prompt "Hi"
                                :request-headers {"X-Trace-Id" "abc-123"
                                                  "X-Custom" "value"}})
          send-params (get @seen "session.send")]
      (is (= "abc-123" (get-in send-params [:requestHeaders (keyword "X-Trace-Id")])))
      (is (= "value" (get-in send-params [:requestHeaders (keyword "X-Custom")])))))

  (testing "send! omits requestHeaders when not set"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.send"} method)
                                                      (swap! seen assoc method params))))
          session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          _ (sdk/send! session {:prompt "Hi"})
          send-params (get @seen "session.send")]
      (is (not (contains? send-params :requestHeaders)))))

  (testing "send-async forwards :request-headers as wire :requestHeaders (upstream PR #1094)"
    (let [seen (atom {})
          send-observed (promise)
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.send"} method)
                                                      (swap! seen assoc method params)
                                                      (deliver send-observed true))))
          session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          _ (sdk/send-async session {:prompt "Hi"
                                     :request-headers {"X-Trace-Id" "xyz-789"}})]
      (await-value! send-observed "async session.send request" 2000)
      (let [send-params (get @seen "session.send")]
        (is (some? send-params) "async send should have issued session.send within deadline")
        (is (= "xyz-789" (get-in send-params [:requestHeaders (keyword "X-Trace-Id")])))))))

(deftest test-provider-headers-on-wire
  (testing "Provider :headers field is forwarded in session.create (upstream PR #1094)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :model "gpt-5"
                                 :provider {:base-url "https://example.com"
                                            :headers {"X-Org" "acme"}}})
          create-params (get @seen "session.create")]
      (is (= "acme" (get-in create-params [:provider :headers (keyword "X-Org")]))))))

(deftest test-spec-can-offer-session-approval
  (testing ":can-offer-session-approval is a valid boolean spec (upstream 1.0.28)"
    (is (s/valid? :github.copilot-sdk.specs/can-offer-session-approval true))
    (is (s/valid? :github.copilot-sdk.specs/can-offer-session-approval false))
    (is (not (s/valid? :github.copilot-sdk.specs/can-offer-session-approval "yes")))))

(deftest test-spec-reasoning-tokens
  (testing ":reasoning-tokens is a non-negative integer spec (upstream 1.0.32)"
    (is (s/valid? :github.copilot-sdk.specs/reasoning-tokens 0))
    (is (s/valid? :github.copilot-sdk.specs/reasoning-tokens 1234))
    (is (not (s/valid? :github.copilot-sdk.specs/reasoning-tokens -1)))
    (is (not (s/valid? :github.copilot-sdk.specs/reasoning-tokens "100")))))

(deftest test-spec-agent-id-on-base-event
  (testing ":agent-id is accepted as an optional string on base events (upstream PR #1108)"
    (let [evt {:event-id "evt-1"
               :event-timestamp "2026-04-20T10:00:00Z"
               :parent-id nil
               :agent-id "subagent-42"}]
      (is (s/valid? :github.copilot-sdk.specs/base-event evt)))
    (let [evt-no-agent {:event-id "evt-2"
                        :event-timestamp "2026-04-20T10:00:00Z"
                        :parent-id nil}]
      (is (s/valid? :github.copilot-sdk.specs/base-event evt-no-agent)))))

(deftest test-enable-session-telemetry-on-wire
  (testing "enableSessionTelemetry is forwarded in session.create when true"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :enable-session-telemetry? true})
          create-params (get @seen "session.create")]
      (is (true? (:enableSessionTelemetry create-params)))))

  (testing "enableSessionTelemetry is forwarded in session.create when false"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :enable-session-telemetry? false})
          create-params (get @seen "session.create")]
      (is (false? (:enableSessionTelemetry create-params)))))

  (testing "enableSessionTelemetry is omitted when not set"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all})
          create-params (get @seen "session.create")]
      (is (not (contains? create-params :enableSessionTelemetry)))))

  (testing "enableSessionTelemetry is forwarded in session.resume"
    (let [seen (atom {})
          session-id (sdk/session-id (sdk/create-session *test-client* {:on-permission-request sdk/approve-all}))
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.resume"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :enable-session-telemetry? false})
          resume-params (get @seen "session.resume")]
      (is (false? (:enableSessionTelemetry resume-params))))))

(deftest test-request-exit-plan-mode-wire-flag
  (testing "requestExitPlanMode is true when :on-exit-plan-mode is provided"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :on-exit-plan-mode (fn [_req _ctx] {:approved? true})})
          create-rpcs (filter #(= "session.create" (:method %)) @requests)]
      (is (= 1 (count create-rpcs)))
      (is (true? (:requestExitPlanMode (:params (first create-rpcs)))))))

  (testing "requestExitPlanMode is false when no handler"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          create-rpcs (filter #(= "session.create" (:method %)) @requests)]
      (is (false? (:requestExitPlanMode (:params (first create-rpcs))))))))

(deftest test-exit-plan-mode-handler-invoked
  (testing "exitPlanMode.request calls registered handler"
    (let [handler-called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :on-exit-plan-mode
                                       (fn [request ctx]
                                         (reset! handler-called {:request request :ctx ctx})
                                         {:approved? true :selected-action "continue" :feedback "ok"})})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "exitPlanMode.request"
                                           {:sessionId session-id
                                            :summary "Plan summary"
                                            :planContent "1. Step\n2. Step"
                                            :actions ["continue" "abort"]
                                            :recommendedAction "continue"})]
      (is (some? @handler-called))
      (is (= "Plan summary" (get-in @handler-called [:request :summary])))
      (is (= ["continue" "abort"] (get-in @handler-called [:request :actions])))
      (is (= "continue" (get-in @handler-called [:request :recommended-action])))
      (is (= session-id (get-in @handler-called [:ctx :session-id])))
      (is (true? (get-in response [:result :approved])))
      (is (= "continue" (get-in response [:result :selectedAction])))
      (is (= "ok" (get-in response [:result :feedback]))))))

(deftest test-exit-plan-mode-no-handler-default-approves
  (testing "exitPlanMode.request without handler returns {:approved true}"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "exitPlanMode.request"
                                           {:sessionId session-id
                                            :summary "p"
                                            :actions ["go"]
                                            :recommendedAction "go"})]
      (is (true? (get-in response [:result :approved]))))))

(deftest test-request-auto-mode-switch-wire-flag
  (testing "requestAutoModeSwitch is true when :on-auto-mode-switch is provided"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :on-auto-mode-switch (fn [_req _ctx] :no)})
          create-rpcs (filter #(= "session.create" (:method %)) @requests)]
      (is (= 1 (count create-rpcs)))
      (is (true? (:requestAutoModeSwitch (:params (first create-rpcs)))))))

  (testing "requestAutoModeSwitch is false when no handler"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          create-rpcs (filter #(= "session.create" (:method %)) @requests)]
      (is (false? (:requestAutoModeSwitch (:params (first create-rpcs))))))))

(deftest test-auto-mode-switch-handler-invoked
  (testing "autoModeSwitch.request calls handler; response wrapped in {response}"
    (let [handler-called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :on-auto-mode-switch
                                       (fn [request ctx]
                                         (reset! handler-called {:request request :ctx ctx})
                                         :yes-always)})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "autoModeSwitch.request"
                                           {:sessionId session-id
                                            :errorCode "rate_limited"
                                            :retryAfterSeconds 60})]
      (is (some? @handler-called))
      (is (= "rate_limited" (get-in @handler-called [:request :error-code])))
      (is (= 60 (get-in @handler-called [:request :retry-after-seconds])))
      (is (= session-id (get-in @handler-called [:ctx :session-id])))
      (is (= "yes_always" (get-in response [:result :response]))))))

(deftest test-auto-mode-switch-handler-string-response
  (testing "handler may return wire string directly"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :on-auto-mode-switch (fn [_ _] "yes")})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "autoModeSwitch.request"
                                           {:sessionId session-id})]
      (is (= "yes" (get-in response [:result :response]))))))

(deftest test-auto-mode-switch-no-handler-default-no
  (testing "autoModeSwitch.request without handler returns {:response \"no\"}"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "autoModeSwitch.request"
                                           {:sessionId session-id})]
      (is (= "no" (get-in response [:result :response]))))))

(deftest test-convert-mcp-call-tool-result-empty-mime-type
  (testing "empty mime-type string falls back to application/octet-stream"
    (let [result (tools/convert-mcp-call-tool-result
                  {:content [{:type "resource"
                              :resource {:uri "file:///x"
                                         :blob "blobdata"
                                         :mime-type ""}}]})]
      (is (= 1 (count (:binary-results-for-llm result))))
      (is (= "application/octet-stream"
             (:mime-type (first (:binary-results-for-llm result))))))))

(deftest test-convert-mcp-call-tool-result-non-string-mime-type
  (testing "non-string mime-type falls back to application/octet-stream"
    (let [result (tools/convert-mcp-call-tool-result
                  {:content [{:type "resource"
                              :resource {:uri "file:///x"
                                         :blob "blobdata"
                                         :mime-type 123}}]})]
      (is (= "application/octet-stream"
             (:mime-type (first (:binary-results-for-llm result))))))))

(deftest test-subagent-started-model-field
  (testing "idiom ::subagent.started-data spec accepts optional :model"
    (is (s/valid? :github.copilot-sdk.specs/subagent.started-data
                  {:tool-call-id "tc-1"
                   :agent-name "rubber-duck"
                   :agent-display-name "Rubber Duck"
                   :agent-description "Reviews a proposed implementation"
                   :model "gpt-5.4"}))
    (is (s/valid? :github.copilot-sdk.specs/subagent.started-data
                  {:tool-call-id "tc-1"
                   :agent-name "rubber-duck"
                   :agent-display-name "Rubber Duck"
                   :agent-description "Reviews a proposed implementation"}))))
