(ns krukow.copilot-sdk.logging
  "Logging facade for the Copilot SDK using clojure.tools.logging.
   
   This wraps clojure.tools.logging to provide SDK-specific defaults.
   Configure logging via your preferred SLF4J backend (logback, log4j2, etc.)."
  (:require [clojure.tools.logging :as log]))

;; Re-export tools.logging macros for SDK use
;; Users can configure logging backend (SLF4J) as they prefer

(defmacro debug
  "Log a debug message."
  [& args]
  `(log/debug (str ~@args)))

(defmacro info
  "Log an info message."
  [& args]
  `(log/info (str ~@args)))

(defmacro warn
  "Log a warning message."
  [& args]
  `(log/warn (str ~@args)))

(defmacro error
  "Log an error message."
  [& args]
  `(log/error (str ~@args)))

;; Legacy compatibility - no-op since logging config is via SLF4J backend
(defn set-log-level!
  "Set log level. Note: With tools.logging, configure via your SLF4J backend instead.
   This function is kept for API compatibility but has no effect."
  [_level]
  nil)

(defn get-log-level
  "Get current log level. Returns :info as default.
   Actual level is determined by your SLF4J backend configuration."
  []
  :info)
