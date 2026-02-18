# Documentation Guidelines

This file provides guidelines for AI agents updating documentation in the copilot-sdk-clojure project.

## Documentation Style

Follow the conventions in [`doc/style.md`](../../doc/style.md):

### Principles

1. **Code-first** — Lead with working Clojure examples, then explain
2. **Progressive disclosure** — Simplest usage first, layer complexity
3. **Direct tone** — Imperative mood, no filler ("simply", "just", "let's")
4. **Clojure-idiomatic** — Use Clojure terminology and conventions

### Code Examples

- All code blocks must be valid Clojure (parseable by `read-string`)
- Show `require` forms so readers know which namespace to import
- Use `;; =>` comments for return values
- Use `;; prints:` comments for output
- Include cleanup (`stop!`, `destroy!`) in lifecycle examples

### Conventions

- Use `copilot` as the alias for `github.copilot-sdk.client`
- Use `h` as the alias for `github.copilot-sdk.helpers`
- Use `session` as the alias for `github.copilot-sdk.session`
- Prefer `def` over `let` in top-level examples for clarity

### Terminology

Use Clojure terms consistently:

| Use | Don't use |
|-----|-----------|
| namespace | package, module |
| var | variable, field |
| map | object, dictionary, hash |
| keyword | enum, symbol (unless literally a symbol) |
| function | method |
| atom | mutable variable |
| channel | queue, stream (unless referring to core.async specifically) |
| require | import |

## Documentation Structure

### Key Files

- **`README.md`** - User-facing quick start and overview
- **`doc/index.md`** - Documentation hub / table of contents
- **`doc/reference/API.md`** - Detailed API reference for all public functions
- **`doc/getting-started.md`** - Step-by-step tutorial for new users
- **`doc/style.md`** - Documentation authoring style guide
- **`doc/auth/index.md`** - Authentication guide (all methods, priority order)
- **`doc/auth/byok.md`** - BYOK (Bring Your Own Key) provider guide
- **`doc/mcp/overview.md`** - MCP server configuration guide
- **`doc/mcp/debugging.md`** - MCP debugging and troubleshooting
- **`examples/README.md`** - Example documentation with usage instructions
- **`CHANGELOG.md`** - Version history (Keep a Changelog format)
- **`AGENTS.md`** - Guidelines for AI agents (internal)

### When to Update

Update documentation when:

- A public API function is added, changed, or removed
- A new example is added to `examples/`
- A configuration option is added or changed
- Authentication or MCP behavior changes
- Event types are added or modified
- The doc structure itself changes (update `doc/index.md`)

### Validation

After making documentation changes, always run:

```bash
bb validate-docs
```

This checks for:
- Broken internal links
- Unparseable Clojure code blocks
- Missing required files
- Structural issues

### Examples

When adding new features:

1. **Document in API.md** - Add function signature, parameters, return values, and examples
2. **Add to examples/** - Create a working example demonstrating the feature
3. **Update examples/README.md** - Document how to run the new example
4. **Update CHANGELOG.md** - Add entry under `[Unreleased]` section
5. **Validate** - Run `bb validate-docs` to ensure correctness

## Changelog Conventions

`CHANGELOG.md` follows [Keep a Changelog](https://keepachangelog.com/):

1. **Always update the `[Unreleased]` section** — add entries under `Added`, `Changed`, `Fixed`, or `Removed`
2. **Group related entries** — use sub-headings like `### Added (CI/CD)` when appropriate
3. **Upstream sync annotations** — when porting changes from an upstream copilot-sdk release, annotate
   the sub-heading with the upstream version: `### Added (v0.1.24 sync)`. Individual entries should also
   cite specific upstream PRs (e.g., "upstream PR #376"). Clojure-specific changes use other annotations
   like `(CI/CD)`, `(documentation)`, or none.
4. **Mark breaking changes** with `**BREAKING**:` prefix
5. On release, the maintainer moves `[Unreleased]` entries to a versioned section. The release version
   (`UPSTREAM.CLJ_PATCH`, e.g., `0.1.24.0`) encodes upstream parity.

## Cross-References

- Use **relative paths** between doc files: `[API Reference](reference/API.md)`
- From repo root (README, AGENTS.md): `[API Reference](./doc/reference/API.md)`
- From examples/: `[BYOK](../doc/auth/byok.md)`
- Never use absolute filesystem paths
- Link to specific sections with anchors: `[Events](reference/API.md#event-types)`

## Documentation Framework

This project uses standard Markdown documentation. While the style guide mentions Diátaxis framework concepts, the implementation uses simple Markdown files organized by purpose:

- **Tutorials**: Getting started guides (`doc/getting-started.md`)
- **How-to Guides**: Task-oriented guides (`doc/auth/`, `doc/mcp/`)
- **Reference**: Technical API documentation (`doc/reference/API.md`)
- **Explanation**: Conceptual documentation (embedded in guides)

Keep documentation pragmatic and accessible to Clojure developers.
