(ns github.copilot-sdk.integration.stable-sync-cc0438d-test
  "Executable certification for the post-1.0.12-preview.0 upstream delta."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.math BigInteger)
           (java.nio.file Files Paths)
           (java.security MessageDigest)))

(def ^:private report-resource
  "resources/stable_upstream_delta_cc0438d.edn")

(def ^:private allowed-classifications
  #{:generated-only :internal :language-specific :test-harness})

(def ^:private upstream-validation-enabled?
  (= "true" (System/getenv "COPILOT_UPSTREAM_VALIDATION")))

(defn- read-report
  []
  (-> report-resource io/resource slurp edn/read-string))

(defn- resolve-upstream
  []
  (let [{:keys [exit out err]}
        (sh/sh "bash"
               ".github/skills/update-upstream/scripts/resolve-upstream.sh")]
    (when-not (zero? exit)
      (throw (ex-info "Could not resolve the upstream checkout"
                      {:exit exit :stderr err})))
    (str/trim out)))

(def ^:private upstream-repo
  (delay
    (when upstream-validation-enabled?
      (resolve-upstream))))

(defn- git-lines
  [upstream & args]
  (let [{:keys [exit out err]}
        (apply sh/sh (concat ["git" "-C" upstream] args))]
    (when-not (zero? exit)
      (throw (ex-info "Could not inspect the upstream checkout"
                      {:args args :exit exit :stderr err})))
    (->> (str/split-lines out)
         (remove str/blank?)
         vec)))

(defn- sha256-file
  [path]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes (Files/readAllBytes (Paths/get path (make-array String 0)))]
    (format "%064x" (BigInteger. 1 (.digest digest bytes)))))

(defn- evidence-references
  [report]
  (set (concat
        (mapcat :evidence (:ported-internal-deltas report))
        (mapcat :evidence (:intentional-exclusions report)))))

(deftest upstream-range-and-classification-are-exact
  (let [{:keys [base target exact-commits classified-files]} (read-report)
        expected-commits (mapv :sha exact-commits)
        expected-files (mapcat :paths classified-files)]
    (is (= "ea41dadb199725766d5097f4592c17be3200035f" base))
    (is (= "cc0438d66e3e68c333537cb935d9425d4e4ed8d5" target))
    (is (= ["21d738ea2cad2b49bb40b125046a29c0880f1504"
            "cc0438d66e3e68c333537cb935d9425d4e4ed8d5"]
           expected-commits))
    (is (= 31 (count expected-files)))
    (is (= (count expected-files) (count (distinct expected-files))))
    (is (every? allowed-classifications
                (map :classification classified-files)))
    (when-let [upstream @upstream-repo]
      (let [actual-commits (git-lines upstream "rev-list" "--reverse"
                                      (str base ".." target))
            actual-files (git-lines upstream "diff" "--name-only"
                                    (str base ".." target))]
        (is (= expected-commits actual-commits))
        (is (= (set expected-files) (set actual-files)))
        (is (= (count actual-files) (count (distinct actual-files))))))))

(deftest public-surface-and-evidence-are-complete
  (let [{:keys [target public-surface-audit ported-internal-deltas
                source-evidence] :as report}
        (read-report)
        evidence-ids (set (keys source-evidence))]
    (is (empty? (:stable-public-deltas public-surface-audit)))
    (is (empty? (:unclassified-deltas public-surface-audit)))
    (is (= #{:resume/mcp-config-in-resume-only}
           (set (map :id ported-internal-deltas))))
    (is (= evidence-ids (evidence-references report)))
    (when-let [upstream @upstream-repo]
      (doseq [[evidence-id {:keys [path symbol]}] source-evidence]
        (testing (name evidence-id)
          (let [{:keys [exit out]}
                (sh/sh "git" "-C" upstream "grep" "-F"
                       symbol target "--" path)]
            (is (zero? exit))
            (is (str/includes? out symbol))))))))

(deftest current-runtime-schema-is-exact
  (let [{:keys [schema version]} (read-report)
        api-schema (json/read-str (slurp "schemas/api.schema.json"))
        definitions (get api-schema "definitions")
        account-login (get definitions "AccountLoginRequest")
        permission-mode-source (get definitions "PermissionModeSource")
        approve-all-source (get definitions "PermissionsSetApproveAllSource")]
    (is (= (:runtime-pin schema)
           (str/trim (slurp ".copilot-schema-version"))))
    (is (= (get-in schema [:api :sha256])
           (sha256-file "schemas/api.schema.json")))
    (is (= (get-in schema [:session-events :sha256])
           (sha256-file "schemas/session-events.schema.json")))
    (is (= #{"host" "token"} (set (get account-login "required"))))
    (is (contains? (get account-login "properties") "login"))
    (is (= false (get account-login "additionalProperties")))
    (is (= "experimental" (get permission-mode-source "stability")))
    (is (contains? (set (get permission-mode-source "enum"))
                   "user_setting"))
    (is (contains? (set (get approve-all-source "enum"))
                   "user_setting"))
    (is (= {:sdk "1.0.11.0"
            :changed? false
            :release-required? false}
           version))))
