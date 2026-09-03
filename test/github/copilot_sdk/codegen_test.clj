(ns github.copilot-sdk.codegen-test
  "Cross-validation tests for the schema-driven codegen pipeline.

   Four distinct purposes:

   1. **Forward correctness** — generated `*-data` specs in
      `github.copilot-sdk.generated.event-specs` must accept canonical wire
      payloads (post `util/wire->clj`). If they don't, the generator or the
      pinned schema is wrong.

   2. **Envelope discrimination** — generated full envelope specs (e.g.
      `::session.start`) and the aggregate `::event` spec must reject
      payloads with a wrong `:type` literal, mismatched `:data` shape, or
      an unknown event type. These tests pin the envelope-as-discriminator
      contract: a feature regression here would silently destroy the value
      of `::event`.

   3. **Three-tier coercion contract** (Phase 3.5) — every coercion entry
      in `script/codegen/coercions.edn` must:
        a. round-trip semantically through wire→idiom→wire (Instant
           equality, not string equality, since ISO 8601 normalization can
           reshape the textual form);
        b. be exercised by at least one fixture (no dead entries);
        c. produce a value that satisfies the hand-written idiom spec.

   4. **Drift audit** — for every event variant that has BOTH a hand-written
      spec in `github.copilot-sdk.specs` and a generated spec, the hand spec
      must accept the COERCED payload (not the raw wire payload). After
      Phase 3.5 the audit's `known-drifts` set is empty: every richer hand
      spec is either reconciled by coercion or reflected in a passthrough
      spec.

   These tests do **not** instrument any runtime path. They validate the
   generated artifacts directly against fixture payloads, so they are pure
   regression coverage for the codegen pipeline."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [github.copilot-sdk.generated.coerce :as coerce]
            [github.copilot-sdk.generated.event-specs :as gen]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.specs :as specs])
  (:import [java.time Instant]))

(def ^:private codegen-probe
  (delay
    (let [{:keys [exit out err]}
          (sh/sh
           "bb" "-cp" "script" "-e"
           (str
            "(require '[clojure.spec.alpha :as s] "
            "         '[codegen.core :as core] '[codegen.emit-specs :as emit]) "
            "(let [bounded-spec (eval (emit/emit-type {:type \"integer\" :minimum 2 :maximum 4} "
            "                                          {:type \"integer\" :minimum 2 :maximum 4})) "
            "      exclusive-spec (eval (emit/emit-type {:type \"number\" :exclusiveMinimum 1 :exclusiveMaximum 2} "
            "                                            {:type \"number\" :exclusiveMinimum 1 :exclusiveMaximum 2})) "
            "      nullable-spec (eval (emit/emit-type {:type [\"string\" \"null\"]} "
            "                                           {:type [\"string\" \"null\"]})) "
            "      closed-object-form "
            "      (pr-str (emit/emit-type {:type \"object\"} "
            "                              {:type \"object\" "
            "                               :properties {:z {:type \"string\"} "
            "                                            :a {:type \"string\"} "
            "                                            :m {:type \"string\"}} "
            "                               :additionalProperties false}))] "
            "  (prn {:keys (mapv core/wire-key->kebab "
            "                    [\"_meta\" \"sessionId\" \"tool_efficiency\" "
            "                     \"URLValue\" \"someURLValue\" \"__foo_bar\"]) "
            "        :bounded (mapv #(s/valid? bounded-spec %) [1 2 2.5 4 5]) "
            "        :exclusive (mapv #(s/valid? exclusive-spec %) [1 1.5 2]) "
            "        :nullable (mapv #(s/valid? nullable-spec %) [\"value\" nil 1]) "
            "        :closed-object-form closed-object-form}))"))]
      (when-not (zero? exit)
        (throw (ex-info "Codegen probe failed" {:exit exit :stderr err})))
      (edn/read-string out))))

(deftest codegen-wire-key-normalization-matches-runtime
  (is (= ["meta" "session-id" "tool-efficiency"
          "url-value" "some-url-value" "foo-bar"]
         (:keys @codegen-probe))))

(deftest codegen-emits-json-schema-numeric-bounds
  (is (= [false true false true false] (:bounded @codegen-probe)))
  (is (= [false true false] (:exclusive @codegen-probe)))
  (is (= [true true false] (:nullable @codegen-probe))))

(deftest codegen-emits-canonical-closed-object-key-order
  (is (str/includes? (:closed-object-form @codegen-probe) "#{:a :m :z}")))

(deftest generated-object-shape-definitions-are-canonical
  (let [source    (slurp "src/github/copilot_sdk/generated/event_specs.clj")
        all-names (map second
                       (re-seq
                        #"\(s/def :github\.copilot-sdk\.generated\.event-specs/([^ \n]+) "
                        source))
        names     (vec (take-while #(str/ends-with? % "-shape") all-names))]
    (is (seq names))
    (is (= (sort names) names))))

;; ---------------------------------------------------------------------------
;; Schema introspection helpers — used by the envelope helper to honour
;; per-variant `const` properties (e.g. `ephemeral: const true` on
;; `session.idle`). Reading the schema at test time keeps the test in
;; lock-step with whatever the generator was run against; if upstream changes
;; the const value, the generated spec changes and the test agrees.
;; ---------------------------------------------------------------------------

(def ^:private schema
  (with-open [r (io/reader (io/file "schemas/session-events.schema.json"))]
    (json/read r :key-fn keyword)))

(def ^:private envelope-consts-by-event
  "Map of event-type → {envelope-property → const-value} for every variant
   property declaring a JSON Schema `const`. Used to construct envelopes
   that satisfy the generator's const predicates."
  (let [variants (-> schema :definitions :SessionEvent :anyOf)
        deref-ref (fn [node]
                    (if-let [r (:$ref node)]
                      (let [path (rest (str/split r #"/"))]
                        (get-in schema (mapv keyword path)))
                      node))]
    (into {}
          (keep (fn [variant]
                  (let [variant    (deref-ref variant)
                        props      (:properties variant)
                        event-type (get-in props [:type :const])
                        consts     (->> props
                                        (keep (fn [[k v]]
                                                (when (contains? v :const)
                                                  [(keyword (name k))
                                                   (:const v)])))
                                        (into {}))]
                    (when event-type
                      [event-type consts])))
                variants))))

(defn- event-types-with-schema-marker
  [marker value]
  (let [variants (-> schema :definitions :SessionEvent :anyOf)]
    (into #{}
          (keep (fn [variant]
                  (let [variant (if-let [r (:$ref variant)]
                                  (get-in schema (mapv keyword (rest (str/split r #"/"))))
                                  variant)]
                    (when (= value (get variant marker))
                      (get-in variant [:properties :type :const])))))
          variants)))

(def ^:private internal-event-types
  "Event types that the protocol schema marks internal. Generated wire specs
   cover them for forward compatibility, but they are not part of the public
   SDK event surface."
  (event-types-with-schema-marker :visibility "internal"))

(def ^:private experimental-event-types
  (event-types-with-schema-marker :stability "experimental"))

(def ^:private intentionally-excluded-experimental-event-types
  #{"assistant.fusion_phase_completed"
    "assistant.fusion_phase_failed"
    "assistant.fusion_phase_started"
    "factory.run_settled"
    "factory.run_started"
    "session.fusion_completed"
    "session.fusion_resolved"
    "session.fusion_route_failed"
    "session.fusion_route_started"
    "ui.ephemeral_query"})

;; ---------------------------------------------------------------------------
;; Canonical wire-shape fixtures (post `util/wire->clj`, i.e. kebab-case keys).
;; Keep these minimal but type-correct per the upstream JSON schema.
;; ---------------------------------------------------------------------------

(def ^:private fixtures
  "Map of event-type → minimal-but-valid `data` payload."
  {"session.start"
   {:session-id "s-1"
    :version 1                                 ;; number per schema
    :producer "test"
    :copilot-version "1.0.0"
    :auto-tier "balance"
    :start-time "2024-01-01T00:00:00Z"}        ;; ISO string per schema

   "session.resume"
   {:resume-time "2024-01-01T00:00:00Z"
    :auto-tier "efficiency"
    :event-count 0}

   "session.error"
   {:error-type "internal"
    :message "boom"}

   "session.idle"
   {:aborted false
    :mode "autopilot"}

   "session.info"
   {:info-type "general"
    :message "hello"}

   "session.shutdown"
   {:shutdown-type "routine"
    :total-premium-requests 0
    :total-api-duration-ms 0
    :session-start-time 1700000000
    ;; ShutdownCodeChanges requires all three fields (additionalProperties false).
    :code-changes {:lines-added 0 :lines-removed 0 :files-modified []}
    :model-metrics {}}

   "session.model_change"
   {:new-model "gpt-4o"}

   "session.handoff"
   {:handoff-time "2024-01-01T00:00:00Z"
    :source-type "remote"}

   "user.message"
   {:content "hello"}

   "assistant.turn_start"
   {:turn-id "t-1"}

   "assistant.turn_retry"
   {:turn-id "t-1"}

   "assistant.server_tool_progress"
   {:kind "web_search"
    :output-index 0
    :status "in_progress"}

   "assistant.reasoning"
   {:reasoning-id "r-1"
    :content "thinking"}

   "assistant.reasoning_delta"
   {:reasoning-id "r-1"
    :delta-content "thi"}

   "assistant.message"
   {:message-id "m-1"
    :content "hi"
    :tool-requests
    [{:tool-call-id "tc-1"
      :name "read_file"
      :arguments {:filePath "README.md"}
      :type "function"
      :intention-summary "Read the project overview"
      :caller {:caller-id "program-1"
               :type "program"}}]}

   "assistant.message_delta"
   {:message-id "m-1"
    :delta-content "hi"}

   "assistant.usage"
   {:model "gpt-4o"
    :cache-expires-at "2026-07-29T12:00:00Z"
    :service-request-id "svc-req-1"}

   "tool.execution_start"
   {:tool-call-id "tc-1"
    :tool-name "shell"}

   "tool.execution_progress"
   {:tool-call-id "tc-1"
    :progress-message "running…"}

   "tool.execution_complete"
   {:tool-call-id "tc-1"
    :success true}

   "tool_search.activated"
   {:strategy "deferred"
    :tool-names ["shell"]}

   "skill.invoked"
   {:name "my-skill"
    :path "/skills/my-skill"
    :content "skill body"}

   "subagent.started"
   {:tool-call-id "tc-1"
    :agent-name "subagent"
    :agent-display-name "SubAgent"
    :agent-description "does things"}

   "subagent.configured"
   {:model "gpt-5.4"
    :multi-turn true}

   "subagent.completed"
   {:tool-call-id "tc-1"
    :agent-name "subagent"
    :agent-display-name "SubAgent"
    :cancelled true}

   "subagent.failed"
   {:tool-call-id "tc-1"
    :agent-name "subagent"
    :agent-display-name "SubAgent"
    :error "boom"}

   "assistant.streaming_delta"
   {:total-response-size-bytes 1024}

   "session.title_changed"
   {:title "My Title"}

   "session.warning"
   {:warning-type "general"
    :message "watch out"}

   "session.context_changed"
   {:cwd "/tmp"}

   "session.context_cleared"
   {:messages-cleared 3}

   "model.call_start"
   {:turn-id "t-1"}

   "session.mode_changed"
   {:previous-mode "interactive"
    :new-mode "plan"}

   "session.mode_notice_delivered"
   {:mode "plan"
    :content "Plan mode is active"}

   "session.plan_changed"
   {:operation "create"}

   "session.workspace_file_changed"
   {:path "/tmp/file"
    :operation "create"}

   "session.task_complete"
   {}

   "session.compaction_start"
   {}

   "session.compaction_complete"
   {:success true}

   "factory.run_updated"
   {:run-id "run-1"
    :revision 1}

   "session.custom_agents_updated"
   {:agents []
    :warnings []
    :errors []}

   "session.managed_settings_resolved"
   {:bypass-permissions-disabled false
    :device-managed false
    :fail-closed false
    :managed-keys []
    :server-managed false
    :source "none"}

   "session.managed_settings_enforced"
   {:action "bypass_permissions_blocked"
    :fail-closed false
    :message "Bypass permissions mode is disabled"
    :setting "permissions.disableBypassPermissionsMode"}

   "session.mcp_servers_loaded"
   {:servers []}

   "session.mcp_server_status_changed"
   {:server-name "server-1"
    :status "connected"}

   "session.skills_loaded"
   {:skills []}

   "session.extensions_loaded"
   {:extensions []}

   "session.schedule_created"
   {:id 42 :interval-ms 1000 :prompt "ping me"}

   "session.schedule_cancelled"
   {:id 42}

   "session.custom_notification"
   {:source "my-extension"
    :name "doc.opened"
    :payload {:path "/tmp/x"}
    :subject {:doc "foo"}
    :version 1}

   ;; Stable data additions in schema 1.0.81-5.
   "model.call_failure"
   {:source "top_level"
    :interaction-type "conversation-agent"}

   "model.call_finished"
   {:turn-id "t-1"
    :dispatch-duration-ms 10
    :outcome "success"
    :edit-classifier-version 1}

   "system.notification"
   {:content "worker finished"
    :kind {:type "agent_completed"
           :agent-id "agent-1"
           :agent-type "task"
           :status "completed"
           :display-name "Build verifier"}}

   "external_tool.requested"
   {:request-id "request-1"
    :session-id "session-1"
    :tool-call-id "tool-1"
    :tool-name "provider-tool"
    :provider-id nil}

   ;; Round 6 additions (upstream schema 1.0.56-1).
   "session.permissions_changed"
   {:mode "assisted"
    :previous-mode "manual"
    :assisted-approval-model "gpt-5.4"}

   "session.autopilot_objective_changed"
   {:operation "create"
    :id 7
    :status "active"}

   "hook.progress"
   {:message "extracting..."}

   ;; Stable 2980c78 sync (pinned schema 1.0.83-1): net-new hook.start/hook.end.
   "hook.start"
   {:hook-invocation-id "h-1"
    :hook-type "pre-tool-use"}

   "hook.end"
   {:hook-invocation-id "h-1"
    :hook-type "pre-tool-use"
    :success true}

   ;; v1.0.1 sync: session.canvas.closed (upstream PR #1604).
   "session.canvas.closed"
   {:instance-id "i1" :extension-id "ext.x" :canvas-id "diff"}})

(defn- envelope
  "Wrap a data payload in a minimal valid envelope of the given type. Honours
   per-variant `const` envelope fields from the schema (e.g. some variants
   pin `ephemeral: const true`); fields without a const default to literals
   the schema's structural rules accept."
  [event-type data]
  (let [consts (get envelope-consts-by-event event-type {})]
    (merge {:id "evt-1"
            :timestamp "2024-01-01T00:00:00Z"
            :parent-id "p-1"
            :ephemeral false
            :type event-type
            :data data}
           consts)))

;; ---------------------------------------------------------------------------
;; Forward correctness — generated specs must accept wire-shape payloads.
;; ---------------------------------------------------------------------------

(deftest generated-data-specs-accept-wire-payloads
  (doseq [[event-type payload] fixtures]
    (testing (str "generated " event-type "-data accepts canonical payload")
      (let [spec-kw (keyword "github.copilot-sdk.generated.event-specs"
                             (str event-type "-data"))]
        (is (s/get-spec spec-kw)
            (str "generated spec missing for " event-type))
        (is (s/valid? spec-kw payload)
            (str "generated spec rejected wire payload for " event-type
                 ": " (s/explain-str spec-kw payload)))))))

(deftest event-types-set-matches-fixtures
  (testing "every fixture event-type is in the generated event-types set"
    (doseq [event-type (keys fixtures)]
      (is (contains? gen/event-types event-type)
          (str event-type " missing from gen/event-types — schema may have moved")))))

(deftest assistant-message-tool-request-caller-idiom-contract
  (let [request (-> fixtures
                    (get "assistant.message")
                    :tool-requests
                    first)]
    (is (s/valid? ::specs/assistant-message-tool-request request))
    (is (s/valid? ::specs/assistant-message-tool-request-caller
                  (:caller request)))
    (is (s/valid? ::specs/assistant-message-tool-request
                  (assoc request :future-tool-field {:enabled true})))
    (is (s/valid? ::specs/assistant-message-tool-request-caller
                  (assoc (:caller request) :future-caller-field "value")))
    (is (not (s/valid? ::specs/assistant-message-tool-request
                       (assoc-in request [:caller :type] "assistant"))))
    (is (not (s/valid? ::specs/assistant-message-tool-request
                       (update request :caller dissoc :caller-id))))))

(deftest session-idle-idiom-contract-is-forward-compatible
  (is (s/valid? ::specs/session.idle-data
                {:aborted false
                 :mode "autopilot"
                 :future-idle-field {:enabled true}}))
  (is (not (s/valid? ::specs/session.idle-data
                     {:mode :autopilot
                      :future-idle-field true}))))

(deftest hook-start-envelope-is-closed-but-data-is-forward-compatible
  (let [data (get fixtures "hook.start")
        valid-envelope (envelope "hook.start" data)]
    (testing "hook.start data stays open — event data carries future fields"
      (is (s/valid? ::gen/hook.start-data
                    (assoc data :future-hook-field {:enabled true}))))
    (testing "hook.start envelope accepts exactly its declared keys"
      (is (s/valid? ::gen/hook.start valid-envelope)))
    (testing "hook.start envelope rejects undeclared envelope-level keys"
      (is (not (s/valid? ::gen/hook.start
                         (assoc valid-envelope :future-envelope-field true)))
          "schema-closed envelopes must reject fields outside the declared envelope keys"))
    (testing "closing the envelope does not close the nested data payload"
      (is (s/valid? ::gen/hook.start
                    (envelope "hook.start" (assoc data :future-hook-field {:enabled true})))))))

(deftest public-event-types-match-generated-schema-set
  (testing "the public curated `event-types` set covers exactly the schema's public event types
            (guards against drift without exposing internal or intentionally excluded experimental events)"
    (let [curated (set (map name sdk/event-types))
          generated (clojure.set/difference
                     gen/event-types
                     internal-event-types
                     intentionally-excluded-experimental-event-types)
          missing (clojure.set/difference generated curated)
          extra (clojure.set/difference curated generated)]
      (is (empty? missing)
          (str "schema event types missing from public event-types: " (sort missing)
               " — add them (or update the schema pin)"))
      (is (empty? extra)
          (str "public event-types not present in the schema: " (sort extra)
               " — remove them or update the schema pin")))))

(deftest intentionally-excluded-events-remain-experimental
  (is (clojure.set/subset? intentionally-excluded-experimental-event-types
                           gen/event-types)
      "Every intentional event exclusion must still exist in the generated schema")
  (is (clojure.set/subset? intentionally-excluded-experimental-event-types
                           experimental-event-types)
      "An intentionally excluded event becoming stable requires a public-surface decision"))

(deftest generated-data-specs-preserve-variant-local-types
  (testing "session.schedule_created-data rejects string :id (must be positive integer)"
    (let [spec-kw :github.copilot-sdk.generated.event-specs/session.schedule_created-data]
      (is (not (s/valid? spec-kw {:id "uuid-string" :interval-ms 1000 :prompt "x"}))
          "data spec must not accept envelope-shaped UUID :id")))
  (testing "session.schedule_cancelled-data rejects string :id (must be positive integer)"
    (let [spec-kw :github.copilot-sdk.generated.event-specs/session.schedule_cancelled-data]
      (is (not (s/valid? spec-kw {:id "uuid-string"}))
          "data spec must not accept envelope-shaped UUID :id")))
  (testing "same-named data properties keep each event variant's schema"
    (let [abort-spec :github.copilot-sdk.generated.event-specs/abort-data
          retry-spec :github.copilot-sdk.generated.event-specs/assistant.turn_retry-data]
      (doseq [reason ["user_initiated" "remote_command" "user_abort"]]
        (is (s/valid? abort-spec {:reason reason})))
      (is (not (s/valid? abort-spec {:reason "arbitrary_reason"}))
          "abort reason must remain a closed enum")
      (is (s/valid? retry-spec {:turn-id "turn-1"
                                :reason "arbitrary_reason"})
          "assistant.turn_retry reason must remain an open string"))))

;; ---------------------------------------------------------------------------
;; Recursive nested-object emission — generated specs for `$ref`'d object
;; properties (e.g. AssistantMessageToolRequestCaller nested inside
;; AssistantMessageToolRequest) must enforce required keys, per-property
;; recursive validity, and `additionalProperties: false`, not degrade to a
;; bare `map?`. See `script/codegen/emit_specs.clj`'s `emit-object`.
;; ---------------------------------------------------------------------------

(deftest generated-nested-object-specs-enforce-structure
  (let [spec-kw       :github.copilot-sdk.generated.event-specs/tool-requests
        valid-request (-> fixtures (get "assistant.message") :tool-requests first)]
    (testing "accepts the canonical, fully-valid fixture"
      (is (s/valid? spec-kw [valid-request])
          (s/explain-str spec-kw [valid-request])))
    (testing "rejects a caller missing required :caller-id"
      (is (not (s/valid? spec-kw [(update valid-request :caller dissoc :caller-id)]))))
    (testing "rejects a caller whose :type is not the single enum value \"program\""
      (is (not (s/valid? spec-kw [(assoc-in valid-request [:caller :type] "assistant")]))))
    (testing "rejects a caller with an unknown key (additionalProperties: false)"
      (is (not (s/valid? spec-kw [(assoc-in valid-request [:caller :bogus-field] "x")]))))
    (testing "rejects a tool-request itself missing a required key (:name)"
      (is (not (s/valid? spec-kw [(dissoc valid-request :name)]))))
    (testing "rejects a tool-request with an unknown top-level key (additionalProperties: false)"
      (is (not (s/valid? spec-kw [(assoc valid-request :bogus-top-level "x")]))))))

(deftest generated-number-specs-require-json-numbers
  (testing "schema number types reject non-finite values and Clojure ratios"
    (doseq [value [Double/NaN
                   Double/POSITIVE_INFINITY
                   Double/NEGATIVE_INFINITY
                   1/2]]
      (is (not (s/valid? ::gen/dispatch-duration-ms value))
          (pr-str value)))
    (doseq [value [0 0.5 42]]
      (is (s/valid? ::gen/dispatch-duration-ms value)
          (pr-str value)))))

(deftest generated-opaque-json-specs-require-recursive-json-values
  (let [valid-blocks [{:signatureId "sig-1"
                       :content ["text" true 42 nil]}]]
    (is (s/valid? ::gen/assistant-message-reasoning-blocks-shape
                  {:provider "anthropic"
                   :blocks valid-blocks}))
    (doseq [invalid [(Object.)
                     {:nested (Object.)}
                     {:nested #{1 2}}
                     {:nested 1/2}
                     {:nested Double/POSITIVE_INFINITY}]]
      (is (not (s/valid? ::gen/assistant-message-reasoning-blocks-shape
                         {:provider "anthropic"
                          :blocks [invalid]}))
          (pr-str invalid)))))

(deftest opaque-json-specs-are-stack-safe
  (let [depth 20000
        nested-json (reduce (fn [value _] [value]) nil (range depth))
        nested-invalid (reduce (fn [value _] [value]) (Object.) (range depth))
        hook-start {:hook-invocation-id "hook-1"
                    :hook-type "pre-tool-use"}]
    (is (s/valid? ::gen/hook.start-data
                  (assoc hook-start :input nested-json)))
    (is (s/valid? ::specs/hook.start-data
                  (assoc hook-start :input nested-json)))
    (is (not (s/valid? ::gen/hook.start-data
                       (assoc hook-start :input nested-invalid))))
    (is (not (s/valid? ::specs/hook.start-data
                       (assoc hook-start :input nested-invalid))))))

(deftest generated-event-data-remains-open-at-the-top-level
  (testing "future event fields pass standalone and through envelope specs"
    (let [data {:hook-invocation-id "hook-1"
                :hook-type "pre-tool-use"
                :future-field {:nested ["value"]}}
          event (envelope "hook.start" data)]
      (is (s/valid? ::gen/hook.start-data data))
      (is (s/valid? ::gen/hook.start event))
      (is (s/valid? ::gen/event event))))
  (testing "nested schema objects stay closed"
    (is (not (s/valid?
              ::gen/assistant-message-tool-request-caller-shape
              {:caller-id "caller-1"
               :type "program"
               :future-field true})))))

;; ---------------------------------------------------------------------------
;; Envelope discrimination — type and data binding must be tight.
;; ---------------------------------------------------------------------------

(deftest envelope-accepts-matching-type-and-data
  (doseq [[event-type payload] fixtures]
    (testing (str "::" event-type " accepts a well-formed envelope")
      (let [spec-kw (keyword "github.copilot-sdk.generated.event-specs" event-type)
            env    (envelope event-type payload)]
        (is (s/valid? spec-kw env)
            (str "envelope rejected: " (s/explain-str spec-kw env)))))))

(deftest envelope-rejects-wrong-type-literal
  (let [start-payload (get fixtures "session.start")
        wrong-env     (envelope "session.shutdown" start-payload)]
    (is (not (s/valid? :github.copilot-sdk.generated.event-specs/session.start
                       wrong-env))
        "::session.start must reject envelope whose :type is not \"session.start\"")
    (is (not (s/valid? :github.copilot-sdk.generated.event-specs/session.shutdown
                       wrong-env))
        "::session.shutdown must reject envelope whose :data does not match its data spec")))

(deftest envelope-rejects-mismatched-data-shape
  (let [bad-env (envelope "session.start" {:totally "unrelated"})]
    (is (not (s/valid? :github.copilot-sdk.generated.event-specs/session.start
                       bad-env))
        "::session.start must reject envelope when :data does not satisfy ::session.start-data")))

(deftest aggregate-event-spec-discriminates
  (testing "::event accepts known event types with matching data"
    (doseq [[event-type payload] fixtures]
      (let [env (envelope event-type payload)]
        (is (s/valid? ::gen/event env)
            (str "::event rejected " event-type ": " (s/explain-str ::gen/event env))))))
  (testing "::event rejects unknown :type"
    (let [env (envelope "definitely-not-an-event"
                        (get fixtures "session.start"))]
      (is (not (s/valid? ::gen/event env))
          "::event must reject unknown :type values")))
  (testing "::event rejects mismatched (type, data) pairs"
    (let [env (envelope "session.start" (get fixtures "session.shutdown"))]
      (is (not (s/valid? ::gen/event env))
          "::event must reject envelopes whose :data shape does not match :type"))))

;; ---------------------------------------------------------------------------
;; Three-tier coercion contract (Phase 3.5).
;; ---------------------------------------------------------------------------

(deftest coercion-table-is-exercised-by-fixtures
  (testing "every coercion entry is exercised by ≥1 fixture with a non-nil value"
    (doseq [[event-type fields] coerce/field-coercions
            [field _tag-pair]   fields]
      (is (contains? fixtures event-type)
          (str "coercion declared for " event-type "/" field
               " but no fixture covers it"))
      (is (contains? (get fixtures event-type) field)
          (str "fixture for " event-type " is missing field " field
               " required by coercion table"))
      (is (some? (get-in fixtures [event-type field]))
          (str "fixture for " event-type "/" field
               " has nil value; coverage would be vacuous")))))

(deftest coercion-is-idempotent
  (testing "applying wire->idiom twice equals applying it once"
    (doseq [[event-type payload] fixtures]
      (let [event   {:type event-type :data payload}
            once    (coerce/event-wire->idiom event)
            twice   (coerce/event-wire->idiom once)]
        (is (= once twice)
            (str "coercion not idempotent for " event-type))))))

(deftest coercion-round-trips-semantically
  (testing "wire → idiom → wire preserves field values (semantic equality)"
    (doseq [[event-type payload] fixtures]
      (let [event       {:type event-type :data payload}
            round-trip  (coerce/event-idiom->wire (coerce/event-wire->idiom event))
            fields      (get coerce/field-coercions event-type)]
        (doseq [[field [wire-tag _idiom-tag]] fields
                :let [orig-v (get-in event       [:data field])
                      rt-v   (get-in round-trip  [:data field])]
                :when (some? orig-v)]
          ;; For ISO timestamps, compare semantically (Instant equality)
          ;; rather than textually — ISO 8601 has multiple valid string
          ;; representations and Instant/toString canonicalizes.
          (case wire-tag
            :iso-string
            (is (= (Instant/parse orig-v) (Instant/parse rt-v))
                (str "round-trip lost semantic equality for "
                     event-type "/" field ": " orig-v " ⇄ " rt-v))
            ;; default: structural equality
            (is (= orig-v rt-v)
                (str "round-trip lost equality for "
                     event-type "/" field))))))))

(deftest coerced-data-satisfies-hand-spec
  (testing "after wire->idiom, hand-written spec accepts the data"
    (doseq [[event-type payload] fixtures]
      (let [hand-kw (keyword "github.copilot-sdk.specs" (str event-type "-data"))]
        (when (s/get-spec hand-kw)
          (let [coerced-data (coerce/coerce-data event-type payload :wire->idiom)]
            (is (s/valid? hand-kw coerced-data)
                (str "hand spec " hand-kw " rejected coerced data: "
                     (s/explain-str hand-kw coerced-data)))))))))

;; ---------------------------------------------------------------------------
;; Drift audit — surfaces residual disagreements between hand-written and
;; generated specs after coercion is applied.
;;
;; The audit is FIELD-PRECISE:
;;   * `known-drifts` lists [event-type field reason] tuples for any
;;     residual drift that the coercion layer does NOT reconcile.
;;   * For every fixture, we extract the set of failing fields from
;;     `s/explain-data` against the hand-written spec, AFTER coercion.
;;   * We assert that set is exactly equal to the documented drifts for
;;     that event-type — so stale entries (drift was fixed but registry
;;     still references it) AND undocumented drifts both fail the test.
;;
;; After Phase 3.5 reconciliation, this set should be empty: every
;; richer hand spec is reconciled either via coercion (Instant) or by
;; intentional omission from the hand spec (e.g. session.start :version,
;; where the global ::version is shared with ::model-info and the
;; generated wire spec is the canonical contract).
;; ---------------------------------------------------------------------------

(def ^:private known-drifts
  "Hand-written specs known to disagree with the schema even after
   coercion. Each entry is a [event-type field reason] tuple. Empty after
   Phase 3.5: the test fails if any drift is detected, forcing the
   contributor to either add a coercion entry or fix the hand spec."
  #{})

(defn- failing-fields
  "Extract the set of map keys (top-level :in path heads) where `payload`
   fails `spec`. Returns #{} when payload is valid."
  [spec payload]
  (let [data (s/explain-data spec payload)]
    (->> (:clojure.spec.alpha/problems data)
         (map (fn [{:keys [in path]}]
                ;; Prefer :in (which targets actual map keys) over :path
                ;; (which references the s/keys spec name).
                (or (first in) (last path))))
         (remove nil?)
         set)))

(deftest hand-written-specs-agree-with-generated
  (doseq [[event-type payload] fixtures]
    (testing (str "specs.clj ::" event-type "-data agrees with generated for canonical payload (post-coercion)")
      (let [hand-kw (keyword "github.copilot-sdk.specs" (str event-type "-data"))
            gen-kw  (keyword "github.copilot-sdk.generated.event-specs"
                             (str event-type "-data"))]
        (when (s/get-spec hand-kw)
          (is (s/valid? gen-kw payload)
              (str "Generator regression: " gen-kw " rejects fixture: "
                   (s/explain-str gen-kw payload)))
          (let [coerced-data    (coerce/coerce-data event-type payload :wire->idiom)
                actual-failing  (failing-fields hand-kw coerced-data)
                registered-fields (->> known-drifts
                                       (filter #(= event-type (first %)))
                                       (map second)
                                       set)]
            (is (= actual-failing registered-fields)
                (str "Drift mismatch for " event-type " (after coercion):\n"
                     "  actual failing fields:    " (sort actual-failing) "\n"
                     "  registered known-drifts:  " (sort registered-fields) "\n"
                     (cond
                       (seq (clojure.set/difference actual-failing registered-fields))
                       (str "  → undocumented drifts: "
                            (sort (clojure.set/difference actual-failing registered-fields))
                            " (add a coercion entry, fix specs.clj, or add to known-drifts)")
                       (seq (clojure.set/difference registered-fields actual-failing))
                       (str "  → stale drift entries: "
                            (sort (clojure.set/difference registered-fields actual-failing))
                            " (remove from known-drifts; the drift is no longer reproducible)")
                       :else "")))))))))

(deftest every-hand-written-event-data-spec-has-a-fixture
  ;; Coverage gate: the drift audit above only inspects event-types present
  ;; in `fixtures`. This test fails if `specs.clj` defines a richer hand-
  ;; written `::<event>-data` spec for an event that has NO fixture, so a
  ;; contributor cannot silently introduce a hand spec that the drift audit
  ;; never exercises.
  (let [hand-spec-event-types
        (->> gen/event-types
             (filter (fn [event-type]
                       (s/get-spec
                        (keyword "github.copilot-sdk.specs"
                                 (str event-type "-data")))))
             set)
        covered (set (keys fixtures))
        missing (clojure.set/difference hand-spec-event-types covered)]
    (is (empty? missing)
        (str "Hand-written `*-data` specs without a fixture (drift audit "
             "would silently skip them): " (sort missing) ". Either add a "
             "minimal valid fixture in `fixtures`, or remove the hand spec "
             "from `github.copilot-sdk.specs`."))))

;; ---------------------------------------------------------------------------
;; Generated top-level form size — JVM `Method code too large!` regression
;; guard
;;
;; `emit-envelope-spec` previously re-emitted the ENTIRE global non-conforming
;; union for the "type"/"data" kebabs (each a cross-schema union spanning
;; ~135/~133 distinct schemas) as a `strict-pred` INSIDE every one of the
;; ~135 event variants' envelope `s/and` forms — even though each variant
;; already has a strictly stronger dedicated check elsewhere (`const-preds`
;; for `:type`, the trailing `data-kw` predicate for `:data`). That produced
;; many top-level forms in the ~26KB-source-char class, and `event_specs.clj`
;; as a whole ballooned to ~3.85MB. At least one such form was large enough to
;; overflow the JVM's 64KB-per-method bytecode limit at compile time
;; (`Method code too large!`).
;;
;; The fix (see `emit-envelope-spec` in `script/codegen/emit_specs.clj`)
;; excludes const-covered properties and the `"data"` kebab from the
;; redundant per-variant envelope re-check, so each such global union is now
;; emitted exactly ONCE as a load-bearing leaf spec (`::type`, `::data`),
;; consumed by every `s/keys` site via clojure.spec's implicit unqualified-key
;; -> fully-qualified-keyword spec lookup — not duplicated per variant.
;;
;; This test reads the actual generated SOURCE TEXT (not the loaded/expanded
;; namespace) via the plain data reader, so it exercises exactly what
;; `write-clj!` wrote and what the JVM must compile. The 32000-char and
;; 8000-char thresholds are reasoned estimates (not a precisely derived exact
;; safety margin): the current known-good maximum is the single ::data leaf
;; spec at ~19,185 chars, comfortably under both; a recurrence of the fixed
;; bug would produce MANY forms at or above the ~26,058-char class this test
;; guards against.
;; ---------------------------------------------------------------------------

(def ^:private generated-event-specs-forms
  "All top-level forms in the generated event-specs source file, excluding
   the leading `ns` form. Read with the plain data reader (`*read-eval*`
   disabled) rather than `require`d/macroexpanded, so measurements reflect
   the literal written source text — what the JVM actually has to compile."
  (let [path (io/file "src/github/copilot_sdk/generated/event_specs.clj")]
    (binding [*read-eval* false]
      (with-open [r (java.io.PushbackReader. (io/reader path))]
        (->> (repeatedly #(read {:eof ::eof} r))
             (take-while #(not= % ::eof))
             (remove #(and (seq? %) (= 'ns (first %))))
             doall)))))

(deftest generated-top-level-forms-stay-well-under-jvm-method-size-limit
  (let [sized (map (fn [form] {:form form :len (count (pr-str form))})
                   generated-event-specs-forms)
        max-entry (apply max-key :len sized)]
    (testing "no single generated top-level form approaches the 64KB JVM per-method bytecode limit"
      (is (<= (:len max-entry) 32000)
          (str "Largest generated top-level form is " (:len max-entry)
               " chars (def name: " (pr-str (second (:form max-entry))) "). "
               "This class of bloat previously caused `Method code too "
               "large!` at JVM compile time — see `emit-envelope-spec` in "
               "`script/codegen/emit_specs.clj`.")))
    (testing "only the known load-bearing global-union leaf spec (`::data`) is large"
      (let [large (->> sized (filter #(> (:len %) 8000)))]
        (is (<= (count large) 1)
            (str "Expected at most one generated top-level form over 8000 "
                 "chars (the load-bearing `::data` global-union leaf spec, "
                 "consumed via `s/keys`'s implicit key-spec lookup in "
                 "`emit-envelope-spec`/`emit-data-spec`). Found "
                 (count large) ": "
                 (pr-str (map (comp second :form) large))
                 ". A jump here likely means the redundant per-variant "
                 "envelope strict-pred bug has recurred — see "
                 "`emit-envelope-spec` in `script/codegen/emit_specs.clj`."))))))
