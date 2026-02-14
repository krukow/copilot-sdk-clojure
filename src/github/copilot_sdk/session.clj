(ns github.copilot-sdk.session
  "CopilotSession - session operations using centralized client state.
   
   All session state is stored in the client's :state atom under:
   - [:sessions session-id] -> {:tool-handlers {} :permission-handler nil :destroyed? false :workspace-path nil}
   - [:session-io session-id] -> {:event-chan :event-mult}
   
   Functions take client + session-id, accessing state through the client."
  (:require [clojure.core.async :as async :refer [go go-loop <! >! >!! <!! chan close! put! alts!! mult tap untap]]
            [clojure.core.async.impl.channels :as channels]
            [clojure.spec.alpha :as s]
            [clojure.data.json :as json]
            [github.copilot-sdk.protocol :as proto]
            [github.copilot-sdk.logging :as log]
            [github.copilot-sdk.specs :as specs]
            [github.copilot-sdk.util :as util]))

;; -----------------------------------------------------------------------------
;; State accessors - all state lives in client's atom
;; -----------------------------------------------------------------------------

(defn- session-state [client session-id]
  (get-in @(:state client) [:sessions session-id]))

(defn- session-io [client session-id]
  (get-in @(:state client) [:session-io session-id]))

(defn- update-session! [client session-id f & args]
  (apply swap! (:state client) update-in [:sessions session-id] f args))

(defn- connection-io [client]
  (:connection-io @(:state client)))

;; -----------------------------------------------------------------------------
;; Session record - lightweight handle returned to users
;; Contains only immutable data + reference to client
;; -----------------------------------------------------------------------------

(defrecord CopilotSession
           [session-id
            workspace-path
            client])     ; reference to owning client

;; -----------------------------------------------------------------------------
;; Internal functions
;; -----------------------------------------------------------------------------

(defn create-session
  "Create a new session. Internal use - called by client.
   Initializes session state in client's atom and returns a CopilotSession handle."
  [client session-id {:keys [tools on-permission-request on-user-input-request hooks workspace-path config]}]
  (log/debug "Creating session: " session-id)
  (let [event-chan (chan (async/sliding-buffer 4096))
        event-mult (mult event-chan)
        send-lock (java.util.concurrent.Semaphore. 1)
        tool-handlers (into {} (map (fn [t] [(:tool-name t) (:tool-handler t)]) tools))]
    ;; Store session state and IO in client's atom
    (swap! (:state client)
           (fn [state]
             (-> state
                  (assoc-in [:sessions session-id]
                            {:tool-handlers tool-handlers
                             :permission-handler on-permission-request
                             :user-input-handler on-user-input-request
                             :hooks hooks
                             :destroyed? false
                             :workspace-path workspace-path
                             :config config})
                 (assoc-in [:session-io session-id]
                           {:event-chan event-chan
                            :event-mult event-mult
                            :send-lock send-lock}))))
    (log/debug "Session created: " session-id)
    ;; Return lightweight handle
    (->CopilotSession session-id workspace-path client)))

(defn dispatch-event!
  "Dispatch an event to all subscribers via the mult. Called by client notification router.
   Events are dropped (with debug log) if the session event buffer is full."
  [client session-id event]
  (let [normalized-event (update event :type util/event-type->keyword)]
    (log/debug "Dispatching event to session " session-id ": type=" (:type normalized-event))
    (when-not (:destroyed? (session-state client session-id))
      (when-let [{:keys [event-chan]} (session-io client session-id)]
        (when-not (async/offer! event-chan normalized-event)
          (log/debug "Dropping event for session " session-id " due to full event buffer"))))))

(defn- normalize-tool-result
  "Normalize a tool result to the wire format."
  [result]
  (cond
    (nil? result)
    {:text-result-for-llm "Tool returned no result"
     :result-type "failure"
     :error "tool returned no result"
     :tool-telemetry {}}

    ;; Already a result object (duck-type check)
    (and (map? result) (:text-result-for-llm result) (:result-type result))
    result

    ;; Backward compatibility for camelCase result maps
    (and (map? result) (:textResultForLlm result) (:resultType result))
    (util/wire->clj result)

    ;; String result
    (string? result)
    {:text-result-for-llm result
     :result-type "success"
     :tool-telemetry {}}

    ;; Any other value - JSON encode
    :else
    {:text-result-for-llm (json/write-str result)
     :result-type "success"
     :tool-telemetry {}}))

(defn- channel?
  "Check if x is a core.async channel."
  [x]
  (instance? clojure.core.async.impl.channels.ManyToManyChannel x))

(defn handle-tool-call!
  "Handle an incoming tool call request. Returns a channel with the result wrapper."
  [client session-id tool-call-id tool-name arguments]
  (async/thread-call
   (fn []
     (let [handler (get-in (session-state client session-id) [:tool-handlers tool-name])
           timeout-ms (or (:tool-timeout-ms (:options client)) 120000)]
       (if-not handler
         {:result {:text-result-for-llm (str "Tool '" tool-name "' is not supported by this client instance.")
                   :result-type "failure"
                   :error (str "tool '" tool-name "' not supported")
                   :tool-telemetry {}}}
         (try
           (let [invocation {:session-id session-id
                             :tool-call-id tool-call-id
                             :tool-name tool-name
                             :arguments arguments}
                 result (handler arguments invocation)
                 result (if (channel? result)
                          (let [timeout-ch (async/timeout timeout-ms)
                                [value ch] (alts!! [result timeout-ch])]
                            (if (= ch timeout-ch)
                              (throw (ex-info "Tool timeout" {:timeout-ms timeout-ms
                                                              :tool-name tool-name
                                                              :tool-call-id tool-call-id}))
                              value))
                          result)]
             {:result (normalize-tool-result result)})
           (catch Exception e
             {:result {:text-result-for-llm "Invoking this tool produced an error. Detailed information is not available."
                       :result-type "failure"
                       :error (ex-message e)
                       :tool-telemetry {}}})))))
   :mixed))

(defn handle-permission-request!
  "Handle an incoming permission request. Returns a channel with the result."
  [client session-id request]
  (async/thread-call
   (fn []
     (let [handler (:permission-handler (session-state client session-id))]
       (if-not handler
         {:result {:kind "denied-no-approval-rule-and-could-not-request-from-user"}}
         (try
           (let [result (handler request {:session-id session-id})
                 ;; If handler returns a channel, await it
                 result (if (channel? result)
                          (<!! result)
                          result)]
             (cond
               (and (map? result) (contains? result :kind))
               {:result result}

               (and (map? result) (contains? result :result)
                    (map? (:result result)) (contains? (:result result) :kind))
               result

               :else
               (do
                 (log/warn "Invalid permission response for session " session-id ": " result)
                 {:result {:kind "denied-no-approval-rule-and-could-not-request-from-user"}})))
            (catch Exception e
              (log/error "Permission handler error for session " session-id ": " (ex-message e))
              {:result {:kind "denied-no-approval-rule-and-could-not-request-from-user"}})))))
    :io))

(defn handle-user-input-request!
  "Handle an incoming user input request (ask_user). Returns a channel with the result.
   PR #269 feature.
   
   The handler should return a map with :answer (string) and optionally :was-freeform (boolean).
   For backwards compatibility, :response is also accepted as an alias for :answer."
  [client session-id request]
  (async/thread-call
   (fn []
     (let [handler (:user-input-handler (session-state client session-id))]
       (if-not handler
         {:error {:code -32001 :message "User input requested but no handler registered"}}
         (try
            (let [result (handler request {:session-id session-id})
                  ;; If handler returns a channel, await it
                  result (if (channel? result)
                           (<!! result)
                           result)
                  ;; Normalize result to expected wire format
                  ;; Accept :answer or :response, default was-freeform to true if not specified
                  answer (or (:answer result) (:response result))
                  was-freeform (if (contains? result :was-freeform)
                                 (:was-freeform result)
                                 true)]
              (if (and (string? answer) (not (empty? answer)))
                {:result {:answer answer :was-freeform was-freeform}}
                (do
                  (log/warn "Invalid user input response for session " session-id ": " result)
                  {:error {:code -32001 :message "User input handler returned invalid answer"}})))
           (catch Exception e
             (log/error "User input handler error for session " session-id ": " (ex-message e))
             {:error {:code -32001 :message (str "User input handler error: " (ex-message e))}})))))
   :io))

(defn handle-hooks-invoke!
  "Handle an incoming hooks invocation. Returns a channel with the result.
   PR #269 feature."
  [client session-id hook-type input]
  (async/thread-call
   (fn []
     (let [hooks (:hooks (session-state client session-id))]
       (if-not hooks
         {:result nil}
         (let [;; Map hook type strings to handler keywords
               handler-key (case hook-type
                             "preToolUse" :on-pre-tool-use
                             "postToolUse" :on-post-tool-use
                             "userPromptSubmitted" :on-user-prompt-submitted
                             "sessionStart" :on-session-start
                             "sessionEnd" :on-session-end
                             "errorOccurred" :on-error-occurred
                             nil)
               handler (when handler-key (get hooks handler-key))]
           (if-not handler
             {:result nil}
             (try
               (let [result (handler input {:session-id session-id})
                     ;; If handler returns a channel, await it
                     result (if (channel? result)
                              (<!! result)
                              result)]
                 {:result result})
               (catch Exception e
                 (log/error "Hook handler error for session " session-id ", hook " hook-type ": " (ex-message e))
                 {:result nil})))))))
   :io))

;; -----------------------------------------------------------------------------
;; Public API - functions that take CopilotSession handle
;; -----------------------------------------------------------------------------

(defn config
  "Get the session configuration that was used to create this session.
   Returns the user-provided config. Note: This reflects what was requested,
   not necessarily what the server is using. The session.start event contains
   the actual selectedModel if validation is needed."
  [session]
  (let [{:keys [session-id client]} session]
    (:config (session-state client session-id))))

(defn send!
  "Send a message to the session.
   Returns the message ID immediately (fire-and-forget).
   
   Options:
   - :prompt       - The message text (required)
   - :attachments  - Vector of attachments (file/directory/selection)
   - :mode         - :enqueue (default) or :immediate"
  [session opts]
  (when-not (s/valid? ::specs/send-options opts)
    (throw (ex-info "Invalid send options"
                    {:opts opts
                     :explain (s/explain-data ::specs/send-options opts)})))
  (let [{:keys [session-id client]} session]
    (log/debug "send! called for session " session-id " with prompt: " (subs (str (:prompt opts)) 0 (min 50 (count (str (:prompt opts))))) "...")
    (when (:destroyed? (session-state client session-id))
      (throw (ex-info "Session has been destroyed" {:session-id session-id})))
    (let [conn (connection-io client)
          wire-attachments (when (:attachments opts)
                             (util/attachments->wire (:attachments opts)))
          params (cond-> {:session-id session-id
                          :prompt (:prompt opts)}
                   wire-attachments (assoc :attachments wire-attachments)
                   (:mode opts) (assoc :mode (name (:mode opts))))
          result (proto/send-request! conn "session.send" params)
          msg-id (:message-id result)]
      (log/debug "send! completed for session " session-id " message-id=" msg-id)
      msg-id)))

(defn send-and-wait!
  "Send a message and wait until the session becomes idle.
   Returns the final assistant message event, or nil if none received.
   Serialized per session to avoid mixing concurrent sends.
   
   Options: same as send!
   
   Additional options:
   - :timeout-ms   - Timeout in milliseconds (default: 300000)"
  ([session opts]
   (send-and-wait! session opts 300000))
  ([session opts timeout-ms]
   (let [{:keys [session-id client]} session]
     (log/debug "send-and-wait! called for session " session-id)
     (when (:destroyed? (session-state client session-id))
       (throw (ex-info "Session has been destroyed" {:session-id session-id})))

     (let [event-ch (chan 1024)
           last-assistant-msg (atom nil)
           {:keys [event-mult send-lock]} (session-io client session-id)]
       (try
         (.acquire send-lock)
         (catch InterruptedException e
           (.interrupt (Thread/currentThread))
           (throw (ex-info "Interrupted while waiting to send message" {} e))))

       ;; Tap the mult BEFORE sending - ensures we don't miss events
       (log/debug "send-and-wait! tapping event mult for session " session-id)
       (tap event-mult event-ch)

       (try
         ;; Send the message
         (log/debug "send-and-wait! sending message")
         (send! session opts)

         ;; Wait for events with single deadline timeout
         (log/debug "send-and-wait! waiting for result with timeout " timeout-ms "ms")
         (let [deadline-ch (async/timeout timeout-ms)]
           (loop []
             (let [[event ch] (alts!! [event-ch deadline-ch])]
               (cond
                 (= ch deadline-ch)
                 (do
                   (log/error "send-and-wait! timeout after " timeout-ms "ms for session " session-id)
                   (throw (ex-info (str "Timeout after " timeout-ms "ms waiting for session.idle")
                                   {:timeout-ms timeout-ms})))

                 (nil? event)
                 (do
                   (log/debug "send-and-wait! event channel closed for session " session-id)
                   (throw (ex-info "Event channel closed unexpectedly" {})))

                 (= :copilot/assistant.message (:type event))
                 (do
                   (log/debug "send-and-wait! got assistant.message, continuing to wait for idle")
                   (reset! last-assistant-msg event)
                   (recur))

                 (= :copilot/session.idle (:type event))
                 (do
                   (log/debug "send-and-wait! got session.idle, returning result for session " session-id)
                   @last-assistant-msg)

                 (= :copilot/session.error (:type event))
                 (do
                   (log/error "send-and-wait! got session.error for session " session-id)
                   (throw (ex-info (get-in event [:data :message] "Session error")
                                   {:event event})))

                 :else
                 (do
                   (log/debug "send-and-wait! ignoring event type: " (:type event))
                   (recur))))))

         (finally
           (log/debug "send-and-wait! cleaning up subscription")
           (untap event-mult event-ch)
           (close! event-ch)
           (.release send-lock)))))))

(defn- send-async*
  "Send a message and return {:message-id :events-ch}."
  ([session opts]
   (send-async* session opts nil))
  ([session opts timeout-ms]
   (let [{:keys [session-id client]} session]
     (when (:destroyed? (session-state client session-id))
       (throw (ex-info "Session has been destroyed" {:session-id session-id})))

     (let [out-ch (chan 1024)
           event-ch (chan 1024)
           {:keys [event-mult send-lock]} (session-io client session-id)
           released? (atom false)
           release-lock! (fn []
                           (when (compare-and-set! released? false true)
                             (.release send-lock)))
           deadline-ch (when timeout-ms (async/timeout timeout-ms))
           timeout-event {:type :copilot/session.error
                          :data {:message (str "Timeout after " timeout-ms "ms waiting for session.idle")
                                 :timeout-ms timeout-ms}}
           emit! (fn [event]
                   (when-not (async/offer! out-ch event)
                     (log/debug "Dropping event for session " session-id " due to full async buffer")))]
       (try
         (.acquire send-lock)
         (catch InterruptedException e
           (.interrupt (Thread/currentThread))
           (throw (ex-info "Interrupted while waiting to send message" {} e))))

       ;; Tap the mult for events
       (tap event-mult event-ch)

       ;; Send the message
       (try
         (let [message-id (send! session opts)]
           (go-loop []
             (let [[event ch] (if deadline-ch
                                (async/alts! [event-ch deadline-ch])
                                [(<! event-ch) event-ch])]
               (cond
                 (and deadline-ch (= ch deadline-ch))
                 (do
                   (emit! timeout-event)
                   (untap event-mult event-ch)
                   (close! event-ch)
                   (close! out-ch)
                   (release-lock!))

                 (nil? event)
                 (do
                   (untap event-mult event-ch)
                   (close! out-ch)
                   (release-lock!))

                 (= :copilot/session.idle (:type event))
                 (do
                   (emit! event)
                   (untap event-mult event-ch)
                   (close! event-ch)
                   (close! out-ch)
                   (release-lock!))

                 (= :copilot/session.error (:type event))
                 (do
                   (emit! event)
                   (untap event-mult event-ch)
                   (close! event-ch)
                   (close! out-ch)
                   (release-lock!))

                 :else
                 (do
                   (emit! event)
                   (recur)))))
           {:message-id message-id
            :events-ch out-ch})
         (catch Exception e
           (untap event-mult event-ch)
           (close! event-ch)
           (close! out-ch)
           (release-lock!)
           (throw e)))))))

(defn send-async
  "Send a message and return a channel that receives events until session.idle.
   The channel closes after session.idle or session.error.
   Serialized per session to avoid mixing concurrent sends.
   
   Options:
   - :timeout-ms   - Timeout in milliseconds (default: 300000, set to nil to disable)"
  [session opts]
  (let [timeout-ms (if (contains? opts :timeout-ms) (:timeout-ms opts) 300000)
        opts (dissoc opts :timeout-ms)]
    (:events-ch (send-async* session opts timeout-ms))))

(defn <send!
  "Send a message and return a channel that delivers the final content string.
   This is the async equivalent of send-and-wait! - use inside go blocks.
   
   Options:
   - :timeout-ms   - Timeout in milliseconds (default: 300000, set to nil to disable)
   
   The returned channel delivers a single value (the response content) then closes."
  [session opts]
  (let [timeout-ms (if (contains? opts :timeout-ms) (:timeout-ms opts) 300000)
        events-ch (send-async session (assoc opts :timeout-ms timeout-ms))
        out-ch (chan (async/sliding-buffer 1))]
    (go
      (loop [last-content nil]
        (when-let [event (<! events-ch)]
          (cond
            (= :copilot/assistant.message (:type event))
            (recur (get-in event [:data :content]))

            (#{:copilot/session.idle :copilot/session.error} (:type event))
            (when last-content
              (async/offer! out-ch last-content))

            :else
            (recur last-content))))
      (close! out-ch))
    out-ch))

(defn send-async-with-id
  "Send a message and return {:message-id :events-ch}."
  [session opts]
  (let [timeout-ms (if (contains? opts :timeout-ms) (:timeout-ms opts) 300000)
        opts (dissoc opts :timeout-ms)]
    (send-async* session opts timeout-ms)))

(defn abort!
  "Abort the currently processing message in this session."
  [session]
  (let [{:keys [session-id client]} session]
    (when (:destroyed? (session-state client session-id))
      (throw (ex-info "Session has been destroyed" {:session-id session-id})))
    (let [conn (connection-io client)]
      (proto/send-request! conn "session.abort" {:session-id session-id})
      nil)))

(defn get-messages
  "Get all events/messages from this session's history."
  [session]
  (let [{:keys [session-id client]} session]
    (when (:destroyed? (session-state client session-id))
      (throw (ex-info "Session has been destroyed" {:session-id session-id})))
    (let [conn (connection-io client)
          result (proto/send-request! conn "session.getMessages" {:session-id session-id})]
      (mapv #(update % :type util/event-type->keyword) (:events result)))))

(defn destroy!
  "Destroy the session and free resources.
   Can be called with either a CopilotSession handle or (client, session-id)."
  ([session]
   (destroy! (:client session) (:session-id session)))
  ([client session-id]
   (log/debug "Destroying session: " session-id)
   (when-not (:destroyed? (session-state client session-id))
     (let [conn (connection-io client)]
       ;; Try to notify server, but don't block forever if connection is broken
       (try
         (proto/send-request! conn "session.destroy" {:session-id session-id} 5000)
         (catch Exception _
           ;; Ignore errors - we're cleaning up anyway
           nil))
       ;; Atomically update state
       (update-session! client session-id assoc
                        :destroyed? true
                        :tool-handlers {}
                        :permission-handler nil)
       ;; Close the event source channel - this propagates to all tapped channels
       (when-let [{:keys [event-chan]} (session-io client session-id)]
         (close! event-chan))
       (log/debug "Session destroyed: " session-id)
       nil))))

(defn events
  "Get the event mult for this session. Use tap to subscribe:
   
   (let [ch (chan 100)]
     (tap (events session) ch)
     (go-loop []
       (when-let [event (<! ch)]
         (println event)
         (recur))))
   
   Remember to untap and close your channel when done."
  [session]
  (let [{:keys [session-id client]} session]
    (:event-mult (session-io client session-id))))

(defn subscribe-events
  "Subscribe to session events. Returns a channel that receives events.
   
   The channel will receive nil (close) when the session is destroyed.
   For explicit cleanup before session destruction, call unsubscribe-events.
   
   Drop behavior: If this subscriber's channel buffer is full when mult tries
   to deliver an event, that specific event is silently dropped for this
   subscriber only. Other subscribers with available buffer space still receive
   the event. The returned channel has a buffer of 1024 events which should be
   sufficient for most use cases.
   
   This is a convenience wrapper around (tap (events session) ch)."
  [session]
  (let [ch (chan 1024)
        {:keys [session-id client]} session
        {:keys [event-mult]} (session-io client session-id)]
    (tap event-mult ch)
    ch))

(defn events->chan
  "Subscribe to session events with options.

   Options:
   - :buffer - Channel buffer size (default 1024)
   - :xf     - Transducer applied to events

   Drop behavior: If this subscriber's channel buffer is full when mult tries
   to deliver an event, that specific event is silently dropped for this
   subscriber only. Other subscribers with available buffer space still receive
   the event."
  ([session]
   (events->chan session {}))
  ([session {:keys [buffer xf] :or {buffer 1024}}]
   (let [{:keys [session-id client]} session
         {:keys [event-mult]} (session-io client session-id)
         ch (if xf (chan buffer xf) (chan buffer))]
     (tap event-mult ch)
     ch)))

(defn unsubscribe-events
  "Unsubscribe a channel from session events."
  [session ch]
  (let [{:keys [session-id client]} session
        {:keys [event-mult]} (session-io client session-id)]
    (untap event-mult ch)
    (close! ch)))

(defn session-id
  "Get the session ID."
  [session]
  (:session-id session))

(defn workspace-path
  "Get the session workspace path when provided by the CLI."
  [session]
  (:workspace-path session))

(defn get-current-model
  "Get the current model for this session.
   Returns the model ID string, or nil if none set."
  [session]
  (let [{:keys [session-id client]} session
        conn (connection-io client)
        result (proto/send-request! conn "session.model.getCurrent"
                                    {:sessionId session-id})]
    (:model-id result)))

(defn switch-model!
  "Switch the model for this session.
   Returns the new model ID string, or nil."
  [session model-id]
  (let [{:keys [session-id client]} session
        conn (connection-io client)
        result (proto/send-request! conn "session.model.switchTo"
                                    {:sessionId session-id
                                     :modelId model-id})]
    (:model-id result)))
