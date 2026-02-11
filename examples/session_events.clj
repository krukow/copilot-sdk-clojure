(ns session-events
  "Example: Monitoring session state events.

   This example demonstrates how to observe and handle session events."
  (:require [clojure.core.async :refer [<!!]]
            [github.copilot-sdk :as copilot :refer [evt]]))

;; See examples/README.md for usage
(declare format-event)

(defn run
  "Run a conversation while monitoring all session state events."
  [{:keys [prompt model reasoning-effort]
    :or {prompt "Think hard - How many \"r\"s in strawberry?"
         model "gpt-5.2"
         reasoning-effort "high"}}]
  (copilot/with-client-session
      [client {:log-level :debug}
       session {:model model
                :streaming? true
                :reasoning-effort reasoning-effort}]
    (let [events-ch (copilot/subscribe-events session)
          all-events (atom [])]
      (copilot/send! session {:prompt prompt})
      (loop []
        (when-let [event (<!! events-ch)]
          (swap! all-events conj event)
          (println (format-event event))
          (condp = (:type event)
            (evt :session.idle)
            (do
              (println "\n=== Summary ===")
              (println (format "Total events: %d" (count @all-events)))
              (doseq [[evt-type cnt] (->> @all-events
                                          (map :type)
                                          frequencies
                                          (sort-by val >))]
                (println (format "  %s: %d" (name evt-type) cnt))))

            (evt :session.error)
            (println "\n⚠️  Session ended with error")

            ;; Keep looping for other events
            (recur)))))))

(defn format-event
  "Format a session event for display."
  [event]
  (let [event-type (:type event)
        data (:data event)
        ephemeral? (:ephemeral? event)]
    (condp = event-type
      (evt :session.start)
      (format "🚀 Session started: %s (model: %s)"
              (:session-id data)
              (or (:selected-model data) "default"))

      (evt :session.resume)
      (format "🔄 Session resumed at %s (%d events)"
              (:resume-time data)
              (or (:event-count data) 0))

      (evt :session.idle)
      "⏸️  Session idle - ready for input"

      (evt :session.error)
      (format "❌ Error [%s]: %s"
              (:error-type data)
              (:message data))

      (evt :session.info)
      (format "ℹ️  Info [%s]: %s"
              (:info-type data)
              (:message data))

      (evt :session.model_change)
      (format "🔀 Model changed: %s → %s"
              (or (:previous-model data) "none")
              (:new-model data))

      (evt :session.truncation)
      (format "✂️  Context truncated: %d tokens removed (%d → %d messages)"
              (or (:tokens-removed-during-truncation data) 0)
              (or (:pre-truncation-messages-length data) 0)
              (or (:post-truncation-messages-length data) 0))

      (evt :session.snapshot_rewind)
      (format "⏪ Snapshot rewind: rolled back to event %s (%d events removed)"
              (:up-to-event-id data)
              (or (:events-removed data) 0))

      (evt :session.usage_info)
      (format "📊 Usage: %d/%d tokens (%d messages)"
              (or (:current-tokens data) 0)
              (or (:token-limit data) 0)
              (or (:messages-length data) 0))

      (evt :session.compaction_start)
      "🗜️  Compaction starting..."

      (evt :session.compaction_complete)
      (if (:success data)
        (format "🗜️  Compaction complete: %d → %d tokens (%d removed)"
                (or (:pre-compaction-tokens data) 0)
                (or (:post-compaction-tokens data) 0)
                (or (:tokens-removed data) 0))
        (format "🗜️  Compaction failed: %s" (:error data)))

      (evt :session.handoff)
      (format "🤝 Session handoff from %s" (:source-type data))

      (evt :assistant.turn_start)
      "🎬 Assistant turn started"

      (evt :assistant.intent)
      (format "🎯 Intent: %s" (:intent data))

      (evt :assistant.reasoning)
      (format "💭 Reasoning: %s" (:content data))

      (evt :assistant.reasoning_delta)
      (format "💭 %s" (:delta-content data))

      (evt :assistant.message)
      (format "💬 Message: %s" (:content data))

      (evt :assistant.message_delta)
      (format "📝 %s" (:delta-content data))

      (evt :assistant.turn_end)
      "🏁 Assistant turn ended"

      (evt :assistant.usage)
      (format "📈 Usage: %d input, %d output tokens"
              (or (:input-tokens data) 0)
              (or (:output-tokens data) 0))

      ;; Default
      (format "📋 %s: %s%s"
              (name event-type)
              (pr-str data)
              (if ephemeral? " (ephemeral)" "")))))
