(ns github.copilot-sdk.api-surface-test
  "Fails when the supported public API drifts from the checked-in snapshot."
  (:require [clojure.data :as data]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk.api-surface :as surface]))

(def ^:private expected-public-namespaces
  '#{github.copilot-sdk
     github.copilot-sdk.client
     github.copilot-sdk.factory
     github.copilot-sdk.helpers
     github.copilot-sdk.instrument
     github.copilot-sdk.logging
     github.copilot-sdk.session
     github.copilot-sdk.specs
     github.copilot-sdk.tool-set
     github.copilot-sdk.tools})

(defn instrumentation-probe [value]
  value)

(s/fdef instrumentation-probe
  :args (s/cat :value any?)
  :ret any?)

(defn- same-instrumentation-state? [left right]
  (#'surface/same-instrumentation-state? left right))

(deftest current-surface-covers-supported-namespaces
  (let [live (surface/current-surface)]
    (is (= 2 (:format-version live)))
    (is (= expected-public-namespaces
           (set (keys (:namespaces live)))))
    (testing "compatibility metadata"
      (is (= {:kind :macro
              :arglists '([[client-sym & [opts]] & body])}
             (get-in live [:namespaces
                           'github.copilot-sdk
                           :vars
                           'with-client])))
      (is (true? (get-in live [:namespaces
                               'github.copilot-sdk.client
                               :vars
                               'list-tools
                               :experimental])))
      (doseq [namespace ['github.copilot-sdk
                         'github.copilot-sdk.client]
              helper ['attributed-permission-result?
                      'attributed-permission-result]]
        (is (true? (get-in live [:namespaces namespace :vars helper :experimental]))
            (str namespace "/" helper " must remain experimental"))
        (is (contains? (get-in live [:namespaces namespace :fdefs])
                       (symbol (str namespace) (str helper)))
            (str namespace "/" helper " must remain instrumented"))))
    (testing "no-doc vars remain outside the supported contract"
      (is (not (contains? (get-in live [:namespaces
                                        'github.copilot-sdk.factory
                                        :vars])
                          'factory-run-function))))
    (testing "generated implementation interns remain outside the contract"
      (is (not (contains? (get-in live [:namespaces
                                        'github.copilot-sdk.factory
                                        :vars])
                          '->FactoryHandle)))
      (is (not (contains? (get-in live [:namespaces
                                        'github.copilot-sdk.factory
                                        :vars])
                          'map->FactoryHandle)))
      (is (not (contains? (get-in live [:namespaces
                                        'github.copilot-sdk.session
                                        :vars])
                          '->CopilotSession)))
      (is (not (contains? (get-in live [:namespaces
                                        'github.copilot-sdk.session
                                        :vars])
                          'map->CopilotSession)))
      (is (not-any? #(str/includes? (name %) ".proxy$")
                    (keys (get-in live [:namespaces
                                        'github.copilot-sdk.client
                                        :vars])))))
    (testing "normalized public fdef forms are grouped with their namespaces"
      (is (= '(clojure.spec.alpha/fspec
               :args
               (clojure.spec.alpha/cat
                :prompt
                clojure.core/string?
                :opts
                (clojure.spec.alpha/?
                 (clojure.spec.alpha/&
                  (clojure.spec.alpha/keys*)
                  :github.copilot-sdk.instrument/helper-query-options)))
               :ret
               (clojure.spec.alpha/nilable clojure.core/string?)
               :fn
               nil)
             (get-in live [:namespaces
                           'github.copilot-sdk.helpers
                           :fdefs
                           'github.copilot-sdk.helpers/query]))))
    (testing "idiom spec keys are membership-only"
      (is (vector? (get-in live [:namespaces
                                 'github.copilot-sdk.specs
                                 :spec-keys])))
      (is (contains? (set (get-in live [:namespaces
                                        'github.copilot-sdk.specs
                                        :spec-keys]))
                     :github.copilot-sdk.specs/client-options)))))

(deftest var-entry-normalizes-compatibility-markers-and-arglists
  (let [probe-ns-name 'github.copilot-sdk.api-surface-test.var-probe
        probe-ns (create-ns probe-ns-name)
        probe-var (intern probe-ns
                          (with-meta 'probe
                            {:arglists '([value__8374__auto__])
                             :macro true
                             :dynamic true
                             :deprecated "Use replacement"
                             :experimental :alpha})
                          identity)]
    (try
      (is (= {:kind :macro
              :arglists '([value__auto__])
              :dynamic true
              :deprecated true
              :experimental true}
             (#'surface/var-entry probe-var)))
      (finally
        (remove-ns probe-ns-name)))))

(deftest public-var-visibility-transitions-are-guarded
  (let [probe-ns-name 'github.copilot-sdk.api-surface-test.visibility-probe
        probe-ns (create-ns probe-ns-name)
        public-var (intern probe-ns 'visible identity)
        no-doc-var (intern probe-ns 'no-doc identity)
        private-var (intern probe-ns 'private identity)]
    (alter-meta! no-doc-var assoc :no-doc true)
    (alter-meta! private-var assoc :private true)
    (try
      (is (= #{'visible}
             (set (keys (surface/public-vars probe-ns-name)))))
      (alter-meta! no-doc-var dissoc :no-doc)
      (is (= #{'visible 'no-doc}
             (set (keys (surface/public-vars probe-ns-name)))))
      (alter-meta! private-var dissoc :private)
      (is (= #{'visible 'no-doc 'private}
             (set (keys (surface/public-vars probe-ns-name)))))
      (alter-meta! no-doc-var assoc :no-doc true)
      (alter-meta! private-var assoc :private true)
      (is (= #{'visible}
             (set (keys (surface/public-vars probe-ns-name)))))
      (finally
        (remove-ns probe-ns-name)
        (doseq [v [public-var no-doc-var private-var]]
          (alter-var-root v (constantly nil)))))))

(deftest curated-exclusions-fail-closed-when-stale
  (let [probe-ns-name 'github.copilot-sdk.api-surface-test.exclusion-probe
        probe-ns (create-ns probe-ns-name)
        present-var (intern probe-ns 'present identity)]
    (try
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Stale public var exclusion"
           (#'surface/validate-named-exclusions!
            probe-ns-name
            {'present present-var}
            {'missing "Compiler-generated test artifact."})))
      (alter-meta! present-var assoc :no-doc true)
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"redundant with \^:no-doc"
           (#'surface/validate-named-exclusions!
            probe-ns-name
            {'present present-var}
            {'present "Compiler-generated test artifact."})))
      (finally
        (remove-ns probe-ns-name)))))

(deftest curated-spec-exclusions-fail-closed-when-stale
  (testing "a missing spec key is stale"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Stale spec key exclusion"
         (#'surface/validate-named-spec-exclusions!
          #{:github.copilot-sdk.specs/permission-response-capability}
          {:github.copilot-sdk.specs/no-such-spec "Experimental placeholder."}))))
  (testing "a blank reason is rejected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Spec key exclusion requires a reason"
         (#'surface/validate-named-spec-exclusions!
          #{:github.copilot-sdk.specs/permission-response-capability}
          {:github.copilot-sdk.specs/permission-response-capability "   "}))))
  (testing "a present, reasoned exclusion passes and is returned"
    (let [exclusions {:github.copilot-sdk.specs/permission-response-capability
                      "Experimental placeholder."}]
      (is (= exclusions
             (#'surface/validate-named-spec-exclusions!
              #{:github.copilot-sdk.specs/permission-response-capability}
              exclusions)))))
  (testing "the excluded experimental key is absent from the live surface"
    (is (not (contains? (set (#'surface/spec-keys))
                        :github.copilot-sdk.specs/permission-response-capability)))))

(deftest controlled-fdef-loading-restores-instrumentation-state
  (let [before (#'surface/instrumentation-state)]
    (surface/current-surface)
    (is (same-instrumentation-state?
         before
         (#'surface/instrumentation-state))
        "loading fdefs does not eagerly instrument the shared JVM"))
  (let [probe-sym
        'github.copilot-sdk.api-surface-test/instrumentation-probe
        before (#'surface/instrumentation-state)]
    (#'surface/with-fdefs-loaded
     (fn []
       (is (= [probe-sym] (stest/instrument [probe-sym])))
       (is (contains? (:entries (#'surface/instrumentation-state))
                      #'instrumentation-probe))))
    (is (same-instrumentation-state?
         before
         (#'surface/instrumentation-state))
        "only instrumentation added inside the loader is removed"))
  (let [probe-sym
        'github.copilot-sdk.api-surface-test/instrumentation-probe
        before (#'surface/instrumentation-state)
        already-instrumented?
        (contains? (:entries before) #'instrumentation-probe)]
    (when-not already-instrumented?
      (stest/instrument [probe-sym]))
    (try
      (let [instrumented (#'surface/instrumentation-state)]
        (surface/current-surface)
        (is (same-instrumentation-state?
             instrumented
             (#'surface/instrumentation-state))
            "pre-existing instrumentation is preserved exactly"))
      (finally
        (when-not already-instrumented?
          (stest/unstrument [probe-sym]))))
    (is (same-instrumentation-state?
         before
         (#'surface/instrumentation-state)))))

(deftest snapshot-rejects-legacy-format
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Unsupported API-surface snapshot format nil"
       (#'surface/validate-snapshot!
        {:vars {} :spec-keys []}))))

(deftest unstable-edn-reports-snapshot-context
  (let [context {:namespace 'github.copilot-sdk.helpers
                 :var 'query}
        error (try
                (#'surface/assert-stable-edn! context #"not-edn")
                nil
                (catch clojure.lang.ExceptionInfo e
                  e))]
    (is (instance? clojure.lang.ExceptionInfo error))
    (is (= context (:context (ex-data error))))
    (is (= "#\"not-edn\"" (:printed (ex-data error))))
    (is (instance? RuntimeException (ex-cause error)))))

(deftest snapshot-contains-only-canonical-edn
  (let [snapshot-text (slurp (io/resource surface/snapshot-resource))]
    (is (not (re-find #"__\d+__auto__" snapshot-text)))
    (is (not (re-find #"__\d+#" snapshot-text)))
    (is (not (re-find #"#object\[" snapshot-text)))
    (is (not (re-find #"(?:^|[.$])proxy\$" snapshot-text)))
    (is (= (surface/read-snapshot)
           (surface/read-snapshot)))))

(defn- format-map-drift [label only-live only-snapshot]
  (let [added (vec (sort (keys (or only-live {}))))
        removed (vec (sort (keys (or only-snapshot {}))))
        changed (vec (sort (set/intersection (set added) (set removed))))
        added-only (vec (remove (set removed) added))
        removed-only (vec (remove (set added) removed))]
    (cond-> []
      (seq added-only) (conj (str "  added " label ": " added-only))
      (seq removed-only) (conj (str "  removed " label ": " removed-only))
      (seq changed) (conj (str "  changed " label ": " changed)))))

(deftest drift-diagnostics-report-fdef-form-changes
  (is (= ["  changed fdefs: [github.copilot-sdk.helpers/query]"]
         (format-map-drift
          "fdefs"
          {'github.copilot-sdk.helpers/query '(new form)}
          {'github.copilot-sdk.helpers/query '(old form)}))))

(defn- format-set-drift [label live snapshot]
  (let [added (vec (sort (set/difference live snapshot)))
        removed (vec (sort (set/difference snapshot live)))]
    (cond-> []
      (seq added) (conj (str "  added " label ": " added))
      (seq removed) (conj (str "  removed " label ": " removed)))))

(defn- regeneration-message [namespace lines]
  (str "\nPublic API surface drift detected in `" namespace "`.\n"
       "If this change is intentional, regenerate the snapshot with "
       "`bb api-surface:update` and commit the diff.\n"
       (str/join "\n" lines)
       "\n"))

(deftest api-surface-matches-snapshot
  (let [snapshot (surface/read-snapshot)]
    (is (some? snapshot)
        (str "Missing API-surface snapshot. Generate it with "
             "`bb api-surface:update`."))
    (when snapshot
      (let [live (surface/current-surface)
            live-namespaces (set (keys (:namespaces live)))
            snapshot-namespaces (set (keys (:namespaces snapshot)))]
        (testing "supported namespace membership"
          (is (= live-namespaces snapshot-namespaces)
              (regeneration-message
               "namespace registry"
               (format-set-drift "namespaces"
                                 live-namespaces
                                 snapshot-namespaces))))
        (doseq [namespace (sort (set/intersection live-namespaces
                                                  snapshot-namespaces))]
          (let [live-entry (get-in live [:namespaces namespace])
                snapshot-entry (get-in snapshot [:namespaces namespace])]
            (testing (str namespace " public vars")
              (let [[only-live only-snapshot _]
                    (data/diff (:vars live-entry)
                               (:vars snapshot-entry))]
                (is (and (nil? only-live) (nil? only-snapshot))
                    (regeneration-message
                     namespace
                     (format-map-drift
                      "vars (kind/arglists/metadata)"
                      only-live
                      only-snapshot)))))
            (testing (str namespace " public fdefs")
              (let [[only-live only-snapshot _]
                    (data/diff (:fdefs live-entry)
                               (:fdefs snapshot-entry))]
                (is (and (nil? only-live) (nil? only-snapshot))
                    (regeneration-message
                     namespace
                     (format-map-drift
                      "fdefs"
                      only-live
                      only-snapshot)))))
            (when (or (contains? live-entry :spec-keys)
                      (contains? snapshot-entry :spec-keys))
              (testing (str namespace " public spec keys")
                (let [live-specs (set (:spec-keys live-entry))
                      snapshot-specs (set (:spec-keys snapshot-entry))]
                  (is (= live-specs snapshot-specs)
                      (regeneration-message
                       namespace
                       (format-set-drift "spec keys"
                                         live-specs
                                         snapshot-specs))))))))))))
