(ns github.copilot-sdk.integration.tools-config-test
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

(deftest test-tool-registration
  (testing "Register tool handler"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :tools [(sdk/define-tool "test_tool"
                                                 {:description "A test tool"
                                                  :parameters {:type "object"
                                                               :properties {"value" {:type "string"}}}
                                                  :handler (fn [_args _invocation] "result")})]})]
      (is (some? session)))))

(deftest test-session-config-wire-keys
  (testing "session config maps are converted to wire keys"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"
                                                             "session.resume"
                                                             "session.mcp.reloadWithConfig"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :model "gpt-5.4"
                                 :provider {:base-url "https://example.test"
                                            :api-key "key"}
                                 :mcp-servers {"srv-1" {:mcp-server-type :http
                                                        :mcp-url "https://mcp.test"
                                                        :mcp-tools ["*"]
                                                        :mcp-timeout 1000}}
                                 :custom-agents [{:agent-name "agent-1"
                                                  :agent-prompt "You are agent 1"
                                                  :agent-display-name "Agent One"}]})
          session-id (sdk/get-last-session-id *test-client*)
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :model "gpt-5.4"
                                 :provider {:base-url "https://resume.test"}
                                 :mcp-servers {"srv-2" {:mcp-server-type :sse
                                                        :mcp-url "https://mcp.resume.test"
                                                        :mcp-tools ["*"]}}
                                 :custom-agents [{:agent-name "agent-2"
                                                  :agent-prompt "You are agent 2"}]})
          create-params (get @seen "session.create")
          resume-params (get @seen "session.resume")
          reload-params (get @seen "session.mcp.reloadWithConfig")]
      (is (= "https://example.test" (get-in create-params [:provider :baseUrl])))
      (is (= "key" (get-in create-params [:provider :apiKey])))
      ;; MCP server keys: :mcp-* prefix is stripped on wire (upstream uses bare names)
      (is (= "http" (get-in create-params [:mcpServers :srv-1 :type])))
      (is (= "https://mcp.test" (get-in create-params [:mcpServers :srv-1 :url])))
      (is (= ["*"] (get-in create-params [:mcpServers :srv-1 :tools])))
      (is (= 1000 (get-in create-params [:mcpServers :srv-1 :timeout])))
      (is (= "agent-1" (get-in create-params [:customAgents 0 :name])))
      (is (= "Agent One" (get-in create-params [:customAgents 0 :displayName])))
      (is (= "https://resume.test" (get-in resume-params [:provider :baseUrl])))
      (is (= "sse" (get-in resume-params [:mcpServers :srv-2 :type])))
      (is (= "https://mcp.resume.test" (get-in resume-params [:mcpServers :srv-2 :url])))
      (is (= ["*"] (get-in resume-params [:mcpServers :srv-2 :tools])))
      (is (nil? reload-params))
      (is (= "agent-2" (get-in resume-params [:customAgents 0 :name])))
      ;; envValueMode is always sent as "direct" (upstream PR #484)
      (is (= "direct" (:envValueMode create-params)))
      (is (= "direct" (:envValueMode resume-params))))))

(deftest test-async-resume-configures-mcp-in-resume-request-only
  (let [seed (sdk/create-session *test-client* {})
        session-id (sdk/session-id seed)
        requests (atom [])
        _ (mock/set-request-hook! *mock-server*
                                  (fn [method params]
                                    (swap! requests conj [method params])))
        result (async/<!!
                (sdk/<resume-session
                 *test-client*
                 session-id
                 {:mcp-servers
                  {"srv" {:mcp-server-type :http
                          :mcp-url "https://mcp.async.test"
                          :mcp-tools ["*"]}}}))
        resume-request (some #(when (= "session.resume" (first %)) %) @requests)
        reloads (filter #(= "session.mcp.reloadWithConfig" (first %)) @requests)]
    (is (not (instance? Throwable result)))
    (is (= {:sessionId session-id
            :mcpServers
            {:srv {:type "http"
                   :url "https://mcp.async.test"
                   :tools ["*"]}}}
           (select-keys (second resume-request) [:sessionId :mcpServers])))
    (is (empty? reloads))))

(deftest test-custom-agent-mcp-server-ids-on-wire
  (testing "custom-agent MCP server IDs and config shapes survive create and resume"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"session.create" "session.resume"} method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session
             *test-client*
             {:on-permission-request sdk/approve-all
              :custom-agents
              [{:agent-name "create-agent"
                :agent-display-name "Create Agent"
                :agent-description "Exercises keyword MCP server IDs"
                :agent-prompt "Use the configured MCP server."
                :agent-skills ["database"]
                :agent-model "gpt-5.4"
                :mcp-servers
                {:team/srv-1 {:mcp-command "node"
                              :mcp-args ["server.js"]
                              :mcp-tools ["query"]
                              :mcp-timeout 1000
                              :cwd "/tmp/create"}}}]})
          session-id (sdk/get-last-session-id *test-client*)
          _ (sdk/resume-session
             *test-client*
             session-id
             {:on-permission-request sdk/approve-all
              :custom-agents
              [{:agent-name "resume-agent"
                :agent-display-name "Resume Agent"
                :agent-description "Exercises string MCP server IDs"
                :agent-prompt "Use the configured remote MCP server."
                :agent-skills ["research"]
                :agent-model "gpt-5.4"
                :mcp-servers
                {"srv-2" {:mcp-server-type :http
                          :mcp-url "https://mcp.resume.test"
                          :mcp-tools ["search"]
                          :mcp-timeout 2000
                          :mcp-defer-tools :never}}}]})
          create-params (get @seen "session.create")
          resume-params (get @seen "session.resume")]
      (is (= [{:name "create-agent"
               :displayName "Create Agent"
               :description "Exercises keyword MCP server IDs"
               :prompt "Use the configured MCP server."
               :skills ["database"]
               :model "gpt-5.4"
               :mcpServers
               {:team/srv-1 {:command "node"
                             :args ["server.js"]
                             :tools ["query"]
                             :timeout 1000
                             :cwd "/tmp/create"}}}]
             (:customAgents create-params)))
      (is (= [{:name "resume-agent"
               :displayName "Resume Agent"
               :description "Exercises string MCP server IDs"
               :prompt "Use the configured remote MCP server."
               :skills ["research"]
               :model "gpt-5.4"
               :mcpServers
               {:srv-2 {:type "http"
                        :url "https://mcp.resume.test"
                        :tools ["search"]
                        :timeout 2000
                        :deferTools "never"}}}]
             (:customAgents resume-params))))))

(deftest test-tool-metadata-and-tool-search-wire-shape
  (testing "tool metadata and tool-search config are forwarded by create and resume"
    (let [seen (atom {})
          metadata {"github.com/copilot:safeForTelemetry"
                    {"name" true
                     "inputs_names" false}}
          tools [(tools/define-tool
                   "metadata-tool"
                   {:description "Tool with host metadata"
                    :metadata metadata
                    :handler (fn [_args _invocation] "ok")})
                 {:tool-name "empty-metadata-tool"
                  :metadata {}
                  :tool-handler (fn [_args _invocation] "ok")}
                 {:tool-name "no-metadata-tool"
                  :tool-handler (fn [_args _invocation] "ok")}]
          config {:on-permission-request sdk/approve-all
                  :tools tools
                  :tool-search {:enabled true
                                :defer-threshold 17}}
          _ (mock/set-request-hook!
             *mock-server*
             (fn [method params]
               (when (#{"session.create" "session.resume"} method)
                 (swap! seen assoc method params))))
          created (sdk/create-session *test-client* config)
          _ (sdk/resume-session *test-client* (sdk/session-id created) config)]
      (is (= metadata (:metadata (first tools))))
      (doseq [method ["session.create" "session.resume"]]
        (let [params (get @seen method)]
          (is (= {:enabled true :deferThreshold 17}
                 (:toolSearch params)))
          (is (= {:github.com/copilot:safeForTelemetry
                  {:name true
                   :inputs_names false}}
                 (get-in params [:tools 0 :metadata])))
          (is (= {} (get-in params [:tools 1 :metadata])))
          (is (not (contains? (get-in params [:tools 2]) :metadata)))))))

  (testing "absent tool metadata and tool-search config are omitted"
    (let [seen (atom nil)
          _ (mock/set-request-hook!
             *mock-server*
             (fn [method params]
               (when (= "session.create" method)
                 (reset! seen params))))
          _ (sdk/create-session
             *test-client*
             {:on-permission-request sdk/approve-all
              :tools [{:tool-name "plain-tool"
                       :tool-handler (fn [_args _invocation] "ok")}]})]
      (is (not (contains? @seen :toolSearch)))
      (is (not (contains? (get-in @seen [:tools 0]) :metadata))))))

(deftest test-terminal-tool-wire-shape
  (let [terminal (tools/define-tool "terminal"
                   {:description "Runs in a terminal"
                    :is-terminal? true})
        non-terminal (tools/define-tool-from-spec "non-terminal"
                       {:description "Does not need a terminal"
                        :is-terminal? false})
        config {:tools [terminal non-terminal]}
        create-wire (util/clj->wire (@#'client/build-create-session-params config))
        resume-wire (util/clj->wire (#'client/build-resume-session-params "s-1" config))]
    (is (s/valid? ::specs/tool terminal))
    (is (s/valid? ::specs/tool non-terminal))
    (doseq [wire [create-wire resume-wire]]
      (is (true? (get-in wire [:tools 0 :isTerminal])))
      (is (false? (get-in wire [:tools 1 :isTerminal]))))))

(deftest test-tool-search-specs
  (testing "tool-search config uses optional boolean and integer fields"
    (is (s/valid? ::specs/tool-search {}))
    (is (s/valid? ::specs/tool-search {:enabled false :defer-threshold 10}))
    (is (not (s/valid? ::specs/tool-search {:enabled "false"})))
    (is (not (s/valid? ::specs/tool-search {:defer-threshold 0.5}))))

  (testing "tool-search invocation and result fields have public specs"
    (is (some? (s/get-spec ::specs/current-tool-metadata)))
    (is (some? (s/get-spec ::specs/tool-invocation)))
    (is (some? (s/get-spec ::specs/tool-references)))))

(deftest test-schema-update-public-idiom-specs
  (testing "canvas icons are represented in the public canvas snapshot spec"
    (is (some? (s/get-spec ::specs/icon)))
    (is (s/valid? ::specs/open-canvas-instance
                  {:instance-id "instance-1"
                   :extension-id "extension-1"
                   :canvas-id "canvas-1"
                   :icon "/tmp/canvas.png"}))
    (is (not (s/valid? ::specs/open-canvas-instance
                       {:instance-id "instance-1"
                        :extension-id "extension-1"
                        :canvas-id "canvas-1"
                        :icon 42}))))

  (testing "model billing promotions use their generated wire shape"
    (is (some? (s/get-spec ::specs/model-billing-promo)))
    (is (s/valid? ::specs/model-billing
                  {:promo {:id "launch"
                           :discount-percent 25.5
                           :ends-at "2026-08-01T00:00:00Z"
                           :message "Launch promotion"}}))
    (is (s/valid? ::specs/model-billing
                  {:promo {:id "open-ended"
                           :discount-percent 10
                           :message "Open-ended promotion"}}))
    (is (not (s/valid? ::specs/model-billing
                       {:promo {:ends-at 42}})))))

(deftest test-instruction-directories-forwarded-on-wire
  (testing ":instruction-directories is forwarded to both session.create and session.resume (upstream PR #1190)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create" "session.resume"} method)
                                                      (swap! seen assoc method params))))
          dirs ["/tmp/instructions/a" "/tmp/instructions/b"]
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :instruction-directories dirs})
          session-id (sdk/get-last-session-id *test-client*)
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :instruction-directories dirs})
          create-params (get @seen "session.create")
          resume-params (get @seen "session.resume")]
      (is (= dirs (:instructionDirectories create-params)))
      (is (= dirs (:instructionDirectories resume-params))))))

(deftest test-continue-pending-work-forwarded-on-resume
  (testing ":continue-pending-work? is forwarded as continuePendingWork on session.resume (upstream PR — types.ts:1458)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.resume"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/get-last-session-id *test-client*)
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :continue-pending-work? true})
          resume-params (get @seen "session.resume")]
      (is (= true (:continuePendingWork resume-params))
          "continuePendingWork should appear on the wire when option is set"))))

(deftest test-provider-config-overrides-forwarded-on-wire
  (testing "ProviderConfig override fields are forwarded with correct wire keys (upstream PR #966)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create" "session.resume"} method)
                                                      (swap! seen assoc method params))))
          provider {:base-url "https://example.test"
                    :api-key "key"
                    :model-id "gpt-5"
                    :wire-model "gpt-5-2026"
                    :max-input-tokens 100000
                    :max-output-tokens 4096}
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :model "fallback-model"
                                 :provider provider})
          session-id (sdk/get-last-session-id *test-client*)
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :model "fallback-model"
                                 :provider provider})
          create-provider (get-in @seen ["session.create" :provider])
          resume-provider (get-in @seen ["session.resume" :provider])]
      (testing "create"
        (is (= "gpt-5" (:modelId create-provider)))
        (is (= "gpt-5-2026" (:wireModel create-provider)))
        (is (= 100000 (:maxPromptTokens create-provider))
            "SDK :max-input-tokens must serialize as wire `maxPromptTokens` (matches upstream toWireProviderConfig)")
        (is (= 4096 (:maxOutputTokens create-provider)))
        (is (not (contains? create-provider :maxInputTokens))
            "the SDK-side key `maxInputTokens` must NOT leak onto the wire"))
      (testing "resume"
        (is (= "gpt-5" (:modelId resume-provider)))
        (is (= "gpt-5-2026" (:wireModel resume-provider)))
        (is (= 100000 (:maxPromptTokens resume-provider)))
        (is (= 4096 (:maxOutputTokens resume-provider)))
        (is (not (contains? resume-provider :maxInputTokens)))))))

(deftest test-provider-config-without-overrides-passes-through
  (testing "ProviderConfig without override fields is forwarded unchanged"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= method "session.create")
                                                      (reset! seen params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :model "gpt-5.4"
                                 :provider {:base-url "https://example.test"
                                            :api-key "key"}})
          create-provider (get-in @seen [:provider])]
      (is (= "https://example.test" (:baseUrl create-provider)))
      (is (= "key" (:apiKey create-provider)))
      (is (not (contains? create-provider :maxPromptTokens)))
      (is (not (contains? create-provider :maxInputTokens))))))

(deftest test-provider-config-type-and-azure-wire-keys
  (testing "ProviderConfig :provider-type/:azure-options serialize with upstream wire keys
            (type/azure/apiVersion), not camelCased SDK names (parity with nodejs ProviderConfig)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create" "session.resume"} method)
                                                      (swap! seen assoc method params))))
          provider {:provider-type :azure
                    :wire-api :responses
                    :base-url "https://my-resource.openai.azure.com"
                    :api-key "key"
                    :bearer-token "tok"
                    :azure-options {:azure-api-version "2024-02-01"}}
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :model "fallback-model"
                                 :provider provider})
          session-id (sdk/get-last-session-id *test-client*)
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :model "fallback-model"
                                 :provider provider})]
      (doseq [method ["session.create" "session.resume"]]
        (let [p (get-in @seen [method :provider])]
          (testing method
            (is (= "azure" (:type p)) "wire key must be `type`")
            (is (not (contains? p :providerType)) "SDK key `providerType` must NOT leak onto wire")
            (is (= {:apiVersion "2024-02-01"} (:azure p)) "wire key must be `azure` with `apiVersion`")
            (is (not (contains? p :azureOptions)) "SDK key `azureOptions` must NOT leak onto wire")
            (is (not (contains? (:azure p) :azureApiVersion)) "nested `azureApiVersion` must NOT leak")
            ;; Correctly-camelCased fields must still survive alongside the remaps.
            (is (= "responses" (:wireApi p)))
            (is (= "https://my-resource.openai.azure.com" (:baseUrl p)))
            (is (= "tok" (:bearerToken p)))))))))

(deftest test-provider-config-absent-azure-stays-absent
  (testing "ProviderConfig without :azure-options must not emit a wire `azure` key"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= method "session.create")
                                                      (reset! seen params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :model "gpt-5.4"
                                 :provider {:provider-type :anthropic
                                            :base-url "https://api.anthropic.com"
                                            :api-key "key"}})
          p (get-in @seen [:provider])]
      (is (= "anthropic" (:type p)))
      (is (not (contains? p :providerType)))
      (is (not (contains? p :azure)))
      (is (not (contains? p :azureOptions))))))

(deftest test-schedule-events-in-public-event-types
  (testing "schedule events are part of the public ::sdk/event-types set (upstream schema 1.0.42)"
    (is (contains? sdk/event-types :copilot/session.schedule_created))
    (is (contains? sdk/event-types :copilot/session.schedule_cancelled)))
  (testing "schedule events are also categorized under session-events"
    (is (contains? sdk/session-events :copilot/session.schedule_created))
    (is (contains? sdk/session-events :copilot/session.schedule_cancelled)))
  (testing "schedule events are accepted by the idiom ::specs/event-type spec"
    (is (s/valid? :github.copilot-sdk.specs/event-type :copilot/session.schedule_created))
    (is (s/valid? :github.copilot-sdk.specs/event-type :copilot/session.schedule_cancelled)))
  (testing "schedule data idiom specs validate well-formed payloads (integer :id)"
    (is (s/valid? :github.copilot-sdk.specs/session.schedule_created-data
                  {:id 42 :interval-ms 1000 :prompt "hi"}))
    (is (not (s/valid? :github.copilot-sdk.specs/session.schedule_created-data
                       {:id "uuid" :interval-ms 1000 :prompt "hi"}))
        "schedule data must reject non-integer :id (vs the UUID-string ::id used elsewhere)")
    (is (s/valid? :github.copilot-sdk.specs/session.schedule_cancelled-data {:id 42}))))

(deftest test-custom-notification-event-type
  (testing "session.custom_notification is part of the public ::sdk/event-types set (upstream PR #1292)"
    (is (contains? sdk/event-types :copilot/session.custom_notification)))
  (testing "session.custom_notification is categorized under session-events"
    (is (contains? sdk/session-events :copilot/session.custom_notification)))
  (testing "session.custom_notification is accepted by the idiom ::specs/event-type spec"
    (is (s/valid? :github.copilot-sdk.specs/event-type :copilot/session.custom_notification)))
  (testing "::session.custom_notification-data idiom spec accepts a well-formed payload"
    (is (s/valid? :github.copilot-sdk.specs/session.custom_notification-data
                  {:source "my-extension"
                   :name "doc.opened"
                   :payload {:path "/tmp/x"}
                   :subject {:doc "foo"}
                   :version 1}))
    (is (s/valid? :github.copilot-sdk.specs/session.custom_notification-data
                  {:source "my-extension"
                   :name "ping"
                   :payload "scalar-ok"})
        "payload may be a scalar")
    (is (not (s/valid? :github.copilot-sdk.specs/session.custom_notification-data
                       {:name "doc.opened" :payload {}}))
        "missing :source must reject"))
  (testing "subject and payload keys are preserved verbatim (not kebab-cased) by normalize-incoming"
    ;; Source-defined identifiers (subject) and opaque JSON (payload) must
    ;; survive normalize-incoming without key transformation, matching the
    ;; existing escape hatch for external_tool.requested arguments.
    (let [normalize @#'protocol/normalize-incoming
          raw-msg {:jsonrpc "2.0"
                   :method "session.event"
                   :params {:sessionId "abc"
                            :event {:type "session.custom_notification"
                                    :data {:source "my-ext"
                                           :name "doc.opened"
                                           :subject {:GitHub-Login "octocat"
                                                     :actor.id "123"}
                                           :payload {:firstName "Foo"
                                                     :nested {:userId 42}}}}}}
          normalized (normalize raw-msg)
          data (get-in normalized [:params :event :data])]
      (is (= "session.custom_notification" (get-in normalized [:params :event :type])))
      (is (s/valid? :github.copilot-sdk.specs/session.custom_notification-data data))
      (is (contains? (:subject data) :GitHub-Login)
          "subject must preserve original casing, not collapse to :git-hub-login")
      (is (= "octocat" (get-in data [:subject :GitHub-Login])))
      (is (= "123" (get-in data [:subject :actor.id]))
          "subject must preserve dotted keys")
      (is (= 42 (get-in data [:payload :nested :userId]))
          "payload nested keys must not be kebab-cased")
      (is (= "Foo" (get-in data [:payload :firstName]))
          "payload top-level keys must not be kebab-cased")))
  (testing "subject and payload keys are preserved in historical events from session.getMessages responses"
    ;; Response messages (id, no :method) carrying :result :events collections
    ;; must apply the same preservation rules per-event, so live and
    ;; historical custom_notification events have the same shape.
    (let [normalize @#'protocol/normalize-incoming
          raw-response {:jsonrpc "2.0"
                        :id 42
                        :result {:events [{:type "session.start"
                                           :data {:sessionId "s1"}}
                                          {:type "session.custom_notification"
                                           :data {:source "ext"
                                                  :name "x"
                                                  :subject {:GitHub-Login "octocat"}
                                                  :payload {:nestedKey {:userId 7}}}}
                                          {:type "external_tool.requested"
                                           :data {:id "t1"
                                                  :name "do"
                                                  :arguments {:OriginalKey "v"}}}]}}
          normalized (normalize raw-response)
          events (get-in normalized [:result :events])
          custom (nth events 1)
          ext-tool (nth events 2)]
      (is (= "octocat" (get-in custom [:data :subject :GitHub-Login]))
          "historical custom_notification subject keys must be preserved")
      (is (= 7 (get-in custom [:data :payload :nestedKey :userId]))
          "historical custom_notification payload keys must be preserved")
      (is (= {:OriginalKey "v"} (get-in ext-tool [:data :arguments]))
          "historical external_tool.requested arguments must be preserved")))
  (testing "remote-enable opts are validated synchronously when provided"
    (let [session {:session-id "s" :client {}}]
      (is (thrown? Exception (github.copilot-sdk.session/remote-enable session {:mode :bogus})))
      (is (thrown? Exception (github.copilot-sdk.session/remote-enable session {:mode "on"}))
          "string :mode value is rejected — the spec requires a keyword from #{:off :export :on}"))))

(deftest test-custom-agent-info-tools-nilable
  (testing "::custom-agent-info accepts :tools nil (upstream schema 1.0.41-1: tools: string[] | null)"
    (let [agent-with-nil-tools {:id "a"
                                :name "agent"
                                :display-name "Agent"
                                :description "test"
                                :source "user"
                                :user-invocable? true
                                :tools nil}
          agent-with-vec-tools (assoc agent-with-nil-tools :tools ["read" "write"])]
      (is (s/valid? :github.copilot-sdk.specs/custom-agent-info agent-with-nil-tools)
          "tools=nil must be accepted")
      (is (s/valid? :github.copilot-sdk.specs/custom-agent-info agent-with-vec-tools)
          "tools=vector still accepted")
      (is (not (s/valid? :github.copilot-sdk.specs/custom-agent-info
                         (assoc agent-with-nil-tools :tools "not-a-vec")))
          "tools must still reject non-nil non-collection values"))))

(deftest test-connect-rpc-used-for-handshake
  (testing "verify-protocol-version! sends `connect` and forwards token"
    (let [seen (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"connect" "ping"} method)
                                        (swap! seen conj [method params]))))
          ;; The fixture client is already connected — just exercise verify
          ;; with a fresh client option that pre-loaded a token via state.
          _ (swap! (:state *test-client*) assoc-in [:options :tcp-connection-token] "tok-123")
          _ (reset! (:expected-token *mock-server*) "tok-123")
          verify-version (var client/verify-protocol-version!)
          _ (verify-version *test-client*)
          calls @seen]
      (is (some (fn [[m p]] (and (= m "connect") (= "tok-123" (:token p)))) calls)
          "connect should be called with the token")
      (is (not-any? (fn [[m _]] (= m "ping")) calls)
          "ping should NOT be called when connect succeeds"))))

(deftest test-connect-falls-back-to-ping-on-method-not-found
  (testing "legacy server without `connect` falls back to `ping`"
    (let [seen (atom [])
          _ (reset! (:supports-connect? *mock-server*) false)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"connect" "ping"} method)
                                        (swap! seen conj [method params]))))
          verify-version (var client/verify-protocol-version!)
          _ (verify-version *test-client*)
          methods (map first @seen)]
      (is (some #{"connect"} methods) "connect should be tried first")
      (is (some #{"ping"} methods) "ping should be the fallback")
      (is (= "connect" (first methods))
          "connect should precede ping"))))

(deftest test-connect-falls-back-to-ping-on-unhandled-method-message
  (testing "legacy server returning non-MethodNotFound code but \"Unhandled method connect\" message also falls back to ping (upstream parity)"
    (let [seen (atom [])
          _ (reset! (:supports-connect? *mock-server*) :legacy-message)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method _]
                                      (when (#{"connect" "ping"} method)
                                        (swap! seen conj method))))
          verify-version (var client/verify-protocol-version!)
          _ (verify-version *test-client*)]
      (is (= ["connect" "ping"] @seen)
          "ping must be the fallback when error message is exactly \"Unhandled method connect\""))))

(deftest test-connect-non-method-not-found-error-propagates
  (testing "non-MethodNotFound errors from `connect` are NOT swallowed by ping fallback"
    ;; Token validation is enforced — a wrong token returns -32603 (not -32601),
    ;; so the SDK must propagate the error rather than silently fall back to ping.
    (let [seen (atom [])
          _ (reset! (:expected-token *mock-server*) "correct-token")
          _ (swap! (:state *test-client*) assoc-in [:options :tcp-connection-token] "wrong-token")
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method _]
                                      (when (#{"connect" "ping"} method)
                                        (swap! seen conj method))))
          verify-version (var client/verify-protocol-version!)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid connection token"
           (verify-version *test-client*))
          "non-MethodNotFound error should propagate")
      (is (= ["connect"] @seen)
          "ping fallback must NOT be triggered for non-MethodNotFound errors"))))

(deftest test-tcp-connection-token-rejected-with-use-stdio
  (testing ":tcp-connection-token cannot be combined with :use-stdio? true"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"tcp-connection-token cannot be used with use-stdio"
         (sdk/client {:tcp-connection-token "tok"
                      :use-stdio? true
                      :auto-start? false})))))

(deftest test-tcp-connection-token-auto-generated-for-tcp-spawn
  (testing "SDK auto-generates a UUID token when spawning CLI in TCP mode"
    (let [c (sdk/client {:use-stdio? false
                         :auto-start? false})
          token (get-in c [:options :tcp-connection-token])]
      (is (string? token) "auto-generated token should be a non-blank string")
      (is (re-matches
           #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
           token)
          "auto-generated token should be a UUID")))
  (testing "explicit token wins over auto-generation"
    (let [c (sdk/client {:use-stdio? false
                         :tcp-connection-token "explicit-token"
                         :auto-start? false})]
      (is (= "explicit-token" (get-in c [:options :tcp-connection-token])))))
  (testing "no token auto-generated for stdio mode"
    (let [c (sdk/client {:use-stdio? true :auto-start? false})]
      (is (nil? (get-in c [:options :tcp-connection-token])))))
  (testing "no token auto-generated for cli-url (external server)"
    (let [c (sdk/client {:cli-url "localhost:9999" :auto-start? false})]
      (is (nil? (get-in c [:options :tcp-connection-token])))))
  (testing "no token auto-generated when running as a child process"
    (let [c (sdk/client {:is-child-process? true :auto-start? false})]
      (is (nil? (get-in c [:options :tcp-connection-token]))))))

(deftest test-client-name-forwarded-on-wire
  (testing "clientName is forwarded in session.create when set (upstream PR #510)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all :client-name "my-app"})
          create-params (get @seen "session.create")]
      (is (= "my-app" (:clientName create-params)))))

  (testing "clientName is forwarded in session.resume when set (upstream PR #510)"
    (let [seen (atom {})
          session-id (sdk/session-id (sdk/create-session *test-client* {:on-permission-request sdk/approve-all}))
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.resume"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id {:on-permission-request sdk/approve-all :client-name "my-app"})
          resume-params (get @seen "session.resume")]
      (is (= "my-app" (:clientName resume-params)))))

  (testing "clientName is omitted from wire when not set"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all :model "gpt-5.4"})
          create-params (get @seen "session.create")]
      (is (not (contains? create-params :clientName))))))

(deftest test-per-session-github-token-forwarded-on-wire
  (testing "githubToken is forwarded in session.create when set"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= "session.create" method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :github-token "session-token-create"})
          create-params (get @seen "session.create")]
      (is (= "session-token-create" (:gitHubToken create-params)))
      (is (not (contains? create-params :githubToken)))))

  (testing "githubToken is forwarded in session.resume when set"
    (let [seen (atom {})
          session-id (sdk/session-id (sdk/create-session *test-client* {:on-permission-request sdk/approve-all}))
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= "session.resume" method)
                                                      (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :github-token "session-token-resume"})
          resume-params (get @seen "session.resume")]
      (is (= "session-token-resume" (:gitHubToken resume-params)))
      (is (not (contains? resume-params :githubToken)))))

  (testing "githubToken is omitted from wire when not set"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= "session.create" method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          create-params (get @seen "session.create")]
      (is (not (contains? create-params :gitHubToken)))
      (is (not (contains? create-params :githubToken))))))

(deftest test-remote-session-config-forwarded-on-wire
  (testing "remote-session :on is forwarded as remoteSession in session.create (upstream PR #1295)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= "session.create" method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :remote-session :on})
          create-params (get @seen "session.create")]
      (is (= "on" (:remoteSession create-params)))
      (is (not (contains? create-params :remote-session)))))

  (testing "remote-session :export is forwarded in session.resume (upstream PR #1295)"
    (let [seen (atom {})
          session-id (sdk/session-id (sdk/create-session *test-client* {:on-permission-request sdk/approve-all}))
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= "session.resume" method)
                                                      (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :remote-session :export})
          resume-params (get @seen "session.resume")]
      (is (= "export" (:remoteSession resume-params)))))

  (testing "remote-session :off is forwarded literally (not stripped)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= "session.create" method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :remote-session :off})
          create-params (get @seen "session.create")]
      (is (= "off" (:remoteSession create-params)))))

  (testing "remote-session is omitted from wire when not set"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= "session.create" method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          create-params (get @seen "session.create")]
      (is (not (contains? create-params :remoteSession)))))

  (testing "remote-session config rejects unknown values via spec validation"
    (is (thrown? Exception
                 (sdk/create-session *test-client*
                                     {:on-permission-request sdk/approve-all
                                      :remote-session :bogus})))))

(deftest test-cloud-session-config-forwarded-on-wire
  (testing "cloud {:repository {...}} is forwarded as cloud.repository on session.create (upstream PR #1306)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= "session.create" method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :cloud {:repository {:owner "octocat"
                                                      :name "hello-world"
                                                      :branch "main"}}})
          create-params (get @seen "session.create")]
      (is (= {:owner "octocat" :name "hello-world" :branch "main"}
             (get-in create-params [:cloud :repository])))
      (is (not (contains? create-params :cloud-repository)))))

  (testing "cloud :repository may omit :branch"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= "session.create" method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :cloud {:repository {:owner "octocat"
                                                      :name "hello-world"}}})
          create-params (get @seen "session.create")]
      (is (= {:owner "octocat" :name "hello-world"}
             (get-in create-params [:cloud :repository])))))

  (testing "cloud {} (empty options) is forwarded as empty map"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= "session.create" method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :cloud {}})
          create-params (get @seen "session.create")]
      (is (= {} (:cloud create-params)))))

  (testing "cloud is omitted from wire when not set"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= "session.create" method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          create-params (get @seen "session.create")]
      (is (not (contains? create-params :cloud)))))

  (testing "cloud config with invalid :repository shape is rejected by spec"
    (is (thrown? Exception
                 (sdk/create-session *test-client*
                                     {:on-permission-request sdk/approve-all
                                      :cloud {:repository {:branch "main"}}}))) ; missing :owner and :name
    (is (thrown? Exception
                 (sdk/create-session *test-client*
                                     {:on-permission-request sdk/approve-all
                                      :cloud {:repository "octocat/hello-world"}})))))

(deftest test-agent-forwarded-on-wire
  (testing "agent is forwarded in session.create when set (upstream PR #722)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all :agent "my-agent"})
          create-params (get @seen "session.create")]
      (is (= "my-agent" (:agent create-params)))))

  (testing "agent is forwarded in session.resume when set (upstream PR #722)"
    (let [seen (atom {})
          session-id (sdk/session-id (sdk/create-session *test-client* {:on-permission-request sdk/approve-all}))
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.resume"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id {:on-permission-request sdk/approve-all :agent "my-agent"})
          resume-params (get @seen "session.resume")]
      (is (= "my-agent" (:agent resume-params)))))

  (testing "agent is omitted from wire when not set"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          create-params (get @seen "session.create")]
      (is (not (contains? create-params :agent))))))

(deftest test-override-built-in-tool-on-wire
  (testing "overridesBuiltInTool is sent on the wire when true"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          tool (sdk/define-tool "grep"
                 {:description "Custom grep"
                  :overrides-built-in-tool true
                  :parameters {:type "object"
                               :properties {:query {:type "string"}}}
                  :handler (fn [args _] (str "Custom grep: " (:query args)))})
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all :tools [tool]})
          create-params (get @seen "session.create")
          wire-tool (first (:tools create-params))]
      (is (some? wire-tool) "tool should be present in wire payload")
      (is (= true (:overridesBuiltInTool wire-tool))
          "overridesBuiltInTool must be true on wire")))

  (testing "overridesBuiltInTool is absent on the wire when not set"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          tool (sdk/define-tool "my_tool"
                 {:description "A tool"
                  :handler (fn [_ _] "ok")})
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all :tools [tool]})
          create-params (get @seen "session.create")
          wire-tool (first (:tools create-params))]
      (is (some? wire-tool) "tool should be present in wire payload")
      (is (not (contains? wire-tool :overridesBuiltInTool))
          "overridesBuiltInTool should be absent when not set"))))

(deftest test-defer-on-wire
  ;; Upstream PR #1632: tool definitions accept an optional `defer` of
  ;; "auto" | "never". The idiom uses keywords (:auto / :never) and the
  ;; keyword is converted to its wire string via (name kw).
  (testing "defer is sent on the wire on session.create (keyword -> string)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          tool (sdk/define-tool "lookup_issue"
                 {:description "Fetch issue details"
                  :defer :auto
                  :handler (fn [_ _] "ok")})
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all :tools [tool]})
          wire-tool (first (:tools (get @seen "session.create")))]
      (is (some? wire-tool) "tool should be present in wire payload")
      (is (= "auto" (:defer wire-tool))
          "defer :auto must be sent as the wire string \"auto\"")))

  (testing "defer :never is sent as the wire string \"never\""
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          tool (sdk/define-tool "lookup_issue"
                 {:description "Fetch issue details"
                  :defer :never
                  :handler (fn [_ _] "ok")})
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all :tools [tool]})
          wire-tool (first (:tools (get @seen "session.create")))]
      (is (= "never" (:defer wire-tool))
          "defer :never must be sent as the wire string \"never\"")))

  (testing "defer is absent on the wire when not set"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          tool (sdk/define-tool "my_tool" {:description "A tool" :handler (fn [_ _] "ok")})
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all :tools [tool]})
          wire-tool (first (:tools (get @seen "session.create")))]
      (is (some? wire-tool) "tool should be present in wire payload")
      (is (not (contains? wire-tool :defer))
          "defer should be absent when not set")))

  (testing "defer is sent on the wire on session.resume"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.resume"} method)
                                                      (swap! seen assoc method params))))
          tool (sdk/define-tool "lookup_issue"
                 {:description "Fetch issue details"
                  :defer :auto
                  :handler (fn [_ _] "ok")})
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/get-last-session-id *test-client*)
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all :tools [tool]})
          wire-tool (first (:tools (get @seen "session.resume")))]
      (is (some? wire-tool) "tool should be present in resume wire payload")
      (is (= "auto" (:defer wire-tool))
          "defer must be sent on session.resume too"))))

(deftest test-defer-spec
  (testing "::tool accepts :defer :auto and :never"
    (is (s/valid? ::specs/tool {:tool-name "t" :defer :auto}))
    (is (s/valid? ::specs/tool {:tool-name "t" :defer :never})))
  (testing "::tool rejects an invalid or non-keyword :defer"
    (is (not (s/valid? ::specs/tool {:tool-name "t" :defer "auto"}))
        "wire string is not a valid idiom value")
    (is (not (s/valid? ::specs/tool {:tool-name "t" :defer :bogus}))
        ":bogus is not a member of #{:auto :never}")))
