#!/usr/bin/env bb
;; Codegen entry point. Reads pinned schemas from schemas/ and
;; emits Clojure source files under src/github/copilot_sdk/generated/.
;;
;; Usage:
;;   bb codegen          ;; regenerate everything
;;
;; CI enforces drift via `git diff --exit-code` after running this script;
;; see .github/workflows/codegen-check.yml.

(ns codegen.main
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [codegen.core :as cc]
            [codegen.emit-coerce :as ec]
            [codegen.emit-specs :as es]))

(def repo-root
  (-> *file* fs/parent fs/parent fs/parent fs/canonicalize str))

(def schemas-dir
  (str (fs/path repo-root "schemas")))

(def gen-dir
  (str (fs/path repo-root "src" "github" "copilot_sdk" "generated")))

(defn -main [& _args]
  (let [events-path (str (fs/path schemas-dir "session-events.schema.json"))
        coerce-path (str (fs/path repo-root "script" "codegen" "coercions.edn"))]
    (when-not (fs/exists? events-path)
      (println (format "Schema not found at %s — run `bb schemas:fetch` first."
                       events-path))
      (System/exit 1))
    (println "Loading" events-path)
    (let [root  (cc/load-schema events-path)
          forms (es/emit-event-specs-ns root)
          out   (str (fs/path gen-dir "event_specs.clj"))]
      (cc/write-clj! out forms)
      (println (format "  → %s (%d forms)" out (count forms)))
      (let [metadata-forms (es/emit-event-metadata-ns root)
            metadata-out (str (fs/path gen-dir "event_metadata.clj"))]
        (cc/write-clj! metadata-out metadata-forms)
        (println (format "  → %s (%d forms)"
                         metadata-out
                         (count metadata-forms)))))
    (println "Loading" coerce-path)
    (let [coercions (ec/load-coercions coerce-path)
          forms     (ec/emit-coerce-ns coercions)
          out       (str (fs/path gen-dir "coerce.clj"))]
      (cc/write-clj! out forms)
      (println (format "  → %s (%d forms)" out (count forms))))
    (println "Codegen complete.")))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
