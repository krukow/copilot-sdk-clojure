(ns krukow.copilot-sdk.java-api
  (:require [clojure.core.async :as async]
            [clojure.walk :as walk]
            [krukow.copilot-sdk :as copilot]
            [krukow.copilot-sdk.helpers :as helpers]))

;; =============================================================================
;; Interfaces
;; =============================================================================

(gen-interface
 :name krukow.copilot_sdk.IEventHandler
 :methods [[handle [krukow.copilot_sdk.Event] void]])

(gen-interface
 :name krukow.copilot_sdk.IToolHandler
 :methods [[handle [java.util.Map java.util.Map] Object]])

(gen-interface
 :name krukow.copilot_sdk.IPermissionHandler
 :methods [[handle [java.util.Map] java.util.Map]])

(gen-interface
 :name krukow.copilot_sdk.ICopilotClient
 :methods [[start [] void]
           [stop [] java.util.List]
           [forceStop [] void]
           [getState [] String]
           [createSession [krukow.copilot_sdk.SessionOptions] krukow.copilot_sdk.ICopilotSession]
           [ping [] java.util.Map]
           [getStatus [] java.util.Map]
           [getAuthStatus [] java.util.Map]
           [listModels [] java.util.List]])

(gen-interface
 :name krukow.copilot_sdk.ICopilotSession
 :methods [[getSessionId [] String]
           [send [String] String]
           [sendAndWait [String long] String]
           [sendStreaming [String krukow.copilot_sdk.IEventHandler] void]
           [sendAsync [String] java.util.concurrent.CompletableFuture]
           [subscribeEvents [] krukow.copilot_sdk.EventSubscription]
           [abort [] void]
           [destroy [] void]
           [getMessages [] java.util.List]])

;; =============================================================================
;; Event class
;; =============================================================================

(gen-class
 :name krukow.copilot_sdk.Event
 :state state
 :init init-event
 :constructors {[String java.util.Map String String] []}
 :prefix "event-"
 :methods [[getType [] String]
           [getData [] java.util.Map]
           [get [String] Object]
           [getContent [] String]
           [getDeltaContent [] String]
           [getId [] String]
           [getTimestamp [] String]
           [isType [String] boolean]
           [isMessage [] boolean]
           [isMessageDelta [] boolean]
           [isIdle [] boolean]
           [isError [] boolean]])

(defn event-init-event [type data id timestamp]
  [[] {:type type :data data :id id :timestamp timestamp}])

(defn event-getType [this] (:type (.state this)))
(defn event-getData [this] (:data (.state this)))
(defn event-get [this key] (when-let [data (:data (.state this))] (.get ^java.util.Map data key)))
(defn event-getContent [this] (event-get this "content"))
(defn event-getDeltaContent [this] (event-get this "delta-content"))
(defn event-getId [this] (:id (.state this)))
(defn event-getTimestamp [this] (:timestamp (.state this)))
(defn event-isType [this expected] (= expected (:type (.state this))))
(defn event-isMessage [this] (= "assistant.message" (:type (.state this))))
(defn event-isMessageDelta [this] (= "assistant.message_delta" (:type (.state this))))
(defn event-isIdle [this] (= "session.idle" (:type (.state this))))
(defn event-isError [this] (= "session.error" (:type (.state this))))
(defn event-toString [this]
  (let [s (.state this)]
    (str "Event{type='" (:type s) "', id='" (:id s) "', data=" (:data s) "}")))

;; =============================================================================
;; EventSubscription class (for streaming event access)
;; =============================================================================

(gen-class
 :name krukow.copilot_sdk.EventSubscription
 :implements [java.lang.AutoCloseable]
 :state state
 :init init-subscription
 :constructors {[Object Object Object] []}
 :prefix "sub-"
 :methods [[take [] krukow.copilot_sdk.Event]
           [poll [] krukow.copilot_sdk.Event]
           [poll [long java.util.concurrent.TimeUnit] krukow.copilot_sdk.Event]])

(declare clj-event->java)

(defn sub-init-subscription [session ch convert-fn]
  [[] {:session session :ch ch :convert-fn convert-fn}])

(defn sub-take [this]
  (let [{:keys [ch convert-fn]} (.state this)]
    (when-let [event (async/<!! ch)]
      (convert-fn event))))

(defn sub-poll
  ([this]
   (let [{:keys [ch convert-fn]} (.state this)]
     (let [[event _] (async/alts!! [ch] :default nil)]
       (when event (convert-fn event)))))
  ([this timeout unit]
   (let [{:keys [ch convert-fn]} (.state this)
         timeout-ms (.toMillis ^java.util.concurrent.TimeUnit unit timeout)]
     (let [[event _] (async/alts!! [ch (async/timeout timeout-ms)])]
       (when event (convert-fn event))))))

(defn sub-close [this]
  (let [{:keys [session ch]} (.state this)]
    (copilot/unsubscribe-events session ch)))

;; =============================================================================
;; Tool class
;; =============================================================================

(gen-class
 :name krukow.copilot_sdk.Tool
 :state state
 :init init-tool
 :constructors {[String String java.util.Map krukow.copilot_sdk.IToolHandler] []}
 :prefix "tool-"
 :methods [[getName [] String]
           [getDescription [] String]
           [getParameters [] java.util.Map]
           [getHandler [] krukow.copilot_sdk.IToolHandler]
           ^:static [success [String] java.util.Map]
           ^:static [failure [String String] java.util.Map]])

(defn tool-init-tool [name description parameters handler]
  [[] {:name name :description description :parameters parameters :handler handler}])

(defn tool-getName [this] (:name (.state this)))
(defn tool-getDescription [this] (:description (.state this)))
(defn tool-getParameters [this] (:parameters (.state this)))
(defn tool-getHandler [this] (:handler (.state this)))
(defn tool-success [text] {"textResultForLlm" text "resultType" "success" "toolTelemetry" {}})
(defn tool-failure [text error] {"textResultForLlm" text "resultType" "failure" "error" error "toolTelemetry" {}})

;; =============================================================================
;; PermissionResult class
;; =============================================================================

(gen-class
 :name krukow.copilot_sdk.PermissionResult
 :prefix "perm-"
 :methods [^:static [approved [] java.util.Map]
           ^:static [deniedByRules [java.util.List] java.util.Map]
           ^:static [deniedNoApprovalRule [] java.util.Map]
           ^:static [deniedByUser [] java.util.Map]
           ^:static [deniedByUser [String] java.util.Map]])

(defn perm-approved [] {"kind" "approved"})
(defn perm-deniedByRules [rules] {"kind" "denied-by-rules" "rules" (java.util.ArrayList. rules)})
(defn perm-deniedNoApprovalRule [] {"kind" "denied-no-approval-rule-and-could-not-request-from-user"})
(defn perm-deniedByUser
  ([] {"kind" "denied-interactively-by-user"})
  ([feedback] {"kind" "denied-interactively-by-user" "feedback" feedback}))

;; =============================================================================
;; ClientOptions class
;; =============================================================================

(gen-class
 :name krukow.copilot_sdk.ClientOptions
 :state state
 :init init-client-opts
 :constructors {[java.util.Map] []}
 :prefix "client-opts-"
 :methods [[getCliPath [] String]
           [getCwd [] String]
           [getPort [] Integer]
           [getUseStdio [] Boolean]
           [getLogLevel [] String]
           [getAutoStart [] Boolean]
           [getAutoRestart [] Boolean]
           [getNotificationQueueSize [] Integer]
           [getRouterQueueSize [] Integer]
           [getToolTimeoutMs [] Integer]
           [getEnv [] java.util.Map]
           [toMap [] java.util.Map]])

(defn client-opts-init-client-opts [opts] [[] opts])
(defn client-opts-getCliPath [this] (.get ^java.util.Map (.state this) "cli-path"))
(defn client-opts-getCwd [this] (.get ^java.util.Map (.state this) "cwd"))
(defn client-opts-getPort [this] (.get ^java.util.Map (.state this) "port"))
(defn client-opts-getUseStdio [this] (.get ^java.util.Map (.state this) "use-stdio?"))
(defn client-opts-getLogLevel [this] (.get ^java.util.Map (.state this) "log-level"))
(defn client-opts-getAutoStart [this] (.get ^java.util.Map (.state this) "auto-start?"))
(defn client-opts-getAutoRestart [this] (.get ^java.util.Map (.state this) "auto-restart?"))
(defn client-opts-getNotificationQueueSize [this] (.get ^java.util.Map (.state this) "notification-queue-size"))
(defn client-opts-getRouterQueueSize [this] (.get ^java.util.Map (.state this) "router-queue-size"))
(defn client-opts-getToolTimeoutMs [this] (.get ^java.util.Map (.state this) "tool-timeout-ms"))
(defn client-opts-getEnv [this] (.get ^java.util.Map (.state this) "env"))
(defn client-opts-toMap [this] (.state this))

;; =============================================================================
;; ClientOptionsBuilder class (methods return Object for self-reference)
;; =============================================================================

(gen-class
 :name krukow.copilot_sdk.ClientOptionsBuilder
 :state state
 :init init-client-builder
 :constructors {[] []}
 :prefix "client-builder-"
 :methods [[cliPath [String] Object]
           [cliArgs [java.util.List] Object]
           [cliUrl [String] Object]
           [cwd [String] Object]
           [port [int] Object]
            [useStdio [boolean] Object]
            [logLevel [String] Object]
            [autoStart [boolean] Object]
            [autoRestart [boolean] Object]
            [notificationQueueSize [int] Object]
            [routerQueueSize [int] Object]
            [toolTimeoutMs [int] Object]
            [env [java.util.Map] Object]
            [build [] krukow.copilot_sdk.ClientOptions]])

(defn client-builder-init-client-builder [] [[] (atom {})])
(defn client-builder-cliPath [this v] (swap! (.state this) assoc "cli-path" v) this)
(defn client-builder-cliArgs [this v] (swap! (.state this) assoc "cli-args" (vec v)) this)
(defn client-builder-cliUrl [this v] (swap! (.state this) assoc "cli-url" v) this)
(defn client-builder-cwd [this v] (swap! (.state this) assoc "cwd" v) this)
(defn client-builder-port [this v] (swap! (.state this) assoc "port" (Integer/valueOf v)) this)
(defn client-builder-useStdio [this v] (swap! (.state this) assoc "use-stdio?" (Boolean/valueOf v)) this)
(defn client-builder-logLevel [this v] (swap! (.state this) assoc "log-level" v) this)
(defn client-builder-autoStart [this v] (swap! (.state this) assoc "auto-start?" (Boolean/valueOf v)) this)
(defn client-builder-autoRestart [this v] (swap! (.state this) assoc "auto-restart?" (Boolean/valueOf v)) this)
(defn client-builder-notificationQueueSize [this v] (swap! (.state this) assoc "notification-queue-size" (Integer/valueOf v)) this)
(defn client-builder-routerQueueSize [this v] (swap! (.state this) assoc "router-queue-size" (Integer/valueOf v)) this)
(defn client-builder-toolTimeoutMs [this v] (swap! (.state this) assoc "tool-timeout-ms" (Integer/valueOf v)) this)
(defn client-builder-env [this v] (swap! (.state this) assoc "env" v) this)
(defn client-builder-build [this] (krukow.copilot_sdk.ClientOptions. (java.util.HashMap. @(.state this))))

;; =============================================================================
;; SessionOptions class
;; =============================================================================

(gen-class
 :name krukow.copilot_sdk.SessionOptions
 :state state
 :init init-session-opts
 :constructors {[java.util.Map] []}
 :prefix "session-opts-"
 :methods [[getModel [] String]
           [getStreaming [] Boolean]
           [getSystemPrompt [] String]
           [getTools [] java.util.List]
           [getAllowedTools [] java.util.List]
           [getExcludedTools [] java.util.List]
           [getConfigDir [] String]
           [getSkillDirectories [] java.util.List]
           [getDisabledSkills [] java.util.List]
           [toMap [] java.util.Map]])

(defn session-opts-init-session-opts [opts] [[] opts])
(defn session-opts-getModel [this] (.get ^java.util.Map (.state this) "model"))
(defn session-opts-getStreaming [this] (.get ^java.util.Map (.state this) "streaming?"))
(defn session-opts-getSystemPrompt [this] (.get ^java.util.Map (.state this) "system-prompt"))
(defn session-opts-getTools [this] (.get ^java.util.Map (.state this) "tools"))
(defn session-opts-getAllowedTools [this] (.get ^java.util.Map (.state this) "available-tools"))
(defn session-opts-getExcludedTools [this] (.get ^java.util.Map (.state this) "excluded-tools"))
(defn session-opts-getConfigDir [this] (.get ^java.util.Map (.state this) "config-dir"))
(defn session-opts-getSkillDirectories [this] (.get ^java.util.Map (.state this) "skill-directories"))
(defn session-opts-getDisabledSkills [this] (.get ^java.util.Map (.state this) "disabled-skills"))
(defn session-opts-toMap [this] (.state this))

;; =============================================================================
;; SessionOptionsBuilder class (methods return Object for self-reference)
;; =============================================================================

(gen-class
 :name krukow.copilot_sdk.SessionOptionsBuilder
 :state state
 :init init-session-builder
 :constructors {[] []}
 :prefix "session-builder-"
 :methods [[sessionId [String] Object]
           [model [String] Object]
           [streaming [boolean] Object]
           [systemPrompt [String] Object]
           [systemMessage [String String] Object]
           [tools [java.util.List] Object]
           [tool [krukow.copilot_sdk.Tool] Object]
           [onPermissionRequest [krukow.copilot_sdk.IPermissionHandler] Object]
           [allowedTools [java.util.List] Object]
           [allowedTool [String] Object]
           [excludedTools [java.util.List] Object]
           [excludedTool [String] Object]
           [provider [java.util.Map] Object]
           [mcpServers [java.util.Map] Object]
           [customAgents [java.util.List] Object]
           [configDir [String] Object]
           [skillDirectories [java.util.List] Object]
           [skillDirectory [String] Object]
           [disabledSkills [java.util.List] Object]
           [disabledSkill [String] Object]
           [largeOutput [java.util.Map] Object]
           [build [] krukow.copilot_sdk.SessionOptions]])

(defn session-builder-init-session-builder [] [[] (atom {})])
(defn session-builder-sessionId [this v] (swap! (.state this) assoc "session-id" v) this)
(defn session-builder-model [this v] (swap! (.state this) assoc "model" v) this)
(defn session-builder-streaming [this v] (swap! (.state this) assoc "streaming?" (Boolean/valueOf v)) this)
(defn session-builder-systemPrompt [this v] (swap! (.state this) assoc "system-prompt" v) this)
(defn session-builder-systemMessage [this mode content] (swap! (.state this) assoc "system-message" {"mode" mode "content" content}) this)
(defn session-builder-tools [this v] (swap! (.state this) assoc "tools" (java.util.ArrayList. v)) this)
(defn session-builder-tool [this v]
  (swap! (.state this) update "tools" (fn [lst] (let [l (or lst (java.util.ArrayList.))] (.add ^java.util.List l v) l)))
  this)
(defn session-builder-onPermissionRequest [this v] (swap! (.state this) assoc "on-permission-request" v) this)
(defn session-builder-allowedTools [this v] (swap! (.state this) assoc "available-tools" (java.util.ArrayList. v)) this)
(defn session-builder-allowedTool [this v]
  (swap! (.state this) update "available-tools" (fn [lst] (let [l (or lst (java.util.ArrayList.))] (.add ^java.util.List l v) l)))
  this)
(defn session-builder-excludedTools [this v] (swap! (.state this) assoc "excluded-tools" (java.util.ArrayList. v)) this)
(defn session-builder-excludedTool [this v]
  (swap! (.state this) update "excluded-tools" (fn [lst] (let [l (or lst (java.util.ArrayList.))] (.add ^java.util.List l v) l)))
  this)
(defn session-builder-provider [this v] (swap! (.state this) assoc "provider" v) this)
(defn session-builder-mcpServers [this v] (swap! (.state this) assoc "mcp-servers" v) this)
(defn session-builder-customAgents [this v] (swap! (.state this) assoc "custom-agents" (java.util.ArrayList. v)) this)
(defn session-builder-configDir [this v] (swap! (.state this) assoc "config-dir" v) this)
(defn session-builder-skillDirectories [this v] (swap! (.state this) assoc "skill-directories" (java.util.ArrayList. v)) this)
(defn session-builder-skillDirectory [this v]
  (swap! (.state this) update "skill-directories" (fn [lst] (let [l (or lst (java.util.ArrayList.))] (.add ^java.util.List l v) l)))
  this)
(defn session-builder-disabledSkills [this v] (swap! (.state this) assoc "disabled-skills" (java.util.ArrayList. v)) this)
(defn session-builder-disabledSkill [this v]
  (swap! (.state this) update "disabled-skills" (fn [lst] (let [l (or lst (java.util.ArrayList.))] (.add ^java.util.List l v) l)))
  this)
(defn session-builder-largeOutput [this v] (swap! (.state this) assoc "large-output" v) this)
(defn session-builder-build [this] (krukow.copilot_sdk.SessionOptions. (java.util.HashMap. @(.state this))))

;; =============================================================================
;; Copilot main class
;; =============================================================================

(gen-class
 :name krukow.copilot_sdk.Copilot
 :prefix "copilot-"
 :methods [^:static [query [String] String]
           ^:static [query [String krukow.copilot_sdk.SessionOptions] String]
           ^:static [query [String krukow.copilot_sdk.SessionOptions long] String]
           ^:static [queryStreaming [String krukow.copilot_sdk.SessionOptions krukow.copilot_sdk.IEventHandler] void]
           ^:static [createClient [] krukow.copilot_sdk.ICopilotClient]
           ^:static [createClient [krukow.copilot_sdk.ClientOptions] krukow.copilot_sdk.ICopilotClient]])

;; =============================================================================
;; Conversion helpers
;; =============================================================================

(defn- java-map->clj-map [^java.util.Map m]
  (when m
    (into {}
          (map (fn [[k v]]
                 [(if (string? k) (keyword k) k)
                  (cond
                    (instance? java.util.Map v) (java-map->clj-map v)
                    (instance? java.util.List v) (vec v)
                    :else v)]))
          m)))

(defn- java-tool->clj-tool [^krukow.copilot_sdk.Tool tool]
  (let [handler (.getHandler tool)
        params (java-map->clj-map (.getParameters tool))]
    (copilot/define-tool (.getName tool)
      {:description (.getDescription tool)
       :parameters params
       :handler (fn [args invocation]
                  (let [java-args (walk/stringify-keys args)
                        java-invocation (walk/stringify-keys invocation)
                        result (.handle handler java-args java-invocation)]
                    (cond
                      (string? result) (copilot/result-success result)
                      (instance? java.util.Map result) (java-map->clj-map result)
                      :else (copilot/result-success (str result)))))})))

(defn- java-permission-handler->clj [^krukow.copilot_sdk.IPermissionHandler handler]
  (fn [request _ctx]
    (let [java-request (walk/stringify-keys request)
          result (.handle handler java-request)]
      (java-map->clj-map result))))

(defn- convert-session-opts [^krukow.copilot_sdk.SessionOptions opts]
  (when opts
    (let [base-map (java-map->clj-map (.toMap opts))
          java-tools (:tools base-map)
          java-perm-handler (:on-permission-request base-map)
          system-prompt (:system-prompt base-map)]
      (cond-> base-map
        (and system-prompt (not (:system-message base-map)))
        (assoc :system-message {:mode :append :content system-prompt})
        system-prompt (dissoc :system-prompt)
        java-tools (assoc :tools (mapv java-tool->clj-tool java-tools))
        java-perm-handler (assoc :on-permission-request (java-permission-handler->clj java-perm-handler))))))

(defn- convert-client-opts [^krukow.copilot_sdk.ClientOptions opts]
  (when opts
    (java-map->clj-map (.toMap opts))))

(defn- clj-event->java [event]
  (krukow.copilot_sdk.Event. (name (:type event))
                             (walk/stringify-keys (:data event))
                             (:id event)
                             (:timestamp event)))

;; =============================================================================
;; Client/Session wrappers
;; =============================================================================

(declare wrap-session)

(defn- wrap-client [clj-client]
  (reify krukow.copilot_sdk.ICopilotClient
    (start [_] (copilot/start! clj-client))
    (stop [_] (java.util.ArrayList. (copilot/stop! clj-client)))
    (forceStop [_] (copilot/force-stop! clj-client))
    (getState [_] (name (copilot/state clj-client)))
    (createSession [_ opts] (wrap-session (copilot/create-session clj-client (or (convert-session-opts opts) {}))))
    (ping [_] (walk/stringify-keys (copilot/ping clj-client)))
    (getStatus [_] (walk/stringify-keys (copilot/get-status clj-client)))
    (getAuthStatus [_] (walk/stringify-keys (copilot/get-auth-status clj-client)))
    (listModels [_] (java.util.ArrayList. (mapv walk/stringify-keys (copilot/list-models clj-client))))))

(defn- wrap-session [clj-session]
  (reify krukow.copilot_sdk.ICopilotSession
    (getSessionId [_] (copilot/session-id clj-session))
    (send [_ prompt] (copilot/send! clj-session {:prompt prompt}))
    (sendAndWait [_ prompt timeout-ms]
      (let [response (copilot/send-and-wait! clj-session {:prompt prompt} timeout-ms)]
        (get-in response [:data :content])))
    (sendStreaming [_ prompt handler]
      (let [events-ch (copilot/subscribe-events clj-session)]
        (try
          (copilot/send! clj-session {:prompt prompt})
          (loop []
            (when-let [event (async/<!! events-ch)]
              (.handle handler (clj-event->java event))
              (when-not (#{:session.idle :session.error} (:type event))
                (recur))))
          (finally
            (copilot/unsubscribe-events clj-session events-ch)))))
    (sendAsync [_ prompt]
      (let [future (java.util.concurrent.CompletableFuture.)
            events-ch (copilot/send-async clj-session {:prompt prompt})]
        (async/go
          (loop [content nil]
            (if-let [event (async/<! events-ch)]
              (case (:type event)
                :assistant.message
                (recur (get-in event [:data :content]))
                :session.idle
                (.complete future content)
                :session.error
                (.completeExceptionally future
                  (ex-info "Session error" {:event (clj-event->java event)}))
                (recur content))
              (.completeExceptionally future
                (ex-info "Event channel closed unexpectedly" {})))))
        future))
    (subscribeEvents [_]
      (let [ch (copilot/subscribe-events clj-session)]
        (krukow.copilot_sdk.EventSubscription. clj-session ch clj-event->java)))
    (abort [_] (copilot/abort! clj-session))
    (destroy [_] (copilot/destroy! clj-session))
    (getMessages [_] (java.util.ArrayList. (mapv #(walk/stringify-keys %) (copilot/get-messages clj-session))))))

;; =============================================================================
;; Copilot static methods
;; =============================================================================

(defn copilot-query
  ([^String prompt] (helpers/query prompt))
  ([^String prompt ^krukow.copilot_sdk.SessionOptions opts]
   (helpers/query prompt :session (convert-session-opts opts)))
  ([^String prompt ^krukow.copilot_sdk.SessionOptions opts ^long timeout-ms]
   (helpers/query prompt :session (convert-session-opts opts) :timeout-ms timeout-ms)))

(defn copilot-queryStreaming [^String prompt ^krukow.copilot_sdk.SessionOptions opts handler]
  (let [events (helpers/query-seq! prompt :session (convert-session-opts opts))]
    (doseq [event events]
      (.handle handler (clj-event->java event)))))

(defn copilot-createClient
  ([] (wrap-client (copilot/client {})))
  ([^krukow.copilot_sdk.ClientOptions opts]
   (wrap-client (copilot/client (or (convert-client-opts opts) {})))))
