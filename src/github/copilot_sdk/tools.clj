(ns github.copilot-sdk.tools
  "Helper functions for defining tools."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]))

(defn define-tool
  "Define a tool the CLI server can call during a session.
   
   Arguments:
   - name        - Tool name (string)
   - opts map:
     - :description             - Tool description
     - :parameters              - JSON schema for parameters (or nil)
     - :handler                 - Function (fn [args invocation] -> result).
                                  **Optional** since upstream PR #1308: when
                                  omitted, the tool is declaration-only and
                                  the runtime emits a
                                  `:copilot/external_tool.requested` event
                                  whenever the LLM calls it. Applications
                                  resolve the pending call by reading the
                                  `:request-id` from the event data and
                                  calling `copilot/handle-pending-tool-call!`
                                  (or the async ``copilot/<handle-pending-tool-call!``).
     - :overrides-built-in-tool - When true, explicitly overrides a built-in tool of the same name.
                                  Without this flag, name clashes with built-in tools cause an error.
     - :defer                   - `:auto` or `:never` (upstream PR #1632). Controls whether the tool may
                                  be deferred (loaded lazily via tool search) rather than always pre-loaded.
                                  `:auto` allows deferral; `:never` forces pre-loading. Defaults to `:auto`.
     - :metadata                - Opaque host-defined map forwarded to the runtime.
     - :is-terminal?            - When true, a successful call ends the agent turn
                                  instead of feeding the result back to the model.
   
   The handler (when provided) receives:
   - args       - The parsed arguments from the LLM (no key conversion)
   - invocation - Map with :session-id, :tool-call-id, :tool-name, :arguments,
                  and :cancel-chan. The cancellation channel closes when the
                  invocation completes, the runtime reports completion, or the
                  session is released. It may also contain optional
                  :available-tools current-tool metadata maps for
                  `tool_search_tool`. Each metadata map has :name, :description,
                  and optional :namespaced-name, :mcp-server-name,
                  :mcp-tool-name, :input-schema, :defer-loading.
   
   The handler should return one of:
   - A string (treated as success)
   - A map with :text-result-for-llm, :result-type, and optional
     :tool-references naming tools returned by a tool-search implementation
   - Any other value (JSON-encoded as success)
   - A core.async channel that will yield one of the above
   
   Example (with handler):
   ```clojure
   (define-tool \"get_weather\"
     {:description \"Get weather for a location\"
      :parameters {:type \"object\"
                   :properties {:location {:type \"string\"}}
                   :required [\"location\"]}
      :handler (fn [args _]
                 (str \"Weather in \" (:location args) \": Sunny, 72°F\"))})
   ```

   Example (declaration-only, manual resolution):
   ```clojure
   (define-tool \"get_weather\"
     {:description \"Get weather for a location\"
      :parameters {:type \"object\"
                   :properties {:location {:type \"string\"}}}})
   ;; Listen for :copilot/external_tool.requested events and resolve via
   ;; (copilot/handle-pending-tool-call! session {:request-id ... :result ...})
   ```"
  [name {:keys [description parameters handler overrides-built-in-tool defer metadata is-terminal?]}]
  (cond-> {:tool-name name
           :tool-description description
           :tool-parameters parameters}
    ;; Upstream PR #1308: handler is optional. Declaration-only tools (no
    ;; handler) are surfaced as external_tool.requested events; consumers
    ;; resolve them via handle-pending-tool-call!.
    (some? handler)
    (assoc :tool-handler handler)
    (some? overrides-built-in-tool)
    (assoc :overrides-built-in-tool overrides-built-in-tool)
    (some? defer)
    (assoc :defer defer)
    (some? metadata)
    (assoc :metadata metadata)
    (some? is-terminal?)
    (assoc :is-terminal? is-terminal?)))

(defn define-tool-from-spec
  "Define a tool using a clojure.spec for parameter validation.
    
   Parameters are validated against the spec at invocation time.
   Note: the spec is NOT auto-converted to JSON schema, so this tool
   has no parameter schema advertised to the model. For tools that need
   a parameter schema, use define-tool with an explicit JSON schema.
   
   Arguments:
   - name        - Tool name (string)
   - opts map:
     - :description             - Tool description
     - :spec                    - A clojure.spec for the arguments
     - :handler                 - Function (fn [args invocation] -> result).
                                  **Optional** since upstream PR #1308: when
                                  omitted, no `:tool-handler` is installed
                                  and the tool is declaration-only. The
                                  runtime emits a
                                  `:copilot/external_tool.requested` event
                                  on call; applications resolve it via
                                  `copilot/handle-pending-tool-call!`
                                  (or ``copilot/<handle-pending-tool-call!``).
                                  Note: when `:handler` is omitted, the
                                  `:spec` is also not used (no automatic
                                  validation occurs in the declaration-only
                                  path).
     - :overrides-built-in-tool - When true, overrides a built-in tool of the same name
     - :defer                   - `:auto` or `:never` (upstream PR #1632). When `:auto` the tool may be
                                  deferred (loaded lazily via tool search); `:never` forces pre-loading.
                                  Defaults to `:auto`.
     - :metadata                - Opaque host-defined map forwarded to the runtime.
     - :is-terminal?            - When true, a successful call ends the agent turn
                                  instead of feeding the result back to the model.

   The handler invocation map has the same shape documented by `define-tool`,
   including the required `:cancel-chan` lifecycle signal.

   Example (with handler):
   ```clojure
   (s/def ::location string?)
   (s/def ::get-weather-args (s/keys :req-un [::location]))
   
   (define-tool-from-spec \"get_weather\"
     {:description \"Get weather for a location\"
      :spec ::get-weather-args
      :handler (fn [args _]
                 (if (s/valid? ::get-weather-args args)
                   (str \"Weather: Sunny\")
                   {:text-result-for-llm (str \"Invalid args: \" (s/explain-str ::get-weather-args args))
                    :result-type \"failure\"}))})
   ```

   Example (declaration-only, manual resolution):
   ```clojure
   (define-tool-from-spec \"get_weather\"
     {:description \"Get weather for a location\"
      :spec ::get-weather-args})
   ;; Resolve pending calls via
   ;; (copilot/handle-pending-tool-call! session {:request-id ... :result ...})
   ```"
  [name {:keys [description spec handler overrides-built-in-tool defer metadata is-terminal?]}]
  ;; For now, we don't auto-convert spec to JSON schema
  ;; The handler should validate using the spec
  (cond-> {:tool-name name
           :tool-description description
           :tool-parameters nil}  ; User should provide JSON schema if needed
    ;; Upstream PR #1308: handler is optional. Declaration-only tools (no
    ;; handler) are surfaced as external_tool.requested events; consumers
    ;; resolve them via handle-pending-tool-call!.
    (some? handler)
    (assoc :tool-handler (fn [args invocation]
                           (if (and spec (not (s/valid? spec args)))
                             {:text-result-for-llm (str "Invalid arguments: " (s/explain-str spec args))
                              :result-type "failure"
                              :error "spec validation failed"}
                             (handler args invocation))))
    (some? overrides-built-in-tool)
    (assoc :overrides-built-in-tool overrides-built-in-tool)
    (some? defer)
    (assoc :defer defer)
    (some? metadata)
    (assoc :metadata metadata)
    (some? is-terminal?)
    (assoc :is-terminal? is-terminal?)))

(defn result-success
  "Create a successful tool result.

   `telemetry` is a map of string bucket names to JSON object maps. Nested
   values may contain JSON scalars, vectors, maps with string keys, and nil."
  ([text]
   (result-success text {}))
  ([text telemetry]
   {:text-result-for-llm text
    :result-type "success"
    :tool-telemetry telemetry}))

(defn result-failure
  "Create a failed tool result.

   `telemetry` follows the same recursive JSON object contract as
   `result-success`."
  ([text]
   (result-failure text nil))
  ([text error]
   (result-failure text error {}))
  ([text error telemetry]
   {:text-result-for-llm text
    :result-type "failure"
    :error error
    :tool-telemetry telemetry}))

(defn result-denied
  "Create a denied tool result (permission denied).

   `telemetry` follows the same recursive JSON object contract as
   `result-success`."
  ([text]
   (result-denied text {}))
  ([text telemetry]
   {:text-result-for-llm text
    :result-type "denied"
    :tool-telemetry telemetry}))

(defn result-rejected
  "Create a rejected tool result (user rejected).

   `telemetry` follows the same recursive JSON object contract as
   `result-success`."
  ([text]
   (result-rejected text {}))
  ([text telemetry]
   {:text-result-for-llm text
    :result-type "rejected"
    :tool-telemetry telemetry}))

(defn convert-mcp-call-tool-result
  "Convert an MCP CallToolResult into the SDK's ToolResultObject format.

   The input map should have Clojure-idiomatic keys:
   - :content    - vector of content blocks, each with :type and type-specific fields
   - :is-error   - optional boolean, when true the result-type is \"failure\"

   Content block types:
   - {:type \"text\" :text \"...\"}
   - {:type \"image\" :data \"base64...\" :mime-type \"image/png\"}
   - {:type \"resource\" :resource {:uri \"...\" :text \"...\" :blob \"...\" :mime-type \"...\"}}

   Returns a ToolResultObject map with :text-result-for-llm, :result-type, and
   optionally :binary-results-for-llm."
  [{:keys [content is-error]}]
  (let [text-parts (transient [])
        binary-results (transient [])]
    (doseq [block content]
      (case (:type block)
        "text"
        (when (string? (:text block))
          (conj! text-parts (:text block)))

        "image"
        (when (and (string? (:data block))
                   (seq (:data block))
                   (string? (:mime-type block)))
          (conj! binary-results {:data (:data block)
                                 :mime-type (:mime-type block)
                                 :type "image"}))

        "resource"
        (let [resource (:resource block)]
          (when (:text resource)
            (conj! text-parts (:text resource)))
          (when (:blob resource)
            (let [mt (:mime-type resource)]
              (conj! binary-results {:data (:blob resource)
                                     :mime-type (if (and (string? mt) (seq mt))
                                                  mt
                                                  "application/octet-stream")
                                     :type "resource"
                                     :description (:uri resource)}))))

        ;; Unknown content type — skip
        nil))
    (let [binaries (persistent! binary-results)]
      (cond-> {:text-result-for-llm (str/join "\n" (persistent! text-parts))
               :result-type (if is-error "failure" "success")}
        (seq binaries) (assoc :binary-results-for-llm binaries)))))
