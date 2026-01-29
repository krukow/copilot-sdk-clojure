(ns session-events
  "Example: Monitoring session state events.

   This example demonstrates how to observe and handle session lifecycle
   and state management events including:
   - session.start / session.resume - Session lifecycle
   - session.truncation - Context window management
   - session.compaction_start / session.compaction_complete - Infinite sessions
   - session.snapshot_rewind - Session state rollback (e.g., undo operations)
   - session.usage_info - Token usage information
   - session.model_change - Model switching
   - session.error - Error conditions

   The snapshot_rewind event is emitted when the CLI rewinds session state
   to a previous point (e.g., undoing tool executions or reverting changes).
   This is an ephemeral event that signals state has been rolled back."
  (:require [clojure.core.async :as async :refer [chan tap go-loop <!]]
            [krukow.copilot-sdk :as copilot]))

;; See examples/README.md for usage

;; Use the SDK's exported session-events set (excludes :copilot/session.start
;; which fires before we can subscribe)
(def displayable-session-events
  "Session events we can display (excludes session.start which fires before subscription)."
  (disj copilot/session-events :copilot/session.start))

(defn format-event
  "Format a session event for display."
  [event]
  (let [event-type (:type event)
        data (:data event)
        ephemeral? (:ephemeral? event)]
    (case event-type
      :copilot/session.start
      (format "🚀 Session started: %s (model: %s)"
              (:session-id data)
              (or (:selected-model data) "default"))

      :copilot/session.resume
      (format "🔄 Session resumed at %s (%d events)"
              (:resume-time data)
              (or (:event-count data) 0))

      :copilot/session.idle
      "⏸️  Session idle - ready for input"

      :copilot/session.error
      (format "❌ Error [%s]: %s"
              (:error-type data)
              (:message data))

      :copilot/session.info
      (format "ℹ️  Info [%s]: %s"
              (:info-type data)
              (:message data))

      :copilot/session.model_change
      (format "🔀 Model changed: %s → %s"
              (or (:previous-model data) "none")
              (:new-model data))

      :copilot/session.truncation
      (format "✂️  Context truncated: %d tokens removed (%d → %d messages)"
              (or (:tokens-removed-during-truncation data) 0)
              (or (:pre-truncation-messages-length data) 0)
              (or (:post-truncation-messages-length data) 0))

      :copilot/session.snapshot_rewind
      (format "⏪ Snapshot rewind: rolled back to event %s (%d events removed)"
              (:up-to-event-id data)
              (or (:events-removed data) 0))

      :copilot/session.usage_info
      (format "📊 Usage: %d/%d tokens (%d messages)"
              (or (:current-tokens data) 0)
              (or (:token-limit data) 0)
              (or (:messages-length data) 0))

      :copilot/session.compaction_start
      "🗜️  Compaction starting..."

      :copilot/session.compaction_complete
      (if (:success data)
        (format "🗜️  Compaction complete: %d → %d tokens (%d removed)"
                (or (:pre-compaction-tokens data) 0)
                (or (:post-compaction-tokens data) 0)
                (or (:tokens-removed data) 0))
        (format "🗜️  Compaction failed: %s" (:error data)))

      :copilot/session.handoff
      (format "🤝 Session handoff from %s" (:source-type data))

      ;; Default
      (format "📋 %s: %s%s"
              (name event-type)
              (pr-str data)
              (if ephemeral? " (ephemeral)" "")))))

(defn run
  "Run a conversation while monitoring all session state events."
  [{:keys [prompt model]
    :or {prompt "Tell me a very short story (2-3 sentences)."
         model "gpt-5.2"}}]
  (copilot/with-client-session [session {:model model :streaming? true}]
    (let [events-ch (chan 256)
          done (promise)
          state-events (atom [])]
      (tap (copilot/events session) events-ch)
      (go-loop []
        (when-let [event (<! events-ch)]
          (let [event-type (:type event)]
            (when (displayable-session-events event-type)
              (swap! state-events conj event)
              (println (format-event event)))
            (when (= :copilot/assistant.message_delta event-type)
              (print (get-in event [:data :delta-content]))
              (flush))
            (when (= :copilot/assistant.message event-type)
              (println (get-in event [:data :content])))
            (when (= :copilot/session.idle event-type)
              (deliver done {:success true :state-events @state-events}))
            (when (= :copilot/session.error event-type)
              (deliver done {:success false :error event :state-events @state-events})))
          (recur)))
      (copilot/send! session {:prompt prompt})
      (let [result @done]
        (println "\n=== Summary ===")
        (println (format "Total session state events: %d" (count (:state-events result))))
        (doseq [[evt-type cnt] (->> (:state-events result)
                                    (map :type)
                                    frequencies
                                    (sort-by val >))]
          (println (format "  %s: %d" (name evt-type) cnt)))
        (when-not (:success result)
          (println "\n⚠️  Session ended with error"))))))

(comment
  ;; Run with defaults
  (run {})

  ;; Run with a custom prompt
  (run {:prompt "What is 2+2?"})

  ;; Run with infinite sessions enabled (requires CLI 0.0.389+)
  (run {:prompt "Tell me a long story about a programmer."
        :infinite-sessions true})

  ;; Note: session.snapshot_rewind events are typically triggered by:
  ;; - Undo operations in interactive CLI sessions
  ;; - Reverting failed tool executions
  ;; - Rolling back to checkpoints
  ;; These are internal CLI operations and may not occur in simple queries.
  )
