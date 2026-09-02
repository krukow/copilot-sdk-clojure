(ns codegen.emit-specs
  "Emit clojure.spec forms for JSON Schema object definitions.

   Output structure (for each event variant):
     :github.copilot-sdk.generated.event-specs/<prop>          ;; per-property leaf spec
     :github.copilot-sdk.generated.event-specs/<event>-data    ;; the `data` payload spec
     :github.copilot-sdk.generated.event-specs/<event>         ;; the full envelope spec
   Plus an aggregate `::event` spec that accepts any of the variants.

   Translation rules (intentionally limited; falls back to `any?` for
   anything we don't model precisely):

   - `string`            → `string?` (or set literal for enum/const)
   - `string` + format=date-time → `string?`  (we keep ISO strings as-is)
   - `string` + format=uuid      → `string?`
   - `number` / `integer`→ `number?` / `integer?`
   - `boolean`           → `boolean?`
   - `array`             → `(s/coll-of <items>)`
   - top-level `data` / envelope objects with properties
     → `(s/keys :req-un [...] :opt-un [...])`
   - nested `object` nodes with declared `properties`, reached via `$ref`
     → registered once as a named `::<definition>-shape` spec (see
     `register-object-shape!`) enforcing required-key presence, recursive
     validity of every known property, and (when the schema declares
     `additionalProperties: false`) rejection of unknown keys. Registering
     (instead of inlining at every occurrence) keeps generated forms small
     when the same definition recurs across many event variants.
   - nested `object` nodes with declared `properties` reached *inline*
     (i.e., not via `$ref` — currently none flow through this path; see
     `emit-object`) → an inline `(s/and map? ...)` form with the same
     structural predicates, built but not separately registered.
   - nested `object` nodes *without* declared `properties` (dictionary-style
     or otherwise opaque objects) → `map?`, left open
   - `anyOf` (incl. nullable)   → `(s/or ...)` or `(s/nilable ...)`
   - Otherwise / less precise cases → `any?`

   Unqualified keys in `s/keys` use namespace-qualified spec keywords; the
   :req-un / :opt-un mechanism then matches the unqualified name part against
   actual map keys (e.g. `:session-id`). Since wire payloads pass through
   `util/wire->clj` before reaching specs, all keys are kebab-case at this
   point."
  (:require [codegen.core :as cc]
            [clojure.string :as str]))

(def ^:private ns-name "github.copilot-sdk.generated.event-specs")

(defn- ns-kw
  "Build a keyword in the generated namespace."
  [name-part]
  (keyword ns-name name-part))

;; ---------------------------------------------------------------------------
;; Nested-object shape registry
;; ---------------------------------------------------------------------------
;; A `$ref`'d object definition with declared `:properties` is registered
;; once as a named `::<definition>-shape` spec rather than inlined at every
;; occurrence. The same definition can recur across many event variants
;; (every event's envelope `:data` property references a distinct Data
;; definition — see `collect-leaf-properties`), and inlining a full
;; recursive structural predicate at each occurrence multiplies generated
;; bytecode enough to exceed the JVM's per-method size limit. Both atoms are
;; reset per `emit-event-specs-ns` invocation (see below); a fresh process
;; runs codegen exactly once, so this is defensive rather than load-bearing.

(def ^:private object-registry
  "`$ref` string -> registered shape spec keyword."
  (atom {}))

(def ^:private object-defs
  "Ordered `[kw form]` pairs accumulated as object shapes are registered."
  (atom []))

(defn- ref->shape-kw
  "Build the registered shape spec keyword for a `$ref` string such as
   `#/definitions/AssistantMessageToolRequestCaller`."
  [ref]
  (-> ref (str/split #"/") last cc/wire-key->kebab (str "-shape") ns-kw))

;; ---------------------------------------------------------------------------
;; Type emission
;; ---------------------------------------------------------------------------

(declare emit-type)

(defn- emit-string [node]
  (cond
    (:enum node)  (set (:enum node))
    (:const node) #{(:const node)}
    :else         `string?))

(defn- emit-number
  [node predicate]
  (let [bounds
        (cond-> []
          (contains? node :minimum)
          (conj `(~'fn [~'n] (~'<= ~(:minimum node) ~'n)))

          (contains? node :exclusiveMinimum)
          (conj `(~'fn [~'n] (~'< ~(:exclusiveMinimum node) ~'n)))

          (contains? node :maximum)
          (conj `(~'fn [~'n] (~'<= ~'n ~(:maximum node))))

          (contains? node :exclusiveMaximum)
          (conj `(~'fn [~'n] (~'< ~'n ~(:exclusiveMaximum node)))))]
    (if (seq bounds)
      `(~'s/and ~predicate ~@bounds)
      predicate)))

(def ^:private json-number-predicate
  `(~'s/and
    ~'number?
    (~'fn [~'n]
      (~'and
       (~'not (~'ratio? ~'n))
       (~'cond
        (~'instance? Double ~'n) (Double/isFinite ~'n)
        (~'instance? Float ~'n) (Float/isFinite ~'n)
        :else true)))))

(defn- emit-array [root node]
  (let [items (:items node)]
    (if items
      `(~'s/coll-of ~(emit-type root items))
      `(~'s/coll-of any?))))

(defn- emit-anyOf [root node]
  (let [branches  (:anyOf node)
        non-null  (remove #(= "null" (:type %)) branches)
        nullable? (some  #(= "null" (:type %)) branches)
        emit-br   (fn [b] (emit-type root b))
        union     (cond
                    (empty? non-null)       `any?
                    (= 1 (count non-null))  (emit-br (first non-null))
                    :else
                    `(~'s/or ~@(mapcat (fn [i b]
                                         [(keyword (str "branch-" i)) (emit-br b)])
                                       (range)
                                       non-null)))]
    (if nullable?
      `(~'s/nilable ~union)
      union)))

(defn- emit-object
  "Build a structural `(s/and map? ...)` form for an object node with
   declared `:properties`. This is the low-level form builder used both
   directly for inline object nodes and, via `register-object-shape!`, to
   populate a named registered spec for `$ref`'d object nodes. Handles
   arbitrary nesting depth through ordinary recursion into `emit-type`:

   - one predicate per declared property enforcing recursive validity via
     `emit-type` — required properties are checked unconditionally
     (`contains?` + `s/valid?`), optional properties only when present
   - when the schema declares `additionalProperties: false` (checked with
     `false?`, so an absent `additionalProperties` — meaning \"open\" per
     JSON Schema — is correctly left alone), an extra predicate rejecting
     any key not in the declared property set

   Properties are iterated in sorted-by-name order for deterministic
   generated output across regenerations."
  [root node]
  (let [props       (:properties node)
        required    (set (:required node))
        kebab       (fn [k] (cc/wire-key->kebab (name k)))
        prop-info   (->> props
                         (map (fn [[k v]]
                                {:kw   (keyword (kebab k))
                                 :form (emit-type root v)
                                 :req? (contains? required (name k))}))
                         (sort-by (comp name :kw)))
        prop-preds  (for [{:keys [kw form req?]} prop-info]
                      (if req?
                        `(~'fn [~'m]
                           (~'and (~'contains? ~'m ~kw)
                                  (~'s/valid? ~form (~kw ~'m))))
                        `(~'fn [~'m]
                           (~'or (~'not (~'contains? ~'m ~kw))
                                 (~'s/valid? ~form (~kw ~'m))))))
        closed?     (false? (:additionalProperties node))
        closed-pred (when closed?
                      (let [allowed (set (map :kw prop-info))]
                        `(~'fn [~'m] (~'every? ~allowed (~'keys ~'m)))))]
    `(~'s/and map? ~@prop-preds ~@(when closed-pred [closed-pred]))))

(defn- register-object-shape!
  "Look up or register a named shape spec for a `$ref`'d object node with
   declared `:properties`, returning its spec keyword. Reserves the keyword
   in `object-registry` *before* building the structural form so a
   (currently nonexistent, but not schema-forbidden) cycle back to the same
   `$ref` resolves to the keyword instead of recursing forever."
  [root ref node]
  (or (get @object-registry ref)
      (let [kw (ref->shape-kw ref)]
        (swap! object-registry assoc ref kw)
        (swap! object-defs conj [kw (emit-object root node)])
        kw)))

(defn emit-type
  [root node]
  (let [ref  (:$ref node)
        node (cc/deref-once root node)]
    (cond
      (:anyOf node)              (emit-anyOf root node)
      (= "string"  (:type node)) (emit-string node)
      (= "integer" (:type node)) (emit-number node `integer?)
      (= "number"  (:type node)) (emit-number node json-number-predicate)
      (= "boolean" (:type node)) `boolean?
      (= "null"    (:type node)) `nil?
      (= "array"   (:type node)) (emit-array root node)
      (= "object"  (:type node)) (if (:properties node)
                                   (if ref
                                     (register-object-shape! root ref node)
                                     (emit-object root node))
                                   `map?)          ;; opaque/dictionary object → stays open
      :else                      `any?)))

;; ---------------------------------------------------------------------------
;; Leaf-property collection
;; ---------------------------------------------------------------------------
;; Approach:
;;   - Walk every variant's envelope properties + data.properties.
;;   - For each unique kebab property name, collect all observed schema nodes.
;;   - If they all yield the same emitted spec form → use it.
;;   - Otherwise → emit a non-conforming union and add variant-local predicates.

(defn- walk-props
  "Collect [kebab-name schema-node] tuples from an object schema's properties."
  [props]
  (for [[wire-k node] props]
    [(cc/wire-key->kebab (name wire-k)) node]))

(defn- collect-leaf-properties
  "Return a map describing emitted leaf-property forms.

   `:leaf-map`         — sorted-by-name map of kebab-name → emitted spec form.
                         When the same property name maps to multiple distinct
                         schemas across event/data payloads, emit a
                         non-conforming union — a `(fn [v] (or (s/valid? f1 v)
                         ...))` wrapper. We avoid `s/or` because it conforms
                         values, which would break the envelope spec's
                         `s/and` chain (the per-event `:type` predicate
                         inspects the raw map and would see a conformed
                         `[:v0 ...]` tuple).

   `:env-form-by-kebab` — kebab-name → strict spec form derived from envelope
                         occurrences only (i.e., excluding nested `data`).
                         Used by the envelope emitter to enforce the
                         envelope-specific shape independent of any weakening
                         introduced by data-side conflicts.

   `:conflicted`       — set of kebab-names whose `:leaf-map` form is a
                         non-conforming union because some data payload
                         contributes a schema not already present on the
                         envelope side (an *envelope-vs-data* collision).
                         Envelope emission adds a per-property strict
                         predicate for most envelope keys appearing in this
                         set, so e.g. envelope `id` (UUID string) is not
                         weakened by a data-payload `id` (positive integer).
                         `:type` and `:data` are always members of this set
                         too (both collide with same-named data-payload
                         properties on the schemas we generate from), but
                         `emit-envelope-spec` skips emitting a strict-pred
                         for either: any envelope property this specific
                         variant declares with a `const` (chiefly `:type`)
                         is already pinned to its exact literal by
                         `const-preds`, and `:data` is always covered by the
                         trailing per-variant `data-kw` predicate. Both
                         existing checks are strictly more precise than a
                         redundant union-of-every-variant strict-pred, which
                         would otherwise bloat every envelope `s/def` form
                         with a huge, information-free union (one branch per
                         event type) — large enough, in practice, to trip
                         the JVM's 64KB-per-method bytecode limit.

   `:data-conflicted`  — set of kebab-names whose global `:leaf-map` form is
                         a non-conforming union and which occur in at least
                         one data payload. Data emission adds a variant-local
                         strict predicate for every data key in this set, so
                         both envelope/data collisions (`id`) and collisions
                         across data variants (`reason`) retain the exact
                         schema declared by each event."
  [root variants]
  (let [env-pairs  (mapcat (fn [{:keys [variant]}]
                             (walk-props (:properties variant)))
                           variants)
        data-pairs (mapcat (fn [{:keys [variant]}]
                             (let [data-node (cc/deref-once root (get-in variant [:properties :data]))]
                               (walk-props (:properties data-node))))
                           variants)
        all-pairs  (concat env-pairs data-pairs)
        groups     (group-by first all-pairs)
        env-groups (group-by first env-pairs)
        data-groups (group-by first data-pairs)
        ;; Strict, side-only forms per kebab name. If the side's variants
        ;; disagree (rare), fall back to a non-conforming union of side-only
        ;; forms.
        side-form
        (fn [groups-side]
          (into {}
                (for [[kebab pairs] groups-side
                      :let [forms (vec (distinct (mapv #(emit-type root (second %)) pairs)))]]
                  [kebab
                   (if (= 1 (count forms))
                     (first forms)
                     (let [v (gensym "v")]
                       `(~'s/spec
                         (~'fn [~v]
                          (~'or ~@(map (fn [f] `(~'s/valid? ~f ~v)) forms))))))])))
        env-form-by-kebab  (side-form env-groups)
        ;; Per-kebab distinct envelope/data forms — used to decide whether a
        ;; key's leaf-union is *truly* weakened from the envelope side (i.e.,
        ;; the data side contributes a form not already present there).
        env-forms-by-kebab
        (into {} (for [[k pairs] env-groups]
                   [k (set (map #(emit-type root (second %)) pairs))]))
        data-forms-by-kebab
        (into {} (for [[k pairs] data-groups]
                   [k (set (map #(emit-type root (second %)) pairs))]))
        env-conflicted  (atom #{})
        data-conflicted (atom #{})
        leaf-map
        (into (sorted-map)
              (for [[kebab pairs] groups
                    :let [nodes (mapv second pairs)
                          forms (mapv #(emit-type root %) nodes)
                          uniq  (vec (distinct forms))]]
                (if (= 1 (count uniq))
                  [kebab (first uniq)]
                  (let [v (gensym "v")
                        union-form `(~'s/spec
                                     (~'fn [~v]
                                      (~'or ~@(map (fn [f] `(~'s/valid? ~f ~v)) uniq))))
                        env-fs  (env-forms-by-kebab kebab #{})
                        data-fs (data-forms-by-kebab kebab #{})
                        ;; Only flag each side as "weakened" if the *other*
                        ;; side contributes a form not already present here.
                        env-weakened?  (and (seq env-fs)
                                            (some #(not (contains? env-fs %)) data-fs))
                        data-conflict? (seq data-fs)]
                    (when env-weakened?  (swap! env-conflicted conj kebab))
                    (when data-conflict? (swap! data-conflicted conj kebab))
                    (binding [*out* *err*]
                      (println (format "INFO: property '%s' has %d distinct schemas — emitting non-conforming union%s%s"
                                       kebab (count uniq)
                                       (if env-weakened?  " (envelope-conflicted)" "")
                                       (if data-conflict? " (data strict-pred added)"     ""))))
                    [kebab union-form]))))]
    {:leaf-map           leaf-map
     :env-form-by-kebab  env-form-by-kebab
     :conflicted         @env-conflicted
     :data-conflicted    @data-conflicted}))

;; ---------------------------------------------------------------------------
;; Emission
;; ---------------------------------------------------------------------------

(defn- emit-leaf-defs
  "Emit `(s/def ::<kebab> <form>)` for every leaf property. `leaf-map`
   values are pre-emitted spec forms (see `collect-leaf-properties`)."
  [leaf-map]
  (for [[kebab form] leaf-map]
    `(~'s/def ~(ns-kw kebab) ~form)))

(defn- emit-data-spec
  "Emit `(s/def ::<event>-data ...)` for one event's data payload.

   When a data property's name has different schemas elsewhere, the global
   leaf spec is a non-conforming union — see `collect-leaf-properties`.
   Without intervention the data `s/keys` would accept the weakened union
   (e.g. abort `:reason` would accept any string after another event adds an
   open string-valued reason). For each data key in `data-conflicted`, emit an
   extra predicate validating against that event variant's property schema.
   Required keys are validated unconditionally; optional keys only when
   present."
  [root variant data-conflicted]
  (let [event-type (get-in variant [:properties :type :const])
        data-node  (cc/deref-once root (get-in variant [:properties :data]))
        props      (:properties data-node)
        required   (set (:required data-node))
        kebab      (fn [k] (cc/wire-key->kebab (name k)))
        ;; Sort by name for deterministic emission across Clojure hash variants.
        req-keys   (->> props
                        (filter (fn [[k _]] (contains? required (name k))))
                        (map    (fn [[k _]] (ns-kw (kebab k))))
                        (sort-by name)
                        vec)
        opt-keys   (->> props
                        (filter (fn [[k _]] (not (contains? required (name k)))))
                        (map    (fn [[k _]] (ns-kw (kebab k))))
                        (sort-by name)
                        vec)
        keys-form  (cond-> `(~'s/keys)
                     (seq req-keys) (concat [:req-un req-keys])
                     (seq opt-keys) (concat [:opt-un opt-keys])
                     true           seq)
        strict-preds (->> props
                          (keep (fn [[k node]]
                                  (let [kb        (kebab k)
                                        data-form (emit-type root node)]
                                    (when (contains? data-conflicted kb)
                                      [kb data-form (contains? required (name k))]))))
                          (sort-by first)
                          (map (fn [[prop-name data-form req?]]
                                 (let [getter `(~(keyword prop-name) ~'data)]
                                   (if req?
                                     `(~'fn [~'data]
                                        (~'s/valid? ~data-form ~getter))
                                     `(~'fn [~'data]
                                        (~'or (~'not (~'contains? ~'data ~(keyword prop-name)))
                                              (~'s/valid? ~data-form ~getter))))))))]
    (if (seq strict-preds)
      `(~'s/def ~(ns-kw (str event-type "-data"))
                (~'s/and ~keys-form ~@strict-preds))
      `(~'s/def ~(ns-kw (str event-type "-data")) ~keys-form))))

(defn- emit-envelope-spec
  "Emit the full envelope spec `(s/def ::<event> ...)`. Uses `s/and` to
   combine the structural `s/keys` (presence + leaf types) with predicates
   that bind every envelope property declaring a JSON Schema `const` value
   to that literal (e.g. `:type` for the variant, `:ephemeral true` for
   variants like `session.idle`), and a final predicate that delegates
   `:data` validation to the variant's `::<event>-data` spec, so envelopes
   from one event variant cannot validate against another.

   When an envelope property's name conflicts with a data-payload property
   that has a different schema, the global leaf spec is a non-conforming
   union (e.g. envelope `id` is a UUID string, but `session.schedule_*`
   data payloads use a positive-integer `id`). Without intervention the
   envelope `s/keys` would accept the weakened union. We therefore emit an
   extra predicate per conflicted envelope key validating it against the
   strict envelope-only form (`env-form-by-kebab`) — except for two kebabs
   that are always in `conflicted` but already have a strictly more precise
   check elsewhere in this same `s/and`, so re-checking them here would be
   pure bloat (previously producing envelope `s/def` forms large enough to
   trip the JVM's 64KB-per-method bytecode limit):
     - any property this variant declares with a JSON Schema `const`
       (chiefly `:type`) — already pinned exactly by `const-preds`, which is
       strictly stronger than a union over every variant's literal;
     - `:data` — already validated against this variant's own
       `::<event>-data` spec by the trailing `data-kw` predicate, which is
       strictly stronger than a union over every variant's data shape (that
       union would spuriously accept `:data` shaped like *any* other event
       type)."
  [variant env-form-by-kebab conflicted]
  (let [event-type (get-in variant [:properties :type :const])
        envelope   (:properties variant)
        required   (set (:required variant))
        kebab      (fn [k] (cc/wire-key->kebab (name k)))
        req-keys   (->> envelope
                        (filter (fn [[k _]] (contains? required (name k))))
                        (map    (fn [[k _]] (ns-kw (kebab k))))
                        (sort-by name)
                        vec)
        opt-keys   (->> envelope
                        (filter (fn [[k _]] (not (contains? required (name k)))))
                        (map    (fn [[k _]] (ns-kw (kebab k))))
                        (sort-by name)
                        vec)
        keys-form  (cond-> `(~'s/keys)
                     (seq req-keys) (concat [:req-un req-keys])
                     (seq opt-keys) (concat [:opt-un opt-keys])
                     true           seq)
        data-kw    (ns-kw (str event-type "-data"))
        ;; Emit one predicate per envelope property with a JSON Schema
        ;; `const` value. Sorted by property name for deterministic output.
        const-preds (->> envelope
                         (keep (fn [[k v]]
                                 (when (contains? v :const)
                                   [(kebab k) (:const v)])))
                         (sort-by first)
                         (map (fn [[prop-name const-val]]
                                `(~'fn [~'event]
                                   (= ~const-val
                                      (~(keyword prop-name) ~'event))))))
        ;; Strict per-property predicates for envelope keys whose global
        ;; leaf spec is a non-conforming union (i.e., weakened by a
        ;; data-payload conflict). Required keys are validated unconditionally;
        ;; optional keys are validated only when present.
        ;;
        ;; Two kebabs are always excluded here even when `conflicted` contains
        ;; them, because a variant-specific, strictly-more-precise check
        ;; already covers them elsewhere in this same `s/and`:
        ;;   - any property this *specific* variant declares with a JSON
        ;;     Schema `const` (chiefly `:type`) is already pinned to its
        ;;     exact literal by `const-preds` above; a strict-pred here would
        ;;     just re-check membership in the union of every variant's
        ;;     literal — provably weaker and pure bloat (one huge redundant
        ;;     union per envelope, multiplied across ~100+ event variants,
        ;;     is what previously produced multi-KB `s/def` forms large
        ;;     enough to trip the JVM's 64KB-per-method bytecode limit).
        ;;   - `"data"` is always covered by the trailing `data-kw` predicate
        ;;     below, which validates `:data` against *this* variant's own
        ;;     `::<event>-data` spec — strictly more precise than a union
        ;;     across every variant's data shape (which would spuriously
        ;;     accept `:data` belonging to any *other* event type).
        strict-preds (->> envelope
                          (keep (fn [[k v]]
                                  (let [kb       (kebab k)
                                        env-form (get env-form-by-kebab kb)]
                                    (when (and (contains? conflicted kb)
                                               env-form
                                               (not (contains? v :const))
                                               (not= kb "data"))
                                      [kb env-form (contains? required (name k))]))))
                          (sort-by first)
                          (map (fn [[prop-name env-form req?]]
                                 (let [getter `(~(keyword prop-name) ~'event)]
                                   (if req?
                                     `(~'fn [~'event]
                                        (~'s/valid? ~env-form ~getter))
                                     `(~'fn [~'event]
                                        (~'or (~'not (~'contains? ~'event ~(keyword prop-name)))
                                              (~'s/valid? ~env-form ~getter))))))))]
    `(~'s/def ~(ns-kw event-type)
              (~'s/and
                ~keys-form
                ~@const-preds
                ~@strict-preds
                (~'fn [~'event] (~'s/valid? ~data-kw (:data ~'event)))))))

(defn- emit-event-multi-spec
  "Emit a `defmulti` + `defmethod`s + aggregate `::event` spec that dispatches
   on the `:type` field. Using `s/multi-spec` (rather than `s/or`) keeps
   error messages variant-targeted and makes adding new event types O(1).
   Unknown event types fall through to the `:default` method which returns
   nil — `s/multi-spec` treats that as invalid."
  [variants]
  (let [mm-sym       'event-mm
        defmulti-form `(~'defmulti ~mm-sym :type)
        sorted        (sort-by :type variants)
        defmethods    (for [{:keys [type]} sorted]
                        `(~'defmethod ~mm-sym ~type [~'_]
                                       (~'s/get-spec ~(ns-kw type))))
        default-method `(~'defmethod ~mm-sym :default [~'_] nil)
        aggregate     `(~'s/def ~(ns-kw "event")
                                (~'s/multi-spec ~mm-sym :type))]
    (concat [defmulti-form] defmethods [default-method aggregate])))

(defn- emit-event-types-set
  "Emit a `def` containing the sorted set of all event-type strings."
  [variants]
  `(~'def ~'event-types
          "Set of all event-type strings known to the schema."
          ~(into (sorted-set) (map :type variants))))

;; ---------------------------------------------------------------------------
;; Public entry point
;; ---------------------------------------------------------------------------

(defn emit-event-specs-ns
  "Build the form list for the generated event-specs namespace."
  [root]
  (reset! object-registry {})
  (reset! object-defs [])
  (let [variants  (cc/collect-anyOf-discriminators root)
        ;; Sort variants by event-type for deterministic emission.
        sorted    (sort-by :type variants)
        {:keys [leaf-map env-form-by-kebab conflicted data-conflicted]}
        (collect-leaf-properties root variants)
        ;; `mapv` forces eager evaluation, so `data-specs`/`envelope-specs`
        ;; are bound before `@object-defs` is read below — every `emit-type`
        ;; call reachable from these (and from `collect-leaf-properties`
        ;; above) has already registered its nested object shapes by then.
        data-specs     (mapv #(emit-data-spec root (:variant %) data-conflicted) sorted)
        envelope-specs (mapv #(emit-envelope-spec (:variant %) env-form-by-kebab conflicted) sorted)
        object-shape-defs (for [[kw form] @object-defs]
                             `(~'s/def ~kw ~form))]
    (concat
      [`(~'ns ~(symbol ns-name)
              "AUTO-GENERATED. clojure.spec definitions for upstream session events.

   Each event variant's `data` payload is registered under
   `::<event-type>-data` (e.g. `::session.start-data`).
   The envelope (id/timestamp/parentId/type/data) is registered under
   `::<event-type>` (e.g. `::session.start`).

   Nested `$ref`'d object definitions (reached via properties on the above)
   are registered once each under `::<definition>-shape`
   (e.g. `::assistant-message-tool-request-caller-shape`).

   Source: schemas/session-events.schema.json"
              (:require [clojure.spec.alpha :as ~'s]))]
      ;; Registered object shapes must precede leaf defs: some leaf defs are
      ;; bare-keyword aliases (e.g. `(s/def ::citations ::citations-shape)`),
      ;; and unlike `s/keys`/`s/valid?` references inside `fn` bodies, a bare
      ;; keyword `s/def` form resolves its target spec *eagerly* at def time.
      object-shape-defs
      (emit-leaf-defs leaf-map)
      data-specs
      envelope-specs
      [(emit-event-types-set variants)]
      (emit-event-multi-spec variants))))
