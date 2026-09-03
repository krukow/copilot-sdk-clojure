(ns codegen.emit-coerce
  "Emit the generated coercion namespace from `script/codegen/coercions.edn`.

   Output: `src/github/copilot_sdk/generated/coerce.clj`.

   Three-tier architecture:
     wire spec  (generated, lives in event-specs)
        │
        ▼  coerce/event-wire->idiom
     idiom spec (hand-written, lives in github.copilot-sdk.specs)

   The table in coercions.edn is the single source of truth for which fields
   need conversion between the upstream wire shape and the Clojure-idiomatic
   public API (Instants, keywords, etc.).

   Converters are idempotent and nil-safe by construction:
     - nil       → nil
     - already-idiom value → unchanged (so re-applying coercion is safe)
     - wire value → coerced
     - anything else → the converter throws ex-info with the raw value and
       its class; `coerce-data` catches and re-throws with `:event-type`,
       `:field`, and `:direction` added to ex-data for diagnosability."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

;; ---------------------------------------------------------------------------
;; Static converter table — emitted verbatim into coerce.clj.
;;
;; Each [wire-tag idiom-tag] entry must define both directions; the test suite
;; round-trips each canonical fixture through both. Adding a new tag pair
;; requires adding both directions here AND a corresponding pair in the
;; generated namespace's static converter literal below.
;; ---------------------------------------------------------------------------

(def supported-tag-pairs
  "Static set of [wire idiom] tag pairs we know how to convert.
   Used at codegen time to validate coercions.edn."
  #{[:enum-string :keyword]
    [:iso-string :instant]})

(defn- validate-coercions!
  "Throw ex-info if `coercions` references a tag pair we don't know how to emit."
  [coercions]
  (doseq [[event-type fields] coercions
          [field [wire-tag idiom-tag]] fields]
    (when-not (contains? supported-tag-pairs [wire-tag idiom-tag])
      (throw (ex-info (format "Unknown coercion tag pair %s for %s/%s"
                              [wire-tag idiom-tag] event-type field)
                      {:event-type event-type
                       :field field
                       :tag-pair [wire-tag idiom-tag]
                       :supported supported-tag-pairs})))))

(defn- sort-coercions
  "Return coercions deterministically: sorted by event-type, then by field name."
  [coercions]
  (into (sorted-map)
        (for [[event-type fields] coercions]
          [event-type (into (sorted-map) fields)])))

(defn load-coercions
  "Read and validate `coercions.edn` from the given path. Returns a sorted map."
  [path]
  (let [raw (edn/read-string (slurp path))]
    (validate-coercions! raw)
    (sort-coercions raw)))

;; ---------------------------------------------------------------------------
;; Form emission
;; ---------------------------------------------------------------------------

(def ^:private ns-name "github.copilot-sdk.generated.coerce")

(defn- emit-ns-form []
  `(~'ns ~(symbol ns-name)
         "AUTO-GENERATED — do not edit. Run `bb codegen`.

   Per-event-type coercion between the upstream wire shape and the
   Clojure-idiomatic public API. Source: script/codegen/coercions.edn"))

(defn- emit-converter-defs
  "Emit the static converter functions. These are hand-coded but lifted into
   the generated file so the runtime has a single self-contained namespace."
  []
  (list
   ;; iso-string ⇄ instant ────────────────────────────────────────────────
   `(~'defn ~'iso-string->instant
     "Parse an ISO-8601 timestamp string to a java.time.Instant. nil-safe and
      idempotent: returns nil for nil, the same Instant for an Instant input,
      and throws ex-info for any other value class."
     [~'v]
     (~'cond
      (~'nil? ~'v)                          ~'nil
      (~'instance? ~'java.time.Instant ~'v) ~'v
      (~'string? ~'v)                       (~'java.time.Instant/parse ~'v)
      :else
      (~'throw (~'ex-info "Expected ISO string or Instant"
                          {:value ~'v :value-class (~'class ~'v)}))))

   `(~'defn ~'instant->iso-string
     "Render a java.time.Instant as an ISO-8601 string. nil-safe and
      idempotent: returns nil for nil, the same string for a string input,
      and throws ex-info for any other value class."
     [~'v]
     (~'cond
      (~'nil? ~'v)                          ~'nil
      (~'string? ~'v)                       ~'v
      (~'instance? ~'java.time.Instant ~'v) (~'.toString ~'v)
      :else
      (~'throw (~'ex-info "Expected Instant or ISO string"
                          {:value ~'v :value-class (~'class ~'v)}))))

   ;; enum-string <-> keyword
   `(~'defn ~'enum-string->keyword
     "Convert a wire enum string to a keyword. nil-safe and idempotent."
     [~'v]
     (~'cond
      (~'nil? ~'v)     ~'nil
      (~'keyword? ~'v) ~'v
      (~'string? ~'v)  (~'keyword ~'v)
      :else
      (~'throw (~'ex-info "Expected enum string or keyword"
                         {:value ~'v :value-class (~'class ~'v)}))))

   `(~'defn ~'keyword->enum-string
     "Convert an idiomatic enum keyword to its wire string. nil-safe and
      idempotent."
     [~'v]
     (~'cond
      (~'nil? ~'v)     ~'nil
      (~'string? ~'v)  ~'v
      (~'keyword? ~'v) (~'name ~'v)
      :else
      (~'throw (~'ex-info "Expected keyword or enum string"
                         {:value ~'v :value-class (~'class ~'v)}))))))

(defn- emit-converters-map
  "Emit the static converters map referenced by coerce-data."
  []
  `(~'def ~(with-meta 'converters {:private true})
          "Map of [wire-tag idiom-tag] → {:wire->idiom fn :idiom->wire fn}."
          {[:iso-string :instant]
           {:wire->idiom ~'iso-string->instant
            :idiom->wire ~'instant->iso-string}
           [:enum-string :keyword]
           {:wire->idiom ~'enum-string->keyword
            :idiom->wire ~'keyword->enum-string}}))

(defn- emit-field-coercions
  "Emit the field-coercions map: event-type-string → {field-kw [wire idiom]}."
  [coercions]
  `(~'def ~'field-coercions
          "Per-event-type field coercion table. Generated from
   script/codegen/coercions.edn."
          ~coercions))

(defn- emit-coerce-data
  "Emit coerce-data + event-wire->idiom + event-idiom->wire functions."
  []
  (list
   `(~'defn ~'coerce-data
     "Apply coercions to a `data` map for the given event-type and direction
      (:wire->idiom or :idiom->wire). Unknown event types and unknown fields
      pass through unchanged. Each converter is nil-safe and idempotent so
      the same coercion can be applied twice without corruption.

      If a converter throws (e.g. malformed wire payload), the exception is
      re-thrown as ex-info with `:event-type`, `:field`, and `:direction`
      added to ex-data so callers can diagnose without inspecting the
      converter source."
     [~'event-type ~'data ~'direction]
     (~'if-let [~'fields (~'get ~'field-coercions ~'event-type)]
              (~'reduce-kv
               (~'fn [~'acc ~'k ~'v]
                     (~'assoc ~'acc ~'k
                              (~'if-let [~'tag-pair (~'get ~'fields ~'k)]
                                       (~'if-let [~'f (~'get-in ~'converters [~'tag-pair ~'direction])]
                                                (~'try
                                                  (~'f ~'v)
                                                  (~'catch ~'Exception ~'e
                                                    (~'throw
                                                      (~'ex-info
                                                        (~'str "Coercion failed for "
                                                               ~'event-type "/" ~'k
                                                               " (" ~'direction "): "
                                                               (~'.getMessage ~'e))
                                                        (~'merge (~'or (~'ex-data ~'e) {})
                                                                 {:event-type ~'event-type
                                                                  :field ~'k
                                                                  :direction ~'direction
                                                                  :tag-pair ~'tag-pair})
                                                        ~'e))))
                                                ~'v)
                                       ~'v)))
               {}
               ~'data)
              ~'data))

   `(~'defn ~'event-wire->idiom
     "Coerce wire-shape event data into idiomatic Clojure shape. Expects an
      event whose `:type` is the upstream string (e.g. \"session.start\").
      Idempotent: re-applying is a no-op."
     [{:keys [~'type] :as ~'event}]
     (~'if (~'map? (:data ~'event))
            (~'update ~'event :data (~'fn [~'d] (~'coerce-data ~'type ~'d :wire->idiom)))
            ~'event))

   `(~'defn ~'event-idiom->wire
     "Inverse of event-wire->idiom. Provided for symmetry — this SDK currently
      exposes no outbound `session.event` path, so this function is only
      invoked by the generated round-trip tests. External callers needing to
      serialize a Clojure-shaped event back to wire form may use it."
     [{:keys [~'type] :as ~'event}]
     (~'if (~'map? (:data ~'event))
            (~'update ~'event :data (~'fn [~'d] (~'coerce-data ~'type ~'d :idiom->wire)))
            ~'event))))

(defn emit-coerce-ns
  "Build the form list for the generated coerce namespace."
  [coercions]
  (concat
   [(emit-ns-form)]
   (emit-converter-defs)
   [(emit-converters-map)
    (emit-field-coercions coercions)]
   (emit-coerce-data)))
