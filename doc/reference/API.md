# API Reference

## Helpers API

The helpers namespace provides simple, stateless query functions with automatic client management.

```clojure
(require '[github.copilot-sdk.helpers :as h])
```

### `query`

```clojure
(h/query prompt & {:keys [client session timeout-ms]})
```

Execute a query and return the response text.

**Options:**
- `:client` - Client options map (cli-path, log-level, cwd, env) OR a CopilotClient instance
- `:session` - Session options map (model, system-prompt, tools, etc.) OR a CopilotSession instance
- `:timeout-ms` - Timeout in milliseconds (default: 180000)

When `:session` is a CopilotSession instance, the query uses that session directly (enabling multi-turn conversations). When `:client` is a CopilotClient instance, it uses that client directly.

```clojure
;; Simple query (shared client, fresh session)
(h/query "What is 2+2?")
;; => "4"

;; With session options
(h/query "Explain monads" :session {:model "claude-sonnet-4.5"})

;; With system prompt
(h/query "Hello" :session {:system-prompt "Be concise."})

;; With explicit client
(copilot/with-client [client {}]
  (h/query "What is Clojure?" :client client))

;; With explicit session (multi-turn conversation)
(copilot/with-client [client {}]
  (copilot/with-session [session client {}]
    (h/query "My name is Alice." :session session)
    (h/query "What is my name?" :session session))) ;; context preserved!
```

### `query-seq!`

```clojure
(h/query-seq! prompt & {:keys [client session max-events]})
```

Execute a query and return a bounded lazy sequence of events with guaranteed cleanup (default: 256 events).

```clojure
(->> (h/query-seq! "Tell me a story" :session {:streaming? true})
     (filter #(= :copilot/assistant.message_delta (:type %)))
     (map #(get-in % [:data :delta-content]))
     (run! print))
```

### `query-chan`

```clojure
(h/query-chan prompt & {:keys [client session buffer]})
```

Execute a query and return a core.async channel of events. Use this when you need an explicit lifecycle
or want to stop reading early without leaking session resources.

```clojure
(let [ch (h/query-chan "Tell me a story" :session {:streaming? true})]
  (go-loop []
    (when-let [event (<! ch)]
      (when (= :copilot/assistant.message_delta (:type event))
        (print (get-in event [:data :delta-content])))
      (recur))))
```

### `shutdown!`

```clojure
(h/shutdown!)
```

Explicitly shutdown the shared client. Safe to call multiple times.

---

## CopilotClient

```clojure
(require '[github.copilot-sdk :as copilot])
```

### Constructor

```clojure
(copilot/client options)
```

**Options:**

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `:cli-path` | string | `"copilot"` | Path to CLI executable |
| `:cli-args` | vector | `[]` | Extra arguments prepended before SDK-managed flags |
| `:cli-url` | string | nil | URL of existing CLI server (e.g., `"localhost:8080"`). When provided, no CLI process is spawned |
| `:port` | number | `0` | Server port (0 = random) |
| `:use-stdio?` | boolean | `true` | Use stdio transport instead of TCP |
| `:log-level` | keyword | `:info` | One of `:none` `:error` `:warning` `:info` `:debug` `:all` |
| `:auto-start?` | boolean | `true` | Auto-start server on first operation |
| `:auto-restart?` | boolean | `true` | Auto-restart on crash |
| `:notification-queue-size` | number | `4096` | Max queued protocol notifications |
| `:router-queue-size` | number | `4096` | Max queued non-session notifications |
| `:tool-timeout-ms` | number | `120000` | Timeout for tool handlers returning channels |
| `:cwd` | string | nil | Working directory for CLI process |
| `:env` | map | nil | Environment variables |
| `:github-token` | string | nil | GitHub token for authentication. Sets `COPILOT_SDK_AUTH_TOKEN` env var and passes `--auth-token-env` flag |
| `:use-logged-in-user?` | boolean | `true` | Use logged-in user auth. Defaults to `false` when `:github-token` is provided. Cannot be used with `:cli-url` |

### Methods

#### `start!`

```clojure
(copilot/start! client)
```

Start the CLI server and establish connection. Blocks until connected.

#### `with-client`

```clojure
(copilot/with-client [client {:log-level :info}]
  ;; use client
  )
```

Create a client, start it, and ensure `stop!` runs on exit.

#### `stop!`

```clojure
(copilot/stop! client)
```

Stop the server and close all sessions gracefully.

#### `force-stop!`

```clojure
(copilot/force-stop! client)
```

Force stop the CLI server without graceful cleanup. Use when `stop!` takes too long.

#### `client-options`

```clojure
(copilot/client-options client)
;; => {:log-level :info, :use-stdio? true, :auto-start? true, ...}
```

Get the options that were used to create this client.

#### `create-session`

```clojure
(copilot/create-session client config)
```

Create a new conversation session.

#### `with-session`

```clojure
(copilot/with-session [session client {:model "gpt-5.2"}]
  ;; use session
  )
```

Create a session and ensure `destroy!` runs on exit.

#### `with-client-session`

```clojure
;; Form 1: [session session-opts] - anonymous client with default options
(copilot/with-client-session [session {:model "gpt-5.2"}]
  ;; use session
  )

;; Form 2: [client-opts session session-opts] - anonymous client with custom options
(copilot/with-client-session [{:log-level :debug} session {:model "gpt-5.2"}]
  ;; use session
  )

;; Form 3: [client session session-opts] - named client with default options
(copilot/with-client-session [client session {:model "gpt-5.2"}]
  ;; use client and session
  )

;; Form 4: [client client-opts session session-opts] - named client with custom options
(copilot/with-client-session [client {:log-level :debug} session {:model "gpt-5.2"}]
  ;; use client and session
  )
```

Create a client and session together, ensuring both are cleaned up on exit.

**Config:**

| Key | Type | Description |
|-----|------|-------------|
| `:session-id` | string | Custom session ID (optional) |
| `:model` | string | Model to use (`"gpt-5.2"`, `"claude-sonnet-4.5"`, etc.) |
| `:tools` | vector | Custom tools exposed to the CLI |
| `:system-message` | map | System message customization (see below) |
| `:available-tools` | vector | List of allowed tool names |
| `:excluded-tools` | vector | List of excluded tool names |
| `:provider` | map | Provider config for BYOK (see [BYOK docs](../auth/byok.md)). Required key: `:base-url`. Optional: `:provider-type` (`:openai`/`:azure`/`:anthropic`), `:wire-api` (`:completions`/`:responses`), `:api-key`, `:bearer-token`, `:azure-options` |
| `:mcp-servers` | map | MCP server configs keyed by server ID (see [MCP docs](../mcp/overview.md)). Local servers: `:mcp-command`, `:mcp-args`, `:mcp-tools`. Remote servers: `:mcp-server-type` (`:http`/`:sse`), `:mcp-url`, `:mcp-tools` |
| `:custom-agents` | vector | Custom agent configs |
| `:on-permission-request` | fn | Permission handler function |
| `:streaming?` | boolean | Enable streaming deltas |
| `:config-dir` | string | Override config directory for CLI |
| `:skill-directories` | vector | Additional skill directories to load |
| `:disabled-skills` | vector | Disable specific skills by name |
| `:large-output` | map | (Experimental) Tool output handling config. CLI protocol feature, not in official SDK. |
| `:working-directory` | string | Working directory for the session (tool operations relative to this) |
| `:infinite-sessions` | map | Infinite session config (see below) |
| `:reasoning-effort` | string | Reasoning effort level: `"low"`, `"medium"`, `"high"`, or `"xhigh"` |
| `:on-user-input-request` | fn | Handler for `ask_user` requests (see below) |
| `:hooks` | map | Lifecycle hooks (see below) |

#### `resume-session`

```clojure
(copilot/resume-session client session-id)
(copilot/resume-session client session-id config)
```

Resume an existing session by ID. The `config` map accepts the same options as `create-session` (except `:session-id`), plus:

| Option | Type | Description |
|---|---|---|
| `:disable-resume?` | boolean | When true, skip emitting the session.resume event (default: false) |

```clojure
;; Resume with a different model and reasoning effort
(copilot/resume-session client "session-123"
  {:model "claude-sonnet-4"
   :reasoning-effort "high"})
```

#### `ping`

```clojure
(copilot/ping client)
(copilot/ping client message)
```

Ping the server to check connectivity. Returns `{:message "..." :timestamp ... :protocol-version ...}`.

#### `get-status`

```clojure
(copilot/get-status client)
```

Get CLI status including version and protocol information. Returns `{:version "0.0.389" :protocol-version 2}`.

#### `get-auth-status`

```clojure
(copilot/get-auth-status client)
```

Get current authentication status. Returns:
```clojure
{:authenticated? true
 :auth-type :user        ; :user | :env | :gh-cli | :hmac | :api-key | :token
 :host "github.com"
 :login "username"
 :status-message "Authenticated as username"}
```

#### `list-models`

```clojure
(copilot/list-models client)
```

List available models with their metadata. Results are cached per client connection.
Requires authentication. Returns a vector of model info maps:
```clojure
[{:id "gpt-5.2"
  :name "GPT-5.2"
  :vendor "openai"
  :family "gpt-5.2"
  :version "gpt-5.2"
  :max-input-tokens 128000
  :max-output-tokens 16384
  :preview? false
  :model-capabilities {:model-supports {:supports-vision true
                                        :supports-reasoning-effort false}
                       :model-limits {:max-prompt-tokens 128000
                                      :max-context-window-tokens 128000
                                      :vision-capabilities
                                      {:supported-media-types ["image/png" "image/jpeg"]
                                       :max-prompt-images 10
                                       :max-prompt-image-size 20971520}}}
  :model-policy {:policy-state "enabled"
                 :terms "..."}
  :model-billing {:multiplier 1.0}
  ;; For models supporting reasoning:
  :supported-reasoning-efforts ["low" "medium" "high" "xhigh"]
  :default-reasoning-effort "medium"}
 ...]
```

#### `list-tools`

```clojure
(copilot/list-tools client)
(copilot/list-tools client "gpt-5.2")
```

List available tools with their metadata. Pass an optional model string to get model-specific tool overrides.

```clojure
(copilot/list-tools client)
;; => [{:name "read_file"
;;      :namespaced-name "builtin.read_file"
;;      :description "Read a file from disk"
;;      :parameters {...}
;;      :instructions "..."}
;;     ...]

;; Print all tool names
(doseq [tool (copilot/list-tools client)]
  (println (:name tool) "-" (:description tool)))
```

Each tool info map contains:

| Key | Type | Required | Description |
|-----|------|----------|-------------|
| `:name` | string | yes | Short tool name |
| `:namespaced-name` | string | no | Fully qualified tool name |
| `:description` | string | yes | Human-readable description |
| `:parameters` | map | no | JSON Schema of tool parameters |
| `:instructions` | string | no | Usage instructions for the tool |

#### `get-quota`

```clojure
(copilot/get-quota client)
```

Get account quota information. Returns a map of quota type (string) to quota snapshot maps.

```clojure
(copilot/get-quota client)
;; => {"chat" {:entitlement-requests 1000
;;             :used-requests 42
;;             :remaining-percentage 95.8
;;             :overage 0
;;             :overage-allowed-with-exhausted-quota? false
;;             :reset-date "2025-02-01T00:00:00Z"}}

(let [quotas (copilot/get-quota client)]
  (doseq [[type snapshot] quotas]
    (println type ":" (:remaining-percentage snapshot) "% remaining")))
```

Each quota snapshot map contains:

| Key | Type | Description |
|-----|------|-------------|
| `:entitlement-requests` | number | Total allowed requests |
| `:used-requests` | number | Requests used so far |
| `:remaining-percentage` | number | Percentage of quota remaining |
| `:overage` | number | Number of requests over quota |
| `:overage-allowed-with-exhausted-quota?` | boolean | Whether overage is allowed when quota is exhausted |
| `:reset-date` | string (optional) | ISO 8601 date when quota resets |

#### `state`

```clojure
(copilot/state client)
```

Get current connection state: `:disconnected` | `:connecting` | `:connected` | `:error`

#### `notifications`

```clojure
(copilot/notifications client)
```

Get a channel that receives non-session notifications. The channel is buffered; notifications are dropped if it fills.

#### `on-lifecycle-event`

```clojure
;; Subscribe to all lifecycle events
(def unsub (copilot/on-lifecycle-event client
             (fn [event]
               (println (:lifecycle-event-type event) (:session-id event)))))

;; Subscribe to a specific event type
(def unsub (copilot/on-lifecycle-event client :session.created
             (fn [event]
               (println "New session:" (:session-id event)))))

;; Unsubscribe
(unsub)
```

Subscribe to session lifecycle events dispatched by the CLI server. The handler receives an event map with:

| Key | Type | Description |
|-----|------|-------------|
| `:lifecycle-event-type` | keyword | One of `:session.created`, `:session.deleted`, `:session.updated`, `:session.foreground`, `:session.background` |
| `:session-id` | string | The session ID |
| `:metadata` | map (optional) | Contains `:start-time`, `:modified-time`, and optionally `:summary` |

**Two arities:**
- `(on-lifecycle-event client handler)` — wildcard, receives all lifecycle events
- `(on-lifecycle-event client event-type handler)` — receives only events matching `event-type`

Returns an unsubscribe function. Call it with no arguments to remove the handler.

Handlers are called synchronously on the notification router's go-loop. Keep handlers fast; offload heavy work to another thread or channel.

#### `list-sessions`

```clojure
(copilot/list-sessions client)
(copilot/list-sessions client {:repository "owner/repo" :branch "main"})
```

List available sessions. Pass an optional filter map to narrow results by context fields.

**Filter options:**

| Key | Type | Description |
|-----|------|-------------|
| `:cwd` | string | Filter by working directory |
| `:git-root` | string | Filter by git repository root |
| `:repository` | string | Filter by repository (e.g., `"owner/repo"`) |
| `:branch` | string | Filter by branch name |

Returns a vector of session metadata maps with `:start-time` and `:modified-time` as `java.time.Instant`. Sessions may include a `:context` map with the session's working directory and repository info.

```clojure
(copilot/list-sessions client)
;; => [{:session-id "abc-123"
;;      :start-time #inst "2025-01-15T10:00:00Z"
;;      :modified-time #inst "2025-01-15T10:05:00Z"
;;      :summary "Refactoring auth module"
;;      :remote? false
;;      :context {:cwd "/home/user/project"
;;                :git-root "/home/user/project"
;;                :repository "owner/repo"
;;                :branch "main"}}
;;     ...]
```

#### `delete-session!`

```clojure
(copilot/delete-session! client session-id)
```

Delete a session and its data from disk.

#### `get-last-session-id`

```clojure
(copilot/get-last-session-id client)
```

Get the ID of the most recently updated session.

#### `get-foreground-session-id`

```clojure
(copilot/get-foreground-session-id client)
```

Get the foreground session ID. Returns the session ID or nil. Only applicable in TUI+server mode.

#### `set-foreground-session-id!`

```clojure
(copilot/set-foreground-session-id! client session-id)
```

Set the foreground session. Requests the TUI to switch to displaying the specified session. Only applicable in TUI+server mode.

---

## CopilotSession

Represents a single conversation session.

### Methods

#### `send!`

```clojure
(copilot/send! session options)
```

Send a message to the session. Returns immediately with the message ID.

**Options:**

| Key | Type | Description |
|-----|------|-------------|
| `:prompt` | string | The message/prompt to send |
| `:attachments` | vector | File attachments (see below) |
| `:mode` | keyword | `:enqueue` or `:immediate` |

**Attachment types:**

| Type | Required Keys | Optional Keys | Description |
|------|--------------|---------------|-------------|
| `:file` | `:type`, `:path` | `:display-name` | File attachment |
| `:directory` | `:type`, `:path` | `:display-name` | Directory attachment |
| `:selection` | `:type`, `:file-path`, `:display-name` | `:selection-range`, `:text` | Code selection attachment |

Selection range is a map with `:start` and `:end` positions, each containing `:line` and `:character`:

```clojure
(copilot/send! session
  {:prompt "Explain this code"
   :attachments [{:type :selection
                  :file-path "/path/to/file.clj"
                  :display-name "my-fn"
                  :selection-range {:start {:line 10 :character 0}
                                   :end {:line 25 :character 0}}
                  :text "(defn my-fn [...] ...)"}]})
```

#### `send-and-wait!`

```clojure
(copilot/send-and-wait! session options)
(copilot/send-and-wait! session options timeout-ms)
```
Send a message and block until the session becomes idle. Returns the final assistant message event.
Default timeout is `300000` ms (5 minutes).

#### `send-async`

```clojure
(copilot/send-async session options)
```

Send a message and return a core.async channel that receives all events for this message, closing when idle.
Supports `:timeout-ms` in options (default: `300000`) to force cleanup on long-running requests.

#### `send-async-with-id`

```clojure
(copilot/send-async-with-id session options)
```

Send a message and return `{:message-id :events-ch}` for correlating responses.
Supports `:timeout-ms` in options (default: `300000`).

#### `<send!`

```clojure
(copilot/<send! session options)
```

Async equivalent of `send-and-wait!` for use inside `go` blocks. Returns a channel that yields the final content string.
Supports `:timeout-ms` in options (default: `300000`).

#### `events`

```clojure
(copilot/events session)
```

Get the core.async `mult` for session events. Use `tap` to subscribe:

```clojure
(let [ch (chan 100)]
  (tap (copilot/events session) ch)
  (go-loop []
    (when-let [event (<! ch)]
      (println event)
      (recur))))
```

#### `events->chan`

```clojure
(copilot/events->chan session {:buffer 256
                               :xf (filter #(= :copilot/assistant.message (:type %)))})
```

Subscribe to session events with optional buffer size and transducer.

#### `subscribe-events`

```clojure
(copilot/subscribe-events session)
```

Subscribe to session events. Returns a channel (buffer size 1024) that receives events.
This is a convenience wrapper around `(tap (copilot/events session) ch)`.

##### Event Drop Behavior

Session events are delivered via core.async `mult`. When `mult` receives an event, it
attempts to `put!` to each subscriber's channel. **If a subscriber's buffer is full at
that moment, that specific event is silently dropped for that subscriber only.**

Key points:
- **Per-subscriber**: Each subscriber is independent. If subscriber A's buffer is full
  but B has space, only A misses the event.
- **Per-event**: Only the event that arrived when the buffer was full is dropped.
  Earlier events already in the buffer are not affected.
- **Silent**: No error, warning, or indication that a drop occurred.
- **Not recoverable**: The dropped event is gone for that subscriber.

With the default 1024 buffer, drops are unlikely unless a subscriber completely stops
reading. For most use cases, this is not a concern.

#### `unsubscribe-events`

```clojure
(copilot/unsubscribe-events session ch)
```

Unsubscribe a channel from session events.

#### `abort!`

```clojure
(copilot/abort! session)
```

Abort the currently processing message.

#### `get-messages`

```clojure
(copilot/get-messages session)
```

Get all events/messages from this session.

#### `get-current-model`

```clojure
(copilot/get-current-model session)
;; => "gpt-5.2"
```

Get the current model for this session. Returns the model ID string, or nil if none set.

#### `switch-model!`

```clojure
(copilot/switch-model! session "claude-sonnet-4.5")
;; => "claude-sonnet-4.5"
```

Switch the model for this session mid-conversation. Returns the new model ID string, or nil.

```clojure
(copilot/with-client-session [session {:model "gpt-5.2"}]
  (println "Before:" (copilot/get-current-model session))
  (copilot/switch-model! session "claude-sonnet-4.5")
  (println "After:" (copilot/get-current-model session)))
;; prints:
;; Before: gpt-5.2
;; After: claude-sonnet-4.5
```

#### `destroy!`

```clojure
(copilot/destroy! session)
```

Destroy the session and free resources.

#### `session-id`

```clojure
(copilot/session-id session)
```

Get the session's unique identifier.

#### `workspace-path`

```clojure
(copilot/workspace-path session)
```

Get the session workspace path when provided by the CLI (may be nil).

#### `session-config`

```clojure
(copilot/session-config session)
;; => {:model "gpt-5.2", :streaming? true, :reasoning-effort "high", ...}
```

Get the configuration that was used to create this session.

#### `client`

```clojure
(copilot/client session)
```

Get the client that owns this session.

---

## Event Types

Sessions emit various events during processing. All event types are namespaced keywords prefixed with `copilot/`.

### Exported Constants

```clojure
;; All event types
copilot/event-types
;; => #{:copilot/session.idle :copilot/assistant.message ...}

;; Session lifecycle events
copilot/session-events
;; => #{:copilot/session.start :copilot/session.idle ...}

;; Assistant response events  
copilot/assistant-events
;; => #{:copilot/assistant.message :copilot/assistant.message_delta ...}

;; Tool execution events
copilot/tool-events
;; => #{:copilot/tool.execution_start :copilot/tool.execution_complete ...}
```

### Event Reference

| Event Type | Description |
|------------|-------------|
| `:copilot/session.start` | Session created |
| `:copilot/session.resume` | Session resumed |
| `:copilot/session.error` | Session error occurred |
| `:copilot/session.idle` | Session finished processing |
| `:copilot/session.info` | Informational session update |
| `:copilot/session.model_change` | Session model changed |
| `:copilot/session.handoff` | Session handed off to another agent |
| `:copilot/session.usage_info` | Token usage information |
| `:copilot/session.context_changed` | Session context (cwd, repo, branch) changed |
| `:copilot/session.title_changed` | Session title updated |
| `:copilot/session.warning` | Session warning (e.g., quota limits) |
| `:copilot/session.shutdown` | Session is shutting down |
| `:copilot/session.truncation` | Context window truncated |
| `:copilot/session.snapshot_rewind` | Session state rolled back |
| `:copilot/session.compaction_start` | Context compaction started (infinite sessions) |
| `:copilot/session.compaction_complete` | Context compaction completed (infinite sessions) |
| `:copilot/skill.invoked` | Skill invocation triggered |
| `:copilot/user.message` | User message added |
| `:copilot/pending_messages.modified` | Pending message queue updated |
| `:copilot/assistant.turn_start` | Assistant turn started |
| `:copilot/assistant.intent` | Assistant intent update |
| `:copilot/assistant.reasoning` | Model reasoning (if supported) |
| `:copilot/assistant.reasoning_delta` | Streaming reasoning chunk |
| `:copilot/assistant.message` | Complete assistant response |
| `:copilot/assistant.message_delta` | Streaming response chunk |
| `:copilot/assistant.turn_end` | Assistant turn completed |
| `:copilot/assistant.usage` | Token usage for this turn |
| `:copilot/abort` | Current message aborted |
| `:copilot/tool.user_requested` | Tool execution requested by user |
| `:copilot/tool.execution_start` | Tool execution started |
| `:copilot/tool.execution_progress` | Tool execution progress update |
| `:copilot/tool.execution_partial_result` | Tool execution partial result |
| `:copilot/tool.execution_complete` | Tool execution completed |
| `:copilot/subagent.started` | Subagent started |
| `:copilot/subagent.completed` | Subagent completed |
| `:copilot/subagent.failed` | Subagent failed |
| `:copilot/subagent.selected` | Subagent selected |
| `:copilot/hook.start` | Hook invocation started |
| `:copilot/hook.end` | Hook invocation finished |
| `:copilot/system.message` | System message emitted |

### Example: Handling Events

```clojure
(copilot/with-client-session [session {:streaming? true}]
  (let [ch (chan 256)]
    (tap (copilot/events session) ch)
    (go-loop []
      (when-let [event (<! ch)]
        (case (:type event)
          :copilot/assistant.message_delta
          (print (get-in event [:data :delta-content]))
          
          :copilot/session.usage_info
          (println "Tokens:" (get-in event [:data :current-tokens]))
          
          :copilot/session.idle
          (println "\nDone!")
          
          nil)
        (recur)))
    (copilot/send! session {:prompt "Hello"})))
```

---

## Streaming

Enable streaming to receive assistant response chunks as they're generated:

```clojure
(def session (copilot/create-session client
               {:model "gpt-5.2"
                :streaming? true}))

(let [ch (chan 100)]
  (tap (copilot/events session) ch)
  (go-loop []
    (when-let [event (<! ch)]
      (case (:type event)
        :copilot/assistant.message_delta
          ;; Streaming chunk - print incrementally
          (print (get-in event [:data :delta-content]))

        :copilot/assistant.reasoning_delta
          ;; Streaming reasoning (model-dependent). Send to stderr.
          (binding [*out* *err*]
            (print (get-in event [:data :delta-content])))

        :copilot/assistant.reasoning
          (binding [*out* *err*]
            (println "\n--- Final Reasoning ---")
            (println (get-in event [:data :content])))

        :copilot/assistant.message
          ;; Final complete message
          (println "\n--- Final ---")
          (println (get-in event [:data :content]))

        nil)
      (recur))))

(copilot/send! session {:prompt "Solve a logic puzzle and show your reasoning."})
```

When `:streaming? true`:
- `:copilot/assistant.message_delta` events contain incremental text in `:delta-content`
- `:copilot/assistant.reasoning_delta` events contain incremental reasoning in `:delta-content` (model-dependent)
- Accumulate delta values to build the full response progressively
- The final `:copilot/assistant.message` event always contains the complete content

---

## Advanced Usage

### Manual Server Control

```clojure
(def client (copilot/client {:auto-start? false}))

;; Start manually
(copilot/start! client)

;; Use client...

;; Stop manually
(copilot/stop! client)
```

### Tools

Let the CLI call back into your process when the model needs capabilities you provide:

```clojure
(def lookup-tool
  (copilot/define-tool "lookup_issue"
    {:description "Fetch issue details from our tracker"
     :parameters {:type "object"
                  :properties {:id {:type "string"
                                    :description "Issue identifier"}}
                  :required ["id"]}
     :handler (fn [{:keys [id]} invocation]
                (let [issue (fetch-issue id)]
                  (copilot/result-success issue)))}))

(def session (copilot/create-session client
               {:model "gpt-5.2"
                :tools [lookup-tool]}))
```

When Copilot invokes `lookup_issue`, the SDK automatically runs your handler and responds to the CLI.

**Handler return values:**

| Return Type | Description |
|-------------|-------------|
| String | Automatically wrapped as success result |
| Map with `:result-type` | Full control over result metadata |
| core.async channel | Async result (yields string or map) |

**Result helpers:**

```clojure
(copilot/result-success "It worked!")
(copilot/result-failure "It failed" "error details")
(copilot/result-denied "Permission denied")
(copilot/result-rejected "Invalid parameters")
```

### System Message Customization

Control the system prompt:

```clojure
(def session (copilot/create-session client
               {:model "gpt-5.2"
                :system-message
                  {:content "
<workflow_rules>
- Always check for security vulnerabilities
- Suggest performance improvements when applicable
</workflow_rules>
"}}))
```

The SDK auto-injects environment context, tool instructions, and security guardrails. Your `:content` is appended after SDK-managed sections.

For full control (removes all guardrails), use `:mode :replace`:

```clojure
(copilot/create-session client
  {:model "gpt-5.2"
   :system-message {:mode :replace
                    :content "You are a helpful assistant."}})
```

### Config Directory and Skills

`config-dir` overrides where the CLI reads its config and state (e.g., `~/.copilot`).
It does not define custom agents. Custom agents are provided via `:custom-agents`.

```clojure
(def session (copilot/create-session client
               {:model "gpt-5.2"
                :config-dir "/tmp/copilot-config"
                :skill-directories ["/path/to/skills" "/opt/team-skills"]
                :disabled-skills ["legacy-skill" "experimental-skill"]}))
```

### Large Tool Output Handling (Experimental)

> **Note:** This is a CLI protocol feature not exposed in the official `@github/copilot-sdk`.
> The `outputDir` and `maxSizeBytes` settings may be ignored by some CLI versions due to
> a known issue where session-level config is not applied during `session.send` execution.
> The CLI's default behavior (30KB threshold, system tmpdir) applies regardless.

Configure how large tool outputs are handled before being sent back to the model:

```clojure
(def session (copilot/create-session client
               {:model "gpt-5.2"
                :large-output {:enabled true
                               :max-size-bytes 65536
                               :output-dir "/tmp/copilot-tool-output"}}))
```

When a tool output exceeds the configured size, the CLI writes the full output to a temp file,
and the tool result delivered to the model contains a short message with the file path and preview.
You can see this message in `:tool.execution_complete` events:

```clojure
(let [events (copilot/subscribe-events session)]
  (go-loop []
    (when-let [event (<! events)]
        (when (= :tool.execution_complete (:type event))
          (when-let [content (get-in event [:data :result :content])]
            (println "Tool output message:\n" content)))
      (recur))))
```

Note: large output handling is applied by the CLI for built-in tools (like the shell tool).
For external tools you define in the SDK, consider handling oversized outputs yourself
(e.g., write to a file and return a short preview).

### Infinite Sessions

Infinite sessions enable automatic context compaction, allowing conversations to continue
beyond the model's context window limit. When the context approaches capacity, the CLI
automatically compacts older messages while preserving important context.

```clojure
;; Enable with defaults (enabled by default)
(def session (copilot/create-session client
               {:model "gpt-5.2"}))

;; Explicit configuration
(def session (copilot/create-session client
               {:model "gpt-5.2"
                :infinite-sessions {:enabled true
                                    :background-compaction-threshold 0.80
                                    :buffer-exhaustion-threshold 0.95}}))

;; Disable infinite sessions
(def session (copilot/create-session client
               {:model "gpt-5.2"
                :infinite-sessions {:enabled false}}))
```

**Configuration options:**

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `:enabled` | boolean | `true` | Enable infinite sessions |
| `:background-compaction-threshold` | number | `0.80` | Context utilization (0.0-1.0) at which background compaction starts |
| `:buffer-exhaustion-threshold` | number | `0.95` | Context utilization (0.0-1.0) at which session blocks until compaction completes |

**How it works:**

1. When context reaches the background threshold (default 80%), compaction starts asynchronously
2. The session continues processing while compaction runs in the background
3. If context reaches the buffer exhaustion threshold (default 95%), the session blocks until compaction completes
4. Compaction preserves essential context while removing older, less relevant messages

**Compaction events:**

Sessions emit `:session.compaction_start` and `:session.compaction_complete` events during compaction:

```clojure
(let [ch (copilot/subscribe-events session)]
  (go-loop []
    (when-let [event (<! ch)]
      (case (:type event)
        :session.compaction_start
        (println "Compaction started...")

        :session.compaction_complete
        (println "Compaction complete")

        nil)
      (recur))))
```

### Permission Handling

When the CLI needs approval (e.g., shell or file write), it sends a JSON-RPC
`permission.request` to the SDK. Your `:on-permission-request` callback must
return a map compatible with the permission result payload; the SDK wraps this
into the JSON-RPC response as `{:result <your-map>}`:

The `permission_bash.clj` example demonstrates both an allowed and a denied
shell command and prints the full permission request payload so you can inspect
fields like `:full-command-text`, `:commands`, and `:possible-paths`.

```clojure
;; Approve
{:kind :approved}

;; Deny with rules
{:kind :denied-by-rules
 :rules [{:kind "shell" :argument "echo hi"}]}

;; Deny without interactive approval
{:kind :denied-no-approval-rule-and-could-not-request-from-user}

;; Deny after user interaction (optional feedback)
{:kind :denied-interactively-by-user :feedback "Not allowed"}
```

### User Input Handling

When the agent needs input from the user (via `ask_user` tool), the `:on-user-input-request`
handler is called. Return a response map with the user's input:

```clojure
(def session (copilot/create-session client
               {:model "gpt-5.2"
                :on-user-input-request
                (fn [request invocation]
                  ;; request contains {:question "..." :choices [...] :allow-freeform true/false}
                  (println "Agent asks:" (:question request))
                  (when-let [choices (:choices request)]
                    (println "Choices:" choices))
                  ;; Return user's response
                  ;; :answer is required, :was-freeform defaults to true
                  {:answer (read-line)
                   :was-freeform true})}))
```

The request map includes:
- `:question` - The question being asked
- `:choices` - Optional list of choices for multiple choice questions
- `:allow-freeform` - Whether freeform text input is allowed

The response map should include:
- `:answer` - The user's answer (string, required). `:response` is also accepted for convenience.
- `:was-freeform` - Whether the answer was freeform (boolean, defaults to true)

### Session Hooks

Lifecycle hooks allow custom logic at various points during the session:

```clojure
(def session (copilot/create-session client
               {:model "gpt-5.2"
                :hooks
                {:on-pre-tool-use
                 (fn [input invocation]
                   ;; Called before each tool execution
                   ;; input contains {:tool-name "..." :arguments {...}}
                   (println "About to use tool:" (:tool-name input))
                   ;; Return nil to proceed, or a modified input map
                   nil)

                 :on-post-tool-use
                 (fn [input invocation]
                   ;; Called after each tool execution
                   ;; input contains {:tool-name "..." :result {...}}
                   (println "Tool completed:" (:tool-name input))
                   nil)

                 :on-user-prompt-submitted
                 (fn [input invocation]
                   ;; Called when user sends a prompt
                   (println "User prompt:" (:prompt input))
                   nil)

                 :on-session-start
                 (fn [input invocation]
                   (println "Session started")
                   nil)

                 :on-session-end
                 (fn [input invocation]
                   (println "Session ended")
                   nil)

                 :on-error-occurred
                 (fn [input invocation]
                   (println "Error:" (:error input))
                   nil)}}))
```

All hooks receive an `input` map (contents vary by hook type) and an `invocation` map
containing `{:session-id ...}`. Hooks may return `nil` to proceed normally, or in some
cases return a modified value.

### Reasoning Effort

For models that support reasoning (like o1), you can control the reasoning effort level:

```clojure
;; Check model capabilities
(let [models (copilot/list-models client)]
  (doseq [m models
          :when (:supports-reasoning-effort m)]
    (println (:name m) "supports reasoning:"
             (:supported-reasoning-efforts m)
             "default:" (:default-reasoning-effort m))))

;; Create session with reasoning effort
(def session (copilot/create-session client
               {:model "o1"
                :reasoning-effort "high"})) ; "low", "medium", "high", or "xhigh"
```

### Multiple Sessions

```clojure
(def session1 (copilot/create-session client {:model "gpt-5.2"}))
(def session2 (copilot/create-session client {:model "claude-sonnet-4.5"}))

;; Both sessions are independent
(copilot/send-and-wait! session1 {:prompt "Hello from session 1"})
(copilot/send-and-wait! session2 {:prompt "Hello from session 2"})
```

### File Attachments

```clojure
;; File attachment
(copilot/send! session
  {:prompt "Analyze this file"
   :attachments [{:type :file
                  :path "/path/to/file.clj"
                  :display-name "My File"}]})

;; Selection attachment (code range)
(copilot/send! session
  {:prompt "What does this function do?"
   :attachments [{:type :selection
                  :file-path "/path/to/file.clj"
                  :display-name "my-function"
                  :selection-range {:start {:line 10 :character 0}
                                   :end {:line 25 :character 0}}
                  :text "(defn my-function [...] ...)"}]})
```

### Connecting to External Server

```clojure
;; Connect to an existing CLI server (no process spawned)
(def client (copilot/client {:cli-url "localhost:8080"}))
(copilot/start! client)
```

---

## Error Handling

```clojure
(try
  (let [session (copilot/create-session client)]
    (copilot/send! session {:prompt "Hello"}))
  (catch Exception e
    (println "Error:" (ex-message e))))
```
