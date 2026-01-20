(ns krukow.copilot-sdk.session
  "CopilotSession - session operations using centralized client state.
   
   All session state is stored in the client's :state atom under:
   - [:sessions session-id] -> {:tool-handlers {} :permission-handler nil :destroyed? false}
   - [:session-io session-id] -> {:event-chan :event-mult}
   
   Functions take client + session-id, accessing state through the client."
  (:require [clojure.core.async :as async :refer [go go-loop <! >! >!! <!! chan close! put! alts!! mult tap untap]]
            [clojure.core.async.impl.channels :as channels]
            [cheshire.core :as json]
            [krukow.copilot-sdk.protocol :as proto]
            [krukow.copilot-sdk.logging :as log]
            [krukow.copilot-sdk.util :as util]))

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
     client])     ; reference to owning client

;; -----------------------------------------------------------------------------
;; Internal functions
;; -----------------------------------------------------------------------------

(defn create-session
  "Create a new session. Internal use - called by client.
   Initializes session state in client's atom and returns a CopilotSession handle."
  [client session-id {:keys [tools on-permission-request]}]
  (log/debug "Creating session: " session-id)
  (let [event-chan (chan 1024)
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
                            :destroyed? false})
                 (assoc-in [:session-io session-id]
                           {:event-chan event-chan
                            :event-mult event-mult
                            :send-lock send-lock}))))
    (log/info "Session created: " session-id)
    ;; Return lightweight handle
    (->CopilotSession session-id client)))

(defn dispatch-event!
  "Dispatch an event to all subscribers via the mult. Called by client notification router."
  [client session-id event]
  (log/debug "Dispatching event to session " session-id ": type=" (:type event))
  (when-not (:destroyed? (session-state client session-id))
    (when-let [{:keys [event-chan]} (session-io client session-id)]
      (>!! event-chan event))))

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
    {:text-result-for-llm (json/generate-string result)
     :result-type "success"
     :tool-telemetry {}}))

(defn- channel?
  "Check if x is a core.async channel."
  [x]
  (instance? clojure.core.async.impl.channels.ManyToManyChannel x))

(defn handle-tool-call!
  "Handle an incoming tool call request. Returns a channel with the result wrapper."
  [client session-id tool-call-id tool-name arguments]
  (async/thread
    (let [handler (get-in (session-state client session-id) [:tool-handlers tool-name])]
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
                ;; If handler returns a channel, await it
                result (if (channel? result)
                         (<!! result)
                         result)]
            {:result (normalize-tool-result result)})
          (catch Exception e
            {:result {:text-result-for-llm "Invoking this tool produced an error. Detailed information is not available."
                      :result-type "failure"
                      :error (ex-message e)
                      :tool-telemetry {}}})))))) 

(defn handle-permission-request!
  "Handle an incoming permission request. Returns a channel with the result."
  [client session-id request]
  (async/thread
    (let [handler (:permission-handler (session-state client session-id))]
      (if-not handler
        {:kind "denied-no-approval-rule-and-could-not-request-from-user"}
        (try
          (let [result (handler request {:session-id session-id})
                ;; If handler returns a channel, await it
                result (if (channel? result)
                         (<!! result)
                         result)]
            result)
          (catch Exception _
            {:kind "denied-no-approval-rule-and-could-not-request-from-user"}))))))

;; -----------------------------------------------------------------------------
;; Public API - functions that take CopilotSession handle
;; -----------------------------------------------------------------------------

(defn send!
  "Send a message to the session.
   Returns the message ID immediately (fire-and-forget).
   
   Options:
   - :prompt       - The message text (required)
   - :attachments  - Vector of {:type :path :display-name}
   - :mode         - :enqueue (default) or :immediate"
  [session opts]
  (let [{:keys [session-id client]} session]
    (log/debug "send! called for session " session-id " with prompt: " (subs (str (:prompt opts)) 0 (min 50 (count (str (:prompt opts))))) "...")
    (when (:destroyed? (session-state client session-id))
      (throw (ex-info "Session has been destroyed" {:session-id session-id})))
    (let [conn (connection-io client)
          wire-attachments (when (:attachments opts)
                             (mapv (fn [a]
                                     {:type (name (:type a))
                                      :path (:path a)
                                      :displayName (:display-name a)})
                                   (:attachments opts)))
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
   - :timeout-ms   - Timeout in milliseconds (default: 180000)"
  ([session opts]
   (send-and-wait! session opts 180000))
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
                 
                 (= "assistant.message" (:type event))
                 (do
                   (log/debug "send-and-wait! got assistant.message, continuing to wait for idle")
                   (reset! last-assistant-msg event)
                   (recur))
                 
                 (= "session.idle" (:type event))
                 (do
                   (log/debug "send-and-wait! got session.idle, returning result for session " session-id)
                   @last-assistant-msg)
                 
                 (= "session.error" (:type event))
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

(defn send-async
  "Send a message and return a channel that receives events until session.idle.
   The channel closes after session.idle or session.error.
   Serialized per session to avoid mixing concurrent sends."
  [session opts]
  (let [{:keys [session-id client]} session]
    (when (:destroyed? (session-state client session-id))
      (throw (ex-info "Session has been destroyed" {:session-id session-id})))
    
    (let [out-ch (chan 1024)
          event-ch (chan 1024)
          {:keys [event-mult send-lock]} (session-io client session-id)
          released? (atom false)
          release-lock! (fn []
                          (when (compare-and-set! released? false true)
                            (.release send-lock)))]
      (try
        (.acquire send-lock)
        (catch InterruptedException e
          (.interrupt (Thread/currentThread))
          (throw (ex-info "Interrupted while waiting to send message" {} e))))
      
      ;; Tap the mult for events
      (tap event-mult event-ch)
      
      ;; Send the message
      (try
        (send! session opts)
        (go-loop []
          (let [event (<! event-ch)]
            (cond
              (nil? event)
              (do
                (untap event-mult event-ch)
                (close! out-ch)
                (release-lock!))

              (= "session.idle" (:type event))
              (do
                (>! out-ch event)
                (untap event-mult event-ch)
                (close! event-ch)
                (close! out-ch)
                (release-lock!))

              (= "session.error" (:type event))
              (do
                (>! out-ch event)
                (untap event-mult event-ch)
                (close! event-ch)
                (close! out-ch)
                (release-lock!))

              :else
              (do
                (>! out-ch event)
                (recur)))))
        (catch Exception e
          (untap event-mult event-ch)
          (close! event-ch)
          (close! out-ch)
          (release-lock!)
          (throw e)))
      
      out-ch)))

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
      (:events result))))

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
       (log/info "Session destroyed: " session-id)
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
   Call unsubscribe-events when done.
   
   This is a convenience wrapper around (tap (events session) ch)."
  [session]
  (let [ch (chan 1024)
        {:keys [session-id client]} session
        {:keys [event-mult]} (session-io client session-id)]
    (tap event-mult ch)
    ch))

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
