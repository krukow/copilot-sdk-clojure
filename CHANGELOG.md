# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]

### Added
- New metadata APIs (upstream PR #77):
  - `get-status` - Get CLI version and protocol information
  - `get-auth-status` - Get current authentication status
  - `list-models` - List available models with metadata
- New event type `:copilot/tool.execution_progress` for progress updates during long-running tool executions
- Infinite sessions support (upstream PR #76):
  - `:infinite-sessions` config option for `create-session`
  - Automatic context compaction when approaching context window limits
  - New event types: `:copilot/session.compaction_start`, `:copilot/session.compaction_complete`
- Session workspace path accessors for Clojure and Java APIs
- New event type `:copilot/session.snapshot_rewind` for session state rollback (upstream PR #208)
- Exported event type constants:
  - `event-types` - All valid event types
  - `session-events` - Session lifecycle and state events
  - `assistant-events` - Assistant response events
  - `tool-events` - Tool execution events
- New example: `session_events.clj` - demonstrates monitoring session state events
- Authentication options for client (upstream PR #237):
  - `:github-token` - GitHub token for authentication (sets `COPILOT_SDK_AUTH_TOKEN` env var)
  - `:use-logged-in-user?` - Whether to use logged-in user auth (default: true, false when token provided)
- Hooks and user input handlers (upstream PR #269):
  - `:on-user-input-request` - Handler for `ask_user` tool invocations
  - `:hooks` - Lifecycle hooks map with callbacks:
    - `:on-pre-tool-use` - Called before tool execution
    - `:on-post-tool-use` - Called after tool execution
    - `:on-user-prompt-submitted` - Called when user sends a prompt
    - `:on-session-start` - Called when session starts
    - `:on-session-end` - Called when session ends
    - `:on-error-occurred` - Called on errors
- Reasoning effort support (upstream PR #302):
  - `:reasoning-effort` session config option ("low", "medium", "high")
  - Model info now includes `:supports-reasoning-effort`, `:supported-reasoning-efforts`, `:default-reasoning-effort`

### Changed
- **BREAKING**: Event types are now namespaced keywords (e.g., `:copilot/session.idle` instead of `:session.idle`)
  - Migration: Add `copilot/` prefix to all event type keywords in your code
- Protocol version bumped from 1 to 2 (requires CLI 0.0.389+)
- Removed `helpers/query-seq` in favor of `helpers/query-seq!` and `helpers/query-chan`
- `list-models` now caches results per client connection to prevent 429 rate limiting under concurrency (upstream PR #300)
  - Cache is cleared on `stop!` and `force-stop!`

## [0.1.0] - 2026-01-18
### Added
- Initial release of copilot-sdk-clojure
- Full port of JavaScript Copilot SDK to idiomatic Clojure
- JSON-RPC protocol layer with Content-Length framing
- CopilotClient for managing CLI server lifecycle
  - stdio and TCP transport support
  - Auto-start and auto-restart capabilities
- CopilotSession for conversation management
  - `send!`, `send-and-wait!`, `send-async` message methods
  - Event handling via core.async mult channels
  - Tool registration and invocation
  - Permission request handling
- Tool definition helpers with result builders
- System message configuration (append/replace modes)
- MCP server configuration support
- Custom agent configuration support
- Provider configuration (BYOK - Bring Your Own Key)
- Streaming support for assistant messages
- Comprehensive clojure.spec definitions
- Example applications:
  - Basic Q&A conversation
  - Custom tool integration
  - Multi-agent orchestration with core.async

### Dependencies
- org.clojure/clojure 1.12.4
- org.clojure/core.async 1.6.681
- org.clojure/spec.alpha 0.5.238
- cheshire/cheshire 5.13.0

[Unreleased]: https://github.com/krukow/copilot-sdk-clojure/compare/0.1.0...HEAD
[0.1.0]: https://github.com/krukow/copilot-sdk-clojure/releases/tag/0.1.0
