(ns github.copilot-sdk.protocol
  "JSON-RPC 2.0 protocol implementation using java.nio channels.
   
   Architecture:
   - NIO channels for interruptible I/O (clean shutdown)
   - core.async channels for message flow
   - Single reader thread puts to incoming-ch
   - Writer go-loop takes from outgoing-ch
   - Reverse requests run on a bounded worker pool owned by the connection
   - State is managed externally (passed in as atom)
   
   This design allows clean shutdown: closing NIO channels causes
   reader to throw AsynchronousCloseException and exit gracefully."
  (:require [clojure.data.json :as json]
            [clojure.core.async :as async :refer [go-loop <! >!! <!! chan close! put!]]
            [clojure.core.async.impl.protocols :as async-protocols]
            [clojure.string :as str]
            [github.copilot-sdk.generated.event-metadata :as event-metadata]
            [github.copilot-sdk.logging :as log]
            [github.copilot-sdk.teardown :as td]
            [github.copilot-sdk.util :as util])
  (:import [java.io InputStream OutputStream IOException]
           [java.nio ByteBuffer]
           [java.nio.channels Channels ReadableByteChannel WritableByteChannel ClosedChannelException]
           [java.nio.channels AsynchronousCloseException]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent ArrayBlockingQueue LinkedBlockingQueue
            RejectedExecutionException ThreadFactory ThreadPoolExecutor
            ThreadPoolExecutor$AbortPolicy TimeUnit]
           [java.util.concurrent.atomic AtomicLong]))

(def ^:private content-length-header "Content-Length: ")

(def ^:private request-worker-thread-name-prefix
  "Thread-name prefix for a connection's reverse-request worker pool.

   Reverse request handlers are arbitrary caller code, so they always run on a
   thread with this prefix -- never on core.async `go` dispatch. In core.async
   1.8 `go` dispatch shares the process-wide unbounded cached `:io` executor,
   so blocking there grows that pool without bound."
  "jsonrpc-request-worker-")

(def ^:no-doc default-request-handler-threads
  "Default cap on concurrently executing reverse request handlers.

   Implementation support for `client`'s option defaults, not documented API."
  16)

(def ^:no-doc default-request-handler-queue-size
  "Default number of reverse requests queued before overload is reported.

   Implementation support for `client`'s option defaults, not documented API."
  256)

;; -----------------------------------------------------------------------------
;; NIO-based Message Framing (Content-Length based, vscode-jsonrpc compatible)
;; -----------------------------------------------------------------------------

(defn- read-byte
  "Read a single byte from channel. Returns byte value or -1 on EOF."
  [^ReadableByteChannel channel ^ByteBuffer buf]
  (.clear buf)
  (.limit buf 1)
  (let [n (.read channel buf)]
    (if (pos? n)
      (do (.flip buf) (bit-and (.get buf) 0xFF))
      -1)))

(defn- read-line-bytes
  "Read a line (until CRLF or LF) from channel. Returns string or nil on EOF."
  [^ReadableByteChannel channel ^ByteBuffer single-byte-buf]
  (let [sb (StringBuilder.)]
    (loop []
      (let [b (read-byte channel single-byte-buf)]
        (cond
          (neg? b) (if (pos? (.length sb)) (str sb) nil)
          (= b 10) (str sb)  ; LF
          (= b 13) (recur)   ; CR - skip
          :else (do (.append sb (char b)) (recur)))))))

(defn- read-bytes
  "Read exactly n bytes from channel into a new byte array."
  [^ReadableByteChannel channel n]
  (let [buf (ByteBuffer/allocate n)]
    (loop [remaining n]
      (when (pos? remaining)
        (let [read (.read channel buf)]
          (when (neg? read)
            (throw (IOException. (str "EOF: expected " n " bytes, got " (- n remaining)))))
          (recur (- remaining read)))))
    (.array buf)))

(defn- read-headers
  "Read headers until empty line. Returns map of header-name -> value, or nil if connection closed."
  [^ReadableByteChannel channel ^ByteBuffer single-byte-buf]
  (loop [headers {}]
    (let [line (read-line-bytes channel single-byte-buf)]
      (cond
        (nil? line)
        nil  ;; Connection closed - return nil instead of throwing

        (str/blank? line)
        headers

        :else
        (let [[k v] (str/split line #": " 2)]
          (recur (assoc headers (str/lower-case (str/trim k)) (str/trim (or v "")))))))))

(defn- read-message
  "Read a single JSON-RPC message from channel. Returns parsed JSON map or nil on EOF/close."
  [^ReadableByteChannel channel ^ByteBuffer single-byte-buf]
  (when-let [headers (read-headers channel single-byte-buf)]
    (let [content-length (some-> (get headers "content-length") parse-long)]
      (when-not content-length
        (throw (IOException. "Missing Content-Length header")))
      (let [content-bytes (read-bytes channel content-length)
            content (String. content-bytes StandardCharsets/UTF_8)]
        (json/read-str content :key-fn keyword)))))

(defn- write-message!
  "Write a JSON-RPC message to channel with Content-Length framing."
  [^WritableByteChannel channel msg]
  (let [json-str (json/write-str msg)
        content-bytes (.getBytes json-str StandardCharsets/UTF_8)
        header (str content-length-header (alength content-bytes) "\r\n\r\n")
        header-bytes (.getBytes header StandardCharsets/UTF_8)
        buf (ByteBuffer/allocate (+ (alength header-bytes) (alength content-bytes)))]
    (.put buf header-bytes)
    (.put buf content-bytes)
    (.flip buf)
    (while (.hasRemaining buf)
      (.write channel buf))))

;; -----------------------------------------------------------------------------
;; Connection Record - holds IO resources only, state is external
;; -----------------------------------------------------------------------------

(defrecord Connection
           [^ReadableByteChannel read-channel
            ^WritableByteChannel write-channel
            ^OutputStream output-stream   ; Keep reference for flushing
            state-atom                    ; atom owned by client, contains :connection key
            incoming-ch                   ; channel for incoming messages (responses + notifications)
            outgoing-ch                   ; channel for outgoing messages
            notification-queue            ; queue for notifications to avoid blocking reader
            notification-thread           ; Thread
            read-thread                   ; Thread
            request-executor              ; bounded pool running reverse request handlers
            rejected-requests             ; AtomicLong of overload-rejected reverse requests
            dropped-notifications])       ; AtomicLong of notifications dropped on a full queue

(defn- request-worker-thread-factory
  []
  (let [counter (AtomicLong.)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. ^Runnable runnable
                       (str request-worker-thread-name-prefix (.incrementAndGet counter)))
          (.setDaemon true))))))

(defn- make-request-executor
  "Bounded worker pool that executes reverse request handlers.

   Handlers are arbitrary caller code that may block, so they must not run on
   core.async `go` dispatch. Concurrency is capped at `threads`, bursts are
   absorbed by a bounded queue, and further submissions are rejected so the
   caller can answer with an explicit overload error instead of blocking the
   reader thread or silently discarding the request. Core threads time out, so
   an idle connection holds no worker threads."
  ^ThreadPoolExecutor [threads queue-size]
  (doto (ThreadPoolExecutor. (int threads) (int threads)
                             60 TimeUnit/SECONDS
                             (ArrayBlockingQueue. (int queue-size))
                             (request-worker-thread-factory)
                             (ThreadPoolExecutor$AbortPolicy.))
    (.allowCoreThreadTimeOut true)))

(defn- connection-stats
  "Snapshot of a connection's worker and queue counters, for diagnostics and
   tests.

   - `:dropped-notifications` -- notifications discarded because the bounded
     notification queue was full
   - `:rejected-requests` -- reverse requests answered with an overload error
     because the handler pool was saturated
   - `:active-request-workers` / `:queued-requests` -- current pool occupancy
   - `:request-workers-terminated?` -- whether the handler pool has shut down"
  [conn]
  (let [^ThreadPoolExecutor executor (:request-executor conn)]
    {:dropped-notifications (.get ^AtomicLong (:dropped-notifications conn))
     :rejected-requests (.get ^AtomicLong (:rejected-requests conn))
     :active-request-workers (.getActiveCount executor)
     :queued-requests (.size (.getQueue executor))
     :request-workers-terminated? (.isTerminated executor)}))

;; State path helpers
(defn- conn-state [state-atom] (get @state-atom :connection))
(defn- update-conn! [state-atom f & args] (apply swap! state-atom update :connection f args))

(defn- drain-pending!
  "Atomically clear all pending requests and deliver `error` to each response
   channel. Safe to call concurrently from the read loop and `disconnect`: the
   single `swap-vals!` guarantees each pending entry is captured exactly once, so
   no response channel is delivered to twice. `error` is the `:error` payload
   (e.g. `{:code -32000 :message \"...\"}`)."
  [state-atom error]
  (let [[old _] (swap-vals! state-atom assoc-in [:connection :pending-requests] {})]
    (doseq [[_ {:keys [ch]}] (get-in old [:connection :pending-requests])]
      (put! ch {:error error})
      (close! ch))))

(defn- pop-pending!
  "Atomically remove and return the pending entry for `id`, or nil if it is no
   longer registered. Using `swap-vals!` guarantees the entry is claimed by
   exactly one caller, so `handle-response!` and `drain-pending!` can never both
   deliver to the same response channel."
  [state-atom id]
  (let [[old _] (swap-vals! state-atom update-in [:connection :pending-requests] dissoc id)]
    (get-in old [:connection :pending-requests id])))

;; -----------------------------------------------------------------------------
;; Message Handling
;; -----------------------------------------------------------------------------

(declare normalize-incoming)

(defn- normalize-response
  "Normalize a response using its originating method so opaque factory JSON
   survives the protocol boundary unchanged."
  [method msg]
  (let [converted (normalize-incoming msg)]
    (case method
      ("session.factory.run" "session.factory.getRun" "session.factory.cancel")
      (cond-> converted
        (contains? (:result msg) :result)
        (assoc-in [:result :result] (get-in msg [:result :result]))

        (contains? (:result msg) :snapshot)
        (assoc-in [:result :snapshot] (get-in msg [:result :snapshot])))

      "session.factory.resume"
      (cond-> converted
        (contains? (get-in msg [:result :run]) :result)
        (assoc-in [:result :run :result]
                  (get-in msg [:result :run :result]))

        (contains? (get-in msg [:result :run]) :snapshot)
        (assoc-in [:result :run :snapshot]
                  (get-in msg [:result :run :snapshot])))

      "session.resume"
      (cond-> converted
        (contains? (:result msg) :grantedEnvironmentVariables)
        (assoc-in [:result :granted-environment-variables]
                  (get-in msg [:result :grantedEnvironmentVariables])))

      ("session.factory.agent" "session.factory.journal.get")
      (cond
        (contains? (:result msg) :result)
        (assoc-in converted [:result :result] (get-in msg [:result :result]))

        (contains? (:result msg) :resultJson)
        (assoc-in converted [:result :result-json]
                  (get-in msg [:result :resultJson]))

        :else converted)

      converted)))

(defn- handle-response!
  "Handle an incoming response message. Delivers to pending channel.

   If the original `send-request` call supplied an `:on-response-inline`
   callback, invoke it synchronously **before** delivering the result to
   the response channel. This callback runs in the reader thread, so any
   work it performs (e.g. session registration after a server-assigned
   sessionId) is guaranteed to complete before the next inbound message
   is dispatched. Used by `client/create-session` for the cloud-no-id
   flow (upstream PR #1479)."
  [state-atom raw-msg]
  (let [id (:id raw-msg)]
    (log/debug "Received response for id=" id)
    ;; Atomically claim the pending entry so a concurrent drain-pending!
    ;; (disconnect / EOF) can never also deliver to this response channel.
    (when-let [{:keys [ch on-response-inline method]} (pop-pending! state-atom id)]
      (let [msg (normalize-response method raw-msg)]
        (if-let [error (:error msg)]
          (do
            (log/debug "Response error: " error)
            (put! ch {:error error})
            (close! ch))
          (let [result (:result msg)]
            (log/debug "Response success for id=" id)
            (when on-response-inline
              (try
                (on-response-inline result)
                (catch Throwable t
                  (log/error t "on-response-inline callback threw for id=" id))))
            (put! ch {:result result})
            (close! ch)))))))

(defn- preserve-outgoing-opaque-fields
  "Per-method outgoing escape hatch: after recursive kebab→camelCase
   conversion via `util/clj->wire`, restore opaque user-supplied values
   that must round-trip verbatim (e.g. SQL column names in
   `sessionFs.sqliteQuery` result rows). Without this, a provider
   returning `{:rows [{:user_id 1}]}` would be serialized as
   `{:userId 1}`, producing rows that no longer match the `columns`
   array."
  [method raw-result wire-result]
  (cond
    ;; Upstream PR #1299: SQL row column names are opaque identifiers.
    ;; Preserve the original :rows vector verbatim while keeping the
    ;; sibling SDK fields (rows-affected → rowsAffected, etc.) converted.
    (and (= "sessionFs.sqliteQuery" method)
         (map? raw-result)
         (contains? raw-result :rows))
    (assoc wire-result :rows (:rows raw-result))

    (and (= "sessionFs.sqliteTransaction" method)
         (map? raw-result)
         (vector? (:results raw-result)))
    (update wire-result :results
            (fn [wire-results]
              (mapv (fn [index wire-entry]
                      (let [raw-entry (get (:results raw-result) index)]
                        (if (and (map? raw-entry) (contains? raw-entry :rows))
                          (assoc wire-entry :rows (:rows raw-entry))
                          wire-entry)))
                    (range)
                    wire-results)))

    (and (= "factory.execute" method)
         (map? raw-result)
         (contains? raw-result :result))
    (assoc wire-result :result (:result raw-result))

    ;; Upstream PR #1366: HookInvokeResponse.output may contain opaque
    ;; preMcpToolCall metadata under `:meta-to-use`. The inner map's keys
    ;; are source-defined and must NOT be camelCased. `contains?` (not
    ;; truthiness) preserves an explicit `nil` as JSON `null`.
    (and (= "hooks.invoke" method)
         (map? (:output raw-result))
         (contains? (:output raw-result) :meta-to-use))
    (assoc-in wire-result [:output :metaToUse]
              (get-in raw-result [:output :meta-to-use]))

    :else wire-result))

(defn- run-request-handler!
  "Execute one reverse request and answer the peer.

   Runs on a worker thread, never inside `go`: `request-handler` is arbitrary
   caller code that may perform ordinary blocking Clojure or Java work before
   it returns its result channel.

   There is deliberately no timeout around the handler's result. A wedged
   handler occupies exactly one worker; the pool bound and the overload error
   in [[handle-request!]] are what make that visible, rather than a fallback
   that hides it."
  [request-handler outgoing-ch method id params]
  (try
    (let [result (if request-handler
                   (let [result-ch (request-handler method params)]
                     (when-not (satisfies? async-protocols/ReadPort result-ch)
                       (throw (ex-info "Request handler must return a core.async channel"
                                       {:method method
                                        :returned-type (some-> result-ch class str)})))
                     (<!! result-ch))
                   {:error {:code -32601 :message "Method not found"}})]
      (if-let [error (:error result)]
        (do
          (log/debug "Request error response: " error)
          (put! outgoing-ch {:jsonrpc "2.0" :id id :error (util/clj->wire error)}))
        (do
          (log/debug "Request success response for id=" id)
          (put! outgoing-ch {:jsonrpc "2.0" :id id
                             :result (preserve-outgoing-opaque-fields
                                      method
                                      (:result result)
                                      (util/clj->wire (:result result)))}))))
    (catch InterruptedException _
      ;; Expected: `disconnect` interrupts workers still inside a handler.
      (.interrupt (Thread/currentThread))
      (log/debug "Reverse request worker interrupted for id=" id))
    (catch Exception t
      (log/error "Request handler exception for method=" method " id=" id ": " (ex-message t))
      (put! outgoing-ch {:jsonrpc "2.0"
                         :id id
                         :error {:code -32603
                                 :message (str "Internal error: " (ex-message t))}}))))

(defn- reject-request!
  "Answer a reverse request that the worker pool refused.

   Saturation and shutdown are reported as explicit JSON-RPC errors. The
   reader thread never blocks waiting for capacity."
  [^ThreadPoolExecutor executor ^AtomicLong rejected-requests outgoing-ch method id]
  (if (.isShutdown executor)
    (do
      (log/debug "Rejecting request during shutdown: method=" method " id=" id)
      (put! outgoing-ch
            {:jsonrpc "2.0"
             :id id
             :error {:code -32000
                     :message "Connection closed"
                     :data (util/clj->wire {:code "connection_closed"
                                            :method method})}}))
    (let [queue (.getQueue executor)
          max-concurrency (.getMaximumPoolSize executor)
          queue-size (+ (.size queue) (.remainingCapacity queue))
          total (.incrementAndGet rejected-requests)]
      (log/warn "Reverse request handler pool saturated, rejecting request: method=" method
                " id=" id " max-concurrency=" max-concurrency
                " queue-size=" queue-size " rejected-total=" total)
      (put! outgoing-ch
            {:jsonrpc "2.0"
             :id id
             :error {:code -32000
                     :message "Reverse request handler pool saturated"
                     :data (util/clj->wire {:code "request_handler_saturated"
                                            :method method
                                            :max-concurrency max-concurrency
                                            :queue-size queue-size})}}))))

(defn- handle-request!
  "Submit an incoming request message (e.g. hooks.invoke) to the connection's
   bounded reverse-request worker pool.

   Called on the reader thread. Submission is non-blocking, so the reader keeps
   routing responses and notifications while handlers run."
  [conn msg]
  (let [{:keys [state-atom outgoing-ch]} conn
        ^ThreadPoolExecutor executor (:request-executor conn)
        request-handler (:request-handler (conn-state state-atom))
        id (:id msg)
        method (:method msg)
        params (:params msg)]
    (log/debug "Received request: method=" method " id=" id)
    (try
      (.execute executor
                ^Runnable (fn []
                            (run-request-handler! request-handler outgoing-ch
                                                  method id params)))
      (catch RejectedExecutionException _
        (reject-request! executor (:rejected-requests conn) outgoing-ch method id)))))

(defn- restore-opaque-path
  [raw converted wire-path idiom-path]
  (if (empty? wire-path)
    raw
    (let [wire-key (first wire-path)
          idiom-key (first idiom-path)
          remaining-wire (next wire-path)
          remaining-idiom (next idiom-path)]
      (if (= :* wire-key)
        (if (and (sequential? raw) (sequential? converted))
          (mapv (fn [raw-value converted-value]
                  (restore-opaque-path raw-value converted-value
                                       remaining-wire remaining-idiom))
                raw
                converted)
          converted)
        (if (and (map? raw)
                 (map? converted)
                 (contains? raw wire-key)
                 (contains? converted idiom-key))
          (assoc converted idiom-key
                 (restore-opaque-path (get raw wire-key)
                                      (get converted idiom-key)
                                      remaining-wire remaining-idiom))
          converted)))))

(defn- preserve-event-opaque-fields
  "Given a raw wire event (pre-`wire->clj`) and a converted event, restore
   source-defined / opaque fields verbatim onto the converted shape so
   kebab-casing doesn't mangle user-supplied keys. Applies the per-event-type
   rules used by `normalize-incoming` for live notifications, so live and
    historical events share the same shape."
  [raw-event converted-event]
  (let [restored
        (reduce
         (fn [converted {:keys [wire idiom]}]
           (restore-opaque-path raw-event converted wire idiom))
         converted-event
         (get event-metadata/opaque-json-paths (:type raw-event)))]
    (if (and (= "session.custom_notification" (:type raw-event))
             (map? (:data raw-event))
             (map? (:data restored))
             (contains? (:data raw-event) :subject))
      (assoc-in restored [:data :subject] (get-in raw-event [:data :subject]))
      restored)))

(defn- normalize-incoming
  "Convert wire-format keys to Clojure keys, preserving opaque user data.

   For v3 `external_tool.requested` broadcast events, tool arguments are
   kept in their original wire format so user-defined tool handlers receive
   the keys the server sent. For v3 `session.custom_notification` events,
   the source-defined `:subject` and opaque `:payload` are also preserved
   verbatim. Assistant `:reasoning-blocks` retain provider-defined key
   spelling. For v3 `mcp_app.tool_call_complete` events (schema 1.0.52-4,
   SEP-1865), the `:arguments` and `:result` payloads are similarly preserved.
   The same preservation applies to historical events returned in
   `session.getMessages` responses so live and historical event shapes agree."
  [msg]
  (let [method (:method msg)
        params (:params msg)
        converted (util/wire->clj msg)
        raw-events (get-in msg [:result :events])]
    (cond
      ;; Upstream PR #1299: SQL bind parameters are opaque keyed values
      ;; (e.g. `$user_id`). Preserve the raw map so kebab-case conversion
      ;; doesn't mangle placeholder names before the handler binds them.
      (and (= "sessionFs.sqliteQuery" method) (map? params) (contains? params :params))
      (assoc-in converted [:params :params] (:params params))

      (and (= "sessionFs.sqliteTransaction" method)
           (vector? (:statements params)))
      (update-in converted [:params :statements]
                 (fn [converted-statements]
                   (mapv (fn [index converted-statement]
                           (let [raw-statement (get (:statements params) index)]
                             (if (contains? raw-statement :params)
                               (assoc converted-statement :params (:params raw-statement))
                               converted-statement)))
                         (range)
                         converted-statements)))

      (and (= "factory.execute" method)
           (map? params)
           (contains? params :args))
      (assoc-in converted [:params :args] (:args params))

      ;; Upstream PR #1366: preMcpToolCall hook input has two opaque
      ;; fields that must NOT be recursively kebab-cased:
      ;; - `:arguments`: MCP tool call arguments (source-defined keys)
      ;; - `:_meta`: MCP metadata. csk would also collapse the key
      ;;   `:_meta` to `:meta`, so we re-key explicitly.
      (and (= "hooks.invoke" method)
           (map? params)
           (= "preMcpToolCall" (:hookType params))
           (map? (:input params)))
      (let [raw-input (:input params)]
        (cond-> converted
          (contains? raw-input :arguments)
          (assoc-in [:params :input :arguments] (:arguments raw-input))

          (contains? raw-input :_meta)
          (-> (update-in [:params :input] dissoc :meta)
              (assoc-in [:params :input :_meta] (:_meta raw-input)))))

      ;; v3: preserve raw arguments / subject / payload in broadcast events
      (and (= "session.event" method)
           (map? (:event params)))
      (assoc-in converted [:params :event]
                (preserve-event-opaque-fields (:event params)
                                              (get-in converted [:params :event])))

      ;; Response carrying an event collection (e.g. session.getMessages).
      ;; Preserve opaque fields per-event so historical custom_notification
      ;; events keep their subject/payload keys, and historical
      ;; external_tool.requested events keep their arguments. Without this,
      ;; live and historical events would have divergent key shapes.
      (and (:id msg) (not method) (sequential? raw-events))
      (assoc-in converted [:result :events]
                (mapv (fn [raw conv]
                        (preserve-event-opaque-fields raw conv))
                      raw-events
                      (get-in converted [:result :events])))

      ;; Upstream PR #1604: `session.resume` responses include `openCanvases[]`
      ;; — each canvas may carry an opaque caller-supplied `:input` map.
      ;; Preserve raw input keys so they aren't kebab-cased when surfaced via
      ;; `(open-canvases session)`. Raw and converted vectors are positionally
      ;; aligned (conversion preserves order).
      (and (:id msg) (not method)
           (sequential? (get-in msg [:result :openCanvases])))
      (assoc-in converted [:result :open-canvases]
                (mapv (fn [raw conv]
                        (if (and (map? raw) (contains? raw :input))
                          (assoc conv :input (:input raw))
                          conv))
                      (get-in msg [:result :openCanvases])
                      (get-in converted [:result :open-canvases])))

      ;; Upstream PR #1835: GitHub telemetry forwarding notifications carry
      ;; three OPAQUE source-defined sub-maps whose keys must survive verbatim
      ;; (NOT kebab-cased): `:properties` (string->string), `:metrics`
      ;; (string->number), `:features` (string->string). The remaining event
      ;; scalars and the optional `:client` sub-map ARE snake->kebab-cased.
      ;; Notification-only path — telemetry events are never replayed via
      ;; `session.getMessages`, so no response-path hatch is needed.
      (and (= "gitHubTelemetry.event" method) (map? (:event params)))
      (let [raw-event (:event params)]
        (cond-> converted
          (map? (:properties raw-event))
          (assoc-in [:params :event :properties] (:properties raw-event))

          (map? (:metrics raw-event))
          (assoc-in [:params :event :metrics] (:metrics raw-event))

          (map? (:features raw-event))
          (assoc-in [:params :event :features] (:features raw-event))))

      :else
      converted)))

(defn- dispatch-message!
  "Route incoming message to appropriate handler."
  [conn msg]
  (let [{:keys [state-atom]} conn]
    ;; Responses need the originating request method to restore opaque factory
    ;; result values, so defer normalization until handle-response! claims the
    ;; pending entry.
    (if (and (:id msg) (not (:method msg)))
      (handle-response! state-atom msg)
      (let [normalized (normalize-incoming msg)]
        (cond
          ;; Request (has id and method) - hand off to the bounded worker pool
          (and (:id normalized) (:method normalized))
          (handle-request! conn normalized)

          ;; Notification (has method, no id) - put to incoming-ch for routing
          (:method normalized)
          (do
            (log/debug "Received notification: method=" (:method normalized))
            (when-not (.offer ^LinkedBlockingQueue (:notification-queue conn) normalized)
              (let [total (.incrementAndGet ^AtomicLong (:dropped-notifications conn))]
                (log/warn "Dropping notification due to full queue: method="
                          (:method normalized) " dropped-total=" total))))

          :else nil)))))

;; -----------------------------------------------------------------------------
;; Reader and Writer Loops
;; -----------------------------------------------------------------------------

(defn- start-read-loop!
  "Start background thread that reads messages from NIO channel.
   Exits cleanly when channel is closed (AsynchronousCloseException)."
  [conn]
  (let [{:keys [read-channel state-atom]} conn
        single-byte-buf (ByteBuffer/allocate 1)]
    (Thread.
     (fn []
       (log/debug "Read loop started")
       (try
         (loop []
           (when (:running? (conn-state state-atom))
             (if-let [msg (read-message read-channel single-byte-buf)]
               (do
                 (dispatch-message! conn msg)
                 (recur))
               (do
                 (log/debug "Read loop: EOF from remote")
                 (update-conn! state-atom assoc :running? false)
                 (drain-pending! state-atom {:code -32000
                                             :message "Connection closed by remote"})))))
         (catch AsynchronousCloseException _
           (log/debug "Read loop: channel closed asynchronously"))
         (catch ClosedChannelException _
           (log/debug "Read loop: channel already closed"))
         (catch IOException e
           ;; "Pipe closed" is normal during shutdown when the other end
           ;; closes. Either way, if we were still running this is an
           ;; unexpected remote close: stop the loop and resolve pending
           ;; requests so callers don't hang. (During a local disconnect,
           ;; :running? is already false and pending already drained, so the
           ;; drain below is a harmless no-op.)
           (let [pipe-closed? (= "Pipe closed" (ex-message e))]
             (when (:running? (conn-state state-atom))
               (if pipe-closed?
                 (log/debug "Read loop: pipe closed by remote")
                 (log/error "Read loop IO exception: " (ex-message e)))
               (update-conn! state-atom assoc :running? false)
               (drain-pending! state-atom {:code -32000
                                           :message (if pipe-closed?
                                                      "Connection closed by remote"
                                                      (str "Connection error: " (ex-message e)))}))))
         (catch Exception e
           (when (:running? (conn-state state-atom))
             (log/error "Read loop exception: " (ex-message e))
             (update-conn! state-atom assoc :running? false)
             (drain-pending! state-atom
                             {:code -32000
                              :message (str "Connection error: "
                                            (ex-message e))})))
         (finally
           (log/debug "Read loop ending")
           (close! (:incoming-ch conn))))))))

(defn- start-write-loop!
  "Start go-loop that writes messages from outgoing-ch to NIO channel.
   Uses a dedicated thread for actual writes to avoid locking issues in go blocks."
  [conn]
  (let [{:keys [write-channel output-stream outgoing-ch state-atom]} conn
        write-queue (java.util.concurrent.LinkedBlockingQueue.)
        writer-thread (Thread.
                       (fn []
                         (try
                           (while (:running? (conn-state state-atom))
                             (when-let [msg (.poll write-queue 100 java.util.concurrent.TimeUnit/MILLISECONDS)]
                               (when (and (:running? (conn-state state-atom)) (.isOpen write-channel))
                                 (try
                                   (log/debug "Writing message: " (if (:id msg) (str "id=" (:id msg)) "notification"))
                                   (write-message! write-channel msg)
                                   (.flush output-stream)
                                   (log/debug "Message written and flushed")
                                   (catch java.nio.channels.ClosedChannelException _
                                     (log/debug "Write channel closed"))
                                   (catch java.io.IOException _
                                     (log/debug "Write stream closed"))
                                   (catch Exception e
                                     (when (:running? (conn-state state-atom))
                                       (log/error "Write error: " (ex-message e))))))))
                           (catch InterruptedException _
                             (log/debug "Writer thread interrupted")))))]
    (.setDaemon writer-thread true)
    (.setName writer-thread "jsonrpc-nio-writer")
    (.start writer-thread)
    ;; Store thread reference for cleanup
    (update-conn! state-atom assoc :writer-thread writer-thread)
    ;; Go-loop to transfer from core.async channel to blocking queue
    (go-loop []
      (when-let [msg (<! outgoing-ch)]
        (when (:running? (conn-state state-atom))
          (.put write-queue msg))
        (recur)))))

;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(defn initial-connection-state
  "Return initial connection state to be stored in client's atom under :connection key."
  []
  {:running? true
   :pending-requests {}
   :request-handler nil
   :writer-thread nil})

(defn connect
  "Create a JSON-RPC connection from input/output streams.
   Uses NIO channels for interruptible I/O.
   
   state-atom: atom containing :connection key with connection state
   
   Returns a Connection record."
  [^InputStream in ^OutputStream out state-atom]
  (log/debug "Creating JSON-RPC connection with NIO channels")
  (let [read-ch (Channels/newChannel in)
        write-ch (Channels/newChannel out)
        incoming-ch (chan 1024)
        outgoing-ch (chan 1024)
        options (get @state-atom :options)
        queue-size (or (:notification-queue-size options) 4096)
        notification-queue (LinkedBlockingQueue. queue-size)
        conn (map->Connection
              {:read-channel read-ch
               :write-channel write-ch
               :output-stream out  ; Keep for flushing
               :state-atom state-atom
               :incoming-ch incoming-ch
               :outgoing-ch outgoing-ch
               :notification-queue notification-queue
               :notification-thread nil
               :read-thread nil
               :request-executor (make-request-executor
                                  (or (:request-handler-threads options)
                                      default-request-handler-threads)
                                  (or (:request-handler-queue-size options)
                                      default-request-handler-queue-size))
               :rejected-requests (AtomicLong.)
               :dropped-notifications (AtomicLong.)})]

    ;; Start writer loop
    (start-write-loop! conn)

    ;; Start notification dispatcher thread
    (let [thread (Thread.
                  (fn []
                    (log/debug "Notification dispatcher started")
                    (try
                      (loop []
                        (when (:running? (conn-state state-atom))
                          (when-let [msg (.poll notification-queue 100 TimeUnit/MILLISECONDS)]
                            (>!! incoming-ch msg))
                          (recur)))
                      (catch InterruptedException _
                        (log/debug "Notification dispatcher interrupted"))
                      (catch Exception e
                        (log/error "Notification dispatcher exception: " (ex-message e)))
                      (finally
                        (log/debug "Notification dispatcher ending")))))]
      (.setDaemon thread true)
      (.setName thread "jsonrpc-notification-dispatcher")
      (.start thread)
      (update-conn! state-atom assoc :notification-thread thread))

    ;; Start reader thread
    (let [thread (start-read-loop! conn)]
      (.setDaemon thread true)
      (.setName thread "jsonrpc-nio-reader")
      (.start thread)
      (log/debug "JSON-RPC connection established")
      (assoc conn :read-thread thread))))

(defn- shutdown-request-executor!
  "Stop the reverse-request worker pool.

   Blocked handlers are interrupted so a wedged handler cannot delay disconnect
   indefinitely. A pool that still has not terminated is reported rather than
   silently ignored -- a handler that swallows interruption is real diagnostic
   information. Idempotent.

   An interrupted wait is re-flagged on the calling thread rather than
   swallowed, but is never propagated: `disconnect` must always reach the
   channel closes and thread joins that follow it. A later teardown join may
   legitimately consume the flag."
  [^ThreadPoolExecutor executor]
  (when executor
    (.shutdownNow executor)
    (let [terminated? (try
                        (.awaitTermination executor 1000 TimeUnit/MILLISECONDS)
                        (catch InterruptedException _
                          (.interrupt (Thread/currentThread))
                          false))]
      (when-not terminated?
        (log/warn "Reverse request handler pool did not terminate within 1000ms; "
                  (.getActiveCount executor) " handler(s) still running")))))

(defn disconnect
  "Close the connection gracefully.
   Closes NIO channels which causes reader thread to exit via AsynchronousCloseException.

   Returns a vector of `ex-info` values describing *unexpected* teardown
   failures; expected close/interruption outcomes are not reported. Every step
   runs even when an earlier one fails, and the whole sequence is idempotent."
  [conn]
  (log/debug "Disconnecting JSON-RPC connection")
  (let [state-atom (:state-atom conn)]
    ;; Signal loops to stop
    (update-conn! state-atom assoc :running? false)

    ;; Resolve any in-flight requests so callers blocked on their response
    ;; channels unblock immediately instead of hanging forever. Done after
    ;; clearing :running? so a concurrent send-request fails fast rather than
    ;; registering a new entry we'd miss.
    (drain-pending! state-atom {:code -32000 :message "Connection closed"})

    ;; Interrupt running handlers and abandon queued reverse requests before
    ;; closing outgoing-ch. A request rejected during this shutdown window gets
    ;; a best-effort connection-closed response.
    (shutdown-request-executor! (:request-executor conn))

    ;; Close outgoing channel first to stop write go-loop
    (close! (:outgoing-ch conn))

    (let [failures
          (td/collect
           [;; Interrupt writer thread if it exists
            (when-let [^Thread writer (:writer-thread (conn-state state-atom))]
              (td/attempt {:operation :join :resource :writer-thread}
                          (.interrupt writer)
                          (.join writer 500)))

            ;; Interrupt notification dispatcher thread
            (when-let [^Thread thread (:notification-thread (conn-state state-atom))]
              (td/attempt {:operation :join :resource :notification-thread}
                          (.interrupt thread)
                          (.join thread 500)))

            ;; Close NIO channels - this unblocks any blocked reads
            (td/attempt {:operation :close :resource :read-channel}
                        (.close ^ReadableByteChannel (:read-channel conn)))
            (td/attempt {:operation :close :resource :write-channel}
                        (.close ^WritableByteChannel (:write-channel conn)))

            ;; Wait for read thread to exit
            (when-let [^Thread thread (:read-thread conn)]
              (td/attempt {:operation :join :resource :read-thread}
                          (.interrupt thread)
                          (.join thread 1000)))])]
      (log/debug "JSON-RPC connection closed")
      failures)))

(defn- remove-pending-by-chan!
  "Remove a pending request entry by channel identity."
  [state-atom target-ch]
  (update-conn! state-atom update :pending-requests
                (fn [pending]
                  (reduce-kv (fn [m id {:keys [ch] :as entry}]
                               (if (identical? ch target-ch)
                                 m
                                 (assoc m id entry)))
                             {}
                             pending))))

(defn- preserve-outgoing-request-opaque-fields
  [method raw-params wire-params]
  (case method
    "session.factory.run"
    (if (contains? raw-params :args)
      (assoc wire-params :args (:args raw-params))
      wire-params)

    "session.factory.agent"
    (if (contains? (:opts raw-params) :schema)
      (assoc-in wire-params [:opts :schema] (get-in raw-params [:opts :schema]))
      wire-params)

    "session.factory.journal.put"
    (if (contains? raw-params :result-json)
      (assoc wire-params :resultJson (:result-json raw-params))
      wire-params)

    wire-params))

(defn send-request
  "Send a JSON-RPC request and return a channel for the response.
   The channel delivers a single {:result ...} or {:error ...} map, then closes.

   Optional `opts` map:
   - `:on-response-inline` — 1-arg fn `(fn [result])` invoked synchronously
     in the read thread, **before** the result is delivered to the response
     channel, on success only. Use this when you need to mutate shared
     state (e.g. register a session under a server-assigned id) before
     any later inbound message can be dispatched. See upstream PR #1479."
  ([conn method params]
   (send-request conn method params {}))
  ([conn method params {:keys [on-response-inline] :as _opts}]
   (let [state-atom (:state-atom conn)
         id (str (java.util.UUID/randomUUID))
         ch (chan 1)
         wire-params (when params
                       (preserve-outgoing-request-opaque-fields
                        method params (util/clj->wire params)))
         msg {:jsonrpc "2.0"
              :id id
              :method method
              :params wire-params}
         entry (cond-> {:ch ch :method method}
                 on-response-inline (assoc :on-response-inline on-response-inline))]
     (log/debug "Sending request: method=" method " id=" id)
     ;; Register the pending entry only if the connection is still running, in a
     ;; single atomic step so a concurrent disconnect either sees the entry (and
     ;; drains it) or refuses registration. Without this, a request registered
     ;; after disconnect would never be resolved and the caller would hang.
     (let [[old new] (swap-vals! state-atom
                                 (fn [s]
                                   (if (get-in s [:connection :running?])
                                     (assoc-in s [:connection :pending-requests id] entry)
                                     s)))
           registered? (not (identical? old new))]
       (if registered?
         (put! (:outgoing-ch conn) msg
               (fn [enqueued?]
                 ;; If the outgoing channel was already closed the message was
                 ;; dropped, so resolve the pending entry with an error rather
                 ;; than leaving the caller blocked.
                 (when-not enqueued?
                   (remove-pending-by-chan! state-atom ch)
                   (put! ch {:error {:code -32000 :message "Connection closed"}})
                   (close! ch))))
         (do
           (put! ch {:error {:code -32000 :message "Connection closed"}})
           (close! ch)))
       ch))))

(defn send-request-with-timeout
  "Send a JSON-RPC request and return a channel for its bounded response.
   A timeout delivers a JSON-RPC-shaped error and removes the pending request.

   The 5-arity form accepts the same opts as `send-request`."
  ([conn method params timeout-ms]
   (send-request-with-timeout conn method params timeout-ms {}))
  ([conn method params timeout-ms opts]
   (let [state-atom (:state-atom conn)
         response-ch (send-request conn method params opts)
         result-ch (chan 1)
         timeout-ch (async/timeout timeout-ms)]
     (async/go
       (let [[result port] (async/alts! [response-ch timeout-ch])]
         (if (= port timeout-ch)
           (do
             (remove-pending-by-chan! state-atom response-ch)
             (close! response-ch)
             (async/>! result-ch
                       {:error
                        {:code -32000
                         :message "Request timeout"
                         :data {:method method :timeout-ms timeout-ms}}}))
           (when (some? result)
             (async/>! result-ch result)))
         (close! result-ch)))
     result-ch)))

(defn send-request!
  "Send a JSON-RPC request and block for the response.
   Returns result or throws on error.

   The 5-arity form accepts an `opts` map forwarded to `send-request`
   (see its docstring for supported keys, e.g. `:on-response-inline`).
   The 3- and 4-arity forms apply the default empty opts."
  ([conn method params]
   (send-request! conn method params 60000 {}))
  ([conn method params timeout-ms]
   (send-request! conn method params timeout-ms {}))
  ([conn method params timeout-ms opts]
   (let [state-atom (:state-atom conn)
         response-ch (send-request conn method params opts)
         timeout-ch (when timeout-ms (async/timeout timeout-ms))
         [result port] (async/alts!! (cond-> [response-ch]
                                       timeout-ch (conj timeout-ch)))]
     (cond
       (and timeout-ch (= port timeout-ch))
       (do
         (remove-pending-by-chan! state-atom response-ch)
         (close! response-ch)
         (throw (ex-info "Request timeout" {:method method :timeout-ms timeout-ms})))

       (nil? result)
       (throw (ex-info "Response channel closed" {:method method}))

       (:error result)
       (throw (ex-info (get-in result [:error :message] "RPC error")
                       {:error (:error result) :method method}))

       :else
       (:result result)))))

(defn send-notification
  "Send a JSON-RPC notification (no response expected)."
  [conn method params]
  (log/debug "Sending notification: method=" method)
  (let [wire-params (when params (util/clj->wire params))
        msg {:jsonrpc "2.0"
             :method method
             :params wire-params}]
    (put! (:outgoing-ch conn) msg)))

(defn set-request-handler!
  "Set handler for incoming requests. 
   Handler is (fn [method params] -> channel with {:result ...} or {:error ...})"
  [conn handler]
  (update-conn! (:state-atom conn) assoc :request-handler handler))

(defn notifications
  "Returns the channel that receives incoming notifications."
  [conn]
  (:incoming-ch conn))
