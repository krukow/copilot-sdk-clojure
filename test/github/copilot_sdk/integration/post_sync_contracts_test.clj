(ns github.copilot-sdk.integration.post-sync-contracts-test
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

(deftest test-convert-mcp-call-tool-result-text
  (testing "converts text content blocks to textResultForLlm"
    (let [result (tools/convert-mcp-call-tool-result
                  {:content [{:type "text" :text "Hello"}
                             {:type "text" :text "World"}]})]
      (is (= "Hello\nWorld" (:text-result-for-llm result)))
      (is (= "success" (:result-type result)))
      (is (nil? (:binary-results-for-llm result))))))

(deftest test-convert-mcp-call-tool-result-image
  (testing "converts image content blocks to binaryResultsForLlm"
    (let [result (tools/convert-mcp-call-tool-result
                  {:content [{:type "image"
                              :data "base64data"
                              :mime-type "image/png"}]})]
      (is (= "" (:text-result-for-llm result)))
      (is (= "success" (:result-type result)))
      (is (= 1 (count (:binary-results-for-llm result))))
      (is (= "base64data" (:data (first (:binary-results-for-llm result)))))
      (is (= "image/png" (:mime-type (first (:binary-results-for-llm result)))))
      (is (= "image" (:type (first (:binary-results-for-llm result))))))))

(deftest test-convert-mcp-call-tool-result-resource
  (testing "converts resource content blocks with text and blob"
    (let [result (tools/convert-mcp-call-tool-result
                  {:content [{:type "resource"
                              :resource {:uri "file:///test.txt"
                                         :text "file content"
                                         :blob "blobdata"
                                         :mime-type "text/plain"}}]})]
      (is (= "file content" (:text-result-for-llm result)))
      (is (= 1 (count (:binary-results-for-llm result))))
      (is (= "blobdata" (:data (first (:binary-results-for-llm result)))))
      (is (= "text/plain" (:mime-type (first (:binary-results-for-llm result)))))
      (is (= "file:///test.txt" (:description (first (:binary-results-for-llm result))))))))

(deftest test-convert-mcp-call-tool-result-error
  (testing "isError maps to failure result-type"
    (let [result (tools/convert-mcp-call-tool-result
                  {:content [{:type "text" :text "something failed"}]
                   :is-error true})]
      (is (= "failure" (:result-type result)))
      (is (= "something failed" (:text-result-for-llm result))))))

(deftest test-convert-mcp-call-tool-result-mixed
  (testing "mixed content types are handled correctly"
    (let [result (tools/convert-mcp-call-tool-result
                  {:content [{:type "text" :text "preamble"}
                             {:type "image" :data "img" :mime-type "image/jpeg"}
                             {:type "resource" :resource {:uri "f" :text "res-text"}}]})]
      (is (= "preamble\nres-text" (:text-result-for-llm result)))
      (is (= 1 (count (:binary-results-for-llm result)))))))

(deftest test-convert-mcp-call-tool-result-empty
  (testing "empty content array produces empty result"
    (let [result (tools/convert-mcp-call-tool-result {:content []})]
      (is (= "" (:text-result-for-llm result)))
      (is (= "success" (:result-type result)))
      (is (nil? (:binary-results-for-llm result))))))

(deftest test-mcp-stdio-server-spec
  (testing "::mcp-stdio-server spec validates local/stdio configs"
    (is (s/valid? :github.copilot-sdk.specs/mcp-stdio-server
                  {:mcp-command "node" :mcp-args ["server.js"] :mcp-tools ["read"]}))
    (is (s/valid? :github.copilot-sdk.specs/mcp-stdio-server
                  {:mcp-command "node" :mcp-args ["server.js"] :mcp-tools ["read"]
                   :mcp-server-type :stdio}))
    (testing "upstream PR #1347: :mcp-args is optional"
      (is (s/valid? :github.copilot-sdk.specs/mcp-stdio-server
                    {:mcp-command "true" :mcp-tools ["read"]})
          ":mcp-args may be omitted (upstream PR #1347)")
      (is (s/valid? :github.copilot-sdk.specs/mcp-stdio-server
                    {:mcp-command "node" :mcp-tools ["read"] :mcp-server-type :stdio})
          ":mcp-args optional with explicit :stdio type"))))

(deftest test-mcp-http-server-spec
  (testing "::mcp-http-server spec validates remote/http configs"
    (is (s/valid? :github.copilot-sdk.specs/mcp-http-server
                  {:mcp-server-type :http :mcp-url "https://example.com" :mcp-tools ["*"]}))
    (is (s/valid? :github.copilot-sdk.specs/mcp-http-server
                  {:mcp-server-type :sse :mcp-url "https://example.com" :mcp-tools ["*"]}))))

(deftest test-custom-agent-skills-spec
  (testing "::custom-agent spec accepts optional :agent-skills field"
    (is (s/valid? :github.copilot-sdk.specs/custom-agent
                  {:agent-name "test" :agent-prompt "You are helpful"}))
    (is (s/valid? :github.copilot-sdk.specs/custom-agent
                  {:agent-name "test" :agent-prompt "You are helpful"
                   :agent-skills ["skill-a" "skill-b"]}))))

(deftest test-custom-agent-skills-on-wire
  (testing "skills field is sent on wire in session.create"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :custom-agents [{:agent-name "test-agent"
                                                  :agent-prompt "Hello"
                                                  :agent-skills ["my-skill"]}]})
          create-params (get @seen "session.create")
          agent (first (:customAgents create-params))]
      (is (= ["my-skill"] (:skills agent))))))

(deftest test-custom-agent-model-spec
  (testing "::custom-agent spec accepts optional :agent-model field"
    (is (s/valid? :github.copilot-sdk.specs/custom-agent
                  {:agent-name "test" :agent-prompt "You are helpful"}))
    (is (s/valid? :github.copilot-sdk.specs/custom-agent
                  {:agent-name "test" :agent-prompt "You are helpful"
                   :agent-model "claude-haiku-4.5"}))
    (is (not (s/valid? :github.copilot-sdk.specs/custom-agent
                       {:agent-name "test" :agent-prompt "You are helpful"
                        :agent-model 42}))
        ":agent-model must be a string when provided")))

(deftest test-custom-agent-model-on-wire
  (testing "model field is sent on wire in session.create and session.resume (upstream PR #1309)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create" "session.resume"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :custom-agents [{:agent-name "haiku-agent"
                                                  :agent-prompt "Hello"
                                                  :agent-model "claude-haiku-4.5"}]})
          session-id (sdk/get-last-session-id *test-client*)
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :custom-agents [{:agent-name "haiku-agent-2"
                                                  :agent-prompt "Hi"
                                                  :agent-model "gpt-5.4"}]})
          create-params (get @seen "session.create")
          resume-params (get @seen "session.resume")]
      (is (= "claude-haiku-4.5"
             (get-in create-params [:customAgents 0 :model])))
      (is (= "gpt-5.4"
             (get-in resume-params [:customAgents 0 :model]))))))

(deftest test-custom-agent-model-omitted-when-not-set
  (testing ":agent-model is omitted from wire when not provided"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :custom-agents [{:agent-name "no-model"
                                                  :agent-prompt "Hi"}]})
          agent (first (get-in @seen ["session.create" :customAgents]))]
      (is (not (contains? agent :model))))))

(deftest test-custom-agent-reasoning-effort-spec
  (testing "::custom-agent spec validates optional :agent-reasoning-effort"
    (is (s/valid? :github.copilot-sdk.specs/custom-agent
                  {:agent-name "test" :agent-prompt "You are helpful"
                   :agent-reasoning-effort "high"}))
    (is (s/valid? :github.copilot-sdk.specs/custom-agent
                  {:agent-name "test" :agent-prompt "You are helpful"
                   :agent-reasoning-effort "max"}))))

(deftest test-custom-agent-reasoning-effort-on-wire
  (testing "reasoning effort is sent on wire in session.create and session.resume"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create" "session.resume"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :custom-agents [{:agent-name "reasoning-agent"
                                                  :agent-prompt "Hello"
                                                  :agent-reasoning-effort "high"}]})
          session-id (sdk/get-last-session-id *test-client*)
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :custom-agents [{:agent-name "reasoning-agent-2"
                                                  :agent-prompt "Hi"
                                                  :agent-reasoning-effort "low"}]})
          create-agent (first (get-in @seen ["session.create" :customAgents]))
          resume-agent (first (get-in @seen ["session.resume" :customAgents]))]
      (is (= "high" (:reasoningEffort create-agent)))
      (is (= "low" (:reasoningEffort resume-agent)))
      (is (not (contains? create-agent :agentReasoningEffort)))
      (is (not (contains? resume-agent :agentReasoningEffort))))))

(deftest test-custom-agent-reasoning-effort-omitted-when-not-set
  (testing ":agent-reasoning-effort is omitted from wire when not provided"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (= "session.create" method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :custom-agents [{:agent-name "default-reasoning"
                                                  :agent-prompt "Hi"}]})
          agent (first (get-in @seen ["session.create" :customAgents]))]
      (is (not (contains? agent :reasoningEffort))))))

(deftest test-default-agent-excluded-tools-on-wire
  (testing "session.create forwards defaultAgent.excludedTools"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :default-agent {:excluded-tools ["private_tool" "delegate_only"]}})
          create-params (get @seen "session.create")]
      (is (= ["private_tool" "delegate_only"]
             (get-in create-params [:defaultAgent :excludedTools])))))

  (testing "session.resume forwards defaultAgent.excludedTools"
    (let [seen (atom {})
          session-id (sdk/session-id (sdk/create-session *test-client*
                                                         {:on-permission-request sdk/approve-all}))
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.resume"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :default-agent {:excluded-tools ["private_tool"]}})
          resume-params (get @seen "session.resume")]
      (is (= ["private_tool"]
             (get-in resume-params [:defaultAgent :excludedTools]))))))

(deftest test-request-permission-flag-on-create-and-resume
  (testing "requestPermission flag reflects whether a permission handler is active"
    (let [base-id (sdk/session-id (sdk/create-session *test-client* {:on-permission-request sdk/approve-all}))]
      (doseq [[desc method create! expected]
              [["session.create always sends requestPermission: true"
                "session.create"
                #(sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
                true]
               ["session.resume with default join handler sends requestPermission: false"
                "session.resume"
                #(sdk/resume-session *test-client* base-id
                                     {:on-permission-request sdk/default-join-session-permission-handler})
                false]
               ["session.resume with explicit handler sends requestPermission: true"
                "session.resume"
                #(sdk/resume-session *test-client* base-id {:on-permission-request sdk/approve-all})
                true]]]
        (testing desc
          (let [seen (atom nil)]
            (mock/set-request-hook! *mock-server*
                                    (fn [m params] (when (= method m) (reset! seen params))))
            (create!)
            (is (= expected (:requestPermission @seen)))))))))

(deftest test-session-name-get
  (testing "session-name-get calls session.name.get RPC"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/session-name-get session)
      (is (some #(= "session.name.get" (:method %)) @requests)))))

(deftest test-session-name-set
  (testing "session-name-set! calls session.name.set RPC with name param"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/session-name-set! session "My Session")
      (let [req (first (filter #(= "session.name.set" (:method %)) @requests))]
        (is (some? req))
        (is (= "My Session" (get-in req [:params :name])))))))

(deftest test-workspace-get-workspace
  (testing "workspace-get-workspace calls session.workspaces.getWorkspace RPC"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/workspace-get-workspace session)
      (is (some #(= "session.workspaces.getWorkspace" (:method %)) @requests)))))

(deftest test-mcp-discover
  (testing "mcp-discover calls mcp.discover RPC"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/mcp-discover session)
      (is (some #(= "mcp.discover" (:method %)) @requests)))))

(deftest test-mcp-discover-with-working-directory
  (testing "mcp-discover forwards working-directory param"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/mcp-discover session {:working-directory "/tmp"})
      (let [req (first (filter #(= "mcp.discover" (:method %)) @requests))]
        (is (some? req))
        (is (= "/tmp" (get-in req [:params :workingDirectory])))))))

(deftest test-usage-get-metrics
  (testing "usage-get-metrics calls session.usage.getMetrics RPC"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/usage-get-metrics session)
      (is (some #(= "session.usage.getMetrics" (:method %)) @requests)))))

(deftest test-remote-enable-rpc
  (testing "remote-enable calls session.remote.enable RPC and coerces the result to kebab-case"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          result (session/remote-enable session)]
      (is (some #(= "session.remote.enable" (:method %)) @requests))
      (is (= "https://copilot-remote.test/abc" (:url result)))
      (is (= true (:remote-steerable result))
          "wire `remoteSteerable` must arrive on the SDK side as :remote-steerable (no `?` suffix)")
      (is (s/valid? :github.copilot-sdk.specs/remote-enable-result result)))))

(deftest test-remote-disable-rpc
  (testing "remote-disable calls session.remote.disable RPC and returns nil"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          result (session/remote-disable session)]
      (is (some #(= "session.remote.disable" (:method %)) @requests))
      (is (nil? result)))))

(deftest test-remote-enable-no-mode
  (testing "remote-enable with no args sends no :mode on the wire (back-compat)"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          _ (session/remote-enable session)
          req (first (filter #(= "session.remote.enable" (:method %)) @requests))]
      (is (some? req))
      (is (not (contains? (:params req) :mode))
          "no-arg call must not include :mode in wire params"))))

(deftest test-remote-enable-with-mode
  (testing "remote-enable accepts opts {:mode :export} and forwards as wire string"
    (doseq [m [:off :export :on]]
      (let [requests (atom [])
            _ (mock/set-request-hook! *mock-server*
                                      (fn [method params]
                                        (swap! requests conj {:method method :params params})))
            session (sdk/create-session *test-client*
                                        {:on-permission-request sdk/approve-all})
            _ (session/remote-enable session {:mode m})
            req (first (filter #(= "session.remote.enable" (:method %)) @requests))]
        (is (some? req))
        (is (= (name m) (:mode (:params req)))
            (str ":mode " m " must arrive on the wire as the raw enum string"))))))

(deftest test-remote-enable-mode-spec
  (testing "::remote-session-mode accepts upstream values"
    (is (s/valid? :github.copilot-sdk.specs/remote-session-mode :off))
    (is (s/valid? :github.copilot-sdk.specs/remote-session-mode :export))
    (is (s/valid? :github.copilot-sdk.specs/remote-session-mode :on))
    (is (not (s/valid? :github.copilot-sdk.specs/remote-session-mode :bogus)))))

(deftest test-schedule-created-recurring-field
  (testing "session.schedule_created-data accepts optional :recurring boolean"
    (is (s/valid? :github.copilot-sdk.specs/session.schedule_created-data
                  {:id 1 :interval-ms 1000 :prompt "ping" :recurring true}))
    (is (s/valid? :github.copilot-sdk.specs/session.schedule_created-data
                  {:id 1 :interval-ms 1000 :prompt "ping" :recurring false}))
    (is (s/valid? :github.copilot-sdk.specs/session.schedule_created-data
                  {:id 1 :interval-ms 1000 :prompt "ping"})
        "still valid without :recurring for back-compat")
    (is (not (s/valid? :github.copilot-sdk.specs/session.schedule_created-data
                       {:id 1 :interval-ms 1000 :prompt "ping" :recurring "yes"}))
        ":recurring must be a boolean if present"))
  (testing "wire-shaped event from wire->clj round-trip validates against idiom spec"
    ;; Real upstream wire data uses `recurring` (csk does NOT append `?`)
    (let [wire-data {:id 1 :intervalMs 1000 :prompt "ping" :recurring true}
          clj-data (util/wire->clj wire-data)]
      (is (= true (:recurring clj-data))
          "wire->clj must produce :recurring (no `?` suffix)")
      (is (s/valid? :github.copilot-sdk.specs/session.schedule_created-data clj-data)
          "post-wire->clj event data must validate against the idiom spec"))))

(deftest test-user-message-is-autopilot-continuation-field
  (testing "user.message-data accepts optional :is-autopilot-continuation boolean"
    (is (s/valid? :github.copilot-sdk.specs/user.message-data
                  {:content "hello" :is-autopilot-continuation true}))
    (is (s/valid? :github.copilot-sdk.specs/user.message-data
                  {:content "hello" :is-autopilot-continuation false}))
    (is (s/valid? :github.copilot-sdk.specs/user.message-data {:content "hello"})
        "still valid without :is-autopilot-continuation for back-compat")
    (is (not (s/valid? :github.copilot-sdk.specs/user.message-data
                       {:content "hello" :is-autopilot-continuation "no"}))
        ":is-autopilot-continuation must be boolean if present"))
  (testing "wire-shaped event from wire->clj round-trip validates against idiom spec"
    (let [wire-data {:content "hi" :isAutopilotContinuation true}
          clj-data (util/wire->clj wire-data)]
      (is (= true (:is-autopilot-continuation clj-data))
          "wire->clj must produce :is-autopilot-continuation (no `?` suffix)")
      (is (s/valid? :github.copilot-sdk.specs/user.message-data clj-data)
          "post-wire->clj event data must validate against the idiom spec")))
  (testing "inbound user.message echoing wire-string :agent-mode validates"
    ;; Server echoes agentMode as the wire string ("interactive", "plan",
    ;; "autopilot", "shell"). wire->clj keeps the value as a string;
    ;; ::user.message-data must accept that without rejecting on the
    ;; caller-side keyword set.
    (doseq [mode ["interactive" "plan" "autopilot" "shell"]]
      (let [wire-data {:content "hi" :agentMode mode}
            clj-data (util/wire->clj wire-data)]
        (is (= mode (:agent-mode clj-data))
            (str "wire->clj preserves wire-string for :agent-mode " mode))
        (is (s/valid? :github.copilot-sdk.specs/user.message-data clj-data)
            (str "inbound user.message-data accepts wire-string :agent-mode " mode))))))

(deftest test-assistant-usage-api-endpoint-field
  (testing "assistant.usage-data accepts optional :api-endpoint string (open enum)"
    (is (s/valid? :github.copilot-sdk.specs/assistant.usage-data
                  {:model "gpt-5" :api-endpoint "/chat/completions"}))
    (is (s/valid? :github.copilot-sdk.specs/assistant.usage-data
                  {:model "gpt-5" :api-endpoint "/v1/messages"}))
    (is (s/valid? :github.copilot-sdk.specs/assistant.usage-data
                  {:model "gpt-5" :api-endpoint "/responses"}))
    (is (s/valid? :github.copilot-sdk.specs/assistant.usage-data
                  {:model "gpt-5" :api-endpoint "ws:/responses"}))
    (is (s/valid? :github.copilot-sdk.specs/assistant.usage-data
                  {:model "gpt-5" :api-endpoint "/future-unknown"})
        "open enum: unknown future strings should validate (forward-compat)")
    (is (s/valid? :github.copilot-sdk.specs/assistant.usage-data {:model "gpt-5"})
        "still valid without :api-endpoint")
    (is (not (s/valid? :github.copilot-sdk.specs/assistant.usage-data
                       {:model "gpt-5" :api-endpoint 42}))
        ":api-endpoint must be a string if present")))

(deftest test-assistant-usage-cache-expiration-and-service-request-id
  (testing "wire cache expiration is exposed as an Instant"
    (let [normalize @#'protocol/normalize-incoming
          raw-msg {:jsonrpc "2.0"
                   :method "session.event"
                   :params {:sessionId "abc"
                            :event {:id "evt-usage"
                                    :type "assistant.usage"
                                    :timestamp "2026-07-29T12:00:01Z"
                                    :parentId nil
                                    :data {:model "gpt-5"
                                           :cacheExpiresAt "2026-07-29T12:00:00Z"
                                           :serviceRequestId "svc-req-1"}}}}
          data (-> (normalize raw-msg)
                   (get-in [:params :event])
                   session/coerce+normalize-event
                   :data)]
      (is (= (java.time.Instant/parse "2026-07-29T12:00:00Z")
             (:cache-expires-at data)))
      (is (= "svc-req-1" (:service-request-id data)))
      (is (s/valid? :github.copilot-sdk.specs/assistant.usage-data data))))
  (testing "the idiom spec validates both optional fields"
    (is (s/valid? :github.copilot-sdk.specs/assistant.usage-data
                  {:model "gpt-5"
                   :cache-expires-at (java.time.Instant/parse "2026-07-29T12:00:00Z")
                   :service-request-id "svc-req-1"}))
    (is (not (s/valid? :github.copilot-sdk.specs/assistant.usage-data
                       {:model "gpt-5"
                        :cache-expires-at "2026-07-29T12:00:00Z"})))
    (is (not (s/valid? :github.copilot-sdk.specs/assistant.usage-data
                       {:model "gpt-5"
                        :service-request-id 42})))))

(deftest test-assistant-usage-time-to-first-token-ms
  (testing "assistant.usage-data accepts :time-to-first-token-ms (renamed from :ttft-ms)"
    (is (s/valid? :github.copilot-sdk.specs/assistant.usage-data
                  {:model "gpt-5" :time-to-first-token-ms 250}))
    (is (s/valid? :github.copilot-sdk.specs/assistant.usage-data
                  {:model "gpt-5" :time-to-first-token-ms 250.5})
        ":time-to-first-token-ms accepts fractional ms (schema 1.0.70 widened integer -> number)")
    (is (not (s/valid? :github.copilot-sdk.specs/assistant.usage-data
                       {:model "gpt-5" :time-to-first-token-ms -1}))
        ":time-to-first-token-ms must be a non-negative number")
    (is (not (s/valid? :github.copilot-sdk.specs/assistant.usage-data
                       {:model "gpt-5" :time-to-first-token-ms "fast"}))
        ":time-to-first-token-ms must be a number")
    (is (not (s/valid? :github.copilot-sdk.specs/assistant.usage-data
                       {:model "gpt-5" :time-to-first-token-ms ##NaN}))
        ":time-to-first-token-ms rejects ##NaN (not a meaningful duration)")
    (testing "legacy :ttft-ms key still accepted for backward compatibility (older CLIs)"
      (is (s/valid? :github.copilot-sdk.specs/assistant.usage-data
                    {:model "gpt-5" :ttft-ms 250}))
      (is (s/valid? :github.copilot-sdk.specs/assistant.usage-data
                    {:model "gpt-5" :ttft-ms 250 :time-to-first-token-ms 250})
          "both keys may coexist during CLI version transition"))))

(deftest test-memory-permission-event-specs
  (testing "memory action/direction/reason specs exist and validate"
    (is (s/valid? :github.copilot-sdk.specs/memory-action :store))
    (is (s/valid? :github.copilot-sdk.specs/memory-action :vote))
    (is (not (s/valid? :github.copilot-sdk.specs/memory-action :invalid)))
    (is (s/valid? :github.copilot-sdk.specs/memory-direction :upvote))
    (is (s/valid? :github.copilot-sdk.specs/memory-direction :downvote))
    (is (s/valid? :github.copilot-sdk.specs/memory-reason "some reason"))))

(deftest test-include-sub-agent-streaming-events-on-wire
  (testing "includeSubAgentStreamingEvents defaults to true in session.create (upstream PR #1108)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all})
          create-params (get @seen "session.create")]
      (is (true? (:includeSubAgentStreamingEvents create-params)))))

  (testing "includeSubAgentStreamingEvents=false is forwarded in session.create (upstream PR #1108)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :include-sub-agent-streaming-events? false})
          create-params (get @seen "session.create")]
      (is (false? (:includeSubAgentStreamingEvents create-params)))))

  (testing "includeSubAgentStreamingEvents defaults to true in session.resume (upstream PR #1108)"
    (let [seen (atom {})
          session-id (sdk/session-id (sdk/create-session *test-client* {:on-permission-request sdk/approve-all}))
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.resume"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all})
          resume-params (get @seen "session.resume")]
      (is (true? (:includeSubAgentStreamingEvents resume-params)))))

  (testing "includeSubAgentStreamingEvents=false is forwarded in session.resume (upstream PR #1108)"
    (let [seen (atom {})
          session-id (sdk/session-id (sdk/create-session *test-client* {:on-permission-request sdk/approve-all}))
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.resume"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :include-sub-agent-streaming-events? false})
          resume-params (get @seen "session.resume")]
      (is (false? (:includeSubAgentStreamingEvents resume-params))))))

(deftest test-github-token-provider-result-closed-contract
  (testing ":cancelled result permits only :kind"
    (is (s/valid? :github.copilot-sdk.specs/github-token-provider-result
                  {:kind :cancelled}))
    (is (not (s/valid? :github.copilot-sdk.specs/github-token-provider-result
                        {:kind :cancelled :reason :expired}))
        ":cancelled must reject unknown keys"))

  (testing ":token result permits only :kind, :access-token, :expires-in, and optional :token-type"
    (is (s/valid? :github.copilot-sdk.specs/github-token-provider-result
                  {:kind :token :access-token "token" :expires-in 3601}))
    (is (s/valid? :github.copilot-sdk.specs/github-token-provider-result
                  {:kind :token :access-token "token" :expires-in 3601
                   :token-type "Bearer"}))
    (is (not (s/valid? :github.copilot-sdk.specs/github-token-provider-result
                        {:kind :token :access-token "token" :expires-in 3601
                         :account-label "enterprise"}))
        ":token must reject unknown keys")))
