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
   (h/query \"Explain monads\" :session {:model \"claude-sonnet-4.5\"})
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
   - `:timeout-ms` - Timeout for blocking `query` (default: 180000)
   "
  (:require [clojure.core.async :as async :refer [go go-loop <! >! chan close! timeout alts!]]
            [github.copilot-sdk :as copilot]))

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
          (let [new-client (copilot/client (or client-opts {}))]
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
  (let [{:keys [model system-prompt tools allowed-tools excluded-tools
                streaming? mcp-servers custom-agents config-dir
                skill-directories disabled-skills]} session-opts]
    (cond-> {}
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
     :timeout-ms - Timeout in milliseconds (default: 180000)

   When :session is a CopilotSession instance, the query uses that session
   directly (enabling multi-turn conversations). Otherwise creates a fresh session.

   When :client is a CopilotClient instance, uses it directly.
   When :client is a map, uses/creates a shared client with those options.

   Returns the assistant's response text as a string.

   Examples:
     ;; Simple query (shared client, fresh session)
     (query \"What is 2+2?\")

     ;; With session options
     (query \"Explain monads\" :session {:model \"claude-sonnet-4.5\"})

     ;; With explicit client
     (copilot/with-client [c {}]
       (query \"Hello\" :client c))

     ;; With explicit session (multi-turn)
     (copilot/with-session [s client {:model \"gpt-5.2\"}]
       (query \"What is 2+2?\" :session s)
       (query \"And 3+3?\" :session s))  ;; context preserved
   "
  [prompt & {:keys [client session timeout-ms] :or {timeout-ms 180000}}]
  (cond
    ;; Session instance provided - use directly
    (session-instance? session)
    (-> (copilot/send-and-wait! session {:prompt prompt} timeout-ms)
        (get-in [:data :content]))

    ;; Client instance provided - create temp session
    (client-instance? client)
    (copilot/with-session [sess client (build-session-config session)]
      (-> (copilot/send-and-wait! sess {:prompt prompt} timeout-ms)
          (get-in [:data :content])))

    ;; Default - use shared client
    :else
    (let [c (ensure-client! client)
          session-config (build-session-config session)
          sess (copilot/create-session c session-config)]
      (try
        (let [response (copilot/send-and-wait! sess {:prompt prompt} timeout-ms)]
          (get-in response [:data :content]))
        (finally
          (copilot/destroy! sess))))))

(defn query-seq!
  "Execute a query and return a lazy sequence of events with guaranteed cleanup.
   
   This variant limits consumption and ensures the session is destroyed even if
   the consumer stops early.
   
   Keyword options:
     :client - Client options map
     :session - Session options map
     :max-events - Maximum number of events to emit (default: 256)
   
   Returns a lazy sequence of at most :max-events events."
  [prompt & {:keys [client session max-events] :or {max-events 256}}]
  (let [c (ensure-client! client)
        session-config (build-session-config session)
        sess (copilot/create-session c session-config)
        events-ch (copilot/subscribe-events sess)
        done? (atom false)]
    (copilot/send! sess {:prompt prompt})
    (letfn [(finish! []
              (when-not @done?
                (reset! done? true)
                (copilot/destroy! sess)))
            (event-seq [remaining]
              (lazy-seq
               (when (pos? remaining)
                 (let [event (async/<!! events-ch)]
                   (cond
                     (nil? event)
                     (do (finish!) nil)

                     (#{:copilot/session.idle :copilot/session.error} (:type event))
                     (do (finish!) (cons event nil))

                     :else
                     (cons event (event-seq (dec remaining))))))))]
      (let [events (event-seq max-events)]
        (when (zero? max-events) (finish!))
        events))))

(defn query-chan
  "Execute a query and return a core.async channel of events.
   
   This allows asynchronous processing of session events using
   core.async primitives.
   
   Arguments:
     prompt - The prompt string to send
   
   Keyword options:
     :client - Client options map
     :session - Session options map
     :buffer - Channel buffer size (default: 256)
   
   Returns a channel that yields event maps. The channel closes when
   the session becomes idle or errors.
   
   Examples:
     (let [ch (query-chan \"Tell me a story\" :session {:streaming? true})]
       (go-loop []
         (when-let [event (<! ch)]
           (when (= :copilot/assistant.message_delta (:type event))
             (print (get-in event [:data :delta-content])))
           (recur))))
   "
  [prompt & {:keys [client session buffer] :or {buffer 256}}]
  (let [c (ensure-client! client)
        session-config (build-session-config session)
        sess (copilot/create-session c session-config)
        events-ch (copilot/subscribe-events sess)
        out-ch (chan buffer)]

    ;; Send the prompt
    (copilot/send! sess {:prompt prompt})

    ;; Pipe events to output channel, cleanup on completion
    (go-loop []
      (if-let [event (<! events-ch)]
        (do
          (>! out-ch event)
          (if (#{:copilot/session.idle :copilot/session.error} (:type event))
            (do
              (copilot/destroy! sess)
              (close! out-ch))
            (recur)))
        (do
          (copilot/destroy! sess)
          (close! out-ch))))

    out-ch))
