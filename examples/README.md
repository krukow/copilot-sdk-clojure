# Copilot SDK Clojure Examples

This directory contains example applications demonstrating various features of the Copilot SDK for Clojure.

## Prerequisites

1. **Copilot CLI**: Ensure the GitHub Copilot CLI is installed and accessible in your PATH.
   ```bash
   which copilot
   # Or set COPILOT_CLI_PATH to your CLI location
   ```

2. **Dependencies**: The examples use the `:examples` alias from `deps.edn`.

## Running Examples

All examples use Clojure's `-X` invocation, which allows passing parameters directly.

From the project root:

```bash
# Basic Q&A conversation
clojure -A:examples -X basic-chat/run

# With custom questions
clojure -A:examples -X basic-chat/run :q1 '"What is Clojure?"' :q2 '"Who created it?"'

# Simple stateless query (helpers API)
clojure -A:examples -X helpers-query/run
clojure -A:examples -X helpers-query/run :prompt '"Explain recursion briefly."'

# Multiple independent queries
clojure -A:examples -X helpers-query/run-multi
clojure -A:examples -X helpers-query/run-multi :questions '["What is Rust?" "What is Go?"]'

# Streaming output
clojure -A:examples -X helpers-query/run-streaming

# Custom tool integration
clojure -A:examples -X tool-integration/run
clojure -A:examples -X tool-integration/run :languages '["clojure" "haskell"]'

# Multi-agent orchestration
clojure -A:examples -X multi-agent/run
clojure -A:examples -X multi-agent/run :topics '["AI safety" "machine learning"]'

# Config directory, skills, and large output
clojure -A:examples -X config-skill-output/run

# Metadata API (list-tools, get-quota, model switching)
clojure -A:examples -X metadata-api/run

# Permission handling
clojure -A:examples -X permission-bash/run

# Session state events monitoring
clojure -A:examples -X session-events/run

# User input handling (ask_user)
clojure -A:examples -X user-input/run
clojure -A:examples -X user-input/run-simple

# BYOK provider (requires API key, see example docs)
OPENAI_API_KEY=sk-... clojure -A:examples -X byok-provider/run
clojure -A:examples -X byok-provider/run :provider-name '"ollama"'

# MCP local server (requires npx/Node.js)
clojure -A:examples -X mcp-local-server/run
clojure -A:examples -X mcp-local-server/run-with-custom-tools
```

Or run all examples:
```bash
./run-all-examples.sh
```

> **Note:** `run-all-examples.sh` runs the core examples (1–9) that need only the Copilot CLI.
> Example 10 (BYOK) and Example 11 (MCP) require external dependencies (API keys, Node.js) and must be run manually.

With a custom CLI path:
```bash
COPILOT_CLI_PATH=/path/to/copilot clojure -A:examples -X basic-chat/run
```

---

## Example 1: Basic Chat (`basic_chat.clj`)

**Difficulty:** Beginner  
**Concepts:** Client lifecycle, sessions, message sending

The simplest use case—create a client, start a conversation, and get responses.

### What It Demonstrates

- Creating and starting a `CopilotClient`
- Creating a session with a specific model
- Sending messages with `send-and-wait!`
- Multi-turn conversation (context is preserved)
- Proper cleanup with `with-client-session`

### Usage

```bash
clojure -A:examples -X basic-chat/run
clojure -A:examples -X basic-chat/run :q1 '"What is Clojure?"' :q2 '"Who created it?"'
```

### Code Walkthrough

```clojure
(require '[github.copilot-sdk :as copilot])
(require '[github.copilot-sdk.helpers :as h])

;; 1. Create a client and session
(copilot/with-client-session [session {:model "gpt-5.2"}]
  ;; 2. Send a message using query with the session
  (println (h/query "What is the capital of France?" :session session))
  ;; => "The capital of France is Paris."

  ;; 3. Follow-up question (conversation context preserved)
  (println (h/query "What is its population?" :session session)))
  ;; The model knows "its" refers to Paris
```

---

## Example 2: Helpers Query (`helpers_query.clj`)

**Difficulty:** Beginner  
**Concepts:** Stateless queries, simple API

Shows the simplified helpers API for one-shot queries without managing client/session lifecycle.

### What It Demonstrates

- `query` - Simple synchronous query, returns just the answer string
- `query-seq!` - Returns a bounded lazy sequence (default 256 events) and guarantees session cleanup  
- `query-chan` - Returns core.async channel of events for explicit lifecycle control
- Automatic client management (created on first use, reused across queries)
- Automatic cleanup via JVM shutdown hook (no manual cleanup needed)

### Usage

```bash
# Simple query
clojure -A:examples -X helpers-query/run

# With custom prompt
clojure -A:examples -X helpers-query/run :prompt '"What is functional programming?"'

# Streaming output (lazy seq)
clojure -A:examples -X helpers-query/run-streaming

# Streaming output (core.async)
clojure -A:examples -X helpers-query/run-async

# Multiple independent queries
clojure -A:examples -X helpers-query/run-multi
clojure -A:examples -X helpers-query/run-multi :questions '["What is Rust?" "What is Go?"]'
```

### Code Walkthrough

```clojure
(require '[github.copilot-sdk.helpers :as h])

;; Simplest possible query - just get the answer
(h/query "What is 2+2?" :session {:model "gpt-5.2"})
;; => "4"

;; With options
(h/query "What is Clojure?" :session {:model "gpt-5.2"})

;; Streaming with multimethod event handling
(defmulti handle-event :type)
(defmethod handle-event :default [_] nil)
(defmethod handle-event :copilot/assistant.message_delta [{{:keys [delta-content]} :data}]
  (print delta-content)
  (flush))
(defmethod handle-event :copilot/assistant.message [_] (println))

(run! handle-event (h/query-seq! "Tell me a joke" :session {:model "gpt-5.2" :streaming? true}))
```

---

## Example 3: Tool Integration (`tool_integration.clj`)

**Difficulty:** Intermediate  
**Concepts:** Custom tools, tool handlers, result types

Shows how to let the LLM call back into your application when it needs capabilities you provide.

### What It Demonstrates

- Defining tools with `define-tool`
- JSON Schema parameters for type-safe tool inputs
- Handler functions that execute when tools are invoked
- Different result types: `result-success`, `result-failure`

### Usage

```bash
clojure -A:examples -X tool-integration/run
clojure -A:examples -X tool-integration/run :languages '["clojure" "haskell"]'
```

### Code Walkthrough

```clojure
(require '[github.copilot-sdk :as copilot])
(require '[github.copilot-sdk.helpers :as h])

;; Define a tool with handler
(def lookup-tool
  (copilot/define-tool "lookup_language"
    {:description "Look up information about a programming language"
     :parameters {:type "object"
                  :properties {:language {:type "string"
                                          :description "Language name"}}
                  :required ["language"]}
     :handler (fn [args invocation]
                ;; args = {:language "clojure"}
                ;; invocation = full invocation context
                (let [lang (-> args :language str/lower-case)
                      info (get knowledge-base lang)]
                  (if info
                    (copilot/result-success info)
                    (copilot/result-failure 
                      (str "No info for: " lang)
                      "not found"))))}))

;; Create session with tools and use query
(copilot/with-client-session [session {:model "gpt-5.2"
                                       :tools [lookup-tool]}]
  (println (h/query "Tell me about Clojure using the lookup tool" :session session)))
```

### Tool Result Types

```clojure
;; Success - return data to the LLM
(copilot/result-success "The answer is 42")

;; Failure - tell LLM the operation failed
(copilot/result-failure "Could not connect to database" "connection timeout")

;; Denied - permission was denied
(copilot/result-denied "User declined permission")

;; Rejected - tool invocation was invalid
(copilot/result-rejected "Invalid parameters")
```

---

## Example 4: Multi-Agent Orchestration (`multi_agent.clj`)

**Difficulty:** Advanced  
**Concepts:** Multiple sessions, core.async, concurrent operations, agent coordination

Demonstrates a sophisticated pattern where multiple specialized agents collaborate using core.async channels for coordination.

### What It Demonstrates

- Creating multiple sessions with different system prompts (personas)
- Using `core.async` channels for concurrent operations
- Parallel research queries with `go` blocks
- Sequential pipeline: Research → Analysis → Synthesis
- Coordinating results from multiple async operations

### Usage

```bash
clojure -A:examples -X multi-agent/run
clojure -A:examples -X multi-agent/run :topics '["AI safety" "machine learning" "neural networks"]'
```

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     Multi-Agent Workflow                     │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│   Phase 1: Parallel Research                                │
│   ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│   │  Topic 1     │  │  Topic 2     │  │  Topic 3     │     │
│   │  (go block)  │  │  (go block)  │  │  (go block)  │     │
│   └──────┬───────┘  └──────┬───────┘  └──────┬───────┘     │
│          │                 │                 │              │
│          └────────────────┬┴─────────────────┘              │
│                           │ result-ch                       │
│                           ▼                                 │
│   Phase 2: Analysis  ┌──────────────┐                      │
│                      │   Analyst    │                      │
│                      │   Session    │                      │
│                      └──────┬───────┘                      │
│                             │                               │
│                             ▼                               │
│   Phase 3: Synthesis ┌──────────────┐                      │
│                      │   Writer     │                      │
│                      │   Session    │                      │
│                      └──────┬───────┘                      │
│                             │                               │
│                             ▼                               │
│                      Final Summary                          │
└─────────────────────────────────────────────────────────────┘
```

---

## Example 5: Config, Skills, and Large Output (`config_skill_output.clj`)

**Difficulty:** Intermediate  
**Concepts:** config-dir overrides, skill directories, disabling skills, large tool output settings

Shows how to:
- set a custom config directory
- provide additional skill directories
- disable specific skills by name
- configure large tool output handling with a custom tool

### Usage

```bash
clojure -A:examples -X config-skill-output/run
```

---

## Example 6: Metadata API (`metadata_api.clj`)

**Difficulty:** Beginner  
**Concepts:** list-sessions, list-tools, get-quota, get-current-model, switch-model

Demonstrates the metadata API functions introduced in v0.1.24 for inspecting available tools, quota information, and dynamically switching models within a session.

### What It Demonstrates

- `list-sessions` with context filtering (by repository, branch, cwd)
- `list-tools` to enumerate available tools, with optional model-specific overrides
- `get-quota` to check account usage and entitlements
- `get-current-model` to inspect the session's current model
- `switch-model!` to change the model mid-conversation while maintaining context

### Usage

```bash
# Run the metadata API demo
clojure -A:examples -X metadata-api/run
```

### Key Points

- **list-sessions**: Filter sessions by context (`:repository`, `:branch`, `:cwd`, `:git-root`)
- **list-tools**: Get tool metadata; pass a model ID for model-specific tool lists
- **get-quota**: Returns a map of quota type to snapshot (entitlement, used, remaining %)
- **switch-model!**: Change models dynamically without losing conversation context

> **Note:** Some methods (`tools.list`, `account.getQuota`, `session.model.*`) may not be
> supported by all CLI versions. The example gracefully skips unsupported operations.

---

## Example 7: Permission Handling (`permission_bash.clj`)

**Difficulty:** Intermediate  
**Concepts:** permission requests, bash tool, approval callback, deny-by-default

The SDK uses a **deny-by-default** permission model — all permission requests are
denied unless an `:on-permission-request` handler is provided. Use `copilot/approve-all`
for blanket approval, or provide a custom handler for fine-grained control.

Shows how to:
- handle `permission.request` via `:on-permission-request`
- invoke the built-in shell tool with allow/deny decisions
- log the full permission request payload for inspection

### Usage

```bash
clojure -A:examples -X permission-bash/run
```

---

## Example 8: Session Events Monitoring (`session_events.clj`)

**Difficulty:** Intermediate  
**Concepts:** Event handling, session lifecycle, state management

Demonstrates how to monitor and handle session state events for debugging, logging, or building custom UIs.

### What It Demonstrates

- Monitoring session lifecycle events (start, resume, idle, error)
- Tracking context management events (truncation, compaction)
- Observing usage metrics (token counts, limits)
- Handling `session.snapshot_rewind` events (state rollback)
- Formatting events for human-readable display

### Session State Events

| Event | Description |
|-------|-------------|
| `session.start` | Session created (note: fires before you can subscribe) |
| `session.resume` | Existing session resumed |
| `session.idle` | Session ready for input |
| `session.error` | Error occurred |
| `session.usage_info` | Token usage metrics |
| `session.truncation` | Context window truncated |
| `session.compaction_start/complete` | Infinite sessions compaction |
| `session.snapshot_rewind` | Session state rolled back |
| `session.model_change` | Model switched |
| `session.handoff` | Session handed off |

### Usage

```bash
clojure -A:examples -X session-events/run
clojure -A:examples -X session-events/run :prompt '"Explain recursion."'
```

### Code Walkthrough

```clojure
(require '[clojure.core.async :refer [chan tap go-loop <!]])
(require '[github.copilot-sdk :as copilot])

(def session-state-events
  #{:copilot/session.idle :copilot/session.usage_info :copilot/session.error
    :copilot/session.truncation :copilot/session.snapshot_rewind
    :copilot/session.compaction_start :copilot/session.compaction_complete})

(copilot/with-client-session [session {:streaming? true}]
  (let [events-ch (chan 256)
        done (promise)]
    (tap (copilot/events session) events-ch)
    (go-loop []
      (when-let [event (<! events-ch)]
        ;; Log session state events
        (when (session-state-events (:type event))
          (println "Session event:" (:type event) (:data event)))
        ;; Handle completion
        (when (= :copilot/session.idle (:type event))
          (deliver done true))
        (recur)))
    (copilot/send! session {:prompt "Hello"})
    @done))
```

---

## Example 9: User Input Handling (`user_input.clj`)

**Difficulty:** Intermediate  
**Concepts:** User input requests, ask_user tool, interactive sessions

Demonstrates how to handle `ask_user` requests when the agent needs clarification or input from the user.

### What It Demonstrates

- Registering an `:on-user-input-request` handler
- Responding to questions with choices or freeform input
- Interactive decision-making workflows

### Usage

```bash
# Full interactive example
clojure -A:examples -X user-input/run

# Simpler yes/no example
clojure -A:examples -X user-input/run-simple
```

### Code Walkthrough

```clojure
(require '[github.copilot-sdk :as copilot])

(copilot/with-client-session [session {:model "gpt-5.2"
                                       :on-user-input-request
                                       (fn [request invocation]
                                         ;; request contains:
                                         ;; - :question - the question being asked
                                         ;; - :choices - optional list of choices
                                         ;; - :allow-freeform - whether freeform input is allowed
                                         (println "Agent asks:" (:question request))
                                         (when-let [choices (:choices request)]
                                           (doseq [c choices]
                                             (println " -" c)))
                                         ;; Return the user's response
                                         ;; :answer is required, :was-freeform defaults to true
                                         {:answer (read-line)})}]
  (copilot/send-and-wait! session
    {:prompt "Ask me what format I prefer for the output, then respond accordingly."}))
```

---

## Example 10: BYOK Provider (`byok_provider.clj`)

**Difficulty:** Intermediate  
**Concepts:** BYOK (Bring Your Own Key), custom providers, API key authentication

Shows how to use the SDK with your own API keys from OpenAI, Azure, Anthropic, or Ollama instead of GitHub Copilot authentication.

### What It Demonstrates

- Configuring a `:provider` map for BYOK
- Connecting to OpenAI, Azure OpenAI, Anthropic, or local Ollama
- Using environment variables for API keys

### Prerequisites

Set an environment variable for your provider:
- OpenAI: `OPENAI_API_KEY`
- Azure: `AZURE_OPENAI_KEY`
- Anthropic: `ANTHROPIC_API_KEY`
- Ollama: No key needed (ensure `ollama serve` is running)

### Usage

```bash
# OpenAI (default)
OPENAI_API_KEY=sk-... clojure -A:examples -X byok-provider/run

# Anthropic
ANTHROPIC_API_KEY=sk-... clojure -A:examples -X byok-provider/run :provider-name '"anthropic"'

# Ollama (local, no key)
clojure -A:examples -X byok-provider/run :provider-name '"ollama"'

# Azure
AZURE_OPENAI_KEY=... clojure -A:examples -X byok-provider/run :provider-name '"azure"'
```

See [doc/auth/byok.md](../doc/auth/byok.md) for full BYOK documentation.

---

## Example 11: MCP Local Server (`mcp_local_server.clj`)

**Difficulty:** Intermediate  
**Concepts:** MCP servers, external tools, filesystem access

Shows how to integrate MCP (Model Context Protocol) servers to extend the assistant's capabilities with external tools.

### What It Demonstrates

- Configuring `:mcp-servers` with a local stdio server
- Using the `@modelcontextprotocol/server-filesystem` MCP server
- Combining MCP server tools with custom tools
- Using `copilot/approve-all` to permit MCP tool execution (deny-by-default)

### Prerequisites

- Node.js and `npx` installed (for the filesystem MCP server)

### Usage

```bash
# Basic filesystem access
clojure -A:examples -X mcp-local-server/run

# With custom directory
clojure -A:examples -X mcp-local-server/run :allowed-dir '"/home/user/docs"'

# MCP + custom tools combined
clojure -A:examples -X mcp-local-server/run-with-custom-tools
```

See [doc/mcp/overview.md](../doc/mcp/overview.md) for full MCP documentation.

---

## Clojure vs JavaScript Comparison

Here's how common patterns compare between the Clojure and JavaScript SDKs:

### Client Creation

**JavaScript:**
```typescript
import { CopilotClient } from "@github/copilot-sdk";
const client = new CopilotClient({ logLevel: "info" });
await client.start();
```

**Clojure:**
```clojure
(require '[github.copilot-sdk :as copilot])
(copilot/with-client [client]
  ;; use client
  )
```

### Simple Query (Helpers)

**JavaScript:**
```typescript
// No direct equivalent - must create client/session
```

**Clojure:**
```clojure
(require '[github.copilot-sdk.helpers :as h])
(h/query "What is 2+2?" :session {:model "gpt-5.2"})
;; => "4"
```

### Event Handling

**JavaScript:**
```typescript
session.on((event) => {
  if (event.type === "assistant.message") {
    console.log(event.data.content);
  }
});
```

**Clojure:**
```clojure
;; Using helpers with multimethod dispatch
(defmulti handle-event :type)
(defmethod handle-event :copilot/assistant.message [{{:keys [content]} :data}]
  (println content))

(run! handle-event (h/query-seq! "Hello" :session {:model "gpt-5.2" :streaming? true}))
```

### Tool Definition

**JavaScript:**
```typescript
import { z } from "zod";
import { defineTool } from "@github/copilot-sdk";

defineTool("lookup", {
  description: "Look up data",
  parameters: z.object({ id: z.string() }),
  handler: async ({ id }) => fetchData(id)
});
```

**Clojure:**
```clojure
(copilot/define-tool "lookup"
  {:description "Look up data"
   :parameters {:type "object"
                :properties {:id {:type "string"}}
                :required ["id"]}
   :handler (fn [{:keys [id]} _] 
              (fetch-data id))})
```

### Async Patterns

**JavaScript (Promises):**
```typescript
const response = await session.sendAndWait({ prompt: "Hello" });
```

**Clojure (Blocking):**
```clojure
(def response (copilot/send-and-wait! session {:prompt "Hello"}))
```

**Clojure (core.async):**
```clojure
(go
  (let [ch (copilot/send-async session {:prompt "Hello"})]
    (loop []
      (when-let [event (<! ch)]
        (println event)
        (recur)))))
```

---

## Troubleshooting

### "Connection refused" errors

Ensure the Copilot CLI is installed and accessible:
```bash
copilot --version
# Or check your custom path
$COPILOT_CLI_PATH --version
```

### Timeout errors

Increase the timeout for complex queries:
```clojure
(copilot/send-and-wait! session {:prompt "Complex question"} 300000) ; 5 minutes
```

### Tool not being called

Ensure your prompt explicitly mentions the tool or its capability:
```clojure
;; Less likely to trigger tool:
{:prompt "Tell me about Clojure"}

;; More likely to trigger tool:
{:prompt "Use the lookup_language tool to tell me about Clojure"}
```

### Memory issues with many sessions

Clean up sessions when done:
```clojure
(copilot/destroy! session)
```

And periodically list/delete orphaned sessions:
```clojure
(doseq [s (copilot/list-sessions client)]
  (copilot/delete-session! client (:session-id s)))
```
