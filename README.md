# copilot-sdk-clojure

Clojure SDK for programmatic control of GitHub Copilot CLI via JSON-RPC.

> **Note:** This SDK is in technical preview and may change in breaking ways.

**Java users:** See [README-java.md](./README-java.md) for the Java API documentation.

## Installation

Add to your `deps.edn`:

```clojure
;; From Clojars
net.clojars.krukow/copilot-sdk {:mvn/version "0.1.2-SNAPSHOT"}

;; Or from Maven Central
io.github.krukow/copilot-sdk {:mvn/version "0.1.2-SNAPSHOT"}

;; Or git dependency
io.github.krukow/copilot-sdk {:git/url "https://github.com/krukow/copilot-sdk-clojure.git"
                              :git/sha "24ea222a4b52c932c8e213f065624b84b28dd5b9"}
```

Update the SHA automatically:

```bash
bb readme:sha
```

## Quick Start

```clojure
(require '[krukow.copilot-sdk :as copilot])

;; Create and start client
(def client (copilot/client {:log-level :info}))
(copilot/start! client)

;; Create a session
(def session (copilot/create-session client {:model "gpt-5.2"}))

;; Wait for response using events
(require '[clojure.core.async :refer [chan tap go-loop <!]])

(let [events-ch (chan 100)
      done (promise)]
  (tap (copilot/events session) events-ch)
  (go-loop []
    (when-let [event (<! events-ch)]
      (case (:type event)
        :assistant.message (println (get-in event [:data :content]))
        :session.idle (deliver done true)
        nil)
      (recur)))

  ;; Send a message and wait for completion
  (copilot/send! session {:prompt "What is 2+2?"})
  @done)

;; Clean up
(copilot/destroy! session)
(copilot/stop! client)
```

Or use the simpler blocking API:

```clojure
;; Send and wait for response in one call
(def response (copilot/send-and-wait! session {:prompt "What is 2+2?"}))
(println (get-in response [:data :content]))
;; => "4"
```

## API Reference

### CopilotClient

#### Constructor

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

#### Methods

##### `start!`

```clojure
(copilot/start! client)
```

Start the CLI server and establish connection. Blocks until connected.

##### `with-client`

```clojure
(copilot/with-client [client {:log-level :info}]
  ;; use client
  )
```

Create a client, start it, and ensure `stop!` runs on exit.

##### `stop!`

```clojure
(copilot/stop! client)
```

Stop the server and close all sessions gracefully.

##### `force-stop!`

```clojure
(copilot/force-stop! client)
```

Force stop the CLI server without graceful cleanup. Use when `stop!` takes too long.

##### `create-session`

```clojure
(copilot/create-session client config)
```

Create a new conversation session.

##### `with-session`

```clojure
(copilot/with-session [session client {:model "gpt-5.2"}]
  ;; use session
  )
```

Create a session and ensure `destroy!` runs on exit.

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

##### `resume-session`

```clojure
(copilot/resume-session client session-id)
(copilot/resume-session client session-id config)
```

Resume an existing session by ID.

##### `ping`

```clojure
(copilot/ping client)
(copilot/ping client message)
```

Ping the server to check connectivity. Returns `{:message "..." :timestamp ... :protocol-version ...}`.

##### `get-status`

```clojure
(copilot/get-status client)
```

Get CLI status including version and protocol information. Returns `{:version "0.0.389" :protocol-version 2}`.

##### `get-auth-status`

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

##### `list-models`

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

##### `state`

```clojure
(copilot/state client)
```

Get current connection state: `:disconnected` | `:connecting` | `:connected` | `:error`

##### `notifications`

```clojure
(copilot/notifications client)
```

Get a channel that receives non-session notifications. The channel is buffered; notifications are dropped if it fills.

##### `list-sessions`

```clojure
(copilot/list-sessions client)
```

List all available sessions. Returns vector of session metadata with
`:start-time` and `:modified-time` as `java.time.Instant`.

##### `delete-session!`

```clojure
(copilot/delete-session! client session-id)
```

Delete a session and its data from disk.

---

### CopilotSession

Represents a single conversation session.

#### Methods

##### `send!`

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

##### `send-and-wait!`

```clojure
(copilot/send-and-wait! session options)
(copilot/send-and-wait! session options timeout-ms)
```
Send a message and block until the session becomes idle. Returns the final assistant message event.
Default timeout is `300000` ms (5 minutes).

##### `send-async`

```clojure
(copilot/send-async session options)
```

Send a message and return a core.async channel that receives all events for this message, closing when idle.
Supports `:timeout-ms` in options (default: `300000`) to force cleanup on long-running requests.

##### `send-async-with-id`

```clojure
(copilot/send-async-with-id session options)
```

Send a message and return `{:message-id :events-ch}` for correlating responses.
Supports `:timeout-ms` in options (default: `300000`).

##### `<send!`

```clojure
(copilot/<send! session options)
```

Async equivalent of `send-and-wait!` for use inside `go` blocks. Returns a channel that yields the final content string.
Supports `:timeout-ms` in options (default: `300000`).

##### `events`

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

##### `events->chan`

```clojure
(copilot/events->chan session {:buffer 256
                               :xf (filter #(= :assistant.message (:type %)))})
```

Subscribe to session events with optional buffer size and transducer.
Note: session events use a sliding buffer (4096). Slow consumers may drop events.

##### `abort!`

```clojure
(copilot/abort! session)
```

Abort the currently processing message.

##### `get-messages`

```clojure
(copilot/get-messages session)
```

Get all events/messages from this session.

##### `destroy!`

```clojure
(copilot/destroy! session)
```

Destroy the session and free resources.

##### `session-id`

```clojure
(copilot/session-id session)
```

Get the session's unique identifier.

##### `client`

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

## Error Handling

```clojure
(try
  (let [session (copilot/create-session client)]
    (copilot/send! session {:prompt "Hello"}))
  (catch Exception e
    (println "Error:" (ex-message e))))
```

## Examples

See the [`examples/`](./examples/) directory for complete working examples:

| Example | Difficulty | Description |
|---------|------------|-------------|
| [`basic_chat.clj`](./examples/basic_chat.clj) | Beginner | Simple Q&A conversation with multi-turn context |
| [`tool_integration.clj`](./examples/tool_integration.clj) | Intermediate | Custom tools that the LLM can invoke |
| [`multi_agent.clj`](./examples/multi_agent.clj) | Advanced | Multi-agent orchestration with core.async |
| [`streaming_chat.clj`](./examples/streaming_chat.clj) | Intermediate | Streaming deltas with incremental output |
| [`config_skill_output.clj`](./examples/config_skill_output.clj) | Intermediate | Config dir, skills, and large output settings |
| [`permission_bash.clj`](./examples/permission_bash.clj) | Intermediate | Permission handling with bash |

Run examples:

```bash
clojure -A:examples -M -m basic-chat
clojure -A:examples -M -m tool-integration
clojure -A:examples -M -m multi-agent
clojure -A:examples -M -m streaming-chat
clojure -A:examples -M -m config-skill-output
clojure -A:examples -M -m permission-bash
```

See [`examples/README.md`](./examples/README.md) for detailed walkthroughs and explanations.

## Architecture

The SDK communicates with the Copilot CLI server via JSON-RPC:

```
Your Application
       ↓
  Clojure SDK
       ↓ JSON-RPC (stdio or TCP)
  Copilot CLI (server mode)
       ↓
  GitHub Copilot API
```

The SDK manages the CLI process lifecycle automatically. You can also connect to an external CLI server via the `:cli-url` option.

## Comparison with JavaScript SDK

This Clojure SDK provides equivalent functionality to the [official JavaScript SDK](https://github.com/github/copilot-sdk/tree/main/nodejs), with idiomatic Clojure patterns:

| Feature | JavaScript | Clojure |
|---------|------------|---------|
| Async model | Promises/async-await | core.async channels |
| Event handling | Callback functions | core.async mult/tap |
| Tool schemas | Zod or JSON Schema | JSON Schema (maps) |
| Blocking calls | `await sendAndWait()` | `send-and-wait!` |
| Non-blocking | `send()` + events | `send!` + `events` mult |

### Quick Comparison

**JavaScript:**
```typescript
import { CopilotClient, defineTool } from "@github/copilot-sdk";
import { z } from "zod";

const client = new CopilotClient();
await client.start();

const session = await client.createSession({
  model: "gpt-5.2",
  tools: [
    defineTool("greet", {
      description: "Greet someone",
      parameters: z.object({ name: z.string() }),
      handler: async ({ name }) => `Hello, ${name}!`
    })
  ]
});

session.on((event) => {
  if (event.type === "assistant.message") {
    console.log(event.data.content);
  }
});

await session.sendAndWait({ prompt: "Greet Alice" });
await session.destroy();
await client.stop();
```

**Clojure:**
```clojure
(require '[krukow.copilot-sdk :as copilot])
(require '[clojure.core.async :refer [chan tap go-loop <!]])

(def client (copilot/client {}))
(copilot/start! client)

(def greet-tool
  (copilot/define-tool "greet"
    {:description "Greet someone"
     :parameters {:type "object"
                  :properties {:name {:type "string"}}
                  :required ["name"]}
     :handler (fn [{:keys [name]} _]
                (str "Hello, " name "!"))}))

(def session (copilot/create-session client
               {:model "gpt-5.2"
                :tools [greet-tool]}))

(let [ch (chan 100)]
  (tap (copilot/events session) ch)
  (go-loop []
    (when-let [event (<! ch)]
      (when (= (:type event) :assistant.message)
        (println (get-in event [:data :content])))
      (recur))))

(copilot/send-and-wait! session {:prompt "Greet Alice"})
(copilot/destroy! session)
(copilot/stop! client)
```

## Development

```bash
# Run tests (unit + integration + example validation)
bb test

# Run tests with E2E (requires Copilot CLI)
COPILOT_E2E_TESTS=true COPILOT_CLI_PATH=/path/to/copilot bb test

# Generate API docs
bb docs

# Build JAR
bb ci

# Install locally
bb install
```

API documentation is generated to `doc/api/`.

### Publishing to Clojars

Set credentials:

```bash
export CLOJARS_USERNAME=your-username
export CLOJARS_PASSWORD=your-token
```

Then publish:

```bash
bb jar
bb deploy:clojars
```

## Testing

The test suite includes unit, integration, example, and E2E tests (E2E disabled by default).

To enable E2E tests:

```bash
export COPILOT_E2E_TESTS=true
export COPILOT_CLI_PATH=/path/to/copilot  # Optional, defaults to "copilot"
bb test
```

## Requirements

- Clojure 1.12+
- JVM 11+
- GitHub Copilot CLI installed and in PATH (or provide custom `:cli-path`)

## Related Projects

- [copilot-sdk](https://github.com/github/copilot-sdk) - Official SDKs (Node.js, Python, Go, .NET)
- [Copilot CLI](https://docs.github.com/en/copilot/how-tos/set-up/install-copilot-cli) - The CLI server this SDK controls

## License

Copyright © 2026 Krukow

Distributed under the MIT License.
