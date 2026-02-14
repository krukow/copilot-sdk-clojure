(ns github.copilot-sdk.mock-server
  "Mock JSON-RPC server for integration testing.
   Simulates the Copilot CLI server behavior."
  (:require [clojure.data.json :as json]
            [clojure.core.async :as async :refer [go go-loop <! >! chan close! put!]])
  (:import [java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter
            PipedInputStream PipedOutputStream]
           [java.util.concurrent.atomic AtomicLong]))

(def ^:private PROTOCOL_VERSION 2)

(defn- write-message
  "Write a JSON-RPC message with Content-Length framing."
  [^BufferedWriter writer msg]
  (let [json-str (json/write-str msg)
        content-length (count (.getBytes json-str "UTF-8"))]
    (locking writer
      (.write writer (str "Content-Length: " content-length "\r\n\r\n"))
      (.write writer json-str)
      (.flush writer))))

(defn- read-headers
  "Read headers until empty line. Returns nil on EOF/closed."
  [^BufferedReader reader]
  (try
    (loop [headers {}]
      (let [line (.readLine reader)]
        (cond
          (nil? line) nil
          (clojure.string/blank? line) headers
          :else
          (let [[k v] (clojure.string/split line #": " 2)]
            (recur (assoc headers (clojure.string/lower-case k) v))))))
    (catch java.io.IOException _ nil)))

(defn- read-content
  "Read exactly n bytes."
  [^BufferedReader reader n]
  (let [buf (char-array n)
        read-count (.read reader buf 0 n)]
    (when (= read-count n)
      (String. buf))))

(defn- read-message
  "Read a JSON-RPC message."
  [^BufferedReader reader]
  (when-let [headers (read-headers reader)]
    (when-let [content-length (some-> (get headers "content-length") parse-long)]
      (when-let [content (read-content reader content-length)]
        (json/read-str content :key-fn keyword)))))

(defrecord MockServer
           [;; Pipes for communication
            client-in          ; PipedInputStream - client reads from this
            client-out         ; PipedOutputStream - client writes to this  
            server-in          ; PipedInputStream - server reads from this (connected to client-out)
            server-out         ; PipedOutputStream - server writes to this (connected to client-in)
     ;; Server I/O
            reader             ; BufferedReader for server
            writer             ; BufferedWriter for server
     ;; State
            running?           ; atom boolean
            sessions           ; atom {session-id -> session-state}
            message-id         ; AtomicLong for generating IDs
     ;; Hooks for testing
            on-request         ; atom fn - called for each request
            pending-events])   ; atom - events to send on next opportunity

(defn- generate-id [^AtomicLong counter]
  (str "evt-" (.incrementAndGet counter)))

(defn- make-event [server type data & {:keys [ephemeral?]}]
  {:id (generate-id (:message-id server))
   :timestamp (.toString (java.time.Instant/now))
   :parentId nil
   :ephemeral (boolean ephemeral?)
   :type type
   :data data})

(defn- send-notification [server method params]
  (write-message (:writer server)
                 {:jsonrpc "2.0"
                  :method method
                  :params params}))

(defn- send-session-event [server session-id event]
  (send-notification server "session.event"
                     {:sessionId session-id
                      :event event}))

(defn- handle-ping [server params]
  {:message (:message params)
   :timestamp (System/currentTimeMillis)
   :protocolVersion PROTOCOL_VERSION})

(defn- handle-status-get [server params]
  {:version "0.0.389-mock"
   :protocolVersion PROTOCOL_VERSION})

(defn- handle-auth-get-status [server params]
  {:isAuthenticated true
   :authType "user"
   :host "github.com"
   :login "test-user"
   :statusMessage "Authenticated as test-user"})

(defn- handle-models-list [server params]
  {:models [{:id "gpt-5.2"
             :name "GPT-5.2"
             :vendor "openai"
             :family "gpt-5.2"
             :version "gpt-5.2"
             :max_input_tokens 128000
             :max_output_tokens 16384
             :preview false}
            {:id "claude-sonnet-4.5"
             :name "Claude Sonnet 4.5"
             :vendor "anthropic"
             :family "claude-sonnet"
             :version "claude-sonnet-4.5"
             :max_input_tokens 200000
             :max_output_tokens 8192
             :preview false
             :vision_limits {:supported_media_types ["image/png" "image/jpeg" "image/gif" "image/webp"]
                             :max_prompt_images 20
                             :max_prompt_image_size 20971520}}]})

(defn- handle-session-create [server params]
  (let [session-id (or (:sessionId params) (str "session-" (generate-id (:message-id server))))
        session-state {:id session-id
                       :model (:model params)
                       :created-at (java.time.Instant/now)}]
    (swap! (:sessions server) assoc session-id session-state)
    ;; Send session.start event
    (send-session-event server session-id
                        (make-event server "session.start"
                                    {:sessionId session-id
                                     :version 1
                                     :producer "mock-server"
                                     :copilotVersion "mock-1.0.0"
                                     :startTime (.toString (java.time.Instant/now))
                                     :selectedModel (:model params)}))
    {:sessionId session-id}))

(defn- handle-session-resume [server params]
  (let [session-id (:sessionId params)]
    (if (get @(:sessions server) session-id)
      (do
        (send-session-event server session-id
                            (make-event server "session.resume"
                                        {:resumeTime (.toString (java.time.Instant/now))
                                         :eventCount 0}))
        {:sessionId session-id})
      (throw (ex-info "Session not found" {:code -32001 :session-id session-id})))))

(defn- handle-session-send [server params]
  (let [session-id (:sessionId params)
        prompt (:prompt params)
        message-id (generate-id (:message-id server))]
    (if-not (get @(:sessions server) session-id)
      (throw (ex-info "Session not found" {:code -32001 :session-id session-id}))
      (do
        ;; Send user.message event
        (send-session-event server session-id
                            (make-event server "user.message"
                                        {:content prompt}))
        ;; Send assistant.turn_start
        (let [turn-id (generate-id (:message-id server))]
          (send-session-event server session-id
                              (make-event server "assistant.turn_start"
                                          {:turnId turn-id}))
          ;; Simulate assistant response
          (send-session-event server session-id
                              (make-event server "assistant.message"
                                          {:messageId message-id
                                           :content (str "Mock response to: " prompt)}))
          ;; Send turn end
          (send-session-event server session-id
                              (make-event server "assistant.turn_end"
                                          {:turnId turn-id}))
          ;; Send idle
          (send-session-event server session-id
                              (make-event server "session.idle" {} :ephemeral? true)))
        {:messageId message-id}))))

(defn- handle-session-destroy [server params]
  (let [session-id (:sessionId params)]
    (swap! (:sessions server) dissoc session-id)
    {:success true}))

(defn- handle-session-abort [server params]
  (let [session-id (:sessionId params)]
    {:success true}))

(defn- handle-session-get-messages [server params]
  {:events []})

(defn- handle-session-list [server params]
  (let [filter-opts (:filter params)
        sessions (mapv (fn [[id state]]
                         (cond-> {:sessionId id
                                  :startTime (.toString (:created-at state))
                                  :modifiedTime (.toString (java.time.Instant/now))
                                  :isRemote false}
                           (:context state) (assoc :context (:context state))))
                       @(:sessions server))
        filtered (if filter-opts
                   (filterv (fn [s]
                              (let [ctx (:context s)]
                                (and (or (nil? (:cwd filter-opts)) (= (:cwd filter-opts) (:cwd ctx)))
                                     (or (nil? (:gitRoot filter-opts)) (= (:gitRoot filter-opts) (:gitRoot ctx)))
                                     (or (nil? (:repository filter-opts)) (= (:repository filter-opts) (:repository ctx)))
                                     (or (nil? (:branch filter-opts)) (= (:branch filter-opts) (:branch ctx))))))
                            sessions)
                   sessions)]
    {:sessions filtered}))

(defn- handle-session-delete [server params]
  (let [session-id (:sessionId params)]
    (swap! (:sessions server) dissoc session-id)
    {:success true}))

(defn- handle-session-get-last-id [server params]
  (let [sessions @(:sessions server)]
    (if (empty? sessions)
      {:sessionId nil}
      {:sessionId (first (keys sessions))})))

(defn- handle-tools-list [server params]
  {:tools [{:name "bash"
            :description "Run a shell command"
            :parameters {:type "object"
                         :properties {:command {:type "string"}}
                         :required ["command"]}}
           {:name "grep"
            :namespacedName "builtin/grep"
            :description "Search files for patterns"
            :parameters {:type "object"
                         :properties {:pattern {:type "string"}}
                         :required ["pattern"]}
            :instructions "Use for searching file contents"}]})

(defn- handle-account-get-quota [server params]
  {:quotaSnapshots {:chat {:entitlementRequests 1000
                           :usedRequests 42
                           :remainingPercentage 95.8
                           :overage 0
                           :overageAllowedWithExhaustedQuota false
                           :resetDate "2026-03-01T00:00:00Z"}
                    :premium_interactions {:entitlementRequests 500
                                           :usedRequests 10
                                           :remainingPercentage 98.0
                                           :overage 0
                                           :overageAllowedWithExhaustedQuota true}}})

(defn- handle-session-model-get-current [server params]
  (let [session-id (:sessionId params)
        session-state (get @(:sessions server) session-id)]
    (if session-state
      {:modelId (:model session-state)}
      (throw (ex-info "Session not found" {:code -32001 :session-id session-id})))))

(defn- handle-session-model-switch-to [server params]
  (let [session-id (:sessionId params)
        model-id (:modelId params)]
    (if (get @(:sessions server) session-id)
      (do
        (swap! (:sessions server) assoc-in [session-id :model] model-id)
        {:modelId model-id})
      (throw (ex-info "Session not found" {:code -32001 :session-id session-id})))))

(defn- handle-request [server msg]
  (let [method (:method msg)
        params (:params msg)
        ;; Call hook if set
        _ (when-let [hook @(:on-request server)]
            (hook method params))
        result (case method
                 "ping" (handle-ping server params)
                 "status.get" (handle-status-get server params)
                 "auth.getStatus" (handle-auth-get-status server params)
                 "models.list" (handle-models-list server params)
                 "session.create" (handle-session-create server params)
                 "session.resume" (handle-session-resume server params)
                 "session.send" (handle-session-send server params)
                 "session.destroy" (handle-session-destroy server params)
                 "session.abort" (handle-session-abort server params)
                 "session.getMessages" (handle-session-get-messages server params)
                 "session.list" (handle-session-list server params)
                 "session.delete" (handle-session-delete server params)
                 "session.getLastId" (handle-session-get-last-id server params)
                 "tools.list" (handle-tools-list server params)
                 "account.getQuota" (handle-account-get-quota server params)
                 "session.model.getCurrent" (handle-session-model-get-current server params)
                 "session.model.switchTo" (handle-session-model-switch-to server params)
                 (throw (ex-info "Method not found" {:code -32601 :method method})))]
    {:jsonrpc "2.0"
     :id (:id msg)
     :result result}))

(defn- server-loop [server]
  (try
    (while @(:running? server)
      (if-let [msg (read-message (:reader server))]
        (try
          (let [response (handle-request server msg)]
            (write-message (:writer server) response))
          (catch Exception e
            (let [error-data (ex-data e)]
              (write-message (:writer server)
                             {:jsonrpc "2.0"
                              :id (:id msg)
                              :error {:code (or (:code error-data) -32603)
                                      :message (.getMessage e)}}))))
        ;; EOF or closed - exit loop
        (reset! (:running? server) false)))
    (catch Exception e
      (when @(:running? server)
        (println "Mock server error:" (.getMessage e))))))

(defn create-mock-server
  "Create a mock server with piped streams for testing.
   Returns a MockServer record."
  []
  (let [;; Create two pairs of piped streams
        ;; Pair 1: server writes -> client reads
        client-in (PipedInputStream. 65536)
        server-out (PipedOutputStream. client-in)
        ;; Pair 2: client writes -> server reads
        server-in (PipedInputStream. 65536)
        client-out (PipedOutputStream. server-in)]
    (map->MockServer
     {:client-in client-in
      :client-out client-out
      :server-in server-in
      :server-out server-out
      :reader (BufferedReader. (InputStreamReader. server-in "UTF-8"))
      :writer (BufferedWriter. (OutputStreamWriter. server-out "UTF-8"))
      :running? (atom false)
      :sessions (atom {})
      :message-id (AtomicLong. 0)
      :on-request (atom nil)
      :pending-events (atom [])})))

(defn start-mock-server!
  "Start the mock server in a background thread."
  [server]
  (reset! (:running? server) true)
  (let [thread (Thread. #(server-loop server) "mock-server")]
    (.setDaemon thread true)
    (.start thread)
    server))

(defn stop-mock-server!
  "Stop the mock server."
  [server]
  (reset! (:running? server) false)
  ;; Close pipes in order that unblocks the reader first
  (try (.close ^PipedOutputStream (:client-out server)) (catch Exception _))
  (try (.close ^PipedOutputStream (:server-out server)) (catch Exception _))
  (try (.close ^PipedInputStream (:client-in server)) (catch Exception _))
  (try (.close ^PipedInputStream (:server-in server)) (catch Exception _))
  server)

(defn client-streams
  "Get the streams that a client should use to connect to this mock server.
   Returns [input-stream output-stream]."
  [server]
  [(:client-in server) (:client-out server)])

(defn set-request-hook!
  "Set a hook function that's called for each request.
   Hook receives (method params)."
  [server hook-fn]
  (reset! (:on-request server) hook-fn))

(defn inject-tool-call!
  "Inject a tool call request from the mock server.
   This simulates the CLI requesting a tool invocation."
  [server session-id tool-name arguments]
  (let [tool-call-id (generate-id (:message-id server))]
    ;; Send tool execution start event
    (send-session-event server session-id
                        (make-event server "tool.execution_start"
                                    {:toolCallId tool-call-id
                                     :toolName tool-name
                                     :arguments arguments}))
    ;; Send the actual tool call request
    (write-message (:writer server)
                   {:jsonrpc "2.0"
                    :id (generate-id (:message-id server))
                    :method "tool.call"
                    :params {:sessionId session-id
                             :toolCallId tool-call-id
                             :toolName tool-name
                             :arguments arguments}})
    tool-call-id))

(defn send-notification!
  "Send a generic notification to the client."
  [server method params]
  (write-message (:writer server)
                 {:jsonrpc "2.0"
                  :method method
                  :params params}))

(defn send-session-event!
  "Send a session event to the client.
   Event should be a map with :type and :data keys."
  [server session-id event-type event-data & {:keys [ephemeral?]}]
  (send-session-event server session-id
                      (make-event server (name event-type) event-data :ephemeral? ephemeral?)))

(defn set-session-context!
  "Set context on a mock session (for testing list-sessions with context)."
  [server session-id context]
  (swap! (:sessions server) assoc-in [session-id :context] context))
