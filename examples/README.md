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

# Streaming responses
clojure -A:examples -X streaming-chat/run
clojure -A:examples -X streaming-chat/run :prompt '"Explain the Fibonacci sequence."'

# Config directory, skills, and large output
clojure -A:examples -X config-skill-output/run

# Permission handling
clojure -A:examples -X permission-bash/run
```

Or run all examples:
```bash
./run-all-examples.sh
```

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
;; 1. Create a client and session
(copilot/with-client-session [session {:model "gpt-5.2"}]
  ;; 2. Send a message and wait for the complete response
  (def response (copilot/send-and-wait! session
                  {:prompt "What is the capital of France?"}))

  ;; 3. Access the response content
  (println (get-in response [:data :content]))
  ;; => "The capital of France is Paris."

  ;; 4. Follow-up question (conversation context preserved)
  (def response2 (copilot/send-and-wait! session
                   {:prompt "What is its population?"}))
  ;; The model knows "its" refers to Paris
  )
```

---

## Example 2: Helpers Query (`helpers_query.clj`)

**Difficulty:** Beginner  
**Concepts:** Stateless queries, simple API

Shows the simplified helpers API for one-shot queries without managing client/session lifecycle.

### What It Demonstrates

- `query` - Simple synchronous query, returns just the answer string
- `query-seq` - Returns lazy sequence of all events  
- `query-chan` - Returns core.async channel of events
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
(require '[krukow.copilot-sdk.helpers :as h])

;; Simplest possible query - just get the answer
(h/query "What is 2+2?")
;; => "4"

;; With options
(h/query "What is Clojure?" :session {:model "gpt-5.2"})

;; Streaming with multimethod event handling
(defmulti handle-event :type)
(defmethod handle-event :default [_] nil)
(defmethod handle-event :assistant.message_delta [{{:keys [delta-content]} :data}]
  (print delta-content)
  (flush))
(defmethod handle-event :assistant.message [_] (println))

(run! handle-event (h/query-seq "Tell me a joke" :session {:streaming? true}))
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

;; Create session with tools
(copilot/with-client-session [session {:model "gpt-5.2"
                                       :tools [lookup-tool]}]
  (copilot/send-and-wait! session
    {:prompt "Tell me about Clojure using the lookup tool"}))
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

## Example 5: Streaming Chat (`streaming_chat.clj`)

**Difficulty:** Intermediate  
**Concepts:** Streaming deltas, event handling, incremental output

Demonstrates how to enable streaming and render assistant output incrementally as
`:assistant.message_delta` events arrive, then print the final message on idle.

### Usage

```bash
clojure -A:examples -X streaming-chat/run
clojure -A:examples -X streaming-chat/run :prompt '"Explain the Fibonacci sequence."'
```

---

## Example 6: Config, Skills, and Large Output (`config_skill_output.clj`)

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

## Example 7: Permission Handling (`permission_bash.clj`)

**Difficulty:** Intermediate  
**Concepts:** permission requests, bash tool, approval callback

Shows how to:
- handle `permission.request` via `:on-permission-request`
- invoke the built-in shell tool with allow/deny decisions
- log the full permission request payload for inspection

### Usage

```bash
clojure -A:examples -X permission-bash/run
```

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
(require '[krukow.copilot-sdk :as copilot])
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
(require '[krukow.copilot-sdk.helpers :as h])
(h/query "What is 2+2?")
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
(defmethod handle-event :assistant.message [{{:keys [content]} :data}]
  (println content))

(run! handle-event (h/query-seq "Hello" :session {:streaming? true}))
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
