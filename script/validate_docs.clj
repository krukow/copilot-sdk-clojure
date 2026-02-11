#!/usr/bin/env bb
;; Validates documentation: internal links, code blocks, and structure.
;; Run via: bb validate-docs

(ns validate-docs
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.edn :as edn]))

(def repo-root (str (fs/canonicalize ".")))

;; --- Configuration ---

(def doc-files
  "Markdown files to validate."
  (->> (concat (fs/glob "." "doc/**/*.md")
               (fs/glob "." "*.md")
               (fs/glob "." "examples/**/*.md"))
       (map str)
       (remove #(str/includes? % "doc/api/"))
       sort))

(def required-files
  "Files that must exist for the doc structure to be valid."
  ["doc/index.md"
   "doc/style.md"
   "doc/reference/API.md"
   "doc/getting-started.md"
   "doc/auth/index.md"
   "doc/auth/byok.md"
   "doc/mcp/overview.md"
   "doc/mcp/debugging.md"
   "README.md"
   "AGENTS.md"
   "CHANGELOG.md"])

(def source-namespaces
  "Known public namespaces in the SDK."
  #{"github.copilot-sdk.client"
    "github.copilot-sdk.session"
    "github.copilot-sdk.helpers"
    "github.copilot-sdk.specs"
    "github.copilot-sdk.instrument"})

;; --- State ---

(def errors (atom []))
(def warnings (atom []))

(defn error! [file msg]
  (swap! errors conj {:file file :msg msg}))

(defn warn! [file msg]
  (swap! warnings conj {:file file :msg msg}))

;; --- Link Validation ---

(defn extract-md-links
  "Extract [text](path) links from markdown content. Returns seq of {:text :path :line}."
  [content]
  (let [lines (str/split-lines content)]
    (->> lines
         (map-indexed
          (fn [idx line]
            (->> (re-seq #"\[([^\]]*)\]\(([^)]+)\)" line)
                 (map (fn [[_ text path]]
                        {:text text :path path :line (inc idx)})))))
         (apply concat))))

(defn resolve-link
  "Resolve a relative link path from the perspective of the file containing it."
  [from-file link-path]
  (let [;; Strip anchor
        path (first (str/split link-path #"#"))
        ;; Skip external links, mailto, etc.
        _ (when (or (str/starts-with? path "http")
                    (str/starts-with? path "mailto:"))
            (throw (ex-info "external" {})))
        ;; Resolve relative to the directory of from-file
        base-dir (str (fs/parent from-file))
        resolved (str (fs/normalize (fs/path base-dir path)))]
    resolved))

(defn validate-links [file content]
  (let [links (extract-md-links content)]
    (doseq [{:keys [text path line]} links]
      (when-not (or (str/starts-with? path "http")
                    (str/starts-with? path "mailto:")
                    (str/starts-with? path "#"))
        (try
          (let [resolved (resolve-link file path)]
            (when-not (fs/exists? resolved)
              (error! file (format "Line %d: broken link [%s](%s) → %s"
                                   line text path resolved))))
          (catch Exception _
            nil))))))

;; --- Code Block Validation ---

(defn extract-clojure-blocks
  "Extract ```clojure code blocks. Returns seq of {:code :start-line}."
  [content]
  (let [lines (str/split-lines content)
        n (count lines)]
    (loop [i 0 blocks [] in-block? false start 0 acc []]
      (if (>= i n)
        (if in-block?
          (conj blocks {:code (str/join "\n" acc) :start-line (inc start)})
          blocks)
        (let [line (nth lines i)]
          (cond
            (and (not in-block?) (re-matches #"\s*```clojure\s*" line))
            (recur (inc i) blocks true i [])

            (and in-block? (re-matches #"\s*```\s*" line))
            (recur (inc i)
                   (conj blocks {:code (str/join "\n" acc) :start-line (inc start)})
                   false 0 [])

            in-block?
            (recur (inc i) blocks true start (conj acc line))

            :else
            (recur (inc i) blocks false start acc)))))))

(defn parse-clojure-forms
  "Try to parse Clojure code block. Returns {:ok true} or {:ok false :error msg}.
   Strips Clojure reader macros that EDN doesn't support (#(), @, #', etc.)."
  [code]
  (try
    (let [;; Neutralize non-EDN reader syntax
          cleaned (-> code
                      ;; #(...) anonymous fns → (fn [] ...)
                      (str/replace #"#\(" "(")
                      ;; @deref → deref
                      (str/replace #"@(\w)" "$1")
                      ;; #'var → var
                      (str/replace #"#'" "")
                      ;; #_ discard — just strip the marker; the next form
                      ;; parses fine on its own (we check syntax, not semantics)
                      (str/replace #"#_" ""))
          rdr (java.io.PushbackReader. (java.io.StringReader. cleaned))]
      (loop []
        (let [form (edn/read {:eof ::eof :readers {'object (fn [_] nil)}} rdr)]
          (if (= form ::eof)
            {:ok true}
            (recur)))))
    (catch Exception e
      {:ok false :error (.getMessage e)})))

(defn validate-code-blocks [file content]
  (let [blocks (extract-clojure-blocks content)]
    (doseq [{:keys [code start-line]} blocks]
      (let [;; Strip ;; => comments and ;; prints: lines for parsing
            cleaned (str/replace code #";;.*$" "")
            result (parse-clojure-forms cleaned)]
        (when-not (:ok result)
          (warn! file (format "Line %d: code block parse warning: %s"
                              start-line (:error result))))))))

;; --- Heading Extraction (for anchor validation) ---

(defn extract-headings
  "Extract markdown headings, return set of anchor slugs."
  [content]
  (->> (str/split-lines content)
       (filter #(re-matches #"^#{1,6}\s+.*" %))
       (map (fn [h]
              (-> h
                  (str/replace #"^#{1,6}\s+" "")
                  str/lower-case
                  (str/replace #"[^a-z0-9\s-]" "")
                  str/trim
                  (str/replace #"\s+" "-"))))
       set))

(defn validate-anchor-links [file content]
  (let [links (extract-md-links content)
        headings (extract-headings content)]
    (doseq [{:keys [text path line]} links]
      (when (str/starts-with? path "#")
        (let [anchor (subs path 1)]
          (when-not (headings anchor)
            (warn! file (format "Line %d: anchor [%s](%s) not found in headings"
                                line text path))))))))

;; --- Structure Validation ---

(defn validate-structure []
  (doseq [f required-files]
    (when-not (fs/exists? f)
      (error! f "Required file missing"))))

;; --- Namespace Reference Check ---

(defn validate-namespace-refs [file content]
  (let [;; Look for require forms referencing our namespaces
        ns-refs (re-seq #"github\.copilot[-_]sdk[.\w-]*" content)]
    (doseq [ns-ref ns-refs]
      (let [normalized (str/replace ns-ref "_" "-")]
        (when (and (not (source-namespaces normalized))
                   ;; Allow sub-references like github.copilot-sdk.client/start!
                   (not (some #(str/starts-with? normalized (str % "/")) source-namespaces))
                   ;; Allow the base namespace
                   (not= normalized "github.copilot-sdk"))
          (warn! file (format "Reference to unknown namespace: %s" normalized)))))))

;; --- Main ---

(defn run-validation []
  (println "Validating documentation...")
  (println (format "  Found %d markdown files to check" (count doc-files)))
  (println)

  ;; Structure
  (print "  Checking structure... ")
  (validate-structure)
  (println "done")

  ;; Per-file checks
  (print "  Checking links, code blocks, and references... ")
  (doseq [file doc-files]
    (let [content (slurp file)]
      (validate-links file content)
      (validate-code-blocks file content)
      (validate-anchor-links file content)
      (validate-namespace-refs file content)))
  (println "done")
  (println)

  ;; Report
  (let [errs @errors
        warns @warnings]
    (when (seq warns)
      (println (format "⚠ %d warning(s):" (count warns)))
      (doseq [{:keys [file msg]} warns]
        (println (format "  %s: %s" file msg)))
      (println))

    (when (seq errs)
      (println (format "✗ %d error(s):" (count errs)))
      (doseq [{:keys [file msg]} errs]
        (println (format "  %s: %s" file msg)))
      (println))

    (if (empty? errs)
      (do
        (println (format "✓ Documentation valid (%d files, %d warnings)"
                         (count doc-files) (count warns)))
        (System/exit 0))
      (do
        (println (format "✗ Documentation invalid (%d errors, %d warnings)"
                         (count errs) (count warns)))
        (System/exit 1)))))

(run-validation)
