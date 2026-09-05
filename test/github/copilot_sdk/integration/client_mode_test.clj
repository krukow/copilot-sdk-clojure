(ns github.copilot-sdk.integration.client-mode-test
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

(deftest test-empty-mode-requires-tenant-scoped-storage
  (testing "construction without storage hook throws"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Mode :empty requires"
         (sdk/client {:mode :empty :auto-start? false}))))
  (testing ":copilot-home satisfies the requirement"
    (let [c (sdk/client {:mode :empty
                         :copilot-home "/tmp/empty-mode-test"
                         :auto-start? false})]
      (is (= :empty (get-in c [:options :mode])))
      (is (= "/tmp/empty-mode-test" (get-in c [:options :copilot-home])))))
  (testing ":session-fs satisfies the requirement"
    (let [c (sdk/client {:mode :empty
                         :session-fs {:initial-cwd "/workspace"
                                      :session-state-path "/state"
                                      :conventions "posix"}
                         :auto-start? false})]
      (is (= :empty (get-in c [:options :mode])))))
  (testing ":cli-url satisfies the requirement"
    (let [c (sdk/client {:mode :empty
                         :cli-url "localhost:1234"
                         :auto-start? false})]
      (is (= :empty (get-in c [:options :mode])))))
  (testing ":is-child-process? satisfies the requirement"
    (let [c (sdk/client {:mode :empty
                         :is-child-process? true
                         :auto-start? false})]
      (is (= :empty (get-in c [:options :mode]))))))

(deftest test-empty-mode-default-and-cli-mode
  (testing "default mode is :copilot-cli (no extra options needed)"
    (let [c (sdk/client {:auto-start? false})]
      ;; :mode is not auto-injected; absence is treated as :copilot-cli by
      ;; downstream code. The point is that no extra options are required.
      (is (nil? (get-in c [:options :mode]))
          ":mode should be unset by default — downstream code treats nil as :copilot-cli")))
  (testing "explicit :copilot-cli mode imposes no extra requirements"
    (let [c (sdk/client {:mode :copilot-cli :auto-start? false})]
      (is (= :copilot-cli (get-in c [:options :mode]))))))

(deftest test-validation-errors-redact-secrets
  (testing "secrets never appear in validation exception data or messages (SEC-1)"
    (let [token "ghp_SUPERSECRETtoken123"
          tcp-token "tcptokenSECRET000"
          azure-key "sk-azureSECRET456"
          mcp-secret "Bearer mcpSECRET789"
          dump (fn [^Throwable e] (pr-str (Throwable->map e)))
          leaked? (fn [^Throwable e ^String s]
                    (or (.contains (str (ex-message e)) s)
                        (.contains ^String (dump e) s)))
          capture (fn [f] (try (f) nil (catch clojure.lang.ExceptionInfo e e)))]
      (testing "client-options spec-explain path"
        (let [e (capture #(sdk/client {:github-token token :log-level :bogus :auto-start? false}))]
          (is (some? e) "expected a validation failure")
          (is (not (leaked? e token)) "github-token must be redacted")))
      (testing "client-options unknown-keys path"
        (let [e (capture #(sdk/client {:github-token token :totally-unknown-key 1 :auto-start? false}))]
          (is (some? e))
          (is (not (leaked? e token)) "github-token must be redacted")))
      (testing "client-options mutual-exclusion raw-opts path"
        (let [e (capture #(sdk/client {:cli-url "localhost:1234" :use-stdio? true
                                       :tcp-connection-token tcp-token :auto-start? false}))]
          (is (some? e))
          (is (not (leaked? e tcp-token)) "tcp-connection-token must be redacted")))
      (testing "session-config BYOK provider api-key"
        (let [c (sdk/client {:auto-start? false})
              e (capture #(sdk/create-session c {:provider {:provider-type "azure" :api-key azure-key}}))]
          (is (some? e))
          (is (not (leaked? e azure-key)) "BYOK :api-key must be redacted")))
      (testing "session-config BYOK :providers registry secrets (v1.0.4)"
        ;; Item 4 added the multi-provider :providers registry; each named
        ;; provider carries the same secret-bearing fields as the singular
        ;; :provider. A validation failure (tripped here by an unknown key)
        ;; must redact them all.
        (let [c (sdk/client {:auto-start? false})
              named-key "sk-namedSECRET789"
              named-bearer "namedBearerSECRET321"
              named-hdr "Bearer namedHdrSECRET654"
              e (capture #(sdk/create-session
                           c {:providers [{:name "openai" :base-url "https://o.test"
                                           :api-key named-key
                                           :bearer-token named-bearer
                                           :headers {"Authorization" named-hdr}}]
                              :totally-unknown-key 1}))]
          (is (some? e) "an unknown key should fail validation")
          (is (not (leaked? e named-key)) "named provider :api-key must be redacted")
          (is (not (leaked? e named-bearer)) "named provider :bearer-token must be redacted")
          (is (not (leaked? e named-hdr)) "named provider :headers value must be redacted")))
      (testing "session-config BYOK :providers as a non-sequential collection (set)"
        ;; redact-secrets runs on the *already-invalid* config in the error path,
        ;; so it must not rely on ::providers being sequential. A set of providers
        ;; (still a valid ::coll-of) must have its secrets masked too.
        (let [c (sdk/client {:auto-start? false})
              set-key "sk-setSECRET111"]
          (let [e (capture #(sdk/create-session
                             c {:providers #{{:name "openai" :base-url "https://o.test"
                                              :api-key set-key}}
                                :totally-unknown-key 1}))]
            (is (some? e) "an unknown key should fail validation")
            (is (not (leaked? e set-key))
                "set-valued :providers entry :api-key must still be redacted"))))
      (testing "session-config BYOK :providers as an (invalid) map of name->config"
        ;; The valid ::providers shape is a sequential collection, but
        ;; redact-secrets runs on the *already-invalid* config in the error
        ;; path. A caller mistake of passing a map (name->provider-config) must
        ;; still have its secret-bearing values masked, not leaked verbatim.
        (let [c (sdk/client {:auto-start? false})
              map-key "sk-mapSECRET222"
              map-bearer "mapBearerSECRET333"
              map-hdr "Bearer mapHdrSECRET444"]
          (let [e (capture #(sdk/create-session
                             c {:providers {:openai {:base-url "https://o.test"
                                                     :api-key map-key
                                                     :bearer-token map-bearer
                                                     :headers {"Authorization" map-hdr}}}}))]
            (is (some? e) "a map-valued :providers should fail validation")
            (is (not (leaked? e map-key))
                "map-valued :providers entry :api-key must still be redacted")
            (is (not (leaked? e map-bearer))
                "map-valued :providers entry :bearer-token must still be redacted")
            (is (not (leaked? e map-hdr))
                "map-valued :providers entry :headers value must still be redacted"))))
      (testing "resume-config BYOK provider api-key"
        (let [c (sdk/client {:auto-start? false})
              e (capture #(sdk/resume-session c "sid" {:provider {:provider-type "azure" :api-key azure-key}}))]
          (is (some? e))
          (is (not (leaked? e azure-key)) "BYOK :api-key must be redacted")))
      (testing "mcp-servers header secret"
        (let [c (sdk/client {:auto-start? false})
              e (capture #(sdk/create-session c {:mcp-servers {"s" {:mcp-headers {"Authorization" mcp-secret}}}}))]
          (is (some? e))
          (is (not (leaked? e mcp-secret)) "MCP header secret must be redacted")))
      (testing "client-options :env secret value"
        ;; :env is merged into the spawned CLI environment, so it can carry
        ;; credentials; a validation failure must not leak them via ex-data.
        (let [env-secret "ghp_ENVSECRET999"
              e (capture #(sdk/client {:env {"GH_TOKEN" env-secret} :log-level :bogus :auto-start? false}))]
          (is (some? e))
          (is (not (leaked? e env-secret)) ":env secret value must be redacted")))
      (testing "blank/invalid secret value still produces a useful spec error"
        ;; A blank :github-token fails the ::non-blank-string spec. Redaction must
        ;; NOT mask it to "***" (which would make the map look valid and suppress
        ;; the explanation); blank values carry no secret to leak.
        (let [e (capture #(sdk/client {:github-token "" :auto-start? false}))]
          (is (some? e) "blank github-token should fail validation")
          (is (.contains (str (ex-message e)) "github-token")
              "the error should still point at :github-token"))))))

(deftest test-failed-start-releases-resources
  (testing "a failed start! tears down the spawned process and connection (A5)"
    ;; `true` exits 0 immediately, so in TCP mode wait-for-port observes the
    ;; process die before announcing a port and start! throws after spawning.
    (let [c (sdk/client {:cli-path "true" :use-stdio? false :auto-start? false})]
      (is (thrown? Exception (sdk/start! c)))
      (let [st @(:state c)]
        (is (= :error (:status st)) "status should be :error after a failed start")
        (is (nil? (:process st)) "spawned process must be released")
        (is (nil? (:connection-io st)) "connection must be released")
        (is (nil? (:socket st)) "socket must be released")))))

(deftest test-empty-mode-spec-validation
  (testing "an unknown :mode value is rejected by the spec"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid client options"
         (sdk/client {:mode :bogus :copilot-home "/tmp/x" :auto-start? false})))))

(deftest test-bare-star-rejected-in-available-tools
  (testing ":available-tools containing bare * is rejected at create-session"
    (let [c (sdk/client {:auto-start? false})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid availableTools entry '\*'"
           (sdk/create-session c {:available-tools ["*"]}))))))

(deftest test-bare-star-rejected-in-excluded-tools
  (testing ":excluded-tools containing bare * is rejected at create-session"
    (let [c (sdk/client {:auto-start? false})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid excludedTools entry '\*'"
           (sdk/create-session c {:excluded-tools ["*"]}))))))

(deftest test-bare-star-rejected-in-resume-session
  (testing ":available-tools containing bare * is rejected at resume-session"
    (let [c (sdk/client {:auto-start? false})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid availableTools entry '\*'"
           (sdk/resume-session c "session-id" {:available-tools ["*"]}))))))

(deftest test-source-qualified-tools-accepted
  (testing "source-qualified patterns pass the bare-* validation"
    ;; We can't fully exercise create-session without a started client, but
    ;; the validation step throws BEFORE ensure-connected!, so a non-throw
    ;; below means we made it past validate-tool-filters! (further work
    ;; will hit the connection check).
    (let [c (sdk/client {:auto-start? false})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Client is not started|not connected|Connection"
           (sdk/create-session c {:available-tools ["builtin:*" "mcp:my_server"]}))))))

(deftest test-empty-mode-requires-available-tools-on-create
  (testing "empty mode rejects create-session without :available-tools"
    (let [c (sdk/client {:mode :empty
                         :copilot-home "/tmp/empty-mode-test"
                         :auto-start? false})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Mode :empty requires every session to specify :available-tools"
           (sdk/create-session c {}))))))

(deftest test-empty-mode-allows-empty-available-tools
  (testing "empty mode accepts :available-tools [] as explicit opt-in to no tools"
    ;; The validation passes; downstream will fail because client is not
    ;; started. Distinguish those errors by message.
    (let [c (sdk/client {:mode :empty
                         :copilot-home "/tmp/empty-mode-test"
                         :auto-start? false})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Client is not started|not connected|Connection"
           (sdk/create-session c {:available-tools []}))))))

(deftest test-empty-mode-requires-available-tools-on-resume
  (testing "empty mode rejects resume-session without :available-tools"
    (let [c (sdk/client {:mode :empty
                         :copilot-home "/tmp/empty-mode-test"
                         :auto-start? false})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Mode :empty requires every session to specify :available-tools"
           (sdk/resume-session c "session-id" {}))))))

(deftest test-cli-mode-does-not-require-available-tools
  (testing "cli mode allows create-session without :available-tools"
    (let [c (sdk/client {:auto-start? false})]
      ;; Validation passes; downstream throws because not started.
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Client is not started|not connected|Connection"
           (sdk/create-session c {}))))))

(deftest test-tool-filter-precedence-always-excluded-on-create
  (testing "session.create always sends toolFilterPrecedence=\"excluded\" (PR #1428)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :model "gpt-5.4"})
          create-params (get @seen "session.create")]
      (is (= "excluded" (:toolFilterPrecedence create-params))
          "tool-filter-precedence must always be \"excluded\" in CLI mode"))))

(deftest test-tool-filter-precedence-always-excluded-on-resume
  (testing "session.resume always sends toolFilterPrecedence=\"excluded\" (PR #1428)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.resume" method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all})
          session-id (sdk/get-last-session-id *test-client*)
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all})
          resume-params (get @seen "session.resume")]
      (is (= "excluded" (:toolFilterPrecedence resume-params))
          "tool-filter-precedence must always be \"excluded\" on resume"))))

(deftest test-empty-mode-spreads-config-defaults-under-caller
  (testing "empty mode spreads the 10 safe defaults under caller config (PR #1428)"
    (let [server (mock/create-mock-server)
          _ (mock/start-mock-server! server)
          seen (atom {})
          _ (mock/set-request-hook! server
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          client (sdk/client {:mode :empty
                              :copilot-home "/tmp/empty-mode-wire-test"
                              :auto-start? false})
          [in out] (mock/client-streams server)]
      (try
        (client/connect-with-streams! client in out)
        (sdk/create-session client
                            {:on-permission-request sdk/approve-all
                             :available-tools ["builtin:think"]})
        (let [p (get @seen "session.create")]
          (is (= false (:enableSessionTelemetry p)))
          (is (= "in-memory" (:mcpOAuthTokenStorage p)))
          (is (= true (:skipEmbeddingRetrieval p)))
          (is (= "in-memory" (:embeddingCacheStorage p)))
          (is (= false (:enableOnDemandInstructionDiscovery p)))
          (is (= false (:enableFileHooks p)))
          (is (= false (:enableHostGitOperations p)))
          (is (= false (:enableSessionStore p)))
          (is (= false (:enableSkills p)))
          (is (= {:enabled false} (:memory p))
              "empty mode disables persistent memory by default")
          (is (= "excluded" (:toolFilterPrecedence p))))
        (finally
          (try (sdk/stop! client) (catch Exception _))
          (mock/stop-mock-server! server))))))

(deftest test-empty-mode-memory-default-on-create-and-resume
  (testing "empty mode sends memory {:enabled false} by default on create and resume,
            and the caller can override it (upstream configDefaultsForMode)"
    (let [server (mock/create-mock-server)
          _ (mock/start-mock-server! server)
          seen (atom {})
          _ (mock/set-request-hook! server
                                    (fn [method params]
                                      (when (#{"session.create" "session.resume"} method)
                                        (swap! seen assoc method params))))
          client (sdk/client {:mode :empty
                              :copilot-home "/tmp/empty-mode-memory-test"
                              :auto-start? false})
          [in out] (mock/client-streams server)]
      (try
        (client/connect-with-streams! client in out)
        ;; Default: no caller :memory -> empty-mode default flows to the wire.
        (sdk/create-session client
                            {:on-permission-request sdk/approve-all
                             :available-tools ["builtin:think"]})
        (is (= {:enabled false} (:memory (get @seen "session.create")))
            "create defaults memory to {:enabled false} in empty mode")
        (let [session-id (sdk/get-last-session-id client)]
          (sdk/resume-session client session-id
                              {:on-permission-request sdk/approve-all
                               :available-tools ["builtin:think"]})
          (is (= {:enabled false} (:memory (get @seen "session.resume")))
              "resume defaults memory to {:enabled false} in empty mode"))
        ;; Caller override wins over the mode default.
        (reset! seen {})
        (sdk/create-session client
                            {:on-permission-request sdk/approve-all
                             :available-tools ["builtin:think"]
                             :memory {:enabled true}})
        (is (= {:enabled true} (:memory (get @seen "session.create")))
            "caller-provided :memory overrides the empty-mode default")
        (finally
          (try (sdk/stop! client) (catch Exception _))
          (mock/stop-mock-server! server))))))

(deftest test-empty-mode-caller-config-wins-over-mode-defaults
  (testing "caller-provided config values always win over mode defaults (PR #1428)"
    (let [server (mock/create-mock-server)
          _ (mock/start-mock-server! server)
          seen (atom {})
          _ (mock/set-request-hook! server
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          client (sdk/client {:mode :empty
                              :copilot-home "/tmp/empty-mode-override-test"
                              :auto-start? false})
          [in out] (mock/client-streams server)]
      (try
        (client/connect-with-streams! client in out)
        ;; Caller overrides 2 of the 9 defaults — those values must win.
        (sdk/create-session client
                            {:on-permission-request sdk/approve-all
                             :available-tools ["builtin:think"]
                             :enable-session-telemetry? true
                             :enable-skills true})
        (let [p (get @seen "session.create")]
          (is (= true (:enableSessionTelemetry p))
              "caller-provided :enable-session-telemetry? must override the mode default")
          (is (= true (:enableSkills p))
              "caller-provided :enable-skills must override the mode default")
          ;; Other defaults still apply
          (is (= true (:skipEmbeddingRetrieval p))))
        (finally
          (try (sdk/stop! client) (catch Exception _))
          (mock/stop-mock-server! server))))))

(deftest test-cli-mode-does-not-spread-mode-defaults
  (testing "CLI mode does NOT spread the empty-mode defaults (PR #1428)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all})
          p (get @seen "session.create")]
      ;; None of the 9 mode-default flags should appear unless caller set them.
      (is (not (contains? p :enableSessionTelemetry)))
      (is (not (contains? p :skipEmbeddingRetrieval)))
      (is (not (contains? p :enableFileHooks)))
      (is (not (contains? p :enableSkills))))))

(defn- empty-mode-create-with-system-message
  "Spin up a fresh empty-mode client against a fresh mock server, invoke
   create-session with the supplied :system-message, and return the
   captured wire payload's :systemMessage field."
  [system-message]
  (let [server (mock/create-mock-server)
        _ (mock/start-mock-server! server)
        seen (atom {})
        _ (mock/set-request-hook! server
                                  (fn [method params]
                                    (when (= "session.create" method)
                                      (swap! seen assoc method params))))
        client (sdk/client {:mode :empty
                            :copilot-home "/tmp/empty-mode-sm-test"
                            :auto-start? false})
        [in out] (mock/client-streams server)]
    (try
      (client/connect-with-streams! client in out)
      (sdk/create-session client
                          (cond-> {:on-permission-request sdk/approve-all
                                   :available-tools []}
                            system-message (assoc :system-message system-message)))
      (-> (get @seen "session.create") :systemMessage)
      (finally
        (try (sdk/stop! client) (catch Exception _))
        (mock/stop-mock-server! server)))))

(deftest test-empty-mode-system-message-default
  (testing "no caller :system-message → emit customize with env-context removed"
    (let [sm (empty-mode-create-with-system-message nil)]
      (is (= "customize" (:mode sm)))
      (is (= {:action "remove"} (get-in sm [:sections :environment_context]))))))

(deftest test-empty-mode-system-message-replace-passes-through
  (testing ":replace mode is preserved unchanged in empty mode"
    (let [sm (empty-mode-create-with-system-message
              {:mode :replace :content "Replacement text."})]
      (is (= "replace" (:mode sm)))
      (is (= "Replacement text." (:content sm)))
      (is (not (contains? sm :sections))))))

(deftest test-empty-mode-system-message-append-promoted-to-customize
  (testing ":append mode is promoted to :customize, content preserved, env-context removed"
    (let [sm (empty-mode-create-with-system-message
              {:mode :append :content "Extra instructions."})]
      (is (= "customize" (:mode sm)))
      (is (= "Extra instructions." (:content sm))
          "caller :content must be preserved verbatim")
      (is (= {:action "remove"} (get-in sm [:sections :environment_context]))))))

(deftest test-empty-mode-system-message-customize-adds-env-context-remove
  (testing ":customize without env-context override → SDK adds env-context remove"
    (let [sm (empty-mode-create-with-system-message
              {:mode :customize
               :sections {:tone {:action :replace :content "Be terse."}}})]
      (is (= "customize" (:mode sm)))
      (is (= {:action "remove"} (get-in sm [:sections :environment_context]))
          "env-context section must be removed")
      (is (some? (get-in sm [:sections :tone]))
          "caller's :tone section must be preserved"))))

(deftest test-empty-mode-system-message-customize-no-sections-key
  (testing ":customize with NO :sections key at all → SDK adds :sections with env-context remove"
    (let [sm (empty-mode-create-with-system-message {:mode :customize})]
      (is (= "customize" (:mode sm)))
      (is (= {:action "remove"} (get-in sm [:sections :environment_context]))
          "env-context section must be added even when caller omits :sections entirely"))))

(deftest test-empty-mode-system-message-customize-respects-app-env-context
  (testing ":customize with explicit env-context override → SDK does NOT touch it"
    (let [sm (empty-mode-create-with-system-message
              {:mode :customize
               :sections {:environment-context {:action :replace :content "Custom env."}}})]
      (is (= "customize" (:mode sm)))
      (is (= {:action "replace" :content "Custom env."}
             (get-in sm [:sections :environment_context]))
          "app's env-context override must win unchanged"))))

(deftest test-cli-mode-system-message-untouched
  (testing "CLI mode does NOT normalize :system-message (preserve historical behavior)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.create" method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :system-message {:mode :append
                                                  :content "Just append this."}})
          sm (-> (get @seen "session.create") :systemMessage)]
      (is (= "append" (:mode sm)) "CLI mode keeps :append, no promotion")
      (is (= "Just append this." (:content sm))))))

(defn- empty-mode-capture-options-update
  "Spin up a fresh empty-mode client, create a session with the given extra
   config map, and return the captured session.options.update wire params
   (or nil if the RPC was not issued)."
  [extra-config]
  (let [server (mock/create-mock-server)
        _ (mock/start-mock-server! server)
        seen (atom nil)
        _ (mock/set-request-hook! server
                                  (fn [method params]
                                    (when (= "session.options.update" method)
                                      (reset! seen params))))
        client (sdk/client {:mode :empty
                            :copilot-home "/tmp/empty-mode-opts-test"
                            :auto-start? false})
        [in out] (mock/client-streams server)]
    (try
      (client/connect-with-streams! client in out)
      (sdk/create-session client
                          (merge {:on-permission-request sdk/approve-all
                                  :available-tools []}
                                 extra-config))
      @seen
      (finally
        (try (sdk/stop! client) (catch Exception _))
        (mock/stop-mock-server! server)))))

(deftest test-empty-mode-options-update-defaults
  (testing "empty mode sends session.options.update with safe flag defaults + empty plugins"
    (let [p (empty-mode-capture-options-update {})]
      (is (some? p) "session.options.update must be issued in :empty mode")
      (is (= true (:skipCustomInstructions p)))
      (is (= true (:customAgentsLocalOnly p)))
      (is (= false (:coauthorEnabled p)))
      (is (= false (:manageScheduleEnabled p)))
      (is (= [] (:includedBuiltinSkills p)))
      (is (= [] (:installedPlugins p)))
      (is (string? (:sessionId p)) "session-id must accompany the patch"))))

(deftest test-empty-mode-options-update-caller-overrides
  (testing "caller-supplied flags override empty-mode defaults in options.update patch"
    (let [p (empty-mode-capture-options-update
             {:skip-custom-instructions false
              :coauthor-enabled true})]
      (is (= false (:skipCustomInstructions p)) "caller false must win over default true")
      (is (= true (:coauthorEnabled p)) "caller true must win over default false")
      (is (= true (:customAgentsLocalOnly p)) "untouched flags keep their defaults")
      (is (= false (:manageScheduleEnabled p)))
      (is (= [] (:includedBuiltinSkills p)))
      (is (= [] (:installedPlugins p)) "installedPlugins always forced to [] in :empty mode"))))

(deftest test-empty-mode-options-update-built-in-skill-allowlist
  (testing "empty mode preserves an explicit built-in skill allowlist"
    (let [p (empty-mode-capture-options-update
             {:included-builtin-skills ["search" "edit"]})]
      (is (= ["search" "edit"] (:includedBuiltinSkills p))))))

(deftest test-cli-mode-options-update-skipped-when-no-caller-flags
  (testing "CLI mode does NOT issue session.options.update when caller sets no flags"
    (let [seen (atom nil)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.options.update" method)
                                        (reset! seen params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all})]
      (is (nil? @seen)
          "no RPC should be sent when the patch would be empty"))))

(deftest test-cli-mode-options-update-forwards-only-explicit-flags
  (testing "CLI mode forwards ONLY caller-supplied flags via options.update (no defaults, no installedPlugins)"
    (let [seen (atom nil)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.options.update" method)
                                        (reset! seen params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :manage-schedule-enabled true})
          p @seen]
      (is (some? p) "RPC should be issued because caller set a flag")
      (is (= true (:manageScheduleEnabled p)))
      (is (not (contains? p :skipCustomInstructions)) "untouched flags must NOT appear")
      (is (not (contains? p :customAgentsLocalOnly)))
      (is (not (contains? p :coauthorEnabled)))
      (is (not (contains? p :installedPlugins))
          "installedPlugins must NOT be forced in :copilot-cli mode"))))

(deftest test-cli-mode-options-update-built-in-skill-allowlist
  (testing "CLI mode forwards the built-in skill allowlist only when explicitly configured"
    (let [seen (atom nil)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= "session.options.update" method)
                                        (reset! seen params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :included-builtin-skills []})
          p @seen]
      (is (= [] (:includedBuiltinSkills p)))
      (is (not (contains? p :installedPlugins)))
      (is (not (contains? p :skipCustomInstructions))))))

(deftest test-empty-mode-options-update-async-path
  (testing "async <create-session also issues session.options.update with mode defaults"
    (let [server (mock/create-mock-server)
          _ (mock/start-mock-server! server)
          seen (atom nil)
          _ (mock/set-request-hook! server
                                    (fn [method params]
                                      (when (= "session.options.update" method)
                                        (reset! seen params))))
          client (sdk/client {:mode :empty
                              :copilot-home "/tmp/empty-mode-async-test"
                              :auto-start? false})
          [in out] (mock/client-streams server)]
      (try
        (client/connect-with-streams! client in out)
        (let [result-ch (sdk/<create-session client
                                             {:on-permission-request sdk/approve-all
                                              :available-tools []})
              result (first (alts!! [result-ch (timeout 5000)]))]
          (is (some? result) "async create-session must return a session")
          (is (not (instance? Throwable result))
              (str "async create-session failed: " result)))
        (let [p @seen]
          (is (some? p) "options.update must be issued in async path")
          (is (= true (:skipCustomInstructions p)))
          (is (= [] (:installedPlugins p))))
        (finally
          (try (sdk/stop! client) (catch Exception _))
          (mock/stop-mock-server! server))))))

(deftest test-empty-mode-options-update-failure-cleans-up-session
  (testing "options.update RPC failure cleans up session (disconnect + remove) and rethrows"
    (let [server (mock/create-mock-server)
          _ (mock/start-mock-server! server)
          requests (atom [])
          _ (mock/set-request-hook! server
                                    (fn [method _params]
                                      (swap! requests conj method)
                                      (when (= "session.options.update" method)
                                        (throw (ex-info "Simulated options.update failure"
                                                        {:code -32603})))))
          client (sdk/client {:mode :empty
                              :copilot-home "/tmp/empty-mode-fail-test"
                              :auto-start? false})
          [in out] (mock/client-streams server)]
      (try
        (client/connect-with-streams! client in out)
        (let [ex (try
                   (sdk/create-session client
                                       {:on-permission-request sdk/approve-all
                                        :available-tools []})
                   nil
                   (catch Throwable t t))]
          (is (some? ex) "create-session must rethrow on options.update failure")
          (is (re-find #"options\.update" (.getMessage ex))
              "exception message should mention options.update"))
        ;; After failure, the SDK should have removed the half-configured session
        ;; from its in-memory registry.
        (is (empty? (:sessions @(:state client)))
            "failed session must be removed from in-memory registry")
        (is (= 1 (count (filter #{"session.destroy"} @requests)))
            "failed setup must destroy the runtime session exactly once")
        (finally
          (try (sdk/stop! client) (catch Exception _))
          (mock/stop-mock-server! server))))))

(deftest test-empty-mode-options-update-async-failure-cleans-up-session
  (testing "async <create-session: options.update failure cleans up session and yields Throwable"
    (let [server (mock/create-mock-server)
          _ (mock/start-mock-server! server)
          requests (atom [])
          _ (mock/set-request-hook! server
                                    (fn [method _params]
                                      (swap! requests conj method)
                                      (when (= "session.options.update" method)
                                        (throw (ex-info "Simulated options.update failure"
                                                        {:code -32603})))))
          client (sdk/client {:mode :empty
                              :copilot-home "/tmp/empty-mode-async-fail-test"
                              :auto-start? false})
          [in out] (mock/client-streams server)]
      (try
        (client/connect-with-streams! client in out)
        (let [result-ch (sdk/<create-session client
                                             {:on-permission-request sdk/approve-all
                                              :available-tools []})
              result (first (alts!! [result-ch (timeout 5000)]))]
          (is (instance? Throwable result)
              "async create-session must yield a Throwable on options.update failure")
          (is (re-find #"options\.update" (.getMessage result))
              "exception message should mention options.update"))
        (is (empty? (:sessions @(:state client)))
            "failed session must be removed from in-memory registry (async path)")
        (is (= 1 (count (filter #{"session.destroy"} @requests)))
            "failed async setup must destroy the runtime session exactly once")
        (finally
          (try (sdk/stop! client) (catch Exception _))
          (mock/stop-mock-server! server))))))

(deftest disconnect-concurrent-idempotent-test
  ;; disconnect! must be idempotent under concurrent calls: only the caller that
  ;; atomically claims :destroyed? should send session.destroy. A non-atomic
  ;; check-then-act lets multiple concurrent callers each send the RPC. Run many
  ;; iterations with several racing threads; the non-atomic version observes the
  ;; race in ~45% of iterations, so requiring exactly one RPC per iteration is a
  ;; reliable (non-flaky) regression guard.
  (dotimes [_ 100]
    (let [ch (chan)
          client {:state (atom {:sessions {"s1" {:destroyed? false}}
                                :session-io {"s1" {:event-chan ch}}
                                :connection-io :fake})}
          calls (atom 0)
          latch (java.util.concurrent.CountDownLatch. 1)]
      (with-redefs [protocol/send-request! (fn [& _] (swap! calls inc) nil)]
        (let [threads (doall (for [_ (range 8)]
                               (future (.await latch)
                                       (try (session/disconnect! client "s1")
                                            (catch Throwable _)))))]
          (.countDown latch)
          (doseq [t threads] @t)))
      (is (= 1 @calls)
          "exactly one concurrent disconnect! should send session.destroy")
      (is (true? (get-in @(:state client) [:sessions "s1" :destroyed?]))))))
