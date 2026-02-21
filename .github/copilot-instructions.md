# AGENTS.md

Guidelines for AI agents working on this codebase.

## Project Overview

This is a **Clojure port of the GitHub Copilot SDK**. The upstream repository is [github/copilot-sdk](https://github.com/github/copilot-sdk).

### Design Philosophy

We do **NOT** blindly copy the upstream implementation. Instead, we create an idiomatic Clojure library that:

- Leverages Clojure language features and idioms (immutability, functional programming, state-mangement and
   concurrency features)
- Would be natural to integrate in Clojure projects
- Would make sense to expert Clojure programmers
- Uses Clojure libraries appropriately, particularly:
  - **core.async** for asynchronous operations
  - **clojure.spec** for data validation and documentation
  - other high-quality libraries as needed.

### Upstream References

When porting features or investigating behavior:

1. **Primary reference**: [nodejs implementation](https://github.com/github/copilot-sdk/tree/main/nodejs) (JavaScript/TypeScript)
2. **Secondary reference**: [python implementation](https://github.com/github/copilot-sdk/tree/main/python) for additional clarity
3. **Local upstream checkout**: The upstream repo is available at `../copilot-sdk` (relative to this repo).
4. **CLI runtime**: The CLI itself is useful for understanding
   protocol behavior, but the **SDK source of truth** is always the Node.js SDK, not the CLI protocol types.

### API Compatibility Rules

This SDK must maintain **strict API parity** with the official Node.js SDK (`@github/copilot-sdk`).

1. **Only expose what the official SDK exposes.** The CLI protocol often defines more options than the SDK surfaces. Do NOT add config options or methods just because the CLI accepts them — only add them when the Node.js SDK's `SessionConfig`, `CopilotClient`, etc. include them.

2. **When adding features**, always verify against:
   - `copilot-sdk/nodejs/src/types.ts` — canonical type definitions (`SessionConfig`, `ResumeSessionConfig`, `MessageOptions`, `CopilotClientOptions`)
   - `copilot-sdk/nodejs/src/client.ts` — `CopilotClient` methods and what params are sent on the wire
   - `copilot-sdk/nodejs/src/session.ts` — `CopilotSession` methods

3. **Experimental/CLI-only features** may be kept if clearly marked as experimental in docstrings and docs,
   with a note that they are not part of the official SDK API. Example: `:large-output` is accepted by the
   CLI protocol but not exposed in the Node.js SDK.

4. **Clojure-only additions** (convenience macros, core.async wrappers, internal tuning knobs) are fine
   as long as they don't conflict with the official API surface.

## Syncing with Upstream

Upstream syncing is **automated** via the daily agentic workflow (`.github/workflows/upstream-sync.md`, runs weekdays). It discovers upstream `github/copilot-sdk` changes, plans idiomatic Clojure ports, implements them, runs full CI, and opens a draft PR for @krukow to review.

To manually check for upstream changes that may need porting:

1. Review merged PRs: https://github.com/github/copilot-sdk/pulls?q=is%3Apr+is%3Aclosed
2. Compare with recent commits in this repository
3. For each upstream change, assess:
   - **Should it be ported?** (code changes yes, docs/CI/language-specific changes usually no)
   - **If yes**: Create a plan adapting it to Clojure idioms
   - **If no**: Document why (e.g., "Python-specific", "CI tooling only")

## Building and Testing

### Running Tests

```bash
# Unit tests only
bb test

# Full test suite including E2E tests (REQUIRED before committing)
COPILOT_E2E_TESTS=true bb test
```

**Copilot Coding Agent**: When running as a Copilot Coding Agent, `COPILOT_GITHUB_TOKEN` is set
by `copilot-setup-steps.yml`. Use it to authenticate E2E tests and examples:

```bash
export COPILOT_GITHUB_TOKEN="${COPILOT_GITHUB_TOKEN:-$GITHUB_TOKEN}"
COPILOT_E2E_TESTS=true bb test
./run-all-examples.sh
```

### Instrumented Testing

All public API functions have `clojure.spec` fdefs defined in `instrument.clj`.
When `github.copilot-sdk.instrument` is required (which happens automatically during
integration tests), every public function call is validated against its spec at runtime.

This means integration tests run with full spec checking — any argument or return
value that violates a spec will throw immediately. When adding new public functions:

1. Add an `s/fdef` in `src/github/copilot_sdk/instrument.clj`
2. Add the function to both `instrument-all!` and `unstrument-all!` lists
3. Ensure the corresponding specs exist in `src/github/copilot_sdk/specs.clj`
4. Run `bb test` — if specs are wrong, instrumented tests will catch it

### Running Examples

Examples are part of the test suite but run separately:

```bash
# Full testing includes all examples working (REQUIRED before committing)
./run-all-examples.sh
```

This runs all Clojure examples.

In addition, all snippets and API descriptions in documentation *.md files
should be checked for validity.

### Building

```bash
# Build JAR
clj -T:build jar

# Install to local Maven repo
clj -T:build install
```

### Version Management

This project uses 4-segment versioning: `UPSTREAM.CLJ_PATCH` (e.g., `0.1.22.0`).
First 3 segments track the upstream copilot-sdk release, 4th is Clojure-specific.

```bash
# Sync to new upstream release (updates build.clj, README.md)
clj -T:build sync-version :upstream '"0.1.23"'

# Bump Clojure patch: 0.1.22.0 -> 0.1.22.1
clj -T:build bump-version
```

## Commit and Deploy Guidelines

### Before Committing

1. Run full CI: `bb ci:full` (requires copilot CLI + auth)
   - This runs: E2E tests, examples, doc validation, and jar build
2. If copilot CLI is not available, run `bb ci` (unit/integration tests, doc validation, jar)
3. Run documentation validation: `bb validate-docs`
4. **Always ask for my review before committing** - do not commit autonomously

### Deployment

- **Do NOT deploy without explicit permission** - the maintainer will deploy
- You may bump version numbers when needed (`clj -T:build bump-version`)

## Code Quality Expectations

### Emphasis on Rigor

This project emphasizes **rigor and correctness**, particularly for:

- **Async code** (core.async channels, go blocks)
- **Protocol/wire code** (JSON-RPC communication with CLI server)
- **Sound process and resource management** robust error handling and cleanup to avoid leaking resources

### Spec Usage

- Use `clojure.spec` for data validation at API boundaries
- Runtime validation with helpful error messages
- Function specs (`s/fdef`) for public APIs in `instrument.clj`

### Testing

- Unit tests for pure functions
- Integration tests using mock server
- E2E tests against real Copilot CLI
- All examples serve as additional integration tests

## Project Structure

```
src/github/copilot_sdk/
├── client.clj       # Main client API (create-client, create-session, etc.)
├── session.clj      # Session operations (send!, send-and-wait!, etc.)
├── helpers.clj      # Convenience functions (query, query-seq!, query-chan, etc.)
├── specs.clj        # clojure.spec definitions
├── instrument.clj   # Function specs and instrumentation
└── util.clj         # Internal utilities
```

## Documentation

All these must be updated as appropriate when making changes:

- `README.md` - User-facing documentation and quick start
- `doc/index.md` - Documentation hub / table of contents
- `doc/reference/API.md` - Detailed API reference - read this to understand the current API design
- `doc/getting-started.md` - Step-by-step tutorial for new users
- `doc/style.md` - Documentation authoring style guide
- `doc/auth/index.md` - Authentication guide (all methods, priority order)
- `doc/auth/byok.md` - BYOK (Bring Your Own Key) provider guide
- `doc/mcp/overview.md` - MCP server configuration guide
- `doc/mcp/debugging.md` - MCP debugging and troubleshooting
- `CHANGELOG.md` - Version history and changes (see below)
- `AGENTS.md` - update this file when significant changes happen (e.g. Project Structure)

Run `bb validate-docs` to check for broken links, unparseable code blocks, and structural issues.
Use `/update-docs` skill (`.github/skills/update-docs/SKILL.md`) to regenerate docs after source changes.

### Changelog

`CHANGELOG.md` follows [Keep a Changelog](https://keepachangelog.com/) conventions. When making changes:

1. **Always update the `[Unreleased]` section** — add entries under `Added`, `Changed`, `Fixed`, or `Removed` as appropriate.
2. **Group related entries** — use sub-headings like `### Added (CI/CD)` when a batch of changes belongs to a theme.
3. **Upstream sync annotations** — when porting changes from an upstream copilot-sdk release, annotate
   the sub-heading with the upstream version: `### Added (v0.1.24 sync)`. This groups ported changes by
   their upstream origin and makes it clear which upstream release they correspond to. Individual entries
   should also cite specific upstream PRs (e.g., "upstream PR #376"). Changes that are Clojure-specific
   (not ported from upstream) use other theme annotations like `(CI/CD)`, `(documentation)`, or no annotation.
4. **Mark breaking changes** with `**BREAKING**:` prefix.
5. On release, the maintainer moves `[Unreleased]` entries to a versioned section and updates comparison links at the bottom.
   The Clojure SDK version (`UPSTREAM.CLJ_PATCH`, e.g., `0.1.24.0`) encodes which upstream release is included,
   so the sync annotations in the changelog and the release version together show upstream parity.
