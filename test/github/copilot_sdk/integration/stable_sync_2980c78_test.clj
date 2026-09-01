(ns github.copilot-sdk.integration.stable-sync-2980c78-test
  "Executable certification for the post-93351c upstream delta."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
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
    :events/mode-notice-delivered
    :events/model-call-finished
    :events/subagent-configured
    :mode/builtin-skill-isolation
    :permissions/managed-bypass-policy
    :permissions/response-capability
    :session/ask-user-variant
    :session/auto-tier
    :session/autopilot-idle
    :session/feature-flags})

(def ^:private allowed-classifications
  #{:experimental :generated-only :internal :language-specific :mixed
    :stable-public :test-harness})

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

(defn- referenced-evidence
  [report]
  (set (concat
        (mapcat :evidence (:stable-deltas report))
        (mapcat :evidence (:intentional-exclusions report)))))

(deftest exact-upstream-range-is-fully-classified
  (let [report (read-report)]
    (is (some? report) "The post-93351c parity oracle must be committed")
    (when report
      (let [{:keys [upstream commit-classifications changed-paths]} report
            {:keys [base-commit target-commit]} upstream]
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
            (is (= (:count changed-paths) (count actual-paths)))
            (is (= (:sha256 changed-paths) (sha256-lines actual-paths)))
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
    (is (some? report) "The post-93351c parity oracle must be committed")
    (when report
      (let [{:keys [public-surface-audit stable-deltas source-evidence]} report
            stable-delta-ids (set (map :id stable-deltas))]
        (is (= expected-stable-delta-ids stable-delta-ids))
        (is (= stable-delta-ids (:stable-delta-ids report)))
        (is (= stable-delta-ids
               (set (:stable-public-deltas public-surface-audit))))
        (is (empty? (:unclassified-deltas public-surface-audit)))
        (is (every? #(and (= :stable-public (:classification %))
                          (= :ported (:status %))
                          (seq (:evidence %))
                          (seq (:clojure-paths %))
                          (seq (get-in % [:contract :tests]))
                          (seq (get-in % [:contract :docs])))
                    stable-deltas))
        (is (= (set (keys source-evidence))
               (referenced-evidence report)))
        (when-let [upstream-repo @upstream-repo]
          (doseq [[evidence-id {:keys [path symbol]}] source-evidence]
            (testing (name evidence-id)
              (let [{:keys [exit out]}
                    (sh/sh "git" "-C" upstream-repo "grep" "-F"
                           symbol
                           (get-in report [:upstream :target-commit])
                           "--" path)]
                (is (zero? exit))
                (is (str/includes? out symbol))))))))))

(deftest runtime-schema-and-version-are-exact
  (let [report (read-report)]
    (is (some? report) "The post-93351c parity oracle must be committed")
    (when report
      (let [{:keys [upstream schema version]} report
            package-json
            (when-let [upstream-repo @upstream-repo]
              (json/read-str
               (git-output upstream-repo "show"
                           (str (:target-commit upstream)
                                ":nodejs/package.json"))))]
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
        (when package-json
          (is (= (:target-package-version upstream)
                 (get package-json "version")))
          (is (= (get-in upstream [:node-runtime-dependency :version])
                 (get-in package-json
                         ["dependencies"
                          (get-in upstream
                                  [:node-runtime-dependency :package])]))))))))
