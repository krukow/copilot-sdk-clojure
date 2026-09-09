(ns github.copilot-sdk.integration.stable-sync-d8bbc9d-test
  "Executable certification for the exact upstream 1644e74..d8bbc9d delta."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.math BigInteger]
           [java.nio.file Files]
           [java.security MessageDigest]))

(def ^:private report-resource
  "resources/stable_upstream_delta_d8bbc9d.edn")

(def ^:private historical-report-resource
  "resources/stable_upstream_delta_1644e74.edn")

(def ^:private expected-commit-classifications
  [["d5c9d06d8c4118530083848d9c3fa9d615c0a5c4" :language-specific]
   ["d8bbc9dd7a6167d4806780f405d8ce74add1cc7c" :stable-public]])

(def ^:private expected-path-classification-counts
  {:language-specific 11 :stable-public 33})

(def ^:private expected-stable-delta-ids
  #{:session/message-source})

(def ^:private required-exclusion-ids
  #{:events/completion-receipt
    :events/fusion-phase-routing
    :generated/send-request-source-values
    :sandbox/allow-bypass
    :session/live-auto-tier-switching})

(def ^:private allowed-classifications
  #{:experimental :generated-only :language-specific :stable-public})

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

(defn- declaration-symbols
  [source]
  (into #{}
        (map second)
        (re-seq
         #"(?m)^export\s+(?:(?:declare|abstract)\s+)*(?:type|interface|class|enum|const|(?:async\s+)?function)\s+([A-Za-z_$][A-Za-z0-9_$]*)"
         source)))

(defn- export-list-symbols
  [source]
  (into
   #{}
   (comp
    (map second)
    (map #(str/replace % #"(?s)/\*.*?\*/|//[^\n]*" ""))
    (mapcat #(str/split % #","))
    (map str/trim)
    (remove str/blank?)
    (map #(str/replace % #"^type\s+" ""))
    (map #(last (str/split % #"\s+as\s+")))
    (map str/trim))
   (re-seq #"(?s)export(?:\s+type)?\s*\{(.*?)\}\s*from" source)))

(defn- exported-symbols
  [source]
  (set/union (declaration-symbols source)
             (export-list-symbols source)))

(defn- class-source
  [source class-name]
  (let [pattern
        (re-pattern
         (str "(?ms)^export class "
              (java.util.regex.Pattern/quote class-name)
              "\\b.*?\\{(.*?)^\\}"))
        body (second (re-find pattern source))]
    (when-not body
      (throw (ex-info "Expected exported class was not found"
                      {:class-name class-name})))
    body))

(defn- public-class-methods
  [source class-name]
  (->> (re-seq
        #"(?m)^    ((?:(?:private|public|protected|async|static|override|readonly)\s+)*)(?:(?:get|set)\s+)?(\[Symbol\.[A-Za-z_$][A-Za-z0-9_$]*\]|[A-Za-z_$][A-Za-z0-9_$]*)\s*\("
        (class-source source class-name))
       (remove #(re-find #"\b(?:private|protected)\b" (second %)))
       (map #(nth % 2))
       set))

(deftest parity-resource-is-complete-and-self-consistent
  (let [report (read-resource report-resource)
        historical-report (read-resource historical-report-resource)]
    (is (some? report) "The d8bbc9d parity evidence must be committed")
    (is (some? historical-report)
        "The historical 1644e74 oracle must remain available")
    (when (and report historical-report)
      (let [{:keys [upstream historical-oracle commit-classifications
                    changed-paths public-surface-audit stable-delta-ids
                    stable-deltas intentional-exclusions source-evidence
                    schema version]} report
            commits (mapv :commit commit-classifications)
            path-entries (:entries changed-paths)
            paths (mapv :path path-entries)
            exclusions (set (map :id intentional-exclusions))]
        (is (= "1644e74578db3637bc7527951bac227aabbc0584"
               (:base-commit upstream)))
        (is (= "d8bbc9dd7a6167d4806780f405d8ce74add1cc7c"
               (:target-commit upstream)))
        (is (= "f13e4a2cc7e4e220974d2333142234e162a3252e"
               (:base-tag-commit upstream)))
        (is (= 2 (:commit-count upstream) (count commits)))
        (is (= expected-commit-classifications
               (mapv (juxt :commit :classification)
                     commit-classifications)))
        (is (= (:commit-sequence-sha256 upstream)
               (sha256-lines commits)))
        (is (every? #(and (contains? allowed-classifications
                                     (:classification %))
                          (keyword? (:status %))
                          (string? (:subject %))
                          (not (str/blank? (:subject %)))
                          (string? (:reason %))
                          (not (str/blank? (:reason %)))
                          (nat-int? (:changed-path-count %))
                          (re-matches #"[0-9a-f]{64}"
                                      (:changed-paths-sha256 %)))
                    commit-classifications))
        (is (= 44 (:count changed-paths) (count path-entries)))
        (is (= paths (vec (sort paths))))
        (is (= 44 (count (set paths))))
        (is (= expected-path-classification-counts
               (:classification-counts changed-paths)
               (frequencies (map :classification path-entries))))
        (is (= (:sha256 changed-paths)
               (sha256-lines paths)))
        (is (= expected-stable-delta-ids stable-delta-ids))
        (is (= expected-stable-delta-ids
               (set (map :id stable-deltas))))
        (is (= required-exclusion-ids exclusions))
        (is (empty? (:unclassified-deltas public-surface-audit)))
        (is (= #{:message-source-export
                 :message-source-type
                 :message-source-option
                 :message-source-send
                 :message-source-tests}
               (set (keys source-evidence))))
        (is (= historical-report-resource (:resource historical-oracle)))
        (is (= (:sha256 historical-oracle)
               (sha256-file
                (io/file "test/resources/stable_upstream_delta_1644e74.edn"))))
        (is (= (:base-commit upstream)
               (get-in historical-report [:upstream :target-commit])))
        (is (= {:runtime-pin "1.0.83"
                :changed? false
                :reason
                "The runtime SendRequest source field predates this stable SDK addition; generated protocol and event sources are byte-for-byte unchanged."}
               schema))
        (is (= "1.0.83" (str/trim (slurp ".copilot-schema-version"))))
        (is (str/includes? (slurp ".github/workflows/ci.yml")
                           (str "ref: " (:target-commit upstream))))
        (is (= {:sdk "1.0.13.0"
                :changed? false
                :release-required? false
                :next-release "1.0.13.1"}
               version))
        (is (str/includes? (slurp "build.clj")
                           "(def version \"1.0.13.0\")"))))))

(deftest exact-upstream-range-and-public-surface-are-pinned
  (when-let [report (read-resource report-resource)]
    (when-let [upstream @upstream-repo]
      (let [{:keys [base-commit base-tag base-tag-commit target-commit
                    target-package-version runtime-version]}
            (:upstream report)
            expected-commits (mapv :commit (:commit-classifications report))
            expected-paths (mapv :path (get-in report [:changed-paths :entries]))
            {:keys [source-blobs baseline-node-tree target-node-tree
                    baseline-generated-tree target-generated-tree
                    baseline-test-tree target-test-tree]}
            (:public-surface-audit report)
            target-surface (:target-public-surface report)
            package-json
            (json/read-str
             (git-output upstream "show"
                         (str target-commit ":nodejs/package.json")))
            read-source
            (fn [path]
              (git-output upstream "show" (str target-commit ":" path)))
            index-symbols
            (exported-symbols (read-source "nodejs/src/index.ts"))
            event-symbols
            (exported-symbols
             (read-source "nodejs/src/generated/session-events.ts"))
            type-symbols
            (exported-symbols (read-source "nodejs/src/types.ts"))
            client-methods
            (public-class-methods
             (read-source "nodejs/src/client.ts")
             "CopilotClient")
            session-methods
            (public-class-methods
             (read-source "nodejs/src/session.ts")
             "CopilotSession")]
        (is (= expected-commits
               (git-lines upstream "rev-list" "--reverse"
                          (str base-commit ".." target-commit))))
        (is (= expected-paths
               (vec
                (sort
                 (git-lines upstream "diff" "--name-only"
                            (str base-commit ".." target-commit))))))
        (is (= base-tag-commit
               (str/trim (git-output upstream "rev-parse" base-tag))))
        (is (= target-package-version (get package-json "version")))
        (is (= runtime-version (get package-json "copilotCliVersion")))
        (doseq [{:keys [commit subject changed-path-count
                        changed-paths-sha256]}
                (:commit-classifications report)]
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
                (git-output upstream "rev-parse"
                            (str base-commit ":nodejs")))))
        (is (= target-node-tree
               (str/trim
                (git-output upstream "rev-parse"
                            (str target-commit ":nodejs")))))
        (is (= baseline-generated-tree
               (str/trim
                (git-output upstream "rev-parse"
                            (str base-commit ":nodejs/src/generated")))))
        (is (= target-generated-tree
               (str/trim
                (git-output upstream "rev-parse"
                            (str target-commit ":nodejs/src/generated")))))
        (is (= baseline-test-tree
               (str/trim
                (git-output upstream "rev-parse"
                            (str base-commit ":nodejs/test")))))
        (is (= target-test-tree
               (str/trim
                (git-output upstream "rev-parse"
                            (str target-commit ":nodejs/test")))))
        (doseq [[path {:keys [base target]}] source-blobs]
          (testing path
            (is (= base
                   (str/trim
                    (git-output upstream "rev-parse"
                                (str base-commit ":" path)))))
            (is (= target
                   (str/trim
                    (git-output upstream "rev-parse"
                                (str target-commit ":" path)))))))
        (is (= (get-in target-surface [:package-root :explicit-symbol-count])
               (count index-symbols)))
        (is (= (get-in target-surface [:package-root :explicit-symbols-sha256])
               (sha256-lines (sort index-symbols))))
        (let [all-symbols (set/union index-symbols event-symbols)]
          (is (= (get-in target-surface [:package-root :symbol-count])
                 (count all-symbols)))
          (is (= (get-in target-surface [:package-root :symbols-sha256])
                 (sha256-lines (sort all-symbols)))))
        (is (= (get-in target-surface [:types :symbol-count])
               (count type-symbols)))
        (is (= (get-in target-surface [:types :symbols-sha256])
               (sha256-lines (sort type-symbols))))
        (is (= (get-in target-surface [:classes :client :method-count])
               (count client-methods)))
        (is (= (get-in target-surface [:classes :client :methods-sha256])
               (sha256-lines (sort client-methods))))
        (is (= (get-in target-surface [:classes :session :method-count])
               (count session-methods)))
        (is (= (get-in target-surface [:classes :session :methods-sha256])
               (sha256-lines (sort session-methods))))
        (doseq [[_ {:keys [path symbol symbols]}]
                (:source-evidence report)
                :let [source (read-source path)]]
          (testing path
            (if symbol
              (is (str/includes? source symbol))
              (doseq [expected symbols]
                (is (str/includes? source expected))))))))))
