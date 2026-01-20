(ns krukow.copilot-sdk-test
  (:require [clojure.test :refer [deftest is testing]]
            [krukow.copilot-sdk :as copilot]
            [krukow.copilot-sdk.protocol :as proto]
            [krukow.copilot-sdk.specs :as specs]
            [clojure.spec.alpha :as s]
            [cheshire.core])
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
      (is (= "OK" (:textResultForLlm r)))
      (is (= "success" (:resultType r)))))
  
  (testing "result-failure"
    (let [r (copilot/result-failure "Failed" "error details")]
      (is (= "Failed" (:textResultForLlm r)))
      (is (= "failure" (:resultType r)))
      (is (= "error details" (:error r)))))
  
  (testing "result-denied"
    (let [r (copilot/result-denied "Permission denied")]
      (is (= "denied" (:resultType r)))))
  
  (testing "result-rejected"
    (let [r (copilot/result-rejected "User rejected")]
      (is (= "rejected" (:resultType r))))))

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
      (let [json-str (cheshire.core/generate-string test-msg)
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
