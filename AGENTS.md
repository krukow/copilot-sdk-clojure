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

## Syncing with Upstream

To check for upstream changes that may need porting:

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

### Running Examples

Examples are part of the test suite but run separately:

```bash
# Full testing includes all examples working (REQUIRED before committing)
./run-all-examples.sh
```

This runs all Clojure examples. Java examples are run via Maven (see below).

In addition, all snippets and API descriptions in documentation *.md files
should be checked for validity.

### Building

```bash
# Build JAR
clj -T:build aot-jar

# Install to local Maven repo (needed for Java examples)
clj -T:build install
```

### Version Management

```bash
# Bump version (updates build.clj, README.md, examples/java/pom.xml)
clj -T:build bump-version
```

## Java API

We maintain a Java interface for integration in Java projects.

### Key Files

- `src/krukow/copilot_sdk/java_api.clj` - Java-native classes and interfaces (AOT compiled)
- `examples/java/` - Java usage examples
- `examples/java/pom.xml` - Maven config (version must match build.clj)

### When Extending the API

When adding new features or changing the public API:

1. Update `java_api.clj` with corresponding Java-friendly interfaces
2. Update Java examples to demonstrate new functionality
3. Ensure `examples/java/pom.xml` version matches `build.clj`

### Testing Java Integration

```bash
# Install JAR locally first
clj -T:build aot-jar

# Install JAR locally first
clj -T:build install

# Run Java examples
cd examples/java && mvn compile && ./run-all-examples.sh
```

## Commit and Deploy Guidelines

### Before Committing

1. Run full test suite: `COPILOT_E2E_TESTS=true bb test`
2. Run all examples: `./run-all-examples.sh`
3. Run Java examples: `cd examples/java && mvn compile exec:java`
4. **Always ask for my review before committing** - do not commit autonomously

### Deployment

- **Do NOT deploy without explicit permission** - the maintainer will deploy
- You may bump version numbers when needed (`clj -T:build bump-version`)
- After version bump, update docs and `examples/java/pom.xml` if not automatic

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
src/krukow/copilot_sdk/
├── client.clj       # Main client API (create-client, create-session, etc.)
├── session.clj      # Session operations (send!, send-and-wait!, etc.)
├── helpers.clj      # Convenience functions (query, query-seq, etc.)
├── specs.clj        # clojure.spec definitions
├── instrument.clj   # Function specs and instrumentation
├── java_api.clj     # Java interop classes
└── util.clj         # Internal utilities

test/krukow/copilot_sdk/
├── *_test.clj       # Unit tests
└── integration_test.clj  # Integration and E2E tests

examples/
├── *.clj            # Clojure examples
└── java/            # Java examples
```

## Documentation

All these must be updated as appropriate when making changes:

- `README.md` - User-facing documentation and quick start
- `doc/API.md` - Detailed API reference - read this to understand the current API design
- `CHANGELOG.md` - Version history and changes
- `README-java.md` - Java-specific documentation
- `AGENTS.md` - update this file when significant changes happen (e.g.Project Structure)
