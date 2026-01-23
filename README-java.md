# Copilot SDK for Java

Java API for programmatic control of GitHub Copilot CLI via JSON-RPC.

> **Note:** This SDK is in technical preview and may change in breaking ways.

## Installation

### Maven Central (Release)

```xml
<dependency>
    <groupId>io.github.krukow</groupId>
    <artifactId>copilot-sdk</artifactId>
    <version>0.1.2</version>
</dependency>
```

### Maven Central (Snapshot)

For the latest development version, use snapshots from the Maven Central snapshots repository:

```xml
<dependency>
    <groupId>io.github.krukow</groupId>
    <artifactId>copilot-sdk</artifactId>
    <version>0.1.2-SNAPSHOT</version>
</dependency>

<repositories>
    <repository>
        <id>central-snapshots</id>
        <url>https://central.sonatype.com/repository/maven-snapshots/</url>
        <releases><enabled>false</enabled></releases>
        <snapshots><enabled>true</enabled></snapshots>
    </repository>
</repositories>
```

### Clojars (alternative)

```xml
<dependency>
    <groupId>net.clojars.krukow</groupId>
    <artifactId>copilot-sdk</artifactId>
    <version>0.1.2-SNAPSHOT</version>
</dependency>

<repositories>
    <repository>
        <id>clojars</id>
        <url>https://repo.clojars.org</url>
    </repository>
</repositories>
```

### Required Dependencies

```xml
<dependency>
    <groupId>org.clojure</groupId>
    <artifactId>clojure</artifactId>
    <version>1.12.4</version>
</dependency>
<dependency>
    <groupId>org.clojure</groupId>
    <artifactId>core.async</artifactId>
    <version>1.8.741</version>
</dependency>
<dependency>
    <groupId>cheshire</groupId>
    <artifactId>cheshire</artifactId>
    <version>6.1.0</version>
</dependency>
```

Or build from source:

```bash
clj -T:build aot-jar
clj -T:build install
```

## Quick Start

### Simple Query

```java
import krukow.copilot_sdk.Copilot;

String answer = Copilot.query("What is 2+2?");
System.out.println(answer);  // "4"
```

### Full Client/Session API

```java
import krukow.copilot_sdk.*;

ICopilotClient client = Copilot.createClient();
client.start();

try {
    SessionOptionsBuilder sb = new SessionOptionsBuilder();
    sb.model("gpt-5.2");
    ICopilotSession session = client.createSession((SessionOptions) sb.build());
    
    try {
        // Context is preserved between calls
        String a1 = session.sendAndWait("What is the capital of France?", 60000);
        String a2 = session.sendAndWait("What is its population?", 60000);
        
        System.out.println(a1);  // "Paris"
        System.out.println(a2);  // "About 2.1 million..."
    } finally {
        session.destroy();
    }
} finally {
    client.stop();
}
```

## API Reference

### Copilot (Static Entry Point)

```java
// Simple queries
String answer = Copilot.query(prompt);
String answer = Copilot.query(prompt, sessionOptions);
String answer = Copilot.query(prompt, sessionOptions, timeoutMs);

// Streaming query with callback
Copilot.queryStreaming(prompt, sessionOptions, event -> {
    if (event.isMessageDelta()) {
        System.out.print(event.getDeltaContent());
    }
});

// Create client
ICopilotClient client = Copilot.createClient();
ICopilotClient client = Copilot.createClient(clientOptions);
```

### ICopilotClient

Manages connections and sessions.

| Method | Returns | Description |
|--------|---------|-------------|
| `start()` | void | Start and connect to CLI |
| `stop()` | List | Stop gracefully, returns errors |
| `forceStop()` | void | Force stop without cleanup |
| `getState()` | String | `"disconnected"`, `"connecting"`, `"connected"`, `"error"` |
| `createSession(opts)` | ICopilotSession | Create a new session |
| `ping()` | Map | Ping server |
| `getStatus()` | Map | CLI version and protocol info |
| `getAuthStatus()` | Map | Authentication status |
| `listModels()` | List | Available models |

### ICopilotSession

Represents a conversation session with context preservation.

| Method | Returns | Description |
|--------|---------|-------------|
| `getSessionId()` | String | Session identifier |
| `send(prompt)` | String | Send message (fire-and-forget), returns message ID |
| `sendAndWait(prompt, timeoutMs)` | String | Send and block until response |
| `sendStreaming(prompt, handler)` | void | Send with callback for each event |
| `sendAsync(prompt)` | CompletableFuture | Send and return future with response |
| `subscribeEvents()` | EventSubscription | Subscribe to raw event stream |
| `abort()` | void | Abort current processing |
| `destroy()` | void | Destroy session |
| `getMessages()` | List | Get message history |

### EventSubscription

Pull-based event stream access. Implements `AutoCloseable` for try-with-resources.

| Method | Returns | Description |
|--------|---------|-------------|
| `take()` | Event | Block until next event (null if closed) |
| `poll()` | Event | Non-blocking poll (null if none) |
| `poll(timeout, unit)` | Event | Poll with timeout |
| `close()` | void | Unsubscribe and release resources |

### Event

Represents a session event.

| Method | Returns | Description |
|--------|---------|-------------|
| `getType()` | String | Event type (e.g., `"assistant.message_delta"`) |
| `getData()` | Map | Event data |
| `get(key)` | Object | Get data field |
| `getContent()` | String | Get `"content"` field |
| `getDeltaContent()` | String | Get `"delta-content"` field |
| `isMessage()` | boolean | Is `"assistant.message"` |
| `isMessageDelta()` | boolean | Is `"assistant.message_delta"` |
| `isIdle()` | boolean | Is `"session.idle"` |
| `isError()` | boolean | Is `"session.error"` |

## Configuration

### ClientOptionsBuilder

```java
ClientOptionsBuilder cb = new ClientOptionsBuilder();
cb.cliPath("/path/to/copilot");
cb.logLevel("info");
ClientOptions opts = (ClientOptions) cb.build();
```

| Method | Type | Description |
|--------|------|-------------|
| `cliPath(path)` | String | Path to CLI executable (default: `"copilot"`) |
| `cliArgs(args)` | List | Extra CLI arguments |
| `cliUrl(url)` | String | URL of existing CLI server |
| `cwd(dir)` | String | Working directory |
| `port(port)` | int | TCP port (0 = random) |
| `useStdio(bool)` | boolean | Use stdio transport (default: true) |
| `logLevel(level)` | String | `"none"`, `"error"`, `"warning"`, `"info"`, `"debug"`, `"all"` |
| `autoStart(bool)` | boolean | Auto-start on first use (default: true) |
| `autoRestart(bool)` | boolean | Auto-restart on crash (default: true) |
| `notificationQueueSize(size)` | int | Max queued protocol notifications (default: 4096) |
| `routerQueueSize(size)` | int | Max queued non-session notifications (default: 4096) |
| `toolTimeoutMs(ms)` | int | Timeout for tool handlers returning channels (default: 120000) |
| `env(map)` | Map | Environment variables |

### SessionOptionsBuilder

```java
SessionOptionsBuilder sb = new SessionOptionsBuilder();
sb.model("gpt-5.2");
sb.systemPrompt("You are a helpful assistant.");
SessionOptions opts = (SessionOptions) sb.build();
```

| Method | Type | Description |
|--------|------|-------------|
| `sessionId(id)` | String | Custom session ID |
| `model(name)` | String | Model name (e.g., `"gpt-5.2"`, `"claude-sonnet-4.5"`) |
| `streaming(bool)` | boolean | Enable streaming deltas |
| `systemPrompt(text)` | String | System prompt (appended to default) |
| `systemMessage(mode, content)` | String, String | System message with mode (`"append"` or `"replace"`) |
| `tool(tool)` | Tool | Add a custom tool |
| `tools(list)` | List | Add multiple custom tools |
| `allowedTool(name)` | String | Allow a tool by name |
| `allowedTools(list)` | List | Set allowed tools |
| `excludedTool(name)` | String | Exclude a tool by name |
| `excludedTools(list)` | List | Set excluded tools |
| `onPermissionRequest(handler)` | IPermissionHandler | Permission request handler |
| `provider(config)` | Map | Custom provider config (BYOK) |
| `mcpServers(config)` | Map | MCP server configs |
| `customAgents(list)` | List | Custom agent configs |
| `configDir(path)` | String | Config directory override |
| `skillDirectory(path)` | String | Add skill directory |
| `skillDirectories(list)` | List | Set skill directories |
| `disabledSkill(name)` | String | Disable a skill |
| `disabledSkills(list)` | List | Set disabled skills |
| `largeOutput(config)` | Map | Large output handling config |

## Async Patterns

### CompletableFuture

```java
// Non-blocking async query
@SuppressWarnings("unchecked")
CompletableFuture<String> future = (CompletableFuture<String>) session.sendAsync("Question?");

// Do other work while waiting...

// Get result when ready
String answer = future.get(60, TimeUnit.SECONDS);

// Or use callbacks
future.thenAccept(answer -> System.out.println(answer));
```

### Concurrent Queries

```java
// Fire multiple queries in parallel
CompletableFuture<String> f1 = (CompletableFuture<String>) session1.sendAsync("Q1?");
CompletableFuture<String> f2 = (CompletableFuture<String>) session2.sendAsync("Q2?");
CompletableFuture<String> f3 = (CompletableFuture<String>) session3.sendAsync("Q3?");

// Wait for all
CompletableFuture.allOf(f1, f2, f3).join();

System.out.println(f1.get());
System.out.println(f2.get());
System.out.println(f3.get());
```

### EventSubscription (Pull-based Streaming)

```java
try (EventSubscription events = session.subscribeEvents()) {
    session.send("Write a poem");  // Non-blocking
    
    Event event;
    while ((event = events.take()) != null) {
        if (event.isMessageDelta()) {
            System.out.print(event.getDeltaContent());
        }
        if (event.isIdle()) break;
    }
}
```

### Polling Pattern

```java
try (EventSubscription events = session.subscribeEvents()) {
    session.send("Question");
    
    while (true) {
        Event event = events.poll(100, TimeUnit.MILLISECONDS);
        if (event == null) {
            // No event yet, do other work
            continue;
        }
        processEvent(event);
        if (event.isIdle()) break;
    }
}
```

## Streaming

### Callback-based

```java
SessionOptionsBuilder sb = new SessionOptionsBuilder();
sb.streaming(true);

Copilot.queryStreaming("Tell me a story", (SessionOptions) sb.build(), event -> {
    if (event.isMessageDelta()) {
        System.out.print(event.getDeltaContent());
    }
});
```

### Pull-based with EventSubscription

```java
try (EventSubscription events = session.subscribeEvents()) {
    session.send("Tell me a story");
    
    Event event;
    while ((event = events.take()) != null) {
        if (event.isMessageDelta()) {
            System.out.print(event.getDeltaContent());
        }
        if (event.isIdle()) break;
    }
}
```

## Custom Tools

Define tools that the LLM can invoke:

```java
import krukow.copilot_sdk.*;
import java.util.*;

// Define tool parameters (JSON Schema)
Map<String, Object> params = Map.of(
    "type", "object",
    "properties", Map.of(
        "query", Map.of("type", "string", "description", "Search query")
    ),
    "required", List.of("query")
);

// Create tool with handler
Tool searchTool = new Tool(
    "search_docs",
    "Search the documentation",
    params,
    (args, invocation) -> {
        String query = (String) args.get("query");
        String result = performSearch(query);  // Your logic
        return Tool.success(result);
    }
);

// Use in session
SessionOptionsBuilder sb = new SessionOptionsBuilder();
sb.model("gpt-5.2");
sb.tool(searchTool);
ICopilotSession session = client.createSession((SessionOptions) sb.build());
```

### Tool Result Helpers

```java
Tool.success("Result text");                    // Success
Tool.failure("Error message", "error_code");    // Failure
```

## Permission Handling

Handle permission requests when the LLM wants to run shell commands or write files:

```java
SessionOptionsBuilder sb = new SessionOptionsBuilder();
sb.model("gpt-5.2");
sb.allowedTool("bash");  // Enable shell tool
sb.onPermissionRequest(request -> {
    String command = (String) request.get("full-command-text");
    String kind = (String) request.get("kind");  // "shell", "write-file", etc.
    
    // Approve safe commands
    if (command.startsWith("echo ")) {
        return PermissionResult.approved();
    }
    
    // Deny everything else
    return PermissionResult.deniedByUser("Command not allowed");
});
```

### Permission Result Types

```java
PermissionResult.approved();                        // Allow
PermissionResult.deniedByRules(rules);              // Deny with rules list
PermissionResult.deniedNoApprovalRule();            // No approval rule configured
PermissionResult.deniedByUser();                    // User denied
PermissionResult.deniedByUser("Reason");            // User denied with feedback
```

**Note:** Permission request maps use kebab-case keys (e.g., `"full-command-text"`).

## Event Types

| Event Type | Description |
|------------|-------------|
| `assistant.message` | Complete assistant response |
| `assistant.message_delta` | Streaming response chunk |
| `assistant.reasoning` | Model reasoning (if supported) |
| `tool.execution_start` | Tool execution started |
| `tool.execution_complete` | Tool execution completed |
| `session.idle` | Session finished processing |
| `session.error` | Error occurred |

## Examples

See [`examples/java/`](./examples/java/) for complete working examples:

| Example | Description |
|---------|-------------|
| `JavaExample` | Basic query API |
| `StreamingJavaExample` | Real-time streaming output |
| `ConversationJavaExample` | Multi-turn conversations |
| `AsyncApiExample` | CompletableFuture and EventSubscription |
| `ToolIntegrationExample` | Custom tools |
| `PermissionBashExample` | Permission handling |
| `MultiAgentExample` | Multiple agents |
| `ParallelQueriesExample` | Concurrent queries |
| `EventHandlingExample` | Event processing patterns |
| `InteractiveChatExample` | Interactive chat |

Run examples:

```bash
cd examples/java
mvn compile
mvn exec:java -Pasync      # Async API demo
mvn exec:java -Pparallel   # Concurrent queries
mvn exec:java -Pevents     # Event handling
```

## Architecture

```
Your Java Application
        ↓
   Copilot SDK (Clojure + AOT)
        ↓ JSON-RPC (stdio)
   Copilot CLI (server mode)
        ↓
   GitHub Copilot API
```

## Notes

- Builder methods return `Object`; cast or use separate statements
- `sendAsync()` returns raw `CompletableFuture`; cast to `CompletableFuture<String>`
- Permission request maps use kebab-case keys
- JVM shutdown hook handles resource cleanup automatically

## Requirements

- Java 17+
- GitHub Copilot CLI installed and authenticated

## License

Copyright © 2026 Krukow

Distributed under the MIT License.
