(ns github.copilot-sdk.integration.hooks-handlers-test
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

(defn- observe-channel-resolution!
  [source-ch completion]
  (let [forwarded-ch (chan 1)]
    (go
      (try
        (when-some [value (async/<! source-ch)]
          (async/>! forwarded-ch value))
        (finally
          (close! forwarded-ch)
          (deliver completion true))))
    forwarded-ch))

(deftest test-hooks-pre-tool-use
  (testing "hooks.invoke preToolUse calls registered handler and returns result"
    (let [handler-called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-pre-tool-use
                                               (fn [input ctx]
                                                 (reset! handler-called {:input input :ctx ctx})
                                                 {:permission-decision "allow"
                                                  :additional-context "extra info"})}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "preToolUse"
                                            :input {:toolName "bash"
                                                    :toolArgs {:command "echo hi"}
                                                    :timestamp 12345
                                                    :cwd "/workspace"}})]
      (is (some? @handler-called))
      ;; Input keys are converted to kebab-case by wire->clj
      (is (= "bash" (get-in @handler-called [:input :tool-name])))
      (is (= {:command "echo hi"} (get-in @handler-called [:input :tool-args])))
      (is (= session-id (get-in @handler-called [:ctx :session-id])))
      ;; HookInvokeResponse wraps the handler's return value under output.
      (is (= "allow" (get-in response [:result :output :permissionDecision]))))))

(deftest test-hooks-agent-stop
  (testing "hooks.invoke agentStop calls the registered handler and returns a block decision"
    (let [handler-called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-agent-stop
                                               (fn [input ctx]
                                                 (reset! handler-called {:input input :ctx ctx})
                                                 {:decision "block"
                                                  :reason "fix the remaining findings"})}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "agentStop"
                                            :input {:stopReason "end_turn"
                                                    :transcriptPath "/tmp/transcript.jsonl"
                                                    :stop_hook_active true
                                                    :timestamp 1700000000000
                                                    :cwd "/workspace"}})]
      (is (s/get-spec ::specs/on-agent-stop))
      (is (= {:stop-reason "end_turn"
              :transcript-path "/tmp/transcript.jsonl"
              :stop-hook-active true
              :timestamp 1700000000000
              :cwd "/workspace"
              :session-id session-id}
             (:input @handler-called)))
      (is (= {:session-id session-id} (:ctx @handler-called)))
      (is (= {:decision "block" :reason "fix the remaining findings"}
             (get-in response [:result :output])))))
  (testing "nil and handler errors both let the agent stop"
    (doseq [handler [(fn [_ _] nil)
                     (fn [_ _] (throw (Exception. "agent-stop failed")))]]
      (let [session (sdk/create-session *test-client*
                                        {:on-permission-request sdk/approve-all
                                         :hooks {:on-agent-stop handler}})
            response (mock/send-rpc-request! *mock-server*
                                             "hooks.invoke"
                                             {:sessionId (sdk/session-id session)
                                              :hookType "agentStop"
                                              :input {:timestamp 1700000000000
                                                      :cwd "/workspace"}})]
        (is (= {} (:result response)))))))

(deftest test-hooks-user-prompt-transformed
  (testing "hooks.invoke userPromptTransformed calls the registered handler"
    (let [handler-called (atom nil)
          copilot-session
          (sdk/create-session
           *test-client*
           {:on-permission-request sdk/approve-all
            :hooks {:on-user-prompt-transformed
                    (fn [input ctx]
                      (reset! handler-called {:input input :ctx ctx})
                      {:modified-transformed-prompt "rewritten prompt"})}})
          session-id (sdk/session-id copilot-session)
          response (mock/send-rpc-request!
                    *mock-server*
                    "hooks.invoke"
                    {:sessionId session-id
                     :hookType "userPromptTransformed"
                     :input {:prompt "original"
                             :transformedPrompt "generated context\noriginal"
                             :timestamp 1700000000000
                             :cwd "/workspace"}})]
      (is (s/get-spec ::specs/on-user-prompt-transformed))
      (is (= "original" (get-in @handler-called [:input :prompt])))
      (is (= "generated context\noriginal"
             (get-in @handler-called [:input :transformed-prompt])))
      (is (= {:session-id session-id} (:ctx @handler-called)))
      (is (= "rewritten prompt"
             (get-in response [:result :output :modifiedTransformedPrompt]))))))

(deftest test-hooks-post-tool-use
  (testing "hooks.invoke postToolUse calls registered handler"
    (let [handler-called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-post-tool-use
                                               (fn [input ctx]
                                                 (reset! handler-called {:input input :ctx ctx})
                                                 nil)}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "postToolUse"
                                            :input {:toolName "bash"
                                                    :toolArgs {}
                                                    :toolResult {:textResultForLlm "ok"
                                                                 :resultType "success"}
                                                    :timestamp 12345
                                                    :cwd "/workspace"}})]
      (is (some? @handler-called))
      (is (= "bash" (get-in @handler-called [:input :tool-name])))
      ;; Handler returned nil, so the response has no output.
      (is (= {} (:result response))))))

(deftest test-hooks-post-tool-use-failure
  (testing "hooks.invoke postToolUseFailure calls registered handler (upstream PR #1421)"
    (let [handler-called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-post-tool-use-failure
                                               (fn [input ctx]
                                                 (reset! handler-called {:input input :ctx ctx})
                                                 {:additional-context "noted"})}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "postToolUseFailure"
                                            :input {:toolName "bash"
                                                    :toolArgs {:command "false"}
                                                    :error "command exited 1"
                                                    :timestamp 12345
                                                    :cwd "/workspace"}})]
      (is (some? @handler-called))
      (is (= "bash" (get-in @handler-called [:input :tool-name])))
      (is (= "command exited 1" (get-in @handler-called [:input :error])))
      (is (= session-id (get-in @handler-called [:input :session-id])))
      (is (= "noted" (get-in response [:result :output :additionalContext]))))))

(deftest test-hooks-post-tool-use-failure-no-handler
  (testing "hooks.invoke postToolUseFailure with no handler returns an empty response"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       ;; Only success hook registered; failure should pass through without output.
                                       :hooks {:on-post-tool-use
                                               (fn [_ _] nil)}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "postToolUseFailure"
                                            :input {:toolName "bash"
                                                    :toolArgs {}
                                                    :error "boom"
                                                    :timestamp 12345
                                                    :cwd "/workspace"}})]
      (is (= {} (:result response))))))

(deftest test-hooks-session-start
  (testing "hooks.invoke sessionStart calls registered handler"
    (let [handler-called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-session-start
                                               (fn [input ctx]
                                                 (reset! handler-called input)
                                                 {:additional-context "welcome"})}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "sessionStart"
                                            :input {:source "new"
                                                    :timestamp 12345
                                                    :cwd "/workspace"}})]
      (is (some? @handler-called))
      (is (= "new" (:source @handler-called)))
      (is (= "welcome" (get-in response [:result :output :additionalContext]))))))

(deftest test-hooks-unknown-type-returns-empty-response
  (testing "hooks.invoke with unknown hook type returns an empty response"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-pre-tool-use (fn [_ _] {:permission-decision "allow"})}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "unknownHookType"
                                            :input {:timestamp 12345
                                                    :cwd "/workspace"}})]
      (is (= {} (:result response))))))

(deftest test-hooks-handler-exception-returns-empty-response
  (testing "hooks.invoke handler exception returns an empty response"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-pre-tool-use (fn [_ _] (throw (Exception. "oops")))}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "preToolUse"
                                            :input {:toolName "bash"
                                                    :toolArgs {}
                                                    :timestamp 12345
                                                    :cwd "/workspace"}})]
      (is (= {} (:result response))))))

(deftest test-hooks-no-hooks-registered
  (testing "hooks.invoke with no hooks registered returns an empty response"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "preToolUse"
                                            :input {:toolName "bash"
                                                    :toolArgs {}
                                                    :timestamp 12345
                                                    :cwd "/workspace"}})]
      (is (= {} (:result response))))))

(deftest test-hooks-unknown-session
  (testing "hooks.invoke with an unknown session returns an RPC error"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Unknown session: missing-session"
         (mock/send-rpc-request! *mock-server*
                                 "hooks.invoke"
                                 {:sessionId "missing-session"
                                  :hookType "agentStop"
                                  :input {:timestamp 1700000000000
                                          :cwd "/workspace"}})))))

(deftest test-hooks-input-exposes-session-id
  (testing "hook input includes :session-id (upstream PR #1290 — BaseHookInput.sessionId)"
    (let [handler-called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-pre-tool-use
                                               (fn [input _ctx]
                                                 (reset! handler-called input)
                                                 nil)}})
          session-id (sdk/session-id session)
          _ (mock/send-rpc-request! *mock-server*
                                    "hooks.invoke"
                                    {:sessionId session-id
                                     :hookType "preToolUse"
                                     :input {:toolName "bash"
                                             :toolArgs {}
                                             :sessionId session-id
                                             :timestamp 12345
                                             :cwd "/workspace"}})]
      (is (some? @handler-called))
      (is (= session-id (:session-id @handler-called)))))

  (testing "hook input :session-id preserves wire-provided value (sub-agent case)"
    (let [handler-called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-pre-tool-use
                                               (fn [input _ctx]
                                                 (reset! handler-called input)
                                                 nil)}})
          parent-session-id (sdk/session-id session)
          sub-agent-session-id "sub-agent-session-xyz"
          _ (mock/send-rpc-request! *mock-server*
                                    "hooks.invoke"
                                    {:sessionId parent-session-id
                                     :hookType "preToolUse"
                                     :input {:toolName "bash"
                                             :toolArgs {}
                                             :sessionId sub-agent-session-id
                                             :timestamp 12345
                                             :cwd "/workspace"}})]
      (is (some? @handler-called))
      (is (= sub-agent-session-id (:session-id @handler-called)))))

  (testing "hook input :session-id falls back to outer session-id when wire omits it"
    (let [handler-called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-pre-tool-use
                                               (fn [input _ctx]
                                                 (reset! handler-called input)
                                                 nil)}})
          session-id (sdk/session-id session)
          _ (mock/send-rpc-request! *mock-server*
                                    "hooks.invoke"
                                    {:sessionId session-id
                                     :hookType "preToolUse"
                                     :input {:toolName "bash"
                                             :toolArgs {}
                                             :timestamp 12345
                                             :cwd "/workspace"}})]
      (is (some? @handler-called))
      (is (= session-id (:session-id @handler-called))))))

(deftest test-hooks-pre-mcp-tool-call-input-shape
  (testing "preMcpToolCall: handler receives kebab-cased base fields + opaque arguments/_meta"
    (let [handler-called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-pre-mcp-tool-call
                                               (fn [input ctx]
                                                 (reset! handler-called {:input input :ctx ctx})
                                                 nil)}})
          session-id (sdk/session-id session)
          opaque-args {:filePath "/tmp/foo.txt"
                       :user_id 42
                       :nested {:keepCamelCase true}}
          opaque-meta {:traceId "abc-123" :foo_bar "ok"}
          _ (mock/send-rpc-request! *mock-server*
                                    "hooks.invoke"
                                    {:sessionId session-id
                                     :hookType "preMcpToolCall"
                                     :input {:serverName "my-mcp"
                                             :toolName "fetch"
                                             :toolCallId "call-42"
                                             :arguments opaque-args
                                             :_meta opaque-meta
                                             :timestamp 12345
                                             :cwd "/workspace"
                                             :sessionId session-id}})]
      (is (some? @handler-called))
      ;; Base fields are kebab-cased
      (is (= "my-mcp" (get-in @handler-called [:input :server-name])))
      (is (= "fetch" (get-in @handler-called [:input :tool-name])))
      (is (= "call-42" (get-in @handler-called [:input :tool-call-id])))
      (is (= session-id (get-in @handler-called [:input :session-id])))
      (is (= 12345 (get-in @handler-called [:input :timestamp])))
      ;; Opaque arguments preserved verbatim (wire-keyword shape, NOT kebab-cased)
      (is (= opaque-args (get-in @handler-called [:input :arguments])))
      ;; _meta key preserved verbatim (kebab conversion would strip leading _)
      (is (= opaque-meta (get-in @handler-called [:input :_meta]))))))

(deftest test-hooks-pre-mcp-tool-call-output-meta-to-use-object
  (testing "preMcpToolCall: :meta-to-use map becomes metaToUse on wire with opaque inner contents"
    (let [opaque-replacement {:newTraceId "xyz-789" :keep_snake "yes"}
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-pre-mcp-tool-call
                                               (fn [_ _]
                                                 {:meta-to-use opaque-replacement})}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "preMcpToolCall"
                                            :input {:serverName "my-mcp"
                                                    :toolName "fetch"
                                                    :arguments {}
                                                    :timestamp 12345
                                                    :cwd "/workspace"
                                                    :sessionId session-id}})]
      ;; The wire field name is metaToUse, NOT meta-to-use
      (is (contains? (get-in response [:result :output]) :metaToUse))
      (is (not (contains? (get-in response [:result :output]) :meta-to-use)))
      ;; Inner map preserved verbatim — inner keys NOT camelCased
      (is (= opaque-replacement (get-in response [:result :output :metaToUse]))))))

(deftest test-hooks-pre-mcp-tool-call-output-meta-to-use-null
  (testing "preMcpToolCall: :meta-to-use nil serializes as JSON null (key present with null value)"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-pre-mcp-tool-call
                                               (fn [_ _]
                                                 {:meta-to-use nil})}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "preMcpToolCall"
                                            :input {:serverName "my-mcp"
                                                    :toolName "fetch"
                                                    :arguments {}
                                                    :timestamp 12345
                                                    :cwd "/workspace"
                                                    :sessionId session-id}})]
      ;; The metaToUse key MUST be present (not absent) and its value MUST be null.
      (is (contains? (get-in response [:result :output]) :metaToUse))
      (is (nil? (get-in response [:result :output :metaToUse]))))))

(deftest test-hooks-pre-mcp-tool-call-output-no-meta-to-use
  (testing "preMcpToolCall: handler returning {} omits metaToUse field"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :hooks {:on-pre-mcp-tool-call
                                               (fn [_ _] {})}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "hooks.invoke"
                                           {:sessionId session-id
                                            :hookType "preMcpToolCall"
                                            :input {:serverName "my-mcp"
                                                    :toolName "fetch"
                                                    :arguments {}
                                                    :timestamp 12345
                                                    :cwd "/workspace"
                                                    :sessionId session-id}})]
      (is (= {:output {}} (:result response)))
      (is (not (contains? (get-in response [:result :output]) :metaToUse))))))

(deftest test-user-input-handler-invoked
  (testing "userInput.request calls registered handler with correct shape"
    (let [handler-called (atom nil)
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :on-user-input-request
                                       (fn [request ctx]
                                         (reset! handler-called {:request request :ctx ctx})
                                         {:answer "option A" :was-freeform false})})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "userInput.request"
                                           {:sessionId session-id
                                            :question "Which option?"
                                            :choices ["option A" "option B"]
                                            :allowFreeform true})]
      (is (some? @handler-called))
      (is (= "Which option?" (get-in @handler-called [:request :question])))
      (is (= "option A" (get-in response [:result :answer])))
      (is (false? (get-in response [:result :wasFreeform]))))))

(deftest test-user-input-no-handler-errors
  (testing "userInput.request without handler returns error"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"User input requested but no handler registered"
                            (mock/send-rpc-request! *mock-server*
                                                    "userInput.request"
                                                    {:sessionId session-id
                                                     :question "Which option?"}))))))

(deftest test-system-message-transform-callback
  (testing "systemMessage.transform invokes registered transform callbacks"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :system-message {:mode :customize
                                                        :sections {:identity {:action (fn [content]
                                                                                        (str content " EXTRA"))}}}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "systemMessage.transform"
                                           {:sessionId session-id
                                            :sections {:identity {:content "I am an agent."}}})]
      (is (= "I am an agent. EXTRA"
             (get-in response [:result :sections :identity :content]))))))

(deftest test-system-message-transform-error-returns-original
  (testing "systemMessage.transform returns original content on callback error"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :system-message {:mode :customize
                                                        :sections {:identity {:action (fn [_] (throw (Exception. "fail")))}}}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "systemMessage.transform"
                                           {:sessionId session-id
                                            :sections {:identity {:content "original text"}}})]
      (is (= "original text"
             (get-in response [:result :sections :identity :content]))))))

(deftest test-system-message-transform-no-callback-passthrough
  (testing "systemMessage.transform passes through sections without callbacks"
    (let [session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all
                                       :system-message {:mode :customize
                                                        :sections {:identity {:action (fn [c] (str c "!"))}}}})
          session-id (sdk/session-id session)
          response (mock/send-rpc-request! *mock-server*
                                           "systemMessage.transform"
                                           {:sessionId session-id
                                            :sections {:identity {:content "hello"}
                                                       :tone {:content "be nice"}}})]
      (is (= "hello!" (get-in response [:result :sections :identity :content])))
      (is (= "be nice" (get-in response [:result :sections :tone :content]))))))

(deftest test-tool-search-invocation-metadata
  (letfn [(invoke-tool! [tool-name metadata-response]
            (let [requests (atom [])
                  invocation-promise (promise)
                  rpc-latch (java.util.concurrent.CountDownLatch. 1)
                  _ (mock/set-current-tool-metadata-response!
                     *mock-server*
                     metadata-response)
                  _ (mock/set-request-hook!
                     *mock-server*
                     (fn [method params]
                       (swap! requests conj {:method method :params params})
                       (when (= "session.tools.handlePendingToolCall" method)
                         (.countDown rpc-latch))))
                  session (sdk/create-session
                           *test-client*
                           {:on-permission-request sdk/approve-all
                            :tools [{:tool-name tool-name
                                     :tool-handler
                                     (fn [_args invocation]
                                       (deliver invocation-promise invocation)
                                       "ok")}]})
                  session-id (sdk/session-id session)]
              (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
              (mock/send-v3-broadcast-event!
               *mock-server*
               session-id
               "external_tool.requested"
               {:requestId (str "request-" tool-name)
                :toolName tool-name
                :toolCallId (str "call-" tool-name)
                :arguments {}})
              (let [invocation (deref invocation-promise 5000
                                      :github.copilot-sdk.integration-test/timeout)]
                (is (not= :github.copilot-sdk.integration-test/timeout invocation))
                (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS))
                {:invocation invocation
                 :requests @requests})))]
    (testing "tool_search_tool receives the current tool metadata snapshot"
      (let [metadata [{:name "github-tool"
                       :namespacedName "github/github-tool"
                       :mcpServerName "github"
                       :mcpToolName "github_tool"
                       :description "Search GitHub"
                       :input_schema {:type "object"}
                       :deferLoading true}]
            {:keys [invocation requests]}
            (invoke-tool! "tool_search_tool" {:tools metadata})]
        (is (= [{:name "github-tool"
                 :namespaced-name "github/github-tool"
                 :mcp-server-name "github"
                 :mcp-tool-name "github_tool"
                 :description "Search GitHub"
                 :input-schema {:type "object"}
                 :defer-loading true}]
               (:available-tools invocation)))
        (is (= 1 (count (filter #(= "session.tools.getCurrentMetadata"
                                    (:method %))
                                requests))))))

    (testing "ordinary tools do not request current tool metadata"
      (let [{:keys [invocation requests]}
            (invoke-tool! "ordinary-tool"
                          {:tools [{:name "unused"
                                    :description "Unused"}]})]
        (is (not (contains? invocation :available-tools)))
        (is (empty? (filter #(= "session.tools.getCurrentMetadata"
                                (:method %))
                            requests)))))

    (testing "metadata lookup failure does not fail tool invocation"
      (let [{:keys [invocation requests]}
            (invoke-tool! "tool_search_tool"
                          (ex-info "metadata unavailable" {:code -32000}))]
        (is (not (contains? invocation :available-tools)))
        (is (= 1 (count (filter #(= "session.tools.getCurrentMetadata"
                                    (:method %))
                                requests))))))))

(deftest test-external-tool-completion-cancels-in-flight-handler
  (let [requests (atom [])
        invocation-ready (promise)
        finish-handler (promise)
        processing-completed (promise)
        real-handle-tool-call!
        (var-get #'session/handle-registered-tool-call!)
        _ (mock/set-request-hook!
           *mock-server*
           (fn [method params]
             (swap! requests conj {:method method :params params})))
        copilot-session
        (sdk/create-session
         *test-client*
         {:on-permission-request sdk/approve-all
          :tools [{:tool-name "slow-tool"
                   :tool-handler
                   (fn [_ invocation]
                     (deliver invocation-ready invocation)
                     @finish-handler
                     "late result")}]})
        session-id (sdk/session-id copilot-session)
        events-ch (sdk/subscribe-events copilot-session)
        request-id "cancelled-request"]
    (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
    (reset! requests [])
    (with-redefs-fn
      {#'session/handle-registered-tool-call!
       (fn [& args]
         (observe-channel-resolution!
          (apply real-handle-tool-call! args)
          processing-completed))}
      (fn []
        (try
          (mock/send-v3-broadcast-event!
           *mock-server* session-id "external_tool.requested"
           {:requestId request-id
            :toolName "slow-tool"
            :toolCallId "cancelled-call"
            :arguments {}})
          (let [invocation (await-value! invocation-ready
                                         "external tool invocation"
                                         1000)
                cancel-ch (:cancel-chan invocation)]
            (is (s/valid? ::specs/tool-invocation invocation))
            (is (some? cancel-ch))
            (is (false? (async-protocols/closed? cancel-ch)))
            (await-atom! (:state *test-client*)
                         #(get-in % [:sessions session-id
                                     :pending-external-tools request-id])
                         "pending external tool registration"
                         1000)
            (mock/send-v3-broadcast-event!
             *mock-server* session-id "external_tool.completed"
             {:requestId request-id})
            (let [completion-event
                  (await-event-type!
                   events-ch :copilot/external_tool.completed 1000)]
              (is (= request-id (get-in completion-event
                                        [:data :request-id]))))
            (is (nil? (get-in @(:state *test-client*)
                              [:sessions session-id
                               :pending-external-tools request-id]))
                "completion cancellation must precede event publication")
            (is (async-protocols/closed? cancel-ch))
            (deliver finish-handler true)
            (is (true? (await-value! processing-completed
                                     "cancelled external tool processing"
                                     1000)))
            (is (empty?
                 (filter #(= "session.tools.handlePendingToolCall"
                             (:method %))
                         @requests))))
          (finally
            (deliver finish-handler true)
            (sdk/unsubscribe-events! copilot-session events-ch)))))))

(deftest test-successful-resume-releases-superseded-session-resources
  (doseq [async? [false true]]
    (testing (str (if async? "async" "sync")
                  " resume closes the displaced session's resources")
      (let [invocation-ready (promise)
            session-id (str "resume-resource-release-" async?)
            old-session
            (sdk/create-session
             *test-client*
             {:session-id session-id
              :on-permission-request sdk/approve-all
              :tools [{:tool-name "blocked-resume-tool"
                       :tool-handler
                       (fn [_ {:keys [cancel-chan] :as invocation}]
                         (deliver invocation-ready invocation)
                         (<!! cancel-chan)
                         "cancelled")}]})
            old-events (sdk/subscribe-events old-session)]
        (mock/send-v3-broadcast-event!
         *mock-server*
         session-id
         "external_tool.requested"
         {:requestId (str "resume-request-" async?)
          :toolName "blocked-resume-tool"
          :toolCallId (str "resume-call-" async?)
          :arguments {}})
        (let [invocation
              (await-value! invocation-ready
                            "pre-resume external tool invocation"
                            1000)
              _ (await-event-type!
                 old-events :copilot/external_tool.requested 1000)
              resumed
              (if async?
                (<!! (sdk/<resume-session
                      *test-client*
                      session-id
                      {:on-permission-request sdk/approve-all}))
                (sdk/resume-session
                 *test-client*
                 session-id
                 {:on-permission-request sdk/approve-all}))]
          (is (not (instance? Throwable resumed)))
          (await-atom! (:state *test-client*)
                       (fn [_]
                         (async-protocols/closed?
                          (:cancel-chan invocation)))
                       "superseded tool cancellation"
                       1000)
          (let [[value port]
                (alts!! [old-events (timeout 1000)] :priority true)]
            (is (= old-events port))
            (is (nil? value))))))))

(deftest test-tool-request-registration-is-bound-to-displaced-session
  (doseq [async? [false true]]
    (testing (str (if async? "async" "sync")
                  " request keeps the handler present at event ingress")
      (let [session-id (str "tool-registration-boundary-" async?)
            registration-entered (promise)
            release-registration (promise)
            resume-rpc-entered (promise)
            release-resume-rpc (promise)
            old-invocation (promise)
            release-old-handler (promise)
            old-calls (atom 0)
            new-calls (atom 0)
            old-handler
            (fn [_ invocation]
              (swap! old-calls inc)
              (deliver old-invocation invocation)
              @release-old-handler
              "old")
            new-handler
            (fn [_ _]
              (swap! new-calls inc)
              "new")
            real-register
            (var-get #'session/register-pending-external-tool!)]
        (sdk/create-session
         *test-client*
         {:session-id session-id
          :on-permission-request sdk/approve-all
          :tools [{:tool-name "generation-tool"
                   :tool-handler old-handler}]})
        (mock/set-request-hook!
         *mock-server*
         (fn [method _]
           (when (= "session.resume" method)
             (deliver resume-rpc-entered true)
             @release-resume-rpc)))
        (with-redefs-fn
          {#'session/register-pending-external-tool!
           (fn [& args]
             (deliver registration-entered true)
             @release-registration
             (apply real-register args))}
          (fn []
            (try
              (future
                (@#'client/handle-v3-tool-requested!
                 *test-client*
                 session-id
                 {:data {:request-id "boundary-request"
                         :tool-name "generation-tool"
                         :tool-call-id "boundary-call"
                         :arguments {}}}))
              (is (true? (await-value! registration-entered
                                       "tool registration boundary"
                                       1000)))
              (let [resume-result (promise)]
                (future
                  (deliver
                   resume-result
                   (try
                     (if async?
                       (<!! (sdk/<resume-session
                             *test-client*
                             session-id
                             {:on-permission-request sdk/approve-all
                              :tools [{:tool-name "generation-tool"
                                       :tool-handler new-handler}]}))
                       (sdk/resume-session
                        *test-client*
                        session-id
                        {:on-permission-request sdk/approve-all
                         :tools [{:tool-name "generation-tool"
                                  :tool-handler new-handler}]}))
                     (catch Throwable failure
                       failure))))
                (is (true? (await-value! resume-rpc-entered
                                         "provisional resume"
                                         1000)))
                (deliver release-registration true)
                (let [invocation
                      (await-value! old-invocation
                                    "displaced session tool handler"
                                    1000)
                      state
                      (await-atom!
                       (:state *test-client*)
                       #(get-in
                         %
                         [:session-setup-snapshots session-id
                          :snapshot :session
                          :pending-external-tools "boundary-request"])
                       "displaced pending tool"
                       1000)
                      pending
                      (get-in
                       state
                       [:session-setup-snapshots session-id
                        :snapshot :session
                        :pending-external-tools "boundary-request"])]
                  (is (= 1 @old-calls))
                  (is (zero? @new-calls))
                  (is (identical? old-handler (:handler pending)))
                  (deliver release-resume-rpc true)
                  (is (not (instance?
                            Throwable
                            (await-value! resume-result
                                          "resume completion"
                                          1000))))
                  (await-atom!
                   (:state *test-client*)
                   #(not (contains?
                          (:session-setup-snapshots %)
                          session-id))
                   "displaced session release"
                   1000)
                  (is (async-protocols/closed?
                       (:cancel-chan invocation)))
                  (is (zero? @new-calls))))
              (finally
                (deliver release-registration true)
                (deliver release-resume-rpc true)
                (deliver release-old-handler true)))))))))

(deftest test-tool-completion-updates-displaced-session-before-restore
  (doseq [async? [false true]]
    (testing (str (if async? "async" "sync")
                  " completion is retained across failed provisional resume")
      (let [session-id (str "tool-completion-restore-" async?)
            invocation-ready (promise)
            processing-completed (promise)
            resume-rpc-entered (promise)
            release-resume-rpc (promise)
            old-session
            (sdk/create-session
             *test-client*
             {:session-id session-id
              :on-permission-request sdk/approve-all
              :tools [{:tool-name "completing-tool"
                       :tool-handler
                       (fn [_ {:keys [cancel-chan] :as invocation}]
                         (deliver invocation-ready invocation)
                         (<!! cancel-chan)
                         "cancelled")}]})
            old-registration-token
            (session/registration-token old-session)
            old-session-io
            (get-in @(:state *test-client*) [:session-io session-id])
            real-handle
            (var-get #'session/handle-registered-tool-call!)]
        (mock/set-request-hook!
         *mock-server*
         (fn [method _]
           (when (= "session.resume" method)
             (deliver resume-rpc-entered true)
             @release-resume-rpc
             (throw
              (ex-info "resume rejected"
                       {:code -32000})))))
        (with-redefs-fn
          {#'session/handle-registered-tool-call!
           (fn [& args]
             (observe-channel-resolution!
              (apply real-handle args)
              processing-completed))}
          (fn []
            (try
              (@#'client/handle-v3-tool-requested!
               *test-client*
               session-id
               {:data {:request-id "completed-while-displaced"
                       :tool-name "completing-tool"
                       :tool-call-id "completed-call"
                       :arguments {}}})
              (let [invocation
                    (await-value! invocation-ready
                                  "pre-resume tool invocation"
                                  1000)
                    resume-result (promise)]
                (future
                  (deliver
                   resume-result
                   (try
                     (if async?
                       (<!! (sdk/<resume-session
                             *test-client*
                             session-id
                             {:on-permission-request sdk/approve-all}))
                       (sdk/resume-session
                        *test-client*
                        session-id
                        {:on-permission-request sdk/approve-all}))
                     (catch Throwable failure
                       failure))))
                (is (true? (await-value! resume-rpc-entered
                                         "provisional resume"
                                         1000)))
                (is (some?
                     (get-in
                      @(:state *test-client*)
                      [:session-setup-snapshots session-id
                       :snapshot :session
                       :pending-external-tools
                       "completed-while-displaced"])))
                (@#'client/handle-v3-tool-completed!
                 *test-client*
                 session-id
                 {:data {:request-id "completed-while-displaced"}})
                (is (async-protocols/closed?
                     (:cancel-chan invocation)))
                (is (true? (await-value! processing-completed
                                         "displaced tool completion"
                                         1000)))
                (is (nil?
                     (get-in
                      @(:state *test-client*)
                      [:session-setup-snapshots session-id
                       :snapshot :session
                       :pending-external-tools
                       "completed-while-displaced"])))
                (deliver release-resume-rpc true)
                (is (instance?
                     Throwable
                     (await-value! resume-result
                                   "failed resume"
                                   1000)))
                (let [state @(:state *test-client*)]
                  (is (identical?
                       old-registration-token
                       (get-in state
                               [:sessions session-id
                                :registration-token])))
                  (is (identical?
                       old-session-io
                       (get-in state [:session-io session-id])))
                  (is (nil?
                       (get-in
                        state
                        [:sessions session-id
                         :pending-external-tools
                         "completed-while-displaced"])))
                  (is (not (contains?
                            (:session-setup-snapshots state)
                            session-id)))))
              (finally
                (deliver release-resume-rpc true)))))))))

(deftest test-external-tool-result-cannot-cross-connection-generations
  (let [result-ch (chan 1)
        invocation-ready (promise)
        ownership-checked (promise)
        ownership-checks (atom 0)
        sent-connections (atom [])
        processing-completed (promise)
        old-connection (:connection-io @(:state *test-client*))
        replacement-connection (Object.)
        real-handle
        (var-get #'session/handle-registered-tool-call!)
        real-registration-current?
        (var-get #'session/session-registration-current?)
        copilot-session
        (sdk/create-session
         *test-client*
         {:on-permission-request sdk/approve-all
          :tools [{:tool-name "generation-bound-tool"
                   :tool-handler
                   (fn [_ invocation]
                     (deliver invocation-ready invocation)
                     result-ch)}]})
        session-id (sdk/session-id copilot-session)]
    (with-redefs-fn
      {#'session/handle-registered-tool-call!
       (fn [& args]
         (observe-channel-resolution!
          (apply real-handle args)
          processing-completed))
       #'session/session-registration-current?
       (fn [& args]
         (let [current? (apply real-registration-current? args)]
           (when (= 1 (swap! ownership-checks inc))
             (deliver ownership-checked current?))
           current?))
       #'protocol/send-request
       (fn [connection-io method _]
         (when (= "session.tools.handlePendingToolCall" method)
           (swap! sent-connections conj connection-io))
         (doto (chan 1)
           (async/put! {:result {}})
           close!))}
      (fn []
        (try
          (@#'client/handle-v3-tool-requested!
           *test-client*
           session-id
           {:data {:request-id "generation-bound-request"
                   :tool-name "generation-bound-tool"
                   :tool-call-id "generation-bound-call"
                   :arguments {}}})
          (await-value! invocation-ready
                        "generation-bound tool invocation"
                        1000)
          (swap! (:state *test-client*)
                 assoc :connection-io replacement-connection)
          (>!! result-ch "stale result")
          (is (false? (await-value! ownership-checked
                                    "tool response ownership check"
                                    1000)))
          (is (true? (await-value! processing-completed
                                   "generation-bound tool processing"
                                   1000)))
          (is (empty? @sent-connections))
          (finally
            (swap! (:state *test-client*)
                   assoc :connection-io old-connection)
            (close! result-ch)))))))

(deftest test-duplicate-external-tool-request-is-handled-once
  (let [handler-calls (atom 0)
        registration-attempts (atom 0)
        duplicate-checked (promise)
        invocation-ready (promise)
        finish-handler (promise)
        response-latch (java.util.concurrent.CountDownLatch. 1)
        requests (atom [])
        real-register (var-get #'session/register-pending-external-tool!)
        _ (mock/set-request-hook!
           *mock-server*
           (fn [method params]
             (swap! requests conj {:method method :params params})
             (when (= "session.tools.handlePendingToolCall" method)
               (.countDown response-latch))))
        copilot-session
        (sdk/create-session
         *test-client*
         {:on-permission-request sdk/approve-all
          :tools [{:tool-name "once-tool"
                   :tool-handler
                   (fn [_ invocation]
                     (swap! handler-calls inc)
                     (deliver invocation-ready invocation)
                     @finish-handler
                     "ok")}]})
        session-id (sdk/session-id copilot-session)
        event {:requestId "duplicate-request"
               :toolName "once-tool"
               :toolCallId "duplicate-call"
               :arguments {}}]
    (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
    (reset! requests [])
    (with-redefs-fn
      {#'session/register-pending-external-tool!
       (fn [& args]
         (let [attempt (swap! registration-attempts inc)
               result (apply real-register args)]
           (when (= 2 attempt)
             (deliver duplicate-checked true))
           result))}
      (fn []
        (try
          (mock/send-v3-broadcast-event!
           *mock-server* session-id "external_tool.requested" event)
          (let [invocation (await-value! invocation-ready
                                         "first external tool invocation"
                                         1000)
                cancel-ch (:cancel-chan invocation)]
            (mock/send-v3-broadcast-event!
             *mock-server* session-id "external_tool.requested" event)
            (is (true? (await-value! duplicate-checked
                                     "duplicate registration check"
                                     1000)))
            (deliver finish-handler true)
            (is (.await response-latch 5 java.util.concurrent.TimeUnit/SECONDS))
            (is (= 1 @handler-calls))
            (is (= 1 (count
                      (filter #(= "session.tools.handlePendingToolCall"
                                  (:method %))
                              @requests))))
            (is (async-protocols/closed? cancel-ch)))
          (finally
            (deliver finish-handler true)))))))

(deftest test-distinct-external-tool-requests-may-share-a-tool-call-id
  (let [handler-started (java.util.concurrent.CountDownLatch. 2)
        release-handlers (promise)
        responses (java.util.concurrent.CountDownLatch. 2)
        requests (atom [])
        _ (mock/set-request-hook!
           *mock-server*
           (fn [method params]
             (when (= "session.tools.handlePendingToolCall" method)
               (swap! requests conj params)
               (.countDown responses))))
        copilot-session
        (sdk/create-session
         *test-client*
         {:on-permission-request sdk/approve-all
          :tools [{:tool-name "shared-call-tool"
                   :tool-handler
                   (fn [_ _]
                     (.countDown handler-started)
                     @release-handlers
                     "ok")}]})
        session-id (sdk/session-id copilot-session)
        tool-call-id "shared-tool-call"]
    (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
    (try
      (doseq [request-id ["request-a" "request-b"]]
        (mock/send-v3-broadcast-event!
         *mock-server* session-id "external_tool.requested"
         {:requestId request-id
          :toolName "shared-call-tool"
          :toolCallId tool-call-id
          :arguments {}}))
      (is (.await handler-started 5 java.util.concurrent.TimeUnit/SECONDS))
      (is (= #{"request-a" "request-b"}
             (set
              (keys
               (get-in @(:state *test-client*)
                       [:sessions session-id :pending-external-tools])))))
      (deliver release-handlers true)
      (is (.await responses 5 java.util.concurrent.TimeUnit/SECONDS))
      (is (= #{"request-a" "request-b"}
             (set (map :requestId @requests))))
      (finally
        (deliver release-handlers true)))))

(deftest test-handle-tool-call-standalone-invokes-handler
  (let [handler-calls (atom 0)
        invocation-seen (promise)
        copilot-session
        (sdk/create-session
         *test-client*
         {:on-permission-request sdk/approve-all
          :tools [{:tool-name "standalone-tool"
                   :tool-handler
                   (fn [arguments invocation]
                     (swap! handler-calls inc)
                     (deliver invocation-seen invocation)
                     (str "handled " (:value arguments)))}]})
        session-id (sdk/session-id copilot-session)
        response
        (<!!
         (session/handle-tool-call!
          *test-client*
          session-id
          "standalone-call"
          "standalone-tool"
          {:value "direct"}))
        invocation
        (await-value! invocation-seen "standalone tool invocation" 1000)]
    (is (= 1 @handler-calls))
    (is (= "standalone-call" (:tool-call-id invocation)))
    (is (= {:value "direct"} (:arguments invocation)))
    (is (= "handled direct"
           (get-in response [:result :text-result-for-llm])))
    (is (= "success"
           (get-in response [:result :result-type])))))

(deftest test-cancelled-tool-worker-cannot-claim-a-replacement-request
  (let [handler-calls (atom 0)
        copilot-session
        (sdk/create-session
         *test-client*
         {:on-permission-request sdk/approve-all
          :tools [{:tool-name "owned-tool"
                   :tool-handler
                   (fn [_ _]
                     (swap! handler-calls inc)
                     "must not run")}]})
        session-id (sdk/session-id copilot-session)
        pending-a
        (session/register-pending-external-tool!
         *test-client* session-id "request-a" "shared-call")]
    (is (some? pending-a))
    (is (true? (session/cancel-pending-external-tool!
                *test-client* session-id "request-a")))
    (let [pending-b
          (session/register-pending-external-tool!
           *test-client* session-id "request-b" "shared-call")
          result-ch
          (session/handle-registered-tool-call!
           *test-client* session-id "request-a" pending-a
           "shared-call" "owned-tool" {})
          deadline (timeout 1000)
          [_ port] (alts!! [result-ch deadline])]
      (try
        (is (= result-ch port))
        (is (zero? @handler-calls))
        (is (identical?
             pending-b
             (get-in @(:state *test-client*)
                     [:sessions session-id
                      :pending-external-tools "request-b"])))
        (finally
          (session/cancel-pending-external-tool!
           *test-client* session-id "request-b"))))))

(deftest test-tool-search-metadata-preflight-races-cancellation
  (let [metadata-started (promise)
        release-metadata (promise)
        handler-called (promise)
        processing-completed (promise)
        real-handle-tool-call!
        (var-get #'session/handle-registered-tool-call!)
        _ (mock/set-request-hook!
           *mock-server*
           (fn [method _params]
             (when (= "session.tools.getCurrentMetadata" method)
               (deliver metadata-started true)
               @release-metadata)))
        copilot-session
        (sdk/create-session
         *test-client*
         {:on-permission-request sdk/approve-all
          :tools [{:tool-name "tool_search_tool"
                   :tool-handler
                   (fn [_ _]
                     (deliver handler-called true)
                     "must not run")}]})
        session-id (sdk/session-id copilot-session)
        request-id "cancelled-preflight"]
    (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
    (with-redefs-fn
      {#'session/handle-registered-tool-call!
       (fn [& args]
         (observe-channel-resolution!
          (apply real-handle-tool-call! args)
          processing-completed))}
      (fn []
        (try
          (mock/send-v3-broadcast-event!
           *mock-server* session-id "external_tool.requested"
           {:requestId request-id
            :toolName "tool_search_tool"
            :toolCallId "preflight-call"
            :arguments {}})
          (is (true? (await-value! metadata-started
                                   "tool metadata request"
                                   1000)))
          (mock/send-v3-broadcast-event!
           *mock-server* session-id "external_tool.completed"
           {:requestId request-id})
          (await-atom! (:state *test-client*)
                       #(not (get-in % [:sessions session-id
                                        :pending-external-tools request-id]))
                       "cancelled metadata preflight"
                       1000)
          (is (true? (await-value! processing-completed
                                   "cancelled metadata preflight processing"
                                   1000)))
          (is (not-any?
               #(= "session.tools.getCurrentMetadata" (:method %))
               (vals
                (get-in @(:state *test-client*)
                        [:connection :pending-requests]))))
          (is (false? (realized? handler-called)))
          (finally
            (deliver release-metadata true)))))))

(deftest test-disconnect-cancels-tools-only-after-successful-detach
  (testing "successful detach closes the invocation cancellation channel"
    (let [invocation-ready (promise)
          finish-handler (promise)
          processing-completed (promise)
          real-handle-tool-call!
          (var-get #'session/handle-registered-tool-call!)
          copilot-session
          (sdk/create-session
           *test-client*
           {:on-permission-request sdk/approve-all
            :tools [{:tool-name "disconnect-tool"
                     :tool-handler
                     (fn [_ invocation]
                       (deliver invocation-ready invocation)
                       @finish-handler
                       "late")}]})
          session-id (sdk/session-id copilot-session)]
      (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
      (with-redefs-fn
        {#'session/handle-registered-tool-call!
         (fn [& args]
           (observe-channel-resolution!
            (apply real-handle-tool-call! args)
            processing-completed))}
        (fn []
          (try
            (mock/send-v3-broadcast-event!
             *mock-server* session-id "external_tool.requested"
             {:requestId "successful-detach-request"
              :toolName "disconnect-tool"
              :toolCallId "successful-detach-call"
              :arguments {}})
            (let [cancel-ch (:cancel-chan
                             (await-value! invocation-ready
                                           "disconnect tool invocation"
                                           1000))]
              (is (nil? (session/disconnect! copilot-session)))
              (is (async-protocols/closed? cancel-ch))
              (deliver finish-handler true)
              (is (true? (await-value! processing-completed
                                       "disconnect tool cancellation"
                                       1000))))
            (finally
              (deliver finish-handler true)))))))

  (testing "failed detach leaves the invocation live and retryable"
    (let [invocation-ready (promise)
          finish-handler (promise)
          response-latch (java.util.concurrent.CountDownLatch. 1)
          requests (atom [])
          _ (mock/set-request-hook!
             *mock-server*
             (fn [method params]
               (swap! requests conj {:method method :params params})
               (cond
                 (= "session.detach" method)
                 {::mock/merge-response
                  {:success false :error "detach rejected"}}

                 (= "session.tools.handlePendingToolCall" method)
                 (.countDown response-latch))))
          copilot-session
          (sdk/create-session
           *test-client*
           {:on-permission-request sdk/approve-all
            :tools [{:tool-name "retryable-tool"
                     :tool-handler
                     (fn [_ invocation]
                       (deliver invocation-ready invocation)
                       @finish-handler
                       "ok")}]})
          session-id (sdk/session-id copilot-session)]
      (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
      (reset! requests [])
      (try
        (mock/send-v3-broadcast-event!
         *mock-server* session-id "external_tool.requested"
         {:requestId "failed-detach-request"
          :toolName "retryable-tool"
          :toolCallId "failed-detach-call"
          :arguments {}})
        (let [cancel-ch (:cancel-chan
                         (await-value! invocation-ready
                                       "retryable tool invocation"
                                       1000))]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"detach rejected"
               (session/disconnect! copilot-session)))
          (is (false? (async-protocols/closed? cancel-ch)))
          (deliver finish-handler true)
          (is (.await response-latch 5 java.util.concurrent.TimeUnit/SECONDS))
          (is (async-protocols/closed? cancel-ch))
          (is (= 1
                 (count
                  (filter #(= "session.tools.handlePendingToolCall"
                              (:method %))
                          @requests)))))
        (finally
          (deliver finish-handler true))))))

(deftest test-tool-result-normalization
  (testing "tool handler return values are normalized into the handlePendingToolCall result"
    (doseq [[desc tool-handler req-id tc-id assert-result]
            [["string is normalized to success"
              (fn [_args _inv] "hello world") "tool-req-1" "tc-1"
              (fn [result]
                (is (= "hello world" (:textResultForLlm result)))
                (is (= "success" (:resultType result))))]
             ["nil is normalized to failure"
              (fn [_args _inv] nil) "tool-req-2" "tc-2"
              (fn [result]
                (is (= "Tool returned no result" (:textResultForLlm result)))
                (is (= "failure" (:resultType result))))]
             ["structured ToolResultObject is forwarded with telemetry"
              (fn [_args _inv] {:text-result-for-llm "all good"
                                :result-type "success"
                                :tool-telemetry {"metrics" {"latency_ms" 42}}})
              "tool-req-3" "tc-3"
              (fn [result]
                (is (= "all good" (:textResultForLlm result)))
                (is (= "success" (:resultType result)))
                (is (= 42 (get-in result [:toolTelemetry :metrics :latency_ms]))))]
             ["structured ToolResultObject forwards tool references"
              (fn [_args _inv] {:text-result-for-llm "found tools"
                                :result-type "success"
                                :tool-references ["github/search" "github/get"]})
              "tool-req-4" "tc-4"
              (fn [result]
                (is (= ["github/search" "github/get"]
                       (:toolReferences result))))]
             ["non-serializable values become failure results"
              (fn [_args _inv] (Object.)) "tool-req-5" "tc-5"
              (fn [result]
                (is (= "failure" (:resultType result)))
                (is (re-find #"Don't know how to write JSON"
                             (:error result))))]
             ["non-Exception throwables become failure results"
              (fn [_args _inv]
                (throw (AssertionError. "handler assertion failed")))
              "tool-req-6" "tc-6"
              (fn [result]
                (is (= "failure" (:resultType result)))
                (is (= "handler assertion failed" (:error result))))]]]
      (testing desc
        (let [requests (atom [])
              rpc-latch (java.util.concurrent.CountDownLatch. 1)
              _ (mock/set-request-hook! *mock-server*
                                        (fn [method params]
                                          (swap! requests conj {:method method :params params})
                                          (when (= "session.tools.handlePendingToolCall" method)
                                            (.countDown rpc-latch))))
              session (sdk/create-session *test-client*
                                          {:on-permission-request sdk/approve-all
                                           :tools [{:tool-name "test-tool"
                                                    :tool-handler tool-handler}]})
              session-id (sdk/session-id session)]
          (swap! (:state *test-client*) assoc :negotiated-protocol-version 3)
          (reset! requests [])
          (mock/send-v3-broadcast-event! *mock-server* session-id
                                         "external_tool.requested"
                                         {:requestId req-id
                                          :toolName "test-tool"
                                          :toolCallId tc-id
                                          :arguments {}})
          (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS))
          (let [rpcs (filter #(= "session.tools.handlePendingToolCall" (:method %)) @requests)
                result (get-in (first rpcs) [:params :result])]
            (is (= 1 (count rpcs)))
            (is (map? result))
            (assert-result result)))))))
