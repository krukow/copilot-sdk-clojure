# copilot-sdk-clojure

Clojure SDK for programmatic control of GitHub Copilot CLI via JSON-RPC.

> **Note:** This SDK is in technical preview and may change in breaking ways.

A fully-featured Clojure port of the official [GitHub Copilot SDK](https://github.com/github/copilot-sdk), designed with idiomatic functional programming patterns. The SDK uses immutable data structures throughout, manages client/session state via Clojure's concurrency primitives (atoms, agents), and leverages [core.async](https://github.com/clojure/core.async) for non-blocking event streams and async operations.

Key features:
- **Blocking and async APIs** — `send-and-wait!` for simple use cases, `send!` + event channels for reactive patterns
- **Custom tools** — Let the LLM call back into your application
- **Streaming** — Incremental response deltas via `:assistant.message_delta` events
- **Multi-session support** — Run multiple independent conversations concurrently
- **Session hooks** — Lifecycle callbacks for pre/post tool use, prompts, errors
- **User input handling** — Handle `ask_user` requests from the agent
- **Authentication options** — GitHub token auth or logged-in user
- **Authentication options** — GitHub token auth or logged-in user

See [`examples/`](./examples/) for working code demonstrating common patterns.

**Java/JVM users:** See [copilot-sdk-java](https://github.com/copilot-community-sdk/copilot-sdk-java) for a native Java SDK.

## Installation

Add to your `deps.edn`:

```clojure
;; From Maven Central
io.github.copilot-community-sdk/copilot-sdk-clojure {:mvn/version "0.1.25.1"}

;; Or git dependency
io.github.copilot-community-sdk/copilot-sdk-clojure {:git/url "https://github.com/copilot-community-sdk/copilot-sdk-clojure.git"
                              :git/sha "614b3f0150acec2b2bd5d54a313af4f089207949"}
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
(h/query "Explain monads in one sentence" :session {:model "claude-sonnet-4.5"})

;; With a system prompt
(h/query "What is Clojure?" :session {:system-prompt "You are a helpful assistant. Be concise."})
```

### More Control

For multi-turn conversations, pass a session instance to `query`:

```clojure
(require '[github.copilot-sdk :as copilot])

(copilot/with-client-session [session {:model "gpt-5.2"}]
  ;; Session maintains context between queries
  (println (h/query "What is the capital of France?" :session session))
  (println (h/query "What is its population?" :session session)))
```

Or use the full API for maximum flexibility:

```clojure
(copilot/with-client-session [session {:model "gpt-5.2"}]
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
  (let [sessions (repeatedly 3 #(copilot/create-session client {}))
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
;; gpt-5.2 x1.0
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
- **Advanced Usage** - Tools, system messages, permissions, multiple sessions

## Examples

See the [`examples/`](./examples/) directory for complete working examples:

| Example | Difficulty | Description |
|---------|------------|-------------|
| [`basic_chat.clj`](./examples/basic_chat.clj) | Beginner | Simple Q&A conversation with multi-turn context |
| [`tool_integration.clj`](./examples/tool_integration.clj) | Intermediate | Custom tools that the LLM can invoke |
| [`multi_agent.clj`](./examples/multi_agent.clj) | Advanced | Multi-agent orchestration with core.async |
| [`config_skill_output.clj`](./examples/config_skill_output.clj) | Intermediate | Config dir, skills, and large output settings |
| [`permission_bash.clj`](./examples/permission_bash.clj) | Intermediate | Permission handling with bash |

Run examples:

```bash
clojure -A:examples -M -m basic-chat
clojure -A:examples -M -m tool-integration
clojure -A:examples -M -m multi-agent
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
