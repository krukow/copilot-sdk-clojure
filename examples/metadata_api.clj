(ns metadata-api
  "Demonstrates the metadata API functions introduced in v0.1.24:
   - list-sessions with context filtering
   - list-tools with model-specific overrides
   - get-quota for account usage information
   - get-current-model and switch-model for dynamic model switching

   Note: Some methods (tools.list, account.getQuota, session.model.*)
   require a CLI version that supports them. The example gracefully
   handles unsupported methods."
  (:require [github.copilot-sdk :as copilot]
            [github.copilot-sdk.helpers :as h]))

;; See examples/README.md for usage

(defn run
  [& _]
  (println "=== Copilot Metadata API Demo ===\n")

  (copilot/with-client [client {:log-level :warning}]
    ;; 1. List sessions (supported on all CLI versions)
    (println "1. Active Sessions:")
    (let [sessions (copilot/list-sessions client)]
      (println (str "   Found " (count sessions) " session(s)"))
      (when (seq sessions)
        (doseq [session (take 3 sessions)]
          (println (str "   - " (:session-id session)))
          (when-let [ctx (:context session)]
            (println (str "     Repository: " (:repository ctx)))
            (println (str "     Branch: " (:branch ctx)))))))

    ;; 2. List available tools
    (println "\n2. Available Tools:")
    (try
      (let [tools (copilot/list-tools client)]
        (println (str "   Found " (count tools) " tools"))
        (doseq [tool (take 5 tools)]
          (println (str "   - " (:name tool) ": " (:description tool)))))
      (catch Exception e
        (println (str "   Skipped: " (.getMessage e)))))

    ;; 3. Get quota information
    (println "\n3. Account Quota:")
    (try
      (let [quotas (copilot/get-quota client)]
        (doseq [[quota-type snapshot] quotas]
          (println (str "   " quota-type ":"))
          (println (str "     Entitlement: " (:entitlement-requests snapshot)))
          (println (str "     Used: " (:used-requests snapshot)))
          (println (str "     Remaining: " (:remaining-percentage snapshot) "%"))))
      (catch Exception e
        (println (str "   Skipped: " (.getMessage e)))))

    ;; 4. Model switching within a session
    (println "\n4. Dynamic Model Switching:")
    (copilot/with-session [session client {:model "gpt-5.2"}]
      ;; Query with gpt-5.2
      (println "   Query: 'What is 2+2? Answer briefly.'")
      (println (str "   Response: " (h/query "What is 2+2? Answer briefly." :session session)))

      ;; Try model introspection (requires CLI support)
      (try
        (let [current (copilot/get-current-model session)]
          (println (str "\n   Current model: " current))
          (copilot/switch-model! session "gpt-5.2")
          (println (str "   Switched to: " (copilot/get-current-model session)))
          (println "   Query: 'What was my previous question?'")
          (println (str "   Response: " (h/query "What was my previous question?" :session session))))
        (catch Exception e
          (println (str "\n   Model switching skipped: " (.getMessage e)))))))

  (println "\n=== Demo Complete ==="))
