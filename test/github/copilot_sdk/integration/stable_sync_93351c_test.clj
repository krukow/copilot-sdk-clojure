(ns github.copilot-sdk.integration.stable-sync-93351c-test
  "Executable certification for the post-cc0438d upstream delta."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private report-resource
  "resources/stable_upstream_delta_93351c.edn")

(def ^:private expected-commit-classifications
  [["38b28f9c26ed2a977b6fd5467e97dd81435e0a9d" :language-specific]
   ["8bc107f78e1d66bf4406cc9fa5c644f1cd00e842" :internal]
   ["b6c004684fea1e66e4a89a04fd54e785a3f91c83" :language-specific]
   ["7c63e58b50576cb8067bf76ed23384dc959771f4" :language-specific]
   ["ff2fc25f10819fe5771b866905bfbd9c2575bf56" :language-specific]
   ["87aee6336ee09f0cba797ba67a1c78f0d7518e71" :language-specific]
   ["6e6eb55f4d0f1ee222cfce3e64493781ef7c5be8" :language-specific]
   ["5de3a128cc149c0a9855308e89a57ae036746609" :internal]
   ["93351c9217a65960c14a863fc0fa540afd93fa15" :language-specific]])

(def ^:private expected-commits
  (mapv first expected-commit-classifications))

(def ^:private no-port-classifications
  #{:internal :language-specific})

(def ^:private upstream-validation-enabled?
  (= "true" (System/getenv "COPILOT_UPSTREAM_VALIDATION")))

(defn- read-report
  []
  (some-> report-resource io/resource slurp edn/read-string))

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

(defn- git-output
  [upstream & args]
  (let [{:keys [exit out err]}
        (apply sh/sh (concat ["git" "-C" upstream] args))]
    (when-not (zero? exit)
      (throw (ex-info "Could not inspect the upstream checkout"
                      {:args args :exit exit :stderr err})))
    out))

(defn- git-lines
  [upstream & args]
  (->> (str/split-lines (apply git-output upstream args))
       (remove str/blank?)
       vec))

(defn- git-success?
  [upstream & args]
  (zero? (:exit (apply sh/sh (concat ["git" "-C" upstream] args)))))

(deftest exact-upstream-range-is-fully-classified
  (let [report (read-report)]
    (is (some? report) "The post-cc0438d parity oracle must be committed")
    (when report
      (let [{:keys [upstream commit-classifications classified-files]} report
            base (:base-commit upstream)
            target (:target-commit upstream)
            classified-paths (mapcat :paths classified-files)
            path-classifications
            (into {}
                  (mapcat (fn [{:keys [classification paths]}]
                            (map #(vector % classification) paths)))
                  classified-files)]
        (is (= "cc0438d66e3e68c333537cb935d9425d4e4ed8d5" base))
        (is (= "93351c9217a65960c14a863fc0fa540afd93fa15" target))
        (is (= :post-v1.0.12-preview.0-main (:target-position upstream)))
        (is (= "0.0.0-dev" (:target-package-version upstream)))
        (is (= {:package "@github/copilot"
                :version "^1.0.81-6"}
               (:node-runtime-dependency upstream)))
        (is (= expected-commit-classifications
               (mapv (juxt :commit :classification) commit-classifications)))
        (is (every? #(contains? no-port-classifications (:classification %))
                    commit-classifications))
        (is (every? #(contains? no-port-classifications (:classification %))
                    classified-files))
        (is (every? #(and (= :reviewed-no-port (:status %))
                          (string? (:reason %))
                          (not (str/blank? (:reason %)))
                          (seq (:paths %)))
                    commit-classifications))
        (is (every? #(and (= :reviewed-no-port (:status %))
                          (seq (:paths %)))
                    classified-files))
        (is (= (count classified-paths) (count (distinct classified-paths))))
        (is (every? (fn [{:keys [classification paths]}]
                      (every? #(= classification
                                  (get path-classifications %))
                              paths))
                    commit-classifications))
        (is (every? #(str/starts-with? (:pull-request %)
                                       "https://github.com/github/copilot-sdk/pull/")
                    commit-classifications))
        (when-let [upstream-repo @upstream-repo]
          (let [package-json
                (json/read-str
                 (git-output upstream-repo "show"
                             (str target ":nodejs/package.json")))
                {:keys [package version]} (:node-runtime-dependency upstream)]
            (is (= expected-commits
                   (git-lines upstream-repo "rev-list" "--reverse"
                              (str base ".." target))))
            (is (= (set classified-paths)
                   (set (git-lines upstream-repo "diff" "--name-only"
                                   (str base ".." target)))))
            (is (= (:target-package-version upstream)
                   (get package-json "version")))
            (is (= version (get-in package-json ["dependencies" package])))
            (is (git-success? upstream-repo "merge-base" "--is-ancestor"
                              "v1.0.12-preview.0" target))
            (doseq [{:keys [commit paths]} commit-classifications]
              (testing commit
                (is (= (set paths)
                       (set (git-lines upstream-repo "diff-tree"
                                       "--no-commit-id" "--name-only" "-r"
                                       commit))))))))))))

(deftest stable-node-surface-and-runtime-were-unchanged
  (let [report (read-report)]
    (is (some? report) "The post-cc0438d parity oracle must be committed")
    (when report
      (let [{:keys [upstream public-surface-audit schema version]} report
            base (:base-commit upstream)
            target (:target-commit upstream)
            inspected (:inspected public-surface-audit)]
        (is (= #{} (:stable-delta-ids report)))
        (is (= [] (:stable-deltas report)))
        (is (= [] (:stable-public-deltas public-surface-audit)))
        (is (= [] (:unclassified-deltas public-surface-audit)))
        (is (= [] (:changed-node-files public-surface-audit)))
        (is (= "b8ba9a1ab52d10865c9ac21fdb242f9e5a02acc6"
               (:baseline-node-tree public-surface-audit)
               (:target-node-tree public-surface-audit)))
        (is (= {:runtime-pin "1.0.81-6"
                :changed? false
                :generated-clojure-output
                {:session-event-schema-changed? false
                 :event-specs-changed? false
                 :coercion-source-changed? false}}
               schema))
        (is (= {:sdk "1.0.11.0"
                :changed? false
                :release-required? false}
               version))
        (when-let [upstream-repo @upstream-repo]
          (is (empty? (git-lines upstream-repo "diff" "--name-only"
                                 (str base ".." target) "--" "nodejs")))
          (is (= (:baseline-node-tree public-surface-audit)
                 (first (git-lines upstream-repo "rev-parse"
                                   (str base ":nodejs")))))
          (is (= (:target-node-tree public-surface-audit)
                 (first (git-lines upstream-repo "rev-parse"
                                   (str target ":nodejs")))))
          (is (apply git-success? upstream-repo "diff" "--quiet"
                     base target "--" inspected)))))))
