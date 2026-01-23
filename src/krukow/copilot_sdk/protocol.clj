(ns krukow.copilot-sdk.protocol
  "JSON-RPC 2.0 protocol implementation using java.nio channels.
   
   Architecture:
   - NIO channels for interruptible I/O (clean shutdown)
   - core.async channels for message flow
   - Single reader thread puts to incoming-ch
   - Writer go-loop takes from outgoing-ch
   - State is managed externally (passed in as atom)
   
   This design allows clean shutdown: closing NIO channels causes
   reader to throw AsynchronousCloseException and exit gracefully."
  (:require [cheshire.core :as json]
            [clojure.core.async :as async :refer [go go-loop <! >! >!! <!! chan close! put!]]
            [clojure.string :as str]
            [krukow.copilot-sdk.logging :as log]
            [krukow.copilot-sdk.util :as util])
  (:import [java.io InputStream OutputStream IOException]
           [java.nio ByteBuffer]
           [java.nio.channels Channels ReadableByteChannel WritableByteChannel ClosedChannelException]
           [java.nio.channels AsynchronousCloseException]
           [java.nio.charset StandardCharsets]
            [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(def ^:private content-length-header "Content-Length: ")

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
        (json/parse-string content true)))))

(defn- write-message!
  "Write a JSON-RPC message to channel with Content-Length framing."
  [^WritableByteChannel channel msg]
  (let [json-str (json/generate-string msg)
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
     read-thread])                 ; Thread

;; State path helpers
(defn- conn-state [state-atom] (get @state-atom :connection))
(defn- update-conn! [state-atom f & args] (apply swap! state-atom update :connection f args))

;; -----------------------------------------------------------------------------
;; Message Handling
;; -----------------------------------------------------------------------------

(defn- handle-response!
  "Handle an incoming response message. Delivers to pending promise."
  [state-atom msg]
  (let [id (:id msg)
        pending-requests (:pending-requests (conn-state state-atom))]
    (log/debug "Received response for id=" id)
    (when-let [{:keys [promise]} (get pending-requests id)]
      (update-conn! state-atom update :pending-requests dissoc id)
      (if-let [error (:error msg)]
        (do
          (log/debug "Response error: " error)
          (deliver promise {:error error}))
        (do
          (log/debug "Response success for id=" id)
        (deliver promise {:result (:result msg)}))))))

(defn- handle-request!
  "Handle an incoming request message (e.g., tool.call). Sends response via outgoing-ch."
  [state-atom outgoing-ch msg]
  (go
    (let [request-handler (:request-handler (conn-state state-atom))
          id (:id msg)
          method (:method msg)
          params (:params msg)]
      (log/debug "Received request: method=" method " id=" id)
      (try
        (let [result (if request-handler
                       (<! (request-handler method params))
                       {:error {:code -32601 :message "Method not found"}})]
          (if (:error result)
            (do
              (log/debug "Request error response: " (:error result))
              (>! outgoing-ch {:jsonrpc "2.0" :id id :error (util/clj->wire (:error result))}))
            (do
              (log/debug "Request success response for id=" id)
              (>! outgoing-ch {:jsonrpc "2.0" :id id :result (util/clj->wire (:result result))}))))
        (catch Exception e
          (log/error "Request handler exception: " (ex-message e))
          (>! outgoing-ch {:jsonrpc "2.0"
                          :id id
                          :error {:code -32603
                                  :message (str "Internal error: " (ex-message e))}}))))))

(defn- normalize-incoming
  "Convert wire-format keys to Clojure keys, preserving tool.call arguments."
  [msg]
  (let [method (:method msg)
        params (:params msg)
        converted (util/wire->clj msg)]
    (if (and (= "tool.call" method) (map? params) (contains? params :arguments))
      (assoc-in converted [:params :arguments] (:arguments params))
      converted)))

(defn- dispatch-message!
  "Route incoming message to appropriate handler."
   [conn msg]
  (let [{:keys [state-atom incoming-ch outgoing-ch]} conn
        normalized (normalize-incoming msg)]
    (cond
      ;; Response (has id, no method) - deliver to pending promise
      (and (:id normalized) (not (:method normalized)))
      (handle-response! state-atom normalized)
      
      ;; Request (has id and method) - handle and respond
      (and (:id normalized) (:method normalized))
      (handle-request! state-atom outgoing-ch normalized)
      
      ;; Notification (has method, no id) - put to incoming-ch for routing
      (:method normalized)
      (do
        (log/debug "Received notification: method=" (:method normalized))
        (when-not (.offer ^LinkedBlockingQueue (:notification-queue conn) normalized)
          (log/debug "Dropping notification due to full queue")))
      
      :else nil)))

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
                 ;; Signal error to pending requests
                 (doseq [[_ {:keys [promise]}] (:pending-requests (conn-state state-atom))]
                   (deliver promise {:error {:code -32000
                                             :message "Connection closed by remote"}}))
                 (update-conn! state-atom assoc :pending-requests {})))))
         (catch AsynchronousCloseException _
           (log/debug "Read loop: channel closed asynchronously"))
         (catch ClosedChannelException _
            (log/debug "Read loop: channel already closed"))
         (catch IOException e
           ;; "Pipe closed" is normal during shutdown when other end closes
           (if (= "Pipe closed" (ex-message e))
             (log/debug "Read loop: pipe closed by remote")
             (when (:running? (conn-state state-atom))
               (log/error "Read loop IO exception: " (ex-message e))
               ;; Signal error to pending requests
               (doseq [[id {:keys [promise]}] (:pending-requests (conn-state state-atom))]
                 (deliver promise {:error {:code -32000
                                           :message (str "Connection error: " (ex-message e))}}))
               (update-conn! state-atom assoc :pending-requests {}))))
         (catch Exception e
           (when (:running? (conn-state state-atom))
             (log/error "Read loop exception: " (ex-message e))))
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
                                    (when (and (:id msg)
                                               (not (:method msg))
                                               (map? (:result msg))
                                               (or (contains? (:result msg) :kind)
                                                   (and (map? (:result (:result msg)))
                                                        (contains? (:result (:result msg)) :kind))))
                                      (log/debug "Sending permission response: " (json/generate-string msg)))
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
        queue-size (or (get-in @state-atom [:options :notification-queue-size]) 4096)
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
               :read-thread nil})]
    
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

(defn disconnect
  "Close the connection gracefully.
   Closes NIO channels which causes reader thread to exit via AsynchronousCloseException."
  [conn]
  (log/debug "Disconnecting JSON-RPC connection")
  (let [state-atom (:state-atom conn)]
    ;; Signal loops to stop
    (update-conn! state-atom assoc :running? false)
    
    ;; Close outgoing channel first to stop write go-loop
    (close! (:outgoing-ch conn))
    
    ;; Interrupt writer thread if it exists
    (when-let [^Thread writer (:writer-thread (conn-state state-atom))]
      (.interrupt writer)
      (try (.join writer 500) (catch Exception _)))
    
    ;; Interrupt notification dispatcher thread
    (when-let [^Thread thread (:notification-thread (conn-state state-atom))]
      (.interrupt thread)
      (try (.join thread 500) (catch Exception _)))

    ;; Close NIO channels - this unblocks any blocked reads
    (try (.close ^ReadableByteChannel (:read-channel conn)) (catch Exception _))
    (try (.close ^WritableByteChannel (:write-channel conn)) (catch Exception _))
    
    ;; Wait for read thread to exit
    (when-let [^Thread thread (:read-thread conn)]
      (try
        (.join thread 1000)
        (catch Exception _)))
    
    (log/debug "JSON-RPC connection closed")))

(defn send-request
  "Send a JSON-RPC request and return a promise for the response."
  [conn method params]
  (let [state-atom (:state-atom conn)
        id (str (java.util.UUID/randomUUID))
        p (promise)
        wire-params (when params (util/clj->wire params))
        msg {:jsonrpc "2.0"
             :id id
             :method method
             :params wire-params}]
    (log/debug "Sending request: method=" method " id=" id)
    (swap! state-atom assoc-in [:connection :pending-requests id] {:promise p :method method})
    (put! (:outgoing-ch conn) msg)
    p))

(defn- remove-pending-by-promise!
  "Remove a pending request entry by promise identity."
  [state-atom p]
  (update-conn! state-atom update :pending-requests
                (fn [pending]
                  (reduce-kv (fn [m id {:keys [promise] :as entry}]
                               (if (identical? promise p)
                                 m
                                 (assoc m id entry)))
                             {}
                             pending))))

(defn send-request!
  "Send a JSON-RPC request and block for the response.
   Returns result or throws on error."
  ([conn method params]
   (send-request! conn method params 60000))
  ([conn method params timeout-ms]
   (let [state-atom (:state-atom conn)
         p (send-request conn method params)
         result (deref p timeout-ms ::timeout)]
     (cond
       (= result ::timeout)
       (do
         (remove-pending-by-promise! state-atom p)
         (throw (ex-info "Request timeout" {:method method :timeout-ms timeout-ms})))
       
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
