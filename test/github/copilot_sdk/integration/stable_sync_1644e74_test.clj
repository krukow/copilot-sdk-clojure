(ns github.copilot-sdk.integration.stable-sync-1644e74-test
  "Executable certification for the exact upstream f13e4a2..1644e74 delta."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.math BigInteger]
           [java.nio.file Files]
           [java.security MessageDigest]))

(def ^:private report-resource
  "resources/stable_upstream_delta_1644e74.edn")

(def ^:private historical-report-resource
  "resources/stable_upstream_delta_f13e4a2.edn")

(def ^:private expected-commit-classifications
  [["52b86a6969c9dc37cfa70ff6f64b64d4082b28d1" :language-specific]
   ["10197c8a37b01efd5b4cfbeeaa7a8e2160ceb906" :language-specific]
   ["d3755535869e97d2bcf5aa6a5b8c35de79f5a7d8" :language-specific]
   ["1644e74578db3637bc7527951bac227aabbc0584" :internal]])

(def ^:private expected-commits
  (mapv first expected-commit-classifications))

(def ^:private expected-path-classifications
  [{:path "CHANGELOG.md" :classification :internal}
   {:path "java/README.md" :classification :language-specific}
   {:path "java/copilot-native/pom.xml" :classification :language-specific}
   {:path "java/pom.xml" :classification :language-specific}
   {:path "java/sdk/jbang-example.java" :classification :language-specific}
   {:path "java/sdk/pom.xml" :classification :language-specific}])

(def ^:private no-port-classifications
  #{:internal :language-specific})

(def ^:private upstream-validation-enabled?
  (= "true" (System/getenv "COPILOT_UPSTREAM_VALIDATION")))

(defn- read-resource
  [resource]
  (some-> resource io/resource slurp edn/read-string))

(defn- sha256-bytes
  [bytes]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (format "%064x" (BigInteger. 1 (.digest digest bytes)))))

(defn- sha256-file
  [file]
  (sha256-bytes (Files/readAllBytes (.toPath file))))

(defn- sha256-lines
  [lines]
  (sha256-bytes (.getBytes (str (str/join "\n" lines) "\n") "UTF-8")))

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

(deftest parity-resource-is-complete-and-self-consistent
  (let [report (read-resource report-resource)
        historical-report (read-resource historical-report-resource)]
    (is (some? report) "The 1644e74 parity evidence must be committed")
    (is (some? historical-report) "The historical f13e4a2 oracle must remain available")
    (when (and report historical-report)
      (let [{:keys [upstream historical-oracle commit-classifications
                    changed-paths public-surface-audit release-metadata-audit
                    schema version]} report
            commits (mapv :commit commit-classifications)
            paths (mapv :path (:entries changed-paths))]
        (is (= "f13e4a2cc7e4e220974d2333142234e162a3252e"
               (:base-commit upstream)))
        (is (= "1644e74578db3637bc7527951bac227aabbc0584"
               (:target-commit upstream)))
        (is (= "v1.0.13" (:base-tag upstream)))
        (is (= :post-v1.0.13-main (:target-position upstream)))
        (is (= 4 (:commit-count upstream) (count commits)))
        (is (= expected-commit-classifications
               (mapv (juxt :commit :classification)
                     commit-classifications)))
        (is (= "a7f2fc54e504afa0d41017d130cd7316f1f7387cc4378c9a2e034b23c876512f"
               (:commit-sequence-sha256 upstream)
               (sha256-lines commits)))
        (is (every? #(and (contains? no-port-classifications
                                     (:classification %))
                          (= :reviewed-no-port (:status %))
                          (string? (:subject %))
                          (not (str/blank? (:subject %)))
                          (string? (:reason %))
                          (not (str/blank? (:reason %)))
                          (nat-int? (:changed-path-count %))
                          (re-matches #"[0-9a-f]{64}"
                                      (:changed-paths-sha256 %))
                          (str/starts-with?
                           (:source-url %)
                           "https://github.com/github/copilot-sdk/"))
                    commit-classifications))
        (is (= 6 (:count changed-paths) (count paths)))
        (is (= expected-path-classifications (:entries changed-paths)))
        (is (= paths (vec (sort paths))))
        (is (= 6 (count (set paths))))
        (is (= {:internal 1 :language-specific 5}
               (:classification-counts changed-paths)))
        (is (= "9db66bb4aede3de12049d7d9ae2513c36b42224c8661996ac4f523b34e329dbe"
               (:sha256 changed-paths)
               (sha256-lines paths)))
        (is (= #{} (:stable-delta-ids report)))
        (is (= [] (:stable-deltas report)))
        (is (= [] (:stable-public-deltas public-surface-audit)))
        (is (= [] (:unclassified-deltas public-surface-audit)))
        (is (= [] (:changed-node-files public-surface-audit)))
        (is (= "f506a0e89c7920149e520da2e8a43a41d7459d16"
               (:baseline-node-tree public-surface-audit)
               (:target-node-tree public-surface-audit)))
        (is (= :internal (:classification release-metadata-audit)))
        (is (= :reviewed-no-port (:status release-metadata-audit)))
        (is (= [] (:unclassified-items release-metadata-audit)))
        (is (= historical-report-resource (:resource historical-oracle)))
        (is (= "4974acfafe9a54e14fda470e005b12e64157903d5b76eb5ad273405932e6cc40"
               (:sha256 historical-oracle)
               (sha256-file (io/file "test/resources/stable_upstream_delta_f13e4a2.edn"))))
        (is (= (:base-commit upstream)
               (get-in historical-report [:upstream :target-commit])))
        (is (= {:runtime-pin "1.0.83"
                :changed? false
                :generated-clojure-output
                {:session-event-schema-changed? false
                 :event-specs-changed? false
                 :event-metadata-changed? false
                 :coercion-source-changed? false}}
               schema))
        (is (= "1.0.83" (str/trim (slurp ".copilot-schema-version"))))
        (is (str/includes? (slurp ".github/workflows/ci.yml")
                           (str "ref: " (:target-commit upstream))))
        (is (= {:sdk "1.0.13.0"
                :changed? false
                :release-required? false}
               version))
        (is (str/includes? (slurp "build.clj")
                           "(def version \"1.0.13.0\")"))))))

(deftest exact-upstream-range-and-public-surface-are-pinned
  (when-let [report (read-resource report-resource)]
    (when-let [upstream @upstream-repo]
      (let [{:keys [base-commit target-commit target-package-version
                    runtime-version]} (:upstream report)
            commit-classifications (:commit-classifications report)
            expected-paths (mapv :path (get-in report [:changed-paths :entries]))
            {:keys [inspected source-blobs baseline-node-tree target-node-tree]}
            (:public-surface-audit report)
            package-json
            (json/read-str
             (git-output upstream "show" (str target-commit ":nodejs/package.json")))]
        (is (= expected-commits
               (git-lines upstream "rev-list" "--reverse"
                          (str base-commit ".." target-commit))))
        (is (= expected-paths
               (vec
                (sort
                 (git-lines upstream "diff" "--name-only"
                            (str base-commit ".." target-commit))))))
        (is (= base-commit
               (str/trim (git-output upstream "rev-parse" "v1.0.13"))))
        (is (= target-package-version (get package-json "version")))
        (is (= runtime-version (get package-json "copilotCliVersion")))
        (doseq [{:keys [commit subject changed-path-count
                        changed-paths-sha256]} commit-classifications]
          (let [paths
                (sort
                 (git-lines upstream "diff-tree" "--no-commit-id"
                            "--name-only" "-r" commit))]
            (testing commit
              (is (= subject
                     (str/trim
                      (git-output upstream "show" "-s" "--format=%s" commit))))
              (is (= changed-path-count (count paths)))
              (is (= changed-paths-sha256 (sha256-lines paths))))))
        (is (= baseline-node-tree
               (str/trim
                (git-output upstream "rev-parse" (str base-commit ":nodejs")))))
        (is (= target-node-tree
               (str/trim
                (git-output upstream "rev-parse" (str target-commit ":nodejs")))))
        (is (git-success? upstream "diff" "--quiet"
                          base-commit target-commit "--" "nodejs"))
        (is (apply git-success? upstream "diff" "--quiet"
                   base-commit target-commit "--" inspected))
        (doseq [[path blob] source-blobs]
          (testing path
            (is (= blob
                   (str/trim
                    (git-output upstream "rev-parse"
                                (str base-commit ":" path)))))
            (is (= blob
                   (str/trim
                    (git-output upstream "rev-parse"
                                (str target-commit ":" path)))))))
        (doseq [{:keys [source-path required-symbols]}
                (get-in report [:release-metadata-audit
                                :classification-notes])]
          (let [source
                (git-output upstream "show" (str target-commit ":" source-path))]
            (testing source-path
              (doseq [symbol required-symbols]
                (is (str/includes? source symbol))))))))))
