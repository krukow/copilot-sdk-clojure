(ns github.copilot-sdk.util
  "Utility functions for the Copilot SDK."
  (:require [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]))

;; -----------------------------------------------------------------------------
;; Key conversion utilities
;; Convert between wire format (camelCase) and Clojure idiom (kebab-case)
;; -----------------------------------------------------------------------------

(defn- keyword->camel
  [k]
  (if (keyword? k)
    (csk/->camelCaseKeyword k)
    k))

(defn- keyword->kebab
  [k]
  (if (keyword? k)
    (csk/->kebab-case-keyword k)
    k))

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
   :mcp-headers :headers})

(defn mcp-server->wire
  "Convert a single MCP server config from Clojure idiom to wire format.
   Strips the :mcp- prefix from MCP-specific keys, then converts remaining
   keys to camelCase. Keyword values for :type are converted to strings.
   Example: {:mcp-command \"node\" :mcp-args [\"x\"] :mcp-server-type :http}
   becomes {\"command\" \"node\" \"args\" [\"x\"] \"type\" \"http\"}."
  [m]
  (let [renamed (reduce-kv (fn [acc k v]
                             (assoc acc (get mcp-key-renames k k) v))
                           {}
                           m)
        ;; Convert keyword values for :type to strings (upstream expects string)
        stringified (cond-> renamed
                      (keyword? (:type renamed))
                      (update :type name))]
    (clj->wire stringified)))

(defn mcp-servers->wire
  "Convert MCP servers map from Clojure idiom to wire format.
   Each server value has :mcp-* prefixed keys stripped before camelCase conversion."
  [servers]
  (into {} (map (fn [[k v]] [k (mcp-server->wire v)])) servers))

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

    ;; :file and :directory
    (cond-> {:type (name (:type att))
             :path (:path att)}
      (:display-name att) (assoc :displayName (:display-name att)))))

(defn attachments->wire
  "Convert a vector of attachments to wire format."
  [attachments]
  (mapv attachment->wire attachments))

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
