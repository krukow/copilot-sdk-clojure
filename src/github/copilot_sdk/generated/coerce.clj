;; AUTO-GENERATED — do not edit. Run `bb codegen`.
;; Source: schemas/  (schema version pinned in .copilot-schema-version)

(ns github.copilot-sdk.generated.coerce "AUTO-GENERATED — do not edit. Run `bb codegen`.\n\n   Per-event-type coercion between the upstream wire shape and the\n   Clojure-idiomatic public API. Source: script/codegen/coercions.edn")

(defn iso-string->instant "Parse an ISO-8601 timestamp string to a java.time.Instant. nil-safe and\n      idempotent: returns nil for nil, the same Instant for an Instant input,\n      and throws ex-info for any other value class." [v] (cond (nil? v) nil (instance? java.time.Instant v) v (string? v) (java.time.Instant/parse v) :else (throw (ex-info "Expected ISO string or Instant" {:value v, :value-class (class v)}))))

(defn instant->iso-string "Render a java.time.Instant as an ISO-8601 string. nil-safe and\n      idempotent: returns nil for nil, the same string for a string input,\n      and throws ex-info for any other value class." [v] (cond (nil? v) nil (string? v) v (instance? java.time.Instant v) (.toString v) :else (throw (ex-info "Expected Instant or ISO string" {:value v, :value-class (class v)}))))

(def ^{:private true} auto-tiers #{:intelligence :balance :efficiency})

(defn auto-tier-string->keyword "Convert a wire auto-tier string to its closed idiomatic keyword domain." [v] (let [tier (cond (nil? v) nil (keyword? v) v (string? v) (keyword v) :else (throw (ex-info "Expected auto-tier string or keyword" {:value v, :value-class (class v)})))] (when-not (or (nil? tier) (contains? auto-tiers tier)) (throw (ex-info "Unknown auto-tier value" {:value v}))) tier))

(defn auto-tier-keyword->string "Convert an idiomatic auto-tier keyword to its closed wire enum domain." [v] (let [tier (auto-tier-string->keyword v)] (when tier (name tier))))

(def ^{:private true} converters "Map of [wire-tag idiom-tag] → {:wire->idiom fn :idiom->wire fn}." {[:iso-string :instant] {:wire->idiom iso-string->instant, :idiom->wire instant->iso-string}, [:auto-tier-string :auto-tier-keyword] {:wire->idiom auto-tier-string->keyword, :idiom->wire auto-tier-keyword->string}})

(def field-coercions "Per-event-type field coercion table. Generated from\n   script/codegen/coercions.edn." {"assistant.usage" {:cache-expires-at [:iso-string :instant]}, "session.resume" {:auto-tier [:auto-tier-string :auto-tier-keyword]}, "session.start" {:auto-tier [:auto-tier-string :auto-tier-keyword], :start-time [:iso-string :instant]}})

(defn coerce-data "Apply coercions to a `data` map for the given event-type and direction\n      (:wire->idiom or :idiom->wire). Unknown event types and unknown fields\n      pass through unchanged. Each converter is nil-safe and idempotent so\n      the same coercion can be applied twice without corruption.\n\n      If a converter throws (e.g. malformed wire payload), the exception is\n      re-thrown as ex-info with `:event-type`, `:field`, and `:direction`\n      added to ex-data so callers can diagnose without inspecting the\n      converter source." [event-type data direction] (if-let [fields (get field-coercions event-type)] (reduce-kv (fn [acc k v] (assoc acc k (if-let [tag-pair (get fields k)] (if-let [f (get-in converters [tag-pair direction])] (try (f v) (catch Exception e (throw (ex-info (str "Coercion failed for " event-type "/" k " (" direction "): " (.getMessage e)) (merge (or (ex-data e) {}) {:event-type event-type, :field k, :direction direction, :tag-pair tag-pair}) e)))) v) v))) {} data) data))

(defn event-wire->idiom "Coerce wire-shape event data into idiomatic Clojure shape. Expects an\n      event whose `:type` is the upstream string (e.g. \"session.start\").\n      Idempotent: re-applying is a no-op." [{:keys [type], :as event}] (if (map? (:data event)) (update event :data (fn [d] (coerce-data type d :wire->idiom))) event))

(defn event-idiom->wire "Inverse of event-wire->idiom. Provided for symmetry — this SDK currently\n      exposes no outbound `session.event` path, so this function is only\n      invoked by the generated round-trip tests. External callers needing to\n      serialize a Clojure-shaped event back to wire form may use it." [{:keys [type], :as event}] (if (map? (:data event)) (update event :data (fn [d] (coerce-data type d :idiom->wire))) event))
