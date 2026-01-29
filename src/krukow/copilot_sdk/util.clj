(ns krukow.copilot-sdk.util
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
