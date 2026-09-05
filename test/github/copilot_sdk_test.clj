(ns github.copilot-sdk-test
  (:require [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk :as copilot]
            [github.copilot-sdk.helpers :as h]
            [github.copilot-sdk.process :as proc]
            [github.copilot-sdk.protocol :as proto]
            [github.copilot-sdk.specs :as specs]
            [github.copilot-sdk.util :as util]
            [clojure.spec.alpha :as s]
            [clojure.data.json])
  (:import [java.nio ByteBuffer]
           [java.nio.channels Channels]
           [java.nio.charset StandardCharsets]
           [java.io PipedInputStream PipedOutputStream BufferedReader InputStreamReader]))

;; =============================================================================
;; Spec Tests
;; =============================================================================

(deftest client-options-spec-test
  (testing "valid client options"
    (is (s/valid? ::specs/client-options {}))
    (is (s/valid? ::specs/client-options {:cli-path "copilot"}))
    (is (s/valid? ::specs/client-options {:log-level :debug}))
    (is (s/valid? ::specs/client-options {:use-stdio? true :port 8080}))
    (is (s/valid? ::specs/client-options {:is-child-process? true}))
    (is (s/valid? ::specs/client-options {:is-child-process? false})))

  (testing "invalid client options"
    (is (not (s/valid? ::specs/client-options {:log-level :invalid})))
    (is (not (s/valid? ::specs/client-options {:is-child-process? "yes"})))))

(deftest send-options-spec-test
  (testing "valid send options"
    (is (s/valid? ::specs/send-options {:prompt "Hello"}))
    (is (s/valid? ::specs/send-options {:prompt "Hi" :message-mode :enqueue})))

  (testing "invalid send options"
    (is (not (s/valid? ::specs/send-options {})))
    (is (not (s/valid? ::specs/send-options {:prompt ""})))))

(deftest connection-state-spec-test
  (testing "valid states"
    (is (s/valid? ::specs/connection-state :disconnected))
    (is (s/valid? ::specs/connection-state :connecting))
    (is (s/valid? ::specs/connection-state :connected))
    (is (s/valid? ::specs/connection-state :error)))

  (testing "invalid states"
    (is (not (s/valid? ::specs/connection-state :invalid)))))

(deftest session-error-data-spec-test
  (testing "minimal valid session.error data"
    (is (s/valid? ::specs/session.error-data
                  {:error-type "authentication" :message "Auth failed"})))

  (testing "with all optional fields"
    (is (s/valid? ::specs/session.error-data
                  {:error-type "quota"
                   :message "Rate limit exceeded"
                   :stack "at foo.bar (line 42)"
                   :status-code 429
                   :provider-call-id "abc-123-def"
                   :url "https://example.com/billing"})))

  (testing "with subset of optional fields"
    (is (s/valid? ::specs/session.error-data
                  {:error-type "query"
                   :message "Context too large"
                   :status-code 400})))

  (testing "invalid: missing required fields"
    (is (not (s/valid? ::specs/session.error-data {})))
    (is (not (s/valid? ::specs/session.error-data {:error-type "auth"})))
    (is (not (s/valid? ::specs/session.error-data {:message "fail"}))))

  (testing "invalid: wrong types for optional fields"
    (is (not (s/valid? ::specs/session.error-data
                       {:error-type "auth" :message "fail" :status-code "not-a-number"})))
    (is (not (s/valid? ::specs/session.error-data
                       {:error-type "auth" :message "fail" :provider-call-id 123}))))

  (testing "invalid: wrong types for required and pre-existing fields"
    (is (not (s/valid? ::specs/session.error-data
                       {:error-type 42 :message "fail"})))
    (is (not (s/valid? ::specs/session.error-data
                       {:error-type "auth" :message "fail" :stack 99})))))

;; =============================================================================
;; Client Tests
;; =============================================================================

(deftest client-creation-test
  (testing "create client with default options"
    (let [c (copilot/client)]
      (is (some? c))
      (is (= :disconnected (copilot/state c)))))

  (testing "create client with custom options"
    (let [c (copilot/client {:log-level :debug :auto-start? false})]
      (is (some? c))
      (is (= :disconnected (copilot/state c)))))

  (testing "cli-url mutual exclusion with use-stdio?"
    (is (thrown? Exception
                 (copilot/client {:cli-url "localhost:8080" :use-stdio? true}))))

  (testing "cli-url mutual exclusion with cli-path"
    (is (thrown? Exception
                 (copilot/client {:cli-url "localhost:8080" :cli-path "/path/to/cli"}))))

  (testing "is-child-process? mutual exclusion with cli-url"
    (is (thrown-with-msg? Exception #"is-child-process\? is mutually exclusive with cli-url"
                          (copilot/client {:is-child-process? true :cli-url "localhost:8080"}))))

  (testing "is-child-process? requires use-stdio? true"
    (is (thrown-with-msg? Exception #"is-child-process\? requires use-stdio\?"
                          (copilot/client {:is-child-process? true :use-stdio? false}))))

  (testing "is-child-process? marks client as external server"
    (let [c (copilot/client {:is-child-process? true :auto-start? false})]
      (is (true? (:external-server? c)))
      (is (true? (:external-server? (:options c))))
      (is (true? (:use-stdio? (:options c))))))

  (testing "is-child-process? with default use-stdio? is accepted"
    (let [c (copilot/client {:is-child-process? true :auto-start? false})]
      (is (some? c))))

  (testing "is-child-process? with explicit use-stdio? true is accepted"
    (let [c (copilot/client {:is-child-process? true :use-stdio? true :auto-start? false})]
      (is (some? c)))))

(deftest internally-managed-client-has-boolean-ownership-flag
  (let [c (copilot/client {:auto-start? false})]
    (is (false? (:external-server? c)))
    (is (s/valid? ::specs/client c)
        (s/explain-str ::specs/client c))))

;; =============================================================================
;; URL Parsing Tests (matching JS SDK client.test.ts)
;; =============================================================================

(deftest cli-url-parsing-test
  (testing "parse port-only URL format"
    (let [c (copilot/client {:cli-url "8080" :auto-start? false})]
      (is (= 8080 (:port (:options c))))
      (is (= "localhost" (:host (:options c))))
      (is (true? (:external-server? (:options c))))))

  (testing "parse host:port URL format"
    (let [c (copilot/client {:cli-url "127.0.0.1:9000" :auto-start? false})]
      (is (= 9000 (:port (:options c))))
      (is (= "127.0.0.1" (:host (:options c))))
      (is (true? (:external-server? (:options c))))))

  (testing "parse http://host:port URL format"
    (let [c (copilot/client {:cli-url "http://localhost:7000" :auto-start? false})]
      (is (= 7000 (:port (:options c))))
      (is (= "localhost" (:host (:options c))))
      (is (true? (:external-server? (:options c))))))

  (testing "reject https:// because the transport is plaintext TCP"
    (is (thrown-with-msg?
         Exception
         #"https:// cli-url is not supported by the plaintext TCP transport"
         (copilot/client {:cli-url "https://example.com:443"
                          :auto-start? false}))))

  (testing "invalid URL format throws"
    (is (thrown-with-msg? Exception #"Invalid cli-url format"
                          (copilot/client {:cli-url "invalid-url" :auto-start? false}))))

  (testing "invalid port (too high) throws"
    (is (thrown-with-msg? Exception #"Invalid port"
                          (copilot/client {:cli-url "localhost:99999" :auto-start? false}))))

  (testing "invalid port (zero) throws"
    (is (thrown-with-msg? Exception #"Invalid port"
                          (copilot/client {:cli-url "localhost:0" :auto-start? false}))))

  (testing "invalid port (negative) throws"
    (is (thrown-with-msg? Exception #"Invalid port"
                          (copilot/client {:cli-url "localhost:-1" :auto-start? false}))))

  (testing "cli-url sets use-stdio? to false"
    (let [c (copilot/client {:cli-url "8080" :auto-start? false})]
      (is (false? (:use-stdio? (:options c))))))

  (testing "cli-url marks client as external server"
    (let [c (copilot/client {:cli-url "localhost:8080" :auto-start? false})]
      (is (true? (:external-server? (:options c)))))))

;; =============================================================================
;; Tool Definition Tests
;; =============================================================================

(deftest define-tool-test
  (testing "define a simple tool"
    (let [tool (copilot/define-tool "test_tool"
                 {:description "A test tool"
                  :parameters {:type "object"
                               :properties {:input {:type "string"}}}
                  :handler (fn [args _] (str "Got: " (:input args)))})]
      (is (= "test_tool" (:tool-name tool)))
      (is (= "A test tool" (:tool-description tool)))
      (is (fn? (:tool-handler tool)))))

  (testing "tool handler execution"
    (let [handler (fn [args _] (str "Hello " (:name args)))
          tool (copilot/define-tool "greet"
                 {:handler handler})
          result ((:tool-handler tool) {:name "World"} {})]
      (is (= "Hello World" result)))))

(deftest define-tool-with-override-test
  (testing "define a tool with overrides-built-in-tool"
    (let [tool (copilot/define-tool "grep"
                 {:description "Custom grep"
                  :overrides-built-in-tool true
                  :parameters {:type "object"
                               :properties {:query {:type "string"}}}
                  :handler (fn [args _] (str "Custom grep: " (:query args)))})]
      (is (= "grep" (:tool-name tool)))
      (is (= true (:overrides-built-in-tool tool)))
      (is (= "Custom grep" (:tool-description tool)))))

  (testing "define a tool without overrides-built-in-tool omits the key"
    (let [tool (copilot/define-tool "my_tool"
                 {:description "A tool"
                  :handler (fn [_ _] "ok")})]
      (is (not (contains? tool :overrides-built-in-tool))))))

(deftest set-model-alias-test
  (testing "set-model! delegates to switch-model!"
    (let [called-args (atom nil)
          sentinel ::switch-model-called]
      (with-redefs [github.copilot-sdk.session/switch-model!
                    (fn [& args]
                      (reset! called-args args)
                      sentinel)]
        (let [result (copilot/set-model! :fake-session "gpt-4.1")]
          (is (some? @called-args) "switch-model! should have been called")
          (is (= 3 (count @called-args)) "switch-model! receives 3 args (session, model-id, nil opts)")
          (is (= :fake-session (first @called-args)))
          (is (= "gpt-4.1" (second @called-args)))
          (is (nil? (nth @called-args 2)) "opts should be nil when not provided")
          (is (= sentinel result)))))))

;; =============================================================================
;; Result Helper Tests
;; =============================================================================

(deftest result-helpers-test
  (testing "result-success"
    (let [r (copilot/result-success "OK")]
      (is (= "OK" (:text-result-for-llm r)))
      (is (= "success" (:result-type r)))))

  (testing "result-failure"
    (let [r (copilot/result-failure "Failed" "error details")]
      (is (= "Failed" (:text-result-for-llm r)))
      (is (= "failure" (:result-type r)))
      (is (= "error details" (:error r)))))

  (testing "result-denied"
    (let [r (copilot/result-denied "Permission denied")]
      (is (= "denied" (:result-type r)))))

  (testing "result-rejected"
    (let [r (copilot/result-rejected "User rejected")]
      (is (= "rejected" (:result-type r))))))

(deftest ensure-client-honors-requested-opts-test
  ;; Regression: the shared-client initializer must create the first client with
  ;; the caller-supplied :client options (e.g. a custom :cli-path or :env), per
  ;; the documented contract ("First query initializes the client with provided
  ;; :client options"). Stub start! so no CLI is spawned; the client constructor
  ;; itself does not spawn, so the option-threading logic is exercised in full.
  (let [state-atom @#'h/client-state
        saved @state-atom]
    (try
      (reset! state-atom nil)
      (with-redefs [copilot/start! (fn [c] c)]
        (let [c (#'h/ensure-client! {:cli-path "custom-copilot-xyz"})]
          (is (= "custom-copilot-xyz" (:cli-path (copilot/client-options c)))
              "first-use client must honor the requested :cli-path")))
      (finally
        (reset! state-atom saved)))))

(deftest start-guard-prevents-double-spawn-test
  ;; The start! status guard must be atomic: if a start is already in progress
  ;; (:connecting) or complete (:connected), a second start! must NOT spawn a
  ;; second CLI process. A non-atomic check-then-act lets two concurrent callers
  ;; both pass the guard and double-spawn. We simulate the in-progress state
  ;; deterministically and assert no spawn is attempted.
  (testing "already :connecting -> start! is a no-op (no second spawn)"
    (let [c (copilot/client {:auto-start? false :use-stdio? true})]
      (swap! (:state c) assoc :status :connecting)
      (with-redefs [proc/spawn-cli (fn [_] (throw (ex-info "start! must not spawn when already :connecting" {})))]
        (is (nil? (copilot/start! c))))))
  (testing "already :connected -> start! is a no-op"
    (let [c (copilot/client {:auto-start? false :use-stdio? true})]
      (swap! (:state c) assoc :status :connected)
      (with-redefs [proc/spawn-cli (fn [_] (throw (ex-info "start! must not spawn when already :connected" {})))]
        (is (nil? (copilot/start! c)))))))

;; =============================================================================
;; Protocol Tests (Unit)
;; =============================================================================

(deftest json-rpc-message-framing-test
  (testing "message framing creates valid Content-Length format"
    ;; Test using a pipe to verify NIO channel write produces correct format
    (let [test-msg {:jsonrpc "2.0" :id 1 :method "test" :params {}}
          pipe (java.io.PipedOutputStream.)
          in (java.io.PipedInputStream. pipe)
          out pipe
          write-ch (java.nio.channels.Channels/newChannel out)]
      ;; Write message using the internal write function pattern
      (let [json-str (clojure.data.json/write-str test-msg)
            content-bytes (.getBytes json-str java.nio.charset.StandardCharsets/UTF_8)
            header (str "Content-Length: " (alength content-bytes) "\r\n\r\n")
            header-bytes (.getBytes header java.nio.charset.StandardCharsets/UTF_8)
            buf (java.nio.ByteBuffer/allocate (+ (alength header-bytes) (alength content-bytes)))]
        (.put buf header-bytes)
        (.put buf content-bytes)
        (.flip buf)
        (while (.hasRemaining buf)
          (.write write-ch buf)))
      (.flush out)
      ;; Read back and verify
      (let [reader (java.io.BufferedReader. (java.io.InputStreamReader. in "UTF-8"))
            first-line (.readLine reader)]
        (is (clojure.string/starts-with? first-line "Content-Length:"))))))

;; =============================================================================
;; Config Validation Tests - Unknown Keys
;; =============================================================================

(deftest client-options-unknown-keys-test
  (testing "unknown client option key is rejected"
    (is (thrown-with-msg? Exception #"unknown keys.*:log-levl"
                          (copilot/client {:log-levl :debug}))))

  (testing "typo in client option key is rejected with helpful message"
    (try
      (copilot/client {:auto-starts? true})
      (is false "Should have thrown")
      (catch Exception e
        (is (re-find #"unknown keys" (ex-message e)))
        (is (re-find #":auto-starts\?" (ex-message e)))
        (is (re-find #":auto-start\?" (ex-message e))))))  ; valid key shown

  (testing "multiple unknown keys are reported"
    (try
      (copilot/client {:foo 1 :bar 2})
      (is false "Should have thrown")
      (catch Exception e
        (is (re-find #":foo" (ex-message e)))
        (is (re-find #":bar" (ex-message e))))))

  (testing "valid client options are accepted"
    (is (some? (copilot/client {:log-level :debug :auto-start? false})))))

(deftest session-config-unknown-keys-test
  (testing "unknown session config key is rejected"
    (is (not (s/valid? ::specs/session-config {:on-permission-request identity
                                               :reasoning-efforts "high"}))))

  (testing "typo in session config provides helpful error"
    (let [unknown (specs/unknown-keys {:model "gpt-5.4" :streeming? true}
                                      specs/session-config-keys)]
      (is (contains? unknown :streeming?))))

  (testing "valid session config keys are accepted"
    (is (s/valid? ::specs/session-config {:on-permission-request identity
                                          :model "gpt-5.4"
                                          :streaming? true
                                          :reasoning-effort "high"})))

  (testing "session config rejects unknown keys even with valid ones"
    (is (not (s/valid? ::specs/session-config {:on-permission-request identity
                                               :model "gpt-5.4"
                                               :unknown-key "value"})))))

(deftest evt-helper-test
  (testing "evt converts unqualified to qualified keywords"
    (is (= :copilot/session.idle (copilot/evt :session.idle)))
    (is (= :copilot/assistant.message (copilot/evt :assistant.message)))
    (is (= :copilot/tool.execution_complete (copilot/evt :tool.execution_complete))))

  (testing "evt throws on invalid event type"
    (is (thrown-with-msg? IllegalArgumentException #"Unknown event type"
                          (copilot/evt :invalid.event))))

  (testing "evt error message includes valid events"
    (try
      (copilot/evt :foo)
      (is false "Should have thrown")
      (catch IllegalArgumentException e
        (is (re-find #"session.idle" (ex-message e)))))))

;; =============================================================================
;; MCP Wire Format Tests
;; =============================================================================

(deftest mcp-server-wire-format-test
  (testing "local MCP server: :mcp-* prefix stripped on wire"
    (let [wire (util/mcp-server->wire {:mcp-command "node"
                                       :mcp-args ["server.js"]
                                       :mcp-tools ["*"]
                                       :mcp-timeout 30000
                                       :env {"DEBUG" "true"}
                                       :cwd "/tmp"})]
      (is (= "node" (:command wire)))
      (is (= ["server.js"] (:args wire)))
      (is (= ["*"] (:tools wire)))
      (is (= 30000 (:timeout wire)))
      (is (= "true" (get-in wire [:env "DEBUG"])))
      (is (= "/tmp" (:cwd wire)))
      ;; Ensure no mcp-prefixed keys remain
      (is (nil? (:mcpCommand wire)))
      (is (nil? (:mcpArgs wire)))
      (is (nil? (:mcpTools wire)))))

  (testing "remote MCP server: :mcp-* prefix stripped on wire"
    (let [wire (util/mcp-server->wire {:mcp-server-type :http
                                       :mcp-url "https://example.com/mcp"
                                       :mcp-tools ["*"]
                                       :mcp-headers {"Authorization" "Bearer tok"}})]
      (is (= "http" (:type wire)))
      (is (= "https://example.com/mcp" (:url wire)))
      (is (= ["*"] (:tools wire)))
      (is (= "Bearer tok" (get-in wire [:headers "Authorization"])))
      (is (nil? (:mcpServerType wire)))
      (is (nil? (:mcpUrl wire)))))

  (testing "mcp-servers->wire converts full servers map"
    (let [wire (util/mcp-servers->wire
                {:team/fs-server {:mcp-command "npx"
                                  :mcp-args ["-y" "@mcp/server-fs" "/tmp"]
                                  :mcp-tools ["*"]}
                 "api" {:mcp-server-type :http
                        :mcp-url "https://api.test"
                        :mcp-tools ["read" "write"]}})]
      (is (= #{"team/fs-server" "api"} (set (keys wire))))
      (is (= "npx" (get-in wire ["team/fs-server" :command])))
      (is (= "https://api.test" (get-in wire ["api" :url])))
      (is (= ["read" "write"] (get-in wire ["api" :tools]))))))

(deftest blob-attachment-wire-format-test
  (testing "blob attachment with display-name"
    (let [wire (util/attachment->wire {:type :blob
                                       :data "iVBORw0KGgoAAAANSUhEUg=="
                                       :mime-type "image/png"
                                       :display-name "test-pixel.png"})]
      (is (= "blob" (:type wire)))
      (is (= "iVBORw0KGgoAAAANSUhEUg==" (:data wire)))
      (is (= "image/png" (:mimeType wire)))
      (is (= "test-pixel.png" (:displayName wire)))))

  (testing "blob attachment without display-name"
    (let [wire (util/attachment->wire {:type :blob
                                       :data "AAAA"
                                       :mime-type "application/octet-stream"})]
      (is (= "blob" (:type wire)))
      (is (= "AAAA" (:data wire)))
      (is (= "application/octet-stream" (:mimeType wire)))
      (is (not (contains? wire :displayName)))))

  (testing "blob attachment spec valid in send-options"
    (is (s/valid? ::specs/send-options
                  {:prompt "Describe this image"
                   :attachments [{:type :blob
                                  :data "iVBORw0KGgoAAAANSUhEUg=="
                                  :mime-type "image/png"}]})))
  (testing "blob attachment spec valid with display-name"
    (is (s/valid? ::specs/send-options
                  {:prompt "What's in this image?"
                   :attachments [{:type :blob
                                  :data "iVBORw0KGgoAAAANSUhEUg=="
                                  :mime-type "image/png"
                                  :display-name "photo.png"}]})))
  (testing "blob attachment mixed with file attachment"
    (is (s/valid? ::specs/send-options
                  {:prompt "Compare these"
                   :attachments [{:type :file :path "/tmp/code.clj"}
                                 {:type :blob
                                  :data "AAAA"
                                  :mime-type "image/png"}]}))))
