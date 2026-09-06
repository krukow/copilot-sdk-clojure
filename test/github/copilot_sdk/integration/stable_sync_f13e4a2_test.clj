(ns github.copilot-sdk.integration.stable-sync-f13e4a2-test
  "Executable certification for the exact upstream 2980c78..f13e4a2 delta."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.client :as client]
            [github.copilot-sdk.integration.support
             :refer [*mock-server* *test-client* with-mock-server]]
            [github.copilot-sdk.mock-server :as mock]
            [github.copilot-sdk.specs :as specs])
  (:import (java.math BigInteger)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files Paths)
           (java.security MessageDigest)))

(use-fixtures :each with-mock-server)

(def ^:private target-upstream-commit
  "f13e4a2cc7e4e220974d2333142234e162a3252e")

(def ^:private baseline-upstream-commit
  "2980c7828d35754bfc2b334831efec309ab8a2eb")

(def ^:private report-resource
  "resources/stable_upstream_delta_f13e4a2.edn")

(def ^:private historical-report-resource
  "resources/stable_upstream_delta_2980c78.edn")

(def ^:private allowed-classifications
  #{:experimental
    :generated-only
    :internal
    :language-specific
    :stable-public})

(def ^:private expected-stable-delta-ids
  #{:client/bracketed-ipv6
    :client/client-info
    :events/stable-1.0.83-additions
    :session/auto-tier-resident-resume
    :session/disconnect-detach
    :tools/external-abort-signal})

(def ^:private required-exclusion-ids
  #{:connect/client-task-kind
    :events/completion-receipt
    :events/fusion-phase-routing
    :generated/rpc-only-declarations
    :runtime/node-artifact-materialization
    :sandbox/allow-bypass
    :session/live-auto-tier-switching})

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
  [path]
  (sha256-bytes
   (Files/readAllBytes (Paths/get path (make-array String 0)))))

(defn- sha256-lines
  [lines]
  (sha256-bytes
   (.getBytes (str (str/join "\n" lines) "\n")
              StandardCharsets/UTF_8)))

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

(defn- note-upstream-validation-status!
  [test-name]
  (println
   (str "[stable-sync-f13e4a2] " test-name
        ": external upstream validation "
        (if upstream-validation-enabled?
          "ENABLED"
          "SKIPPED (set COPILOT_UPSTREAM_VALIDATION=true)"))))

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

(defn- interface-fields
  [source interface-name]
  (let [pattern
        (re-pattern
         (str "(?ms)^export interface "
              (java.util.regex.Pattern/quote interface-name)
              "\\b[^\\{]*\\{(.*?)^\\}"))
        body (second (re-find pattern source))]
    (when-not body
      (throw (ex-info "Expected upstream interface was not found"
                      {:interface interface-name})))
    (let [indent
          (second
           (re-find
            #"(?m)^([ \t]+)(?:readonly\s+)?[A-Za-z_$][A-Za-z0-9_$]*\??:"
            body))]
      (if-not indent
        #{}
        (into #{}
              (map second)
              (re-seq
               (re-pattern
                (str "(?m)^" (java.util.regex.Pattern/quote indent)
                     "(?:readonly\\s+)?([A-Za-z_$][A-Za-z0-9_$]*)\\??:"))
               body))))))

(defn- declaration-text
  [source symbol]
  (let [pattern
        (re-pattern
         (str "(?m)^export\\s+(?:type|interface)\\s+"
              (java.util.regex.Pattern/quote symbol)
              "\\b"))
        matcher (re-matcher pattern source)]
    (when (.find matcher)
      (let [start (.start matcher)
            interface? (str/starts-with? (subs source start) "export interface")]
        (if interface?
          (let [open (.indexOf source "{" start)]
            (loop [index (inc open)
                   depth 1]
              (let [character (.charAt source index)
                    next-depth (case character
                                 \{ (inc depth)
                                 \} (dec depth)
                                 depth)]
                (if (zero? next-depth)
                  (subs source start (inc index))
                  (recur (inc index) next-depth)))))
          (subs source start (inc (.indexOf source ";" start))))))))

(defn- referenced-evidence
  [report]
  (set
   (concat
    (mapcat :evidence (:stable-deltas report))
    (mapcat :evidence (:intentional-exclusions report)))))

(deftest parity-resource-is-complete-and-self-consistent
  (let [report (read-resource report-resource)
        historical-report (read-resource historical-report-resource)]
    (is (some? report) "The f13e4a2 parity evidence must be committed")
    (is (some? historical-report) "The historical 2980c78 oracle must remain available")
    (when (and report historical-report)
      (let [{:keys [upstream commit-classifications changed-paths
                    stable-delta-ids stable-deltas intentional-exclusions
                    local-test-evidence source-evidence
                    target-public-surface]} report
            commits (mapv :commit commit-classifications)
            path-entries (:entries changed-paths)
            paths (mapv :path path-entries)
            package-root (:package-root target-public-surface)
            baseline-package
            (get-in historical-report [:target-public-surface :package-root])]
        (is (= baseline-upstream-commit (:base-commit upstream)))
        (is (= target-upstream-commit (:target-commit upstream)))
        (is (= "v1.0.13" (:target-tag upstream)))
        (is (= 51 (:commit-count upstream) (count commits)))
        (is (= 51 (count (set commits))))
        (is (= "817b0afc254b1b7f6d1e609253b3a541df317290c51e926f80a1289dc65b98fe"
               (:commit-sequence-sha256 upstream)
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
        (is (= 847 (:count changed-paths) (count path-entries)))
        (is (= paths (vec (sort paths))))
        (is (= 847 (count (set paths))))
        (is (= "74fde2db91b4ec56697a8fc158b5e4d5aee800e164dc48eb2d16cf8833c58fc1"
               (:sha256 changed-paths)
               (sha256-lines paths)))
        (is (every? #(contains? allowed-classifications (:classification %))
                    path-entries))
        (is (= (:classification-counts changed-paths)
               (frequencies (map :classification path-entries))))
        (is (= expected-stable-delta-ids stable-delta-ids))
        (is (= expected-stable-delta-ids (set (map :id stable-deltas))))
        (is (every? #(and (= :stable-public (:classification %))
                          (= :ported (:status %))
                          (seq (:evidence %))
                          (seq (:inventory-items %))
                          (seq (:clojure-paths %))
                          (seq (:tests %))
                          (string? (:contract %))
                          (not (str/blank? (:contract %))))
                    stable-deltas))
        (is (set/subset? required-exclusion-ids
                         (set (map :id intentional-exclusions))))
        (is (every? #(and (contains? allowed-classifications
                                     (:classification %))
                          (= :exclude (:decision %))
                          (= :approved (:status %))
                          (seq (:evidence %))
                          (string? (:reason %))
                          (not (str/blank? (:reason %))))
                    intentional-exclusions))
        (is (= (set (keys source-evidence))
               (referenced-evidence report)))
        (is (= expected-stable-delta-ids
               (set (keys local-test-evidence))))
        (doseq [[delta-id {:keys [path symbols]}] local-test-evidence
                :let [source (slurp path)]
                symbol symbols]
          (testing (str (name delta-id) " test evidence " symbol)
            (is (str/includes? source symbol))))
        (doseq [{:keys [clojure-paths tests]} stable-deltas
                path (concat clojure-paths tests)]
          (is (.isFile (io/file path)) path))
        (is (= historical-report-resource
               (get-in report [:historical-oracle :resource])
               (get-in package-root [:provenance :baseline-resource])))
        (is (= baseline-upstream-commit
               (get-in historical-report [:upstream :target-commit])))
        (is (= 718 (:symbol-count package-root)))
        (is (= 694 (:symbol-count baseline-package)))
        (is (= (:symbol-count package-root)
               (+ (:symbol-count baseline-package)
                  (count (get-in package-root [:delta :stable-public]))
                  (count (get-in package-root [:delta :experimental])))))
        (is (= {:stable-public 563 :experimental 150 :internal 5}
               (:classification-counts package-root)))
        (is (= (:classification-counts package-root)
               (merge-with +
                           (:classification-counts baseline-package)
                           {:stable-public 8 :experimental 16})))))))

(deftest local-version-schema-and-generated-artifacts-are-exact
  (let [report (read-resource report-resource)
        artifacts (:local-artifacts report)
        source-proof (:schema-source-proof report)]
    (is (= "1.0.13.0" (:source-version artifacts)))
    (is (= "1.0.83" (:schema-version artifacts)))
    (is (= "1.0.83" (str/trim (slurp ".copilot-schema-version"))))
    (is (str/includes? (slurp "build.clj") "(def version \"1.0.13.0\")"))
    (doseq [[key path]
            [[:historical-oracle-sha256
              "test/resources/stable_upstream_delta_2980c78.edn"]
             [:api-schema-sha256 "schemas/api.schema.json"]
             [:session-events-schema-sha256
              "schemas/session-events.schema.json"]
             [:event-specs-sha256
              "src/github/copilot_sdk/generated/event_specs.clj"]
             [:event-metadata-sha256
              "src/github/copilot_sdk/generated/event_metadata.clj"]
             [:coerce-sha256
              "src/github/copilot_sdk/generated/coerce.clj"]]]
      (testing path
        (is (= (key artifacts) (sha256-file path)))))
    (is (= {:npm-package "@github/copilot-linux-x64"
            :npm-version "1.0.83"
            :official-release "github/copilot-cli v1.0.83"
            :release-asset "github-copilot-1.0.83-linux-x64.tgz"
            :release-archive-sha256
            "888f8fbb4575c335afba4a8863c647ef04f81e5124c7c794bdcaee90c5fa4503"
            :schema-manifest-file-count 2
            :schema-manifest-sha256
            "0fd2a836d536c0c8bb25362114e55f8e776fb42c4815ca7fe6071b651f86022a"
            :relationship :byte-identical}
           source-proof))))

(deftest exact-upstream-range-paths-and-source-evidence
  (note-upstream-validation-status! "exact-upstream-range-paths-and-source-evidence")
  (when-let [upstream @upstream-repo]
    (let [report (read-resource report-resource)
          {:keys [base-commit target-commit]} (:upstream report)
          commit-classifications (:commit-classifications report)
          expected-paths (mapv :path (get-in report [:changed-paths :entries]))
          actual-commits
          (git-lines upstream "rev-list" "--reverse"
                     (str base-commit ".." target-commit))
          actual-paths
          (vec
           (sort
            (git-lines upstream "diff" "--name-only"
                       (str base-commit ".." target-commit))))]
      (is (= (mapv :commit commit-classifications) actual-commits))
      (is (= expected-paths actual-paths))
      (is (= (get-in report [:changed-paths :sha256])
             (sha256-lines actual-paths)))
      (doseq [{:keys [commit subject changed-path-count
                      changed-paths-sha256]}
              commit-classifications]
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
      (doseq [[evidence-id {:keys [path symbol kind declaration]}]
              (:source-evidence report)]
        (let [base-source
              (git-output upstream "show" (str base-commit ":" path))
              target-source
              (git-output upstream "show" (str target-commit ":" path))]
          (testing (name evidence-id)
            (case kind
              :added
              (do
                (is (str/includes? target-source symbol))
                (is (not (str/includes? base-source symbol))))

              :target
              (is (str/includes? target-source symbol))

              :unchanged
              (do
                (is (str/includes? target-source symbol))
                (is (= base-source target-source)))

              :declaration-added-value
              (do
                (is (str/includes?
                     (declaration-text target-source declaration)
                     symbol))
                (is (not
                     (some-> (declaration-text base-source declaration)
                             (str/includes? symbol)))))

              (is false (str "Unknown evidence kind " kind)))))))))

(deftest exact-target-public-surface-is-pinned
  (note-upstream-validation-status! "exact-target-public-surface-is-pinned")
  (when-let [upstream @upstream-repo]
    (let [report (read-resource report-resource)
          {:keys [base-commit target-commit]} (:upstream report)
          {:keys [package-root types classes extension tool-set
                  generated-rpc-delta event-interface-fields
                  relevant-stable-tests upstream-test-evidence]}
          (:target-public-surface report)
          read-source
          #(git-output upstream "show" (str target-commit ":" %))
          added-symbols
          (fn [path]
            (set/difference
             (exported-symbols (read-source path))
             (exported-symbols
              (git-output upstream "show" (str base-commit ":" path)))))]
      (let [explicit-symbols (exported-symbols (read-source (:path package-root)))
            event-symbols
            (exported-symbols
             (read-source (get-in package-root [:star-exports 0 :path])))
            all-symbols (set/union explicit-symbols event-symbols)]
        (is (= (:explicit-symbol-count package-root)
               (count explicit-symbols)))
        (is (= (:explicit-symbols-sha256 package-root)
               (sha256-lines (sort explicit-symbols))))
        (is (= (get-in package-root [:star-exports 0 :symbol-count])
               (count event-symbols)))
        (is (= (get-in package-root [:star-exports 0 :symbols-sha256])
               (sha256-lines (sort event-symbols))))
        (is (= (:symbol-count package-root) (count all-symbols)))
        (is (= (:symbols-sha256 package-root)
               (sha256-lines (sort all-symbols))))
        (is (= (set/union
                (get-in types [:delta :stable-public])
                (get-in types [:delta :experimental]))
               (added-symbols (:path package-root))))
        (is (= (set/difference
                (set/union
                 (get-in package-root [:delta :stable-public])
                 (get-in package-root [:delta :experimental]))
                (set/union
                 (get-in types [:delta :stable-public])
                 (get-in types [:delta :experimental])))
               (added-symbols
                (get-in package-root [:star-exports 0 :path])))))
      (let [type-symbols (exported-symbols (read-source (:path types)))]
        (is (= (:symbol-count types) (count type-symbols)))
        (is (= (:symbols-sha256 types)
               (sha256-lines (sort type-symbols)))))
      (doseq [[_ {:keys [path class-name methods method-count
                         methods-sha256]}]
              classes]
        (let [actual (public-class-methods (read-source path) class-name)
              classified (apply set/union #{} (vals methods))]
          (is (= method-count (count actual)))
          (is (= methods-sha256 (sha256-lines (sort actual))))
          (is (= classified actual))))
      (doseq [surface [extension tool-set]]
        (let [{:keys [path base-blob target-blob symbol-count
                      symbols-sha256]} surface
              symbols (exported-symbols (read-source path))]
          (is (= base-blob target-blob))
          (is (= base-blob
                 (str/trim
                  (git-output upstream "rev-parse"
                              (str base-commit ":" path)))))
          (is (= target-blob
                 (str/trim
                  (git-output upstream "rev-parse"
                              (str target-commit ":" path)))))
          (is (= symbol-count (count symbols)))
          (is (= symbols-sha256 (sha256-lines (sort symbols))))))
      (is (= (set/union (:experimental generated-rpc-delta)
                        (:internal generated-rpc-delta))
             (added-symbols (:path generated-rpc-delta))))
      (doseq [[interface-name classifications] event-interface-fields]
        (let [actual
              (set/difference
               (interface-fields
                (read-source "nodejs/src/generated/session-events.ts")
                interface-name)
               (interface-fields
                (git-output
                 upstream "show"
                 (str base-commit
                      ":nodejs/src/generated/session-events.ts"))
                interface-name))]
          (is (= (apply set/union #{} (vals classifications))
                 actual)
              interface-name)))
      (doseq [path relevant-stable-tests]
        (is (zero?
             (:exit
              (sh/sh "git" "-C" upstream "cat-file" "-e"
                     (str target-commit ":" path))))
            path))
      (doseq [[path symbols] upstream-test-evidence
              :let [source (read-source path)]
              symbol symbols]
        (testing (str path " contains " symbol)
          (is (str/includes? source symbol)))))))

(deftest stable-target-version-pins
  (testing "the source and runtime versions identify the stable v1.0.13 target"
    (is (= "1.0.83"
           (str/trim (slurp (io/file ".copilot-schema-version")))))
    (is (str/includes? (slurp (io/file "build.clj"))
                       "(def version \"1.0.13.0\")")))
  (testing "the post-baseline parity oracle pins the exact stable commit"
    (let [oracle (io/file "test/resources/stable_upstream_delta_f13e4a2.edn")]
      (is (.isFile oracle))
      (when (.isFile oracle)
        (is (str/includes? (slurp oracle) target-upstream-commit))))))

(deftest auto-tier-wire-contract-covers-create-cold-resume-resident-resume-and-join
  (let [requests (atom [])
        session-id "auto-tier-session"
        _ (mock/set-request-hook!
           *mock-server*
           (fn [method params]
             (when (#{"session.create" "session.resume"} method)
               (swap! requests conj [method params]))))
        created
        (sdk/create-session
         *test-client*
         {:session-id session-id
          :on-permission-request sdk/approve-all
          :capi {:auto-tier :balance}})
        _ (sdk/resume-session
           *test-client*
           (sdk/session-id created)
           {:on-permission-request sdk/approve-all})
        _ (sdk/resume-session
           *test-client*
           (sdk/session-id created)
           {:on-permission-request sdk/approve-all
            :capi {:auto-tier :intelligence}})
        _ (with-redefs-fn
            {#'client/foreground-session-id (constantly session-id)
             #'client/client (constantly *test-client*)}
            #(client/join-session {:capi {:auto-tier :efficiency}}))
        [[create-method create-params]
         [cold-method cold-params]
         [resident-method resident-params]
         [join-method join-params]] @requests]
    (is (= ["session.create"
            "session.resume"
            "session.resume"
            "session.resume"]
           [create-method cold-method resident-method join-method]))
    (is (= {:autoTier "balance"} (:capi create-params)))
    (is (not (contains? cold-params :capi)))
    (is (= {:autoTier "intelligence"} (:capi resident-params)))
    (is (= {:autoTier "efficiency"} (:capi join-params)))))

(deftest remediation-and-message-identity-event-contracts
  (testing "recoverable errors and warnings accept the stable remediation actions"
    (doseq [remediation ["sign_in"
                         "switch_account"
                         "show_account"
                         "review_sandbox_policy"
                         "allow_sandbox_outbound"]]
      (is (s/valid? ::specs/session.error-data
                    {:error-type "authentication"
                     :message "action required"
                     :remediation remediation}))
      (is (s/valid? ::specs/session.warning-data
                    {:warning-type "policy"
                     :message "action required"
                     :remediation remediation}))
      (is (s/valid? ::specs/tool.execution_complete-data
                    {:tool-call-id "call-1"
                     :tool-name "bash"
                     :success false
                     :error {:message "blocked"
                             :remediation remediation}})))
    (is (not (s/valid? ::specs/session.error-data
                       {:error-type "authentication"
                        :message "action required"
                        :remediation "retry"}))))
  (testing "user.message carries an optional stable logical message identity"
    (is (s/valid? ::specs/user.message-data
                  {:content "hello" :message-id "message-1"}))
    (is (s/valid? ::specs/user.message-data {:content "hello"}))
    (is (not (s/valid? ::specs/user.message-data
                       {:content "hello" :message-id nil})))))

(deftest skill-agent-and-permission-event-contracts
  (testing "skill invocation preserves open source identifiers and model-invocation policy"
    (doseq [source ["sdk" "remote" "future-source"]]
      (is (s/valid? ::specs/skill.invoked-data
                    {:name "sdk-skill"
                     :path ""
                     :content "Use this skill"
                     :source source
                     :disable-model-invocation false})))
    (is (not (s/valid? ::specs/skill.invoked-data
                       {:name "sdk-skill"
                        :path ""
                        :content "Use this skill"
                        :source :sdk
                        :disable-model-invocation false})))
    (is (not (s/valid? ::specs/skill.invoked-data
                       {:name "sdk-skill"
                        :path ""
                        :content "Use this skill"
                        :source "sdk"
                        :disable-model-invocation "false"}))))
  (testing "loaded skill metadata uses the separate closed SkillSource union"
    (doseq [source ["project"
                    "inherited"
                    "personal-copilot"
                    "personal-agents"
                    "plugin"
                    "custom"
                    "builtin"
                    "sdk"]]
      (is (s/valid? ::specs/session.skills_loaded-data
                    {:skills [{:name "skill"
                               :description "Skill"
                               :enabled true
                               :source source
                               :user-invocable true}]})))
    (doseq [source ["remote" "future-source"]]
      (is (not
           (s/valid? ::specs/session.skills_loaded-data
                     {:skills [{:name "skill"
                                :description "Skill"
                                :enabled true
                                :source source
                                :user-invocable true}]})))))
  (testing "custom-agent model preferences and subagent override reasons are visible"
    (is (s/valid? ::specs/custom-agent-info
                  {:id "reviewer"
                   :name "reviewer"
                   :display-name "Reviewer"
                   :description "Reviews changes"
                   :source "project"
                   :tools nil
                   :user-invocable? true
                   :models ["gpt-5.4" "claude-sonnet-5"]
                   :model-policy "preferred"}))
    (is (s/valid? ::specs/subagent.completed-data
                  {:agent-name "reviewer"
                   :agent-display-name "Reviewer"
                   :tool-call-id "call-1"
                   :model-override-reason "configured model unavailable"}))
    (is (s/valid? ::specs/subagent.failed-data
                  {:agent-name "reviewer"
                   :agent-display-name "Reviewer"
                   :tool-call-id "call-1"
                   :error "failed"
                   :model-override-reason "configured model unavailable"})))
  (testing "permission requests expose the active stable session mode"
    (doseq [mode ["interactive" "plan" "autopilot"]]
      (is (s/valid? ::specs/permission.requested-data
                    {:request-id "permission-1"
                     :permission-request {:permission-kind "shell"}
                     :agent-mode mode})))
    (is (not (s/valid? ::specs/permission.requested-data
                       {:request-id "permission-1"
                        :permission-request {:permission-kind "shell"}
                        :agent-mode "shell"})))))

(deftest stable-mcp-lifecycle-event-contracts
  (testing "loaded server metadata distinguishes omission from explicit null instructions"
    (doseq [server [{:name "github" :status "connected"}
                    {:name "github"
                     :status "connected"
                     :server-metadata {:instructions nil}}
                    {:name "github"
                     :status "connected"
                     :server-metadata {:instructions "Prefer repository-scoped tools."}}]]
      (is (s/valid? ::specs/mcp-loaded-server server))))
  (testing "stable MCP removal and reconnect events are curated publicly"
    (doseq [event-type [:copilot/session.mcp_server_removed
                        :copilot/session.mcp_server_needs_reconnect]]
      (is (contains? sdk/event-types event-type))
      (is (contains? sdk/session-events event-type)))
    (is (s/valid? ::specs/session.mcp_server_removed-data
                  {:server-name "github"}))
    (is (s/valid? ::specs/session.mcp_server_needs_reconnect-data
                  {:server-name "github"}))))
