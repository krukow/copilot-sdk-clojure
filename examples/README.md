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

From the project root:

```bash
# Basic Q&A conversation
clojure -A:examples -M -m basic-chat

# Custom tool integration
clojure -A:examples -M -m tool-integration

# Multi-agent orchestration
clojure -A:examples -M -m multi-agent

# Streaming responses
clojure -A:examples -M -m streaming-chat

# Config directory, skills, and large output
clojure -A:examples -M -m config-skill-output
```

Or with a custom CLI path:

```bash
COPILOT_CLI_PATH=/path/to/copilot clojure -A:examples -M -m basic-chat
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
- Proper cleanup with `with-client` and `with-session`

### Code Walkthrough

```clojure
;; 1. Create a client with configuration and start it
(copilot/with-client [client {:cli-path "copilot"
                              :log-level :info}]
  ;; 2. Create a session (specifying model)
  (copilot/with-session [session client {:model "gpt-5"}]
    ;; 3. Send a message and wait for the complete response
    (def response (copilot/send-and-wait! session
                    {:prompt "What is the capital of France?"}))

    ;; 4. Access the response content
    (println (get-in response [:data :content]))
    ;; => "The capital of France is Paris."

    ;; 5. Follow-up question (conversation context preserved)
    (def response2 (copilot/send-and-wait! session
                     {:prompt "What is its population?"}))
    ;; The model knows "its" refers to Paris
    ))
```

### Expected Output

```
🚀 Basic Chat Example
======================

📡 Starting Copilot client...
✅ Connected!

📝 Creating session...
✅ Session created: abc123-...

💬 Asking: What is the capital of France?

🤖 Response:
The capital of France is Paris.

💬 Follow-up: What is its population?

🤖 Response:
Paris has a population of approximately 2.1 million in the city proper...

🧹 Cleaning up session...
✅ Done!
```

---

## Example 2: Tool Integration (`tool_integration.clj`)

**Difficulty:** Intermediate  
**Concepts:** Custom tools, tool handlers, result types

Shows how to let the LLM call back into your application when it needs capabilities you provide.

### What It Demonstrates

- Defining tools with `define-tool`
- JSON Schema parameters for type-safe tool inputs
- Handler functions that execute when tools are invoked
- Different result types: `result-success`, `result-failure`
- Multiple tools in a single session

### Tools Defined

1. **`lookup_language`** - Queries a mock knowledge base for programming language info
2. **`calculate`** - Performs arithmetic calculations

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
(def session (copilot/create-session client
               {:model "gpt-5"
                :tools [lookup-tool calculator-tool]}))

;; When you send a prompt, the LLM may invoke your tools
(copilot/send-and-wait! session
  {:prompt "Tell me about Clojure using the lookup tool"})
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

### Expected Output

```
🔧 Tool Integration Example
============================

📡 Starting Copilot client...
✅ Connected!

📝 Creating session with custom tools...
✅ Session created with tools: lookup_language, calculate

💬 Asking about Clojure (should use lookup_language tool)...
  [Tool called: lookup_language with {:language "clojure"}]

🤖 Response:
Clojure is a dynamic, functional programming language that runs on the JVM...

💬 Asking for a calculation (should use calculate tool)...
  [Tool called: calculate with {:expression "15 * 7 + 23"}]

🤖 Response:
The result of 15 * 7 + 23 is 128.

🧹 Cleaning up...
✅ Done!
```

---

## Example 3: Multi-Agent Orchestration (`multi_agent.clj`)

**Difficulty:** Advanced  
**Concepts:** Multiple sessions, core.async, concurrent operations, agent coordination

Demonstrates a sophisticated pattern where multiple specialized agents collaborate using core.async channels for coordination.

### What It Demonstrates

- Creating multiple sessions with different system prompts (personas)
- Using `core.async` channels for concurrent operations
- Parallel research queries with `go` blocks
- Sequential pipeline: Research → Analysis → Synthesis
- Coordinating results from multiple async operations

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

### Key Patterns

#### Creating Specialized Agents

```clojure
(defn create-agent [client agent-name role-description model]
  (let [session (copilot/create-session client
                  {:model model
                   :system-message {:mode :append
                                    :content role-description}})]
    {:name agent-name
     :session session
     :model model}))

;; Create agents with different personas
(def researcher (create-agent client "Researcher"
                  "You are a research assistant. Focus on facts."
                  "gpt-5"))

(def analyst (create-agent client "Analyst"
               "You are an analyst. Identify patterns and insights."
               "gpt-5"))
```

#### Async Response Helper

```clojure
(defn agent-respond! [agent prompt]
  (let [out-ch (chan 1)]
    (go
      (try
        (let [response (copilot/send-and-wait! (:session agent)
                         {:prompt prompt}
                         120000)]
          (>! out-ch {:agent (:name agent)
                      :content (get-in response [:data :content])
                      :success true}))
        (catch Exception e
          (>! out-ch {:agent (:name agent)
                      :error (ex-message e)
                      :success false})))
      (close! out-ch))
    out-ch))
```

#### Parallel Execution

```clojure
(defn run-parallel-research! [researcher topics]
  (let [result-ch (chan (count topics))]
    ;; Launch all tasks in parallel
    (doseq [topic topics]
      (go
        (let [response (<! (agent-respond! researcher
                            (str "Research: " topic)))]
          (>! result-ch (assoc response :topic topic)))))
    
    ;; Collect all results
    (go-loop [results [] remaining (count topics)]
      (if (zero? remaining)
        results
        (let [result (<! result-ch)]
          (recur (conj results result) (dec remaining)))))))
```

### Expected Output

```
🤖 Multi-Agent Orchestration Example
=====================================

This example creates 3 specialized agents that work together:
  1. Researcher - gathers information on topics
  2. Analyst - identifies patterns and insights
  3. Writer - synthesizes a final summary

📡 Starting Copilot client...
✅ Connected!

🎭 Creating specialized agents...
  ✓ Researcher agent ready
  ✓ Analyst agent ready
  ✓ Writer agent ready

📖 Phase 1: Parallel Research
   Researching multiple topics concurrently...

  📚 Researching: functional programming benefits
  📚 Researching: immutable data structures
  📚 Researching: concurrent programming challenges

   Research Results:
   • functional programming benefits: Functional programming offers...
   • immutable data structures: Immutable data structures provide...
   • concurrent programming challenges: Concurrent programming faces...

🔍 Phase 2: Analysis
   Sending research findings to Analyst...

   Analysis Complete:
   The research reveals a common thread: immutability and functional...

✍️  Phase 3: Synthesis
   Writer is creating final summary...

═══════════════════════════════════════════════════════════════
📋 FINAL SUMMARY
═══════════════════════════════════════════════════════════════
Functional programming paradigms, combined with immutable data
structures, offer a robust solution to the challenges of modern
concurrent programming...
═══════════════════════════════════════════════════════════════

🧹 Cleaning up agents...

✅ Multi-agent workflow complete!
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
(copilot/with-client [client {:log-level :info}]
  ;; use client
  )
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
(let [ch (copilot/events->chan session
                               {:xf (filter #(= "assistant.message" (:type %)))})]
  (go-loop []
    (when-let [event (<! ch)]
      (println (get-in event [:data :content]))
      (recur))))
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

## Example 4: Streaming Chat (`streaming_chat.clj`)

**Difficulty:** Intermediate  
**Concepts:** Streaming deltas, event handling, incremental output

Demonstrates how to enable streaming and render assistant output incrementally as
`:assistant.message_delta` events arrive, then print the final message on idle.

## Example 5: Config, Skills, and Large Output (`config_skill_output.clj`)

**Difficulty:** Intermediate  
**Concepts:** config-dir overrides, skill directories, disabling skills, large tool output settings

Shows how to:
- set a custom config directory
- provide additional skill directories
- disable specific skills by name
 - configure large tool output handling with a custom tool

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
