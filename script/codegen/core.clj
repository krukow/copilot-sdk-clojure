(ns codegen.core
  "Shared utilities for the copilot-sdk-clojure schema → Clojure code generator.

  Pure-Clojure / Babashka-compatible. Uses cheshire (bundled with bb) for JSON."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]))

;; ---------------------------------------------------------------------------
;; Schema loading & $ref resolution
;; ---------------------------------------------------------------------------

(defn load-schema
  "Read a JSON Schema file and return it as a Clojure map (keyword keys)."
  [path]
  (with-open [r (io/reader path)]
    (json/parse-stream r true)))

(defn resolve-ref
  "Resolve a `$ref` like `#/definitions/Foo` against the root schema.
   Only supports the local `#/...` form (sufficient for the schemas we consume)."
  [root ref]
  (when-not (str/starts-with? ref "#/")
    (throw (ex-info "Only local $refs are supported" {:ref ref})))
  (loop [node root
         segs (rest (str/split ref #"/"))]
    (cond
      (empty? segs) node
      (nil? node)   (throw (ex-info "Unresolved $ref" {:ref ref}))
      :else         (recur (get node (keyword (first segs))) (rest segs)))))

(defn deref-once
  "If `node` is a `$ref`, follow one level of indirection. Otherwise return as-is."
  [root node]
  (if-let [r (:$ref node)]
    (resolve-ref root r)
    node))

;; ---------------------------------------------------------------------------
;; Naming: wire (camelCase / snake_case) ↔ Clojure (kebab-case)
;; ---------------------------------------------------------------------------

(defn wire-key->kebab
  "Transform a wire JSON key to a kebab-case Clojure name part.
   Rules:
   - Preserve acronym word boundaries (`URLValue` → `url-value`).
   - Insert `-` between a lowercase letter or digit and an uppercase letter.
   - Collapse separators and strip them from the edges.
   - Lowercase the result.
   Examples:
     `sessionId`     → `session-id`
     `startTime`     → `start-time`
     `tool_efficiency` → `tool-efficiency`
     `parentId`      → `parent-id`"
  [s]
  (-> s
      (str/replace #"([A-Z]+)([A-Z][a-z])" "$1-$2")
      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
      (str/replace #"[_\s-]+" "-")
      (str/replace #"^-+|-+$" "")
      str/lower-case))

(defn wire-key->kw
  "Transform a wire key to an unqualified Clojure keyword (kebab-case)."
  [s]
  (keyword (wire-key->kebab s)))

;; ---------------------------------------------------------------------------
;; Anti-collision: keep a single canonical name for collisions like
;; `parentId` / `parent_id` if they ever appear together (they don't yet).
;; ---------------------------------------------------------------------------

(defn collect-anyOf-discriminators
  "Given a top-level schema using `anyOf` over object variants with a
   `properties.type.const` discriminator, return a vector of {:type :variant}
   maps preserving order."
  [root]
  (let [variants (or (get-in root [:definitions :SessionEvent :anyOf])
                     (:anyOf root))]
    (when-not variants
      (throw (ex-info "Schema does not have an anyOf at the expected location"
                      {:keys (keys root)})))
    (mapv (fn [v]
            (let [v* (deref-once root v)
                  t  (get-in v* [:properties :type :const])]
              (when-not t
                (throw (ex-info "anyOf variant has no `type.const` discriminator"
                                {:variant v*})))
              {:type t :variant v*}))
          variants)))

;; ---------------------------------------------------------------------------
;; File I/O helpers
;; ---------------------------------------------------------------------------

(def header
  ";; AUTO-GENERATED — do not edit. Run `bb codegen`.\n;; Source: schemas/  (schema version pinned in .copilot-schema-version)\n")

(defn write-clj!
  "Write `forms` (a sequence of forms) to `path` with a generated-file header."
  [path forms]
  (let [sw (java.io.StringWriter.)]
    (.write sw header)
    (.write sw "\n")
    (doseq [f forms]
      (binding [*print-meta* true]
        (.write sw (pr-str f)))
      (.write sw "\n\n"))
    (io/make-parents path)
    (spit path (.toString sw))))
