# API Reference

## Helpers API

The helpers namespace provides simple, stateless query functions with automatic client management.

```clojure
(require '[krukow.copilot-sdk.helpers :as h])
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

### `query-seq`

```clojure
(h/query-seq prompt & {:keys [client session]})
```

Execute a query and return a lazy sequence of events.

```clojure
(->> (h/query-seq "Tell me a story" :session {:streaming? true})
     (filter #(= :assistant.message_delta (:type %)))
     (map #(get-in % [:data :delta-content]))
     (run! print))
```

### `query-seq!`

```clojure
(h/query-seq! prompt & {:keys [client session max-events]})
```

Like `query-seq` but with guaranteed cleanup and bounded consumption (default: 256 events).

### `query-chan`

```clojure
(h/query-chan prompt & {:keys [client session buffer]})
```

Execute a query and return a core.async channel of events.

```clojure
(let [ch (h/query-chan "Tell me a story" :session {:streaming? true})]
  (go-loop []
    (when-let [event (<! ch)]
      (when (= :assistant.message_delta (:type event))
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
(require '[krukow.copilot-sdk :as copilot])
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
;; Simple form - just session binding
(copilot/with-client-session [session {:model "gpt-5.2"}]
  ;; use session
  )

;; Full form - both client and session bindings
(copilot/with-client-session [client session {:model "gpt-5.2"} {:log-level :info}]
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
| `:provider` | map | Provider config for BYOK |
| `:mcp-servers` | map | MCP server configs keyed by server ID |
| `:custom-agents` | vector | Custom agent configs |
| `:on-permission-request` | fn | Permission handler function |
| `:streaming?` | boolean | Enable streaming deltas |
| `:config-dir` | string | Override config directory for CLI |
| `:skill-directories` | vector | Additional skill directories to load |
| `:disabled-skills` | vector | Disable specific skills by name |
| `:large-output` | map | Tool output handling config |

#### `resume-session`

```clojure
(copilot/resume-session client session-id)
(copilot/resume-session client session-id config)
```

Resume an existing session by ID.

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

List available models with their metadata. Requires authentication. Returns a vector of model info maps:
```clojure
[{:id "gpt-5.2"
  :name "GPT-5.2"
  :vendor "openai"
  :family "gpt-5.2"
  :version "gpt-5.2"
  :max-input-tokens 128000
  :max-output-tokens 16384
  :preview? false
  :vision-limits {:supported-media-types ["image/png" "image/jpeg"]
                  :max-prompt-images 10
                  :max-prompt-image-size 20971520}}
 ...]
```

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

#### `list-sessions`

```clojure
(copilot/list-sessions client)
```

List all available sessions. Returns vector of session metadata with
`:start-time` and `:modified-time` as `java.time.Instant`.

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
| `:attachments` | vector | File attachments `[{:type :file/:directory :path :display-name}]` |
| `:mode` | keyword | `:enqueue` or `:immediate` |

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
                               :xf (filter #(= :assistant.message (:type %)))})
```

Subscribe to session events with optional buffer size and transducer.
Note: session events use a sliding buffer (4096). Slow consumers may drop events.

#### `subscribe-events`

```clojure
(copilot/subscribe-events session)
```

Subscribe to session events. Returns a channel that receives events.
This is a convenience wrapper around `(tap (copilot/events session) ch)`.

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

#### `client`

```clojure
(copilot/client session)
```

Get the client that owns this session.

---

## Event Types

Sessions emit various events during processing:

| Event Type | Description |
|------------|-------------|
| `:user.message` | User message added |
| `:assistant.message` | Complete assistant response |
| `:assistant.message_delta` | Streaming response chunk |
| `:assistant.reasoning` | Model reasoning (if supported) |
| `:assistant.reasoning_delta` | Streaming reasoning chunk |
| `:tool.execution_start` | Tool execution started |
| `:tool.execution_progress` | Tool execution progress update |
| `:tool.execution_partial_result` | Tool execution partial result |
| `:tool.execution_complete` | Tool execution completed |
| `:session.idle` | Session finished processing |

Event `:type` values are keywords derived from the wire strings, e.g.
`"assistant.message_delta"` becomes `:assistant.message_delta`.

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
        :assistant.message_delta
          ;; Streaming chunk - print incrementally
          (print (get-in event [:data :delta-content]))

        :assistant.reasoning_delta
          ;; Streaming reasoning (model-dependent). Send to stderr.
          (binding [*out* *err*]
            (print (get-in event [:data :delta-content])))

        :assistant.reasoning
          (binding [*out* *err*]
            (println "\n--- Final Reasoning ---")
            (println (get-in event [:data :content])))

        :assistant.message
          ;; Final complete message
          (println "\n--- Final ---")
          (println (get-in event [:data :content]))

        nil)
      (recur))))

(copilot/send! session {:prompt "Solve a logic puzzle and show your reasoning."})
```

When `:streaming? true`:
- `:assistant.message_delta` events contain incremental text in `:delta-content`
- `:assistant.reasoning_delta` events contain incremental reasoning in `:delta-content` (model-dependent)
- Accumulate delta values to build the full response progressively
- The final `:assistant.message` event always contains the complete content

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

### Large Tool Output Handling

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
(copilot/send! session
  {:prompt "Analyze this file"
   :attachments [{:type :file
                  :path "/path/to/file.clj"
                  :display-name "My File"}]})
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
