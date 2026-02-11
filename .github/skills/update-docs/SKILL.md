---
name: update-docs
description: Update SDK documentation by analyzing source code changes and regenerating affected doc pages. Use when new features are added, APIs change, or docs need refresh.
allowed-tools: View, Grep, Glob, Edit, Create, Task, Bash
user-invocable: true
---

# SDK Documentation Update Skill

You are updating the documentation for the copilot-sdk-clojure project. Your goal is to keep docs accurate, comprehensive, and aligned with the source code.

## Style Guide

**CRITICAL**: Before writing any documentation, read the full style guide at `doc/style.md`.

Key principles:

1. **Code-first** — Lead with working Clojure examples, then explain
2. **Progressive disclosure** — Simplest usage first, layer complexity
3. **Direct tone** — Imperative mood, no filler ("simply", "just", "let's")
4. **Clojure-idiomatic** — Use Clojure terminology (namespaces, vars, maps, keywords)

Every doc must pass the checklist in `doc/style.md` before completion.

## Documentation Structure

```
doc/
├── index.md              # Doc hub / navigation
├── getting-started.md    # Step-by-step tutorial
├── style.md              # Authoring conventions
├── guides/               # Topic guides
├── reference/
│   └── API.md            # Complete API reference
├── auth/
│   ├── index.md          # Authentication overview
│   └── byok.md           # Bring Your Own Key guide
├── mcp/
│   ├── overview.md       # MCP server integration
│   └── debugging.md      # MCP troubleshooting
└── api/                  # Auto-generated Codox HTML
```

## Source Code → Documentation Mapping

| Doc Area | Primary Sources | Affected Docs |
|----------|----------------|---------------|
| Helpers API | `src/krukow/copilot_sdk/helpers.clj` | `doc/reference/API.md` (Helpers section), `doc/getting-started.md` |
| Client API | `src/krukow/copilot_sdk/client.clj` | `doc/reference/API.md` (Client section) |
| Session API | `src/krukow/copilot_sdk/session.clj` | `doc/reference/API.md` (Session section) |
| Tools | `src/krukow/copilot_sdk/client.clj` (`define-tool`, `result-success`) | `doc/reference/API.md` (Tools section) |
| Specs | `src/krukow/copilot_sdk/specs.clj`, `src/krukow/copilot_sdk/instrument.clj` | `doc/reference/API.md` |
| MCP | `src/krukow/copilot_sdk/util.clj` (`mcp-server->wire`), `src/krukow/copilot_sdk/client.clj` | `doc/mcp/overview.md`, `doc/mcp/debugging.md` |
| Auth/BYOK | `src/krukow/copilot_sdk/client.clj` (`:provider` in `create-session`) | `doc/auth/index.md`, `doc/auth/byok.md` |
| Events | `src/krukow/copilot_sdk/client.clj` (`event-types`, `subscribe-events!`) | `doc/reference/API.md` (Events section) |
| Examples | `examples/*.clj` | `examples/README.md`, related doc pages |

## Update Workflow

### 1. Identify Scope

If the user specifies a scope (e.g., "update mcp docs"), focus on that area only.

If no scope is specified, scan for changes:

```bash
git diff --name-only HEAD~10 -- src/ examples/
```

Map changed files to affected docs using the source mapping table above.

### 2. Analyze Source Code

For each affected area, use the `explore` agent to gather information:

```
task agent_type: explore
prompt: "In /path/to/repo, find all public functions in src/krukow/copilot_sdk/helpers.clj. List function name, arglists, and docstring."
```

Key things to check in source:
- Public function signatures (`defn`, `defmacro` — skip `defn-` private fns)
- Docstrings
- Spec definitions in `specs.clj` (option keys, types, defaults)
- Default values and option maps
- Event type constants

### 3. Generate/Update Documentation in Parallel

**IMPORTANT**: When multiple doc files need updates, use parallel `general-purpose` subagents via the `task` tool.

For each doc file that needs updating, launch a subagent:

```
task agent_type: general-purpose
prompt: |
  You are updating Clojure SDK documentation.

  **STYLE GUIDE**: Read and follow doc/style.md strictly:
  - Lead with working Clojure code examples
  - Short paragraphs (1-3 sentences max)
  - Use tables for options/config keys
  - Use imperative mood
  - Show require forms in code blocks
  - Use ;; => for return values

  **YOUR TASK**: Update doc/reference/API.md — Helpers API section

  **RESEARCH**: Analyze src/krukow/copilot_sdk/helpers.clj for:
  - All public functions, their arglists, and docstrings
  - Option keys accepted (check specs.clj for spec definitions)
  - Return types and behavior

  **REQUIREMENTS**:
  - Update function signatures if changed
  - Add any new functions
  - Ensure code examples match current API
  - Use relative links for cross-references
```

### 4. Update Examples README

If examples changed, also update `examples/README.md`:
- Verify all listed examples still exist as files
- Update walkthroughs for changed examples
- Add entries for new examples

### 5. Quality Gate

After all updates, run validation:

```bash
bb validate-docs
```

Fix any errors (broken links, unparseable code blocks) before finishing.

Also verify:
- [ ] Code examples use correct `require` aliases (`copilot`, `h`, `session`)
- [ ] Tables have consistent formatting
- [ ] Cross-references use relative paths
- [ ] No filler language
- [ ] `doc/index.md` links are up to date

## Common Tasks

### New Public Function Added

1. Read the function from its source file
2. Add to the appropriate section in `doc/reference/API.md`
3. Include signature, description, options table, and example
4. If it's a major feature, consider updating `doc/getting-started.md`

### New Example Added

1. Add entry to the examples table in `README.md`
2. Add detailed walkthrough to `examples/README.md`
3. Include difficulty level, concepts covered, and usage command

### New Config/Session Option

1. Read from `specs.clj` and `client.clj`
2. Add to the session options table in `doc/reference/API.md`
3. Add example usage if behavior is non-obvious

### Auth/BYOK Changes

1. Read from `client.clj` (create-session provider handling)
2. Update `doc/auth/index.md` and/or `doc/auth/byok.md`
3. Verify example provider configs still work

### MCP Changes

1. Read from `util.clj` (mcp-server->wire) and `client.clj`
2. Update `doc/mcp/overview.md`
3. Update `doc/mcp/debugging.md` if error handling changed

## Arguments

$ARGUMENTS

### Usage Modes

**Mode 1: Full docs refresh (default)**

```
/update-docs
/update-docs all
```

Compares all source files against all docs. Updates everything out of sync.

**Mode 2: Specific section**

```
/update-docs helpers
/update-docs mcp
/update-docs auth
/update-docs getting-started
```

Only updates docs for the specified area. Valid: `helpers`, `client`, `session`, `tools`, `events`, `mcp`, `auth`, `getting-started`, `examples`.

**Mode 3: Branch delta**

```
/update-docs branch
```

Compares your branch against `main`, identifies changed source files, and updates only affected docs.

### Mode Detection

Parse `$ARGUMENTS` to determine mode:

1. If arguments contain `branch`: Use Mode 3 (branch delta)
2. If arguments contain a section name: Use Mode 2 (specific section)
3. If arguments are empty or `all`: Use Mode 1 (full refresh)

### Branch Delta Workflow (Mode 3)

```bash
# Get changed source files vs main
git diff --name-only main...HEAD -- src/ examples/

# Map to affected docs using source mapping table
# Launch parallel subagents for each affected doc
```

Example: If `src/krukow/copilot_sdk/helpers.clj` changed, update `doc/reference/API.md` (Helpers section) and `doc/getting-started.md`.
