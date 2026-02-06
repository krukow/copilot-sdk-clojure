# MCP Server Debugging Guide

This guide covers debugging techniques specific to MCP (Model Context Protocol) servers when using the Copilot SDK for Clojure.

## Quick Diagnostics

### Checklist

Before diving deep, verify these basics:

- [ ] MCP server executable exists and is runnable
- [ ] Command path is correct (use absolute paths when in doubt)
- [ ] Tools are enabled (`:mcp-tools ["*"]` or specific tool names)
- [ ] Server implements MCP protocol correctly (responds to `initialize`)
- [ ] No firewall blocking the process

### Enable Debug Logging

Use the client's `:log-level :debug` to see MCP communication:

```clojure
(copilot/with-client-session [{:log-level :debug}
                              session
                              {:model "gpt-5.2"
                               :mcp-servers {"my-server" {:mcp-command "/path/to/server"
                                                          :mcp-args []
                                                          :mcp-tools ["*"]}}}]
  ;; Debug output will show MCP initialization and tool calls
  (h/query "Use my tools" :session session))
```

You can also add environment variables to enable MCP server-side debugging:

```clojure
{:mcp-command "/path/to/server"
 :mcp-args []
 :mcp-tools ["*"]
 :env {"MCP_DEBUG" "1"
       "DEBUG" "*"
       "NODE_DEBUG" "mcp"}}  ; For Node.js MCP servers
```

## Testing MCP Servers Independently

Always test your MCP server outside the SDK first.

### Manual Protocol Test

Send an `initialize` request via stdin:

```bash
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}' | /path/to/your/mcp-server
```

**Expected response:**
```json
{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","capabilities":{"tools":{}},"serverInfo":{"name":"your-server","version":"1.0"}}}
```

### Test Tool Listing

After initialization, request the tools list:

```bash
echo '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' | /path/to/your/mcp-server
```

### Interactive Testing with MCP Inspector

Use the official MCP Inspector tool:

```bash
npx @modelcontextprotocol/inspector /path/to/your/mcp-server
```

This provides a web UI to send test requests, view responses, and inspect tool schemas.

## Common Issues

### Server Not Starting

**Symptoms:** No tools appear, no errors in logs.

| Cause | Solution |
|-------|----------|
| Wrong command path | Use absolute path: `/usr/local/bin/server` |
| Missing executable permission | `chmod +x /path/to/server` |
| Missing dependencies | Check with `ldd` (Linux) or run manually |
| Working directory issues | Set `:cwd` in config |

**Debug by running manually:**
```bash
cd /expected/working/dir
/path/to/command arg1 arg2
```

### Server Starts But Tools Don't Appear

1. **Tools not enabled:**
   ```clojure
   {:mcp-tools ["*"]}  ; Must be ["*"] or list of tool names
   ```

2. **Server doesn't expose tools:** Test with `tools/list` request manually.

3. **Initialization handshake fails:** Server must respond to `initialize` correctly and handle `notifications/initialized`.

### Tools Listed But Never Called

1. **Prompt doesn't clearly need the tool:**
   ```clojure
   ;; Too vague
   {:prompt "What's the weather?"}

   ;; Better - explicitly mentions capability
   {:prompt "Use the weather tool to get the current temperature in Seattle"}
   ```

2. **Tool description unclear:**
   ```clojure
   ;; Bad - model doesn't know when to use it
   {:description "Does a thing"}

   ;; Good - clear purpose
   {:description "Get current weather conditions for a city. Returns temperature, humidity, and conditions."}
   ```

3. **Tool schema issues:** Ensure `inputSchema` is valid JSON Schema with `required` fields.

### Timeout Errors

1. **Increase timeout:**
   ```clojure
   {:mcp-command "slow-server"
    :mcp-args []
    :mcp-tools ["*"]
    :mcp-timeout 300000}  ; 5 minutes
   ```

2. **Optimize server performance:** Add progress logging, check for blocking I/O.

### JSON-RPC Errors

Common causes:

1. **Server writes debug output to stdout instead of stderr:**
   ```javascript
   // Wrong - pollutes stdout
   console.log("Debug info");

   // Correct - use stderr
   console.error("Debug info");
   ```

2. **Encoding issues:** Ensure UTF-8 encoding, no BOM.

3. **Message framing:** Each message must be a complete JSON object, newline-delimited.

## Platform-Specific Issues

### macOS

**Gatekeeper blocking:**
```bash
xattr -d com.apple.quarantine /path/to/mcp-server
```

**Homebrew paths:** Use full paths when the CLI environment may not have Homebrew in PATH:
```clojure
{:mcp-command "/opt/homebrew/bin/node"
 :mcp-args ["/path/to/server.js"]
 :mcp-tools ["*"]}
```

### Linux

**Permission issues:**
```bash
chmod +x /path/to/mcp-server
```

**Missing shared libraries:**
```bash
ldd /path/to/mcp-server
```

## Advanced Debugging

### Capture All MCP Traffic

Create a wrapper script to log all communication:

```bash
#!/bin/bash
# mcp-debug-wrapper.sh
LOG="/tmp/mcp-debug-$(date +%s).log"
echo "=== MCP Debug Session ===" >> "$LOG"
tee -a "$LOG" | "$1" "${@:2}" 2>> "$LOG" | tee -a "$LOG"
```

Use it:
```clojure
{:mcp-command "/path/to/mcp-debug-wrapper.sh"
 :mcp-args ["/actual/server/path" "arg1" "arg2"]
 :mcp-tools ["*"]}
```

### Monitoring MCP Events in Clojure

Subscribe to tool execution events to observe MCP tool calls:

```clojure
(require '[clojure.core.async :refer [chan tap go-loop <!]])

(copilot/with-client-session [session {:model "gpt-5.2"
                                       :mcp-servers {"my-server" {:mcp-command "server"
                                                                   :mcp-args []
                                                                   :mcp-tools ["*"]}}}]
  (let [ch (chan 256)]
    (tap (copilot/events session) ch)
    (go-loop []
      (when-let [event (<! ch)]
        (case (:type event)
          :copilot/tool.execution_start
          (println "MCP tool called:" (get-in event [:data :tool-name])
                   "args:" (get-in event [:data :arguments]))

          :copilot/tool.execution_complete
          (println "MCP tool completed:" (get-in event [:data :tool-call-id])
                   "success:" (get-in event [:data :success?]))
          nil)
        (recur)))
    (copilot/send-and-wait! session {:prompt "Use my tools"})))
```

## Debugging Checklist

When opening an issue or asking for help, collect:

- [ ] SDK version (check `deps.edn` or `pom.xml`)
- [ ] CLI version (`copilot --version`)
- [ ] MCP server type (Node.js, Python, Go, etc.)
- [ ] Full MCP server configuration (redact secrets)
- [ ] Result of manual `initialize` test
- [ ] Result of manual `tools/list` test
- [ ] Debug logs from SDK (`:log-level :debug`)
- [ ] Any error messages

## See Also

- [MCP Overview](./overview.md) — Configuration and setup
- [MCP Specification](https://modelcontextprotocol.io/) — Official protocol docs
- [MCP Servers Directory](https://github.com/modelcontextprotocol/servers) — Community MCP servers
