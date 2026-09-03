(ns github.copilot-sdk.integration.stable-sync-2980c78-test
  "Executable certification for the upstream delta through 2980c78."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk.generated.event-specs :as generated-events]
            [github.copilot-sdk.session :as session]
            [github.copilot-sdk.specs :as specs])
  (:import (java.math BigInteger)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files Paths)
           (java.security MessageDigest)))

(def ^:private report-resource
  "resources/stable_upstream_delta_2980c78.edn")

(def ^:private expected-commits
  ["48b280ac0049eed2c00e3f0040783636c7aa82a9"
   "0afa524ce35b27236aac7d428ed9ca9c9d998e3c"
   "3c3ec41c670f0d64ae6f044719566e7ecef911b8"
   "ced555f077335717743eb50279949af3301fa99c"
   "ed1c2eaa134ed60faa99acb1870f0477c6943031"
   "017c9a3ba1c097dad39200a8409da7ca59b53293"
   "7016935558b01b44d716013212d00f8d50b7a63c"
   "de17ac8ba101899eac555b4f70e4fb88eb00aeab"
   "f0a575aaad366e93e93349544d91035d0fb2e511"
   "83c179a3a82096dc8e0f524c03dfa95140f497c8"
   "69c503a80d24020e79bd4fb2f5bb179da92e0075"
   "f0e388bf844fb377daae34e6841a5096020427a4"
   "29141a4cc779191f9b292a280daaddd3597cacac"
   "3d630a790e3b1f8c74b4443d144a52429a232b28"
   "521aa26b29e35a25cd483c589b35ee4f0b7b8750"
   "a671d9490b28b8e55cff19ab66376a9409588025"
   "3ce052769fb00c24587c639b892d9d31dbf995fc"
   "8715c1372abcf0135415710b6b04f3743f372a70"
   "c2f08ff199d6a04908158a66803a6854b8de224d"
   "a94a1f2ddd7e015b3d3f7bbc94c7c2b1a28219e0"
   "50ce37e19258524c6de82651652971e96d7ae5f3"
   "53efb3593e65e5f04474099ffd3e00efa7777482"
   "b79cef213bfc29d4573e21ec7037fa44c0f1d5d0"
   "01b27f16c52db654918d6d831c9ae5d24b47bf14"
   "7a916f8af8fb11315e9d043156487f9076264145"
   "128927eb6590394a1d8de5a646da07873e1523ce"
   "07ae7a18c078514c32d22a9cc37a82b4d083c335"
   "6adcf00d433614d550563835b688012d5b9c5782"
   "538b2dce30755ca53f60b5ad42d11a98addf8edb"
   "5a2a857bd711527cc7d7f0ef4bc545cc99e88d7f"
   "fc78bf6cbb114814141d936099190e320b55a89c"
   "5b3a03e2d5615076598b9421cd2bced93f4612e8"
   "759156077163205bbb7235c3467856ed69de9d10"
   "80d09ff1660cd7be1be65a0045b82d71cd8841e7"
   "913cb61302d6e27789c51dcacccfb193569eea73"
   "6b0252cf0e8243d5c972d4dea00c1d8862b57d9f"
   "76f6b6daf7816441d44312f128755d327fbeff5e"
   "edf11030e5893885508504cfcbae8310b0d528f1"
   "2980c7828d35754bfc2b334831efec309ab8a2eb"])

(def ^:private expected-stable-delta-ids
  #{:auth/github-token-provider
    :events/assistant-message-tool-request-caller
    :events/mode-notice-delivered
    :events/model-call-finished
    :events/stable-payload-fields
    :events/subagent-configured
    :mode/builtin-skill-isolation
    :permissions/managed-bypass-policy
    :session/ask-user-variant
    :session/auto-tier
    :session/autopilot-idle
    :session/feature-flags})

;; Ground truth for fields added to existing public event-payload interfaces
;; in `nodejs/src/generated/session-events.ts` between the base and target
;; commits, verified directly against the pinned upstream git history.
;; `:stable-public` fields are the ones curated into `specs.clj`;
;; `:experimental` fields (e.g. the fusion rollout) are intentionally
;; excluded from the curated idiom specs but must still be accounted for so
;; the oracle cannot silently drift out of sync with the real upstream diff.
(def ^:private expected-event-interface-fields
  {"AssistantMessageData"
   {:experimental #{"fusion" "reasoningBlocks"}}
   "AssistantUsageData"
   {:stable-public #{"outputTtftMs"} :experimental #{"fusion"}}
   "CompactionCompleteData"
   {:stable-public #{"behaviorModelId"}}
   "HookEndData"
   {:stable-public #{"parentToolCallId"}}
   "HookStartData"
   {:stable-public #{"parentToolCallId"}}
   "ModelCallFailureData"
   {:experimental #{"fusion"}}
   "PermissionPromptRequestMcp"
   {:stable-public #{"canOfferServerWideApproval"}}
   "SubagentStartedData"
   {:stable-public #{"agentType" "executionMode" "parentId" "resumable"}}
   "SubagentCompletedData"
   {:stable-public #{"configuredModelMatchesActual" "configuredModelPreference"
                     "explicitModelMatchesPreference" "explicitModelOverride"
                     "firstDispatchedModel"}}
   "SubagentFailedData"
   {:stable-public #{"configuredModelMatchesActual" "configuredModelPreference"
                     "explicitModelMatchesPreference" "explicitModelOverride"
                     "firstDispatchedModel"}}
   "ToolExecutionCompleteContentShellExit"
   {:stable-public #{"outputFilePath"}}
   "ToolExecutionCompleteData"
   {:experimental #{"fusion"}}
   "ToolExecutionStartData"
   {:experimental #{"fusion"}}})

(def ^:private allowed-classifications
  #{:experimental :generated-only :internal :language-specific :stable-public})

(def ^:private upstream-validation-enabled?
  (= "true" (System/getenv "COPILOT_UPSTREAM_VALIDATION")))

(defn- note-upstream-validation-status!
  "Emit a visible pass/skip signal for external upstream-diff checks gated
   behind COPILOT_UPSTREAM_VALIDATION."
  [test-name]
  (println
   (str "[stable-sync-2980c78] " test-name
        ": external upstream-diff validation "
        (if upstream-validation-enabled?
          "ENABLED (comparing the committed report against a resolved local upstream checkout)"
          "SKIPPED (set COPILOT_UPSTREAM_VALIDATION=true with a local upstream checkout to run the exact-pin git-diff assertions)"))))

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

(defn- path-rule-matches?
  [path {:keys [paths prefixes]}]
  (or (contains? (set paths) path)
      (some #(str/starts-with? path %) prefixes)))

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

(defn- star-export-modules
  [source]
  (into #{}
        (map second)
        (re-seq
         #"(?m)^export\s+(?:type\s+)?\*\s+from\s+\"([^\"]+)\";"
         source)))

(defn- added-exported-symbols
  [upstream base target path]
  (set/difference
   (exported-symbols (git-output upstream "show" (str target ":" path)))
   (exported-symbols (git-output upstream "show" (str base ":" path)))))

(defn- interface-fields
  "Return the set of top-level field names declared directly in the named
   TypeScript interface. The interface's own field indentation is detected
   from its first declared field rather than assumed, so this works across
   generation conventions that differ in indent width (e.g. the hand-written
   4-space `nodejs/src/types.ts` vs. the generated 2-space
   `nodejs/src/generated/session-events.ts`), while still ignoring anything
   nested deeper than that top-level indent."
  [source interface-name]
  (let [pattern
        (re-pattern
         (str "(?ms)^export interface "
              (java.util.regex.Pattern/quote interface-name)
              "\\b[^\\{]*\\{(.*?)^\\}"))
        body (second (re-find pattern source))]
    (when-not body
      (throw (ex-info "Upstream interface not found"
                      {:interface interface-name})))
    (let [indent (second
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

(defn- added-interface-fields
  [upstream base target path interface-name]
  (set/difference
   (interface-fields
    (git-output upstream "show" (str target ":" path))
    interface-name)
   (interface-fields
    (git-output upstream "show" (str base ":" path))
    interface-name)))

(def ^:private exported-declaration-pattern
  #"(?m)^\s*export\s+(type|interface)\s+([A-Za-z_$][A-Za-z0-9_$]*)\b")

(defn- strip-typescript-comments
  [source]
  (-> source
      (str/replace #"(?s)/\*.*?\*/" " ")
      (str/replace #"(?m)//[^\r\n]*" " ")))

(defn- declaration-end
  [source start kind]
  (loop [index start
         quote-char nil
         escaped? false
         paren-depth 0
         bracket-depth 0
         brace-depth 0
         angle-depth 0
         interface-body? false]
    (when (>= index (count source))
      (throw (ex-info "Unterminated exported TypeScript declaration"
                      {:kind kind :start start})))
    (let [ch (.charAt source index)]
      (cond
        quote-char
        (cond
          escaped?
          (recur (inc index) quote-char false
                 paren-depth bracket-depth brace-depth angle-depth interface-body?)

          (= ch \\)
          (recur (inc index) quote-char true
                 paren-depth bracket-depth brace-depth angle-depth interface-body?)

          (= ch quote-char)
          (recur (inc index) nil false
                 paren-depth bracket-depth brace-depth angle-depth interface-body?)

          :else
          (recur (inc index) quote-char false
                 paren-depth bracket-depth brace-depth angle-depth interface-body?))

        (#{\" \' \`} ch)
        (recur (inc index) ch false
               paren-depth bracket-depth brace-depth angle-depth interface-body?)

        (= kind "interface")
        (cond
          (= ch \{)
          (recur (inc index) nil false
                 paren-depth bracket-depth (inc brace-depth) angle-depth true)

          (and interface-body? (= ch \}) (= brace-depth 1))
          (inc index)

          (= ch \})
          (recur (inc index) nil false
                 paren-depth bracket-depth (dec brace-depth) angle-depth interface-body?)

          :else
          (recur (inc index) nil false
                 paren-depth bracket-depth brace-depth angle-depth interface-body?))

        :else
        (case ch
          \( (recur (inc index) nil false
                    (inc paren-depth) bracket-depth brace-depth angle-depth interface-body?)
          \) (recur (inc index) nil false
                    (dec paren-depth) bracket-depth brace-depth angle-depth interface-body?)
          \[ (recur (inc index) nil false
                    paren-depth (inc bracket-depth) brace-depth angle-depth interface-body?)
          \] (recur (inc index) nil false
                    paren-depth (dec bracket-depth) brace-depth angle-depth interface-body?)
          \{ (recur (inc index) nil false
                    paren-depth bracket-depth (inc brace-depth) angle-depth interface-body?)
          \} (recur (inc index) nil false
                    paren-depth bracket-depth (dec brace-depth) angle-depth interface-body?)
          \< (recur (inc index) nil false
                    paren-depth bracket-depth brace-depth (inc angle-depth) interface-body?)
          \> (recur (inc index) nil false
                    paren-depth bracket-depth brace-depth (max 0 (dec angle-depth)) interface-body?)
          \; (if (every? zero? [paren-depth bracket-depth brace-depth angle-depth])
               (inc index)
               (recur (inc index) nil false
                      paren-depth bracket-depth brace-depth angle-depth interface-body?))
          (recur (inc index) nil false
                 paren-depth bracket-depth brace-depth angle-depth interface-body?))))))

(defn- exported-declarations
  [source]
  (let [source (strip-typescript-comments source)
        matcher (re-matcher exported-declaration-pattern source)]
    (loop [declarations {}]
      (if (.find matcher)
        (let [kind (.group matcher 1)
              declaration-name (.group matcher 2)
              start (.start matcher)
              end (declaration-end source (.end matcher) kind)
              normalized (-> (subs source start end)
                             (str/replace #"\s+" " ")
                             str/trim)]
          (recur (assoc declarations declaration-name normalized)))
        declarations))))

(defn- changed-exported-declarations
  [upstream base target path]
  (let [base-declarations
        (exported-declarations
         (git-output upstream "show" (str base ":" path)))
        target-declarations
        (exported-declarations
         (git-output upstream "show" (str target ":" path)))]
    (->> (set/intersection (set (keys base-declarations))
                           (set (keys target-declarations)))
         (filter #(not= (get base-declarations %)
                        (get target-declarations %)))
         set)))

(defn- class-method-symbols
  [source]
  (into #{}
        (map second)
        (re-seq
         #"(?m)^    (?:(?:private|public|protected|async|static)\s+)*([A-Za-z_$][A-Za-z0-9_$]*)\s*\("
         source)))

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

(defn- public-class-properties
  [source class-name]
  (let [body (class-source source class-name)
        fields
        (->> (re-seq
              #"(?m)^    ((?:(?:private|public|protected|static|override|readonly)\s+)*)([A-Za-z_$][A-Za-z0-9_$]*)\s*(?::|=)"
              body)
             (remove #(re-find #"\b(?:private|protected)\b" (second %)))
             (map #(nth % 2))
             set)
        parameter-properties
        (->> (re-seq
              #"(?m)^        ((?:(?:private|public|protected|readonly)\s+)+)([A-Za-z_$][A-Za-z0-9_$]*)\s*:"
              body)
             (remove #(re-find #"\b(?:private|protected)\b" (second %)))
             (map #(nth % 2))
             set)]
    (set/union fields parameter-properties)))

(defn- classified-symbols
  [classification]
  (apply set/union #{} (vals classification)))

(defn- classification-duplicate-count
  [classification]
  (let [symbols (mapcat identity (vals classification))]
    (- (count symbols) (count (set symbols)))))

(defn- complete-classification?
  [classification symbols]
  (and (map? classification)
       (seq classification)
       (every? allowed-classifications (keys classification))
       (every? set? (vals classification))
       (zero? (classification-duplicate-count classification))
       (= symbols (classified-symbols classification))))

(defn- resolve-classification-policy
  [{:keys [default overrides]} symbols]
  (when (and (contains? allowed-classifications default)
             (map? overrides)
             (every? allowed-classifications (keys overrides))
             (not (contains? overrides default))
             (every? set? (vals overrides))
             (zero? (classification-duplicate-count overrides)))
    (assoc overrides
           default
           (set/difference symbols (classified-symbols overrides)))))

(defn- added-class-method-symbols
  [upstream base target path]
  (set/difference
   (class-method-symbols
    (git-output upstream "show" (str target ":" path)))
   (class-method-symbols
    (git-output upstream "show" (str base ":" path)))))

(defn- delete-tree!
  [file]
  (when (.exists file)
    (doseq [child (reverse (file-seq file))]
      (io/delete-file child true))))

(defn- published-schema-hashes
  [{:keys [package version schemas]}]
  (let [temp-root
        (.toFile
         (Files/createTempDirectory
          "copilot-schema-artifact"
          (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (let [{:keys [exit out err]}
            (sh/sh "npm" "pack"
                   "--ignore-scripts"
                   "--silent"
                   "--registry=https://registry.npmjs.org"
                   (str package "@" version)
                   :dir (.getPath temp-root))]
        (when-not (zero? exit)
          (throw (ex-info "Unable to download published Copilot schema artifact"
                          {:package package
                           :version version
                           :exit exit
                           :stderr err})))
        (let [archive (io/file temp-root (str/trim out))
              unpacked (io/file temp-root "unpacked")
              extraction
              (do
                (.mkdirs unpacked)
                (sh/sh "tar" "-xzf" (.getPath archive)
                       "-C" (.getPath unpacked)))]
          (when-not (zero? (:exit extraction))
            (throw (ex-info "Unable to extract published Copilot schema artifact"
                            {:package package
                             :version version
                             :exit (:exit extraction)
                             :stderr (:err extraction)})))
          (into {}
                (map (fn [path]
                       [path
                        (sha256-file
                         (.getPath (io/file unpacked path)))]))
                (keys schemas))))
      (finally
        (delete-tree! temp-root)))))

(defn- referenced-evidence
  [report]
  (set (concat
        (mapcat :evidence (:stable-deltas report))
        (mapcat :evidence (:intentional-exclusions report)))))

(defn- stable-inventory-items
  [inventory]
  (set
   (concat
    (for [[path classifications] (:exported-symbols inventory)
          symbol (:stable-public classifications)]
      [:exported-symbol path symbol])
    (for [[interface-name fields] (:session-config-fields inventory)
          field fields]
      [:session-config-field interface-name field])
    (for [[interface-name classifications] (:event-interface-fields inventory)
          field (:stable-public classifications)]
      [:event-interface-field interface-name field])
    (for [[path classifications] (:changed-declarations inventory)
          declaration (:stable-public classifications)]
      [:changed-declaration path declaration]))))

(defn- changed-source-lines
  [upstream base target path]
  (->> (git-lines upstream "diff" "--unified=0" base target "--" path)
       (keep (fn [line]
               (cond
                 (and (str/starts-with? line "+")
                      (not (str/starts-with? line "+++")))
                 (subs line 1)

                 (and (str/starts-with? line "-")
                      (not (str/starts-with? line "---")))
                 (subs line 1)

                 :else nil)))
       vec))

(deftest exact-upstream-range-is-fully-classified
  (let [report (read-report)]
    (is (some? report) "The 2980c78 parity oracle must be committed")
    (when report
      (let [{:keys [upstream commit-classifications changed-paths]} report
            {:keys [base-commit target-commit]} upstream]
        (note-upstream-validation-status! "exact-upstream-range-is-fully-classified")
        (is (= "93351c9217a65960c14a863fc0fa540afd93fa15"
               base-commit))
        (is (= "2980c7828d35754bfc2b334831efec309ab8a2eb"
               target-commit))
        (is (= expected-commits (mapv :commit commit-classifications)))
        (is (= 39 (count commit-classifications)))
        (is (every? #(contains? allowed-classifications (:classification %))
                    commit-classifications))
        (is (every? #(and (keyword? (:status %))
                          (string? (:reason %))
                          (not (str/blank? (:reason %)))
                          (pos-int? (:changed-path-count %))
                          (re-matches #"[0-9a-f]{64}" (:changed-paths-sha256 %)))
                    commit-classifications))
        (is (= 365 (:count changed-paths)))
        (is (= "abae40ae39c147606b2f06d64f594a99c6ae592045ba1a5b350e89b77c314fae"
               (:sha256 changed-paths)))
        (is (seq (:classification-rules changed-paths)))
        (when-let [upstream-repo @upstream-repo]
          (let [actual-commits
                (git-lines upstream-repo "rev-list" "--reverse"
                           (str base-commit ".." target-commit))
                actual-paths
                (sort
                 (git-lines upstream-repo "diff" "--name-only"
                            (str base-commit ".." target-commit)))]
            (is (= expected-commits actual-commits))
            (is (= target-commit
                   (str/trim
                    (git-output upstream-repo "rev-parse" "HEAD")))
                "validation must use an exact checkout of the certified target")
            (is (= (:count changed-paths) (count actual-paths)))
            (is (= (:sha256 changed-paths) (sha256-lines actual-paths)))
            (is (= (:classification-counts changed-paths)
                   (frequencies
                    (map (fn [path]
                           (:classification
                            (first
                             (filter #(path-rule-matches? path %)
                                     (:classification-rules
                                      changed-paths)))))
                         actual-paths))))
            (doseq [path actual-paths]
              (testing path
                (is (= 1 (count (filter #(path-rule-matches? path %)
                                        (:classification-rules changed-paths))))
                    "every changed path must match exactly one classification rule")))
            (doseq [{:keys [commit changed-path-count changed-paths-sha256]}
                    commit-classifications
                    :let [paths (sort
                                 (git-lines upstream-repo "diff-tree"
                                            "--no-commit-id" "--name-only" "-r"
                                            commit))]]
              (testing commit
                (is (= changed-path-count (count paths)))
                (is (= changed-paths-sha256 (sha256-lines paths)))))))))))

(deftest stable-public-surface-and-evidence-are-complete
  (let [report (read-report)]
    (is (some? report) "The 2980c78 parity oracle must be committed")
    (when report
      (let [{:keys [public-surface-audit stable-deltas intentional-exclusions
                    source-evidence decision-authorities]} report
            actual-stable-delta-ids (set (map :id stable-deltas))
            inventory (:symbol-inventory report)]
        (note-upstream-validation-status! "stable-public-surface-and-evidence-are-complete")
        (is (= expected-stable-delta-ids actual-stable-delta-ids))
        (is (= expected-stable-delta-ids (:stable-delta-ids report)))
        (is (= expected-stable-delta-ids
               (set (:stable-public-deltas public-surface-audit))))
        (let [expected-items (stable-inventory-items inventory)
              traced-items (set (mapcat :inventory-items stable-deltas))]
          (is (= expected-items traced-items)
              (str "Every stable inventory item must trace to a stable delta. "
                   "Missing: " (pr-str (set/difference expected-items traced-items))
                   "; unknown: " (pr-str (set/difference traced-items expected-items)))))
        (is (empty? (:unclassified-deltas public-surface-audit)))
        (is (= expected-event-interface-fields (:event-interface-fields inventory))
            "committed event-interface-fields oracle must match the certified expectations")
        (is (every? #(and (= :stable-public (:classification %))
                          (= :ported (:status %))
                          (seq (:evidence %))
                          (seq (:inventory-items %))
                          (seq (:clojure-paths %))
                          (seq (get-in % [:contract :tests]))
                          (seq (get-in % [:contract :docs])))
                    stable-deltas))
        (is (every? #(and (contains? allowed-classifications (:classification %))
                          (not= :stable-public (:classification %))
                          (= :exclude (:decision %))
                          (= :approved (:status %))
                          (contains? decision-authorities (:authority %))
                          (seq (:evidence %))
                          (string? (:reason %))
                          (not (str/blank? (:reason %))))
                    intentional-exclusions))
        (is (= (set (keys source-evidence))
               (referenced-evidence report)))
        (let [exclusions (into {}
                               (map (juxt :id identity))
                               intentional-exclusions)]
          (is (= :experimental
                 (get-in exclusions
                         [:permissions/response-capability :classification])))
          (is (= #{"session.fusion_handoff"
                   "session.fusion_commit_started"}
                 (get-in exclusions
                         [:events/hydra-fusion-internal :event-types])))
          (is (= #{"session.fusion_route_started"
                   "session.fusion_route_failed"
                   "session.fusion_resolved"
                   "session.fusion_completed"
                   "assistant.fusion_phase_started"
                   "assistant.fusion_phase_completed"
                   "assistant.fusion_phase_failed"}
                 (get-in exclusions
                         [:events/hydra-fusion-experimental :event-types]))))
        (let [schema (json/read-str
                      (slurp "schemas/session-events.schema.json"))
              definitions (get schema "definitions")]
          (doseq [[definition event-type]
                  [["FusionCommitStartedEvent" "session.fusion_commit_started"]
                   ["FusionHandoffEvent" "session.fusion_handoff"]]]
            (testing event-type
              (is (= event-type
                     (get-in definitions
                             [definition "properties" "type" "const"])))
              (is (= "internal"
                     (get-in definitions [definition "visibility"])))
              (is (= "experimental"
                     (get-in definitions [definition "stability"]))))))
        (doseq [{:keys [id clojure-paths contract]} stable-deltas
                path (concat clojure-paths (:tests contract) (:docs contract))]
          (testing (str (name id) " local path " path)
            (is (.isFile (io/file path)))))
        (when-let [upstream-repo @upstream-repo]
          (let [{:keys [base-commit target-commit]} (:upstream report)]
            (doseq [[path classifications]
                    (:exported-symbols inventory)]
              (let [expected (apply set/union #{} (vals classifications))
                    actual (added-exported-symbols
                            upstream-repo base-commit target-commit path)]
                (is (= expected actual)
                    (str "Added exported symbols drifted for " path))
                (doseq [[left-class left] classifications
                        [right-class right] classifications
                        :when (neg? (compare (name left-class)
                                             (name right-class)))]
                  (is (empty? (set/intersection left right))
                      (str "Symbol classifications overlap in " path
                           ": " left-class " and " right-class)))))
            (doseq [[interface-name expected]
                    (:session-config-fields inventory)]
              (is (= expected
                     (added-interface-fields
                      upstream-repo base-commit target-commit
                      "nodejs/src/types.ts" interface-name))
                  (str "Added config fields drifted for " interface-name)))
            (doseq [[interface-name {:keys [stable-public experimental]}]
                    (:event-interface-fields inventory)]
              (let [expected (set/union (or stable-public #{}) (or experimental #{}))
                    actual (added-interface-fields
                            upstream-repo base-commit target-commit
                            "nodejs/src/generated/session-events.ts" interface-name)]
                (is (= expected actual)
                    (str "Added event-interface fields drifted for " interface-name))))
            (doseq [[path classifications] (:changed-declarations inventory)]
              (let [expected (apply set/union #{} (vals classifications))
                    actual (changed-exported-declarations
                            upstream-repo base-commit target-commit path)]
                (is (= expected actual)
                    (str "Modified exported declarations drifted for " path))
                (doseq [[left-class left] classifications
                        [right-class right] classifications
                        :when (neg? (compare (name left-class)
                                             (name right-class)))]
                  (is (empty? (set/intersection left right))
                      (str "Declaration classifications overlap in " path
                           ": " left-class " and " right-class)))))
            (doseq [[path expected] (:internal-methods inventory)]
              (is (= expected
                     (added-class-method-symbols
                      upstream-repo base-commit target-commit path))
                  (str "Added class methods drifted for " path))))
          (doseq [path (:inspected-paths public-surface-audit)]
            (testing (str "inspected path " path)
              (is (zero?
                   (:exit
                    (sh/sh "git" "-C" upstream-repo "cat-file" "-e"
                           (str (get-in report [:upstream :target-commit])
                                ":" path)))))))
          (let [{:keys [base-commit target-commit]} (:upstream report)
                changed-lines-by-path
                (into {}
                      (map (fn [path]
                             [path (changed-source-lines
                                    upstream-repo base-commit target-commit path)]))
                      (set (map :path (vals source-evidence))))]
            (doseq [[evidence-id {:keys [path symbol]}] source-evidence]
              (testing (name evidence-id)
                (if symbol
                  (let [{:keys [exit out]}
                        (sh/sh "git" "-C" upstream-repo "grep" "-F"
                               symbol target-commit "--" path)]
                    (is (zero? exit))
                    (is (str/includes? out symbol))
                    (is (some #(str/includes? % symbol)
                              (get changed-lines-by-path path))
                        "evidence symbols must occur on a line changed within the certified range"))
                  (is (seq (get changed-lines-by-path path))
                      "path-only evidence must identify a path changed within the certified range"))))))))))

(deftest exact-target-public-surface-is-certified
  (let [report (read-report)]
    (is (some? report) "The 2980c78 parity oracle must be committed")
    (when report
      (note-upstream-validation-status! "exact-target-public-surface-is-certified")
      (let [{:keys [package-root extension]}
            (:target-public-surface report)]
        (doseq [[surface-name {:keys [symbol-count classification-policy
                                      classification-counts]}]
                [[:package-root package-root] [:extension extension]]]
          (let [{:keys [default overrides stable-overrides]}
                classification-policy
                override-symbol-count (count (classified-symbols overrides))]
            (testing (str (name surface-name)
                          " has a complete committed classification policy")
              (is (contains? allowed-classifications default))
              (is (every? allowed-classifications (keys overrides)))
              (is (not (contains? overrides default)))
              (is (zero? (classification-duplicate-count overrides)))
              (is (= override-symbol-count
                     (reduce + (map count (vals overrides)))))
              (is (= symbol-count
                     (+ override-symbol-count
                        (get classification-counts default))))
              (doseq [[classification symbols] overrides]
                (is (= (count symbols)
                       (get classification-counts classification))))
              (is (every? #(and (string? (:source %))
                                (not (str/blank? (:source %)))
                                (string? (:reason %))
                                (not (str/blank? (:reason %))))
                          (vals stable-overrides))))))
        (is (some-> package-root :provenance :baseline :resource io/resource)
            "The package-root inventory must link to its prior exact-pin oracle"))
      (when-let [upstream-repo @upstream-repo]
        (let [target-commit (get-in report [:upstream :target-commit])
              {:keys [package-root extension classes]}
              (:target-public-surface report)
              baseline-report
              (-> (get-in package-root [:provenance :baseline :resource])
                  io/resource
                  slurp
                  edn/read-string)
              read-source
              (fn [path]
                (git-output upstream-repo "show"
                            (str target-commit ":" path)))
              package-source (read-source (:path package-root))
              explicit-package-symbols (exported-symbols package-source)
              star-exports (:star-exports package-root)
              star-export-symbols
              (for [{:keys [module path symbol-count symbols-sha256]}
                    star-exports
                    :let [symbols (exported-symbols (read-source path))]]
                (do
                  (is (= symbol-count (count symbols))
                      (str module " exported symbol count"))
                  (is (= symbols-sha256
                         (sha256-lines (sort symbols)))
                      (str module " exported symbol inventory"))
                  symbols))
              package-symbols
              (apply set/union explicit-package-symbols
                     star-export-symbols)
              extension-symbols
              (exported-symbols (read-source (:path extension)))
              package-classifications
              (resolve-classification-policy
               (:classification-policy package-root)
               package-symbols)
              extension-classifications
              (resolve-classification-policy
               (:classification-policy extension)
               extension-symbols)]
          (testing "package root resolves every star-exported declaration"
            (is (= (get-in report [:upstream :base-commit])
                   (get-in package-root [:provenance :baseline :target-commit])
                   (get-in baseline-report [:upstream :target-commit])))
            (is (= (get-in package-root [:provenance :baseline :node-tree])
                   (get-in baseline-report
                           [:public-surface-audit :target-node-tree])))
            (is (= (set (map :module star-exports))
                   (star-export-modules package-source)))
            (is (= (:explicit-symbol-count package-root)
                   (count explicit-package-symbols)))
            (is (= (:explicit-symbols-sha256 package-root)
                   (sha256-lines (sort explicit-package-symbols))))
            (is (= (:symbol-count package-root)
                   (count package-symbols)))
            (is (= (:symbols-sha256 package-root)
                   (sha256-lines (sort package-symbols))))
            (is (complete-classification?
                 package-classifications
                 package-symbols))
            (is (= (:classification-counts package-root)
                   (update-vals package-classifications count)))
            (is (every?
                 #(contains? (:stable-public package-classifications) %)
                 (keys (get-in package-root
                               [:classification-policy :stable-overrides])))))
          (testing "extension module exports remain exact"
            (is (complete-classification?
                 extension-classifications
                 extension-symbols))
            (is (= (:symbol-count extension)
                   (count extension-symbols)))
            (is (= (:symbols-sha256 extension)
                   (sha256-lines (sort extension-symbols))))
            (is (= (:classification-counts extension)
                   (update-vals extension-classifications count))))
          (let [classifications-by-path
                {"nodejs/src/index.ts" package-classifications
                 "nodejs/src/generated/session-events.ts" package-classifications
                 "nodejs/src/extension.ts" extension-classifications}]
            (doseq [[path inventory-classifications]
                    (get-in report [:symbol-inventory :exported-symbols])
                    :when (contains? classifications-by-path path)
                    [classification symbols] inventory-classifications
                    symbol symbols]
              (is (contains? (get-in classifications-by-path
                                     [path classification])
                             symbol)
                  (str path " delta classification for " symbol
                       " must match the exact target surface"))))
          (doseq [[surface {:keys [path class-name methods properties
                                   method-count methods-sha256]}]
                  classes
                  :let [source (read-source path)
                        actual-methods
                        (public-class-methods source class-name)
                        expected-methods (classified-symbols methods)
                        actual-properties
                        (public-class-properties source class-name)
                        expected-properties
                        (classified-symbols properties)]]
            (testing (str (name surface)
                          " public class members remain exact and classified")
              (is (= #{:stable :internal :experimental}
                     (set (keys methods))))
              (is (= #{:stable :internal :experimental}
                     (set (keys properties))))
              (is (zero? (classification-duplicate-count methods)))
              (is (zero? (classification-duplicate-count properties)))
              (is (= expected-methods actual-methods))
              (is (= method-count (count actual-methods)))
              (is (= methods-sha256
                     (sha256-lines (sort actual-methods))))
              (is (= expected-properties actual-properties)))))))))

(deftest runtime-schema-and-version-are-exact
  (let [report (read-report)]
    (is (some? report) "The 2980c78 parity oracle must be committed")
    (when report
      (let [{:keys [upstream schema version published-schema-artifact]} report
            package-json
            (when-let [upstream-repo @upstream-repo]
              (json/read-str
               (git-output upstream-repo "show"
                           (str (:target-commit upstream)
                                ":nodejs/package.json"))))]
        (note-upstream-validation-status! "runtime-schema-and-version-are-exact")
        (is (= "1.0.83-1" (:runtime-pin schema)))
        (is (= (:runtime-pin schema)
               (str/trim (slurp ".copilot-schema-version"))))
        (is (= (get-in schema [:api :sha256])
               (sha256-file "schemas/api.schema.json")))
        (is (= (get-in schema [:session-events :sha256])
               (sha256-file "schemas/session-events.schema.json")))
        (is (= (get-in schema [:generated-clojure-output :event-specs-sha256])
               (sha256-file "src/github/copilot_sdk/generated/event_specs.clj")))
        (is (= (get-in schema [:generated-clojure-output :coercions-sha256])
               (sha256-file "script/codegen/coercions.edn")))
        (is (= {:sdk "1.0.11.0"
                :changed? false
                :release-required? false}
               version))
        (is (= (:sdk version)
               (second
                (re-find #"\(def version \"([^\"]+)\"\)"
                         (slurp "build.clj"))))
            "the certification version must match the build version")
        (when package-json
          (is (= (:target-package-version upstream)
                 (get package-json "version")))
          (is (= (get-in upstream [:node-runtime-dependency :version])
                 (get-in package-json
                         ["dependencies"
                          (get-in upstream
                                  [:node-runtime-dependency :package])])))
          (is (= (:schemas published-schema-artifact)
                 (published-schema-hashes published-schema-artifact))
              "vendored schemas must match the exact published npm artifact"))))))

;; -----------------------------------------------------------------------
;; Stable 2980c78 sync (schema 1.0.83-1) additive-field contract tests.
;;
;; These are lightweight, hermetic `s/valid?` checks (no mock server, no
;; upstream checkout) covering the curated idiom specs reconciled in
;; `github.copilot-sdk.specs` for the additive fields introduced by this
;; sync. They complement the oracle/inventory tests above by pinning down
;; the *value-level* contract for each field, including the previously
;; under-specified `map?`-only nested shapes that Lane 1's codegen fix now
;; also enforces structurally on the generated wire side.
;; -----------------------------------------------------------------------

(deftest dispatch-duration-ms-requires-finite-non-negative-value
  (testing "model.call_finished dispatchDurationMs rejects NaN/Infinity/negative"
    (let [base {:turn-id "turn-1"
                :outcome "success"
                :edit-classifier-version 1}]
      (is (s/valid? ::specs/model.call_finished-data
                    (assoc base :dispatch-duration-ms 0)))
      (is (s/valid? ::specs/model.call_finished-data
                    (assoc base :dispatch-duration-ms 123.5)))
      (is (not (s/valid? ::specs/model.call_finished-data
                         (assoc base :dispatch-duration-ms -1)))
          "negative durations must be rejected")
      (is (not (s/valid? ::specs/model.call_finished-data
                         (assoc base :dispatch-duration-ms Double/NaN)))
          "##NaN must be rejected")
      (is (not (s/valid? ::specs/model.call_finished-data
                         (assoc base :dispatch-duration-ms Double/POSITIVE_INFINITY)))
          "##Inf must be rejected")
      (is (not (s/valid? ::specs/model.call_finished-data
                         (assoc base :dispatch-duration-ms Double/NEGATIVE_INFINITY)))
          "##-Inf must be rejected")
      (is (not (s/valid? ::specs/model.call_finished-data
                         (assoc base :dispatch-duration-ms "123")))
          "non-numeric durations must be rejected"))))

(deftest tool-call-id-permits-empty-string
  (testing "pinned schema 1.0.83-1 allows an empty toolCallId"
    (is (s/valid? ::specs/tool-call-id ""))
    (is (s/valid? ::specs/tool-call-id "call-123"))
    (is (not (s/valid? ::specs/tool-call-id 123)))
    (is (not (s/valid? ::specs/tool-call-id nil)))))

(deftest session-auto-tier-accepted-on-start-and-resume-data
  (testing "session.start autoTier accepts the pinned enum and rejects other values"
    (let [base {:session-id "session-1"}]
      (doseq [tier [:balance :intelligence :efficiency]]
        (is (s/valid? ::specs/session.start-data (assoc base :auto-tier tier))))
      (is (s/valid? ::specs/session.start-data base)
          "auto-tier remains optional")
      (is (not (s/valid? ::specs/session.start-data (assoc base :auto-tier :turbo))))
      (is (not (s/valid? ::specs/session.start-data (assoc base :auto-tier "balance"))))))
  (testing "session.resume autoTier accepts the pinned enum and rejects other values"
    (let [base {:event-count 0}]
      (doseq [tier [:balance :intelligence :efficiency]]
        (is (s/valid? ::specs/session.resume-data (assoc base :auto-tier tier))))
      (is (s/valid? ::specs/session.resume-data base)
          "auto-tier remains optional")
      (is (not (s/valid? ::specs/session.resume-data (assoc base :auto-tier :turbo))))
      (is (not (s/valid? ::specs/session.resume-data (assoc base :auto-tier "balance")))))))

(deftest assistant-message-reasoning-blocks-remain-generated-only
  (testing "experimental reasoningBlocks are wire evidence, not a curated idiom spec"
    (is (nil? (s/get-spec ::specs/reasoning-blocks)))
    (is (s/valid?
         ::generated-events/assistant-message-reasoning-blocks-shape
         {:provider "anthropic"
          :blocks [{:type "text" :text "..."}]}))))

(deftest assistant-usage-output-ttft-ms-non-negative
  (testing "assistant.usage outputTtftMs accepts non-negative numbers only"
    (let [base {:model "gpt-5"}]
      (is (s/valid? ::specs/assistant.usage-data (assoc base :output-ttft-ms 0)))
      (is (s/valid? ::specs/assistant.usage-data (assoc base :output-ttft-ms 42.5)))
      (is (s/valid? ::specs/assistant.usage-data base)
          "output-ttft-ms remains optional")
      (is (not (s/valid? ::specs/assistant.usage-data (assoc base :output-ttft-ms -1))))
      (is (not (s/valid? ::specs/assistant.usage-data
                         (assoc base :output-ttft-ms Double/NaN))))
      (is (not (s/valid? ::specs/assistant.usage-data
                         (assoc base :output-ttft-ms
                                Double/POSITIVE_INFINITY))))
      (is (not (s/valid? ::specs/assistant.usage-data
                         (assoc base :output-ttft-ms 1/2))))
      (is (not (s/valid? ::specs/assistant.usage-data (assoc base :output-ttft-ms "42")))))))

(deftest compaction-complete-behavior-model-id-additive-field
  (testing "session.compaction_complete behaviorModelId is a plain optional string"
    (is (s/valid? ::specs/session.compaction_complete-data {:success true}))
    (is (s/valid? ::specs/session.compaction_complete-data
                  {:success true :behavior-model-id "gpt-5-compaction"}))
    (is (not (s/valid? ::specs/session.compaction_complete-data
                       {:success true :behavior-model-id 42})))))

(deftest hook-start-and-end-data-required-fields
  (testing "hook.start requires hookInvocationId and hookType, parentToolCallId optional"
    (let [base {:hook-invocation-id "hook-1" :hook-type "pre-tool-use"}]
      (is (s/valid? ::specs/hook.start-data base))
      (is (s/valid? ::specs/hook.start-data
                    {:hook-invocation-id "" :hook-type ""}))
      (is (s/valid? ::specs/hook.start-data (assoc base :parent-tool-call-id "call-1")))
      (doseq [input [nil "text" 42 true [1 nil] {:nested ["value"]}]]
        (is (s/valid? ::specs/hook.start-data (assoc base :input input))))
      (is (not (s/valid? ::specs/hook.start-data
                         (assoc base :input (Object.)))))
      (is (not (s/valid? ::specs/hook.start-data (dissoc base :hook-invocation-id))))
      (is (not (s/valid? ::specs/hook.start-data (dissoc base :hook-type))))))
  (testing "hook.end additionally requires success, parentToolCallId optional"
    (let [base {:hook-invocation-id "hook-1" :hook-type "pre-tool-use" :success true}]
      (is (s/valid? ::specs/hook.end-data base))
      (is (s/valid? ::specs/hook.end-data
                    {:hook-invocation-id "" :hook-type "" :success true}))
      (is (s/valid? ::specs/hook.end-data (assoc base :parent-tool-call-id "call-1")))
      (is (s/valid? ::specs/hook.end-data
                    (assoc base :error {:message "boom"
                                        :source "plugin"
                                        :stack "trace"
                                        :future-field true})))
      (doseq [output [nil "text" 42 true [1 nil] {:nested ["value"]}]]
        (is (s/valid? ::specs/hook.end-data (assoc base :output output))))
      (is (not (s/valid? ::specs/hook.end-data
                         (assoc base :output #{:not-json}))))
      (is (not (s/valid? ::specs/hook.end-data (assoc base :error "boom"))))
      (is (not (s/valid? ::specs/hook.end-data
                         (assoc base :error {:source "plugin"}))))
      (is (not (s/valid? ::specs/hook.end-data (dissoc base :success)))))))

(deftest event-fields-with-colliding-names-use-field-specific-contracts
  (testing "shared leaf specs do not silently constrain unrelated event fields"
    (doseq [spec [::specs/result ::specs/error ::specs/arguments ::specs/caller]]
      (is (nil? (s/get-spec spec)))))
  (testing "assistant tool arguments accept every recursive JSON value"
    (let [base {:tool-call-id "call-1" :name "tool"}]
      (doseq [arguments [nil "text" 42 true [1 nil] {:camelCase "value"}]]
        (is (s/valid? ::specs/assistant-message-tool-request
                      (assoc base :arguments arguments))))
      (is (not (s/valid? ::specs/assistant-message-tool-request
                         (assoc base :arguments (Object.)))))))
  (testing "tool execution fields retain their schema-defined shapes"
    (is (s/valid? ::specs/tool.execution_start-data
                  {:tool-call-id "call-1" :tool-name "tool"
                   :arguments [1 {:camelCase true}]}))
    (is (s/valid? ::specs/tool.execution_complete-data
                  {:tool-call-id "call-1" :success false
                   :result {:content "result"
                            :structured-content {:nested [true 1 "value" nil]}}
                   :error {:message "provider" :code "failed"}}))
    (doseq [invalid [{:result "result"}
                     {:result {:structured-content true}}
                     {:result {:content "result" :contents {}}}
                     {:error "boom"}
                     {:error {:code "failed"}}
                     {:error {:message "boom" :code 42}}]]
      (is (not (s/valid? ::specs/tool.execution_complete-data
                         (merge {:tool-call-id "call-1" :success false}
                                invalid))))))
  (testing "tool-result-object errors are strings"
    (is (s/valid? ::specs/tool-result-object
                  {:text-result-for-llm "failed"
                   :result-type :failure
                   :error "message"}))
    (is (not (s/valid? ::specs/tool-result-object
                       {:text-result-for-llm "failed"
                        :result-type :failure
                        :error {:message "boom"}}))))
  (testing "string-valued errors reject unrelated JSON values"
    (is (not (s/valid? ::specs/session.compaction_complete-data
                       {:success false :error {:message "boom"}})))
    (is (not (s/valid? ::specs/subagent.failed-data
                       {:tool-call-id "call-1"
                        :agent-name "reviewer"
                        :agent-display-name "Reviewer"
                        :error {:message "boom"}})))
    (is (not (s/valid? ::specs/mcp-loaded-server
                       {:name "server"
                        :status "failed"
                        :error {:message "boom"}})))))

(deftest permission-response-capability-maps-to-pinned-wire-values
  (doseq [capability [:interactive :headless :none]]
    (let [context {:outcome :auto-approved
                   :source :host-policy
                   :surface :sdk
                   :response-capability capability}]
      (is (s/valid? ::specs/permission-decision-context context))
      (is (= (name capability)
             (:response-capability
              (@#'session/permission-context->wire context)))))))

(deftest subagent-started-additive-fields-and-parent-id-collision-fix
  (testing "subagent.started agentType/executionMode/resumable/parentId are additive"
    (let [base {:tool-call-id "call-1"
                :agent-name "reviewer"
                :agent-display-name "Reviewer"
                :agent-description "Reviews code"}]
      (is (s/valid? ::specs/subagent.started-data base)
          "all additive fields remain optional")
      (is (s/valid? ::specs/subagent.started-data
                    (assoc base
                           :agent-type "review"
                           :execution-mode "autopilot"
                           :resumable true
                           :parent-id "parent-call-1")))
      (is (not (s/valid? ::specs/subagent.started-data (assoc base :resumable "yes"))))
      (is (not (s/valid? ::specs/subagent.started-data (assoc base :agent-type 42))))
      (is (not (s/valid? ::specs/subagent.started-data (assoc base :execution-mode 42))))
      (is (not (s/valid? ::specs/subagent.started-data (assoc base :parent-id 42)))
          "parent-id must be validated via ::subagent-parent-id despite the unqualified-key collision with the unrelated top-level ::parent-id spec"))))

(deftest subagent-completed-and-failed-model-tracking-fields
  (let [model-tracking {:first-dispatched-model "gpt-5"
                        :configured-model-preference "gpt-5"
                        :explicit-model-override "gpt-5-mini"
                        :explicit-model-matches-preference false
                        :configured-model-matches-actual true}]
    (testing "subagent.completed accepts the five model-tracking fields"
      (let [base {:tool-call-id "call-1" :agent-name "reviewer" :agent-display-name "Reviewer"}]
        (is (s/valid? ::specs/subagent.completed-data base))
        (is (s/valid? ::specs/subagent.completed-data (merge base model-tracking)))
        (is (not (s/valid? ::specs/subagent.completed-data
                           (assoc base :explicit-model-matches-preference "false"))))))
    (testing "subagent.failed accepts the five model-tracking fields"
      (let [base {:tool-call-id "call-1" :agent-name "reviewer" :agent-display-name "Reviewer"
                  :error "boom"}]
        (is (s/valid? ::specs/subagent.failed-data base))
        (is (s/valid? ::specs/subagent.failed-data (merge base model-tracking)))
        (is (not (s/valid? ::specs/subagent.failed-data
                           (assoc base :configured-model-matches-actual "true"))))))))

(deftest permission-request-can-offer-server-wide-approval
  (testing "PermissionPromptRequestMcp canOfferServerWideApproval is a plain optional boolean"
    (is (s/valid? ::specs/permission-request {:permission-kind :mcp}))
    (is (s/valid? ::specs/permission-request
                  {:permission-kind :mcp :can-offer-server-wide-approval true}))
    (is (not (s/valid? ::specs/permission-request
                       {:permission-kind :mcp :can-offer-server-wide-approval "true"})))))

(deftest generated-assistant-message-tool-request-caller-is-closed
  (testing "the generated wire spec for AssistantMessageToolRequestCaller stays closed"
    (is (s/valid? ::generated-events/assistant-message-tool-request-caller-shape
                  {:caller-id "abc" :type "program"}))
    (is (not (s/valid? ::generated-events/assistant-message-tool-request-caller-shape
                       {:type "program"}))
        "callerId is required")
    (is (not (s/valid? ::generated-events/assistant-message-tool-request-caller-shape
                       {:caller-id "abc" :type "user"}))
        "type must be the literal \"program\"")
    (is (not (s/valid? ::generated-events/assistant-message-tool-request-caller-shape
                       {:caller-id "abc" :type "program" :extra "nope"}))
        "additionalProperties=false must reject unknown keys"))
  (testing "the idiom event spec validates known fields without closing future extensions"
    (let [base {:tool-call-id "call-1" :name "tool"}]
      (is (s/valid? ::specs/assistant-message-tool-request
                    (assoc base :caller {:caller-id "abc" :type "program"})))
      (is (not (s/valid? ::specs/assistant-message-tool-request
                         (assoc base :caller {:type "program"}))))
      (is (not (s/valid? ::specs/assistant-message-tool-request
                         (assoc base :caller {:caller-id "abc"
                                              :type "user"}))))
      (is (s/valid? ::specs/assistant-message-tool-request
                    (assoc base :caller {:caller-id "abc"
                                         :type "program"
                                         :future-field "value"}))))))
