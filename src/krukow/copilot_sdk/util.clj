(ns krukow.copilot-sdk.util
  "Utility functions for the Copilot SDK."
  (:require [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]))

;; -----------------------------------------------------------------------------
;; Key conversion utilities
;; Convert between wire format (camelCase) and Clojure idiom (kebab-case)
;; -----------------------------------------------------------------------------

(defn ->wire-keys
  "Convert map keys from kebab-case to camelCase for wire format.
   Works recursively on nested maps."
  [m]
  (cske/transform-keys csk/->camelCaseKeyword m))

(defn ->clj-keys
  "Convert map keys from camelCase to kebab-case for Clojure idiom.
   Works recursively on nested maps."
  [m]
  (cske/transform-keys csk/->kebab-case-keyword m))

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
