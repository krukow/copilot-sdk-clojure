# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]

### Added (upstream PR #329 sync)
- Windows console window hiding: CLI process is spawned with explicit PIPE redirects ensuring the JVM sets `CREATE_NO_WINDOW` on Windows — no console window appears in GUI applications. Equivalent to upstream `windowsHide: true` (upstream PR #329).

### Changed
- Recommended default model for non-streaming examples is `claude-haiku-4.5` instead of `gpt-5.2` for faster response times

## [0.1.26.0-SNAPSHOT] - 2026-02-20
### Added (upstream PR #510 sync)
- `:client-name` option for `create-session` and `resume-session` — identifies the application using the SDK, included in the User-Agent header for API requests. Forwarded as `clientName` on the wire (upstream PR #510).

### Changed (upstream PR #509 sync)
- **BREAKING**: Deny all permissions by default — `requestPermission` is now always `true` on the wire, and permission requests are denied when no `:on-permission-request` handler is configured. Previously, omitting the handler meant the CLI never asked for permission. To restore the old behavior, pass `:on-permission-request copilot/approve-all` in your session config.

### Added (upstream PR #509 sync)
- `approve-all` — convenience permission handler that approves all requests (`copilot/approve-all`). Equivalent to the upstream Node.js SDK `approveAll` export. Use as `:on-permission-request copilot/approve-all` in session config.
- Integration tests for deny-by-default permission model: wire format assertions, `approve-all` behavior, handler dispatch with/without handler, custom selective handler

### Changed
- MCP local server example now passes `:on-permission-request copilot/approve-all` (required for MCP tool execution under deny-by-default)

### Fixed
- Permission denial result `:kind` now consistently uses keywords (not strings) in default handler responses, matching specs and `approve-all` behavior

## [0.1.25.1] - 2026-02-18
### Fixed
- Release pipeline: GPG signing now fails fast with a clear error when no key is available, instead of silently producing unsigned artifacts that Maven Central rejects
- Release pipeline: `stamp-changelog` no longer throws when `[Unreleased]` is empty — prints a warning and exits cleanly

### Changed
- Metadata API example: suppressed SDK INFO log noise, improved session display (short IDs, summaries, timestamps), clearer messaging for unsupported CLI methods

## [0.1.25.0] - 2026-02-18
### Added
- **Core.async native async architecture** — eliminates all blocking operations from the async API path:
  - `<create-session` / `<resume-session` — async session lifecycle functions that return channels delivering `CopilotSession`, safe for use inside `go` blocks
  - Protocol layer now uses core.async channels instead of Java promises for RPC responses
  - Session send-lock replaced `java.util.concurrent.Semaphore` with a channel-based lock
  - `<send-async*` — fully non-blocking send pipeline using parking channel operations
  - The idiomatic pattern is now `(go (let [s (<! (<create-session client opts))] (<! (<send! s {:prompt "..."}))))` — no thread pool starvation

### Fixed
- Wire parity: always send `requestPermission`, `requestUserInput`, and `hooks` fields (as `false` when not configured) to match upstream Node.js SDK — fixes 400 errors when creating sessions without specifying a model
- MCP server environment variables now passed correctly as literal values to subprocesses — sends `envValueMode: "direct"` on session create/resume wire payloads (upstream PR #484)
- Fix potential semaphore deadlock in `send-and-wait!` and `send-async*` — `tap` on a closed mult could leave the send-lock permanently held; moved `tap` inside the `try/finally` block that releases the lock
- Multi-agent example parallelism: sessions and sends now run concurrently in `go` blocks instead of sequentially blocking

### Added (v0.1.24 sync)
- CLI stderr is now captured and forwarded to debug logging; included in error messages on startup failure for better diagnostics (inspired by upstream PR #492)
- `verify-protocol-version!` now races the initial ping against process exit to detect early CLI failures instead of blocking for 60 seconds on timeout
- `list-sessions` now accepts optional filter map `{:cwd :git-root :repository :branch}` to narrow results by session context (upstream PR #427)
- Session metadata from `list-sessions` now includes `:context` map with working directory info (`{:cwd :git-root :repository :branch}`) when available (upstream PR #427)
- `list-tools` — list available tools with metadata; accepts optional model param for model-specific overrides (upstream PR #464)
- `get-quota` — get account quota information (entitlements, usage, overage) (upstream PR #464)
- `get-current-model` — get the current model for a session (session-scoped) (upstream PR #464)
- `switch-model!` — switch the model for a session (session-scoped) (upstream PR #464)
- New event types: `session.context_changed`, `session.title_changed`, `session.warning` (upstream PRs #396, #427)
- `line-range` optional field on file/directory attachment specs (upstream session-events schema update)
- `agent-mode` optional field on `user.message` event data — one of `:interactive`, `:plan`, `:autopilot`, `:shell` (upstream session-events schema update)

### Changed
- **BREAKING**: Namespace prefix renamed from `krukow.copilot-sdk` to `github.copilot-sdk`.
  All requires must be updated (e.g., `github.copilot-sdk.client`, `github.copilot-sdk.helpers`).
- **Repository moved** to [`copilot-community-sdk/copilot-sdk-clojure`](https://github.com/copilot-community-sdk/copilot-sdk-clojure)
  on GitHub. Maven artifact unchanged: `io.github.copilot-community-sdk/copilot-sdk-clojure`.
- Git dependency URL in README fixed to point to new org

### Added (v0.1.23 sync)
- Selection attachment type support (`:selection` with `:file-path`, `:display-name`, `:selection-range`, `:text`)
- Session lifecycle event subscription via `on-lifecycle-event` (`:session.created`, `:session.deleted`, `:session.updated`, `:session.foreground`, `:session.background`)
- Enhanced model info with full capabilities (`:model-capabilities`), billing (`:model-billing`), and policy (`:model-policy`) structures
- `session.shutdown` and `skill.invoked` event types
- `"xhigh"` reasoning effort level

### Added (CI/CD)
- GitHub Actions CI workflow: runs `bb ci` (unit/integration tests, doc validation, jar build) on PRs and `main` pushes
- Daily documentation updater agentic workflow: automatically scans for merged PRs and updates docs
- `.github/instructions/documentation.instructions.md`: guidelines for AI agents updating documentation
- GitHub Actions Release workflow: manual dispatch with version management inputs (`sync-upstream`, `bump-clj-patch`, `set-version`), GPG signing, Maven Central deploy, and [SLSA build provenance attestation](https://github.com/copilot-community-sdk/copilot-sdk-clojure/attestations)
- `bb ci` task: runs tests, doc validation, and jar build (no copilot CLI required)
- `bb ci:full` task: full pipeline including E2E tests and examples (requires copilot CLI)
- Cross-platform `build.clj`: `md5-hash` and `sha1-hash` helpers with macOS/Linux fallback
- Idempotent `update-readme-sha`: succeeds when README already has current SHA
- `stamp-changelog` build task: automatically moves `[Unreleased]` entries to a versioned section with today's date and updates comparison links; integrated into the release workflow

### Changed (CI/CD)
- Release workflow now creates a PR with auto-merge instead of pushing directly to `main`,
  compatible with branch protection rules requiring PRs and status checks
- Release workflow creates a `vX.Y.Z.N` tag after successful deploy

### Added (documentation)
- `doc/index.md` — Documentation hub / table of contents
- `doc/style.md` — Documentation authoring style guide
- `doc/reference/API.md` — API reference (moved from `doc/API.md`)
- `PUBLISHING.md` — Maven Central publishing guide
- `script/validate_docs.clj` — Documentation validation script (`bb validate-docs`)
- `.github/skills/update-docs/SKILL.md` — Update-docs skill for regenerating docs after source changes

### Changed
- **BREAKING**: Version scheme changed to 4-segment format `UPSTREAM.CLJ_PATCH` (e.g., `0.1.22.0`)
  to track upstream copilot-sdk releases. See PUBLISHING.md for details.
- New build tasks: `sync-version` (align to upstream), `bump-version` (increment clj-patch)
- Replaced `cheshire/cheshire` (Clojars) with `org.clojure/data.json` (Maven Central)
  for JSON processing — eliminates Clojars and Jackson transitive dependencies
- **Deprecated** Clojars publishing (`net.clojars.krukow/copilot-sdk`). Use Maven Central
  (`io.github.copilot-community-sdk/copilot-sdk-clojure`) going forward.

### Removed
- Java API (`java_api.clj`), Java examples, and AOT compilation.
  For Java/JVM usage, see [copilot-sdk-java](https://github.com/copilot-community-sdk/copilot-sdk-java).
- `doc/intro.md` and `doc/java-async-api.md` (replaced by reorganized documentation)

### Added
- Resume session config parity with create-session (upstream PR #376):
  - `resume-session` now accepts `:model`, `:system-message`, `:available-tools`,
    `:excluded-tools`, `:config-dir`, and `:infinite-sessions` options
- API parity with official Node.js SDK (`@github/copilot-sdk`):
  - `:working-directory` option for `create-session` and `resume-session`
  - `:disable-resume?` option for `resume-session`
  - `get-foreground-session-id` and `set-foreground-session-id!` client methods (TUI+server mode)
  - `:large-output` marked as experimental (CLI protocol feature, not in official SDK)
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
  - `:reasoning-effort` session config option ("low", "medium", "high", "xhigh")
  - Model info now includes `:supports-reasoning-effort`, `:supported-reasoning-efforts`, `:default-reasoning-effort`
- Documentation:
  - `doc/getting-started.md` — Comprehensive tutorial
  - `doc/auth/index.md` — Authentication guide (all methods, priority order)
  - `doc/auth/byok.md` — BYOK (Bring Your Own Key) guide with examples for OpenAI, Azure, Anthropic, Ollama
  - `doc/mcp/overview.md` — MCP server configuration guide
  - `doc/mcp/debugging.md` — MCP debugging and troubleshooting guide
- New examples:
  - `examples/byok_provider.clj` — BYOK provider configuration
  - `examples/mcp_local_server.clj` — MCP local server integration
- BYOK validation: `create-session` and `resume-session` now throw when `:provider` is specified without `:model`

### Changed
- **BREAKING**: Event types are now namespaced keywords (e.g., `:copilot/session.idle` instead of `:session.idle`)
  - Migration: Add `copilot/` prefix to all event type keywords in your code
- **FIX**: MCP server config wire format now correctly strips `:mcp-` prefix before sending to CLI.
  Previously `:mcp-command`, `:mcp-args`, `:mcp-tools`, etc. were sent as `mcpCommand`, `mcpArgs`,
  `mcpTools` on the wire; they are now correctly sent as `command`, `args`, `tools` to match the
  upstream Node.js SDK. The Clojure API keys (`:mcp-command`, `:mcp-args`, etc.) are unchanged.
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

[Unreleased]: https://github.com/copilot-community-sdk/copilot-sdk-clojure/compare/v0.1.26.0-SNAPSHOT...HEAD
[0.1.26.0-SNAPSHOT]: https://github.com/copilot-community-sdk/copilot-sdk-clojure/compare/v0.1.25.1...v0.1.26.0-SNAPSHOT
[0.1.25.1]: https://github.com/copilot-community-sdk/copilot-sdk-clojure/compare/v0.1.25.0...v0.1.25.1
[0.1.25.0]: https://github.com/copilot-community-sdk/copilot-sdk-clojure/compare/0.1.0...v0.1.25.0
[0.1.0]: https://github.com/copilot-community-sdk/copilot-sdk-clojure/releases/tag/0.1.0
