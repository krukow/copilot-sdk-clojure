(ns github.copilot-sdk.optional-wire-contract-test
  "Table-driven session.create/session.resume optional-field wire contracts.

  The oracle is test/resources/optional_wire_contracts.edn, grounded in the
  exact upstream Node SDK baseline recorded there. Stable public config added
  after that baseline is classified by exact-pin stable-delta reports and
  tested in focused namespaces. Tests invoke the public Clojure API and capture
  JSON-RPC params after serialization."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.walk :as walk]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.client :as client]
            [github.copilot-sdk.mock-server :as mock]
            [github.copilot-sdk.specs :as specs]))

(def contract-report
  (-> "resources/optional_wire_contracts.edn"
      io/resource
      slurp
      edn/read-string))

(def contracts (:contracts contract-report))

(def stable-delta-811adc-report
  (-> "resources/stable_upstream_delta_811adc.edn"
      io/resource
      slurp
      edn/read-string))

(def stable-delta-2980c78-report
  (-> "resources/stable_upstream_delta_2980c78.edn"
      io/resource
      slurp
      edn/read-string))

(def post-2980-session-config
  {:auth/github-token-provider
   {:key :github-token-provider :scopes #{:create :resume}}
   :mode/builtin-skill-isolation
   {:key :included-builtin-skills :scopes #{:create :resume}}
   :session/ask-user-variant
   {:key :ask-user-variant :scopes #{:create :resume}}
   :session/feature-flags
   {:key :feature-flags :scopes #{:create :resume}}})

(def post-baseline-public-config
  (concat
   (->> (:stable-deltas stable-delta-811adc-report)
        (keep (fn [{:keys [id clojure]}]
                (when (= :session-config/enable-file-change-tracking id)
                  {:key (:public-key clojure)
                   :scopes (:scopes clojure)}))))
   (keep post-2980-session-config
         (:stable-delta-ids stable-delta-2980c78-report))))

(def fixtures
  {:fixture/handler (fn [& _])
   :fixture/permission-handler (fn [& _] {:kind :approve-once})
   :fixture/token-provider (fn [_] "matrix-token")
   :fixture/fs-factory (fn [_] {})})

(def ^:dynamic *mock-server* nil)
(def ^:dynamic *test-client* nil)

(defn- with-mock-server [test-fn]
  (let [server (mock/create-mock-server)
        _ (mock/start-mock-server! server)
        test-client (sdk/client {:auto-start? false})
        [in out] (mock/client-streams server)]
    (client/connect-with-streams! test-client in out)
    (binding [*mock-server* server
              *test-client* test-client]
      (try
        (test-fn)
        (finally
          (try
            (sdk/stop! test-client)
            (catch Exception _))
          (mock/stop-mock-server! server))))))

(use-fixtures :each with-mock-server)

(defn- resolve-fixtures [value]
  (walk/postwalk #(get fixtures % %) value))

(defn- resume-config-keys []
  (var-get (ns-resolve 'github.copilot-sdk.specs 'resume-session-config-keys)))

(defn- scopes-for [{:keys [scope]}]
  (case scope
    :shared [:create :resume]
    :create-only [:create]
    :resume-only [:resume]))

(defn- expected-for-scope [expectation scope]
  (if (and (map? expectation)
           (or (contains? expectation :create)
               (contains? expectation :resume)))
    (get expectation scope)
    expectation))

(defn- invoke-config! [scope resume-session-id config]
  (let [requests (atom [])]
    (mock/set-request-hook!
     *mock-server*
     (fn [method params]
       (swap! requests conj [method params])))
    (try
      (case scope
        :create (sdk/create-session *test-client* config)
        :resume (sdk/resume-session *test-client* resume-session-id config))
      {:requests @requests}
      (catch Throwable error
        {:error error :requests @requests}))))

(defn- request-params [requests method]
  (some (fn [[request-method params]]
          (when (= method request-method) params))
        requests))

(defn- target-method [scope]
  (case scope
    :create "session.create"
    :resume "session.resume"))

(defn- assert-path!
  [params path expectation label]
  (let [missing (Object.)
        actual (get-in params path missing)]
    (case expectation
      :absent
      (is (identical? missing actual)
          (str label " expected " path " to be omitted, got " (pr-str actual)))

      :generated-string
      (is (and (string? actual) (not (empty? actual)))
          (str label " expected a generated non-empty string at " path
               ", got " (pr-str actual)))

      (is (= expectation actual)
          (str label " expected " path " = " (pr-str expectation)
               ", got " (pr-str actual))))))

(defn- assert-side-effect!
  [requests {:keys [method path expect]} label]
  (let [params (request-params requests method)]
    (is (some? params) (str label " expected side-effect RPC " method))
    (when params
      (assert-path! params path expect (str label " side effect")))))

(defn- case-config [{:keys [key]} {:keys [value extra-config]}]
  (assoc (resolve-fixtures (or extra-config {}))
         key
         (resolve-fixtures value)))

(deftest contract-report-covers-every-accepted-public-key
  ;; This guards Clojure-spec drift. Node parity is an independent oracle:
  ;; each row cites the pinned upstream source, and review compares those rows
  ;; against that source rather than deriving expectations from production.
  (let [create-keys (into (->> contracts
                               (filter #(#{:shared :create-only} (:scope %)))
                               (map :key)
                               set)
                          (comp (filter #(contains? (:scopes %) :create))
                                (map :key))
                          post-baseline-public-config)
        resume-keys (into (->> contracts
                               (filter #(#{:shared :resume-only} (:scope %)))
                               (map :key)
                               set)
                          (comp (filter #(contains? (:scopes %) :resume))
                                (map :key))
                          post-baseline-public-config)
        duplicate-keys (->> contracts
                            (map :key)
                            frequencies
                            (keep (fn [[key n]] (when (> n 1) key)))
                            set)
        duplicate-case-names
        (->> contracts
             (keep (fn [{:keys [key cases]}]
                     (let [duplicates (->> cases
                                           (map :name)
                                           frequencies
                                           (keep (fn [[case-name n]]
                                                   (when (> n 1) case-name)))
                                           set)]
                       (when (seq duplicates) [key duplicates]))))
             (into {}))
        accepted-node-keys (->> contracts (keep :node-key) set)
        excluded-node-keys (->> (get-in contract-report
                                        [:classification :node-public-not-accepted-by-clojure])
                                (map :node-key)
                                set)]
    (is (empty? duplicate-keys)
        (str "Contract rows must be key-by-key; duplicate rows: " duplicate-keys))
    (is (empty? duplicate-case-names)
        (str "Contract case names must be unique per key: " duplicate-case-names))
    (is (empty? (set/intersection accepted-node-keys excluded-node-keys))
        (str "Accepted Node keys must not remain explicitly excluded: "
             (set/intersection accepted-node-keys excluded-node-keys)))
    (is (= specs/session-config-keys create-keys)
        (str "Create coverage drift. Missing "
             (set/difference specs/session-config-keys create-keys)
             ", extra " (set/difference create-keys specs/session-config-keys)))
    (is (= (resume-config-keys) resume-keys)
        (str "Resume coverage drift. Missing "
             (set/difference (resume-config-keys) resume-keys)
             ", extra " (set/difference resume-keys (resume-config-keys))))
    (is (= "3108e8ce26286043afa52f12781331460628baa0"
           (get-in contract-report [:upstream :commit])))))

(deftest unset-optional-fields-match-node-omission-contract
  (let [create-outcome (invoke-config! :create nil {})
        created-id (get-in (request-params (:requests create-outcome) "session.create")
                           [:sessionId])
        resume-outcome (invoke-config! :resume created-id {})
        baselines {:create (request-params (:requests create-outcome) "session.create")
                   :resume (request-params (:requests resume-outcome) "session.resume")}]
    (is (nil? (:error create-outcome)))
    (is (nil? (:error resume-outcome)))
    (doseq [contract contracts
            scope (scopes-for contract)]
      (let [path (:wire-path contract)
            expectation (expected-for-scope (get contract :unset :absent) scope)
            label (str (:key contract) " unset on " (name scope))]
        (when path
          (assert-path! (get baselines scope) path expectation label))))))

(deftest enable-mcp-apps-public-wire-contract
  (let [seed (sdk/create-session *test-client* {})
        resume-session-id (sdk/session-id seed)
        cases [{:name :unset :config {} :expect :absent}
               {:name :false :config {:enable-mcp-apps false} :expect :absent}
               {:name :true :config {:enable-mcp-apps true} :expect true}
               {:name :explicit-nil :config {:enable-mcp-apps nil} :expect :invalid}]]
    (doseq [scope [:create :resume]
            {:keys [name config expect]} cases]
      (testing (str name " on " (clojure.core/name scope))
        (let [outcome (invoke-config! scope resume-session-id config)
              method (target-method scope)
              params (request-params (:requests outcome) method)
              label (str ":enable-mcp-apps " name " on " (clojure.core/name scope))]
          (if (= :invalid expect)
            (do
              (is (instance? clojure.lang.ExceptionInfo (:error outcome))
                  (str label " must be rejected by the public config contract"))
              (is (nil? params)
                  (str label " must fail before sending " method)))
            (do
              (is (nil? (:error outcome))
                  (str label " unexpectedly failed: "
                       (some-> (:error outcome) ex-message)))
              (when params
                (is (not (contains? params :enableMcpApps))
                    (str label " must not leak the public option name onto the wire"))
                (assert-path! params [:requestMcpApps] expect label)))))))))

(deftest optional-field-values-match-node-wire-contract
  (let [seed (sdk/create-session *test-client* {})
        resume-session-id (sdk/session-id seed)]
    (doseq [contract contracts
            scope (scopes-for contract)
            contract-case (:cases contract)]
      (testing (str (:key contract) " " (:name contract-case)
                    " on " (name scope))
        (let [config (case-config contract contract-case)
              outcome (invoke-config! scope resume-session-id config)
              method (target-method scope)
              params (request-params (:requests outcome) method)
              expectation (expected-for-scope (:expect contract-case) scope)
              label (str (:key contract) " " (:name contract-case)
                         " on " (name scope))]
          (if (= :invalid expectation)
            (do
              (is (instance? clojure.lang.ExceptionInfo (:error outcome))
                  (str label " must be rejected by the public config contract"))
              (is (nil? params)
                  (str label " must fail before sending " method)))
            (do
              (is (nil? (:error outcome))
                  (str label " unexpectedly failed: "
                       (some-> (:error outcome) ex-message)))
              (when (and params (:wire-path contract))
                (assert-path! params (:wire-path contract) expectation label))
              (when-let [side-effect (:side-effect contract-case)]
                (assert-side-effect! (:requests outcome) side-effect label)))))))))

(deftest unknown-session-config-keys-are-rejected-before-rpc
  (let [seed (sdk/create-session *test-client* {})
        session-id (sdk/session-id seed)]
    (doseq [scope [:create :resume]]
      (testing (name scope)
        (let [outcome (invoke-config! scope session-id {:unknown-wire-option true})]
          (is (instance? clojure.lang.ExceptionInfo (:error outcome)))
          (is (nil? (request-params (:requests outcome) (target-method scope)))))))))
