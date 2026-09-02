(ns github.copilot-sdk.integration.schema-provider-test
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

(deftest test-upstream-1-0-83-session-config-forwarding
  (testing "new session options preserve exact create and resume wire shapes"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"session.create" "session.resume"} method)
                                        (swap! seen assoc method params))))
          config {:on-permission-request sdk/approve-all
                  :ask-user-variant :elicitation
                  :capi {:auto-tier :intelligence}
                  :feature-flags {"fusion" true}}
          session (sdk/create-session *test-client* config)
          _ (sdk/resume-session *test-client* (sdk/session-id session) config)]
      (doseq [method ["session.create" "session.resume"]]
        (let [params (get @seen method)]
          (is (= "elicitation" (:askUserVariant params)))
          (is (= {:autoTier "intelligence"} (:capi params)))
          (is (= {:fusion true} (:featureFlags params)))))))

  (testing "omitted options remain absent from create and resume"
    (let [create-params (@#'client/build-create-session-params
                         {:on-permission-request sdk/approve-all})
          resume-params (@#'client/build-resume-session-params
                         "session" {:on-permission-request sdk/approve-all})]
      (doseq [params [create-params resume-params]]
        (is (not (contains? params :ask-user-variant)))
        (is (not (contains? params :capi)))
        (is (not (contains? params :feature-flags))))))

  (testing "an explicitly empty feature flag map remains distinct from omission"
    (doseq [params [(@#'client/build-create-session-params
                     {:on-permission-request sdk/approve-all
                      :feature-flags {}})
                    (@#'client/build-resume-session-params
                     "session"
                     {:on-permission-request sdk/approve-all
                      :feature-flags {}})]]
      (is (= {} (:feature-flags params)))))

  (testing "new options are valid for create, resume, and join configurations"
    (let [config {:on-permission-request sdk/approve-all
                  :ask-user-variant :legacy
                  :capi {:auto-tier :balance}
                  :feature-flags {"fusion" true}
                  :included-builtin-skills ["search"]
                  :github-token-provider (fn [_] {:kind :cancelled})}]
      (doseq [spec [::specs/session-config
                    ::specs/resume-session-config
                    ::specs/join-session-config]]
        (is (s/valid? spec config) (s/explain-str spec config))))))

(deftest test-included-builtin-skills-spec
  (testing "built-in skill inclusion mirrors the unrestricted upstream string array"
    (doseq [skills [(list "search" "edit")
                    #{"search" "edit"}
                    ["" " "]]]
      (is (s/valid? ::specs/included-builtin-skills skills)))
    (doseq [skills [[:search] "search"]]
      (is (not (s/valid? ::specs/included-builtin-skills skills))))))

(deftest test-upstream-1-0-79-event-schema
  (testing "generated and curated surfaces include new public events"
    (doseq [[wire-type idiom-type]
            [["session.context_cleared" :copilot/session.context_cleared]
             ["factory.run_updated" :copilot/factory.run_updated]]]
      (is (contains? generated-events/event-types wire-type))
      (is (contains? sdk/event-types idiom-type))
      (is (s/get-spec (keyword "github.copilot-sdk.generated.event-specs" wire-type)))
      (is (s/valid? ::specs/event-type idiom-type)))))

(deftest test-schema-1-0-52-4-mcp-app-tool-call-complete-event-type
  (testing "mcp_app.tool_call_complete is part of the public ::sdk/event-types set (SEP-1865)"
    (is (contains? sdk/event-types :copilot/mcp_app.tool_call_complete)))
  (testing "mcp_app.tool_call_complete is accepted by the idiom ::specs/event-type spec"
    (is (s/valid? :github.copilot-sdk.specs/event-type :copilot/mcp_app.tool_call_complete))))

(deftest test-schema-1-0-52-4-mcp-app-tool-call-complete-opaque-fields
  (testing "mcp_app.tool_call_complete :arguments and :result preserve source-defined keys verbatim"
    ;; Per upstream schema 1.0.52-4, the MCP App view supplies opaque
    ;; tool arguments and the MCP server returns a standard CallToolResult.
    ;; Both must survive normalize-incoming without csk kebab-casing.
    (let [normalize @#'protocol/normalize-incoming
          raw-msg {:jsonrpc "2.0"
                   :method "session.event"
                   :params {:sessionId "abc"
                            :event {:type "mcp_app.tool_call_complete"
                                    :id "evt-1"
                                    :timestamp "2026-05-23T08:00:00.000Z"
                                    :parentId nil
                                    :ephemeral true
                                    :data {:serverName "demo"
                                           :toolName "doThing"
                                           :durationMs 42
                                           :success true
                                           :arguments {:firstName "Foo"
                                                       :nested {:userId 42}}
                                           :result {:isError false
                                                    :content [{:type "text"
                                                               :text "ok"}]
                                                    :customField "preserve"}}}}}
          normalized (normalize raw-msg)
          data (get-in normalized [:params :event :data])]
      (is (= "mcp_app.tool_call_complete" (get-in normalized [:params :event :type])))
      (is (= "demo" (:server-name data))
          "non-opaque fields are kebab-cased")
      (is (= 42 (:duration-ms data)))
      (is (contains? (:arguments data) :firstName)
          ":arguments must preserve camelCase keys verbatim")
      (is (= 42 (get-in data [:arguments :nested :userId]))
          ":arguments nested keys must survive csk")
      (is (contains? (:result data) :isError)
          ":result must preserve camelCase keys verbatim")
      (is (= "preserve" (get-in data [:result :customField]))
          ":result must preserve user-defined keys"))))

(deftest test-schema-1-0-52-4-service-request-id
  (testing "::service-request-id field is propagated through wire->clj on relevant event data specs"
    ;; Upstream schema 1.0.52-4 adds optional `serviceRequestId` (the
    ;; Copilot CAPI x-copilot-service-request-id header) to several
    ;; event-data shapes for correlation with CAPI logs. The generated
    ;; specs accept it via `:opt-un`; we verify roundtrip through
    ;; normalize-incoming.
    (doseq [spec-key [:github.copilot-sdk.generated.event-specs/assistant.message-data
                      :github.copilot-sdk.generated.event-specs/assistant.usage-data
                      :github.copilot-sdk.generated.event-specs/model.call_failure-data
                      :github.copilot-sdk.generated.event-specs/session.compaction_complete-data
                      :github.copilot-sdk.generated.event-specs/session.error-data]]
      (is (some? (s/get-spec spec-key)) (str spec-key " should exist")))
    (let [normalize @#'protocol/normalize-incoming
          raw-msg {:jsonrpc "2.0"
                   :method "session.event"
                   :params {:sessionId "abc"
                            :event {:type "assistant.usage"
                                    :id "evt-2"
                                    :timestamp "2026-05-23T08:00:00.000Z"
                                    :parentId nil
                                    :data {:model "gpt-5"
                                           :serviceRequestId "svc-req-abc"}}}}
          data (get-in (normalize raw-msg) [:params :event :data])]
      (is (= "svc-req-abc" (:service-request-id data))
          "serviceRequestId must arrive as :service-request-id"))))

(deftest test-schema-1-0-52-4-model-change-context-tier
  (testing "session.model_change accepts :context-tier (default | long_context | nil)"
    ;; Upstream schema 1.0.52-4 adds optional :context-tier to ModelChangeData.
    ;; A literal `null` explicitly clears a previously-selected tier.
    (let [spec :github.copilot-sdk.generated.event-specs/session.model_change-data]
      (is (s/valid? spec {:new-model "gpt-5" :context-tier "default"}))
      (is (s/valid? spec {:new-model "gpt-5" :context-tier "long_context"}))
      (is (s/valid? spec {:new-model "gpt-5" :context-tier nil}))
      (is (s/valid? spec {:new-model "gpt-5"}))
      (is (not (s/valid? spec {:new-model "gpt-5" :context-tier "tiny"}))))))

(deftest test-schema-1-0-52-4-skill-invoked-source-trigger
  (testing "skill.invoked accepts :source and :trigger"
    (let [spec :github.copilot-sdk.generated.event-specs/skill.invoked-data]
      (is (s/valid? spec {:name "foo" :path "/x" :content "..."}))
      (is (s/valid? spec {:name "foo" :path "/x" :content "..."
                          :source "project"
                          :trigger "user-invoked"}))
      (is (s/valid? spec {:name "foo" :path "/x" :content "..."
                          :trigger "agent-invoked"}))
      (is (s/valid? spec {:name "foo" :path "/x" :content "..."
                          :trigger "context-load"}))
      (is (not (s/valid? spec {:name "foo" :path "/x" :content "..."
                               :trigger "bogus"}))))))

(deftest test-schema-1-0-52-4-runtime-instructions-section
  (testing ":runtime-instructions is a known system message section (upstream PR #1377)"
    (is (contains? (set (keys specs/system-prompt-sections)) :runtime-instructions))
    (is (= "runtime_instructions" (util/section-kw->wire-id :runtime-instructions))
        ":runtime-instructions converts to the wire string \"runtime_instructions\"")
    (is (s/valid? :github.copilot-sdk.specs/system-prompt-section :runtime-instructions))
    (is (s/valid? :github.copilot-sdk.specs/system-message-section :runtime-instructions)
        "::system-message-section alias also accepts it"))
  (testing "system-message-sections alias points at the same map (upstream rename)"
    (is (identical? specs/system-prompt-sections specs/system-message-sections))))

(deftest test-schema-1-0-52-4-runtime-instructions-wire-roundtrip
  (testing ":runtime-instructions section survives the create-session wire conversion"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"session.create"} method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :system-message {:mode :customize
                                                  :sections
                                                  {:runtime-instructions
                                                   {:action :replace
                                                    :content "runtime ctx"}}}})
          wire (get-in @seen ["session.create" :systemMessage :sections])]
      (is (contains? wire :runtime_instructions)
          ":runtime-instructions must be sent as wire key :runtime_instructions")
      (is (= "replace" (get-in wire [:runtime_instructions :action])))
      (is (= "runtime ctx" (get-in wire [:runtime_instructions :content]))))))

(deftest test-v1-0-4-preamble-section
  (testing ":preamble is a known system message section (upstream PR #1683)"
    (is (contains? (set (keys specs/system-prompt-sections)) :preamble))
    (is (= "preamble" (util/section-kw->wire-id :preamble))
        ":preamble converts to the wire string \"preamble\"")
    (is (= :preamble (util/wire-id->section-kw "preamble"))
        "\"preamble\" round-trips back to :preamble")
    (is (s/valid? :github.copilot-sdk.specs/system-prompt-section :preamble))
    (is (s/valid? :github.copilot-sdk.specs/system-message-section :preamble)
        "::system-message-section alias also accepts it")))

(deftest test-v1-0-4-preamble-section-wire-roundtrip
  (testing ":preamble section survives the create-session wire conversion"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"session.create"} method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :system-message {:mode :customize
                                                  :sections
                                                  {:preamble
                                                   {:action :replace
                                                    :content "you are an agent"}}}})
          wire (get-in @seen ["session.create" :systemMessage :sections])]
      (is (contains? wire :preamble)
          ":preamble must be sent as wire key :preamble")
      (is (= "replace" (get-in wire [:preamble :action])))
      (is (= "you are an agent" (get-in wire [:preamble :content]))))))

(deftest test-v1-0-4-preserve-section-action
  (testing ":preserve is a valid static section action (upstream PR #1713)"
    (is (s/valid? :github.copilot-sdk.specs/section-action :preserve)
        ":preserve must validate as a static section action")
    ;; :preserve is a no-op marker — content is NOT required (unlike replace/append/prepend)
    (is (s/valid? :github.copilot-sdk.specs/section-override {:action :preserve})
        ":preserve override needs no :content")
    ;; ...and it carries no content: a content-bearing :preserve/:remove is a
    ;; caller mistake the spec must reject (upstream PR #1713 — these actions
    ;; have no content payload).
    (is (false? (s/valid? :github.copilot-sdk.specs/section-override
                          {:action :preserve :content "x"}))
        ":preserve must reject :content")
    (is (false? (s/valid? :github.copilot-sdk.specs/section-override
                          {:action :remove :content "x"}))
        ":remove must reject :content"))
  (testing ":preserve action survives the create-session wire conversion"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"session.create"} method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :system-message {:mode :customize
                                                  :sections
                                                  {:identity {:action :remove}
                                                   :tone {:action :preserve}}}})
          wire (get-in @seen ["session.create" :systemMessage :sections])]
      (is (= "preserve" (get-in wire [:tone :action]))
          ":preserve must be sent as the wire action string \"preserve\"")
      (is (not (contains? (get wire :tone) :content))
          ":preserve emits no :content key"))))

(deftest test-v1-0-4-capi-enable-websocket-responses-wire
  (testing ":capi {:enable-web-socket-responses ...} forwards on both session.create and session.resume (upstream PR #1711)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"session.create" "session.resume"} method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :capi {:enable-web-socket-responses false}})
          session-id (sdk/get-last-session-id *test-client*)
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :capi {:enable-web-socket-responses false}})
          create-params (get @seen "session.create")
          resume-params (get @seen "session.resume")]
      (is (= {:enableWebSocketResponses false} (:capi create-params))
          ":capi must be sent verbatim under wire key :capi with camelCase :enableWebSocketResponses on create")
      (is (= {:enableWebSocketResponses false} (:capi resume-params))
          ":capi must also forward on resume")))
  (testing ":capi is accepted by the session-config spec"
    (is (s/valid? :github.copilot-sdk.specs/capi {:enable-web-socket-responses true}))
    (is (s/valid? :github.copilot-sdk.specs/capi {})
        "empty :capi map is valid (field is optional)")))

(deftest test-v1-0-5-new-session-options-wire
  (testing ":excluded-builtin-agents, :enable-citations, :session-limits forward on both session.create and session.resume (upstream PR #1865)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"session.create" "session.resume"} method)
                                        (swap! seen assoc method params))))
          opts {:excluded-builtin-agents ["planner" "reviewer"]
                :enable-citations true
                :session-limits {:max-ai-credits 500}}
          _ (sdk/create-session *test-client*
                                (merge {:on-permission-request sdk/approve-all} opts))
          session-id (sdk/get-last-session-id *test-client*)
          _ (sdk/resume-session *test-client* session-id
                                (merge {:on-permission-request sdk/approve-all} opts))
          create-params (get @seen "session.create")
          resume-params (get @seen "session.resume")]
      (is (= ["planner" "reviewer"] (:excludedBuiltinAgents create-params))
          ":excluded-builtin-agents must forward under camelCase :excludedBuiltinAgents on create")
      (is (= ["planner" "reviewer"] (:excludedBuiltinAgents resume-params))
          ":excluded-builtin-agents must also forward on resume")
      (is (true? (:enableCitations create-params))
          ":enable-citations must forward as :enableCitations on create")
      (is (true? (:enableCitations resume-params))
          ":enable-citations must also forward on resume")
      (is (= {:maxAiCredits 500} (:sessionLimits create-params))
          ":session-limits {:max-ai-credits n} must forward as {:maxAiCredits n} on create")
      (is (= {:maxAiCredits 500} (:sessionLimits resume-params))
          ":session-limits must also forward on resume")))
  (testing ":enable-citations is gated on some?, so an explicit false is still forwarded (not omitted)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"session.create" "session.resume"} method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :enable-citations false})
          session-id (sdk/get-last-session-id *test-client*)
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :enable-citations false})
          create-params (get @seen "session.create")
          resume-params (get @seen "session.resume")]
      (is (false? (:enableCitations create-params))
          "explicit :enable-citations false must forward as :enableCitations false on create")
      (is (false? (:enableCitations resume-params))
          "explicit :enable-citations false must also forward on resume")))
  (testing "new options are accepted by the session-config, resume-session-config, and join-session-config specs"
    (let [opts {:excluded-builtin-agents ["a"]
                :enable-citations true
                :session-limits {:max-ai-credits 100}}]
      (is (s/valid? ::specs/session-config
                    (merge {:on-permission-request sdk/approve-all} opts)))
      (is (s/valid? ::specs/resume-session-config
                    (merge {:on-permission-request sdk/approve-all} opts)))
      (is (s/valid? ::specs/join-session-config
                    (merge {:on-permission-request sdk/approve-all} opts))))
    (is (s/valid? ::specs/session-limits {:max-ai-credits 100}))
    (is (s/valid? ::specs/session-limits {})
        "empty :session-limits map is valid (:max-ai-credits is optional)")
    (is (not (s/valid? ::specs/session-limits {:max-ai-credits 0}))
        ":max-ai-credits must be positive (wire exclusiveMinimum 0)")
    (is (not (s/valid? ::specs/session-limits {:max-ai-credits -5}))
        ":max-ai-credits rejects negative values")))

(deftest test-v1-0-5-session-limits-events
  (testing "session.response_limits_changed renamed to session.session_limits_changed (upstream schema 1.0.67)"
    (is (contains? sdk/event-types :copilot/session.session_limits_changed)
        "renamed event must be in the master event-types set")
    (is (contains? sdk/session-events :copilot/session.session_limits_changed)
        "renamed event must be in the session-events set")
    (is (not (contains? sdk/event-types :copilot/session.response_limits_changed))
        "the old event name must be gone from the public sets"))
  (testing "new usage_checkpoint and session_limits_exhausted events are public (upstream schema 1.0.67)"
    (is (contains? sdk/event-types :copilot/session.usage_checkpoint))
    (is (contains? sdk/session-events :copilot/session.usage_checkpoint))
    (is (contains? sdk/event-types :copilot/session_limits_exhausted.requested))
    (is (contains? sdk/event-types :copilot/session_limits_exhausted.completed))
    (is (contains? sdk/interaction-events :copilot/session_limits_exhausted.requested))
    (is (contains? sdk/interaction-events :copilot/session_limits_exhausted.completed))))

(deftest test-v1-0-7-preview-new-events
  (testing "assistant.tool_call_delta is a public assistant event (upstream schema 1.0.69-3)"
    (is (contains? sdk/event-types :copilot/assistant.tool_call_delta)
        "must be in the master event-types set")
    (is (contains? sdk/assistant-events :copilot/assistant.tool_call_delta)
        "must be categorized under assistant-events"))
  (testing "mcp list_changed events are public MCP interaction events (upstream schema 1.0.70)"
    (doseq [ev [:copilot/mcp.tools.list_changed
                :copilot/mcp.resources.list_changed
                :copilot/mcp.prompts.list_changed]]
      (is (contains? sdk/event-types ev)
          (str ev " must be in the master event-types set"))
      (is (contains? sdk/interaction-events ev)
          (str ev " must be categorized under interaction-events"))))
  (testing "session.auto_mode_resolved is a public session event (upstream schema 1.0.70-0)"
    (is (contains? sdk/event-types :copilot/session.auto_mode_resolved)
        "must be in the master event-types set")
    (is (contains? sdk/session-events :copilot/session.auto_mode_resolved)
        "must be categorized under session-events"))
  (testing "new event types validate against the idiom ::event-type enum"
    (doseq [ev [:copilot/assistant.tool_call_delta
                :copilot/mcp.tools.list_changed
                :copilot/mcp.resources.list_changed
                :copilot/mcp.prompts.list_changed
                :copilot/session.auto_mode_resolved]]
      (is (s/valid? ::specs/event-type ev)
          (str ev " must be accepted by the idiom ::event-type spec")))))

(deftest test-post-v1-0-7-schema-events
  (let [generated-events #{"assistant.server_tool_progress"
                           "assistant.turn_retry"
                           "model.call_start"
                           "session.managed_settings_enforced"
                           "session.managed_settings_resolved"
                           "tool_search.activated"}
        public-events #{:copilot/assistant.server_tool_progress
                        :copilot/session.managed_settings_enforced
                        :copilot/session.managed_settings_resolved
                        :copilot/tool_search.activated}
        internal-events #{:copilot/assistant.turn_retry
                          :copilot/model.call_start}]
    (testing "schema 1.0.73 generates all new wire event specs"
      (doseq [event-type generated-events]
        (is (contains? github.copilot-sdk.generated.event-specs/event-types event-type)
            (str event-type " must be generated from the pinned schema"))
        (is (s/get-spec (keyword "github.copilot-sdk.generated.event-specs"
                                 (str event-type "-data")))
            (str event-type " must have a generated data spec"))))
    (testing "only upstream-public events enter the curated idiom surface"
      (doseq [event-type public-events]
        (is (contains? sdk/event-types event-type)
            (str event-type " must be public"))
        (is (s/valid? ::specs/event-type event-type)
            (str event-type " must satisfy the idiom event-type spec")))
      (doseq [event-type internal-events]
        (is (not (contains? sdk/event-types event-type))
            (str event-type " is marked internal upstream"))
        (is (not (s/valid? ::specs/event-type event-type))
            (str event-type " must stay outside the public idiom spec"))))
    (testing "public events are categorized by their SDK domain"
      (is (contains? sdk/assistant-events :copilot/assistant.server_tool_progress))
      (is (contains? sdk/session-events :copilot/session.managed_settings_enforced))
      (is (contains? sdk/session-events :copilot/session.managed_settings_resolved)))))

(deftest test-v1-0-83-event-classification
  (let [stable-events #{:copilot/session.mode_notice_delivered
                        :copilot/model.call_finished
                        :copilot/subagent.configured}
        internal-events #{:copilot/session.fusion_handoff
                          :copilot/session.fusion_commit_started}
        experimental-events #{:copilot/assistant.fusion_phase_started
                              :copilot/assistant.fusion_phase_completed
                              :copilot/assistant.fusion_phase_failed
                              :copilot/session.fusion_route_started
                              :copilot/session.fusion_route_failed
                              :copilot/session.fusion_resolved
                              :copilot/session.fusion_completed}]
    (testing "all schema events have generated wire specs"
      (doseq [event-type (into stable-events (into internal-events experimental-events))
              :let [wire-type (name event-type)]]
        (is (contains? github.copilot-sdk.generated.event-specs/event-types wire-type)
            (str wire-type " must be generated from the pinned schema"))
        (is (s/get-spec (keyword "github.copilot-sdk.generated.event-specs"
                                 (str wire-type "-data")))
            (str wire-type " must have a generated data spec"))))
    (testing "stable events enter the curated idiom surface"
      (doseq [event-type stable-events]
        (is (contains? sdk/event-types event-type)
            (str event-type " must be public"))
        (is (s/valid? ::specs/event-type event-type)
            (str event-type " must satisfy the idiom event-type spec")))
      (is (contains? sdk/session-events :copilot/session.mode_notice_delivered)))
    (testing "non-public HydraFusion events remain generated-only"
      (doseq [[event-type classification]
              (concat (map #(vector % "internal") internal-events)
                      (map #(vector % "experimental") experimental-events))]
        (is (not (contains? sdk/event-types event-type))
            (str event-type " is " classification " upstream"))
        (is (not (s/valid? ::specs/event-type event-type))
            (str event-type " must stay outside the public idiom spec"))))
    (testing "stable payloads have open, typed idiom specs"
      (is (s/valid? ::specs/session.mode_notice_delivered-data
                    {:mode "autopilot"
                     :content "Continue autonomously."
                     :future-field true}))
      (is (s/valid? ::specs/model.call_finished-data
                    {:turn-id "turn-1"
                     :interaction-id "interaction-1"
                     :dispatch-duration-ms 12.5
                     :outcome "success"
                     :contains-built-in-file-edit-request true
                     :edit-classifier-version 1
                     :future-field true}))
      (is (s/valid? ::specs/subagent.configured-data
                    {:model "gpt-5.4"
                     :reasoning-effort "high"
                     :context-tier "long_context"
                     :multi-turn true
                     :future-field true}))
      (is (not (s/valid? ::specs/session.mode_notice_delivered-data
                         {:content "Missing the required mode."})))
      (is (not (s/valid? ::specs/model.call_finished-data
                         {:turn-id "turn-1"
                          :dispatch-duration-ms -1
                          :outcome "success"
                          :edit-classifier-version 1})))
      (is (not (s/valid? ::specs/subagent.configured-data
                         {:model "gpt-5.4"
                          :multi-turn "true"}))))))

(deftest test-v1-0-4-provider-transport-wire
  (testing ":provider :transport forwards on both session.create and session.resume (upstream PR #1711)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"session.create" "session.resume"} method)
                                        (swap! seen assoc method params))))
          provider {:provider-type :openai
                    :wire-api :responses
                    :base-url "https://example.test"
                    :api-key "key"
                    :transport :websockets}
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
            (is (= "websockets" (:transport p))
                ":transport keyword value must serialize to its wire string"))))))
  (testing "::transport enum is enforced on ::provider"
    (is (s/valid? :github.copilot-sdk.specs/transport :http))
    (is (s/valid? :github.copilot-sdk.specs/transport :websockets))
    (is (false? (s/valid? :github.copilot-sdk.specs/transport :bogus)))
    (is (s/valid? :github.copilot-sdk.specs/provider
                  {:base-url "https://example.test" :transport :http}))
    (is (false? (s/valid? :github.copilot-sdk.specs/provider
                          {:base-url "https://example.test" :transport :bogus}))
        "an invalid :transport value must fail provider validation")))

(deftest test-v1-0-4-multi-provider-byok-registry-wire
  (testing ":providers/:models forward on both session.create and session.resume (upstream PR #1718)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"session.create" "session.resume"} method)
                                        (swap! seen assoc method params))))
          providers [{:name "my-openai"
                      :provider-type :openai
                      :wire-api :responses
                      :base-url "https://oai.test"
                      :api-key "k1"
                      :headers {"X-Org" "acme"}}
                     {:name "my-azure"
                      :provider-type :azure
                      :base-url "https://azure.test"
                      :api-key "k2"
                      :azure-options {:azure-api-version "2024-06-01"}}]
          models [{:id "gpt-4o"
                   :provider "my-openai"
                   :model-id "gpt-4o"
                   :name "GPT-4o"
                   :wire-model "gpt-4o-2024"
                   :max-input-tokens 128000
                   :max-context-window-tokens 200000
                   :max-output-tokens 16000
                   :capabilities {"reasoningEffort" "high"
                                  "max_prompt_tokens" 120000
                                  "supported_media_types" ["image/png"]}}]
          cfg {:on-permission-request sdk/approve-all
               :model "fallback-model"
               :providers providers
               :models models}
          _ (sdk/create-session *test-client* cfg)
          session-id (sdk/get-last-session-id *test-client*)
          _ (sdk/resume-session *test-client* session-id cfg)]
      (doseq [method ["session.create" "session.resume"]]
        (testing method
          (let [params (get @seen method)
                wprov (:providers params)
                wmodels (:models params)
                oai (first wprov)
                azure (second wprov)
                m (first wmodels)]
            (is (= 2 (count wprov)) "both named providers are forwarded")
            ;; named provider wire shape
            (is (= "my-openai" (:name oai)))
            (is (= "openai" (:type oai)) ":provider-type renames to wire :type")
            (is (= "responses" (:wireApi oai)))
            (is (= "https://oai.test" (:baseUrl oai)))
            (is (= "k1" (:apiKey oai)))
            (is (= {:X-Org "acme"} (:headers oai)))
            (is (not (contains? oai :providerType)) "no :providerType leaks onto the wire")
            ;; azure nested rename
            (is (= "azure" (:type azure)))
            (is (= {:apiVersion "2024-06-01"} (:azure azure))
                ":azure-options -> :azure with nested :azure-api-version -> :apiVersion")
            ;; provider-model wire shape
            (is (= "gpt-4o" (:id m)))
            (is (= "my-openai" (:provider m)))
            (is (= "gpt-4o" (:modelId m)))
            (is (= "GPT-4o" (:name m)))
            (is (= "gpt-4o-2024" (:wireModel m)))
            (is (= 128000 (:maxPromptTokens m)) ":max-input-tokens -> wire :maxPromptTokens")
            (is (= 200000 (:maxContextWindowTokens m)))
            (is (= 16000 (:maxOutputTokens m)))
            (is (not (contains? m :maxInputTokens)) "no :maxInputTokens leaks onto the wire")
            ;; capabilities passthrough: opaque string keys survive unmangled
            (is (= "high" (get-in m [:capabilities :reasoningEffort])))
            (is (= 120000 (get-in m [:capabilities :max_prompt_tokens]))
                ":capabilities is opaque — snake_case keys must NOT be camelCased")
            (is (= ["image/png"] (get-in m [:capabilities :supported_media_types]))))))))
  (testing "specs accept the multi-provider registry shapes"
    (is (s/valid? :github.copilot-sdk.specs/named-provider
                  {:name "p" :base-url "https://x.test"}))
    (is (false? (s/valid? :github.copilot-sdk.specs/named-provider
                          {:base-url "https://x.test"}))
        ":name is required on a named provider")
    (is (false? (s/valid? :github.copilot-sdk.specs/named-provider
                          {:name "has/slash" :base-url "https://x.test"}))
        "named provider :name must not contain '/'")
    ;; A named provider carries no transport or inline model-override fields
    ;; (upstream NamedProviderConfig, PR #1718) — those belong on the singular
    ;; ::provider / ::provider-model. The spec must reject them so misuse fails
    ;; fast at validate-session-config! instead of silently forwarding on the wire.
    (is (false? (s/valid? :github.copilot-sdk.specs/named-provider
                          {:name "p" :base-url "https://x.test" :transport :http}))
        ":transport is not a named-provider field")
    (is (false? (s/valid? :github.copilot-sdk.specs/named-provider
                          {:name "p" :base-url "https://x.test" :model-id "gpt-4o"}))
        ":model-id is not a named-provider field")
    (is (false? (s/valid? :github.copilot-sdk.specs/named-provider
                          {:name "p" :base-url "https://x.test" :max-input-tokens 1000}))
        "model-override token limits are not named-provider fields")
    (is (s/valid? :github.copilot-sdk.specs/provider-model
                  {:id "m" :provider "p"}))
    (is (false? (s/valid? :github.copilot-sdk.specs/provider-model
                          {:provider "p"}))
        ":id is required on a provider model")
    (is (false? (s/valid? :github.copilot-sdk.specs/provider-model
                          {:id "m"}))
        ":provider is required on a provider model")
    (is (false? (s/valid? :github.copilot-sdk.specs/provider-model
                          {:id "m" :provider "p" :max-input-tokens 0}))
        "token overrides must be positive")
    (is (s/valid? :github.copilot-sdk.specs/providers
                  [{:name "p" :base-url "https://x.test"}]))
    (is (s/valid? :github.copilot-sdk.specs/models
                  [{:id "m" :provider "p"}]))
    (is (s/valid? :github.copilot-sdk.specs/session-config
                  {:providers [{:name "p" :base-url "https://x.test"}]
                   :models [{:id "m" :provider "p"}]}))))

(deftest test-post-v1-0-7-exp-assignments-wire
  (let [exp {"Features" ["feature-x"]
             "Flights" {"flight-abc" "treatment"}
             "Configs" [{"Id" "config-a"
                         "Parameters" {"enabled" true
                                       "threshold" 0.5
                                       "optional" nil}}]
             "ParameterGroups" {"group-a" ["config-a"]}
             "FlightingVersion" 7
             "ImpressionId" "impression-1"
             "AssignmentContext" "assignment-context"}]
    (testing ":exp-assignments forwards its PascalCase contract unchanged on create and resume"
      (let [seen (atom {})
            _ (mock/set-request-hook! *mock-server*
                                      (fn [method params]
                                        (when (#{"session.create" "session.resume"} method)
                                          (swap! seen assoc method params))))
            cfg {:on-permission-request sdk/approve-all
                 :exp-assignments exp}
            _ (sdk/create-session *test-client* cfg)
            session-id (sdk/get-last-session-id *test-client*)
            _ (sdk/resume-session *test-client* session-id cfg)
            expected {:Features ["feature-x"]
                      :Flights {:flight-abc "treatment"}
                      :Configs [{:Id "config-a"
                                 :Parameters {:enabled true
                                              :threshold 0.5
                                              :optional nil}}]
                      :ParameterGroups {:group-a ["config-a"]}
                      :FlightingVersion 7
                      :ImpressionId "impression-1"
                      :AssignmentContext "assignment-context"}]
        (doseq [method ["session.create" "session.resume"]]
          (testing method
            (is (= expected (:expAssignments (get @seen method)))
                "PascalCase field names must bypass kebab-to-camel conversion")))))
    (testing "::exp-assignments enforces CopilotExpAssignmentResponse (upstream PR #2033)"
      (is (s/valid? ::specs/exp-assignments exp))
      (is (s/valid? ::specs/session-config {:exp-assignments exp}))
      (doseq [required-field ["Features" "Flights" "Configs" "AssignmentContext"]]
        (is (not (s/valid? ::specs/exp-assignments (dissoc exp required-field)))
            (str required-field " is required")))
      (is (not (s/valid? ::specs/exp-assignments
                         (assoc exp "Configs" [{"Parameters" {}}])))
          "config Id is required")
      (is (not (s/valid? ::specs/exp-assignments
                         (assoc exp "Configs" [{"Id" "config-a"}])))
          "config Parameters are required")
      (is (not (s/valid? ::specs/exp-assignments
                         (assoc-in exp ["Configs" 0 "Parameters" "bad"] [])))
          "flag values are limited to string, number, boolean, or nil")
      (is (not (s/valid? ::specs/exp-assignments {"flight-abc" "treatment"}))
          "the former arbitrary flat-map contract is no longer valid"))))

(deftest test-v1-0-4-provider-and-providers-mutually-exclusive
  (testing "combining singular :provider with the :providers registry is rejected on both create and resume (upstream ProviderTokenArgs/SessionConfig contract, PR #1718)"
    ;; Upstream documents that combining `providers`/`models` with the singular
    ;; `provider` is rejected; the SDK forwards both to the runtime which rejects
    ;; the combination. We fail fast client-side with a clear message — a
    ;; Clojure-only convenience that never alters the wire for any valid config
    ;; (the combination is invalid everywhere).
    (let [cfg {:on-permission-request sdk/approve-all
               :model "m"
               :provider {:base-url "https://single.test"}
               :providers [{:name "p" :base-url "https://registry.test"}]}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"(?i):provider.*cannot be combined.*:providers"
                            (sdk/create-session *test-client* cfg))
          "create-session rejects :provider + :providers")
      (let [ok-session (sdk/create-session *test-client*
                                           {:on-permission-request sdk/approve-all
                                            :model "m"})
            session-id (sdk/session-id ok-session)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"(?i):provider.*cannot be combined.*:providers"
                              (sdk/resume-session *test-client* session-id cfg))
            "resume-session rejects :provider + :providers")))))

(deftest test-session-github-token-provider-static-conflict
  (testing "all config specs and synchronous/asynchronous entry points reject conflicting token sources before RPC"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method _]
                                      (when (str/starts-with? method "session.")
                                        (swap! requests conj method))))
          config {:on-permission-request sdk/approve-all
                  :github-token "static"
                  :github-token-provider (fn [_] {:kind :cancelled})}]
      (doseq [spec [::specs/session-config
                    ::specs/resume-session-config
                    ::specs/join-session-config]]
        (is (not (s/valid? spec config))))
      (doseq [invoke [#(sdk/create-session *test-client* config)
                      #(sdk/<create-session *test-client* config)
                      #(sdk/resume-session *test-client* "conflict" config)
                      #(sdk/<resume-session *test-client* "conflict" config)
                      #(sdk/join-session config)]]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"mutually exclusive"
                              (invoke))))
      (is (empty? @requests)))))

(deftest test-session-github-token-provider-transport-security
  (testing "external non-loopback TCP rejects credential callbacks before session RPCs"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method _]
                                      (when (str/starts-with? method "session.")
                                        (swap! requests conj method))))
          client (sdk/client {:cli-url "example.com:4444"
                              :auto-start? false})
          config {:session-id "remote-token-provider"
                  :on-permission-request sdk/approve-all
                  :github-token-provider (fn [_] {:kind :cancelled})}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"loopback"
           (sdk/create-session client config)))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"loopback"
           (sdk/<create-session client config)))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"loopback"
           (sdk/resume-session
            client "remote-token-provider" (dissoc config :session-id))))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"loopback"
           (sdk/<resume-session
            client "remote-token-provider" (dissoc config :session-id))))
      (is (empty? @requests))
      (is (empty? (:github-token-providers @(:state client))))
      (sdk/stop! client)))

  (testing "syntactic loopback transports are accepted, including IPv6 literals"
    (doseq [url ["localhost:4444"
                 "127.0.0.1:4444"
                 "127.255.2.3:4444"
                 "[::1]:4444"
                 "[0:0:0:0:0:0:0:1]:4444"
                 "https://localhost:4444"]]
      (let [client (sdk/client {:cli-url url :auto-start? false})]
        (is (nil?
             (@#'client/ensure-github-token-provider-transport!
              client
              {:github-token-provider (fn [_] {:kind :cancelled})}))
            url))))

  (testing "SDK-owned and child-process stdio transports are accepted"
    (doseq [client [(sdk/client {:auto-start? false})
                    (sdk/client {:is-child-process? true :auto-start? false})]]
      (is (nil?
           (@#'client/ensure-github-token-provider-transport!
            client
            {:github-token-provider (fn [_] {:kind :cancelled})}))))))

(deftest test-session-github-token-provider-wire-and-callbacks
  (testing "only an opaque UUID-v4 registration is serialized and callbacks map token and cancellation results"
    (let [create-params (atom nil)
          observed (atom [])
          call-count (atom 0)
          provider (fn [args]
                     (swap! observed conj args)
                     (if (= 1 (swap! call-count inc))
                       {:kind :token
                        :access-token "secret-token"
                        :token-type "Bearer"
                        :expires-in 28800}
                       (async/to-chan! [{:kind :cancelled}])))
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (reset! create-params params))))
          session (sdk/create-session *test-client*
                                      {:session-id "session-one"
                                       :on-permission-request sdk/approve-all
                                       :github-token-provider provider})
          registration-id (:gitHubTokenProviderRegistrationId @create-params)]
      (is (= 4 (.version (java.util.UUID/fromString registration-id))))
      (is (not (contains? @create-params :githubTokenProvider)))
      (is (not (contains? @create-params :gitHubToken)))
      (is (= {:kind "token"
              :accessToken "secret-token"
              :tokenType "Bearer"
              :expiresIn 28800}
             (:result
              (mock/send-rpc-request! *mock-server*
                                      "gitHubToken.getToken"
                                      {:registrationId registration-id
                                       :host "github.example.com"
                                       :reason "initial"}))))
      (is (= {:kind "cancelled"}
             (:result
              (mock/send-rpc-request! *mock-server*
                                      "gitHubToken.getToken"
                                      {:registrationId registration-id
                                       :host "github.example.com"
                                       :sessionId (sdk/session-id session)
                                       :reason "refresh"}))))
      (is (= [{:host "github.example.com"
               :session-id "session-one"
               :reason :initial}
              {:host "github.example.com"
               :session-id "session-one"
               :reason :refresh}]
             @observed))
      (is (s/valid? ::specs/session-config (session/config session)))
      (is (not (contains? (session/config session)
                          :github-token-provider-registration-id)))
      (is (empty? (:github-token-provider-invocations
                   @(:state *test-client*)))))))

(deftest test-session-github-token-provider-cloud-session-assignment
  (testing "cloud creation assigns the server-generated session ID before later token requests"
    (let [observed (promise)
          create-params (atom nil)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (reset! create-params params))))
          session (sdk/create-session
                   *test-client*
                   {:cloud {}
                    :on-permission-request sdk/approve-all
                    :github-token-provider
                    (fn [args]
                      (deliver observed args)
                      {:kind :cancelled})})
          registration-id (:gitHubTokenProviderRegistrationId @create-params)
          _ (mock/send-rpc-request! *mock-server*
                                    "gitHubToken.getToken"
                                    {:registrationId registration-id
                                     :host "github.com"
                                     :reason "initial"})]
      (is (not (contains? @create-params :sessionId)))
      (is (= (sdk/session-id session)
             (:session-id (deref observed 1000 nil))))))

  (testing "cloud creation can request a token before assigning a session ID"
    (let [server *mock-server*
          provider-args (promise)
          token-response (promise)
          _ (mock/set-request-hook!
             server
             (fn [method params]
               (when (= "session.create" method)
                 (let [registration-id
                       (:gitHubTokenProviderRegistrationId params)]
                   (future
                     (deliver
                      token-response
                      (mock/send-rpc-request!
                       server
                       "gitHubToken.getToken"
                       {:registrationId registration-id
                        :host "github.com"
                        :reason "initial"})))
                   (when (= ::timeout
                            (deref provider-args 1000 ::timeout))
                     (throw (ex-info "provider was not called before session.create completed"
                                     {:code -32603})))))))
          session
          (sdk/create-session
           *test-client*
           {:cloud {}
            :on-permission-request sdk/approve-all
            :github-token-provider
            (fn [args]
              (deliver provider-args args)
              {:kind :cancelled})})]
      (is (= {:host "github.com" :reason :initial}
             (deref provider-args 1000 ::timeout)))
      (is (= {:kind "cancelled"}
             (:result (deref token-response 1000 ::timeout))))
      (is (some? (sdk/session-id session))))))

(deftest test-session-github-token-provider-errors-and-rollback
  (testing "callback failures are sanitized and unknown registrations remain descriptive"
    (let [secret "credential-broker-secret"
          failure (ex-info secret {:access-token "also-secret"})
          session (sdk/create-session
                   *test-client*
                   {:session-id "error-session"
                    :on-permission-request sdk/approve-all
                    :github-token-provider (fn [_] (throw failure))})
          registration-id (-> @(:state *test-client*)
                              :github-token-providers
                              keys
                              first)]
      (let [error (try
                    (mock/send-rpc-request! *mock-server*
                                            "gitHubToken.getToken"
                                            {:registrationId registration-id
                                             :host "github.com"
                                             :reason "initial"})
                    nil
                    (catch Throwable t t))]
        (is (= "Internal error: GitHub token provider callback failed"
               (ex-message error)))
        (is (not (str/includes? (pr-str (Throwable->map error)) secret))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"No GitHub token provider registered"
                            (mock/send-rpc-request! *mock-server*
                                                    "gitHubToken.getToken"
                                                    {:registrationId "unknown"
                                                     :host "github.com"
                                                     :reason "refresh"})))
      (is (= "error-session" (sdk/session-id session)))))

  (testing "thrown and returned Throwables are replaced at the provider boundary"
    (doseq [[label provider]
            [[:thrown-error
              (fn [_] (throw (AssertionError. "thrown-secret")))]
             [:returned-exception
              (fn [_] (ex-info "returned-secret" {:access-token "secret"}))]
             [:channel-error
              (fn [_] (async/to-chan! [(AssertionError. "channel-secret")]))]]]
      (let [client (sdk/client {:auto-start? false})
            registration-id
            (@#'client/register-github-token-provider!
             client provider (str "failure-" (name label)))
            response
            (<!!
             (@#'client/github-token-provider-response
              client
              {:registration-id registration-id
               :host "github.com"
               :reason "refresh"}))]
        (is (= -32603 (get-in response [:error :code])) (name label))
        (is (= "Internal error: GitHub token provider callback failed"
               (get-in response [:error :message]))
            (name label))
        (is (not (re-find #"(?i)(thrown|returned|channel)-secret|access-token"
                          (pr-str response)))
            (name label)))))

  (testing "malformed callback results expose only their failed constraint"
    (let [secret "invalid-result-secret"
          invalid-results
          [[(str secret "-non-map") :result-must-be-map]
           [{:kind :unknown
             :access-token secret}
            :kind-must-be-token-or-cancelled]
           [{:kind :token
             :access-token " "
             :expires-in 3601}
            :access-token-must-be-non-blank-string]
           [{:kind :token
             :access-token secret
             :expires-in 3600.5}
            :expires-in-must-be-integer]
           [{:kind :token
             :access-token secret
             :expires-in 3600}
            :expires-in-must-exceed-3600]
           [{:kind :token
             :access-token secret
             :token-type " "
             :expires-in 3601}
            :token-type-must-be-non-blank-string]]]
      (doseq [[result constraint] invalid-results]
        (let [client (sdk/client {:auto-start? false})
              registration-id
              (@#'client/register-github-token-provider!
               client (constantly result) "invalid-result")]
          (log-test/with-log
            (let [response
                  (<!!
                   (@#'client/github-token-provider-response
                    client
                    {:registration-id registration-id
                     :host "github.com"
                     :reason "initial"}))
                  log-output
                  (str/join "\n" (map :message (log-test/the-log)))]
              (is (= -32603 (get-in response [:error :code])))
              (is (= "Internal error: GitHub token provider returned an invalid result"
                     (get-in response [:error :message])))
              (is (not (str/includes? (pr-str response) secret)))
              (is (not (str/includes? log-output secret)))
              (is (str/includes? log-output (str constraint)))
              (is (str/includes? log-output registration-id))
              (is (str/includes? log-output "github.com"))
              (is (str/includes? log-output ":initial"))
              (is (empty? (:github-token-provider-invocations
                           @(:state client))))))))))

  (testing "callback failure logs only exception classes and request identity"
    (let [secret "provider-exception-secret"
          cause-secret "provider-cause-secret"
          client (sdk/client {:auto-start? false})
          registration-id
          (@#'client/register-github-token-provider!
           client
           (fn [_]
             (throw
              (ex-info secret {}
                       (RuntimeException. cause-secret))))
           "failure-log-session")]
      (log-test/with-log
        (let [response
              (<!!
               (@#'client/github-token-provider-response
                client
                {:registration-id registration-id
                 :host "github.example"
                 :reason "refresh"}))
              log-output (str/join "\n" (map :message (log-test/the-log)))]
          (is (= -32603 (get-in response [:error :code])))
          (is (not (str/includes? log-output secret)))
          (is (not (str/includes? log-output cause-secret)))
          (is (str/includes? log-output "clojure.lang.ExceptionInfo"))
          (is (str/includes? log-output "java.lang.RuntimeException"))
          (is (str/includes? log-output registration-id))
          (is (str/includes? log-output "failure-log-session"))
          (is (str/includes? log-output "github.example"))
          (is (str/includes? log-output ":refresh"))))))

  (testing "callback arguments are validated before invocation"
    (let [client (sdk/client {:auto-start? false})
          called? (atom false)
          registration-id
          (@#'client/register-github-token-provider!
           client
           (fn [_]
             (reset! called? true)
             {:kind :cancelled})
           "invalid-arguments")]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid GitHub token provider request"
           (@#'client/github-token-provider-response
            client
            {:registration-id registration-id
             :host ""
             :reason "unknown"})))
      (is (false? @called?))
      (is (not (s/valid? ::specs/github-token-provider-args
                         {:host "github.com"
                          :session-id nil
                          :reason :initial})))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid GitHub token provider request"
           (@#'client/github-token-provider-response
            client
            {:registration-id registration-id
             :host "github.com"
             :session-id nil
             :reason "initial"})))
      (is (false? @called?))))

  (testing "provider results are open maps with integer expiry above one hour"
    (doseq [expires-in [3600 3600.5]]
      (is (not (s/valid? ::specs/github-token-provider-result
                         {:kind :token
                          :access-token "token"
                          :expires-in expires-in}))))
    (doseq [result [{:kind :cancelled :reason :expired}
                    {:kind :token
                     :access-token "token"
                     :expires-in 3601
                     :account-label "enterprise"}]]
      (is (s/valid? ::specs/github-token-provider-result result))
      (let [client (sdk/client {:auto-start? false})
            registration-id
            (@#'client/register-github-token-provider!
             client (constantly result) "extended-result")]
        (is (= result
               (get-in
                (<!!
                 (@#'client/github-token-provider-response
                  client
                  {:registration-id registration-id
                   :host "github.com"
                   :reason "initial"}))
                [:result]))))))

  (testing "failed creation rolls back its provisional provider registration"
    (let [registrations-before
          (:github-token-providers @(:state *test-client*))]
      (mock/set-request-hook! *mock-server*
                              (fn [method _]
                                (when (= "session.create" method)
                                  (throw (ex-info "create failed" {:code -32000})))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"create failed"
                            (sdk/create-session
                             *test-client*
                             {:on-permission-request sdk/approve-all
                              :github-token-provider (fn [_] {:kind :cancelled})})))
      (is (= registrations-before
             (:github-token-providers @(:state *test-client*)))
          "rollback must remove only the failed operation's provisional registration")
      (let [result (<!! (sdk/<create-session
                         *test-client*
                         {:on-permission-request sdk/approve-all
                          :github-token-provider (fn [_] {:kind :cancelled})}))]
        (is (instance? Throwable result))
        (is (= registrations-before
               (:github-token-providers @(:state *test-client*)))
            "asynchronous rollback must remove its provisional registration")))))

(defn- registrations-for-session
  [client session-id]
  (into {}
        (filter (fn [[_ registration]]
                  (= session-id (:session-id registration))))
        (:github-token-providers @(:state client))))

(defn- invoke-create
  [mode client config]
  (case mode
    :sync
    (try
      (sdk/create-session client config)
      (catch Throwable t
        t))

    :async
    (try
      (<!! (sdk/<create-session client config))
      (catch Throwable t
        t))))

(defn- invoke-resume
  [mode client session-id config]
  (case mode
    :sync
    (try
      (sdk/resume-session client session-id config)
      (catch Throwable t
        t))

    :async
    (try
      (<!! (sdk/<resume-session client session-id config))
      (catch Throwable t
        t))))

(deftest test-failed-create-setup-owns-complete-cleanup
  (doseq [mode [:sync :async]
          stage [:returned-id-mismatch :mcp-interest :options-update]]
    (testing (str (name mode) " " (name stage))
      (let [session-id (str "failed-create-" (name mode) "-" (name stage))
            methods (atom [])
            config
            (merge
             {:session-id session-id
              :on-permission-request sdk/approve-all
              :github-token-provider (fn [_] {:kind :cancelled})}
             (case stage
               :mcp-interest {:on-mcp-auth-request (fn [_ _] nil)}
               :options-update {:skip-custom-instructions true}
               {}))]
        (mock/set-request-hook!
         *mock-server*
         (fn [method _]
           (swap! methods conj method)
           (case stage
             :returned-id-mismatch
             (when (= "session.create" method)
               {::mock/merge-response {:sessionId (str session-id "-other")}})

             :mcp-interest
             (when (= "session.eventLog.registerInterest" method)
               (throw (ex-info "interest setup failed" {:code -32000})))

             :options-update
             (when (= "session.options.update" method)
               (throw (ex-info "options setup failed" {:code -32000}))))))
        (let [failure (invoke-create mode *test-client* config)]
          (is (instance? Throwable failure))
          (is (= 1 (count (filter #{"session.destroy"} @methods))))
          (is (nil? (get-in @(:state *test-client*) [:sessions session-id])))
          (is (nil? (get-in @(:state *test-client*) [:session-io session-id])))
          (is (empty? (registrations-for-session *test-client* session-id)))
          (is (empty? (:github-token-provider-invocations
                       @(:state *test-client*))))
          (is (nil? (get @(:sessions *mock-server*) session-id))))
        (mock/set-request-hook! *mock-server* nil)))))

(deftest test-create-failure-before-remote-acceptance-rolls-back-locally
  (doseq [mode [:sync :async]
          stage [:session-fs-factory :create-rpc]]
    (testing (str (name mode) " " (name stage))
      (let [session-id (str "pre-create-" (name mode) "-" (name stage))
            client (if (= stage :session-fs-factory)
                     (assoc *test-client*
                            :session-fs
                            {:initial-cwd "/workspace"
                             :session-state-path "/state"
                             :conventions "posix"})
                     *test-client*)
            config
            (cond-> {:session-id session-id
                     :on-permission-request sdk/approve-all
                     :github-token-provider (fn [_] {:kind :cancelled})}
              (= stage :session-fs-factory)
              (assoc :create-session-fs-handler
                     (fn [_]
                       (throw (ex-info "session fs factory failed" {})))))]
        (mock/set-request-hook!
         *mock-server*
         (when (= stage :create-rpc)
           (fn [method _]
             (when (= "session.create" method)
               (throw (ex-info "create RPC failed" {:code -32000}))))))
        (let [failure (invoke-create mode client config)]
          (is (instance? Throwable failure))
          (is (nil? (get-in @(:state client) [:sessions session-id])))
          (is (nil? (get-in @(:state client) [:session-io session-id])))
          (is (empty? (registrations-for-session client session-id)))
          (is (nil? (get @(:sessions *mock-server*) session-id))))
        (mock/set-request-hook! *mock-server* nil)))))

(deftest test-failed-provisional-resume-preserves-live-session
  (doseq [mode [:sync :async]
          stage [:mcp-interest :resume-rpc :session-fs-factory]]
    (testing (str (name mode) " " (name stage))
      (let [session-id (str "preserved-resume-" (name mode) "-" (name stage))
            old-provider (fn [_] {:kind :cancelled})
            base-session
            (sdk/create-session
             *test-client*
             {:session-id session-id
              :on-permission-request sdk/approve-all
              :github-token-provider old-provider})
            old-registration-id
            (-> (registrations-for-session *test-client* session-id)
                keys
                first)
            old-session-state (get-in @(:state *test-client*) [:sessions session-id])
            old-session-io (get-in @(:state *test-client*) [:session-io session-id])
            client
            (if (= stage :session-fs-factory)
              (assoc *test-client*
                     :session-fs
                     {:initial-cwd "/workspace"
                      :session-state-path "/state"
                      :conventions "posix"})
              *test-client*)
            config
            (cond-> {:on-permission-request sdk/approve-all
                     :github-token-provider (fn [_] {:kind :cancelled})}
              (= stage :mcp-interest)
              (assoc :on-mcp-auth-request (fn [_ _] nil))

              (= stage :session-fs-factory)
              (assoc :create-session-fs-handler
                     (fn [_]
                       (throw (ex-info "resume session fs factory failed" {})))))]
        (mock/set-request-hook!
         *mock-server*
         (case stage
           :mcp-interest
           (fn [method _]
             (when (= "session.eventLog.registerInterest" method)
               (throw (ex-info "resume interest failed" {:code -32000}))))

           :resume-rpc
           (fn [method _]
             (when (= "session.resume" method)
               (throw (ex-info "resume RPC failed" {:code -32000}))))

           nil))
        (let [failure (invoke-resume mode client session-id config)]
          (is (instance? Throwable failure))
          (is (identical?
               old-session-state
               (get-in @(:state client) [:sessions session-id])))
          (is (identical?
               old-session-io
               (get-in @(:state client) [:session-io session-id])))
          (is (= #{old-registration-id}
                 (set (keys (registrations-for-session client session-id)))))
          (is (= {:kind "cancelled"}
                 (:result
                  (mock/send-rpc-request!
                   *mock-server*
                   "gitHubToken.getToken"
                   {:registrationId old-registration-id
                    :host "github.com"
                    :sessionId (sdk/session-id base-session)
                    :reason "refresh"}))))
          (is (some? (get @(:sessions *mock-server*) session-id))))
        (mock/set-request-hook! *mock-server* nil)))))

(deftest test-failed-resume-after-remote-acceptance-destroys-session
  (doseq [mode [:sync :async]]
    (testing (name mode)
      (let [session-id (str "failed-resume-" (name mode))
            methods (atom [])]
        (sdk/create-session
         *test-client*
         {:session-id session-id
          :on-permission-request sdk/approve-all
          :github-token-provider (fn [_] {:kind :cancelled})})
        (mock/set-request-hook!
         *mock-server*
         (fn [method _]
           (swap! methods conj method)
           (when (= "session.options.update" method)
             (throw (ex-info "resume options failed" {:code -32000})))))
        (let [failure
              (invoke-resume
               mode
               *test-client*
               session-id
               {:on-permission-request sdk/approve-all
                :github-token-provider (fn [_] {:kind :cancelled})
                :skip-custom-instructions true})]
          (is (instance? Throwable failure))
          (is (= 1 (count (filter #{"session.resume"} @methods))))
          (is (= 1 (count (filter #{"session.destroy"} @methods))))
          (is (nil? (get-in @(:state *test-client*) [:sessions session-id])))
          (is (nil? (get-in @(:state *test-client*) [:session-io session-id])))
          (is (empty? (registrations-for-session *test-client* session-id)))
          (is (nil? (get @(:sessions *mock-server*) session-id))))
        (mock/set-request-hook! *mock-server* nil)))))

(deftest test-failed-setup-cancels-active-provider-invocation
  (let [server *mock-server*
        session-id "failed-setup-active-provider"
        provider-entered (promise)
        provider-interrupted (promise)
        token-response (promise)
        registration-id (atom nil)
        blocker (promise)]
    (mock/set-request-hook!
     server
     (fn [method params]
       (case method
         "session.create"
         (reset! registration-id (:gitHubTokenProviderRegistrationId params))

         "session.options.update"
         (do
           (future
             (deliver
              token-response
              (mock/send-rpc-request!
               server
               "gitHubToken.getToken"
               {:registrationId @registration-id
                :host "github.com"
                :sessionId session-id
                :reason "initial"})))
           (await-value! provider-entered "provider callback entry" 1000)
           (throw (ex-info "options failed with provider active" {:code -32000})))

         nil)))
    (let [failure
          (try
            (sdk/create-session
             *test-client*
             {:session-id session-id
              :on-permission-request sdk/approve-all
              :skip-custom-instructions true
              :github-token-provider
              (fn [_]
                (deliver provider-entered true)
                (try
                  @blocker
                  (catch InterruptedException t
                    (deliver provider-interrupted true)
                    (throw t))))})
            (catch Throwable t
              t))]
      (is (instance? Throwable failure))
      (is (true? (await-value! provider-interrupted
                               "provider callback interruption"
                               1000)))
      (is (= {:kind "cancelled"}
             (:result
              (await-value! token-response "cancelled provider response" 1000))))
      (is (empty? (:github-token-provider-invocations
                   @(:state *test-client*))))
      (is (empty? (registrations-for-session *test-client* session-id))))))

(deftest test-cleanup-failure-is-suppressed-under-primary-setup-failure
  (let [session-id "suppressed-cleanup-failure"]
    (mock/set-request-hook!
     *mock-server*
     (fn [method _]
       (case method
         "session.options.update"
         (throw (ex-info "primary options failure" {:code -32000}))

         "session.destroy"
         (throw (ex-info "cleanup destroy failure" {:code -32000}))

         nil)))
    (let [failure
          (try
            (sdk/create-session
             *test-client*
             {:session-id session-id
              :on-permission-request sdk/approve-all
              :skip-custom-instructions true
              :github-token-provider (fn [_] {:kind :cancelled})})
            (catch Throwable t
              t))]
      (is (instance? Throwable failure))
      (is (str/includes? (ex-message failure) "primary options failure"))
      (is (= 1 (count (.getSuppressed ^Throwable failure))))
      (is (str/includes?
           (ex-message (.getCause ^Throwable
                        (first (.getSuppressed ^Throwable failure))))
           "cleanup destroy failure"))
      (is (nil? (get-in @(:state *test-client*) [:sessions session-id])))
      (is (nil? (get-in @(:state *test-client*) [:session-io session-id])))
      (is (empty? (registrations-for-session *test-client* session-id))))))

(deftest test-session-github-token-provider-request-ownership
  (testing "a callback request cannot override its registration-bound session"
    (let [client (sdk/client {:auto-start? false})
          called? (atom false)
          registration-id
          (@#'client/register-github-token-provider!
           client
           (fn [_]
             (reset! called? true)
             {:kind :cancelled})
           "owned-session")
          error
          (try
            (@#'client/github-token-provider-response
             client
             {:registration-id registration-id
              :host "github.com"
              :session-id "different-session"
              :reason "refresh"})
            nil
            (catch Throwable t t))]
      (is (= "GitHub token provider request session does not match registration"
             (ex-message error)))
      (is (= {:type :github-token-provider-session-mismatch
              :registration-id registration-id
              :session-id "owned-session"}
             (ex-data error)))
      (is (false? @called?))
      (is (empty? (:github-token-provider-invocations @(:state client)))))))

(defn- pending-github-token-request
  [client registration-id]
  (let [response
        (future
          (<!!
           (@#'client/github-token-provider-response
            client
            {:registration-id registration-id
             :host "github.com"
             :reason "refresh"})))]
    (await-atom! (:state client)
                 #(seq (:github-token-provider-invocations %))
                 "GitHub token provider invocation registration"
                 1000)
    response))

(defn- assert-provider-request-cancelled!
  [response]
  (try
    (is (= {:result {:kind :cancelled}}
           (deref response 1000 ::timeout)))
    (finally
      (future-cancel response))))

(deftest test-session-github-token-provider-invocation-cancellation
  (testing "session teardown cancels an active channel-returning provider"
    (let [client (sdk/client {:auto-start? false})
          session-id "teardown-active-provider"
          _ (session/create-session client session-id {})
          registration-id
          (@#'client/register-github-token-provider!
           client (constantly (chan)) session-id)
          _ (@#'client/commit-github-token-provider!
             client session-id registration-id)
          response (pending-github-token-request client registration-id)]
      (session/teardown-local! client session-id)
      (assert-provider-request-cancelled! response)
      (is (empty? (:github-token-provider-invocations @(:state client))))))

  (testing "provider rotation cancels active invocations owned by the replaced registration"
    (let [client (sdk/client {:auto-start? false})
          session-id "rotate-active-provider"
          old-registration-id
          (@#'client/register-github-token-provider!
           client (constantly (chan)) session-id)
          _ (@#'client/commit-github-token-provider!
             client session-id old-registration-id)
          response (pending-github-token-request client old-registration-id)
          new-registration-id
          (@#'client/register-github-token-provider!
           client (constantly {:kind :cancelled}) session-id)]
      (@#'client/commit-github-token-provider!
       client session-id new-registration-id)
      (assert-provider-request-cancelled! response)
      (is (= #{new-registration-id}
             (set (keys (:github-token-providers @(:state client))))))
      (is (empty? (:github-token-provider-invocations @(:state client))))))

  (testing "provisional rollback cancels invocations for the discarded registration"
    (let [client (sdk/client {:auto-start? false})
          registration-id
          (@#'client/register-github-token-provider!
           client (constantly (chan)) "rollback-active-provider")
          response (pending-github-token-request client registration-id)]
      (@#'client/rollback-github-token-provider! client registration-id)
      (assert-provider-request-cancelled! response)
      (is (empty? (:github-token-provider-invocations @(:state client))))))

  (testing "transport release cancels every active provider invocation"
    (let [client (sdk/client {:auto-start? false})
          registration-id
          (@#'client/register-github-token-provider!
           client (constantly (chan)) "release-active-provider")
          response (pending-github-token-request client registration-id)]
      (@#'client/release-transport! client {:process :none})
      (assert-provider-request-cancelled! response)
      (is (empty? (:github-token-provider-invocations @(:state client)))))))

(deftest test-session-github-token-provider-executor-lifecycle
  (testing "teardown interrupts blocking callback work and returns cancellation promptly"
    (let [client (sdk/client {:auto-start? false})
          session-id "interrupt-provider"
          _ (session/create-session client session-id {})
          entered (promise)
          interrupted (promise)
          blocker (promise)
          registration-id
          (@#'client/register-github-token-provider!
           client
           (fn [_]
             (deliver entered {:daemon? (.isDaemon (Thread/currentThread))
                               :thread-name (.getName (Thread/currentThread))})
             (try
               @blocker
               {:kind :cancelled}
               (catch InterruptedException _
                 (deliver interrupted true)
                 (throw (InterruptedException.)))))
           session-id)
          _ (@#'client/commit-github-token-provider!
             client session-id registration-id)
          call
          (future
            (@#'client/github-token-provider-response
             client
             {:registration-id registration-id
              :host "github.com"
              :reason "refresh"}))]
      (try
        (let [{:keys [daemon? thread-name]} (deref entered 1000 ::timeout)]
          (is daemon?)
          (is (str/starts-with? thread-name "copilot-github-token-provider-")))
        (session/teardown-local! client session-id)
        (let [response-ch (deref call 1000 ::timeout)]
          (is (satisfies? async-protocols/ReadPort response-ch))
          (is (= {:result {:kind :cancelled}}
                 (first (alts!! [response-ch (timeout 1000)])))))
        (is (= true (deref interrupted 1000 false)))
        (finally
          (deliver blocker true)
          (future-cancel call)
          (@#'client/release-transport! client {:process :none})))))

  (testing "transport release shuts down the executor and a later request creates a fresh one"
    (let [client (sdk/client {:auto-start? false})
          invoke!
          (fn [session-id]
            (let [registration-id
                  (@#'client/register-github-token-provider!
                   client (constantly {:kind :cancelled}) session-id)]
              (<!!
               (@#'client/github-token-provider-response
                client
                {:registration-id registration-id
                 :host "github.com"
                 :reason "initial"}))))
          _ (invoke! "first")
          first-executor (:github-token-provider-executor @(:state client))]
      (is (some? first-executor))
      (@#'client/release-transport! client {:process :none})
      (is (nil? (:github-token-provider-executor @(:state client))))
      (invoke! "second")
      (let [second-executor (:github-token-provider-executor @(:state client))]
        (is (some? second-executor))
        (is (not (identical? first-executor second-executor))))
      (@#'client/release-transport! client {:process :none})))

  (testing "a stale invocation cannot recreate an executor after release"
    (let [client (sdk/client {:auto-start? false})
          registration-id
          (@#'client/register-github-token-provider!
           client (constantly {:kind :cancelled}) "stale-generation")
          original-executor @#'client/github-token-provider-executor!
          acquiring (promise)
          continue (promise)]
      (with-redefs-fn
        {#'client/github-token-provider-executor!
         (fn [client generation]
           (deliver acquiring true)
           @continue
           (original-executor client generation))}
        (fn []
          (let [response-call
                (future
                  (@#'client/github-token-provider-response
                   client
                   {:registration-id registration-id
                    :host "github.com"
                    :reason "refresh"}))]
            (try
              (is (= true (deref acquiring 1000 ::timeout)))
              (@#'client/release-github-token-provider-runtime! client)
              (deliver continue true)
              (let [response-ch (deref response-call 1000 ::timeout)]
                (is (= {:result {:kind :cancelled}}
                       (first (alts!! [response-ch (timeout 1000)])))))
              (is (nil? (:github-token-provider-executor @(:state client))))
              (is (= 1
                     (:github-token-provider-runtime-generation
                      @(:state client))))
              (is (zero?
                   (:github-token-provider-saturation-count
                    @(:state client))))
              (finally
                (deliver continue true)
                (future-cancel response-call)
                (@#'client/release-github-token-provider-runtime! client))))))))

  (testing "executor saturation produces an explicit sanitized response"
    (with-redefs-fn
      {#'client/github-token-provider-thread-count 1
       #'client/github-token-provider-queue-size 1}
      (fn []
        (let [client (sdk/client {:auto-start? false})
              gate (promise)
              entered (atom 0)
              registration-id
              (@#'client/register-github-token-provider!
               client
               (fn [_]
                 (swap! entered inc)
                 @gate
                 {:kind :cancelled})
               "saturated")
              first-response
              (@#'client/github-token-provider-response
               client
               {:registration-id registration-id
                :host "github.com"
                :reason "refresh"})]
          (try
            (await-atom! entered #(= 1 %) "first provider task" 1000)
            (log-test/with-log
              (let [second-response
                    (@#'client/github-token-provider-response
                     client
                     {:registration-id registration-id
                      :host "github.com"
                      :reason "refresh"})
                    third-response
                    (@#'client/github-token-provider-response
                     client
                     {:registration-id registration-id
                      :host "github.com"
                      :reason "refresh"})]
                (is (= {:error
                        {:code -32000
                         :message "GitHub token provider executor saturated"}}
                       (<!! third-response)))
                (is (= 1
                       (:github-token-provider-saturation-count
                        @(:state client))))
                (let [log-output
                      (str/join "\n" (map :message (log-test/the-log)))]
                  (is (str/includes? log-output
                                     "GitHub token provider executor saturated"))
                  (is (str/includes? log-output registration-id))
                  (is (str/includes? log-output "github.com"))
                  (is (str/includes? log-output ":refresh"))
                  (is (str/includes? log-output ":saturation-count 1"))
                  (is (str/includes? log-output ":thread-limit 1"))
                  (is (str/includes? log-output ":queue-limit 1")))
                (@#'client/release-transport! client {:process :none})
                (is (= {:result {:kind :cancelled}}
                       (first (alts!! [first-response (timeout 1000)]))))
                (is (= {:result {:kind :cancelled}}
                       (first (alts!! [second-response (timeout 1000)]))))))
            (finally
              (deliver gate true)
              (@#'client/release-transport! client {:process :none}))))))))

(deftest test-session-github-token-provider-unexpected-connection-closure
  (testing "notification-channel closure cancels active provider invocations"
    (let [session-id "connection-closure-provider"
          _ (sdk/create-session
             *test-client*
             {:session-id session-id
              :on-permission-request sdk/approve-all
              :github-token-provider (constantly (chan))})
          registration-id
          (some (fn [[id registration]]
                  (when (= session-id (:session-id registration))
                    id))
                (:github-token-providers @(:state *test-client*)))
          response
          (pending-github-token-request *test-client* registration-id)]
      (close! (protocol/notifications
               (:connection-io @(:state *test-client*))))
      (assert-provider-request-cancelled! response)
      (await-atom! (:state *test-client*)
                   #(and (empty? (:github-token-providers %))
                         (empty? (:github-token-provider-invocations %)))
                   "GitHub token provider cleanup after notification closure"
                   1000))))

(deftest test-session-github-token-provider-lifecycle
  (testing "resume rotates providers only on success and omission clears the previous provider"
    (let [payloads (atom [])
          first-provider (fn [_] {:kind :cancelled})
          second-provider (fn [_] {:kind :cancelled})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"session.create" "session.resume"} method)
                                        (swap! payloads conj params))))
          session (sdk/create-session
                   *test-client*
                   {:session-id "resumed"
                    :on-permission-request sdk/approve-all
                    :github-token-provider first-provider})
          first-registration (:gitHubTokenProviderRegistrationId (first @payloads))
          _ (sdk/resume-session
             *test-client* (sdk/session-id session)
             {:on-permission-request sdk/approve-all
              :github-token-provider second-provider})
          second-registration (:gitHubTokenProviderRegistrationId (second @payloads))]
      (is (not (contains? (:github-token-providers @(:state *test-client*))
                          first-registration)))
      (is (contains? (:github-token-providers @(:state *test-client*))
                     second-registration))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"No GitHub token provider registered"
                            (mock/send-rpc-request! *mock-server*
                                                    "gitHubToken.getToken"
                                                    {:registrationId first-registration
                                                     :host "github.com"
                                                     :reason "refresh"})))
      (is (= {:kind "cancelled"}
             (:result
              (mock/send-rpc-request! *mock-server*
                                      "gitHubToken.getToken"
                                      {:registrationId second-registration
                                       :host "github.com"
                                       :reason "refresh"}))))
      (sdk/resume-session *test-client* (sdk/session-id session)
                          {:on-permission-request sdk/approve-all})
      (is (empty? (:github-token-providers @(:state *test-client*))))))

  (testing "concurrent pending registrations survive until each operation commits"
    (let [client (sdk/client {:auto-start? false})
          old-id (@#'client/register-github-token-provider!
                  client (fn [_] {:kind :cancelled}) "concurrent")
          _ (@#'client/commit-github-token-provider! client "concurrent" old-id)
          first-id (@#'client/register-github-token-provider!
                    client (fn [_] {:kind :cancelled}) "concurrent")
          second-id (@#'client/register-github-token-provider!
                     client (fn [_] {:kind :cancelled}) "concurrent")]
      (@#'client/commit-github-token-provider! client "concurrent" first-id)
      (is (= #{first-id second-id}
             (set (keys (:github-token-providers @(:state client))))))
      (@#'client/commit-github-token-provider! client "concurrent" second-id)
      (is (= #{second-id}
             (set (keys (:github-token-providers @(:state client))))))))

  (testing "teardown removes provisional registrations and a later commit is a no-op"
    (let [client (sdk/client {:auto-start? false})
          session-id "teardown-race"
          _ (session/create-session client session-id {})
          registration-id
          (@#'client/register-github-token-provider!
           client (fn [_] {:kind :cancelled}) session-id)]
      (is (= :claimed (session/teardown-local! client session-id)))
      (is (empty? (:github-token-providers @(:state client))))
      (@#'client/commit-github-token-provider! client session-id registration-id)
      (is (empty? (:github-token-providers @(:state client))))))

  (testing "failed synchronous and asynchronous resume preserve the committed provider"
    (let [base (sdk/create-session
                *test-client*
                {:session-id "failed-resume"
                 :on-permission-request sdk/approve-all
                 :github-token-provider (fn [_] {:kind :cancelled})})
          registrations-before
          (:github-token-providers @(:state *test-client*))
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method _]
                                      (when (= "session.resume" method)
                                        (throw (ex-info "resume failed"
                                                        {:code -32000})))))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"resume failed"
           (sdk/resume-session
            *test-client* (sdk/session-id base)
            {:on-permission-request sdk/approve-all
             :github-token-provider (fn [_] {:kind :cancelled})})))
      (is (= registrations-before
             (:github-token-providers @(:state *test-client*))))
      (let [result (<!! (sdk/<resume-session
                         *test-client* (sdk/session-id base)
                         {:on-permission-request sdk/approve-all
                          :github-token-provider (fn [_] {:kind :cancelled})}))]
        (is (instance? Throwable result))
        (is (= registrations-before
               (:github-token-providers @(:state *test-client*))))
        (client/delete-session! *test-client* "failed-resume"))))

  (testing "session disconnect, deletion, and client stop clean registrations"
    (let [first (sdk/create-session
                 *test-client*
                 {:session-id "first"
                  :on-permission-request sdk/approve-all
                  :github-token-provider (fn [_] {:kind :cancelled})})
          _ (sdk/create-session
             *test-client*
             {:session-id "second"
              :on-permission-request sdk/approve-all
              :github-token-provider (fn [_] {:kind :cancelled})})]
      (is (= 2 (count (:github-token-providers @(:state *test-client*)))))
      (sdk/disconnect! first)
      (is (= #{"second"}
             (set (keep :session-id
                        (vals (:github-token-providers
                               @(:state *test-client*)))))))
      (client/delete-session! *test-client* "second")
      (is (empty? (:github-token-providers @(:state *test-client*))))
      (sdk/create-session
       *test-client*
       {:session-id "third"
        :on-permission-request sdk/approve-all
        :github-token-provider (fn [_] {:kind :cancelled})})
      (sdk/stop! *test-client*)
      (is (empty? (:github-token-providers @(:state *test-client*)))))))

(deftest test-v1-0-4-provider-and-models-mutually-exclusive
  (testing "combining singular :provider with the :models registry is rejected on both create and resume (upstream SessionConfig contract, PR #1718)"
    ;; Upstream documents (types.ts SessionConfig.providers/models JSDoc) that
    ;; combining *either* `providers` *or* `models` with the singular `provider`
    ;; is rejected — `:models` is part of the same multi-provider registry
    ;; surface. A config with `:provider` + `:models` (no `:providers`) must fail
    ;; the same client-side guard, otherwise it serializes a wire payload that
    ;; contradicts the documented "provider vs multi-provider registry" contract.
    (let [cfg {:on-permission-request sdk/approve-all
               :model "m"
               :provider {:base-url "https://single.test"}
               :models [{:provider "p" :id "m"}]}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"(?i):provider.*cannot be combined.*:models"
                            (sdk/create-session *test-client* cfg))
          "create-session rejects :provider + :models")
      (let [ok-session (sdk/create-session *test-client*
                                           {:on-permission-request sdk/approve-all
                                            :model "m"})
            session-id (sdk/session-id ok-session)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"(?i):provider.*cannot be combined.*:models"
                              (sdk/resume-session *test-client* session-id cfg))
            "resume-session rejects :provider + :models")))))

(deftest test-v1-0-4-bearer-token-exception-message-not-leaked
  (testing "an exception thrown by a bearer-token callback never leaks its message to logs or the runtime (SEC)"
    ;; The callback mints credentials; an exception it raises can easily carry
    ;; sensitive material in its message (e.g. a token echoed in an auth error).
    ;; The JSON-RPC error returned to the runtime must be generic and the log
    ;; must record only the exception class, never `ex-message`. Handler invoked
    ;; directly so `thread-call` conveys the `with-log` binding to the io thread.
    (let [secret "tok_LEAKED_IN_EXCEPTION_MESSAGE_456"
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :model "fallback-model"
                                       :provider {:base-url "https://oai.test"
                                                  :bearer-token-provider
                                                  (fn [_] (throw (ex-info secret {})))}})
          session-id (sdk/session-id session)]
      (log-test/with-log
        (let [resp (<!! (session/handle-provider-token-request!
                         *test-client* session-id "default"))]
          (is (= -32001 (get-in resp [:error :code]))
              "a thrown callback yields a JSON-RPC error")
          (is (not (str/includes? (str (get-in resp [:error :message])) secret))
              "the error message returned to the runtime must not echo the exception message")
          (is (seq (log-test/the-log)) "the exception branch should emit a log entry")
          (doseq [entry (log-test/the-log)]
            (is (not (str/includes? (str (:message entry)) secret))
                "exception message must not be logged")))))))

(deftest test-v1-0-4-bearer-token-provider-wire
  (testing ":bearer-token-provider strips the fn and emits :hasBearerTokenProvider on both builders (upstream PR #1748)"
    (let [token-fn (fn [_args] "secret-token")
          provider {:provider-type :openai
                    :wire-api :responses
                    :base-url "https://oai.test"
                    :bearer-token-provider token-fn}
          named [{:name "my-azure"
                  :provider-type :azure
                  :base-url "https://azure.test"
                  :bearer-token-provider token-fn}]
          capture (fn [cfg]
                    (let [seen (atom {})]
                      (mock/set-request-hook! *mock-server*
                                              (fn [method params]
                                                (when (#{"session.create" "session.resume"} method)
                                                  (swap! seen assoc method params))))
                      (sdk/create-session *test-client* cfg)
                      (let [session-id (sdk/get-last-session-id *test-client*)]
                        (sdk/resume-session *test-client* session-id cfg))
                      @seen))
          ;; singular :provider and registry :providers are mutually exclusive,
          ;; so exercise each on its own config.
          singular-seen (capture {:on-permission-request sdk/approve-all
                                  :model "fallback-model"
                                  :provider provider})
          registry-seen (capture {:on-permission-request sdk/approve-all
                                  :model "fallback-model"
                                  :providers named})]
      (doseq [method ["session.create" "session.resume"]]
        (testing method
          (let [wprov (:provider (get singular-seen method))
                wnamed (first (:providers (get registry-seen method)))]
            ;; singular provider
            (is (true? (:hasBearerTokenProvider wprov))
                "singular provider with a callback emits :hasBearerTokenProvider true")
            (is (not (contains? wprov :bearerTokenProvider))
                "the callback fn must NOT be serialized onto the wire")
            (is (not (contains? wprov :bearer-token-provider))
                "the kebab-case callback key must NOT leak onto the wire")
            ;; named provider
            (is (true? (:hasBearerTokenProvider wnamed))
                "named provider with a callback emits :hasBearerTokenProvider true")
            (is (not (contains? wnamed :bearerTokenProvider))
                "the named-provider callback fn must NOT be serialized onto the wire"))))))
  (testing "a provider without a callback omits :hasBearerTokenProvider"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (reset! seen params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :model "fallback-model"
                                 :provider {:base-url "https://oai.test" :api-key "k"}})]
      (is (not (contains? (:provider @seen) :hasBearerTokenProvider))
          "no callback => no :hasBearerTokenProvider flag")))
  (testing "::bearer-token-provider is accepted on provider and named-provider specs"
    (is (s/valid? :github.copilot-sdk.specs/provider
                  {:base-url "https://x.test" :bearer-token-provider (fn [_] "t")}))
    (is (s/valid? :github.copilot-sdk.specs/named-provider
                  {:name "p" :base-url "https://x.test" :bearer-token-provider (fn [_] "t")}))
    (is (false? (s/valid? :github.copilot-sdk.specs/provider
                          {:base-url "https://x.test" :bearer-token-provider "not-a-fn"}))
        ":bearer-token-provider must be a function")))

(deftest test-v1-0-4-provider-token-get-token-callback
  (testing "providerToken.getToken routes to the singular provider callback (DEFAULT_PROVIDER_NAME)"
    (let [called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :model "fallback-model"
                                       :provider {:base-url "https://oai.test"
                                                  :bearer-token-provider
                                                  (fn [args]
                                                    (reset! called args)
                                                    "singular-token")}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "providerToken.getToken"
                                           {:sessionId session-id
                                            :providerName "default"})]
      (is (= {:provider-name "default" :session-id session-id} @called)
          "callback receives idiomatic ProviderTokenArgs with :provider-name and :session-id")
      (is (= "singular-token" (get-in response [:result :token]))
          "the resolved token is returned under wire key :token")))
  (testing "providerToken.getToken routes to a named provider callback by name"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :model "fallback-model"
                                       :providers [{:name "my-azure"
                                                    :base-url "https://azure.test"
                                                    :bearer-token-provider
                                                    (fn [_] "azure-token")}]})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "providerToken.getToken"
                                           {:sessionId session-id
                                            :providerName "my-azure"})]
      (is (= "azure-token" (get-in response [:result :token])))))
  (testing "providerToken.getToken with no matching callback returns an error"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :model "fallback-model"
                                       :provider {:base-url "https://oai.test"
                                                  :bearer-token-provider (fn [_] "tok")}})
          session-id (sdk/session-id session)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"(?i)no bearer-token provider"
                            (mock/send-rpc-request! *mock-server*
                                                    "providerToken.getToken"
                                                    {:sessionId session-id
                                                     :providerName "nonexistent"}))))))

(deftest test-v1-0-4-bearer-token-non-string-result-not-logged
  (testing "a non-string bearer-token callback result value never reaches the logs (SEC)"
    ;; The callback is credential-related; a mistaken non-string return (e.g. a
    ;; map carrying the token) must not have its value interpolated into a log
    ;; message. The JSON-RPC error returned to the runtime is generic; this
    ;; guards the log side-channel. The handler is invoked directly on the test
    ;; thread so core.async `thread-call` conveys the `with-log` binding frame
    ;; to the io thread (the server-dispatch path runs on a reader thread that
    ;; predates this binding, so it cannot be captured via the mock RPC route).
    (let [secret "tok_LEAKED_IN_MAP_VALUE_123"
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :model "fallback-model"
                                       :provider {:base-url "https://oai.test"
                                                  :bearer-token-provider
                                                  (fn [_] {:access-token secret})}})
          session-id (sdk/session-id session)]
      (log-test/with-log
        (let [resp (<!! (session/handle-provider-token-request!
                         *test-client* session-id "default"))]
          (is (= -32001 (get-in resp [:error :code]))
              "a non-string result must yield a JSON-RPC error")
          (is (not (str/includes? (str (get-in resp [:error :message])) secret))
              "the error message must not echo the secret")
          (is (seq (log-test/the-log)) "the non-string branch should emit a warning")
          (doseq [entry (log-test/the-log)]
            (is (not (str/includes? (str (:message entry)) secret))
                "non-string callback result value must not be logged")))))))

(deftest test-schema-1-0-52-4-min-protocol-version-3
  (testing "client rejects servers reporting protocol version < 3 (upstream PR #1378)"
    ;; The SDK no longer supports v2 servers after the cleanup PR removed
    ;; the v2 `tool.call` / `permission.request` back-compat adapters.
    (let [server (mock/create-mock-server)
          _ (reset! (:protocol-version server) 2)
          _ (mock/start-mock-server! server)
          client (sdk/client {:auto-start? false})
          [in out] (mock/client-streams server)]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"(?i)protocol.*version"
                              (client/connect-with-streams! client in out)))
        (finally
          (try (sdk/disconnect! client) (catch Exception _))
          (mock/stop-mock-server! server))))))
