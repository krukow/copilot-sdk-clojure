(ns github.copilot-sdk.integration.stable-sync-cc0438d-test
  "Executable certification for the post-1.0.12-preview.0 upstream delta."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.math BigInteger]
           [java.nio.file Files]
           [java.security MessageDigest]))

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

(defn- evidence-references
  [report]
  (set (concat
        (mapcat :evidence (:ported-internal-deltas report))
        (mapcat :evidence (:intentional-exclusions report)))))

(defn- sha256-file
  [file]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (Files/readAllBytes (.toPath file)))]
    (format "%064x" (BigInteger. 1 digest))))

(defn- run-command!
  [directory & command]
  (let [{:keys [exit out err]}
        (apply sh/sh (concat command [:dir directory]))]
    (when-not (zero? exit)
      (throw (ex-info "Historical schema artifact command failed"
                      {:command command
                       :exit exit
                       :stdout out
                       :stderr err})))
    (str/trim out)))

(defn- delete-tree!
  [root]
  (when (.exists root)
    (doseq [file (reverse (file-seq root))]
      (io/delete-file file))))

(defn- published-schema-hashes
  [{:keys [package version schemas]}]
  (let [temp-dir (.toFile
                  (Files/createTempDirectory
                   "copilot-sdk-historical-schema-"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        directory (.getCanonicalPath temp-dir)]
    (try
      (let [tarball-name
            (run-command! directory
                          "npm" "pack" "--silent"
                          (str package "@" version))
            tarball (.getCanonicalPath (io/file temp-dir tarball-name))]
        (run-command! directory "tar" "-xzf" tarball)
        (into {}
              (map (fn [entry]
                     [entry (sha256-file (io/file temp-dir entry))]))
              (keys schemas)))
      (finally
        (delete-tree! temp-dir)))))

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

(deftest historical-runtime-contract-remains-represented
  (let [{:keys [schema published-schema-artifact version]} (read-report)]
    (is (= "1.0.81-6" (:runtime-pin schema)))
    (is (= "@github/copilot/1.0.81-6" (get-in schema [:api :source])))
    (is (= "@github/copilot/1.0.81-6"
           (get-in schema [:session-events :source])))
    (is (= "603b014acb7a5c93a4b3c1580394f301eb60453e516eef15e47cbb6522bad558"
           (get-in schema [:api :sha256])))
    (is (= "9fd414f5020c317a234da6d7a06a4d0ef02ddad227ddc9962dced49302e5e8ec"
           (get-in schema [:session-events :sha256])))
    (when upstream-validation-enabled?
      (is (= (:schemas published-schema-artifact)
             (published-schema-hashes published-schema-artifact))
          "recorded historical hashes must match the published npm artifact"))
    (is (= {:sdk "1.0.11.0"
            :changed? false
            :release-required? false}
           version))))
