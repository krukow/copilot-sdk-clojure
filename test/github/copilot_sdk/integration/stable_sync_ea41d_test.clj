(ns github.copilot-sdk.integration.stable-sync-ea41d-test
  "Exact-pin public event-shape parity through upstream ea41dadb1."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.generated.event-specs :as generated-events]
            [github.copilot-sdk.specs]))

(def ^:private stable-delta-resource
  "resources/stable_upstream_delta_ea41d.edn")

(defn- read-schema
  [path]
  (with-open [reader (io/reader path)]
    (json/read reader :key-fn keyword)))

(deftest stable-delta-inventory-is-complete-and-internally-consistent
  (let [resource (io/resource stable-delta-resource)]
    (is (some? resource) "The post-eb7ba parity oracle must be committed")
    (when resource
      (let [report (-> resource slurp edn/read-string)
            source-symbols (:source-symbols report)
            runtime-schema-symbols (:runtime-schema-symbols report)
            stable-deltas (:stable-deltas report)
            experimental-deltas (:experimental-deltas report)
            exclusions (:intentional-exclusions report)
            referenced-evidence (set (mapcat :evidence
                                             (concat stable-deltas
                                                     experimental-deltas
                                                     exclusions)))
            all-evidence (set (concat (keys source-symbols)
                                      (keys runtime-schema-symbols)))]
        (is (= "eb7ba2411171f5e1fea9d38df01b436acdfb7271"
               (get-in report [:upstream :base-commit])))
        (is (= "ea41dadb199725766d5097f4592c17be3200035f"
               (get-in report [:upstream :target-commit])))
        (is (= "@github/copilot-linux-x64"
               (get-in report [:upstream :schema-package])))
        (is (= "1.0.81-5"
               (get-in report [:upstream :schema-version])))
        (is (= {:package "@github/copilot"
                :version "^1.0.81-5"}
               (get-in report [:upstream :node-runtime-dependency])))
        (is (= {"api.schema.json"
               "6bb6cd3e01b6d59b8b9f83740e3d1e90fda13f70f458dca24ebdac6c7941c464"
               "session-events.schema.json"
               "9fd414f5020c317a234da6d7a06a4d0ef02ddad227ddc9962dced49302e5e8ec"}
               (get-in report [:upstream :schema-sha256])))
        (is (= (:stable-delta-ids report)
               (set (map :id stable-deltas))))
        (is (empty? (:unclassified-stable report)))
        (is (= all-evidence referenced-evidence))
        (is (every? #(and (= :stable (:classification %))
                          (= :ported (:status %))
                          (seq (:evidence %))
                          (seq (:clojure %)))
                    stable-deltas))
        (is (every? #(and (= :experimental (:classification %))
                          (= :ported-compatibility (:status %))
                          (seq (:evidence %))
                          (seq (:clojure %)))
                    experimental-deltas))
        (is (every? #(and (contains? #{:experimental :internal
                                       :generated-only :language-specific}
                                     (:classification %))
                          (= :excluded (:status %))
                          (keyword? (:reason %))
                          (seq (:evidence %)))
                    exclusions))
        (is (every? (fn [[_ {:keys [file symbol]}]]
                      (and (string? file)
                           (str/starts-with? file "nodejs/")
                           (string? symbol)
                           (not (str/blank? symbol))))
                    source-symbols))
        (is (every? (fn [[_ {:keys [file symbol]}]]
                     (and (= "schemas/session-events.schema.json" file)
                          (string? symbol)
                          (not (str/blank? symbol))))
                    runtime-schema-symbols))
        (is (= #{"525866c9379c525f405086d32b82da338a353ecf"
                 "0c599433cc3759264503c2e96a09bc7670b63a72"
                 "1593e223233b50a9ae27b8812c9ff19a3def41b1"
                 "21652079c8fab4e30bd7358fabd9a258a7140b94"
                 "23dcc2e7afd1f06107a7af261d1fc2a25539773e"
                 "e4cbb63a2df791dea83675f47bfc08f2f839174e"
                 "5fe49f7d5f606a7a5e9328f2bf7eaa0758633478"
                 "ea41dadb199725766d5097f4592c17be3200035f"}
               (set (map :commit (:commit-classifications report)))))
        (is (= #{"nodejs/src/index.ts"
                 "nodejs/src/types.ts"
                 "nodejs/src/client.ts"
                 "nodejs/src/session.ts"
                 "nodejs/src/extension.ts"
                 "nodejs/src/toolSet.ts"
                 "nodejs/src/factory.ts"}
               (set (get-in report
                            [:public-surface-audit
                             :stable-public-files-inspected]))))))))

(deftest existing-experimental-permission-event-tracks-the-current-wire-shape
  (is (s/valid? :github.copilot-sdk.specs/session.permissions_changed-data
                {:mode "assisted"
                 :previous-mode "manual"
                 :assisted-approval-model "gpt-5.4"}))
  (is (not (s/valid? :github.copilot-sdk.specs/session.permissions_changed-data
                     {:allow-all-permissions true
                      :previous-allow-all-permissions false}))))

(deftest stable-exported-event-fields-have-idiom-specs
  (testing "assistant usage additions"
    (let [usage {:model "gpt-5.4"
                 :accepted-prediction-tokens 3
                 :is-auto true
                 :is-byok false
                 :max-output-tokens 4096
                 :max-prompt-tokens 128000
                 :reasoning-summary "detailed"
                 :rejected-prediction-tokens 2
                 :transport "websocket"}]
      (is (s/valid? :github.copilot-sdk.specs/assistant.usage-data usage))
      (is (not (s/valid? :github.copilot-sdk.specs/assistant.usage-data
                         (assoc usage :transport :websocket))))))

  (testing "session lifecycle additions"
    (is (s/valid? :github.copilot-sdk.specs/session.model_change-data
                  {:new-model "gpt-5.4" :source "model_command"}))
    (is (not (s/valid? :github.copilot-sdk.specs/session.model_change-data
                       {:new-model "gpt-5.4" :source 42})))
    (let [shutdown {:shutdown-type "routine"
                    :total-api-duration-ms 50
                    :session-start-time 1
                    :code-changes {}
                    :model-metrics {}
                    :agent-metrics
                    {:main {:model-metrics {}
                            :total-api-duration-ms 50
                            :total-nano-aiu 1000}}}]
      (is (s/valid? :github.copilot-sdk.specs/session.shutdown-data shutdown))
      (is (not (s/valid? :github.copilot-sdk.specs/session.shutdown-data
                         (assoc-in shutdown
                                   [:agent-metrics :main :total-nano-aiu]
                                   "1000"))))))

  (testing "interaction additions"
    (is (s/valid? :github.copilot-sdk.specs/user.message-data
                  {:content "hello" :turn-id "turn-1"}))
    (is (not (s/valid? :github.copilot-sdk.specs/user.message-data
                       {:content "hello" :turn-id 1})))
    (is (s/valid? :github.copilot-sdk.specs/subagent.started-data
                  {:tool-call-id "tool-1"
                   :agent-name "worker"
                   :agent-display-name "Worker"
                   :agent-description "Performs delegated work"
                   :factory-run-id "run-1"}))
    (is (not (s/valid? :github.copilot-sdk.specs/subagent.started-data
                      {:tool-call-id "tool-1"
                       :agent-name "worker"
                       :agent-display-name "Worker"
                       :factory-run-id "run-1"})))
    (is (not (s/valid? :github.copilot-sdk.specs/subagent.started-data
                      {:tool-call-id "tool-1"
                       :agent-name "worker"
                       :agent-display-name "Worker"
                       :agent-description "Performs delegated work"
                       :factory-run-id 1}))))

  (testing "telemetry and host-integration additions"
    (is (s/valid? :github.copilot-sdk.specs/model.call_failure-data
                  {:source "top_level"
                   :interaction-type "conversation-agent"}))
    (is (not (s/valid? :github.copilot-sdk.specs/model.call_failure-data
                       {:source "top_level"
                        :interaction-type :conversation-agent})))
    (is (s/valid? :github.copilot-sdk.specs/system.notification-data
                  {:content "worker finished"
                   :kind {:type "agent_completed"
                          :agent-id "agent-1"
                          :agent-type "task"
                          :status "completed"
                          :display-name "Build verifier"}}))
    (is (not (s/valid? :github.copilot-sdk.specs/system.notification-data
                       {:content "worker finished"
                        :kind {:type "agent_completed"
                               :agent-id "agent-1"
                               :agent-type "task"
                               :status "completed"
                               :display-name 42}})))
    (doseq [kind [{:type "agent_idle"
                  :agent-id "agent-2"
                  :agent-type "explore"
                  :display-name "Researcher"}
                 {:type "new_inbox_message"
                  :entry-id "entry-1"
                  :sender-name "Worker"
                  :sender-type "task"
                   :summary "New result"}
                  {:type "shell_completed"
                   :shell-id "shell-1"
                   :exit-code 0}
                  {:type "shell_detached_completed"
                   :shell-id "shell-2"}
                  {:type "instruction_discovered"
                   :source-path "AGENTS.md"
                   :trigger-file "src/core.clj"
                   :trigger-tool "view"}
                  {:type "factory_completed"
                   :run-id "run-1"
                   :factory-name "review"
                   :status "completed"
                   :consumed-subagents 2
                   :elapsed-ms 100
                   :consumed-nano-aiu 200
                   :attempt 1}
                  {:type "unclassified"
                   :metadata {:source "host"}}]]
      (is (s/valid? :github.copilot-sdk.specs/system.notification-data
                    {:content "notification" :kind kind})))
    (doseq [kind [{:type "agent_idle" :agent-id "agent-2"}
                  {:type "shell_completed"}
                  {:type "new_inbox_message" :entry-id "entry-1"}
                  {:type "factory_completed"
                   :run-id "run-1"
                   :factory-name "review"
                   :status "completed"
                   :consumed-subagents 2
                   :elapsed-ms 100
                   :consumed-nano-aiu 200}
                  {:type "future_runtime_kind"}]]
      (is (not (s/valid? :github.copilot-sdk.specs/system.notification-data
                        {:content "notification" :kind kind}))))
    (is (s/valid? :github.copilot-sdk.specs/external_tool.requested-data
                  {:request-id "request-1"
                   :session-id "session-1"
                   :tool-call-id "tool-1"
                   :tool-name "provider-tool"
                   :provider-id nil}))
    (is (not (s/valid? :github.copilot-sdk.specs/external_tool.requested-data
                       {:request-id "request-1"
                        :session-id "session-1"
                        :tool-call-id "tool-1"
                        :tool-name "provider-tool"
                        :provider-id 42})))
    (is (s/valid? :github.copilot-sdk.specs/exit-plan-mode-request
                  {:summary "Implement it"
                   :actions ["interactive"]
                   :recommended-action "interactive"
                   :model "gpt-5.4"}))
    (is (not (s/valid? :github.copilot-sdk.specs/exit-plan-mode-request
                       {:summary "Implement it"
                        :actions ["interactive"]
                        :recommended-action "interactive"
                        :model 42})))))

(deftest extension-environment-permission-shape-is-stable
  (is (s/valid? :github.copilot-sdk.specs/permission-request
                {:permission-kind :extension-env-access
                 :extension-name "github-app"
                 :environment-variables ["TOKEN" "REGION"]}))
  (is (not (s/valid? :github.copilot-sdk.specs/permission-request
                     {:permission-kind :extension-env-access
                      :extension-name "github-app"
                      :environment-variables []})))
  (is (not (s/valid? :github.copilot-sdk.specs/permission-request
                     {:permission-kind :extension-env-access
                      :extension-name "github-app"})))
  (is (s/valid? :github.copilot-sdk.specs/permission-request
                {:permission-kind :custom-tool
                 :skip-permission true}))
  (is (not (s/valid? :github.copilot-sdk.specs/permission-request
                     {:permission-kind :custom-tool
                      :skip-permission "true"})))
  (is (s/valid? :github.copilot-sdk.specs/permission-request
                {:permission-kind :memory
                 :repo-nwo "github/copilot-sdk"
                 :scope "repository"}))
  (is (not (s/valid? :github.copilot-sdk.specs/permission-request
                     {:permission-kind :memory
                      :repo-nwo 42
                      :scope "repository"})))
  (is (not (s/valid? :github.copilot-sdk.specs/permission-result
                     {:kind :approve-once
                      :managed-approval-handled true})))
  (let [event-schema (read-schema "schemas/session-events.schema.json")
        api-schema (read-schema "schemas/api.schema.json")]
    (doseq [definition [:PermissionApproved
                       :PermissionApprovedForSession
                       :PermissionApprovedForLocation]]
      (is (= {:type "boolean"}
             (select-keys
              (get-in event-schema
                      [:definitions definition :properties
                      :managedApprovalHandled])
              [:type]))))
    (doseq [definition [:PermissionDecisionApproveOnce
                       :PermissionDecisionApproveForSession
                       :PermissionDecisionApproveForLocation]]
      (is (not (contains?
                (get-in api-schema [:definitions definition :properties])
                :managedApprovalHandled))))))

(deftest schema-only-internal-events-stay-out-of-the-public-idiom-surface
  (doseq [[wire-type idiom-type]
          [["agent.interrupted" :copilot/agent.interrupted]
           ["prompt_cache_break" :copilot/prompt_cache_break]
           ["sandbox.decision" :copilot/sandbox.decision]]]
    (is (contains? generated-events/event-types wire-type))
    (is (not (contains? sdk/event-types idiom-type)))
    (is (not (s/valid? :github.copilot-sdk.specs/event-type idiom-type)))))
