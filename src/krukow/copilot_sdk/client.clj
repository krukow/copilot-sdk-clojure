(ns krukow.copilot-sdk.client
  "CopilotClient - manages connection to the Copilot CLI server."
  (:require [clojure.core.async :as async :refer [go go-loop <! >! chan close!]]
            [clojure.spec.alpha :as s]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [krukow.copilot-sdk.protocol :as proto]
            [krukow.copilot-sdk.process :as proc]
            [krukow.copilot-sdk.specs :as specs]
            [krukow.copilot-sdk.session :as session]
            [krukow.copilot-sdk.logging :as log])
  (:import [java.net Socket]))

(def ^:private sdk-protocol-version 1)

(defn- parse-cli-url
  "Parse CLI URL into {:host :port}."
  [url]
  (let [clean (str/replace url #"^https?://" "")]
    (if (re-matches #"\d+" clean)
      ;; Port only
      (let [port (parse-long clean)]
        (when (or (nil? port) (<= port 0) (> port 65535))
          (throw (ex-info "Invalid port in cli-url" {:url url :port port})))
        {:host "localhost" :port port})
      ;; host:port
      (let [[host port-str] (str/split clean #":" 2)]
        (when (or (str/blank? port-str) (not (re-matches #"-?\d+" port-str)))
          (throw (ex-info "Invalid cli-url format" {:url url})))
        (let [port (parse-long port-str)]
          (when (or (nil? port) (<= port 0) (> port 65535))
            (throw (ex-info "Invalid port in cli-url" {:url url :port port})))
          {:host (if (str/blank? host) "localhost" host)
           :port port})))))

(defn- default-options
  "Return default client options."
  []
  {:cli-path "copilot"
   :cli-args []
   :cwd (System/getProperty "user.dir")
   :port 0
   :use-stdio? true
   :log-level :info
   :auto-start? true
   :auto-restart? true
   :env nil})

;; Client is a simple map with a single state atom
;; The state atom contains all mutable state as an immutable map:
;; {:status :disconnected/:connecting/:connected/:error
;;  :connection {:running? :pending-requests :request-handler :writer-thread :next-request-id}
;;  :connection-io nil or protocol/Connection (IO resources only)
;;  :process nil or process/ManagedProcess  
;;  :socket nil or Socket (for TCP mode)
;;  :sessions {session-id -> {:tool-handlers :permission-handler :destroyed?}}
;;  :session-io {session-id -> {:event-chan :event-mult}} (IO resources)
;;  :actual-port nil or int
;;  :router-ch nil or channel
;;  :force-stopping? false}

(defn- initial-state
  "Create initial client state."
  [port]
  {:status :disconnected
   :connection nil           ; protocol state (when connected)
   :connection-io nil        ; protocol/Connection record (IO resources)
   :process nil
   :socket nil
   :sessions {}              ; session state by session-id
   :session-io {}            ; session IO resources by session-id
   :actual-port port
   :router-ch nil
   :force-stopping? false})

(defn client
  "Create a new CopilotClient.
   
   Options:
   - :cli-path      - Path to CLI executable (default: \"copilot\")
   - :cli-args      - Extra arguments for CLI
   - :cli-url       - URL of existing server (e.g., \"localhost:8080\")
   - :cwd           - Working directory for CLI process
   - :port          - TCP port (default: 0 for random)
   - :use-stdio?    - Use stdio transport (default: true)
   - :log-level     - :none :error :warning :info :debug :all
   - :auto-start?   - Auto-start on first use (default: true)
   - :auto-restart? - Auto-restart on crash (default: true)
   - :env           - Environment variables map"
  ([]
   (client {}))
  ([opts]
   (when (and (:cli-url opts) (= true (:use-stdio? opts)))
     (throw (ex-info "cli-url is mutually exclusive with use-stdio?" opts)))
   (when (and (:cli-url opts) (:cli-path opts))
     (throw (ex-info "cli-url is mutually exclusive with cli-path" opts)))

   (let [merged (merge (default-options) opts)
         external? (boolean (:cli-url opts))
         {:keys [host port]} (when (:cli-url opts)
                               (parse-cli-url (:cli-url opts)))
         final-opts (cond-> merged
                      external? (-> (assoc :use-stdio? false)
                                    (assoc :host host)
                                    (assoc :port port)
                                    (assoc :external-server? true)))]
     {:options final-opts
      :external-server? external?
      :actual-host (or host "localhost")
      :state (atom (initial-state port))})))

(defn state
  "Get the current connection state."
  [client]
  (:status @(:state client)))

(declare stop!)

(defn- start-notification-router!
  "Route notifications to appropriate sessions."
  [client]
  (let [{:keys [connection-io]} @(:state client)
        notif-ch (proto/notifications connection-io)
        router-ch (chan 1024)]
    ;; Store the router channel
    (swap! (:state client) assoc :router-ch router-ch)
    
    ;; Simple routing - read from notification-chan and dispatch
    (go-loop []
      (when-let [notif (<! notif-ch)]
        (when (= (:method notif) "session.event")
          (let [{:keys [sessionId event]} (:params notif)]
            (log/debug "Routing event to session " sessionId ": type=" (:type event))
            (when-not (:destroyed? (get-in @(:state client) [:sessions sessionId]))
              (when-let [{:keys [event-chan]} (get-in @(:state client) [:session-io sessionId])]
                (>! event-chan event)))))
        (recur)))))

(defn- setup-request-handler!
  "Set up handler for incoming requests (tool calls, permission requests)."
  [client]
  (let [{:keys [connection-io]} @(:state client)]
    (proto/set-request-handler! connection-io
      (fn [method params]
        (go
          (case method
            "tool.call"
            (let [{:keys [sessionId toolCallId toolName arguments]} params]
              (if-not (get-in @(:state client) [:sessions sessionId])
                {:error {:code -32001 :message (str "Unknown session: " sessionId)}}
                {:result (<! (session/handle-tool-call! client sessionId toolCallId toolName arguments))}))

            "permission.request"
            (let [{:keys [sessionId permissionRequest]} params]
              (if-not (get-in @(:state client) [:sessions sessionId])
                {:result {:kind "denied-no-approval-rule-and-could-not-request-from-user"}}
                {:result (<! (session/handle-permission-request! client sessionId permissionRequest))}))

            {:error {:code -32601 :message (str "Unknown method: " method)}}))))))

(defn- connect-stdio!
  "Connect via stdio to the CLI process."
  [client]
  (let [{:keys [process]} @(:state client)]
    ;; Initialize connection state before connecting
    (swap! (:state client) assoc :connection (proto/initial-connection-state))
    (let [conn (proto/connect (:stdout process) (:stdin process) (:state client))]
      (swap! (:state client) assoc :connection-io conn))))

(defn- connect-tcp!
  "Connect via TCP to the CLI server."
  [client]
  (let [host (:actual-host client)
        {:keys [actual-port]} @(:state client)
        socket (proc/connect-tcp host actual-port 10000)]
    ;; Initialize connection state before connecting
    (swap! (:state client) assoc :connection (proto/initial-connection-state))
    (let [conn (proto/connect (.getInputStream socket) (.getOutputStream socket) (:state client))]
      (swap! (:state client) assoc :socket socket :connection-io conn))))

(defn- verify-protocol-version!
  "Verify the server's protocol version matches ours."
  [client]
  (let [{:keys [connection-io]} @(:state client)
        result (proto/send-request! connection-io "ping" {})
        server-version (:protocolVersion result)]
    (when (nil? server-version)
      (throw (ex-info
              (str "SDK protocol version mismatch: SDK expects version "
                   sdk-protocol-version ", but server does not report a protocol version.")
              {:expected sdk-protocol-version :actual nil})))
    (when (not= server-version sdk-protocol-version)
      (throw (ex-info
              (str "SDK protocol version mismatch: SDK expects version "
                   sdk-protocol-version ", but server reports version " server-version)
              {:expected sdk-protocol-version :actual server-version})))))

(defn start!
  "Start the CLI server and establish connection.
   Blocks until connected or throws on error."
  [client]
  (when-not (= :connected (:status @(:state client)))
    (log/info "Starting Copilot client...")
    (swap! (:state client) assoc :status :connecting)

    ;; Set log level from options
    (when-let [level (:log-level (:options client))]
      (log/set-log-level! level))

    (try
      ;; Start CLI process if not connecting to external server
      (when-not (:external-server? client)
        (log/debug "Spawning CLI process")
        (let [opts (:options client)
              mp (proc/spawn-cli opts)]
          (swap! (:state client) assoc :process mp)

          ;; For TCP mode, wait for port announcement
          (when-not (:use-stdio? opts)
            (let [port (proc/wait-for-port mp 10000)]
              (swap! (:state client) assoc :actual-port port)))))

      ;; Connect to server
      (if (or (:external-server? client)
              (not (:use-stdio? (:options client))))
        (do
          (log/debug "Connecting via TCP")
          (connect-tcp! client))
        (do
          (log/debug "Connecting via stdio")
          (connect-stdio! client)))

      ;; Verify protocol version
      (verify-protocol-version! client)

      ;; Set up notification routing and request handling
      (start-notification-router! client)
      (setup-request-handler! client)

      (swap! (:state client) assoc :status :connected)
      (log/info "Copilot client connected")
      nil

      (catch Exception e
        (log/error "Failed to start client: " (ex-message e))
        (swap! (:state client) assoc :status :error)
        (throw e)))))

(defn stop!
  "Stop the CLI server and close all sessions.
   Returns a vector of any errors encountered during cleanup."
  [client]
  (log/info "Stopping Copilot client...")
  (let [errors (atom [])
        {:keys [sessions session-io process connection-io socket]} @(:state client)]
    ;; 1. Destroy all sessions
    (doseq [[session-id _] sessions]
      (try
        (session/destroy! client session-id)
        (catch Exception e
          (swap! errors conj
                 (ex-info (str "Failed to destroy session " session-id)
                          {:session-id session-id} e)))))
    (swap! (:state client) assoc :sessions {} :session-io {})

    ;; 2. Close connection (non-blocking, may leave read thread blocked for stdio)
    (when connection-io
      (try
        (proto/disconnect connection-io)
        (catch Exception e
          (swap! errors conj
                 (ex-info "Failed to close connection" {} e))))
      (swap! (:state client) assoc :connection nil :connection-io nil))

    ;; 3. Close socket (TCP mode)
    (when socket
      (try
        (.close ^Socket socket)
        (catch Exception e
          (swap! errors conj
                 (ex-info "Failed to close socket" {} e))))
      (swap! (:state client) assoc :socket nil))

    ;; 4. Kill CLI process (this also unblocks any stdio read thread)
    (when (and (not (:external-server? client)) process)
      (try
        (proc/destroy! process)
        (catch Exception e
          (swap! errors conj
                 (ex-info "Failed to kill CLI process" {} e))))
      (swap! (:state client) assoc :process nil))

    (swap! (:state client) assoc :status :disconnected :actual-port nil)

    (log/info "Copilot client stopped")
    @errors))

(defn force-stop!
  "Force stop the CLI server without graceful cleanup."
  [client]
  (swap! (:state client) assoc :force-stopping? true)
  
  (let [{:keys [connection-io socket process]} @(:state client)]
    ;; Clear sessions without destroying
    (swap! (:state client) assoc :sessions {} :session-io {})

    ;; Force close connection
    (when connection-io
      (try (proto/disconnect connection-io) (catch Exception _)))

    ;; Force close socket
    (when socket
      (try (.close ^Socket socket) (catch Exception _)))

    ;; Force kill process
    (when (and (not (:external-server? client)) process)
      (try (proc/destroy-forcibly! process) (catch Exception _))))

  (swap! (:state client) merge
         {:status :disconnected
          :connection nil
          :connection-io nil
          :socket nil
          :process nil
          :actual-port nil
          :force-stopping? false})
  nil)

(defn- ensure-connected!
  "Ensure client is connected, auto-starting if configured."
  [client]
  (when-not (= :connected (:status @(:state client)))
    (if (:auto-start? (:options client))
      (start! client)
      (throw (ex-info "Client not connected. Call start! first." {})))))

(defn ping
  "Ping the server to check connectivity.
   Returns {:message :timestamp :protocol-version}."
  ([client]
   (ping client nil))
  ([client message]
   (ensure-connected! client)
   (let [{:keys [connection-io]} @(:state client)
         result (proto/send-request! connection-io "ping" {:message message})]
     {:message (:message result)
      :timestamp (:timestamp result)
      :protocol-version (:protocolVersion result)})))

(defn create-session
  "Create a new conversation session.
   
   Config options:
   - :session-id         - Custom session ID
   - :model              - Model to use (e.g., \"gpt-5\")
   - :tools              - Vector of tool definitions
   - :system-message     - System message config
   - :available-tools    - List of allowed tool names
   - :excluded-tools     - List of excluded tool names
   - :provider           - Custom provider config (BYOK)
   - :on-permission-request - Permission handler function
   - :streaming?         - Enable streaming
   - :mcp-servers        - MCP server configs
   - :custom-agents      - Custom agent configs
   
   Returns a CopilotSession."
  ([client]
   (create-session client {}))
  ([client config]
   (log/debug "Creating session with config: " (select-keys config [:model :session-id]))
   (ensure-connected! client)
   (let [{:keys [connection-io]} @(:state client)
         ;; Convert tools to wire format
         wire-tools (when (:tools config)
                      (mapv (fn [t]
                              {:name (:tool-name t)
                               :description (:tool-description t)
                               :parameters (:tool-parameters t)})
                            (:tools config)))
         ;; Convert system message
         wire-sys-msg (when-let [sm (:system-message config)]
                        (cond
                          (= :replace (:mode sm))
                          {:mode "replace" :content (:content sm)}
                          
                          :else
                          {:mode "append" :content (:content sm)}))
         ;; Build request params
         params (cond-> {}
                  (:session-id config) (assoc :sessionId (:session-id config))
                  (:model config) (assoc :model (:model config))
                  wire-tools (assoc :tools wire-tools)
                  wire-sys-msg (assoc :systemMessage wire-sys-msg)
                  (:available-tools config) (assoc :availableTools (:available-tools config))
                  (:excluded-tools config) (assoc :excludedTools (:excluded-tools config))
                  (:provider config) (assoc :provider (:provider config))
                  (:on-permission-request config) (assoc :requestPermission true)
                  (:streaming? config) (assoc :streaming (:streaming? config))
                  (:mcp-servers config) (assoc :mcpServers (:mcp-servers config))
                  (:custom-agents config) (assoc :customAgents (:custom-agents config)))
         result (proto/send-request! connection-io "session.create" params)
         session-id (:sessionId result)
         ;; Session state is stored by session/create-session in client's atom
         session (session/create-session client session-id
                                         {:tools (:tools config)
                                          :on-permission-request (:on-permission-request config)})]
     (log/info "Session created: " session-id)
     session)))

(defn resume-session
  "Resume an existing session by ID.
   
   Config options (same as create-session except session-id/model):
   - :tools
   - :provider
   - :streaming?
   - :on-permission-request
   - :mcp-servers
   - :custom-agents
   
   Returns a CopilotSession."
  ([client session-id]
   (resume-session client session-id {}))
  ([client session-id config]
   (ensure-connected! client)
   (let [{:keys [connection-io]} @(:state client)
         wire-tools (when (:tools config)
                      (mapv (fn [t]
                              {:name (:tool-name t)
                               :description (:tool-description t)
                               :parameters (:tool-parameters t)})
                            (:tools config)))
         params (cond-> {:sessionId session-id}
                  wire-tools (assoc :tools wire-tools)
                  (:provider config) (assoc :provider (:provider config))
                  (:on-permission-request config) (assoc :requestPermission true)
                  (:streaming? config) (assoc :streaming (:streaming? config))
                  (:mcp-servers config) (assoc :mcpServers (:mcp-servers config))
                  (:custom-agents config) (assoc :customAgents (:custom-agents config)))
         result (proto/send-request! connection-io "session.resume" params)
         resumed-id (:sessionId result)
         ;; Session state is stored by session/create-session in client's atom
         session (session/create-session client resumed-id
                                         {:tools (:tools config)
                                          :on-permission-request (:on-permission-request config)})]
     session)))

(defn list-sessions
  "List all available sessions.
   Returns a vector of session metadata maps."
  [client]
  (ensure-connected! client)
  (let [{:keys [connection-io]} @(:state client)
        result (proto/send-request! connection-io "session.list" {})
        sessions (:sessions result)]
    (mapv (fn [s]
            {:session-id (:sessionId s)
             :start-time (java.time.Instant/parse (:startTime s))
             :modified-time (java.time.Instant/parse (:modifiedTime s))
             :summary (:summary s)
             :remote? (:isRemote s)})
          sessions)))

(defn delete-session!
  "Delete a session and its data from disk."
  [client session-id]
  (ensure-connected! client)
  (let [{:keys [connection-io]} @(:state client)
        result (proto/send-request! connection-io "session.delete" {:sessionId session-id})]
    (when-not (:success result)
      (throw (ex-info (str "Failed to delete session: " (:error result))
                      {:session-id session-id :error (:error result)})))
    ;; Remove from local sessions and IO
    (swap! (:state client) (fn [s]
                             (-> s
                                 (update :sessions dissoc session-id)
                                 (update :session-io dissoc session-id))))
    nil))

(defn get-last-session-id
  "Get the ID of the most recently updated session."
  [client]
  (ensure-connected! client)
  (let [{:keys [connection-io]} @(:state client)
        result (proto/send-request! connection-io "session.getLastId" {})]
    (:sessionId result)))

;; -----------------------------------------------------------------------------
;; Testing Utilities
;; -----------------------------------------------------------------------------

(defn connect-with-streams!
  "Connect to a server using pre-existing input/output streams.
   For testing purposes only."
  [client in out]
  (when-not (= :connected (:status @(:state client)))
    (swap! (:state client) assoc :status :connecting)
    (try
      ;; Initialize connection state before connecting
      (swap! (:state client) assoc :connection (proto/initial-connection-state))
      (let [conn (proto/connect in out (:state client))]
        (swap! (:state client) assoc :connection-io conn))
      (verify-protocol-version! client)
      (start-notification-router! client)
      (setup-request-handler! client)
      (swap! (:state client) assoc :status :connected)
      nil
      (catch Exception e
        (swap! (:state client) assoc :status :error)
        (throw e)))))
