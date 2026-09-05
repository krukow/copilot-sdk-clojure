# copilot-sdk-clojure

Clojure SDK for programmatic control of GitHub Copilot CLI via JSON-RPC.

> **Note:** Version `1.0.0.0` is the first generally available (GA) release (tracking upstream [github/copilot-sdk](https://github.com/github/copilot-sdk) `v1.0.0`). The public API is stable. Subsequent releases follow the upstream versioning scheme (see [Versioning](./PUBLISHING.md#versioning)); any breaking changes are called out in the [CHANGELOG](./CHANGELOG.md).

A fully-featured Clojure port of the official [GitHub Copilot SDK](https://github.com/github/copilot-sdk), designed with idiomatic functional programming patterns. The SDK uses immutable data structures throughout, manages client/session state via Clojure's concurrency primitives (atoms, agents), and leverages [core.async](https://github.com/clojure/core.async) for non-blocking event streams and async operations.

Key features:
- **Blocking and async APIs** — `send-and-wait!` for simple use cases, `send!` + event channels for reactive patterns
- **Custom tools** — Let the LLM call back into your application
- **Streaming** — Incremental response deltas via `:assistant.message_delta` events
- **Multi-session support** — Run multiple independent conversations concurrently
- **[Agent Factories](./doc/guides/agent-factories.md)** *(experimental)* — Durable, resumable multi-agent orchestration via `define-factory` / `run-factory!`
- **Session hooks** — Lifecycle callbacks for pre/post tool use, prompts, errors
- **User input handling** — Handle `ask_user` requests from the agent
- **Event callbacks** — Register `:on-event` handlers to receive all session events
- **Child process mode** — Join existing sessions via `join-session` for extensions
- **Enterprise & workspace policy** — New config options like `:enable-managed-settings?`, `:additional-directories`, and `:disabled-mcp-servers`
- **Authentication options** — GitHub token auth or logged-in user

See [`examples/`](./examples/) for working code demonstrating common patterns.

**Java/JVM users:** See [copilot-sdk-java](https://github.com/copilot-community-sdk/copilot-sdk-java) for a native Java SDK.

## Installation

Add to your `deps.edn`:

```clojure
;; From Maven Central
io.github.copilot-community-sdk/copilot-sdk-clojure {:mvn/version "1.0.13.0"}

;; Or git dependency
io.github.copilot-community-sdk/copilot-sdk-clojure {:git/url "https://github.com/copilot-community-sdk/copilot-sdk-clojure.git"
                              :git/sha "1167909b9c77111951e80cb007ed3411b175ec81"}
```

> **Note:** The Clojars artifact `net.clojars.krukow/copilot-sdk` is deprecated.
> Starting from version `0.1.22.0`, releases are published to Maven Central only.
> Versioning follows the upstream [github/copilot-sdk](https://github.com/github/copilot-sdk/releases) releases.

## Quick Start

The simplest way to use the SDK is with the `query` helper:

```clojure
(require '[github.copilot-sdk.helpers :as h])

;; One-liner query
(h/query "What is 2+2?")
;; => "4"

;; With model selection
(h/query "Explain monads in one sentence" :session {:on-permission-request copilot/approve-all :model "claude-sonnet-4.5"})

;; With a system prompt
(h/query "What is Clojure?" :session {:on-permission-request copilot/approve-all :system-prompt "You are a helpful assistant. Be concise."})
```

### More Control

For multi-turn conversations, pass a session instance to `query`:

```clojure
(require '[github.copilot-sdk :as copilot])

(copilot/with-client-session [session {:on-permission-request copilot/approve-all
                                       :model "claude-haiku-4.5"}]
  ;; Session maintains context between queries
  (println (h/query "What is the capital of France?" :session session))
  (println (h/query "What is its population?" :session session)))
```

Or use the full API for maximum flexibility:

```clojure
(copilot/with-client-session [session {:on-permission-request copilot/approve-all
                                       :model "claude-haiku-4.5"}]
  (println (-> (copilot/send-and-wait! session {:prompt "What is the capital of France?"})
               (get-in [:data :content]))))
```

### Async Example

Use `<send!` with core.async for non-blocking operations:

```clojure
(require '[github.copilot-sdk :as copilot])
(require '[clojure.core.async :refer [<!!]])

(copilot/with-client [client {}]
  ;; Launch multiple requests in parallel
  (let [sessions (repeatedly 3 #(copilot/create-session client {:on-permission-request copilot/approve-all}))
        channels (map #(copilot/<send! %1 {:prompt %2})
                      sessions
                      ["Capital of France?" "Capital of Japan?" "Capital of Brazil?"])]
    ;; Collect results
    (doseq [ch channels]
      (println (<!! ch)))))
```

See [`examples/`](./examples/) for more patterns including streaming, custom tools, and multi-agent orchestration.

### List Available Models

Discover available models and their billing multipliers:

```clojure
(require '[github.copilot-sdk :as copilot])

(copilot/with-client [client]
  (doseq [m (copilot/list-models client)]
    (println (:id m) (str "x" (get-in m [:model-billing :multiplier])))))
;; prints:
;; gpt-5.4 x1.0
;; claude-sonnet-4.5 x1.0
;; o1 x2.0
;; ...
```

## API Reference

See [doc/reference/API.md](./doc/reference/API.md) for the complete API reference, including:

- **CopilotClient** - Client options, lifecycle methods (`start!`, `stop!`, `with-client`)
- **CopilotSession** - Session methods (`send!`, `send-and-wait!`, `<send!`, `events`)
- **Event Types** - All session events (`:assistant.message`, `:assistant.message_delta`, etc.)
- **Streaming** - How to handle incremental responses
- **Advanced Usage** - Tools, system messages, permissions (deny-by-default), multiple sessions

### Track File Changes

Opt into stable file-change capture when creating, resuming, or joining a session:

```clojure
(require '[github.copilot-sdk :as copilot])

(copilot/with-client [client]
  (def tracked-session
    (copilot/create-session
      client
      {:enable-file-change-tracking? true
       :on-permission-request copilot/approve-all}))
  (copilot/disconnect! tracked-session))
```

Observe file-change and snapshot events through the normal event APIs. The
experimental low-level rewind RPCs are intentionally not exposed.

## Examples

See the [`examples/`](./examples/) directory for complete working examples:

| Example | Difficulty | Description |
|---------|------------|-------------|
| [`basic_chat.clj`](./examples/basic_chat.clj) | Beginner | Simple Q&A conversation with multi-turn context |
| [`helpers_query.clj`](./examples/helpers_query.clj) | Beginner | Stateless query API with blocking and streaming modes |
| [`reasoning_effort.clj`](./examples/reasoning_effort.clj) | Beginner | Control reasoning effort level |
| [`tool_integration.clj`](./examples/tool_integration.clj) | Intermediate | Custom tools that the LLM can invoke |
| [`config_skill_output.clj`](./examples/config_skill_output.clj) | Intermediate | Config dir, skills, and large output settings |
| [`permission_bash.clj`](./examples/permission_bash.clj) | Intermediate | Permission handling with bash tool |
| [`session_events.clj`](./examples/session_events.clj) | Intermediate | Monitor session state events and their flow |
| [`session_resume.clj`](./examples/session_resume.clj) | Intermediate | Save and resume sessions by ID |
| [`file_attachments.clj`](./examples/file_attachments.clj) | Intermediate | Send file attachments for analysis |
| [`infinite_sessions.clj`](./examples/infinite_sessions.clj) | Intermediate | Infinite sessions with context compaction |
| [`lifecycle_hooks.clj`](./examples/lifecycle_hooks.clj) | Intermediate | Lifecycle hooks for tool use, prompts, errors |
| [`user_input.clj`](./examples/user_input.clj) | Intermediate | Handle ask_user requests from the agent |
| [`metadata_api.clj`](./examples/metadata_api.clj) | Intermediate | List sessions, tools, and quota |
| [`multi_agent.clj`](./examples/multi_agent.clj) | Advanced | Multi-agent orchestration with core.async |
| [`ask_user_failure.clj`](./examples/ask_user_failure.clj) | Advanced | User cancellation (Esc) with event tracing |
| [`mcp_local_server.clj`](./examples/mcp_local_server.clj) | Advanced | Model Context Protocol server integration |
| [`byok_provider.clj`](./examples/byok_provider.clj) | Advanced | Bring Your Own Key provider configuration |
| [`elicitation_provider.clj`](./examples/elicitation_provider.clj) | Advanced | Custom elicitation provider for UI dialogs |
| [`commands.clj`](./examples/commands.clj) | Intermediate | Register slash commands on sessions |

Run examples:

```bash
clojure -A:examples -M -m basic-chat
clojure -A:examples -M -m helpers-query
clojure -A:examples -M -m tool-integration
clojure -A:examples -M -m session-events
clojure -A:examples -M -m multi-agent
clojure -A:examples -M -m byok-provider
```

See [`examples/README.md`](./examples/README.md) for detailed walkthroughs and explanations.

## Permission Handling

The SDK uses a **deny-by-default** permission model. All tool executions (file
writes, shell commands, URL fetches, MCP tools, etc.) are denied unless your
session config provides an `:on-permission-request` handler (**required** for
`create-session` and `resume-session`; optional for `join-session` which
defaults to `{:kind :no-result}`).

Use `approve-all` to permit everything:

```clojure
(copilot/create-session client {:on-permission-request copilot/approve-all})
```

For fine-grained control, provide a custom handler:

```clojure
(copilot/create-session client
  {:on-permission-request
   (fn [request _ctx]
     (case (keyword (:permission-kind request))
       :shell {:kind :approve-once}
       :read  {:kind :approve-once}
       ;; deny everything else
       {:kind :reject
        :feedback "not permitted"}))})
```

Available permission kinds: `:shell`, `:write`, `:read`, `:url`, `:mcp`,
`:custom-tool`, `:memory`, `:hook`, `:factory` (arrive as strings from the wire; use `keyword`
to match).

See [Permission Handling](./doc/reference/API.md#permission-handling) in the
API Reference and [`permission_bash.clj`](./examples/permission_bash.clj)
for a complete example.

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

### Known limitations vs the official SDK (1.0.0)

The following upstream surface is intentionally **out of scope for 1.0.0 GA**:

- **Canvas authoring API** — the official SDK exposes a canvas authoring/registration
  surface (config fields + session getter). This SDK does not implement the authoring
  API. The related **events** (`session.canvas.opened`, `session.canvas.registry_changed`,
  `session.extensions.attachments_pushed`) **are** observable via the normal event stream.
  Tracked in [#121](https://github.com/copilot-community-sdk/copilot-sdk-clojure/issues/121).
- **Extension launch-provider RPC** — stable extension session fields are supported,
  but the experimental `extensionLaunchProvider.resolve` reverse RPC is not exposed.
  Hosts cannot register a custom extension launcher.
- **Application-owned inference interception** — the upstream experimental
  `CopilotClientOptions.requestHandler` and five-method `llmInference.*`
  lifecycle are intentionally excluded. See the
  [accepted architecture decision](./doc/adr/2026-08-10-host-owned-inference-boundary.md).

### Quick Comparison

**JavaScript:**
```typescript
import { CopilotClient, defineTool } from "@github/copilot-sdk";
import { z } from "zod";

const client = new CopilotClient();
await client.start();

const session = await client.createSession({
  model: "gpt-5.4",
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
await session.disconnect();
await client.stop();
```

**Clojure:**
```clojure
(require '[github.copilot-sdk :as copilot])
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
               {:on-permission-request copilot/approve-all
                :model "claude-haiku-4.5"
                :tools [greet-tool]}))

(let [ch (chan 100)]
  (tap (copilot/events session) ch)
  (go-loop []
    (when-let [event (<! ch)]
      (when (= (:type event) :assistant.message)
        (println (get-in event [:data :content])))
      (recur))))

(copilot/send-and-wait! session {:prompt "Greet Alice"})
(copilot/disconnect! session)
(copilot/stop! client)
```

## Development

```bash
# Run CI (unit/integration tests, doc validation, jar build)
bb ci

# Run full CI including E2E tests and examples (requires copilot CLI)
bb ci:full

# Run tests only
bb test

# Run tests with E2E (requires Copilot CLI)
COPILOT_E2E_TESTS=true bb test

# Generate API docs
bb docs

# Build JAR
bb jar

# Install locally
bb install
```

API documentation is generated to `doc/api/`.

### CI/CD

This project uses GitHub Actions for CI/CD:

| Workflow | Trigger | Description |
|----------|---------|-------------|
| **CI** | Pull requests, push to `main` | Runs `bb ci` (tests, doc validation, jar build) |
| **Release** | Manual dispatch | Version bump, GPG signing, deploy to Maven Central, [build attestation](https://github.com/copilot-community-sdk/copilot-sdk-clojure/attestations) |

Release artifacts include [SLSA build provenance attestations](https://github.com/copilot-community-sdk/copilot-sdk-clojure/attestations) generated by `actions/attest-build-provenance`.

### Publishing to Maven Central

See [PUBLISHING.md](./PUBLISHING.md) for details.

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
