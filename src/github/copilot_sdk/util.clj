(ns github.copilot-sdk.util
  "Utility functions for the Copilot SDK."
  (:require [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]))

(defn ^:no-doc github-token-auth-conflict?
  [config]
  (and (map? config)
       (contains? config :github-token)
       (contains? config :github-token-provider)))

;; -----------------------------------------------------------------------------
;; Key conversion utilities
;; Convert between wire format (camelCase) and Clojure idiom (kebab-case)
;; -----------------------------------------------------------------------------

(defn- simple-lowercase-keyword?
  [k allow-hyphen? allow-digits?]
  (when (and (keyword? k) (nil? (namespace k)))
    (let [^String value (name k)
          length (.length value)]
      (and (pos? length)
           (loop [index 0
                  previous-hyphen? false]
             (if (= index length)
               true
               (let [character (.charAt value index)
                     hyphen? (= character \-)]
                 (cond
                   (or (<= (int \a) (int character) (int \z))
                       (and allow-digits?
                            (<= (int \0) (int character) (int \9))))
                   (recur (inc index) false)

                   (and allow-hyphen?
                        hyphen?
                        (pos? index)
                        (< index (dec length))
                        (not previous-hyphen?))
                   (recur (inc index) true)

                   :else
                   false))))))))

(defn- keyword->camel
  [k]
  (cond
    (simple-lowercase-keyword? k false true) k
    (keyword? k) (csk/->camelCaseKeyword k)
    :else k))

(defn- keyword->kebab
  [k]
  (cond
    (simple-lowercase-keyword? k true false) k
    (keyword? k) (csk/->kebab-case-keyword k)
    :else k))

(defn ->wire-keys
  "Convert map keys from kebab-case to camelCase for wire format.
   Works recursively on nested maps. Non-keyword keys are preserved."
  [m]
  (cske/transform-keys keyword->camel m))

(defn ->clj-keys
  "Convert map keys from camelCase to kebab-case for Clojure idiom.
   Works recursively on nested maps. Non-keyword keys are preserved."
  [m]
  (cske/transform-keys keyword->kebab m))

(defn wire->clj
  "Convert a wire-format map to Clojure idiom.
   Alias for ->clj-keys."
  [m]
  (->clj-keys m))

(defn clj->wire
  "Convert a Clojure idiom map to wire format.
   Alias for ->wire-keys."
  [m]
  (->wire-keys m))

;; -----------------------------------------------------------------------------
;; System prompt section key mapping
;; Wire uses snake_case identifiers (e.g., "tool_efficiency");
;; Clojure SDK uses kebab-case keywords (e.g., :tool-efficiency).
;; camel-snake-kebab converts snake_case to kebab-case and vice versa,
;; but ->camelCase would produce "toolEfficiency" which is wrong.
;; We need explicit mappings for correct round-tripping.
;; -----------------------------------------------------------------------------

(def section-key->wire
  "Map from Clojure keyword to wire string for system message sections."
  {:preamble             "preamble"
   :identity             "identity"
   :tone                 "tone"
   :tool-efficiency      "tool_efficiency"
   :environment-context  "environment_context"
   :code-change-rules    "code_change_rules"
   :guidelines           "guidelines"
   :safety               "safety"
   :tool-instructions    "tool_instructions"
   :custom-instructions  "custom_instructions"
   :runtime-instructions "runtime_instructions"
   :last-instructions    "last_instructions"})

(def wire->section-key
  "Map from wire string to Clojure keyword for system prompt sections."
  (into {} (map (fn [[k v]] [v k])) section-key->wire))

(defn section-kw->wire-id
  "Convert a section keyword to its wire string ID.
   Known sections use the explicit mapping; unknown sections
   fall back to (name kw) for extensibility."
  [kw]
  (get section-key->wire kw (name kw)))

(defn wire-id->section-kw
  "Convert a wire string section ID to its Clojure keyword.
   Known sections use the explicit mapping; unknown sections
   fall back to (keyword id) for extensibility."
  [id]
  (get wire->section-key id (keyword id)))

;; MCP server config keys use an :mcp- prefix in Clojure for clarity
;; (e.g., :mcp-command, :mcp-args, :mcp-tools) but the upstream wire
;; protocol expects bare names (command, args, tools, url, headers, type, timeout).
;; This mapping strips the mcp- prefix before applying camelCase conversion.
(def ^:private mcp-key-renames
  {:mcp-command :command
   :mcp-args :args
   :mcp-tools :tools
   :mcp-server-type :type
   :mcp-timeout :timeout
   :mcp-url :url
   :mcp-headers :headers
   :mcp-defer-tools :defer-tools})

(defn mcp-server->wire
  "Convert a single MCP server config from Clojure idiom to wire format.
   Strips the :mcp- prefix from MCP-specific keys, then converts remaining
   keys to camelCase. Keyword values for :type and :defer-tools are converted
   to strings.
   Example: {:mcp-command \"node\" :mcp-args [\"x\"] :mcp-server-type :http}
   becomes {:command \"node\" :args [\"x\"] :type \"http\"} (camelCase keyword
   keys, with :type / :defer-tools values stringified)."
  [m]
  (let [renamed (reduce-kv (fn [acc k v]
                             (assoc acc (get mcp-key-renames k k) v))
                           {}
                           m)
        ;; Convert keyword values for :type to strings (upstream expects string)
        stringified (cond-> renamed
                      (keyword? (:type renamed))
                      (update :type name)

                      (keyword? (:defer-tools renamed))
                      (update :defer-tools name))]
    (clj->wire stringified)))

(defn mcp-servers->wire
  "Convert MCP servers map from Clojure idiom to wire format.
   Each server value has :mcp-* prefixed keys stripped before camelCase conversion."
  [servers]
  (into {} (map (fn [[k v]] [(if (keyword? k) (subs (str k) 1) k)
                             (mcp-server->wire v)]))
        servers))

;; -----------------------------------------------------------------------------
;; Attachment wire conversion
;; -----------------------------------------------------------------------------

(defn attachment->wire
  "Convert an attachment from Clojure format to wire format.
   Handles the special mapping for selection attachments where
   :file-path -> filePath and :selection-range -> selection."
  [att]
  (case (:type att)
    :selection
    (cond-> {:type "selection"
             :filePath (:file-path att)
             :displayName (:display-name att)}
      (:selection-range att) (assoc :selection (clj->wire (:selection-range att)))
      (:text att) (assoc :text (:text att)))

    :github-reference
    {:type "github_reference"
     :number (:number att)
     :title (:title att)
     :referenceType (name (:reference-type att))
     :state (:state att)
     :url (:url att)}

    :blob
    (cond-> {:type "blob"
             :data (:data att)
             :mimeType (:mime-type att)}
      (:display-name att) (assoc :displayName (:display-name att)))

    ;; :file and :directory
    (cond-> {:type (name (:type att))
             :path (:path att)}
      (:display-name att) (assoc :displayName (:display-name att)))))

(defn attachments->wire
  "Convert a vector of attachments to wire format."
  [attachments]
  (mapv attachment->wire attachments))

(defn context-tier->wire
  "Convert a Clojure :context-tier keyword to the wire string value.
   The CLI expects \"default\" / \"long_context\" (underscore), so csk
   camelCasing would produce the wrong value — we map explicitly."
  [tier]
  (case tier
    :default "default"
    :long-context "long_context"
    nil nil
    (throw (ex-info "Invalid :context-tier value (expected :default or :long-context)"
                    {:context-tier tier}))))

(defn ^:no-doc model-capabilities->wire
  "Convert a ModelCapabilitiesOverride from the Clojure idiom to its exact
  mixed-case runtime shape.

  Canonical input uses `:supports` / `:limits`. The deprecated
  `:model-supports` / `:model-limits` aliases are normalized to the same wire
  shape. Supplying both names for one branch is ambiguous and rejected."
  [capabilities]
  (when (and (contains? capabilities :supports)
             (contains? capabilities :model-supports))
    (throw (ex-info "Model capabilities cannot contain both :supports and :model-supports"
                    {:model-capabilities capabilities})))
  (when (and (contains? capabilities :limits)
             (contains? capabilities :model-limits))
    (throw (ex-info "Model capabilities cannot contain both :limits and :model-limits"
                    {:model-capabilities capabilities})))
  (let [legacy-supports? (contains? capabilities :model-supports)
        legacy-limits? (contains? capabilities :model-limits)
        supports (if legacy-supports?
                   (:model-supports capabilities)
                   (:supports capabilities))
        limits (if legacy-limits?
                 (:model-limits capabilities)
                 (:limits capabilities))
        vision (when limits
                 (if legacy-limits?
                   (:vision-capabilities limits)
                   (:vision limits)))]
    (cond-> {}
      (or (contains? capabilities :supports) legacy-supports?)
      (assoc :supports
             (cond-> {}
               (contains? supports (if legacy-supports? :supports-vision :vision))
               (assoc :vision
                      (get supports (if legacy-supports? :supports-vision :vision)))
               (contains? supports
                          (if legacy-supports?
                            :supports-reasoning-effort
                            :reasoning-effort))
               (assoc :reasoningEffort
                      (get supports
                           (if legacy-supports?
                             :supports-reasoning-effort
                             :reasoning-effort)))
               (and (not legacy-supports?)
                    (contains? supports :adaptive-thinking))
               (assoc "adaptive_thinking"
                      (let [value (:adaptive-thinking supports)]
                        (if (keyword? value) (name value) value)))))

      (or (contains? capabilities :limits) legacy-limits?)
      (assoc :limits
             (cond-> {}
               (contains? limits :max-prompt-tokens)
               (assoc "max_prompt_tokens" (:max-prompt-tokens limits))
               (and (not legacy-limits?) (contains? limits :max-output-tokens))
               (assoc "max_output_tokens" (:max-output-tokens limits))
               (contains? limits :max-context-window-tokens)
               (assoc "max_context_window_tokens" (:max-context-window-tokens limits))
               (contains? limits (if legacy-limits? :vision-capabilities :vision))
               (assoc :vision
                      (cond-> {}
                        (contains? vision :supported-media-types)
                        (assoc "supported_media_types" (:supported-media-types vision))
                        (contains? vision :max-prompt-images)
                        (assoc "max_prompt_images" (:max-prompt-images vision))
                        (contains? vision :max-prompt-image-size)
                        (assoc "max_prompt_image_size" (:max-prompt-image-size vision)))))))))

;; -----------------------------------------------------------------------------
;; Event type normalization
;; -----------------------------------------------------------------------------

(defn event-type->keyword
  "Normalize event :type values to namespaced keywords.
   Example: \"assistant.message_delta\" -> :copilot/assistant.message_delta."
  [event-type]
  (cond
    (keyword? event-type)
    (if (namespace event-type)
      event-type
      (keyword "copilot" (name event-type)))
    (string? event-type) (keyword "copilot" event-type)
    :else event-type))
