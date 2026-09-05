# Documentation

Clojure SDK for programmatic control of the GitHub Copilot CLI via JSON-RPC.

## Getting Started

- [Getting Started](getting-started.md) — Step-by-step tutorial building a weather assistant
- [Examples](https://github.com/copilot-community-sdk/copilot-sdk-clojure/blob/main/examples/README.md) — 20 working examples with walkthroughs

## Guides

- [Authentication](auth/index.md) — Session-scoped token providers, GitHub auth, OAuth, environment variables, priority order
- [BYOK Providers](auth/byok.md) — Bring Your Own Key for OpenAI, Azure, Anthropic, Ollama
- [Azure Managed Identity](auth/azure-managed-identity.md) — Azure BYOK with Managed Identity (no API keys)
- [MCP Servers](mcp/overview.md) — Model Context Protocol server integration
- [MCP Debugging](mcp/debugging.md) — Troubleshooting MCP connections
- [Custom Agents](guides/custom-agents.md) — Define specialized agents with scoped tools for sub-agent orchestration
- [Agent Factories](guides/agent-factories.md) — Reverse-executed, resumable extension workflows, **experimental**

## Features

Quick links to the major SDK capabilities (see the [API Reference](reference/API.md) for full detail):

- [Streaming events](reference/API.md#streaming) — incremental assistant/reasoning deltas
- [Tools](reference/API.md#tools) and [Tool Sets](reference/API.md#tool-sets) — expose and filter callable tools
- [Session Hooks](reference/API.md#session-hooks) — pre/post tool-use and lifecycle interception
- [UI Elicitation](reference/API.md#ui-elicitation) — `ask_user` / structured elicitation handlers
- [File & Blob Attachments](reference/API.md#file-attachments) — image and binary input
- [Custom Agents](guides/custom-agents.md) — specialized sub-agents with scoped tools
- [BYOK Providers](auth/byok.md) — OpenAI, Azure, Anthropic, Ollama
- [MCP Servers](mcp/overview.md) — Model Context Protocol integration
- [Observability](reference/API.md#observability) — OpenTelemetry export and session telemetry. **Privacy note:** `:capture-content?` records prompt/response content and is **off by default** — enable only in trusted environments.
- [Client Mode `:empty`](reference/API.md#client-mode-empty) — multi-tenant SaaS isolation. **Security note:** hardens sessions against local machine state; intended for hosts serving multiple users.
- [Session Filesystem](reference/API.md#session-filesystem) — route filesystem operations through host-provided handlers. **Security note:** the host fully controls session file access.
- Remote / cloud sessions (`:remote-session`, `:cloud`) and [fleet mode](reference/API.md#experimental-rpc-methods) — **experimental**; not covered by GA semver guarantees.
- [Agent Factories](guides/agent-factories.md) — reverse-executed, resumable workflows for extensions. **experimental**; not covered by GA semver guarantees.

## Reference

- [API Reference](reference/API.md) — Complete API: helpers, client, session, events, tools
- [Generated API Docs](https://github.com/copilot-community-sdk/copilot-sdk-clojure/blob/main/doc/api/index.html) — Codox-generated namespace documentation

## Architecture Decisions

- [ADR: Defer a host-owned inference boundary](adr/2026-08-10-host-owned-inference-boundary.md) -- **Accepted.** Keeps the upstream experimental `requestHandler` / `llmInference.*` lifecycle outside the supported SDK until concrete Clojure demand or upstream stabilization/material redesign.
- [ADR: Add scope-bound query sequences before deprecating query-seq!](adr/2026-08-08-query-seq-scoped-lifecycle.md) -- **Accepted.** Adds `with-query-seq` as the safe default for streaming/seq-style consumption; `query-seq!` deprecation and removal are deferred, separate future steps.

## Contributing

- [Style Guide](style.md) — Documentation authoring conventions
- [Code Generation](codegen.md) — Schema-driven generation of clojure.spec definitions
- [Matched Node/Clojure Benchmarks](https://github.com/copilot-community-sdk/copilot-sdk-clojure/blob/main/benchmarks/README.md) — Deterministic cross-SDK performance evidence
- [Upstream Doc Gap Matrix](upstream-doc-gap-matrix.md) — Per-topic coverage vs the upstream SDK docs
- [AGENTS.md](https://github.com/copilot-community-sdk/copilot-sdk-clojure/blob/main/AGENTS.md) — Guidelines for AI agents working on this codebase
- [PUBLISHING.md](https://github.com/copilot-community-sdk/copilot-sdk-clojure/blob/main/PUBLISHING.md) — Versioning, CI/CD workflows, release process, build attestation
- [CHANGELOG](https://github.com/copilot-community-sdk/copilot-sdk-clojure/blob/main/CHANGELOG.md) — Version history
