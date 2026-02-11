(ns github.copilot-sdk-test
  (:require [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk :as copilot]
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
    (is (s/valid? ::specs/client-options {:use-stdio? true :port 8080})))

  (testing "invalid client options"
    (is (not (s/valid? ::specs/client-options {:log-level :invalid})))))

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
                 (copilot/client {:cli-url "localhost:8080" :cli-path "/path/to/cli"})))))

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

  (testing "parse https://host:port URL format"
    (let [c (copilot/client {:cli-url "https://example.com:443" :auto-start? false})]
      (is (= 443 (:port (:options c))))
      (is (= "example.com" (:host (:options c))))
      (is (true? (:external-server? (:options c))))))

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
    (is (not (s/valid? ::specs/session-config {:reasoning-efforts "high"}))))

  (testing "typo in session config provides helpful error"
    (let [unknown (specs/unknown-keys {:model "gpt-5.2" :streeming? true}
                                       specs/session-config-keys)]
      (is (contains? unknown :streeming?))))

  (testing "valid session config keys are accepted"
    (is (s/valid? ::specs/session-config {:model "gpt-5.2"
                                          :streaming? true
                                          :reasoning-effort "high"})))

  (testing "session config rejects unknown keys even with valid ones"
    (is (not (s/valid? ::specs/session-config {:model "gpt-5.2"
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
                {"fs" {:mcp-command "npx"
                       :mcp-args ["-y" "@mcp/server-fs" "/tmp"]
                       :mcp-tools ["*"]}
                 "api" {:mcp-server-type :http
                        :mcp-url "https://api.test"
                        :mcp-tools ["read" "write"]}})]
      (is (= "npx" (get-in wire ["fs" :command])))
      (is (= "https://api.test" (get-in wire ["api" :url])))
      (is (= ["read" "write"] (get-in wire ["api" :tools]))))))
