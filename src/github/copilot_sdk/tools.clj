(ns github.copilot-sdk.tools
  "Helper functions for defining tools."
  (:require [clojure.spec.alpha :as s]))

(defn define-tool
  "Define a tool with a handler function.
   
   Arguments:
   - name        - Tool name (string)
   - opts map:
     - :description - Tool description
     - :parameters  - JSON schema for parameters (or nil)
     - :handler     - Function (fn [args invocation] -> result)
   
   The handler receives:
   - args       - The parsed arguments from the LLM (no key conversion)
   - invocation - Map with :session-id, :tool-call-id, :tool-name, :arguments
   
   The handler should return one of:
   - A string (treated as success)
   - A map with :text-result-for-llm and :result-type
   - Any other value (JSON-encoded as success)
   - A core.async channel that will yield one of the above
   
   Example:
   ```clojure
   (define-tool \"get_weather\"
     {:description \"Get weather for a location\"
      :parameters {:type \"object\"
                   :properties {:location {:type \"string\"}}
                   :required [\"location\"]}
      :handler (fn [args _]
                 (str \"Weather in \" (:location args) \": Sunny, 72°F\"))})
   ```"
  [name {:keys [description parameters handler]}]
  {:tool-name name
   :tool-description description
   :tool-parameters parameters
   :tool-handler handler})

(defn define-tool-from-spec
  "Define a tool using a clojure.spec for parameter validation.
   
   The spec is converted to a basic JSON schema.
   Note: This provides limited schema conversion. For complex schemas,
   use define-tool with an explicit JSON schema.
   
   Arguments:
   - name        - Tool name (string)
   - opts map:
     - :description - Tool description
     - :spec        - A clojure.spec for the arguments
     - :handler     - Function (fn [args invocation] -> result)
   
   Example:
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
   ```"
  [name {:keys [description spec handler]}]
  ;; For now, we don't auto-convert spec to JSON schema
  ;; The handler should validate using the spec
  {:tool-name name
   :tool-description description
   :tool-parameters nil  ; User should provide JSON schema if needed
   :tool-handler (fn [args invocation]
                   (if (and spec (not (s/valid? spec args)))
                     {:text-result-for-llm (str "Invalid arguments: " (s/explain-str spec args))
                      :result-type "failure"
                      :error "spec validation failed"}
                     (handler args invocation)))})

(defn result-success
  "Create a successful tool result."
  ([text]
   (result-success text {}))
  ([text telemetry]
   {:text-result-for-llm text
    :result-type "success"
    :tool-telemetry telemetry}))

(defn result-failure
  "Create a failed tool result."
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
  "Create a denied tool result (permission denied)."
  ([text]
   (result-denied text {}))
  ([text telemetry]
   {:text-result-for-llm text
    :result-type "denied"
    :tool-telemetry telemetry}))

(defn result-rejected
  "Create a rejected tool result (user rejected)."
  ([text]
   (result-rejected text {}))
  ([text telemetry]
   {:text-result-for-llm text
    :result-type "rejected"
    :tool-telemetry telemetry}))
