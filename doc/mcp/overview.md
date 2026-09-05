# Using MCP Servers with the Copilot SDK for Clojure

The Copilot SDK can integrate with **MCP servers** (Model Context Protocol) to extend the assistant's capabilities with external tools. MCP servers run as separate processes and expose tools (functions) that Copilot can invoke during conversations.

## What is MCP?

[Model Context Protocol (MCP)](https://modelcontextprotocol.io/) is an open standard for connecting AI assistants to external tools and data sources. MCP servers can:

- Execute code or scripts
- Query databases
- Access file systems
- Call external APIs
- And much more

## Server Types

The SDK supports two types of MCP servers:

| Type | Description | Use Case |
|------|-------------|----------|
| **Local/Stdio** (`:local`, `:stdio`) | Runs as a subprocess, communicates via stdin/stdout | Local tools, file access, custom scripts |
| **HTTP/SSE** (`:http`, `:sse`) | Remote server accessed via HTTP | Shared services, cloud-hosted tools |

## Configuration

### Local MCP Server

```clojure
(require '[github.copilot-sdk :as copilot])
(require '[github.copilot-sdk.helpers :as h])

(copilot/with-client-session [session
                              {:on-permission-request copilot/approve-all
                               :model "gpt-5.4"
                               :mcp-servers
                               {"my-local-server"
                                {:mcp-command "node"
                                 :mcp-args ["./mcp-server.js"]
                                 :mcp-tools ["*"]
                                 :env {"DEBUG" "true"}
                                 :cwd "./servers"}}}]
  (println (h/query "Use my tools to help me" :session session)))
```

### Remote MCP Server (HTTP)

```clojure
(copilot/with-client-session [session
                              {:on-permission-request copilot/approve-all
                               :model "gpt-5.4"
                               :mcp-servers
                               {"github"
                                {:mcp-server-type :http
                                 :mcp-url "https://api.githubcopilot.com/mcp/"
                                 :mcp-headers {"Authorization" (str "Bearer " token)}
                                 :mcp-tools ["*"]}}}]
  (println (h/query "List my recent GitHub notifications" :session session)))
```

### Multiple MCP Servers

You can combine multiple MCP servers in a single session:

```clojure
(copilot/with-client-session [session
                              {:on-permission-request copilot/approve-all
                               :model "gpt-5.4"
                               :mcp-servers
                               {"filesystem"
                                {:mcp-command "npx"
                                 :mcp-args ["-y" "@modelcontextprotocol/server-filesystem" "/tmp"]
                                 :mcp-tools ["*"]}
                                "github"
                                {:mcp-server-type :http
                                 :mcp-url "https://api.githubcopilot.com/mcp/"
                                 :mcp-headers {"Authorization" (str "Bearer " token)}
                                 :mcp-tools ["*"]}}}]
  ;; Both servers' tools are available
  (println (h/query "List files in /tmp and my GitHub notifications" :session session)))
```

## Quick Start: Filesystem MCP Server

Here's a complete working example using the official [`@modelcontextprotocol/server-filesystem`](https://www.npmjs.com/package/@modelcontextprotocol/server-filesystem) MCP server:

```clojure
(require '[github.copilot-sdk :as copilot])
(require '[github.copilot-sdk.helpers :as h])

(copilot/with-client-session [session
                              {:on-permission-request copilot/approve-all
                               :model "gpt-5.4"
                               :mcp-servers
                               {"filesystem"
                                {:mcp-command "npx"
                                 :mcp-args ["-y" "@modelcontextprotocol/server-filesystem" "/tmp"]
                                 :mcp-tools ["*"]}}}]
  (println (h/query "List the files in the allowed directory" :session session)))
```

> **Tip:** You can use any MCP server from the [MCP Servers Directory](https://github.com/modelcontextprotocol/servers). Popular options include `@modelcontextprotocol/server-github`, `@modelcontextprotocol/server-sqlite`, and `@modelcontextprotocol/server-puppeteer`.

## Configuration Options

### Local/Stdio Server

| Key | Type | Required | Description |
|-----|------|----------|-------------|
| `:mcp-command` | string | Yes | Command to execute |
| `:mcp-args` | vector | No | Command arguments (optional since CLI 1.0.51; omit for argument-less commands) |
| `:mcp-tools` | vector | Yes | Tools to enable (`["*"]` for all, `[]` for none, or specific tool names) |
| `:mcp-server-type` | keyword | No | `:local` or `:stdio` (defaults to local) |
| `:mcp-timeout` | number | No | Timeout in milliseconds |
| `:mcp-defer-tools` | keyword | No | **Experimental, CLI-only:** tool-deferral policy `:auto` (defer tool registration until needed) or `:never`. Wire-encoded as `deferTools` (upstream schema 1.0.63); this field is not exposed by the official Node SDK's `MCPServerConfig`. |
| `:env` | map | No | Environment variables for the subprocess |
| `:cwd` | string | No | Working directory for the subprocess |

### Remote Server (HTTP/SSE)

| Key | Type | Required | Description |
|-----|------|----------|-------------|
| `:mcp-server-type` | keyword | Yes | `:http` or `:sse` |
| `:mcp-url` | string | Yes | Server URL |
| `:mcp-tools` | vector | Yes | Tools to enable (`["*"]` for all) |
| `:mcp-timeout` | number | No | Timeout in milliseconds |
| `:mcp-defer-tools` | keyword | No | **Experimental, CLI-only:** tool-deferral policy `:auto` or `:never`. Wire-encoded as `deferTools` (upstream schema 1.0.63); this field is not exposed by the official Node SDK's `MCPServerConfig`. |
| `:mcp-headers` | map | No | HTTP headers (e.g., for authentication) |

### Resume-Time Configuration

When `:mcp-servers` is supplied to `resume-session` or `<resume-session`, the
SDK sends the converted configuration as `mcpServers` in the `session.resume`
request. `join-session` inherits this behavior because it resumes the parent
session internally. Omitting `:mcp-servers` omits the wire key; an empty map
sends an empty configuration.

### Disabling MCP Servers

Disable specific servers for a session without removing their `:mcp-servers` entry:

```clojure
(copilot/with-client-session [session
                              {:on-permission-request copilot/approve-all
                               :mcp-servers {"filesystem" {:mcp-command "npx"
                                                            :mcp-args ["-y" "@modelcontextprotocol/server-filesystem" "/tmp"]
                                                            :mcp-tools ["*"]}
                                             "github" {:mcp-server-type :http
                                                       :mcp-url "https://api.githubcopilot.com/mcp/"
                                                       :mcp-tools ["*"]}}
                               :disabled-mcp-servers ["github"]}]
  ;; Only "filesystem" starts; "github" is neither started nor authenticated.
  (println (h/query "List files in /tmp" :session session)))
```

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `:disabled-mcp-servers` | vector of strings | `nil` (none disabled) | Exact `:mcp-servers` names to disable. Wire key: `disabledMcpServers` |

`:disabled-mcp-servers` names servers that are not started or authenticated when creating or cold-resuming a session. Supplying it on a **resident** (warm) resume cannot stop servers that are already running — only `create-session` or a cold resume actually prevent startup.

Passing `[]` explicitly differs on the wire from omitting the key: `[]` sends an empty `disabledMcpServers` vector, while omission sends no field. Supported identically on `create-session`, `resume-session`, and `join-session` (which resumes internally).

### Observing MCP Server Lifecycle

Session subscriptions expose MCP status through curated events:

| Event | Data |
|-------|------|
| `:copilot/session.mcp_servers_loaded` | `{:servers [...]}` snapshot. Each server includes `:name` and `:status`, and may include source/plugin identity, `:error`, and `:server-metadata {:instructions <string-or-nil>}`. |
| `:copilot/session.mcp_server_status_changed` | `{:server-name "..." :status "..."}` |
| `:copilot/session.mcp_server_removed` | `{:server-name "..."}` |
| `:copilot/session.mcp_server_needs_reconnect` | `{:server-name "..."}` |

Statuses are `"connected"`, `"failed"`, `"needs-auth"`, `"pending"`,
`"disabled"`, `"stopped"`, or `"not_configured"`. Treat
`mcp_server_needs_reconnect` as a host-facing signal; the runtime does not imply
that the SDK reconfigures or restarts the server automatically.

### Built-in GitHub MCP Tool Configuration

`:github-mcp-tool-config` tunes the runtime's **built-in** GitHub MCP server. It is independent of manually configuring a `"github"` entry under `:mcp-servers`, as in [Remote MCP Server (HTTP)](#remote-mcp-server-http) above:

```clojure
(copilot/with-client-session [session
                              {:on-permission-request copilot/approve-all
                               :github-mcp-tool-config {:enable-all-tools? true
                                                         :additional-toolsets ["security"]}}]
  (println (h/query "List my recent GitHub notifications" :session session)))
```

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `:enable-all-tools?` | boolean | `false` | Use the read-write endpoint and request all toolsets. Wire key: `enableAllTools` |
| `:additional-toolsets` | vector of strings | `nil` (none added) | Additional GitHub MCP toolsets requested. Wire key: `additionalToolsets` |
| `:additional-tools` | vector of strings | `nil` (none added) | Additional GitHub MCP tools requested. Wire key: `additionalTools` |
| `:enable-insiders-mode?` | boolean | `false` | Request the GitHub MCP insiders build. Wire key: `enableInsidersMode` |
| `:disable-form-deferral?` | boolean | `false` | Make form-backed GitHub write tools execute directly instead of returning an awaiting-form stub. Only applies to the built-in GitHub MCP server, and only has an effect when MCP Apps and form-backed GitHub tools are enabled for the session. Wire key: `disableFormDeferral` |

No other keys are accepted in the `:github-mcp-tool-config` map. Supply it on each `create-session`, `resume-session`, or `join-session` call that needs the configuration.

Omitting the key (or passing `nil`) sends nothing on the wire. Passing `{}` sends `githubMcpToolConfig: {}` (every sub-field defaults). Within the map, `:additional-toolsets` and `:additional-tools` follow the same explicit-`[]`-vs-omitted rule as `:disabled-mcp-servers`: an explicit `[]` is sent distinctly from omitting the key, while the boolean keys simply omit their wire field when `nil`.

### Key Naming Convention

MCP server config keys use an `:mcp-` prefix in Clojure (e.g., `:mcp-command`, `:mcp-args`, `:mcp-tools`) to distinguish them from other configuration options. On the wire, the SDK automatically strips this prefix to match the upstream protocol (e.g., `command`, `args`, `tools`).

The experimental CLI-only `:mcp-defer-tools` key maps to the camelCase wire key `deferTools`, and its keyword value (`:auto`/`:never`) is stringified. It is a Clojure escape hatch for the CLI protocol, not part of strict Node SDK API parity.

The non-prefixed keys `:env` and `:cwd` are shared with other config types and do not have an `:mcp-` prefix.

## Tool Filtering

Control which tools from an MCP server are available to the model:

```clojure
;; All tools
{:mcp-tools ["*"]}

;; No tools (server connected but tools disabled)
{:mcp-tools []}

;; Specific tools only
{:mcp-tools ["read_file" "write_file" "list_directory"]}
```

## Combining MCP Servers with Custom Tools

MCP server tools work alongside custom tools defined with `define-tool`:

```clojure
(def my-tool
  (copilot/define-tool "my_custom_tool"
    {:description "A custom tool"
     :parameters {:type "object"
                  :properties {:input {:type "string"}}
                  :required ["input"]}
     :handler (fn [args _] (copilot/result-success (str "Processed: " (:input args))))}))

(copilot/with-client-session [session
                              {:on-permission-request copilot/approve-all
                               :model "gpt-5.4"
                               :tools [my-tool]
                               :mcp-servers
                               {"filesystem"
                                {:mcp-command "npx"
                                 :mcp-args ["-y" "@modelcontextprotocol/server-filesystem" "/tmp"]
                                 :mcp-tools ["*"]}}}]
  ;; Both MCP tools and custom tools are available
  )
```

## Interactive OAuth

Remote MCP servers may require OAuth. By default the runtime uses a browserless,
cached-token flow. To drive an interactive (browser-based) authorization, supply
an `:on-mcp-auth-request` handler on `create-session` / `resume-session`:

```clojure
(copilot/with-client-session [session
                              {:on-permission-request copilot/approve-all
                               :mcp-servers {"remote" {:mcp-url "https://mcp.example.com"
                                                       :mcp-tools ["*"]}}
                               :on-mcp-auth-request
                               (fn [request _ctx]
                                 (let [token (acquire-oauth-token! (:server-url request))]
                                   (if token
                                     {:access-token token :token-type "Bearer" :expires-in 3600}
                                     {:kind :cancelled})))}]
  ;; The handler fires when an MCP server emits mcp.oauth_required.
  )
```

The handler receives the `McpAuthRequest` map and a `{:session-id ...}` context,
and may return a `core.async` channel. Return a map with `:access-token` to
answer with a token, or `nil` / `{:kind :cancelled}` / throw to cancel. See
[MCP OAuth Handler](../reference/API.md#mcp-oauth-handler) for the full request
shape and result mapping. (upstream PR #1669)

## Troubleshooting

See the [MCP Debugging Guide](./debugging.md) for detailed troubleshooting.

### Common Issues

| Issue | Solution |
|-------|----------|
| Tools not showing up | Verify `:mcp-tools` is `["*"]` or lists specific tools |
| Server not starting | Check command path, use absolute paths when in doubt |
| Connection refused (HTTP) | Check URL and ensure the server is running |
| Timeout errors | Increase `:mcp-timeout` or check server performance |
| Tools work but aren't called | Make your prompt clearly require the tool's functionality |

## Related Resources

- [Model Context Protocol Specification](https://modelcontextprotocol.io/)
- [MCP Servers Directory](https://github.com/modelcontextprotocol/servers) — Community MCP servers
- [GitHub MCP Server](https://github.com/github/github-mcp-server) — Official GitHub MCP server
- [MCP Debugging Guide](./debugging.md) — Detailed MCP troubleshooting
- [Getting Started Guide](../getting-started.md) — SDK basics and custom tools
