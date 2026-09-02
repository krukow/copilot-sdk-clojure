(ns github.copilot-sdk.session
  "CopilotSession - session operations using centralized client state.
   
   All session state is stored in the client's :state atom under:
   - [:sessions session-id] -> {:tool-handlers {} :permission-handler nil :destroyed? false :workspace-path nil}
   - [:session-io session-id] -> {:event-chan :event-mult}
   
   Functions take client + session-id, accessing state through the client."
  (:require [clojure.core.async :as async :refer [go go-loop <! >! >!! <!! chan close! put! alts!! mult tap untap]]
            [clojure.core.async.impl.protocols :as async-protocols]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [github.copilot-sdk.protocol :as proto]
            [github.copilot-sdk.factory :as factory]
            [github.copilot-sdk.logging :as log]
            [github.copilot-sdk.specs :as specs]
            [github.copilot-sdk.util :as util]
            [github.copilot-sdk.generated.coerce :as coerce]))

;; -----------------------------------------------------------------------------
;; State accessors - all state lives in client's atom
;; -----------------------------------------------------------------------------

(defn- session-state [client session-id]
  (get-in @(:state client) [:sessions session-id]))

(defn- session-io [client session-id]
  (get-in @(:state client) [:session-io session-id]))

(defn- session-disconnected?
  [client session-id]
  (not (false? (:destroyed? (session-state client session-id)))))

(defn- update-session! [client session-id f & args]
  (swap! (:state client)
         (fn [state]
           (let [session (get-in state [:sessions session-id])]
             (if (and session (not (:destroyed? session)))
               (apply update-in state [:sessions session-id] f args)
               state)))))

(defn- connection-io [client]
  (:connection-io @(:state client)))

(def ^:private tool-search-tool-name "tool_search_tool")

(defn- current-tool-metadata
  [client session-id]
  (try
    (:tools (proto/send-request! (connection-io client)
                                 "session.tools.getCurrentMetadata"
                                 {:session-id session-id}))
    (catch Exception e
      (log/debug "Failed to fetch tool metadata for tool search: " (ex-message e))
      nil)))

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
   Initializes session state in client's atom and returns a CopilotSession handle.
   Retains the validated config, including the experimental :enable-mcp-apps
   host opt-in after the client performs wire negotiation.
   If :on-event is provided, taps a subscriber that forwards events to the handler
   on a dedicated thread. Uses a sliding buffer, so events may be dropped under
   extreme backpressure if the handler cannot keep up with the event rate."
  [client session-id {:keys [tools on-permission-request on-user-input-request on-elicitation-request
                             on-exit-plan-mode on-auto-mode-switch on-mcp-auth-request
                             hooks workspace-path on-event config commands]}]
  (log/debug "Creating session: " session-id)
  (let [factory-definitions (factory/definitions-by-name (:factories config))
        event-chan (chan (async/sliding-buffer 4096))
        event-mult (mult event-chan)
        send-lock (doto (chan 1) (>!! :token))
        ;; Upstream PR #1308: declaration-only tools (no :tool-handler) are
        ;; left out of the handler map — they're distinguished from tools with
        ;; handlers, and unhandled invocations are left pending for manual
        ;; resolution via handle-pending-tool-call!.
        tool-handlers (into {} (keep (fn [t]
                                       (when-let [h (:tool-handler t)]
                                         [(:tool-name t) h]))
                                     tools))
        command-handlers (into {} (map (fn [c] [(:name c) (:command-handler c)]) commands))]
    ;; Store session state and IO in client's atom
    (let [[_ registered-state]
          (swap-vals! (:state client)
                      (fn [state]
                        (if (or (:stopping? state)
                                (contains? (:disconnecting-session-ids state)
                                           session-id))
                          state
                          (-> state
                              (assoc-in [:sessions session-id]
                                        {:tool-handlers tool-handlers
                                         :command-handlers command-handlers
                                         :permission-handler on-permission-request
                                         :mcp-auth-handler on-mcp-auth-request
                                         :user-input-handler on-user-input-request
                                         :elicitation-handler on-elicitation-request
                                         :exit-plan-mode-handler on-exit-plan-mode
                                         :auto-mode-switch-handler on-auto-mode-switch
                                         :hooks hooks
                                         :factories factory-definitions
                                         :factory-executions {}
                                         :managed-settings-enabled?
                                         (or (true? (:enable-managed-settings? config))
                                             (some? (:managed-settings config)))
                                         :destroyed? false
                                         :workspace-path workspace-path
                                         :capabilities {}
                                         :open-canvases []
                                         :config config})
                              (assoc-in [:session-io session-id]
                                        {:event-chan event-chan
                                         :event-mult event-mult
                                         :send-lock send-lock})))))]
      (when-not (identical? event-chan
                            (get-in registered-state [:session-io session-id :event-chan]))
        (close! event-chan)
        (close! send-lock)
        (throw (ex-info (cond
                          (:stopping? registered-state)
                          "Client is stopping; cannot create session"

                          (contains?
                           (:disconnecting-session-ids registered-state)
                           session-id)
                          "Session disconnect is in progress; cannot create session"

                          :else
                          "Session registration was superseded")
                        {:session-id session-id}))))
    ;; If an on-event handler is provided, tap and forward events to it.
    ;; Uses async/thread to avoid blocking core.async dispatch threads,
    ;; since user handlers may perform blocking I/O.
    ;; The handler channel uses a sliding buffer — if the handler cannot keep up
    ;; with the event rate, oldest unprocessed events are silently dropped.
    (when on-event
      (let [handler-ch (chan (async/sliding-buffer 1024))]
        (tap event-mult handler-ch)
        (async/thread
          (loop []
            (if-let [event (<!! handler-ch)]
              (do
                (try
                  (on-event event)
                  (catch Throwable t
                    (log/warn t "on-event handler threw"
                              {:session-id session-id
                               :event-type (:type event)})))
                (recur))
              ;; Channel closed — session torn down
              nil)))))
    (log/debug "Session created: " session-id)
    ;; Return lightweight handle
    (->CopilotSession session-id client)))

(defn set-workspace-path!
  "Update the workspace path in session state. Called after RPC response."
  [client session-id workspace-path]
  (when workspace-path
    (update-session! client session-id assoc :workspace-path workspace-path)))

(defn set-capabilities!
  "Store host capabilities in session state. Called after session.create/session.resume RPC."
  [client session-id capabilities]
  (update-session! client session-id assoc :capabilities (or capabilities {})))

;; --- Open canvas snapshot (upstream PR #1604) -------------------------------
;; The CLI host can open auxiliary UI canvases inside a session. The SDK keeps
;; an in-memory snapshot of currently-open canvases per session. The snapshot
;; is initialized from `session.resume` (NOT `session.create` — matches upstream
;; client.ts behavior) and mutated by `session.canvas.opened` /
;; `session.canvas.closed` events. Each entry's keys go through `wire->clj`
;; kebab-case conversion (`:instance-id`, `:extension-id`, `:canvas-id`,
;; optional `:extension-name`, `:title`, `:status`, `:url`, `:input`). The
;; opaque `:input` map (caller-defined keys) is preserved verbatim by the
;; protocol layer so source-supplied keys are not kebab-cased.

(defn- valid-open-canvas-instance?
  "Mirrors upstream `isOpenCanvasInstance` (nodejs/src/session.ts). Requires the
   three id fields (`:instance-id`, `:extension-id`, `:canvas-id`) to be present
   and non-blank strings before an entry is admitted to the snapshot. As of
   upstream v1.0.4 the `OpenCanvasInstance` shape dropped `reopen`/`availability`."
  [data]
  (and (map? data)
       (string? (:instance-id data)) (not (str/blank? (:instance-id data)))
       (string? (:extension-id data)) (not (str/blank? (:extension-id data)))
       (string? (:canvas-id data)) (not (str/blank? (:canvas-id data)))))

(defn set-open-canvases!
  "Replace the open-canvases snapshot for `session-id`. Called once after
  `session.resume` succeeds. Sanitizes the input the same way live
  `session.canvas.opened` upserts do — non-sequential values are treated as
  empty, and entries failing `valid-open-canvas-instance?` are dropped with a
  warning so the snapshot invariant holds for callers (and instrumentation)."
  [client session-id canvases]
  (let [coll (when (sequential? canvases) canvases)
        {:keys [valid invalid]} (group-by (fn [c]
                                            (if (valid-open-canvas-instance? c)
                                              :valid :invalid))
                                          coll)]
    (when (seq invalid)
      (log/warn "dropping invalid entries from session.resume openCanvases"
                {:session-id session-id
                 :dropped-count (count invalid)}))
    (update-session! client session-id assoc :open-canvases (vec valid))))

(defn upsert-open-canvas!
  "Apply a `session.canvas.opened` event payload to the snapshot. If an entry
  with the same `:instance-id` exists, replace it in place (preserves order);
  otherwise append. Logs a warning and no-ops on payloads missing any of the
  required id fields (`:instance-id`, `:extension-id`, `:canvas-id`), matching
  upstream `isOpenCanvasInstance` validation."
  [client session-id data]
  (if-not (valid-open-canvas-instance? data)
    (log/warn "failed to deserialize session.canvas.opened payload"
              {:session-id session-id})
    (let [iid (:instance-id data)]
      (update-session! client session-id update :open-canvases
                       (fn [canvases]
                         (let [canvases (vec (or canvases []))
                               idx (first (keep-indexed
                                           (fn [i c] (when (= (:instance-id c) iid) i))
                                           canvases))]
                           (if idx
                             (assoc canvases idx data)
                             (conj canvases data))))))))

(defn remove-open-canvas!
  "Apply a `session.canvas.closed` event payload to the snapshot — removes the
  entry with matching `:instance-id`. Logs a warning and no-ops when
  `:instance-id` is not a non-blank string. Closing an absent instance is a
  silent no-op (idempotent), matching upstream `removeOpenCanvas`."
  [client session-id data]
  (let [iid (:instance-id data)]
    (if-not (and (string? iid) (not (str/blank? iid)))
      (log/warn "failed to deserialize session.canvas.closed payload"
                {:session-id session-id})
      (update-session! client session-id update :open-canvases
                       (fn [canvases]
                         (filterv #(not= (:instance-id %) iid) (or canvases [])))))))

(defn register-transform-callbacks!
  "Store system message transform callbacks on a session.
   Callbacks is a map of wire section ID strings to 1-arity functions
   that receive current content and return transformed content."
  [client session-id callbacks]
  (when callbacks
    (update-session! client session-id assoc :transform-callbacks callbacks)))

(defn- validate-session-fs-handler!
  [handler context]
  (when-not (s/valid? ::specs/session-fs-handler handler)
    (throw (ex-info "Invalid sessionFs handler"
                    (merge {:handler handler
                            :explain (s/explain-data ::specs/session-fs-handler handler)}
                           context))))
  handler)

(defn- validate-session-fs-provider!
  [provider context]
  (when-not (s/valid? ::specs/session-fs-provider provider)
    (throw (ex-info "Invalid sessionFs provider"
                    (merge {:provider provider
                            :explain (s/explain-data ::specs/session-fs-provider provider)}
                           context))))
  provider)

(defn set-session-fs-handler!
  "Store a sessionFs handler map on a session. Called by client during create/resume
   when :session-fs is enabled. Handler is a map of keyword→fn for FS operations.

   Upstream PR #1299: when the client's :session-fs config declares
   `:capabilities {:sqlite true}` the per-session handler must expose BOTH
   :sqlite-query and :sqlite-exists. Otherwise the runtime would route
   sessionFs.sqliteExists (or sessionFs.sqliteQuery) to a missing handler key
   and surface an opaque \"Unknown sessionFs method\" error at runtime."
  [client session-id handler]
  (let [validated (validate-session-fs-handler! handler {:session-id session-id})
        sqlite-declared? (boolean (get-in client [:session-fs :capabilities :sqlite]))
        missing (when sqlite-declared?
                  (remove #(contains? validated %) [:sqlite-query :sqlite-exists]))]
    (when (seq missing)
      (throw (ex-info
              "SessionFs config declares capabilities.sqlite but the provider does not implement sqlite."
              {:session-id session-id
               :capabilities (get-in client [:session-fs :capabilities])
               :missing-handlers (vec missing)})))
    (update-session! client session-id assoc :session-fs-handler validated)))

(defn- channel?
  "Check if x is a core.async channel."
  [x]
  (satisfies? async-protocols/ReadPort x))

(defn- accepts-arity?
  [f arity]
  (and (some? f)
       (let [fixed-arity? (boolean
                           (some (fn [^java.lang.reflect.Method method]
                                   (and (= "invoke" (.getName method))
                                        (= arity (.getParameterCount method))))
                                 (.getDeclaredMethods (class f))))
             variadic-min-arity (when (instance? clojure.lang.RestFn f)
                                  (.getRequiredArity ^clojure.lang.RestFn f))]
         (or fixed-arity?
             (and (some? variadic-min-arity)
                  (<= variadic-min-arity arity))))))

(defn- session-fs-error
  "Convert a provider exception to the SessionFsError shape expected by the CLI."
  [err]
  (let [code (or (:code (ex-data err))
                 (when (instance? java.io.FileNotFoundException err) "ENOENT"))
        code (if (= "ENOENT" code) "ENOENT" "UNKNOWN")]
    {:code code
     :message (or (ex-message err) (str err))}))

(defn session-fs-sqlite-transaction-failure
  "Create a classified SQLite transaction failure.

   Error class is one of :busy-or-locked, :post-commit-ambiguous, or :fatal."
  ([message]
   (session-fs-sqlite-transaction-failure message :fatal))
  ([message error-class]
   (when-not (s/valid? ::specs/sqlite-transaction-error-class error-class)
     (throw (ex-info "Invalid SQLite transaction error class"
                     {:error-class error-class})))
   (ex-info message
            {:type :session-fs-sqlite-transaction-failure
             :error-class error-class})))

(defn session-fs-sqlite-transaction-failure?
  "Return true when value is a classified SQLite transaction failure."
  [value]
  (and (instance? clojure.lang.ExceptionInfo value)
       (= :session-fs-sqlite-transaction-failure (:type (ex-data value)))
       (s/valid? ::specs/sqlite-transaction-error-class
                 (:error-class (ex-data value)))))

(defn- sqlite-transaction-error-class->wire [error-class]
  (case error-class
    :busy-or-locked "busyOrLocked"
    :post-commit-ambiguous "postCommitAmbiguous"
    "fatal"))

(defn- sqlite-transaction-error [error]
  {:error-class
   (sqlite-transaction-error-class->wire
    (when (session-fs-sqlite-transaction-failure? error)
      (:error-class (ex-data error))))
   :message (or (ex-message error) (str error))})

(defn- await-session-fs-result
  [result]
  (cond
    (channel? result) (<!! result)
    (or (instance? java.util.concurrent.Future result)
        (instance? clojure.lang.IPending result)) @result
    :else result))

(defn- session-fs-void-result
  [f args params]
  (try
    (await-session-fs-result (apply f args))
    nil
    (catch clojure.lang.ArityException _
      (try
        (await-session-fs-result (f params))
        nil
        (catch Throwable t
          (session-fs-error t))))
    (catch Throwable t
      (session-fs-error t))))

(defn create-session-fs-adapter
  "Adapt a provider-style session filesystem implementation to a sessionFs handler map.

   Provider functions use direct arguments and throw on errors:
   - :read-file          (fn [path] content)
   - :write-file         (fn [path content mode])
   - :append-file        (fn [path content mode])
   - :exists             (fn [path] boolean)
   - :stat               (fn [path] file-info-map)
   - :mkdir              (fn [path recursive mode])
   - :readdir            (fn [path] entries)
   - :readdir-with-types (fn [path] typed-entries)
   - :rm                 (fn [path recursive force])
   - :rename             (fn [src dest])
   Provider functions may return values directly, core.async channels, futures,
   or promises.

   The returned handler map has the low-level RPC contract: each function
   receives a params map and returns RPC-shaped result maps or structured
   SessionFsError maps. create-session/resume-session automatically adapt
   provider-style factory returns, so call this directly only when you need the
   low-level handler map yourself.

   Existing low-level handler maps returned from :create-session-fs-handler are
   preserved by the client registration path; this helper is for provider-style
   maps."
  [provider]
  (let [provider (validate-session-fs-provider! provider {:contract :session-fs-provider})
        base-handler
        {:read-file
         (fn [{:keys [path]}]
           (try
             (let [result (await-session-fs-result ((:read-file provider) path))]
               (if (and (map? result)
                        (or (contains? result :content)
                            (contains? result :error)))
                 result
                 {:content result}))
             (catch Throwable t
               {:content "" :error (session-fs-error t)})))

         :write-file
         (fn [{:keys [path content mode] :as params}]
           (session-fs-void-result (:write-file provider) [path content mode] params))

         :append-file
         (fn [{:keys [path content mode] :as params}]
           (session-fs-void-result (:append-file provider) [path content mode] params))

         :exists
         (fn [{:keys [path]}]
           (try
             (let [result (await-session-fs-result ((:exists provider) path))]
               (if (and (map? result)
                        (or (contains? result :exists)
                            (contains? result :error)))
                 result
                 {:exists (boolean result)}))
             (catch Throwable _
               {:exists false})))

         :stat
         (fn [{:keys [path]}]
           (try
             (await-session-fs-result ((:stat provider) path))
             (catch Throwable t
               {:is-file false
                :is-directory false
                :size 0
                :mtime (.toString (java.time.Instant/now))
                :birthtime (.toString (java.time.Instant/now))
                :error (session-fs-error t)})))

         :mkdir
         (fn [{:keys [path recursive mode] :as params}]
           (session-fs-void-result (:mkdir provider) [path (boolean recursive) mode] params))

         :readdir
         (fn [{:keys [path]}]
           (try
             (let [result (await-session-fs-result ((:readdir provider) path))]
               (if (and (map? result)
                        (or (contains? result :entries)
                            (contains? result :error)))
                 result
                 {:entries result}))
             (catch Throwable t
               {:entries [] :error (session-fs-error t)})))

         :readdir-with-types
         (fn [{:keys [path]}]
           (try
             (let [result (await-session-fs-result ((:readdir-with-types provider) path))]
               (if (and (map? result)
                        (or (contains? result :entries)
                            (contains? result :error)))
                 result
                 {:entries result}))
             (catch Throwable t
               {:entries [] :error (session-fs-error t)})))

         :rm
         (fn [{:keys [path recursive force] :as params}]
           (session-fs-void-result (:rm provider) [path (boolean recursive) (boolean force)] params))

         :rename
         (fn [{:keys [src dest] :as params}]
           (session-fs-void-result (:rename provider) [src dest] params))}]
    ;; Upstream PR #1299: optional SQLite sub-provider. Adapter exposes flat
    ;; :sqlite-query and :sqlite-exists handler keys that the RPC dispatch
    ;; layer wires to the per-session handler map.
    ;;
    ;; Unlike the FS methods, SQLite handlers let provider exceptions propagate
    ;; (matching upstream Node behavior). The dispatch layer in
    ;; handle-session-fs-request! converts these into JSON-RPC errors.
    (if-let [sql (:sqlite provider)]
      (assoc base-handler
             :sqlite-query
             (fn [{:keys [query-type query params]}]
               (let [result (await-session-fs-result ((:query sql) query-type query params))]
                 (or result {:rows [] :columns [] :rows-affected 0})))

             :sqlite-transaction
             (fn [{:keys [statements]}]
               (if-let [transaction (:transaction sql)]
                 (try
                   {:results (mapv identity
                                   (await-session-fs-result (transaction statements)))}
                   (catch Throwable t
                     {:results [] :error (sqlite-transaction-error t)}))
                 {:results []
                  :error {:error-class "fatal"
                          :message "SQLite transactions are not supported by this provider"}}))

             :sqlite-exists
             (fn [_params]
               {:exists (boolean (await-session-fs-result ((:exists sql))))}))
      base-handler)))

(defn adapt-session-fs-handler
  "Return an RPC-shaped sessionFs handler for either supported factory contract.

   Upstream SDKs expect :create-session-fs-handler to return a provider-style
   implementation, which this function wraps with create-session-fs-adapter.
   Existing Clojure callers may already return the low-level one-arg handler
   map; those maps are preserved."
  [handler-or-provider]
  (if (or (accepts-arity? (:write-file handler-or-provider) 3)
          (accepts-arity? (:append-file handler-or-provider) 3)
          (accepts-arity? (:mkdir handler-or-provider) 3)
          (accepts-arity? (:rm handler-or-provider) 3)
          (accepts-arity? (:rename handler-or-provider) 2)
          ;; PR #1299: presence of nested :sqlite provider also indicates
          ;; provider-style (low-level handlers expose flat :sqlite-query /
          ;; :sqlite-exists keys instead).
          (and (map? (:sqlite handler-or-provider))
               (or (contains? (:sqlite handler-or-provider) :query)
                   (contains? (:sqlite handler-or-provider) :exists))))
    (create-session-fs-adapter handler-or-provider)
    handler-or-provider))

(defn handle-system-message-transform
  "Handle a systemMessage.transform RPC request from the CLI runtime.
   Dispatches each section to its registered transform callback.
   On callback error, returns the original content (graceful fallback).
   
   Uses string keys in the response to preserve the original wire-format
   section IDs (e.g. \"tool_efficiency\", not \"tool-efficiency\")."
  [client session-id sections]
  (let [callbacks (get-in @(:state client) [:sessions session-id :transform-callbacks])]
    {:sections
     (reduce-kv
      (fn [acc section-id {:keys [content]}]
        (let [;; Convert incoming kebab-case keyword back to wire string ID
              ;; e.g. :tool-efficiency -> "tool_efficiency"
              wire-id (util/section-kw->wire-id section-id)
              callback (get callbacks wire-id)]
          ;; Use wire string as response key to preserve original format
          (assoc acc wire-id
                 {:content
                  (if callback
                    (try
                      (callback content)
                      (catch Throwable t
                        (log/warn t "systemMessage.transform callback failed"
                                  {:session-id session-id :section wire-id})
                        content))
                    content)})))
      {}
      sections)}))

(declare cancel-all-factory-executions!)

(defn remove-session!
  "Remove a session from client state. Called on RPC failure during pre-registration."
  [client session-id]
  (let [event-chan (get-in @(:state client) [:session-io session-id :event-chan])]
    (cancel-all-factory-executions! client session-id)
    (swap! (:state client) (fn [s]
                             (-> s
                                 (update :sessions dissoc session-id)
                                 (update :session-io dissoc session-id))))
    (when event-chan
      (close! event-chan))))

(defn dispatch-event!
  "Dispatch an event to all subscribers via the mult. Called by client notification router.
   Events are dropped (with warning) if the session event buffer is full."
  [client session-id event]
  (let [normalized-event (update event :type util/event-type->keyword)]
    (log/debug "Dispatching event to session " session-id ": type=" (:type normalized-event))
    (when-not (:destroyed? (session-state client session-id))
      (when-let [{:keys [event-chan]} (session-io client session-id)]
        (when-not (async/offer! event-chan normalized-event)
          (log/warn "Dropping event for session " session-id
                    " type=" (:type normalized-event) " (event buffer full)"))))))

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

(def ^:private session-fs-method->handler-key
  "Map RPC method names to handler map keys."
  {"sessionFs.readFile"        :read-file
   "sessionFs.writeFile"       :write-file
   "sessionFs.appendFile"      :append-file
   "sessionFs.exists"          :exists
   "sessionFs.stat"            :stat
   "sessionFs.mkdir"           :mkdir
   "sessionFs.readdir"         :readdir
   "sessionFs.readdirWithTypes" :readdir-with-types
   "sessionFs.rm"              :rm
   "sessionFs.rename"          :rename
   ;; Upstream PR #1299: SQLite operations. Handlers are optional — provider
   ;; opts in by exposing a nested :sqlite {:query :exists} sub-provider.
   "sessionFs.sqliteQuery"     :sqlite-query
   "sessionFs.sqliteTransaction" :sqlite-transaction
   "sessionFs.sqliteExists"    :sqlite-exists})

(defn- coerce-sqlite-params
  "For sessionFs.sqliteQuery: coerce the wire-format params into the shape
   expected by adapted handlers. The wire `queryType` is a literal string —
   convert to keyword so handlers receive `:exec`, `:query`, or `:run`."
  [method params]
  (case method
    "sessionFs.sqliteQuery"
    (cond-> params
      (string? (:query-type params))
      (update :query-type keyword))

    "sessionFs.sqliteTransaction"
    (update params :statements
            (fn [statements]
              (mapv (fn [statement]
                      (cond-> statement
                        (string? (:query-type statement))
                        (update :query-type keyword)))
                    statements)))

    params))

(def ^:private max-factory-fanout 4096)

(defn- factory-aborted-error [run-id]
  (ex-info "Factory run was aborted"
           {:type :factory-aborted
            :run-id run-id}))

(defn- throw-if-factory-aborted! [{:keys [cancelled? run-id]}]
  (when @cancelled?
    (throw (factory-aborted-error run-id))))

(defn- factory-rpc!
  [client execution method params]
  (throw-if-factory-aborted! execution)
  (let [response-chan (proto/send-request (connection-io client) method params)
        [response port] (alts!! [(:cancel-chan execution) response-chan] :priority true)]
    (if (= port (:cancel-chan execution))
      (throw (factory-aborted-error (:run-id execution)))
      (cond
        (nil? response)
        (throw (ex-info "Factory RPC response channel closed" {:method method}))

        (:error response)
        (throw (ex-info (get-in response [:error :message] "Factory RPC error")
                        {:method method :error (:error response)}))

        :else
        (:result response)))))

(defn- factory-fatal-error? [error]
  (let [{:keys [type method]} (ex-data error)]
    (or (= :factory-aborted type)
        (and (string? method) (str/starts-with? method "session.factory.")))))

(defn- await-factory-value [value]
  (cond
    (channel? value) (<!! value)
    (or (instance? java.util.concurrent.Future value)
        (instance? clojure.lang.IPending value)) @value
    :else value))

(defn- json-value? [value]
  (cond
    (nil? value) true
    (or (string? value) (boolean? value)) true
    (number? value) (if (or (float? value) (double? value))
                      (Double/isFinite (double value))
                      true)
    (vector? value) (every? json-value? value)
    (map? value) (and (every? #(or (string? %) (keyword? %)) (keys value))
                      (every? json-value? (vals value)))
    :else false))

(defn- assert-factory-json! [value label]
  (when-not (json-value? value)
    (throw (ex-info (str label " must be a JSON value")
                    {:value-type (some-> value class .getName)})))
  value)

(defn- serializable-ex-data [error]
  (into {}
        (filter (fn [[key value]]
                  (and (keyword? key) (json-value? value))))
        (ex-data error)))

(defn- factory-parallel [thunks]
  (when-not (and (vector? thunks) (every? fn? thunks))
    (throw (ex-info "parallel expects a vector of functions" {:value thunks})))
  (when (> (count thunks) max-factory-fanout)
    (throw (ex-info (str "parallel accepts at most " max-factory-fanout " items")
                    {:count (count thunks)})))
  (let [results (mapv (fn [thunk]
                        (future
                          (try
                            {:value (await-factory-value (thunk))}
                            (catch Throwable error
                              {:error error}))))
                      thunks)]
    (mapv (fn [result]
            (let [{:keys [value error]} @result]
              (if error
                (if (factory-fatal-error? error)
                  (throw error)
                  nil)
                value)))
          results)))

(defn- factory-pipeline [items & stages]
  (when-not (vector? items)
    (throw (ex-info "pipeline items must be a vector" {:value items})))
  (when-not (every? fn? stages)
    (throw (ex-info "pipeline stages must be functions" {:value stages})))
  (when (> (count items) max-factory-fanout)
    (throw (ex-info (str "pipeline accepts at most " max-factory-fanout " items")
                    {:count (count items)})))
  (let [futures
        (mapv
         (fn [index item]
           (future
             (loop [previous item
                    remaining stages]
               (if-let [stage (first remaining)]
                 (let [outcome (try
                                 {:value (await-factory-value
                                          (stage previous item index))}
                                 (catch Throwable error
                                   {:error error}))]
                   (if-let [error (:error outcome)]
                     (if (factory-fatal-error? error)
                       {:error error}
                       {:value nil})
                     (recur (:value outcome) (next remaining))))
                 {:value previous}))))
         (range)
         items)]
    (mapv (fn [future-result]
            (let [{:keys [value error]} @future-result]
              (if error
                (throw error)
                value)))
          futures)))

(defn- register-factory-execution! [client session-id run-id execution-token]
  (let [execution {:run-id run-id
                   :execution-token execution-token
                   :cancelled? (atom false)
                   :cancel-chan (chan)}]
    (when (identical?
           execution
           (get-in
            (swap! (:state client)
                   (fn [state]
                     (let [session (get-in state [:sessions session-id])]
                       (if (and session (not (:destroyed? session)))
                         (assoc-in state
                                   [:sessions session-id :factory-executions run-id execution-token]
                                   execution)
                         state))))
            [:sessions session-id :factory-executions run-id execution-token]))
      execution)))

(defn- remove-factory-execution!
  [client session-id run-id execution-token execution]
  (swap! (:state client)
         (fn [state]
           (let [path [:sessions session-id :factory-executions run-id]
                 current (get-in state (conj path execution-token))]
             (if-not (identical? current execution)
               state
               (let [remaining (dissoc (get-in state path) execution-token)]
                 (if (seq remaining)
                   (assoc-in state path remaining)
                   (update-in state
                              [:sessions session-id :factory-executions]
                              dissoc
                              run-id))))))))

(defn- cancel-executions! [executions]
  (doseq [{:keys [cancelled? cancel-chan]}
          executions]
    (reset! cancelled? true)
    (close! cancel-chan)))

(defn- cancel-factory-executions! [client session-id run-id]
  (cancel-executions!
   (vals (get-in @(:state client)
                 [:sessions session-id :factory-executions run-id]))))

(defn- cancel-all-factory-executions! [client session-id]
  (cancel-executions!
   (mapcat vals
           (vals (get-in @(:state client)
                         [:sessions session-id :factory-executions])))))

(defn ^:no-doc purge-github-token-provider-registrations
  "Remove registrations owned by session-id.

   scope is :all for teardown, or :committed-only when rotating a provider
   after a successful create/resume operation."
  [registrations session-id scope]
  (into {}
        (remove
         (fn [[_ registration]]
           (and (= session-id (:session-id registration))
                (case scope
                  :all true
                  :committed-only (:committed? registration)
                  (throw (ex-info "Invalid GitHub token provider purge scope"
                                  {:scope scope}))))))
        (or registrations {})))

(defn- purge-github-token-provider-invocations
  [invocations registration-ids]
  (into {}
        (remove (fn [[_ invocation]]
                  (contains? registration-ids
                             (:registration-id invocation))))
        (or invocations {})))

(defn ^:no-doc purge-github-token-provider-resources
  "Remove a session's provider registrations and their active invocations."
  [state session-id scope]
  (let [registrations (:github-token-providers state)
        retained (purge-github-token-provider-registrations
                  registrations session-id scope)
        removed-ids (into #{}
                          (remove #(contains? retained %))
                          (keys registrations))]
    (-> state
        (assoc :github-token-providers retained)
        (update :github-token-provider-invocations
                purge-github-token-provider-invocations
                removed-ids))))

(defn ^:no-doc purge-github-token-provider-registration
  "Remove one provider registration and its active invocations."
  [state registration-id]
  (-> state
      (update :github-token-providers dissoc registration-id)
      (update :github-token-provider-invocations
              purge-github-token-provider-invocations
              #{registration-id})))

(defn ^:no-doc purge-all-github-token-provider-resources
  "Remove every provider registration and active invocation."
  [state]
  (assoc state
         :github-token-providers {}
         :github-token-provider-invocations {}))

(defn ^:no-doc close-removed-github-token-provider-invocations!
  "Cancel invocations removed by one atomic client-state transition."
  [old-state new-state]
  (doseq [[invocation-id {:keys [cancel-chan cancelled? task] :as invocation}]
          (:github-token-provider-invocations old-state)
          :when (not (identical?
                      invocation
                      (get-in new-state
                              [:github-token-provider-invocations
                               invocation-id])))]
    (when cancelled?
      (reset! cancelled? true))
    (close! cancel-chan)
    (when-let [^java.util.concurrent.Future future (some-> task deref)]
      (.cancel future true))))

(defn ^:no-doc teardown-local!
  "Mark a session terminal and release resources without contacting the runtime.

   `provider-scope` defaults to `:all`. Pass nil only while rolling back a
   provisional resume setup that must leave the previously committed provider
   available."
  ([client session-id]
   (teardown-local! client session-id :all))
  ([client session-id provider-scope]
   (let [[old new]
         (swap-vals!
          (:state client)
          (fn [state]
            (let [session (get-in state [:sessions session-id])
                  state (cond-> state
                          provider-scope
                          (purge-github-token-provider-resources
                           session-id provider-scope)

                          true
                          (update :disconnecting-session-ids disj session-id))]
              (if (or (nil? session) (:destroyed? session))
                state
                (assoc-in
                 state
                 [:sessions session-id]
                 (assoc session
                        :destroyed? true
                        :tool-handlers {}
                        :permission-handler nil
                        :user-input-handler nil
                        :factories {}
                        :factory-executions {}
                        :hooks {}
                        :config nil))))))]
     (close-removed-github-token-provider-invocations! old new)
     (cond
       (nil? (get-in old [:sessions session-id]))
       :absent

       (get-in old [:sessions session-id :destroyed?])
       :already-destroyed

       :else
       (do
         (cancel-executions!
          (mapcat vals
                  (vals (get-in old [:sessions session-id :factory-executions]))))
         (let [{:keys [event-chan send-lock]} (get-in old [:session-io session-id])]
           (when event-chan
             (close! event-chan))
           (when send-lock
             (close! send-lock)))
         :claimed)))))

(defn- factory-context
  [client session-id {:keys [run-id execution-token args] :as _params} execution]
  (let [progress (atom {:next-seq 0 :pending []})
        enqueue! (fn [kind text]
                   (throw-if-factory-aborted! execution)
                   (swap! progress
                          (fn [{:keys [next-seq pending]}]
                            {:next-seq (inc next-seq)
                             :pending (conj pending
                                            {:seq next-seq :kind kind :text text})})))
        flush! (fn []
                 (let [[old _] (swap-vals! progress assoc :pending [])
                       lines (:pending old)]
                   (when (seq lines)
                     (factory-rpc! client execution "session.factory.log"
                                   {:session-id session-id
                                    :run-id run-id
                                    :execution-token execution-token
                                    :lines lines}))))
        agent! (fn agent!
                 ([prompt] (agent! prompt {}))
                 ([prompt options]
                  (flush!)
                  (:result
                   (factory-rpc! client execution "session.factory.agent"
                                 {:session-id session-id
                                  :factory-run-id run-id
                                  :execution-token execution-token
                                  :prompt prompt
                                  :opts (select-keys options [:label :schema :model])}))))
        step! (fn step!
                ([key producer] (step! key producer {}))
                ([key producer {:keys [volatile?]}]
                 (flush!)
                 (if volatile?
                   (do
                     (throw-if-factory-aborted! execution)
                     (assert-factory-json!
                      (await-factory-value (producer))
                      (str "step " (pr-str key) " result")))
                   (let [cached (factory-rpc! client execution
                                              "session.factory.journal.get"
                                              {:session-id session-id
                                               :run-id run-id
                                               :execution-token execution-token
                                               :key key})]
                     (if (:hit cached)
                       (if (contains? cached :result-json)
                         (assert-factory-json! (:result-json cached)
                                               (str "step " (pr-str key) " result"))
                         (throw (ex-info "Factory journal hit omitted its result"
                                         {:key key})))
                       (let [result (assert-factory-json!
                                     (await-factory-value (producer))
                                     (str "step " (pr-str key) " result"))]
                         (factory-rpc! client execution
                                       "session.factory.journal.put"
                                       {:session-id session-id
                                        :run-id run-id
                                        :execution-token execution-token
                                        :key key
                                        :result-json result})
                         result))))))]
    {:run-id run-id
     :args args
     :session (->CopilotSession session-id client)
     :cancel-chan (:cancel-chan execution)
     :cancelled? #(deref (:cancelled? execution))
     :agent agent!
     :step step!
     :parallel factory-parallel
     :pipeline factory-pipeline
     :phase #(enqueue! "phase" %)
     :log #(enqueue! "log" %)
     :factory (fn [& _]
                (throw (ex-info "nested factories are not supported" {})))
     ::flush-progress! flush!}))

(defn handle-factory-execute!
  "Execute an extension-authored factory for a runtime reverse RPC."
  [client session-id {:keys [name run-id execution-token] :as params}]
  (async/thread-call
   (fn []
     (if-let [handle (get-in @(:state client) [:sessions session-id :factories name])]
       (if-let [execution (register-factory-execution!
                           client session-id run-id execution-token)]
         (let [context (factory-context client session-id params execution)
               flush! (::flush-progress! context)]
           (try
             (let [result (await-factory-value
                           ((factory/factory-run-function handle)
                            (dissoc context ::flush-progress!)))]
               (flush!)
               (cond
                 (nil? result) {:result {}}
                 (identical? factory/json-null result) {:result {:result nil}}
                 :else (do
                         (assert-factory-json! result "Factory result")
                         {:result {:result result}})))
             (catch Throwable error
               {:error {:code -32603
                        :message (or (ex-message error) (str error))
                        :data (serializable-ex-data error)}})
             (finally
               (try
                 (flush!)
                 (catch Throwable error
                   (log/warn "Failed to flush final factory progress"
                             {:session-id session-id
                              :run-id run-id
                              :error (ex-message error)})))
               (remove-factory-execution!
                client session-id run-id execution-token execution))))
         {:error {:code -32001
                  :message (str "Session has been disconnected: " session-id)}})
       {:error {:code -32602
                :message (str "No factory registered with name " (pr-str name))
                :data {:code "factory_not_found" :name name}}}))
   :io))

(defn handle-factory-abort!
  "Cooperatively cancel active executions for a durable factory run."
  [client session-id run-id]
  (cancel-factory-executions! client session-id run-id)
  (let [result (chan 1)]
    (put! result {:result {}})
    (close! result)
    result))

(defn handle-session-fs-request!
  "Handle an incoming sessionFs.* RPC request. Dispatches to the session's
   FS handler and returns a channel with {:result ...} or {:error ...}.

   For sessionFs.sqliteQuery / sqliteExists (upstream PR #1299), exceptions
   from the provider propagate as JSON-RPC errors rather than being wrapped
   in a SessionFsError result map, matching upstream Node behavior."
  [client session-id method params]
  (async/thread-call
   (fn []
     (let [handler-map (:session-fs-handler (session-state client session-id))
           handler-key (session-fs-method->handler-key method)
           params (coerce-sqlite-params method params)]
       (if-not handler-map
         {:error {:code -32001 :message (str "No sessionFs handler for session: " session-id)}}
         (if-let [handler-fn (get handler-map handler-key)]
           (try
             (let [result (handler-fn params)
                   result (if (channel? result) (<!! result) result)]
               {:result result})
             (catch Throwable t
               (log/warn t "sessionFs handler error" {:method method :session-id session-id})
               {:error {:code -32603 :message (str "sessionFs error: " (ex-message t))}}))
           {:error {:code -32601 :message (str "Unknown sessionFs method: " method)}}))))
   :io))

(defn handle-tool-call!
  "Handle an incoming tool call request. Returns a channel with the result wrapper."
  [client session-id tool-call-id tool-name arguments & {:keys [traceparent tracestate]}]
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
           (let [available-tools (when (= tool-search-tool-name tool-name)
                                   (current-tool-metadata client session-id))
                 invocation (cond-> {:session-id session-id
                                     :tool-call-id tool-call-id
                                     :tool-name tool-name
                                     :arguments arguments}
                              (some? available-tools)
                              (assoc :available-tools available-tools)
                              traceparent (assoc :traceparent traceparent)
                              tracestate (assoc :tracestate tracestate))
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

(defn- normalize-permission-result
  "Normalize legacy Clojure permission results to the upstream v0.3.0
   PermissionDecision shape before sending them to the CLI."
  [result]
  (let [feedback (fn [fallback-feedback]
                   (or (:feedback result) (:message result) fallback-feedback))
        reject-decision (fn [fallback-feedback]
                          (cond-> {:kind :reject}
                            (feedback fallback-feedback)
                            (assoc :feedback (feedback fallback-feedback))))
        session-decision (fn []
                           (cond-> {:kind :approve-for-session}
                             (:approval result)
                             (assoc :approval (:approval result))))
        location-decision (fn []
                            (cond-> {:kind :approve-for-location}
                              (:approval result)
                              (assoc :approval (:approval result))
                              (:location-key result)
                              (assoc :location-key (:location-key result))))]
    (case (:kind result)
      :approve-once
      {:kind :approve-once}

      :approved
      {:kind :approve-once}

      :approve-for-session
      (session-decision)

      :approve-for-location
      (location-decision)

      :denied-no-approval-rule-and-could-not-request-from-user
      {:kind :user-not-available}

      :user-not-available
      {:kind :user-not-available}

      :denied-by-rules
      (reject-decision "Denied by rules")

      :denied-interactively-by-user
      (reject-decision "Denied by user")

      :denied-by-content-exclusion-policy
      (reject-decision "Denied by content exclusion policy")

      :denied-by-permission-request-hook
      (reject-decision "Denied by permission request hook")

      :reject
      (reject-decision nil)

      result)))

(def ^:private permission-context-wire-values
  {:auto-approved "auto_approved"
   :autopilot-denied "autopilot_denied"
   :prompted-user "prompted_user"
   :assisted-approval "assisted_approval"
   :human-response "human_response"
   :host-policy "host_policy"
   :unattended-fallback "unattended_fallback"
   :tui "tui"
   :prompt-mode "prompt_mode"
   :copilot-app "copilot_app"
   :sdk "sdk"
   :acp "acp"
   :interactive "interactive"
   :headless "headless"
   :none "none"})

(defn- permission-context->wire
  [{:keys [outcome source surface response-capability]}]
  (cond-> {:outcome (permission-context-wire-values outcome)
           :source (permission-context-wire-values source)
           :surface (permission-context-wire-values surface)}
    response-capability
    (assoc :response-capability
           (permission-context-wire-values response-capability))))

(defn- normalize-permission-handler-result
  [result]
  (cond
    (and (map? result) (= :attributed (:kind result)))
    (when (s/valid? ::specs/attributed-permission-result result)
      (let [decision (:result result)]
        (if (= :no-result (:kind decision))
          {:result :no-result}
          {:result (normalize-permission-result decision)
           :decision-context
           (permission-context->wire (:decision-context result))})))

    (and (map? result) (= :no-result (:kind result)))
    {:result :no-result}

    (and (map? result) (contains? result :kind))
    {:result (normalize-permission-result result)}

    ;; Historical wrapped form: {:result {:kind ...}}
    (and (map? result) (map? (:result result))
         (= :no-result (get-in result [:result :kind])))
    {:result :no-result}

    (and (map? result) (map? (:result result))
         (contains? (:result result) :kind))
    {:result (normalize-permission-result (:result result))}

    :else
    nil))

(defn handle-permission-request!
  "Handle an incoming permission request. Returns a channel with the result.
   When the handler returns `{:kind :no-result}`, the result is
   `{:result :no-result}` — callers must check for this sentinel:
   - **v3 (broadcast path):** skip the `handlePendingPermissionRequest` RPC
     entirely so the extension does not answer this permission request.
   - **v2 (request-handler path):** propagate as a JSON-RPC internal error
     (code -32603) so the CLI knows the request was not handled."
  [client session-id request]
  (async/thread-call
   (fn []
     (let [handler (:permission-handler (session-state client session-id))]
       (if-not handler
         {:result {:kind :user-not-available}}
         (try
           (let [result (handler request
                                 {:session-id session-id
                                  :managed-settings-enabled?
                                  (:managed-settings-enabled?
                                   (session-state client session-id))})
                 ;; If handler returns a channel, await it
                 result (if (channel? result)
                          (<!! result)
                          result)
                 response (normalize-permission-handler-result result)]
             (if response
               response
               (do
                 (log/warn "Invalid permission response for session " session-id ": " result)
                 {:result {:kind :user-not-available}})))
           (catch Exception e
             (log/error "Permission handler error for session " session-id ": " (ex-message e))
             {:result {:kind :user-not-available}})))))
   :io))

(defn- mcp-auth-result->wire
  "Map an McpAuthResult returned by an :on-mcp-auth-request handler to the wire
   `result` shape for session.mcp.oauth.handlePendingRequest (upstream PR #1669).
  A map carrying a non-nil :access-token yields {:kind \"token\" ...}; anything
  else (nil, {:access-token nil}, {:kind :cancelled}, or a malformed value)
  yields {:kind \"cancelled\"}. Token fields stay kebab-cased — util/clj->wire
  camelCases the keys when the enclosing RPC params are converted, and the
  string :kind value is preserved."
  [result]
  (if (and (map? result) (some? (:access-token result)))
    (cond-> {:kind "token" :access-token (:access-token result)}
      (some? (:token-type result)) (assoc :token-type (:token-type result))
      (some? (:expires-in result)) (assoc :expires-in (:expires-in result)))
    {:kind "cancelled"}))

(defn handle-mcp-auth-request!
  "Handle an `mcp.oauth_required` MCP OAuth request (upstream PR #1669).
   Returns a channel yielding the wire `result` map for
   session.mcp.oauth.handlePendingRequest.

   The configured :on-mcp-auth-request handler is invoked with the idiomatic
   McpAuthRequest map (the event data — {:request-id :server-name :server-url
   :reason ...}) and a context map {:session-id ...}; it may return a channel.
   A result carrying :access-token answers with a token; nil, {:kind
   :cancelled}, or a thrown exception cancels the request (matching upstream's
   error-swallowing behavior, so a transient handler failure never wedges the
   pending request)."
  [client session-id request]
  (async/thread-call
   (fn []
     (let [handler (:mcp-auth-handler (session-state client session-id))]
       (if-not handler
         {:kind "cancelled"}
         (try
           (let [result (handler request {:session-id session-id})
                 result (if (channel? result) (<!! result) result)]
             (mcp-auth-result->wire result))
           (catch Exception e
             (log/error "MCP auth handler error for session " session-id ": " (ex-message e))
             {:kind "cancelled"})))))
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

(defn- extract-bearer-token-providers
  "Build a `{provider-name -> callback}` map from session config (upstream PR #1748).
   The singular whole-session `:provider` callback is keyed under the implicit
   provider name `\"default\"` (upstream `DEFAULT_PROVIDER_NAME`); each named
   provider in `:providers` is keyed by its `:name`."
  [config]
  (let [singular (when-let [f (get-in config [:provider :bearer-token-provider])]
                   {"default" f})
        named (into {} (keep (fn [p]
                               (when-let [f (:bearer-token-provider p)]
                                 [(:name p) f]))
                             (:providers config)))]
    (merge singular named)))

(defn handle-provider-token-request!
  "Handle an incoming providerToken.getToken request (upstream PR #1748).
   Returns a channel with the result.

   The runtime issues this session-scoped request when a BYOK provider configured
   with a `:bearer-token-provider` callback needs a fresh token. The matching
   callback (looked up by `provider-name`) is invoked with the idiomatic
   `ProviderTokenArgs` map `{:provider-name ... :session-id ...}` and must
   return the raw token string (without the `Bearer ` prefix); a channel yielding
   the string is also accepted. The runtime performs no caching, so the callback
   owns refresh.

   Upstream PR #1796 settled the public name on `bearerTokenProvider` (which this
   SDK already used) and added `sessionId` to the callback args, so a single
   callback can mint provider tokens scoped to the originating session."
  [client session-id provider-name]
  (async/thread-call
   (fn []
     (let [config (:config (session-state client session-id))
           callback (get (extract-bearer-token-providers config) provider-name)]
       (if-not callback
         {:error {:code -32001
                  :message (str "No bearer-token provider registered for provider \""
                                provider-name "\"")}}
         (try
           (let [result (callback {:provider-name provider-name :session-id session-id})
                 result (if (channel? result) (<!! result) result)]
             (if (string? result)
               {:result {:token result}}
               (do
                 (log/warn "Bearer-token provider returned a non-string for session "
                           session-id " (result type: "
                           (some-> result class .getName) ")")
                 {:error {:code -32001
                          :message "Bearer-token provider returned a non-string token"}})))
           (catch Exception e
             (log/error "Bearer-token provider error for session " session-id
                        " (exception type: " (some-> e class .getName) ")")
             {:error {:code -32001
                      :message "Bearer-token provider error"}})))))
   :io))

(defn handle-hooks-invoke!
  "Handle an incoming hooks invocation. Returns a channel with the result.
   PR #269 feature."
  [client session-id hook-type input]
  (async/thread-call
   (fn []
     (let [hooks (:hooks (session-state client session-id))]
       (if-not hooks
         {:result {}}
         (let [;; Map hook type strings to handler keywords
               handler-key (case hook-type
                             "preToolUse" :on-pre-tool-use
                             "preMcpToolCall" :on-pre-mcp-tool-call
                             "postToolUse" :on-post-tool-use
                             "postToolUseFailure" :on-post-tool-use-failure
                             "userPromptSubmitted" :on-user-prompt-submitted
                             "userPromptTransformed" :on-user-prompt-transformed
                             "sessionStart" :on-session-start
                             "sessionEnd" :on-session-end
                             "errorOccurred" :on-error-occurred
                             "agentStop" :on-agent-stop
                             nil)
               handler (when handler-key (get hooks handler-key))]
           (if-not handler
             {:result {}}
             (try
               (let [;; Upstream PR #1290: BaseHookInput.sessionId. Preserve the
                 ;; wire-provided :session-id when present (it may identify a
                 ;; sub-agent session distinct from the outer session-id);
                 ;; otherwise fall back to the outer session-id.
                     input (cond-> input
                             (not (contains? input :session-id))
                             (assoc :session-id session-id))
                     result (handler input {:session-id session-id})
                     ;; If handler returns a channel, await it
                     result (if (channel? result)
                              (<!! result)
                              result)]
                 {:result (cond-> {}
                            (some? result) (assoc :output result))})
               (catch Exception e
                 (log/error "Hook handler error for session " session-id ", hook " hook-type ": " (ex-message e))
                 {:result {}})))))))
   :io))

(defn handle-command-execute!
  "Handle an incoming command.execute event. Returns a channel with the result.
   Looks up the command handler by name and calls it with a context map.
   Returns {:result nil} on success or {:error message} on failure."
  [client session-id {:keys [command-name command args]}]
  (async/thread-call
   (fn []
     (let [handler (get-in (session-state client session-id) [:command-handlers command-name])]
       (if-not handler
         {:error (str "Unknown command: " command-name)}
         (try
           (let [timeout-ms (or (:tool-timeout-ms (:options client)) 120000)
                 result (handler {:session-id session-id
                                  :command command
                                  :command-name command-name
                                  :args args})
                 ;; If handler returns a channel, await with timeout
                 _ (when (channel? result)
                     (let [timeout-ch (async/timeout timeout-ms)
                           [_ ch] (alts!! [result timeout-ch])]
                       (when (= ch timeout-ch)
                         (throw (ex-info "Command handler timeout"
                                         {:timeout-ms timeout-ms
                                          :command-name command-name})))))]
             {:result nil})
           (catch Exception e
             {:error (ex-message e)})))))
   :io))

(defn handle-elicitation-request!
  "Handle an incoming elicitation.requested broadcast event.
   Calls the session's elicitation handler with a single ElicitationContext arg
   (includes :session-id alongside request fields). Returns a channel with the result.
   If the handler fails, returns {:action \"cancel\"} to avoid hanging requests."
  [client session-id context]
  (async/thread-call
   (fn []
     (let [handler (:elicitation-handler (session-state client session-id))]
       (when handler
         (try
           (let [result (handler context)
                 result (if (channel? result) (<!! result) result)]
             (or result {:action "cancel"}))
           (catch Throwable t
             (log/warn t "Elicitation handler threw" {:session-id session-id})
             {:action "cancel"})))))
   :io))

(defn- exit-plan-result->idiom
  "Convert an idiomatic exit-plan-mode handler result to the SDK-internal
   idiomatic response map that the protocol layer will wire-convert.
   Idiom keys: :approved? (required) → re-keyed to :approved (no ? suffix),
   optional :selected-action, :feedback (carried through; the protocol layer
   camelizes them to selectedAction / feedback).
   Optional fields whose values are not strings are dropped with a logged
   warning so we never forward malformed payloads to the CLI."
  [result]
  (let [drop-non-string (fn [m k]
                          (let [v (get result k)]
                            (cond
                              (nil? v) m
                              (string? v) (assoc m k v)
                              :else (do (log/warn "Exit-plan-mode handler result has non-string value for optional key; dropping"
                                                  {:key k :value v})
                                        m))))]
    (-> {:approved (boolean (:approved? result))}
        (drop-non-string :selected-action)
        (drop-non-string :feedback))))

(defn handle-exit-plan-mode-request!
  "Handle an incoming exitPlanMode.request RPC (upstream PR #1228).
   The server asks the client whether to exit plan mode. Calls the
   session's :exit-plan-mode-handler with `(request, {:session-id ...})`.
   If the handler returns a channel, awaits its value. Returns a channel
   with a wire-shaped response wrapped in {:result ...} or {:error ...}.

   Default behavior (no handler): {:result {:approved true}}.
   Handler must return an idiomatic map containing :approved? (required
   boolean), optional :selected-action (string), :feedback (string).
   If the handler result is malformed (non-map, missing :approved?, or
   :approved? is not a boolean), a warning is logged and the response
   falls back to {:result {:approved true}} (matching the no-handler
   default). Exceptions thrown by the handler are converted to
   {:error {:code -32603 :message ...}}."
  [client session-id request]
  (async/thread-call
   (fn []
     (let [handler (:exit-plan-mode-handler (session-state client session-id))]
       (if-not handler
         {:result {:approved true}}
         (try
           (let [result (handler request {:session-id session-id})
                 result (if (channel? result) (<!! result) result)]
             (cond
               (not (map? result))
               (do (log/warn "Exit-plan-mode handler returned non-map; defaulting to {:approved true}"
                             {:session-id session-id :result result})
                   {:result {:approved true}})

               (not (contains? result :approved?))
               (do (log/warn "Exit-plan-mode handler result is missing :approved?; defaulting to {:approved true}"
                             {:session-id session-id :result result})
                   {:result {:approved true}})

               (not (boolean? (:approved? result)))
               (do (log/warn "Exit-plan-mode handler result :approved? is not a boolean; defaulting to {:approved true}"
                             {:session-id session-id :result result})
                   {:result {:approved true}})

               :else
               {:result (exit-plan-result->idiom result)}))
           (catch Throwable t
             (log/warn t "Exit-plan-mode handler threw" {:session-id session-id})
             {:error {:code -32603 :message (str "Exit plan mode handler error: " (ex-message t))}})))))
   :io))

(defn- auto-mode-response->wire
  "Coerce an auto-mode response value to wire form.
   Accepts keyword :yes / :yes-always / :no or matching string. Returns wire string."
  [resp]
  (cond
    (= resp :yes) "yes"
    (= resp :yes-always) "yes_always"
    (= resp :no) "no"
    (and (string? resp) (#{"yes" "yes_always" "no"} resp)) resp
    :else "no"))

(defn handle-auto-mode-switch-request!
  "Handle an incoming autoModeSwitch.request RPC (upstream PR #1228).
   The server asks the client whether to switch the agent to auto mode after
   a rate-limit event. Calls the session's :auto-mode-switch-handler with
   `(request, {:session-id ...})`. If the handler returns a channel, awaits it.

   Default behavior (no handler): {:result {:response \"no\"}}.
   Handler may return :yes / :yes-always / :no (or matching string), or a map
   {:response ...} with the same value."
  [client session-id request]
  (async/thread-call
   (fn []
     (let [handler (:auto-mode-switch-handler (session-state client session-id))]
       (if-not handler
         {:result {:response "no"}}
         (try
           (let [result (handler request {:session-id session-id})
                 result (if (channel? result) (<!! result) result)
                 resp (if (map? result) (:response result) result)]
             {:result {:response (auto-mode-response->wire resp)}})
           (catch Throwable t
             (log/warn t "Auto-mode-switch handler threw" {:session-id session-id})
             {:error {:code -32603 :message (str "Auto mode switch handler error: " (ex-message t))}})))))
   :io))

(defn- deep-merge
  "Recursively merge maps, preserving nested keys."
  [a b]
  (merge-with (fn [x y] (if (and (map? x) (map? y)) (deep-merge x y) y)) a b))

(defn update-capabilities!
  "Deep-merge capability changes into the session's capabilities map.
   Called when a capabilities.changed broadcast event is received."
  [client session-id capability-changes]
  (update-session! client session-id update :capabilities
                   (fn [caps] (deep-merge (or caps {}) capability-changes))))

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
   - :prompt          - The message text (required)
   - :attachments     - Vector of attachments (file/directory/selection)
   - :mode            - :enqueue (default) or :immediate
   - :agent-mode      - **Optional**. One of :interactive (default), :plan,
                        :autopilot, or :shell. Selects the agent mode for
                        this turn (upstream PR #1438).
   - :display-prompt  - **Optional**. String shown in the session timeline
                        instead of the model `:prompt` (e.g., when the model
                        prompt is augmented with internal context that should
                        not be shown to end users). (upstream PR #1470)
   - :request-headers - Optional map of HTTP headers forwarded to the
                        upstream LLM on this send (upstream PR #1094).
                        Keys and values must both be strings (do not use
                        Clojure keywords — they would be camelized by the
                        wire-conversion layer)."
  [session opts]
  (when-not (s/valid? ::specs/send-options opts)
    (throw (ex-info "Invalid send options"
                    {:opts opts
                     :explain (s/explain-data ::specs/send-options opts)})))
  (let [{:keys [session-id client]} session]
    (log/debug "send! called for session " session-id " with prompt: " (subs (str (:prompt opts)) 0 (min 50 (count (str (:prompt opts))))) "...")
    (when (session-disconnected? client session-id)
      (throw (ex-info "Session has been disconnected" {:session-id session-id})))
    (let [conn (connection-io client)
          wire-attachments (when (:attachments opts)
                             (util/attachments->wire (:attachments opts)))
          trace-ctx (when-let [provider (:on-get-trace-context client)]
                      (try (let [ctx (provider)]
                             (when (map? ctx)
                               (select-keys ctx [:traceparent :tracestate])))
                           (catch Throwable _ nil)))
          params (cond-> {:session-id session-id
                          :prompt (:prompt opts)}
                   trace-ctx (merge trace-ctx)
                   wire-attachments (assoc :attachments wire-attachments)
                   (:mode opts) (assoc :mode (name (:mode opts)))
                   (:agent-mode opts) (assoc :agent-mode (name (:agent-mode opts)))
                   (some? (:display-prompt opts)) (assoc :display-prompt (:display-prompt opts))
                   (:request-headers opts) (assoc :request-headers (:request-headers opts)))
          result (proto/send-request! conn "session.send" params)
          msg-id (:message-id result)]
      (log/debug "send! completed for session " session-id " message-id=" msg-id)
      msg-id)))

(def ^:private ^:const default-send-and-wait-timeout-ms
  "Default idle-wait timeout (ms) for the `send-and-wait!` / `send-async`
   family when no `:timeout-ms` is supplied. Matches upstream
   `nodejs/src/session.ts` (`effectiveTimeout = timeout ?? 60_000`). Controls
   how long to wait for `session.idle`; it does not abort in-flight agent work."
  60000)

(defn- terminal-idle-event?
  [event]
  (and (= :copilot/session.idle (:type event))
       (not= "autopilot" (get-in event [:data :mode]))))

(defn send-and-wait!
  "Send a message and wait until the session becomes idle.
   Returns the final assistant message event, or nil if none received.
   Serialized per session to avoid mixing concurrent sends.
   An idle event whose wire `:mode` is the string `\"autopilot\"` is a
   nonterminal turn boundary, so the wait continues.

   Options: same as send!

   Additional options:
   - :timeout-ms   - Timeout in milliseconds (default: 60000). The 2-arity form
                     reads this from `opts`; an explicit numeric value overrides
                     the default. A `nil` value (in `opts` or as the positional
                     3-arity argument) disables the deadline and waits
                     indefinitely for `session.idle`/`session.error`. In every
                     case `:timeout-ms` is stripped from `opts` before the
                     underlying `session.send`, so it is never forwarded on the
                     wire."
  ([session opts]
   (let [timeout-ms (if (contains? opts :timeout-ms)
                      (:timeout-ms opts)
                      default-send-and-wait-timeout-ms)]
     (send-and-wait! session (dissoc opts :timeout-ms) timeout-ms)))
  ([session opts timeout-ms]
   (let [{:keys [session-id client]} session]
     (log/debug "send-and-wait! called for session " session-id)
     (when (session-disconnected? client session-id)
       (throw (ex-info "Session has been disconnected" {:session-id session-id})))

     (let [send-opts (dissoc opts :timeout-ms)
           event-ch (chan 1024)
           last-assistant-msg (atom nil)
           {:keys [event-mult send-lock]} (session-io client session-id)]
        ;; Acquire channel-based lock (blocks calling thread)
       (<!! send-lock)

       (try
         ;; Tap the mult BEFORE sending - ensures we don't miss events
         (log/debug "send-and-wait! tapping event mult for session " session-id)
         (tap event-mult event-ch)

         ;; Send the message (never forward :timeout-ms on the wire)
         (log/debug "send-and-wait! sending message")
         (send! session send-opts)

         ;; Wait for events with a single optional deadline. A nil timeout-ms
         ;; disables the deadline: the wait set is event-ch alone rather than
         ;; calling (async/timeout nil).
         (log/debug "send-and-wait! waiting for result with timeout " timeout-ms "ms")
         (let [deadline-ch (when timeout-ms (async/timeout timeout-ms))]
           (loop []
             (let [[event ch] (if deadline-ch
                                (alts!! [event-ch deadline-ch])
                                [(<!! event-ch) event-ch])]
               (cond
                 (and deadline-ch (= ch deadline-ch))
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

                 (terminal-idle-event? event)
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
           (put! send-lock :token)))))))

(defn- send-async*
  "Send a message and return {:message-id :events-ch}."
  ([session opts]
   (send-async* session opts nil))
  ([session opts timeout-ms]
   (let [{:keys [session-id client]} session]
     (when (session-disconnected? client session-id)
       (throw (ex-info "Session has been disconnected" {:session-id session-id})))

     (let [out-ch (chan 1024)
           event-ch (chan 1024)
           {:keys [event-mult send-lock]} (session-io client session-id)
           released? (atom false)
           release-lock! (fn []
                           (when (compare-and-set! released? false true)
                             (put! send-lock :token)))
           deadline-ch (when timeout-ms (async/timeout timeout-ms))
           timeout-event {:type :copilot/session.error
                          :data {:message (str "Timeout after " timeout-ms "ms waiting for session.idle")
                                 :timeout-ms timeout-ms}}
           emit! (fn [event]
                   (when-not (async/offer! out-ch event)
                     (log/debug "Dropping event for session " session-id " due to full async buffer")))]
        ;; Acquire channel-based lock (blocks calling thread)
       (<!! send-lock)

       ;; Tap the mult for events, then send
       (try
         (tap event-mult event-ch)
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

                 (terminal-idle-event? event)
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

(defn- <send-async*
  "Fully non-blocking send pipeline for use in go blocks.
   Acquires lock, sends message, and processes events — all via parking channel ops.
   Returns events-ch immediately; events flow once the go block completes setup."
  [session opts timeout-ms]
  (let [{:keys [session-id client]} session]
    (when (session-disconnected? client session-id)
      (throw (ex-info "Session has been disconnected" {:session-id session-id})))
    (let [out-ch (chan 1024)
          event-ch (chan 1024)
          {:keys [event-mult send-lock]} (session-io client session-id)
          released? (atom false)
          release-lock! (fn []
                          (when (compare-and-set! released? false true)
                            (put! send-lock :token)))
          deadline-ch (when timeout-ms (async/timeout timeout-ms))
          timeout-event {:type :copilot/session.error
                         :data {:message (str "Timeout after " timeout-ms "ms waiting for session.idle")
                                :timeout-ms timeout-ms}}
          emit! (fn [event]
                  (when-not (async/offer! out-ch event)
                    (log/debug "Dropping event for session " session-id " due to full async buffer")))
          cleanup! (fn []
                     (untap event-mult event-ch)
                     (close! event-ch)
                     (close! out-ch)
                     (release-lock!))]
      (go
        (if-not (<! send-lock) ;; park for lock (nil = channel closed)
          (do (close! event-ch) (close! out-ch))
          (try
            (tap event-mult event-ch)
            ;; Send message via channel-based RPC (no blocking)
            (let [conn (connection-io client)
                  wire-attachments (when (:attachments opts)
                                     (util/attachments->wire (:attachments opts)))
                  trace-ctx (when-let [provider (:on-get-trace-context client)]
                              (try (let [ctx (provider)]
                                     (when (map? ctx)
                                       (select-keys ctx [:traceparent :tracestate])))
                                   (catch Throwable _ nil)))
                  params (cond-> {:session-id session-id
                                  :prompt (:prompt opts)}
                           trace-ctx (merge trace-ctx)
                           wire-attachments (assoc :attachments wire-attachments)
                           (:mode opts) (assoc :mode (name (:mode opts)))
                           (:agent-mode opts) (assoc :agent-mode (name (:agent-mode opts)))
                           (some? (:display-prompt opts)) (assoc :display-prompt (:display-prompt opts))
                           (:request-headers opts) (assoc :request-headers (:request-headers opts)))
                  response-ch (proto/send-request conn "session.send" params)
                  [result port] (if deadline-ch
                                  (async/alts! [response-ch deadline-ch])
                                  [(<! response-ch) response-ch])]
              (cond
                ;; Timeout during send
                (and deadline-ch (= port deadline-ch))
                (do (emit! timeout-event) (cleanup!))

                ;; RPC error or channel closed
                (or (nil? result) (:error result))
                (do
                  (when (:error result)
                    (log/error "Async send RPC error: " (get-in result [:error :message])))
                  (cleanup!))

                ;; Success — process events
                :else
                (loop []
                  (let [[event ch] (if deadline-ch
                                     (async/alts! [event-ch deadline-ch])
                                     [(<! event-ch) event-ch])]
                    (cond
                      (and deadline-ch (= ch deadline-ch))
                      (do (emit! timeout-event) (cleanup!))

                      (nil? event)
                      (do (untap event-mult event-ch) (close! out-ch) (release-lock!))

                      (or (terminal-idle-event? event)
                          (= :copilot/session.error (:type event)))
                      (do (emit! event) (cleanup!))

                      :else
                      (do (emit! event) (recur)))))))
            (catch Exception e
              (log/error "<send-async* error for session " session-id ": " (ex-message e))
              (cleanup!)))))
      out-ch)))

(defn send-async
  "Send a message and return a channel that receives events until an ordinary
   session.idle or session.error. An idle event whose wire `:mode` is the
   string `\"autopilot\"` is emitted without closing the channel.
   Serialized per session to avoid mixing concurrent sends.
   Safe for use inside go blocks — no blocking operations.
   
   Options: same as send! (including :request-headers).
   
   Additional options:
   - :timeout-ms   - Timeout in milliseconds (default: 60000, set to nil to disable)"
  [session opts]
  (when-not (s/valid? ::specs/send-options opts)
    (throw (ex-info "Invalid send options"
                    {:opts opts
                     :explain (s/explain-data ::specs/send-options opts)})))
  (let [timeout-ms (if (contains? opts :timeout-ms) (:timeout-ms opts) default-send-and-wait-timeout-ms)
        opts (dissoc opts :timeout-ms)]
    (<send-async* session opts timeout-ms)))

(defn <send!
  "Send a message and return a channel that delivers the final content string.
   This is the async equivalent of send-and-wait! - use inside go blocks.
   
   Options: same as send! (including :request-headers).
   
   Additional options:
   - :timeout-ms   - Timeout in milliseconds (default: 60000, set to nil to disable)
   
   The returned channel delivers a single value (the response content) then closes."
  [session opts]
  (let [timeout-ms (if (contains? opts :timeout-ms) (:timeout-ms opts) default-send-and-wait-timeout-ms)
        events-ch (send-async session (assoc opts :timeout-ms timeout-ms))
        out-ch (chan (async/sliding-buffer 1))]
    (go
      (loop [last-content nil]
        (when-let [event (<! events-ch)]
          (cond
            (= :copilot/assistant.message (:type event))
            (recur (get-in event [:data :content]))

            (or (terminal-idle-event? event)
                (= :copilot/session.error (:type event)))
            (when last-content
              (async/offer! out-ch last-content))

            :else
            (recur last-content))))
      (close! out-ch))
    out-ch))

(defn <send-and-wait!
  "Send a message and return a channel that delivers the final assistant message
   event. This is the channel-based equivalent of `send-and-wait!`; use it inside
   go blocks instead of blocking a dispatch thread.

   The delivered event has the same shape as `send-and-wait!`'s successful return
   value (an `:copilot/assistant.message` event - content lives under
   `[:data :content]`). Error semantics differ: unlike `send-and-wait!`, which
   throws on `:copilot/session.error` or timeout, this variant never surfaces those
   - on error or timeout the channel simply closes (delivering the last assistant
   message if one had already arrived, otherwise nothing). This matches `<send!`.

   Options: same as send!.

   Additional options:
   - :timeout-ms   - Timeout in milliseconds (default: 60000, set to nil to disable)

   The returned channel delivers at most one value then closes."
  [session opts]
  (let [timeout-ms (if (contains? opts :timeout-ms) (:timeout-ms opts) default-send-and-wait-timeout-ms)
        events-ch (send-async session (assoc opts :timeout-ms timeout-ms))
        out-ch (chan (async/sliding-buffer 1))]
    (go
      (loop [last-msg nil]
        (when-let [event (<! events-ch)]
          (cond
            (= :copilot/assistant.message (:type event))
            (recur event)

            (or (terminal-idle-event? event)
                (= :copilot/session.error (:type event)))
            (when last-msg
              (async/offer! out-ch last-msg))

            :else
            (recur last-msg))))
      (close! out-ch))
    out-ch))

(defn send-async-with-id
  "Send a message and return {:message-id :events-ch}."
  [session opts]
  (let [timeout-ms (if (contains? opts :timeout-ms) (:timeout-ms opts) default-send-and-wait-timeout-ms)
        opts (dissoc opts :timeout-ms)]
    (send-async* session opts timeout-ms)))

(defn abort!
  "Abort the currently processing message in this session."
  [session]
  (let [{:keys [session-id client]} session]
    (when (session-disconnected? client session-id)
      (throw (ex-info "Session has been disconnected" {:session-id session-id})))
    (let [conn (connection-io client)]
      (proto/send-request! conn "session.abort" {:session-id session-id})
      nil)))

(defn coerce+normalize-event
  "Apply wire→idiom coercion then normalize :type to a keyword. Fail-open: on
   coercion failure, log a warning (with ex-data) and return the event with
   :type normalized but data uncoerced. Used by both the notification router
   (live events) and `get-messages` (historical events) so the two paths share
   identical shape and error semantics.

   Optional `log-context` (typically a session-id) is appended to the warning
   message when supplied."
  ([event] (coerce+normalize-event event nil))
  ([event log-context]
   (try
     (-> event
         coerce/event-wire->idiom
         (update :type util/event-type->keyword))
     (catch Exception e
       (log/warn "Failed to coerce session event"
                 (if log-context (str " for " log-context) "")
                 ": " (ex-message e)
                 " ex-data=" (pr-str (ex-data e)))
       (update event :type util/event-type->keyword)))))

(defn get-messages
  "Get all events/messages from this session's history."
  [session]
  (let [{:keys [session-id client]} session]
    (when (session-disconnected? client session-id)
      (throw (ex-info "Session has been disconnected" {:session-id session-id})))
    (let [conn (connection-io client)
          result (proto/send-request! conn "session.getMessages" {:session-id session-id})]
      (mapv coerce+normalize-event (:events result)))))

(def ^:private supported-permission-decision-kinds
  "Accepted `:kind` values when manually resolving a pending permission
   request via `handle-pending-permission-request!`. Matches the upstream
   `PermissionDecision` schema (api.schema.json `$defs/PermissionDecision`),
   which is the union of six variants:
   `PermissionDecisionApproveOnce`, `PermissionDecisionApproveForSession`,
   `PermissionDecisionApproveForLocation`, `PermissionDecisionApprovePermanently`,
   `PermissionDecisionReject`, `PermissionDecisionUserNotAvailable`.
   `:no-result` is deliberately excluded — to decline answering, do not call
   the resolver."
  #{:approve-once
    :approve-for-session
    :approve-for-location
    :approve-permanently
    :reject
    :user-not-available})

(defn- check-pending-request-id!
  "Validate the :request-id supplied to a pending-RPC resolver. Must be a
   non-blank string."
  [request-id opts]
  (when-not (and (string? request-id) (not (str/blank? request-id)))
    (throw (ex-info ":request-id must be a non-blank string" {:opts opts}))))

(defn- check-pending-permission-result!
  "Validate the :result map supplied to a pending permission resolver. Must be
   a map with a keyword `:kind` from `supported-permission-decision-kinds`."
  [result opts]
  (when-not (and (map? result) (contains? result :kind))
    (throw (ex-info ":result must be a map with a :kind key" {:opts opts})))
  (let [kind (:kind result)]
    (when-not (keyword? kind)
      (throw (ex-info ":result :kind must be a keyword" {:opts opts :kind kind})))
    (when (= :no-result kind)
      (throw (ex-info ":no-result is not a valid decision for the pending RPC"
                      {:opts opts})))
    (when-not (contains? supported-permission-decision-kinds kind)
      (throw (ex-info ":result :kind is not a supported permission decision"
                      {:opts opts
                       :kind kind
                       :supported supported-permission-decision-kinds})))))

(defn handle-pending-tool-call!
  "Manually resolve a pending external tool call (upstream PR #1308).

   Use this when a tool was declared without a `:handler` and the runtime has
   emitted a `:copilot/external_tool.requested` event. The consumer reads the
   `:request-id` from `(:data event)` and supplies either `:result` (the tool
   result string or map) or `:error` (a string message).

   Options map keys:
   - :request-id - The `:request-id` from the external_tool.requested event (required)
   - :result     - Tool result (string, map, or anything `normalize-tool-result` accepts)
   - :error      - Error message (string). Mutually exclusive with `:result`.

   Returns the RPC result map (sync). Use `<handle-pending-tool-call!` for the
   core.async variant."
  [session {:keys [request-id result error] :as opts}]
  (let [{:keys [session-id client]} session]
    (when (session-disconnected? client session-id)
      (throw (ex-info "Session has been disconnected" {:session-id session-id})))
    (check-pending-request-id! request-id opts)
    (when-not (or (contains? opts :result) (contains? opts :error))
      (throw (ex-info "exactly one of :result or :error is required" {:opts opts})))
    (when (and (contains? opts :result) (contains? opts :error))
      (throw (ex-info ":result and :error are mutually exclusive" {:opts opts})))
    (when (and (contains? opts :error) (not (string? error)))
      (throw (ex-info ":error must be a string when provided" {:opts opts})))
    (let [conn (connection-io client)
          base {:session-id session-id :request-id request-id}
          params (cond
                   (some? error) (assoc base :error error)
                   :else (assoc base :result (normalize-tool-result result)))]
      (proto/send-request! conn "session.tools.handlePendingToolCall" params))))

(defn <handle-pending-tool-call!
  "core.async variant of `handle-pending-tool-call!`. Returns a channel."
  [session opts]
  (let [{:keys [session-id client]} session
        {:keys [request-id result error]} opts]
    (when (session-disconnected? client session-id)
      (throw (ex-info "Session has been disconnected" {:session-id session-id})))
    (check-pending-request-id! request-id opts)
    (when-not (or (contains? opts :result) (contains? opts :error))
      (throw (ex-info "exactly one of :result or :error is required" {:opts opts})))
    (when (and (contains? opts :result) (contains? opts :error))
      (throw (ex-info ":result and :error are mutually exclusive" {:opts opts})))
    (when (and (contains? opts :error) (not (string? error)))
      (throw (ex-info ":error must be a string when provided" {:opts opts})))
    (let [conn (connection-io client)
          base {:session-id session-id :request-id request-id}
          params (cond
                   (some? error) (assoc base :error error)
                   :else (assoc base :result (normalize-tool-result result)))]
      (proto/send-request conn "session.tools.handlePendingToolCall" params))))

(defn handle-pending-permission-request!
  "Manually resolve a pending permission request (upstream PR #1308).

   Use this when no `:on-permission-request` handler was registered and the
   runtime has emitted a `:copilot/permission.requested` event. The consumer
   reads the `:request-id` from `(:data event)` and supplies a permission
   decision in `:result`.

   Options map keys:
   - :request-id - The `:request-id` from the permission.requested event (required)
   - :result     - Permission decision map with `:kind`, matching the upstream
                   `PermissionDecision` union. Allowed kinds:
                   `:approve-once`, `:approve-for-session`,
                   `:approve-for-location`, `:approve-permanently`,
                   `:reject`, `:user-not-available`. `:no-result` is not
                   supported here — to decline answering, simply don't call
                   this function.

   Returns the RPC result map (sync). Use ``copilot/<handle-pending-permission-request!``
   for the core.async variant."
  [session {:keys [request-id result] :as opts}]
  (let [{:keys [session-id client]} session]
    (when (session-disconnected? client session-id)
      (throw (ex-info "Session has been disconnected" {:session-id session-id})))
    (check-pending-request-id! request-id opts)
    (check-pending-permission-result! result opts)
    (let [conn (connection-io client)
          normalized (normalize-permission-result result)]
      (proto/send-request! conn "session.permissions.handlePendingPermissionRequest"
                           {:session-id session-id
                            :request-id request-id
                            :result normalized}))))

(defn <handle-pending-permission-request!
  "core.async variant of `handle-pending-permission-request!`. Returns a channel."
  [session opts]
  (let [{:keys [session-id client]} session
        {:keys [request-id result]} opts]
    (when (session-disconnected? client session-id)
      (throw (ex-info "Session has been disconnected" {:session-id session-id})))
    (check-pending-request-id! request-id opts)
    (check-pending-permission-result! result opts)
    (let [conn (connection-io client)
          normalized (normalize-permission-result result)]
      (proto/send-request conn "session.permissions.handlePendingPermissionRequest"
                          {:session-id session-id
                           :request-id request-id
                           :result normalized}))))

(defn disconnect!
  "Disconnects the session and releases in-memory resources (event handlers,
   tool handlers, permission handler). Session data on disk (conversation
   history, planning state, artifacts) is preserved for later resumption
   via `resume-session`. To permanently remove all session data, use
   `delete-session!` instead.
   Can be called with either a CopilotSession handle or (client, session-id)."
  ([session]
   (disconnect! (:client session) (:session-id session)))
  ([client session-id]
   (log/debug "Disconnecting session: " session-id)
   (let [[old-state _]
         (swap-vals!
          (:state client)
          (fn [state]
            (let [session (get-in state [:sessions session-id])]
              (if (and session
                       (false? (:destroyed? session))
                       (not (contains?
                             (:disconnecting-session-ids state)
                             session-id)))
                (update state
                        :disconnecting-session-ids
                        (fnil conj #{})
                        session-id)
                state))))
         session (get-in old-state [:sessions session-id])
         claimed? (and session
                       (false? (:destroyed? session))
                       (not (contains?
                             (:disconnecting-session-ids old-state)
                             session-id)))
         disconnect? (or (nil? session) claimed?)]
     (when disconnect?
       (try
         (proto/send-request! (connection-io client)
                              "session.destroy"
                              {:session-id session-id}
                              5000)
         (when claimed?
           (teardown-local! client session-id))
         (catch Throwable error
           (when claimed?
             (swap! (:state client)
                    update
                    :disconnecting-session-ids
                    disj
                    session-id))
           (when (instance? InterruptedException error)
             (.interrupt (Thread/currentThread)))
           (throw error)))
       (log/debug "Session disconnected: " session-id)))
   nil))

(defn destroy!
  "Deprecated: Use disconnect! instead. This function will be removed in a future release.
   Disconnects the session and releases in-memory resources.
   Session data on disk is preserved for later resumption."
  ([session]
   (disconnect! session))
  ([client session-id]
   (disconnect! client session-id)))

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
   
   The channel will receive nil (close) when the session is disconnected.
   For explicit cleanup before session disconnection, call unsubscribe-events!.
   
   Drop behavior: the returned channel uses a sliding buffer of 1024 events.
   If this subscriber falls behind and its buffer fills, the oldest buffered
   events are dropped for this subscriber only — delivery to other subscribers
   is never blocked. 1024 is sufficient for most use cases.
   
   This is a convenience wrapper around (tap (events session) ch)."
  [session]
  (let [ch (chan (async/sliding-buffer 1024))
        {:keys [session-id client]} session
        {:keys [event-mult]} (session-io client session-id)]
    (tap event-mult ch)
    ch))

(defn events->chan
  "Subscribe to session events with options.

   Options:
   - :buffer - Channel buffer size (default 1024)
   - :xf     - Transducer applied to events

   Drop behavior: the returned channel uses a sliding buffer of `:buffer`
   events. If this subscriber falls behind and its buffer fills, the oldest
   buffered events are dropped for this subscriber only — delivery to other
   subscribers is never blocked."
  ([session]
   (events->chan session {}))
  ([session {:keys [buffer xf] :or {buffer 1024}}]
   (let [{:keys [session-id client]} session
         {:keys [event-mult]} (session-io client session-id)
         buf (async/sliding-buffer buffer)
         ch (if xf (chan buf xf) (chan buf))]
     (tap event-mult ch)
     ch)))

(defn unsubscribe-events!
  "Unsubscribe a channel from session events.

   Side effects: untaps `ch` from the session's event mult and closes `ch`.
   The caller must not use `ch` after calling this."
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
  (let [{:keys [session-id client]} session]
    (:workspace-path (session-state client session-id))))

(defn ^:experimental get-current-model
  "Get the current model for this session.
   Returns the model ID string, or nil if none set.

   Experimental: not part of the official Copilot SDK API; the wire RPC
   (`session.model.getCurrent`) is exposed for convenience and may change."
  [session]
  (let [{:keys [session-id client]} session
        conn (connection-io client)
        result (proto/send-request! conn "session.model.getCurrent"
                                    {:sessionId session-id})]
    (:model-id result)))

(defn switch-model!
  "Switch the model for this session.
   The new model takes effect for the next message. Conversation history is preserved.

   Optional opts map:
   - :reasoning-effort      - Reasoning effort level for the new model (\"low\", \"medium\", \"high\", \"xhigh\")
   - :reasoning-summary     - Reasoning summary mode for the new model (\"none\", \"concise\", \"detailed\")
   - :context-tier          - Context window tier for models that support it
                              (:default or :long-context, upstream PR #1522)
   - :model-capabilities    - Model capabilities override map (upstream PR #1029)
                              e.g. {:supports {:vision true}
                                    :limits {:max-prompt-tokens 128000}}
                              (:adaptive-thinking / :max-output-tokens are experimental
                              CLI-protocol extras)

   Returns the new model ID string, or nil."
  ([session model-id] (switch-model! session model-id nil))
  ([session model-id opts]
   (let [{:keys [session-id client]} session
         conn (connection-io client)
         context-tier (util/context-tier->wire (:context-tier opts))
         params (cond-> {:sessionId session-id
                         :modelId model-id}
                  (:reasoning-effort opts) (assoc :reasoningEffort (:reasoning-effort opts))
                  (:reasoning-summary opts) (assoc :reasoningSummary (:reasoning-summary opts))
                  (some? context-tier) (assoc :contextTier context-tier)
                  (:model-capabilities opts) (assoc :modelCapabilities
                                                    (util/model-capabilities->wire
                                                     (:model-capabilities opts))))
         result (proto/send-request! conn "session.model.switchTo" params)]
     (:model-id result))))

(defn set-model!
  "Alias for switch-model!. Matches the upstream SDK's setModel() API.
   See switch-model! for details."
  ([session model-id] (switch-model! session model-id nil))
  ([session model-id opts] (switch-model! session model-id opts)))

(defn log!
  "Log a message to the session timeline.
   Options (optional map):
   - :level      - \"info\", \"warning\", or \"error\" (default: \"info\")
   - :ephemeral? - when true, message is not persisted to disk (default: false)
   Returns the event ID string."
  ([session message] (log! session message nil))
  ([session message opts]
   (let [{:keys [session-id client]} session
         conn (connection-io client)
         params (cond-> {:sessionId session-id :message message}
                  (:level opts) (assoc :level (:level opts))
                  (:ephemeral? opts) (assoc :ephemeral (:ephemeral? opts)))
         result (proto/send-request! conn "session.log" params)]
     (:event-id result))))

;; =============================================================================
;; Low-level RPC methods (session.rpc.*)
;;
;; These are thin wrappers around the CLI's JSON-RPC methods. They are emerging
;; APIs that don't yet have friendly high-level wrappers in the upstream SDK.
;; Some are marked experimental and may change.
;; =============================================================================

;; -- Skills ------------------------------------------------------------------

(defn ^:experimental skills-list
  "List all skills available to the session.
   Returns a map with :skills (vector of skill info maps)."
  [session]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (util/wire->clj
     (proto/send-request! conn "session.skills.list" {:sessionId session-id}))))

(defn ^:experimental skills-enable!
  "Enable a skill by name."
  [session skill-name]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (proto/send-request! conn "session.skills.enable"
                         {:sessionId session-id :name skill-name})))

(defn ^:experimental skills-disable!
  "Disable a skill by name."
  [session skill-name]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (proto/send-request! conn "session.skills.disable"
                         {:sessionId session-id :name skill-name})))

(defn ^:experimental skills-reload!
  "Reload all skills."
  [session]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (proto/send-request! conn "session.skills.reload" {:sessionId session-id})))

;; -- Commands (queued command response) --------------------------------------

(defn ^:experimental respond-to-queued-command!
  "Respond to a `:copilot/command.queued` event from the CLI.

   The CLI emits `command.queued` events when a slash-command is dispatched
   for client-side execution. Each event carries a `:request-id` (and
   `:command`). Clients should respond with either:

     {:request-id <id> :handled? true                       ;; consumed by client
      :stop-processing-queue? false}                        ;; optional
     {:request-id <id> :handled? false}                     ;; fall through

   When `:handled?` is `true`, the CLI marks the queued command done; when
   `:stop-processing-queue?` is also `true`, the CLI stops processing the
   remainder of the queue. When `:handled?` is `false`, the CLI re-routes
   the command to its default handling. The CLI accepts the response via
   the `session.commands.respondToQueuedCommand` RPC.

   Marked experimental — the upstream Node SDK exposes this only via the
   generated low-level `commands.respondToQueuedCommand` RPC (no high-level
   helper)."
  [session {:keys [request-id handled? stop-processing-queue?] :as params}]
  (let [{:keys [session-id client]} session
        conn (connection-io client)
        result (cond-> {:handled (boolean handled?)}
                 (and handled? (contains? params :stop-processing-queue?))
                 (assoc :stopProcessingQueue (boolean stop-processing-queue?)))]
    (proto/send-request! conn "session.commands.respondToQueuedCommand"
                         {:sessionId session-id
                          :requestId request-id
                          :result result})))

;; -- MCP Servers -------------------------------------------------------------

(defn ^:experimental mcp-list
  "List all MCP servers configured for the session.
   Returns a map with :servers (vector of server info maps)."
  [session]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (util/wire->clj
     (proto/send-request! conn "session.mcp.list" {:sessionId session-id}))))

(defn ^:experimental mcp-enable!
  "Enable an MCP server by name."
  [session server-name]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (proto/send-request! conn "session.mcp.enable"
                         {:sessionId session-id :serverName server-name})))

(defn ^:experimental mcp-disable!
  "Disable an MCP server by name."
  [session server-name]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (proto/send-request! conn "session.mcp.disable"
                         {:sessionId session-id :serverName server-name})))

(defn ^:experimental mcp-reload!
  "Reload all MCP servers."
  [session]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (proto/send-request! conn "session.mcp.reload" {:sessionId session-id})))

;; -- Extensions --------------------------------------------------------------

(defn ^:experimental extensions-list
  "List all extensions for the session.
   Returns a map with :extensions (vector of extension info maps)."
  [session]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (util/wire->clj
     (proto/send-request! conn "session.extensions.list" {:sessionId session-id}))))

(defn ^:experimental extensions-enable!
  "Enable an extension by its source-qualified ID."
  [session extension-id]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (proto/send-request! conn "session.extensions.enable"
                         {:sessionId session-id :id extension-id})))

(defn ^:experimental extensions-disable!
  "Disable an extension by its source-qualified ID."
  [session extension-id]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (proto/send-request! conn "session.extensions.disable"
                         {:sessionId session-id :id extension-id})))

(defn ^:experimental extensions-reload!
  "Reload all extensions."
  [session]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (proto/send-request! conn "session.extensions.reload" {:sessionId session-id})))

;; -- Plugins -----------------------------------------------------------------

(defn ^:experimental plugins-list
  "List all plugins for the session.
   Returns a map with :plugins (vector of plugin info maps)."
  [session]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (util/wire->clj
     (proto/send-request! conn "session.plugins.list" {:sessionId session-id}))))

;; -- History (compaction / truncation) ----------------------------------------

(defn ^:experimental compaction-compact!
  "Trigger manual compaction of the session context.
   Note: renamed from session.compaction.compact to session.history.compact in upstream #1039."
  [session]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (util/wire->clj
     (proto/send-request! conn "session.history.compact" {:sessionId session-id}))))

(defn ^:experimental history-truncate!
  "Trigger manual truncation of the session context (upstream #1039)."
  [session]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (util/wire->clj
     (proto/send-request! conn "session.history.truncate" {:sessionId session-id}))))

(defn ^:experimental history-clear-context!
  "Clear conversation context and set the prompt used to start the new context.
   Returns `{:messages-cleared n}` (upstream PR #2129)."
  [session prompt]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (util/wire->clj
     (proto/send-request! conn "session.history.clearContext"
                          {:sessionId session-id
                           :prompt prompt}))))

(defn ^:experimental sessions-fork!
  "Fork the current session (upstream #1039).
   This is a server-scoped RPC."
  [session]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (util/wire->clj
     (proto/send-request! conn "sessions.fork" {:sessionId session-id}))))

;; -- Shell -------------------------------------------------------------------

(defn ^:experimental shell-exec!
  "Execute a shell command in the session.
   Returns the execution result."
  [session command]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (util/wire->clj
     (proto/send-request! conn "session.shell.exec"
                          {:sessionId session-id :command command}))))

(defn ^:experimental shell-kill!
  "Kill a running shell process by process ID."
  [session process-id]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (proto/send-request! conn "session.shell.kill"
                         {:sessionId session-id :processId process-id})))

;; -- Mode -------------------------------------------------------------------

(defn ^:experimental mode-get
  "Get the current agent mode for the session.
   Returns a map with :mode (\"interactive\", \"plan\", or \"autopilot\")."
  [session]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (util/wire->clj
     (proto/send-request! conn "session.mode.get" {:sessionId session-id}))))

(defn ^:experimental mode-set!
  "Set the agent mode for the session.
   mode should be \"interactive\", \"plan\", or \"autopilot\"."
  [session mode]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (util/wire->clj
     (proto/send-request! conn "session.mode.set" {:sessionId session-id :mode mode}))))

;; -- Plan -------------------------------------------------------------------

(defn ^:experimental plan-read
  "Read the plan file for the session.
   Returns a map with :exists? (boolean), :content (string or nil),
   and :file-path (string or nil)."
  [session]
  (let [{:keys [session-id client]} session
        conn (connection-io client)
        result (util/wire->clj
                (proto/send-request! conn "session.plan.read" {:sessionId session-id}))]
    (if (contains? result :exists)
      (-> result
          (assoc :exists? (:exists result))
          (dissoc :exists))
      result)))

(defn ^:experimental plan-update!
  "Update the plan file content for the session."
  [session content]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (util/wire->clj
     (proto/send-request! conn "session.plan.update" {:sessionId session-id :content content}))))

(defn ^:experimental plan-delete!
  "Delete the plan file for the session."
  [session]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (util/wire->clj
     (proto/send-request! conn "session.plan.delete" {:sessionId session-id}))))

;; -- Workspace --------------------------------------------------------------

(defn ^:experimental workspace-list-files
  "List files in the session workspace directory.
   Returns a map with :files (vector of relative file paths)."
  [session]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (util/wire->clj
     (proto/send-request! conn "session.workspace.listFiles" {:sessionId session-id}))))

(defn ^:experimental workspace-read-file
  "Read a file from the session workspace.
   path is relative to the workspace files directory.
   Returns a map with :content (string)."
  [session path]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (util/wire->clj
     (proto/send-request! conn "session.workspace.readFile" {:sessionId session-id :path path}))))

(defn ^:experimental workspace-create-file!
  "Create a file in the session workspace.
   path is relative to the workspace files directory."
  [session path content]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (util/wire->clj
     (proto/send-request! conn "session.workspace.createFile"
                          {:sessionId session-id :path path :content content}))))

;; -- Agent ------------------------------------------------------------------

(defn ^:experimental agent-list
  "List all custom agents available to the session.
   Returns a map with :agents (vector of agent info maps)."
  [session]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (util/wire->clj
     (proto/send-request! conn "session.agent.list" {:sessionId session-id}))))

(defn ^:experimental agent-get-current
  "Get the currently active custom agent for the session.
   Returns a map with :name (string or nil)."
  [session]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (util/wire->clj
     (proto/send-request! conn "session.agent.getCurrent" {:sessionId session-id}))))

(defn ^:experimental agent-select!
  "Select a custom agent by name."
  [session agent-name]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (util/wire->clj
     (proto/send-request! conn "session.agent.select" {:sessionId session-id :name agent-name}))))

(defn ^:experimental agent-deselect!
  "Deselect the current custom agent."
  [session]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (util/wire->clj
     (proto/send-request! conn "session.agent.deselect" {:sessionId session-id}))))

(defn ^:experimental agent-reload!
  "Reload all custom agents."
  [session]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (util/wire->clj
     (proto/send-request! conn "session.agent.reload" {:sessionId session-id}))))

;; -- Fleet ------------------------------------------------------------------

(defn ^:experimental fleet-start!
  "Start a fleet of parallel sub-sessions.
   params is a map forwarded to the session.fleet.start RPC."
  [session params]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (util/wire->clj
     (proto/send-request! conn "session.fleet.start"
                          (assoc (merge {} params) :session-id session-id)))))

;; -- Session Name -----------------------------------------------------------

(defn ^:experimental session-name-get
  "Get the session name (or auto-generated summary).
   Returns a map with :name (string or nil)."
  [session]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (util/wire->clj
     (proto/send-request! conn "session.name.get" {:session-id session-id}))))

(defn ^:experimental session-name-set!
  "Set the session name (1–100 characters)."
  [session name]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (util/wire->clj
     (proto/send-request! conn "session.name.set"
                          {:session-id session-id :name name}))))

;; -- Workspace (extended) ---------------------------------------------------

(defn ^:experimental workspace-get-workspace
  "Get current workspace metadata. Returns a map with :workspace (map or nil)."
  [session]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (util/wire->clj
     (proto/send-request! conn "session.workspaces.getWorkspace"
                          {:session-id session-id}))))

;; -- MCP Discovery ----------------------------------------------------------

(defn ^:experimental mcp-discover
  "Discover MCP servers in the working directory.
   opts is an optional map with :working-directory."
  ([session] (mcp-discover session {}))
  ([session opts]
   (let [{:keys [session-id client]} session
         conn (connection-io client)]
     (util/wire->clj
      (proto/send-request! conn "mcp.discover"
                           (cond-> {}
                             (:working-directory opts)
                             (assoc :working-directory (:working-directory opts))))))))

;; -- Usage Metrics ----------------------------------------------------------

(defn ^:experimental usage-get-metrics
  "Get usage metrics for the session."
  [session]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (util/wire->clj
     (proto/send-request! conn "session.usage.getMetrics"
                          {:session-id session-id}))))

;; -- Remote sessions (Mission Control) -------------------------------------

(defn ^:experimental remote-enable
  "Enable remote steering for this session, exposing it to GitHub Mission
   Control web/mobile clients.

   Optional `opts`:
   - `:mode` — keyword, one of `:off`, `:export`, or `:on`. Per-session remote
     mode. `:off` disables remote, `:export` exports session events to Mission
     Control without enabling remote steering, `:on` enables both export and
     remote steering. When omitted, the CLI applies its default. (Upstream CLI
     1.0.48-1, PR #1288.)

   Returns a map:
   - `:url`               — Mission Control frontend URL (may be absent).
   - `:remote-steerable`  — boolean; whether remote steering is enabled.

   **Experimental** — corresponds to the `session.remote.enable` JSON-RPC
   method introduced upstream in PR #1192. The shape of the result and
   guarantees may change."
  ([session] (remote-enable session nil))
  ([session opts]
   (when (and opts (not (s/valid? ::specs/remote-enable-opts opts)))
     (throw (ex-info "Invalid remote-enable opts"
                     {:opts opts
                      :explain (s/explain-data ::specs/remote-enable-opts opts)})))
   (let [{:keys [session-id client]} session
         conn (connection-io client)
         base {:session-id session-id}
         params (if-let [m (:mode opts)]
                  (assoc base :mode (name m))
                  base)]
     (util/wire->clj
      (proto/send-request! conn "session.remote.enable" params)))))

(defn ^:experimental remote-disable
  "Disable remote steering for this session. Returns nil.

   **Experimental** — corresponds to the `session.remote.disable` JSON-RPC
   method introduced upstream in PR #1192."
  [session]
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (proto/send-request! conn "session.remote.disable"
                         {:session-id session-id})
    nil))

;; -- UI Elicitation ----------------------------------------------------------

(defn capabilities
  "Get the host capabilities reported when the session was created or resumed.
   Returns a map, e.g. `{:ui {:elicitation true}}`."
  [session]
  (let [{:keys [session-id client]} session]
    (:capabilities (session-state client session-id))))

(defn open-canvases
  "Get the current open-canvases snapshot for `session`. Returns a vector of
  canvas-instance maps (each with `:instance-id`, `:extension-id`, `:canvas-id`,
  plus optional `:extension-name`, `:icon`, `:title`, `:status`, `:url`,
  `:input`). The snapshot is initialized from `session.resume` and updated by
  `session.canvas.opened` / `session.canvas.closed` events. `session.create`
  does NOT populate it (matches upstream Node.js behavior)."
  [session]
  (let [{:keys [session-id client]} session]
    (vec (:open-canvases (session-state client session-id)))))

(defn elicitation-supported?
  "Check if the CLI host supports interactive elicitation dialogs."
  [session]
  (boolean (get-in (capabilities session) [:ui :elicitation])))

(defn- assert-elicitation! [session]
  (when-not (elicitation-supported? session)
    (throw (ex-info "Elicitation is not supported by the host. Check (elicitation-supported? session) before calling UI methods."
                    {:session-id (:session-id session)
                     :capabilities (capabilities session)}))))

(defn ui-elicitation!
  "Request structured user input via an elicitation prompt.
   params is a map with :message and :requested-schema keys.
   Throws if the host does not support elicitation."
  [session params]
  (assert-elicitation! session)
  (let [{:keys [session-id client]} session
        conn (connection-io client)]
    (util/wire->clj
     (proto/send-request! conn "session.ui.elicitation"
                          (assoc (util/clj->wire params) :sessionId session-id)))))

(defn confirm!
  "Show a confirmation dialog and return the user's boolean answer.
   Returns false if the user declines or cancels.
   Throws if the host does not support elicitation."
  [session message]
  (let [result (ui-elicitation! session
                                {:message message
                                 :requested-schema
                                 {:type "object"
                                  :properties {"confirmed" {:type "boolean" :default true}}
                                  :required ["confirmed"]}})]
    (and (= "accept" (:action result))
         (true? (get-in result [:content :confirmed])))))

(defn select!
  "Show a selection dialog with the given options.
   Returns the selected value as a string, or nil if the user declines/cancels.
   Throws if the host does not support elicitation."
  [session message options]
  (let [result (ui-elicitation! session
                                {:message message
                                 :requested-schema
                                 {:type "object"
                                  :properties {"selection" {:type "string" :enum (vec options)}}
                                  :required ["selection"]}})]
    (when (and (= "accept" (:action result))
               (some? (get-in result [:content :selection])))
      (get-in result [:content :selection]))))

(defn input!
  "Show a text input dialog. Returns the entered text, or nil if the user
   declines/cancels. opts is an optional map with :title, :description,
   :min-length, :max-length, :format, and :default keys.
   Throws if the host does not support elicitation."
  ([session message] (input! session message nil))
  ([session message opts]
   (let [field (cond-> {:type "string"}
                 (:title opts) (assoc :title (:title opts))
                 (:description opts) (assoc :description (:description opts))
                 (some? (:min-length opts)) (assoc :minLength (:min-length opts))
                 (some? (:max-length opts)) (assoc :maxLength (:max-length opts))
                 (:format opts) (assoc :format (:format opts))
                 (some? (:default opts)) (assoc :default (:default opts)))
         result (ui-elicitation! session
                                 {:message message
                                  :requested-schema
                                  {:type "object"
                                   :properties {"value" field}
                                   :required ["value"]}})]
     (when (and (= "accept" (:action result))
                (some? (get-in result [:content :value])))
       (get-in result [:content :value])))))
