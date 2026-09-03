(ns github.copilot-sdk.helpers
  "Convenience functions for simple, stateless queries to Copilot.
   
   This namespace provides a higher-level API inspired by Claude Agent SDK's
   `query()` function. It manages a shared client internally to avoid CLI
   startup overhead across multiple queries.
   
   ## Quick Start
   
   ```clojure
   (require '[github.copilot-sdk.helpers :as h])
   
   ;; Simple blocking query
   (h/query \"What is 2+2?\")
   ;; => \"4\"
   
   ;; With options
   (h/query \"Explain monads\" :session {:on-permission-request copilot/approve-all
                                        :model \"claude-sonnet-4.5\"})
   ```
   
   ## Client Management
   
   The helpers namespace manages a shared client internally:
   - First query initializes the client with provided `:client` options
   - Subsequent queries reuse the client if `:client` options match
   - Different `:client` options trigger client replacement
   - Client is automatically cleaned up on JVM shutdown (no manual cleanup needed)
   - Call `(shutdown!)` for explicit cleanup if desired
   
   ## Options
   
   All query functions accept keyword arguments:
   - `:client` - Client options (cli-path, log-level, cwd, env)
   - `:session` - Session options (model, tools, streaming?, etc.)
   - `:timeout-ms` - Idle/event wait timeout for `query`, `query-seq!`, and
     `with-query-seq` (default: 60000)
   "
  (:require [clojure.core.async :as async :refer [go-loop <! chan close! alts!]]
            [clojure.core.async.impl.protocols :as async-protocols]
            [github.copilot-sdk :as copilot]
            [github.copilot-sdk.logging :as log]
            [github.copilot-sdk.session :as session]
            [github.copilot-sdk.teardown :as teardown]))

;; =============================================================================
;; Internal State
;; =============================================================================

(def ^:private ^:const shutdown-timeout-ms 5000)

;; Atom holding {:client <CopilotClient> :client-opts <normalized-opts>}
(defonce ^:private client-state (atom nil))

(defn- run-with-timeout
  "Run f with a timeout. Returns true if completed, false if timed out or threw."
  [f timeout-ms]
  (let [result (promise)
        thread (Thread. (fn []
                          (try
                            (f)
                            (deliver result :ok)
                            (catch Exception _
                              (deliver result :error)))))]
    (.start thread)
    (let [r (deref result timeout-ms :timeout)]
      (when (= r :timeout)
        (try (.interrupt thread) (catch Exception _)))
      (= r :ok))))

;; Register JVM shutdown hook to clean up client automatically
(defonce ^:private _shutdown-hook
  (.addShutdownHook
   (Runtime/getRuntime)
   (Thread.
    (fn []
      (when-let [{:keys [client]} @client-state]
        (let [stopped? (run-with-timeout #(copilot/stop! client) shutdown-timeout-ms)]
          (when-not stopped?
            (run-with-timeout #(copilot/force-stop! client) shutdown-timeout-ms)))
        (reset! client-state nil))))))

(defn- normalize-client-opts
  "Normalize client options for comparison."
  [opts]
  (select-keys (or opts {}) [:cli-path :cli-args :cwd :port :use-stdio?
                             :log-level :auto-restart? :env]))

(defn- opts-match?
  "Check if client options match the current client."
  [opts]
  (let [current (:client-opts @client-state)
        normalized (normalize-client-opts opts)]
    (= current normalized)))

(defn- ensure-client!
  "Ensure a connected client exists with matching options.
   Returns the client."
  [client-opts]
  (let [normalized (normalize-client-opts client-opts)]
    (locking client-state
      (let [{:keys [client client-opts]} @client-state]
        (cond
          ;; No client exists - create one
          (nil? client)
          (let [new-client (copilot/client normalized)]
            (copilot/start! new-client)
            (reset! client-state {:client new-client :client-opts normalized})
            new-client)

          ;; Client exists with matching opts - reuse
          (= client-opts normalized)
          client

          ;; Client exists with different opts - replace
          :else
          (do
            (try (copilot/stop! client) (catch Exception _))
            (let [new-client (copilot/client normalized)]
              (copilot/start! new-client)
              (reset! client-state {:client new-client :client-opts normalized})
              new-client)))))))

(defn- build-session-config
  "Build session config from options map."
  [session-opts]
  (let [{:keys [on-permission-request model system-prompt tools allowed-tools excluded-tools
                streaming? mcp-servers custom-agents config-dir
                skill-directories disabled-skills]} session-opts]
    (cond-> {}
      on-permission-request (assoc :on-permission-request on-permission-request)
      model (assoc :model model)
      system-prompt (assoc :system-message {:mode :append :content system-prompt})
      tools (assoc :tools tools)
      allowed-tools (assoc :available-tools allowed-tools)
      excluded-tools (assoc :excluded-tools excluded-tools)
      streaming? (assoc :streaming? streaming?)
      mcp-servers (assoc :mcp-servers mcp-servers)
      custom-agents (assoc :custom-agents custom-agents)
      config-dir (assoc :config-dir config-dir)
      skill-directories (assoc :skill-directories skill-directories)
      disabled-skills (assoc :disabled-skills disabled-skills))))

(defn- client-instance?
  "Check if x is a CopilotClient instance (has :state atom)."
  [x]
  (and (map? x) (contains? x :state) (instance? clojure.lang.Atom (:state x))))

(defn- session-instance?
  "Check if x is a CopilotSession instance (has :session-id and :client)."
  [x]
  (and (record? x) (contains? x :session-id) (contains? x :client)))

(defn- disconnect-owned-session!
  [{:keys [client session-id] :as owned-session}]
  (try
    (copilot/disconnect! owned-session)
    (catch Throwable failure
      (teardown/cleanup-preserving!
       failure
       #(session/teardown-local! client session-id))
      (throw failure))))

(defn- call-with-owned-session
  [client session-config f]
  (let [owned-session (copilot/create-session client session-config)]
    (teardown/call-with-cleanup
     #(f owned-session)
     #(disconnect-owned-session! owned-session))))

(defn- query-chan-cleanup-error
  [failure]
  {:type :copilot/session.error
   :data {:message "Failed to disconnect helper-owned query session"
          :cause failure}})

(defn- report-query-chan-cleanup-failure!
  [failure]
  (log/warn failure "Failed to disconnect helper-owned query session"))

(defn- cancellable-channel
  [out-ch cancel-ch disconnect-ch]
  (reify
    async-protocols/ReadPort
    (take! [_ handler]
      (async-protocols/take! out-ch handler))

    async-protocols/WritePort
    (put! [_ value handler]
      (async-protocols/put! out-ch value handler))

    async-protocols/Channel
    (close! [_]
      (close! cancel-ch)
      (close! out-ch)
      (force disconnect-ch)
      nil)
    (closed? [_]
      (async-protocols/closed? out-ch))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn shutdown!
  "Shutdown the shared client. Call for clean exit.
   Safe to call multiple times or when no client exists."
  []
  (locking client-state
    (when-let [{:keys [client]} @client-state]
      (try (copilot/stop! client) (catch Exception _)))
    (reset! client-state nil))
  nil)

(defn client-info
  "Get information about the current shared client state.
   Returns nil if no client exists, otherwise:
   {:client-opts <map> :connected? <bool>}"
  []
  (when-let [{:keys [client client-opts]} @client-state]
    {:client-opts client-opts
     :connected? (= :connected (copilot/state client))}))

(defn query
  "Execute a query and return the response text.

   Arguments:
     prompt - The prompt string to send

   Keyword options:
     :client - Client options map OR a CopilotClient instance
     :session - Session options map OR a CopilotSession instance
     :timeout-ms - Session idle-wait timeout in milliseconds (default: 60000)

   When :session is a CopilotSession instance, the query uses that session
   directly (enabling multi-turn conversations). Otherwise creates a fresh session.

   When :client is a CopilotClient instance, uses it directly.
   When :client is a map, uses/creates a shared client with those options.

   Returns the assistant's response text as a string.

   Examples:
     ;; Simple query (shared client, fresh session)
     (query \"What is 2+2?\" :session {:on-permission-request copilot/approve-all})

     ;; With session options
     (query \"Explain monads\" :session {:on-permission-request copilot/approve-all
                                        :model \"claude-sonnet-4.5\"})

     ;; With explicit client
     (copilot/with-client [c {}]
       (query \"Hello\" :client c :session {:on-permission-request copilot/approve-all}))

     ;; With explicit session (multi-turn)
     (copilot/with-session [s client {:on-permission-request copilot/approve-all
                                      :model \"gpt-5.4\"}]
       (query \"What is 2+2?\" :session s)
       (query \"And 3+3?\" :session s))  ;; context preserved
   "
  [prompt & {:keys [client session timeout-ms] :or {timeout-ms 60000}}]
  (cond
    ;; Session instance provided - use directly
    (session-instance? session)
    (-> (copilot/send-and-wait! session {:prompt prompt} timeout-ms)
        (get-in [:data :content]))

    ;; Client instance provided - create temp session
    (client-instance? client)
    (call-with-owned-session
     client
     (build-session-config session)
     #(-> (copilot/send-and-wait! % {:prompt prompt} timeout-ms)
          (get-in [:data :content])))

    ;; Default - use shared client
    :else
    (let [c (ensure-client! client)
          session-config (build-session-config session)]
      (call-with-owned-session
       c
       session-config
       #(-> (copilot/send-and-wait! % {:prompt prompt} timeout-ms)
            (get-in [:data :content]))))))

(defn- terminal-query-event?
  [event]
  (or (= :copilot/session.error (:type event))
      (session/terminal-idle-event? event)))

(defn- query-seq-source
  [prompt & {:keys [client session max-events timeout-ms]
             :or {max-events 256
                  timeout-ms 60000}}]
  (when-not (nat-int? max-events)
    (throw (ex-info ":max-events must be a non-negative integer"
                    {:max-events max-events})))
  (when-not (or (nil? timeout-ms) (pos-int? timeout-ms))
    (throw (ex-info ":timeout-ms must be a positive integer or nil"
                    {:timeout-ms timeout-ms})))
  (let [c (if (client-instance? client)
            client
            (ensure-client! client))
        session-config (build-session-config session)
        sess (copilot/create-session c session-config)
        deadline-ch (when timeout-ms (async/timeout timeout-ms))
        done? (atom false)]
    (letfn [(finish! []
              (when (compare-and-set! done? false true)
                (disconnect-owned-session! sess)))
            (event-seq [events-ch remaining]
              (lazy-seq
               (when (pos? remaining)
                 (let [[event port]
                       (if deadline-ch
                         (async/alts!! [deadline-ch events-ch] :priority true)
                         [(async/<!! events-ch) events-ch])]
                   (cond
                     (identical? port deadline-ch)
                     (let [failure
                           (ex-info
                            (str "Query timed out after " timeout-ms " ms")
                            {:type :query-timeout
                             :timeout-ms timeout-ms})]
                       (teardown/cleanup-preserving! failure finish!)
                       (throw failure))

                     (nil? event)
                     (do (finish!) nil)

                     (terminal-query-event? event)
                     (do (finish!) (cons event nil))

                     :else
                     (cons event (event-seq events-ch (dec remaining))))))))]
      (try
        (let [events-ch (copilot/subscribe-events sess)]
          (copilot/send! sess {:prompt prompt})
          (let [events (event-seq events-ch max-events)]
            (when (zero? max-events) (finish!))
            [events finish!]))
        (catch Throwable t
          (teardown/cleanup-preserving! t finish!)
          (throw t))))))

(defmacro with-query-seq
  "Execute a query, bind a bounded lazy sequence of events, and clean up on body exit.

   Use this for seq-style streaming consumption when the body may stop before the
   session reaches a terminal event. Cleanup runs in a `finally`, so the session
   disconnects whether the body returns normally, stops after a partial realization,
   or throws. If both the body and disconnect fail, the body failure remains
   primary and the disconnect failure is attached as suppressed.

   Binding form:
     [events prompt & {:keys [client session max-events timeout-ms]}]

   Keyword options match `query-seq!`:
     :client - Client options map or CopilotClient instance
     :session - Session options map
     :max-events - Maximum number of events to emit (default: 256)
     :timeout-ms - Deadline observed during event consumption (default: 60000);
                   starts after session creation; nil disables it

   Examples:
     (with-query-seq [events \"Tell me a story\"
                      :session {:on-permission-request copilot/approve-all
                                :streaming? true}]
       (run! println events))"
  [bindings & body]
  (when-not (and (vector? bindings)
                 (<= 2 (count bindings))
                 (symbol? (first bindings)))
    (throw (IllegalArgumentException.
            "with-query-seq requires [events prompt & options] binding form")))
  (let [events-sym (first bindings)
        query-args (rest bindings)
        finish-sym (gensym "finish!")]
    `(let [[~events-sym ~finish-sym] (#'query-seq-source ~@query-args)]
       (teardown/call-with-cleanup
        (fn [] ~@body)
        ~finish-sym))))

(defn query-seq!
  "Execute a query and return a bounded lazy sequence of events.

   Cleanup (session disconnect) happens only when the sequence is realized all the
   way to the end of the event stream: either an ordinary
   `:copilot/session.idle` event, a `:copilot/session.error` event, or the events
   channel closing - detected when the next read yields `nil` (the end-of-stream
   sentinel, not an emitted event). An idle event whose wire `:mode` is the string
   `\"autopilot\"` is emitted as a nonterminal turn boundary.
   Consuming the whole seq to its natural end releases the session and its event
   tap.

   WARNING: cleanup is tied to reaching that end of stream, so a consumer that
   abandons the seq before it reaches a terminal event leaks the session and its
   event tap. For example `(first (query-seq! ...))` or `(take 1 (query-seq! ...))`
   realize just one element: they leak unless that first element already happens
   to be a terminal `:copilot/session.idle` / `:copilot/session.error` event
   (realizing the terminal event runs cleanup). The `:max-events` bound only caps
   how many events are yielded — it is not a cleanup guarantee; hitting a positive
   bound before a terminal event still leaks the session (the sole exception is
   `:max-events 0`, which disconnects immediately without emitting anything).
   Only use `query-seq!` when you will consume the sequence to its natural end.
   If you may stop early, prefer `with-query-seq` (scope-bound seq consumption)
   or `query` (single response, deterministic cleanup).

   Keyword options:
     :client - Client options map or CopilotClient instance
     :session - Session options map
     :max-events - Maximum number of events to emit (default: 256)
     :timeout-ms - Deadline observed during event consumption (default: 60000);
                   starts after session creation; nil disables it

   Returns a lazy sequence of at most :max-events events."
  [prompt & {:keys [client session max-events timeout-ms]
             :or {max-events 256
                  timeout-ms 60000}}]
  (first (apply query-seq-source
                prompt
                (cond-> [:max-events max-events :timeout-ms timeout-ms]
                  (some? client) (into [:client client])
                  (some? session) (into [:session session])))))

(defn query-chan
  "Execute a query and return a core.async channel of events.

   This allows asynchronous processing of session events using
   core.async primitives. Closing the returned channel cancels the query and
   disconnects its hidden session, including when its bounded output buffer is
   full. Values accepted into the buffer before cancellation remain readable;
   an in-flight event whose parked put loses to cancellation may be dropped.

   Arguments:
     prompt - The prompt string to send

   Keyword options:
     :client - Client options map
     :session - Session options map
     :buffer - Channel buffer size (default: 256)

   Returns a channel that yields event maps. The channel closes when the
   session ordinarily becomes idle or errors. If disconnecting the hidden
   session then fails, the channel yields a tagged `:copilot/session.error`
   map after the terminal event and closes. The original failure is available
   at `[:data :cause]`. An idle event whose wire `:mode` is the string
   `\"autopilot\"` is emitted without closing the channel. Consumer
   cancellation still releases the hidden session locally; a runtime cleanup
   failure is logged because the output channel is already closed.

   Examples:
     (let [ch (query-chan \"Tell me a story\" :session {:on-permission-request copilot/approve-all
                                                       :streaming? true})]
       (go-loop [remaining 10]
         (when-let [event (<! ch)]
           (when (= :copilot/assistant.message_delta (:type event))
             (print (get-in event [:data :delta-content])))
           (if (= remaining 1)
             (close! ch)
             (recur (dec remaining))))))
   "
  [prompt & {:keys [client session buffer] :or {buffer 256}}]
  (when-not (pos-int? buffer)
    (throw (ex-info ":buffer must be a positive integer" {:buffer buffer})))
  (let [c (ensure-client! client)
        session-config (build-session-config session)
        sess (copilot/create-session c session-config)
        ;; disconnect! blocks (thread joins), so start it at most once on a real
        ;; thread and retain its failure as a value for the producer.
        disconnect-ch
        (delay
          (async/thread
            (try
              (disconnect-owned-session! sess)
              {:status :ok}
              (catch Throwable failure
                (report-query-chan-cleanup-failure! failure)
                {:status :error
                 :failure failure}))))]
    (try
      (let [cancel-ch (chan)
            out-ch (chan buffer)
            events-ch (copilot/subscribe-events sess)
            result-ch (cancellable-channel out-ch cancel-ch disconnect-ch)]
        (copilot/send! sess {:prompt prompt})

        (go-loop []
          (let [[event source] (alts! [cancel-ch events-ch] :priority true)]
            (cond
              (identical? source cancel-ch)
              (do
                (<! (force disconnect-ch))
                (close! out-ch))

              (nil? event)
              (let [{:keys [status failure]} (<! (force disconnect-ch))]
                (when (= :error status)
                  (alts! [cancel-ch
                          [out-ch (query-chan-cleanup-error failure)]]
                         :priority true))
                (close! out-ch))

              :else
              (let [[accepted? destination]
                    (alts! [cancel-ch [out-ch event]] :priority true)]
                (if (and (identical? destination out-ch)
                         (true? accepted?))
                  (if (terminal-query-event? event)
                    (let [{:keys [status failure]} (<! (force disconnect-ch))]
                      (when (= :error status)
                        (alts! [cancel-ch
                                [out-ch (query-chan-cleanup-error failure)]]
                               :priority true))
                      (close! out-ch))
                    (recur))
                  (do
                    (<! (force disconnect-ch))
                    (close! out-ch)))))))

        result-ch)
      (catch Throwable t
        (let [{:keys [failure]} (async/<!! (force disconnect-ch))]
          (when failure
            (teardown/cleanup-preserving! t #(throw failure))))
        (throw t)))))
