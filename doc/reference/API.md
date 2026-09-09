# API Reference

## Helpers API

The helpers namespace provides simple, stateless query functions with automatic client management.

```clojure
(require '[github.copilot-sdk.helpers :as h])
```

Helpers do not stop caller-supplied clients or disconnect caller-supplied
sessions. Sessions created internally by a helper are helper-owned and release
their local resources even if remote disconnect fails.

### `query`

```clojure
(h/query prompt & {:keys [client session timeout-ms]})
```

Execute a query and return the response text.

**Options:**
- `:client` - Client options map (cli-path, log-level, cwd, env) OR a CopilotClient instance
- `:session` - Session options map (model, system-prompt, tools, etc.) OR a CopilotSession instance
- `:timeout-ms` - Timeout in milliseconds (default: 60000)

When `:session` is a CopilotSession instance, the query uses that session directly (enabling multi-turn conversations). When `:client` is a CopilotClient instance, it uses that client directly.

```clojure
;; Simple query (shared client, fresh session)
(h/query "What is 2+2?")
;; => "4"

;; With session options
(h/query "Explain monads" :session {:on-permission-request copilot/approve-all :model "claude-sonnet-4.5"})

;; With system prompt
(h/query "Hello" :session {:on-permission-request copilot/approve-all :system-prompt "Be concise."})

;; With explicit client
(copilot/with-client [client {}]
  (h/query "What is Clojure?" :client client))

;; With explicit session (multi-turn conversation)
(copilot/with-client [client {}]
  (copilot/with-session [session client {:on-permission-request copilot/approve-all}]
    (h/query "My name is Alice." :session session)
    (h/query "What is my name?" :session session))) ;; context preserved!
```

### `with-query-seq`

```clojure
(h/with-query-seq [events prompt & {:keys [client session max-events timeout-ms]}]
  body)
```

Execute a query, bind a bounded lazy sequence of events for the dynamic extent of `body`, and disconnect the session when the body exits.

Use this as the default seq-style streaming helper. Cleanup runs when `body` returns, throws, stops after a partial realization such as `(first events)`, or consumes the sequence to a terminal event.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `:client` | map or `CopilotClient` | `nil` | Client options map or caller-owned client |
| `:max-events` | non-negative integer | `256` | Maximum number of events to emit; `0` disconnects immediately |
| `:session` | map | `nil` | Session options map |
| `:timeout-ms` | positive integer or nil | `60000` | One deadline starts after session creation and covers blocking `session.send` acknowledgement plus event consumption; nonterminal autopilot idle events do not reset it. `nil` disables the deadline. Expiry throws `ExceptionInfo` with `{:type :query-timeout}` before `body` begins if send exhausts the deadline, otherwise during realization |

Client startup and session creation are outside the deadline. Synchronous
subscription is not preempted. The blocking `session.send` request receives the
remaining time. If the deadline is exhausted before or during that request,
`with-query-seq` throws before entering `body`; once the sequence is bound, the
same fixed deadline controls event reads.

```clojure
(h/with-query-seq [events "Tell me a story"
                   :session {:on-permission-request copilot/approve-all
                             :streaming? true}]
  (->> events
       (filter #(= :copilot/assistant.message_delta (:type %)))
       (map #(get-in % [:data :delta-content]))
       (run! print)))
```

Do not let `events` escape the body. The session is closed when the macro exits, like `with-open`.

### `query-seq!`

```clojure
(h/query-seq! prompt & {:keys [client session max-events timeout-ms]})
```

Execute a query and return a bounded lazy sequence of events (default: 256 events). This function is still supported.
Pass a client options map to use the helpers-managed client, or a started
`CopilotClient` instance to keep client lifecycle ownership with the caller.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `:client` | map or `CopilotClient` | `nil` | Client options map or caller-owned client |
| `:max-events` | non-negative integer | `256` | Maximum number of events to emit; `0` disconnects immediately |
| `:session` | map | `nil` | Session options map |
| `:timeout-ms` | positive integer or nil | `60000` | One deadline starts after session creation and covers blocking `session.send` acknowledgement plus event consumption; nonterminal autopilot idle events do not reset it. `nil` disables the deadline. Expiry throws `ExceptionInfo` with `{:type :query-timeout}` before the function returns if send exhausts the deadline, otherwise during realization |

The deadline has the same scope and observation semantics as
`with-query-seq`.

**Warning:** cleanup (session disconnect) runs only when the sequence is consumed to its natural end — a
terminal `:copilot/session.idle` / `:copilot/session.error` event, or the events channel closing (detected
when the next read yields `nil`, the end-of-stream sentinel — not an emitted element). An idle event whose
wire `:mode` is the string `"autopilot"` is emitted as a nonterminal turn boundary. Abandoning the seq before it
reaches a terminal event (e.g. `(first ...)` or `(take 1 ...)` when the first element isn't already terminal),
or hitting a positive `:max-events` bound before that end of stream, leaks
the session and its event tap (the sole exception is `:max-events 0`, which disconnects
immediately without emitting anything). Consume the whole seq, or use `with-query-seq` or `query` when you may stop reading early.

```clojure
(run! println
      (h/query-seq! "Tell me a story"
                    :session {:on-permission-request copilot/approve-all
                              :streaming? true}))
```

### `query-chan`

```clojure
(h/query-chan prompt & {:keys [client session buffer]})
```

Execute a query and return a bounded core.async channel of events. The channel closes after a
terminal `:copilot/session.idle` or `:copilot/session.error` event. An idle event whose wire
`:mode` is the string `"autopilot"` is emitted without closing the channel.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `:buffer` | positive integer | `256` | Maximum number of events buffered before producer backpressure |
| `:client` | map | `nil` | Client options used for the shared client |
| `:session` | map | `nil` | Session options map |

```clojure
(require '[clojure.core.async :refer [<! close! go-loop]])

(def events
  (h/query-chan "Tell me a story"
               :session {:on-permission-request copilot/approve-all
                         :streaming? true}))

(go-loop [remaining 10]
  (when-let [event (<! events)]
    (when (= :copilot/assistant.message_delta (:type event))
      (print (get-in event [:data :delta-content])))
    (if (= remaining 1)
      (close! events)
      (recur (dec remaining)))))
```

Close the returned channel when you stop consuming before a terminal event. Closing cancels
the hidden query and disconnects its session even when the output buffer is full. The example
above closes after ten events instead of abandoning the channel.

Values accepted into the buffer before cancellation remain readable after `close!`. An
in-flight event whose parked put loses to cancellation may be dropped; cancellation is not a
lossless drain.

On natural completion, `query-chan` disconnects its hidden session before closing. If that
disconnect fails after a terminal event or source closure, the channel yields a tagged
`:copilot/session.error` map and then closes; the original failure is available at
`[:data :cause]`. When a consumer explicitly closes the channel, cleanup still runs and logs a
cleanup failure because it cannot be delivered through the already-closed channel. In both
cases, local resources for the hidden session are released even when remote disconnect fails.

### `shutdown!`

```clojure
(h/shutdown!)
```

Explicitly shutdown the shared client. Safe to call multiple times.

### `client-info`

```clojure
(h/client-info)
;; => {:client-opts {:log-level :info, ...} :connected? true}
```

Get information about the current shared client state. Returns `nil` if no shared client exists, otherwise a map with `:client-opts` and `:connected?` keys.

---

## CopilotClient

```clojure
(require '[github.copilot-sdk :as copilot])
```

### Naming and shape differences vs the official SDK

The Clojure SDK maintains strict API parity with the official Node.js SDK
(`@github/copilot-sdk`), but a handful of names and return shapes are
adapted to Clojure idioms. When translating Node.js examples, use this map:

| Clojure | Official Node.js SDK | Notes |
|---------|----------------------|-------|
| `:disable-resume?` | `suppressResumeEvent` | Config key on `resume-session` / `join-session`. When true, skips emitting the `session.resume` event. Defaults to `true` in `join-session` (matching upstream), `false` elsewhere. |
| `:max-input-tokens` | `maxPromptTokens` | BYOK provider/model config key (input/prompt token cap). Serialized back to `maxPromptTokens` on the wire. |
| `:source {:agent-id "reviewer"}` | `source: "agent-reviewer"` | `send` message provenance. Fixed sources use `:user` or `:system`; identified agents use a map so the opaque ID is preserved exactly before the `agent-` wire prefix is added. |
| `join-session` return `{:client :session}` | `joinSession()` returns `CopilotSession` | Clojure has no implicit/global client, so it returns both so the caller can own the client lifecycle. See [`join-session`](#join-session). |
| `join-session` return `:granted-environment-variables` | `joinSession()` writes grants to `process.env` | The JVM cannot portably mutate process environment variables. When an extension requests environment access, Clojure returns approved values as a string-keyed map filtered to the exact requested names. |

These are the only cases where a public Clojure key or return value does
not map 1:1 to the upstream name. Everything else follows the standard
kebab-case ↔ camelCase wire convention (e.g. `:working-directory` ↔
`workingDirectory`), which is applied automatically and needs no lookup.

### Constructor

```clojure
(copilot/client options)
```

**Options:**

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `:cli-path` | string | `"copilot"` | Path to CLI executable. Falls back to `COPILOT_CLI_PATH` env var when not set |
| `:cli-args` | vector | `[]` | Extra arguments prepended before SDK-managed flags |
| `:builtin-plugin-directories` | vector of strings | `[]` | Absolute paths to trusted plugin directories bundled by the host. The complete non-empty set is registered once after the protocol handshake and before any session or session filesystem provider. A registration failure force-stops the client and fails `start!`. Distinct from the per-session `:plugin-directories` option. ([upstream PR #2330](https://github.com/github/copilot-sdk/pull/2330)) |
| `:cli-url` | string | nil | Address of an existing CLI server (for example, `"localhost:8080"`, `"http://localhost:8080"`, or `"[::1]:8080"`). The transport is plaintext TCP, so `https://` is rejected rather than silently downgraded. Bracketed hosts must be valid IPv6 literals. When provided, no CLI process is spawned |
| `:client-info` | map | nil | Application and integration identity sent on the `connect` handshake. Optional string keys: `:application-name`, `:application-version`, `:integration-name`, and `:integration-version`. Empty fields are omitted independently; the entire wire `clientInfo` object is omitted when no non-empty field remains. |
| `:port` | number | `0` | Server port (0 = random) |
| `:use-stdio?` | boolean | `true` | Use stdio transport instead of TCP |
| `:log-level` | keyword | `:info` | One of `:none` `:error` `:warning` `:info` `:debug` `:all` |
| `:auto-start?` | boolean | `true` | Auto-start server on first operation |
| `:auto-restart?` | boolean | `true` | Auto-restart on crash |
| `:notification-queue-size` | number | `4096` | Max queued protocol notifications |
| `:router-queue-size` | number | `4096` | Max queued non-session notifications |
| `:request-handler-threads` | number | `16` | Max reverse-RPC handlers (hooks, sessionFs, factories, user input, etc.) executing concurrently. Handlers run on a bounded worker pool owned by the connection, never on core.async dispatch |
| `:request-handler-queue-size` | number | `256` | Max reverse RPCs queued once every worker is busy. Beyond `threads + queue-size` outstanding requests the runtime receives an explicit `-32000` `request_handler_saturated` error |
| `:tool-timeout-ms` | number | `120000` | Timeout for tool handlers returning channels |
| `:cwd` | string | nil | Working directory for CLI process |
| `:env` | map | nil | Environment variables |
| `:github-token` | string | nil | GitHub token for authentication. Sets `COPILOT_SDK_AUTH_TOKEN` env var and passes `--auth-token-env` flag |
| `:use-logged-in-user?` | boolean | `true` | Use logged-in user auth. Defaults to `false` when `:github-token` is provided. Cannot be used with `:cli-url` |
| `:copilot-home` | string | nil | Base directory for Copilot data files. Sets `COPILOT_HOME` env var on the spawned CLI. (upstream PR #1191) |
| `:tcp-connection-token` | string | nil | Connection token for the headless CLI server (TCP only). When the SDK spawns its own CLI in TCP mode and this is omitted, a UUID is generated automatically so the loopback listener is safe by default. The token is sent to the CLI via `COPILOT_CONNECTION_TOKEN` and forwarded over the wire on the new `connect` handshake. Rejected when combined with `:use-stdio? true`. (upstream PR #1176) |
| `:remote?` | boolean | `false` | When `true`, append `--remote` to the spawned CLI args so the CLI exposes the session over a GitHub-hosted remote endpoint. Ignored when `:cli-url` is set. (upstream PR #1192) |
| `:session-idle-timeout-seconds` | integer | `0` (disabled) | Server-wide session idle timeout in seconds. When `> 0`, append `--session-idle-timeout <n>` to the spawned CLI so idle sessions are cleaned up after the given duration. |
| `:on-list-models` | fn | nil | Zero-arg function returning model info maps. Bypasses `models.list` RPC; does not require `start!`. Results are cached the same way as RPC results |
| `:telemetry` | map | nil | OpenTelemetry export config, applied as environment variables to the **spawned** CLI (ignored when connecting to an existing server via `:cli-url` or a parent process via `:is-child-process?`, since no CLI is spawned). When present, enables OTel. Keys (all optional): `:otlp-endpoint` (OTLP HTTP endpoint), `:otlp-protocol` (`"http/json"` or `"http/protobuf"` — sets `OTEL_EXPORTER_OTLP_PROTOCOL`), `:file-path` (write spans to a file), `:exporter-type` (exporter selection), `:source-name` (service/source name), `:capture-content?` (boolean — capture prompt/response content; **off by default for privacy**). See [Observability](#observability). (upstream PR #785, [PR #1648](https://github.com/github/copilot-sdk/pull/1648)) |
| `:on-get-trace-context` | fn | nil | Zero-arg function returning `{:traceparent "..." :tracestate "..."}`, called per request (session create/resume and each message send) to propagate a distributed-trace context. Only `:traceparent` and `:tracestate` are forwarded. See [Observability](#observability) |
| `:on-github-telemetry` | fn | nil | **@experimental / Internal.** One-arg callback receiving each forwarded GitHub telemetry notification. Registering it adds `enableGitHubTelemetryForwarding: true` to the `connect` handshake (so the first session's un-replayable `session.start` telemetry is forwarded — upstream PR #1909) as well as to the wire params of `session.create` and `session.resume`; the runtime then emits connection-global `gitHubTelemetry.event` notifications. A throwing handler is caught and logged (WARN) and cannot corrupt dispatch. Not a stable public SDK surface. See [Observability](#observability). ([PR #1835](https://github.com/github/copilot-sdk/pull/1835)) |
| `:is-child-process?` | boolean | `false` | When `true`, connect via own stdio to a parent Copilot CLI process (no process spawning). Requires `:use-stdio?` `true`; mutually exclusive with `:cli-url` |
| `:session-fs` | map | nil | Session filesystem provider config. Keys: `:initial-cwd` (string, required), `:session-state-path` (string, required), `:conventions` (`"windows"` or `"posix"`, required). When set, the client calls `sessionFs.setProvider` on connect and routes filesystem operations through per-session handlers. See [Session Filesystem](#session-filesystem) |
| `:mode` | keyword | `:copilot-cli` | Client multitenancy mode: `:copilot-cli` (default — preserve historical CLI behavior) or `:empty` (multi-tenant SaaS hosts that must isolate sessions from local machine state). In `:empty` mode the SDK requires at least one tenant-scoped storage root (`:copilot-home`, `:session-fs`, `:cli-url`, or `:is-child-process?`), sets `COPILOT_DISABLE_KEYTAR=1` on the spawned CLI, spreads 10 safe defaults under caller session config, forces `installedPlugins []`, and normalizes `:system-message` to strip `environment_context`. See [Client Mode](#client-mode-empty). (upstream PR #1428) |

### Methods

#### `start!`

```clojure
(copilot/start! client)
```

Start the CLI server and establish connection. Blocks until connected.

#### `with-client`

```clojure
(copilot/with-client [client {:log-level :info}]
  ;; use client
  )
```

Create a client, start it, and ensure `stop!` runs on exit.

#### `stop!`

```clojure
(copilot/stop! client)
```

Stop the server and close all sessions gracefully.

For SDK-spawned processes (not `:external-server?`), `stop!` issues a
`runtime.shutdown` RPC before closing the connection, giving the CLI a chance to
flush state and exit cleanly. The call is bounded by a 10-second timeout; on
timeout or error the SDK falls back to terminating the process (SIGTERM, then
SIGKILL). Connecting to an external server (`:cli-url`) skips the shutdown RPC and
the process is left running. (upstream [PR #1667](https://github.com/github/copilot-sdk/pull/1667))

Returns a vector of any errors encountered during cleanup — a failed session
disconnect, a failed `runtime.shutdown`, or a transport resource that could not
be released. An empty vector means everything shut down cleanly. Errors are
reported rather than thrown, so a single failure never leaves the rest of the
teardown undone.

#### `force-stop!`

```clojure
(copilot/force-stop! client)
```

Force stop the CLI server without graceful RPCs. It closes local session event
subscriptions and releases in-flight session work before closing the transport
and terminating an SDK-owned process. Use when `stop!` takes too long.

The forced kill is confirmed rather than assumed: the child is signalled and
then waited on for a bounded window. A child that survives is logged with its
resource identity, and its handle is kept in client state so the host is never
left holding no reference to a live process. The wait ends as soon as the child
dies, so it is a worst-case bound, not a fixed delay. `force-stop!` still
returns `nil`.

#### `client-options`

```clojure
(copilot/client-options client)
;; => {:log-level :info, :use-stdio? true, :auto-start? true, ...}
```

Get the options that were used to create this client.

#### `create-session`

```clojure
(copilot/create-session client config)
```

Create a new conversation session.

#### `with-session`

```clojure
(copilot/with-session [session client {:model "gpt-5.4"
                                       :on-permission-request copilot/approve-all}]
  ;; use session
  )
```

Create a session and ensure `disconnect!` runs on exit. If the body and
`disconnect!` both fail, the body failure remains primary and the cleanup
failure is attached as a suppressed exception. A cleanup-only failure is
thrown.

#### `with-client-session`

```clojure
;; Form 1: [session session-opts] - anonymous client with default options
(copilot/with-client-session [session {:model "gpt-5.4"
                                       :on-permission-request copilot/approve-all}]
  ;; use session
  )

;; Form 2: [client-opts session session-opts] - anonymous client with custom options
(copilot/with-client-session [{:log-level :debug} session {:model "gpt-5.4"
                                                           :on-permission-request copilot/approve-all}]
  ;; use session
  )

;; Form 3: [client session session-opts] - named client with default options
(copilot/with-client-session [client session {:model "gpt-5.4"
                                              :on-permission-request copilot/approve-all}]
  ;; use client and session
  )

;; Form 4: [client client-opts session session-opts] - named client with custom options
(copilot/with-client-session [client {:log-level :debug} session {:model "gpt-5.4"
                                                                  :on-permission-request copilot/approve-all}]
  ;; use client and session
  )
```

Create a client and session together, ensuring both are asked to clean up on
exit. A thrown session cleanup failure follows the `with-session` contract:
body failures remain primary and the session cleanup failure is attached as a
suppressed exception. Client `stop!` failures are returned in its error vector
rather than thrown, so this macro cannot attach them as suppressed exceptions.
Use explicit lifecycle management when the caller must inspect client cleanup
failures.

**Config:**

| Key | Type | Description |
|-----|------|-------------|
| `:session-id` | string | Custom session ID (optional) |
| `:client-name` | string | Client name to identify the application (included in User-Agent header) |
| `:model` | string | Model to use (`"gpt-5.4"`, `"claude-sonnet-4.5"`, etc.) |
| `:tools` | vector | Custom tools exposed to the CLI |
| `:system-message` | map | System message customization (see below) |
| `:available-tools` | vector | List of allowed tool names |
| `:excluded-tools` | vector | List of excluded tool names |
| `:tool-search` | map | Configure runtime tool search for `create-session`, `resume-session`, and `join-session`. Optional keys: `:enabled` (boolean) and `:defer-threshold` (integer). This session-level configuration is distinct from a tool definition's `:defer` policy; omit it to use runtime defaults. |
| `:provider` | map | Provider config for BYOK (see [BYOK docs](../auth/byok.md)). Required key: `:base-url`. Optional: `:provider-type` (`:openai`/`:azure`/`:anthropic`), `:wire-api` (`:completions`/`:responses`), `:api-key`, `:bearer-token`, `:azure-options`, `:headers` (map of HTTP header name→value, sent with each provider request — upstream PR #1094), `:model-id` (string — the model identifier to send to the provider; overrides session `:model`), `:wire-model` (string — model name as sent on the provider wire when it differs from `:model-id`), `:max-input-tokens` (integer — input/prompt token cap; serialized as wire `maxPromptTokens`), `:max-output-tokens` (integer — output token cap), `:transport` (`:http`/`:websockets` — provider transport; serialized as wire `transport` — upstream PR #1711), `:bearer-token-provider` (fn — dynamic bearer-token callback, see [BYOK docs](../auth/byok.md#dynamic-bearer-tokens) — upstream PR #1748). The four override fields were added in upstream PR #966 |
| `:providers` | vector | (Experimental) Multi-provider BYOK registry — a vector of named providers. Each entry takes the connection fields of `:provider` — `:base-url` (required), `:provider-type`, `:wire-api`, `:api-key`, `:bearer-token`, `:azure-options`, `:headers`, `:bearer-token-provider` — plus a required `:name` (the registry key, no `/`). Unlike the singular `:provider`, a named provider does **not** accept `:transport` or the inline model-override fields (`:model-id`, `:wire-model`, `:max-input-tokens`, `:max-output-tokens`); model overrides are declared in `:models` instead. Pairs with `:models` to declare a model catalog. Cannot be combined with the singular `:provider`. (upstream PR #1718) |
| `:models` | vector | (Experimental) Model catalog referencing the `:providers` registry. Each entry: `:id` (required, provider-local model id), `:provider` (required, a `:name` in `:providers`), and optional override fields (`:model-id`, `:wire-model`, `:capabilities`, `:max-input-tokens`, `:max-context-window-tokens`, `:max-output-tokens`). Prefer the canonical `:capabilities` idiom documented for `:model-capabilities`; exact string-keyed wire maps remain accepted as a deprecated compatibility escape hatch. The full model selection id is `"providerName/id"`. Cannot be combined with the singular `:provider`. (upstream PR #1718) |
| `:capi` | map | CAPI (Copilot API) session options. Optional keys: `:enable-web-socket-responses` (boolean) and `:auto-tier` (`:efficiency`, `:balance`, or `:intelligence`). The tier is serialized as `capi.autoTier`; omission uses or restores the runtime's persisted preference. Supplying a different tier while resuming a resident session requests a safe runtime switch. Experimental live setters, nullable reset, and tier-status APIs are not exposed. ([upstream PR #2437](https://github.com/github/copilot-sdk/pull/2437), [upstream PR #2514](https://github.com/github/copilot-sdk/pull/2514)) |
| `:feature-flags` | map | Host-resolved feature flag overrides as string keys and boolean values. Omission sends no wire key; an explicit `{}` is forwarded and remains distinct from omission. Valid on create, resume, and join. ([upstream PR #2451](https://github.com/github/copilot-sdk/pull/2451)) |
| `:excluded-builtin-agents` | vector | Names of built-in agents to hide/exclude from the session. Serialized as wire `excludedBuiltinAgents`. (upstream PR #1865) |
| `:enable-citations` | boolean | (Experimental) Opt into native model citations. Gated on `some?` — an explicit `false` is forwarded; an absent key is omitted. Serialized as wire `enableCitations`. (upstream PR #1865) |
| `:enable-file-change-tracking?` | boolean | Opt into file-change capture for cumulative session diffs. Omission sends no key; explicit `false` and `true` are preserved as `enableFileChangeTracking` on create, resume, and join. On resume, tracking starts only when the runtime still has a valid baseline and cannot reconstruct earlier untracked turns. Observe stable file-change and snapshot events through the normal event APIs; experimental low-level rewind RPCs are intentionally not exposed. |
| `:session-limits` | map | (Experimental) Session AI-credit limits. `{:max-ai-credits <number>}` — serialized as wire `sessionLimits.maxAiCredits`. (upstream PR #1865) |
| `:enable-managed-settings?` | boolean | Opt-in. When true, the runtime self-fetches enterprise managed settings (bypass-permissions policy) at session bootstrap using the session's `:github-token` (required; the runtime fails closed if omitted). Gated on `some?` — an explicit `false` is forwarded verbatim; an absent key is omitted. Serialized as wire `enableManagedSettings`. (upstream PR #1925) |
| `:managed-settings` | map | Structured enterprise managed-settings payload, supplied by the caller instead of (or alongside) `:enable-managed-settings?`. Optional key `:permissions`: `{:disable-bypass-permissions-mode :disable, :deny [...], :ask [...], :allow [...]}`. The policy accepts any wire string or a simple keyword; strings pass through unchanged and keywords use `name`. Known values are `:disable` and `:allow-auto-only`. It is serialized as `managedSettings.permissions.disableBypassPermissionsMode`; `:deny`/`:ask`/`:allow` are vectors of non-blank permission-rule strings forwarded verbatim. Presence of this key (or `:enable-managed-settings? true`) sets the permission-handler context's `:managed-settings-enabled?` to `true` — see [`approve-all`](#approve-all). Valid on `create-session`, `resume-session`, and `join-session`. ([upstream PR #2139](https://github.com/github/copilot-sdk/pull/2139)) |
| `:request-extensions?` | boolean | Opt into extension management tools and per-extension dispatch for this connection. Explicit `false` is preserved as `requestExtensions: false`; omission sends no wire key. Valid on create, resume, and join. Explicit `nil` is invalid. ([upstream PR #1401](https://github.com/github/copilot-sdk/pull/1401)) |
| `:extension-sdk-path` | string | Override the `copilot-sdk/` folder injected into extension subprocesses. The runtime falls back to its bundled SDK when the path is invalid. Serialized as `extensionSdkPath` on create and resume; not accepted by `join-session` because the extension process has already started. Explicit `nil` is invalid. ([upstream PR #1494](https://github.com/github/copilot-sdk/pull/1494)) |
| `:extension-info` | map | Stable extension identity `{:source string :name string}`. Serialized exactly as `extensionInfo.{source,name}` on create, resume, and join. Both strings are required; unknown nested keys and explicit `nil` are invalid. This config shape is distinct from the richer `session.extensions_loaded` event items. ([upstream PR #1401](https://github.com/github/copilot-sdk/pull/1401)) |
| `:canvas-provider` | map | Canvas provider identity only. `{:id "..." :name "..."}` (`:name` optional) — serialized as wire `canvasProvider.{id,name}`. This does not implement the upstream experimental canvas authoring/provider callbacks. (upstream PR #1847) |
| `:exp-assignments` | map | (`@internal`) Exact `CopilotExpAssignmentResponse` contract using PascalCase string keys. Required: `"Features"` (string vector), `"Flights"` (string-to-string map), `"Configs"` (vector of closed maps containing exactly `"Id"` (string) and `"Parameters"` (map of string keys to string, number, boolean, or `nil` values)), and `"AssignmentContext"` (string). Optional: `"ParameterGroups"` (opaque), `"FlightingVersion"` (number), and `"ImpressionId"` (string). The Clojure spec rejects unknown top-level and config-entry keys. The map is forwarded unchanged on `create-session`, `resume-session`, and `join-session` (`join-session` delegates to resume). Serialized as `expAssignments`. ([upstream PR #2033](https://github.com/github/copilot-sdk/pull/2033)) |
| `:mcp-servers` | map | MCP server configs keyed by opaque string or keyword server IDs; keyword IDs preserve their full spelling without the leading colon (for example, `:srv-1` becomes `"srv-1"` and `:team/srv-1` becomes `"team/srv-1"`). See [MCP docs](../mcp/overview.md). Local (stdio) servers: `:mcp-command`, `:mcp-args`, `:mcp-tools`. Remote (HTTP/SSE) servers: `:mcp-server-type` (`:http`/`:sse`), `:mcp-url`, `:mcp-tools`. Spec aliases: `::mcp-stdio-server` = `::mcp-local-server`, `::mcp-http-server` = `::mcp-remote-server` |
| `:enable-mcp-apps` | boolean | **Experimental (SEP-1865).** Opt into MCP Apps UI passthrough only when the host can render `ui://` MCP App bundles. Explicit `true` sends `requestMcpApps: true` on `session.create` and `session.resume` (`join-session` delegates to resume). `false` and omission leave the wire key absent; explicit `nil` is invalid. The runtime may still decline the capability when its MCP Apps gate is off. ([upstream PR #1335](https://github.com/github/copilot-sdk/pull/1335)) |
| `:disabled-mcp-servers` | vector | Names of configured MCP servers (from `:mcp-servers` or on-disk `.mcp.json`) to suppress for this session. Vector of non-blank strings. Serialized as wire `disabledMcpServers`. ([upstream PR #2260](https://github.com/github/copilot-sdk/pull/2260)) |
| `:github-mcp-tool-config` | map | Configures the built-in GitHub MCP server's tool surface. Optional keys: `:enable-all-tools?` (boolean, gated on `some?`), `:additional-toolsets` (vector of strings), `:additional-tools` (vector of strings), `:enable-insiders-mode?` (boolean, gated on `some?`), `:disable-form-deferral?` (boolean, gated on `some?`). Serialized as wire `githubMcpToolConfig.{enableAllTools,additionalToolsets,additionalTools,enableInsidersMode,disableFormDeferral}`. ([upstream PR #2112](https://github.com/github/copilot-sdk/pull/2112)) |
| `:commands` | vector | Command definitions (slash commands). An omitted command `:description` is sent as `""`. See [Commands](#commands) |
| `:custom-agents` | vector | Custom agent configs. Each agent map: `:agent-name` (required), `:agent-prompt` (required), `:agent-display-name`, `:agent-description`, `:agent-tools`, `:agent-infer?`, `:agent-skills` (vector of strings), `:agent-model` (string, e.g. `"claude-haiku-4.5"`; when set the runtime tries this model for the agent, falling back to the parent session model — upstream PR #1309), `:agent-reasoning-effort` (`"low"`, `"medium"`, `"high"`, `"xhigh"`, or `"max"`), `:mcp-servers`. The disambiguating Clojure prefixes are removed on the wire (`:agent-name` becomes `name`, `:agent-prompt` becomes `prompt`, and so on); `:agent-reasoning-effort` becomes `reasoningEffort`. Nested `:mcp-servers` follow the same config and opaque server-ID rules as session-level MCP servers. When reasoning effort is omitted, the runtime resolves it from the selected model's configuration; the parent session's reasoning effort is inherited only when the custom agent uses the same model as the parent. ([upstream PR #2064](https://github.com/github/copilot-sdk/pull/2064)) |
| `:default-agent` | map | Built-in/default agent config. Use `{:excluded-tools [...]}` to hide tools from the default agent while leaving them available to custom agents |
| `:on-permission-request` | fn | Permission handler function. **Optional** (upstream PR #1308). On create, omission sends `requestPermission: false`; providing a handler sends `true`. Resolve surfaced pending requests manually via `handle-pending-permission-request!`. Use `copilot/approve-all` to approve everything. |
| `:streaming?` | boolean | Enable streaming deltas. Explicit `false` is forwarded; omission leaves the runtime default unchanged. |
| `:config-directory` | string | Override the CLI config directory. `:config-dir` remains accepted as a deprecated alias. |
| `:skill-directories` | vector | Additional skill directories to load |
| `:instruction-directories` | vector | Additional directories to search for custom instruction files. Forwarded as `instructionDirectories` on `session.create` and `session.resume`. (upstream PR #1190) |
| `:additional-directories` | vector | Extra directories the runtime is allowed to read/write outside the session's working directory. Vector of non-blank strings. Serialized as wire `additionalDirectories`. Re-supply the vector when resuming a session. ([upstream PR #2180](https://github.com/github/copilot-sdk/pull/2180)) |
| `:disabled-skills` | vector | Disable specific skills by name |
| `:included-builtin-skills` | vector | Allowlist of runtime-bundled skill names, applied through `session.options.update` after create/resume. `:empty` client mode defaults this to `[]`; an explicit vector overrides that default. `:copilot-cli` mode omits it unless explicitly supplied, including an explicit `[]`. Custom skill loading remains controlled separately. ([upstream PR #2410](https://github.com/github/copilot-sdk/pull/2410)) |
| `:large-output` | map | Tool output handling config. Supports `:enabled`, `:max-size-bytes`, and `:output-directory` (`:output-dir` remains a deprecated alias). The official SDK exposes the same config as `largeOutput`. |
| `:working-directory` | string | Working directory for the session (tool operations relative to this) |
| `:infinite-sessions` | map | Infinite session config (see below) |
| `:reasoning-effort` | string | Reasoning effort level: `"low"`, `"medium"`, `"high"`, `"xhigh"`, or `"max"` ([upstream PR #2228](https://github.com/github/copilot-sdk/pull/2228)) |
| `:github-token` | string | Static GitHub token for this session. Sent as `gitHubToken`; mutually exclusive with `:github-token-provider`. |
| `:github-token-provider` | fn | Session-scoped, refreshable GitHub credential callback. Receives `{:host string :session-id string? :reason keyword}` where `:reason` is exactly `:initial` or `:refresh`. Returns `{:kind :token :access-token string :expires-in integer>=3601 :token-type string?}` or `{:kind :cancelled}`, directly or on a core.async channel. Both result variants are open to additional extension fields. Create, resume, and join configuration carries only an opaque registration ID; the callback remains local, while acquired credentials cross the JSON-RPC connection to the CLI when requested. Managed child-process stdio, SDK-managed TCP, and explicit `:cli-url` connections are supported; the Clojure-only caller-supplied testing-stream transport is rejected. Explicit `:cli-url` uses raw TCP; `http://` is accepted as an explicit plaintext spelling and `https://` is rejected. Use a trusted runtime and an authenticated, protected tunnel for nonlocal connections. Provider work runs on a bounded client-owned executor with a fixed 120-second deadline per callback. Callback failure, an invalid result, or timeout is returned directly to the runtime without an SDK retry. Failed create/resume/join calls roll back provisional registrations; session and client teardown remove committed registrations and cancel in-flight work. Mutually exclusive with `:github-token`. See [Authentication](../auth/index.md#session-scoped-token-provider). ([upstream PR #2412](https://github.com/github/copilot-sdk/pull/2412)) |
| `:on-user-input-request` | fn | Handler for `ask_user` requests (see below) |
| `:ask-user-variant` | keyword | Selects the built-in `ask_user` tool shape: `:legacy` or `:elicitation`. Omission preserves the runtime default; explicit values serialize as `askUserVariant` on create/resume/join. The `:elicitation` variant requires an `:on-elicitation-request` handler when the host must answer requests. ([upstream PR #2432](https://github.com/github/copilot-sdk/pull/2432)) |
| `:hooks` | map | Lifecycle hooks (see below) |
| `:agent` | string | Name of a custom agent to activate at session start. Must match a name in `:custom-agents`. Equivalent to calling `agent.select` after creation. |
| `:on-event` | fn | Event handler (1-arg fn receiving event maps). Registered before the RPC call, guaranteeing early events like `session.start` are not missed. |
| `:on-elicitation-request` | fn | Handler for elicitation requests from the agent. When provided, advertises `requestElicitation=true` and handles `elicitation.requested` broadcast events. Single-arg handler receives an `ElicitationContext` map with `:session-id`, `:message`, `:requested-schema`, `:mode`, `:elicitation-source`, `:url`. Returns an `ElicitationResult` map `{:action "accept"/"decline"/"cancel" :content {...}}`. See [Elicitation Provider](#elicitation-provider) |
| `:on-mcp-auth-request` | fn | Handler for interactive MCP OAuth requests. When provided, the SDK registers interest in `mcp.oauth_required` (on both create and resume) so the runtime delegates browser-based OAuth to this handler instead of silently using a cached token. 2-arg handler `(fn [request ctx])` receives an `McpAuthRequest` map and `{:session-id ...}`; may return a channel. See [MCP OAuth Handler](#mcp-oauth-handler). (upstream PR #1669) |
| `:on-exit-plan-mode` | fn | Handler for `exitPlanMode.request` RPCs — invoked when the agent asks to leave plan mode. When provided, advertises `requestExitPlanMode=true`. Receives the request map; returns the approval result. (upstream PR #1228) |
| `:on-auto-mode-switch` | fn | Handler for `autoModeSwitch.request` RPCs — invoked when the agent asks to switch autonomy mode. When provided, advertises `requestAutoModeSwitch=true`. Receives the request map; returns the approval result. (upstream PR #1228) |
| `:enable-session-telemetry?` | boolean | Enable/disable the CLI's **internal** session telemetry (distinct from the client `:telemetry` OpenTelemetry export). Defaults to enabled for GitHub-authenticated sessions; always disabled when a BYOK `:provider` is set; defaulted to `false` in `:mode :empty` (caller can override). Wire-encoded as `enableSessionTelemetry`. See [Observability](#observability). (upstream PR #1224) |
| `:create-session-fs-handler` | fn | Factory for session filesystem providers. Required when `:session-fs` is set on the client. Called as `(factory session)`, returns a provider-style map or a low-level handler map. See [Session Filesystem](#session-filesystem) |
| `:enable-config-discovery` | boolean | Auto-discover `.mcp.json`, `.vscode/mcp.json`, skills, etc. Instruction files always load regardless. (upstream PR #1044) |
| `:enable-experimental-mode?` | boolean | Opt into CLI-side experimental features. Gated on `some?` — an explicit `false` is forwarded verbatim; an absent key is omitted. Serialized as wire `isExperimentalMode`. Defaulted to `false` in `:empty` mode. ([upstream PR #1600](https://github.com/github/copilot-sdk/pull/1600)) |
| `:model-capabilities` | map | Deep-partial model capability override forwarded verbatim (upstream `SessionConfig.modelCapabilities`, a stable public SDK field). Canonical shape: `{:supports {:vision boolean :reasoning-effort boolean :adaptive-thinking :unsupported\|:optional\|:required} :limits {:max-prompt-tokens int :max-output-tokens int :max-context-window-tokens int :vision {:supported-media-types [...] :max-prompt-images int :max-prompt-image-size int}}}`. The stable public-SDK fields are `:supports {:vision :reasoning-effort}` and `:limits {:max-prompt-tokens :max-context-window-tokens :vision {...}}`; `:adaptive-thinking` and `:max-output-tokens` are **experimental** CLI-protocol extensions (present in the runtime wire schema but not the public Node SDK type). The wire keeps `reasoningEffort` camelCase but uses `adaptive_thinking`, `max_prompt_tokens`, `max_output_tokens`, `max_context_window_tokens`, and the nested vision leaves in snake_case. The published `:model-supports` / `:model-limits` aliases remain accepted for outbound compatibility and normalize to the canonical wire shape; do not combine an alias with its canonical branch. (upstream PR #1029) |
| `:include-sub-agent-streaming-events?` | boolean | Forward streaming events from sub-agents to the parent session's event stream. Defaults to `true` on the wire. (upstream PR #1108) |
| `:remote-session` | keyword | Per-session Mission Control mode: `:off`, `:export`, or `:on`. When omitted, the CLI applies its default. `:off` disables remote, `:export` exports session events to Mission Control without enabling remote steering, `:on` enables both. Forwarded as `remoteSession`. (upstream PR #1295, CLI 1.0.48) |
| `:cloud` | map | (create-session only) Creates a remote cloud session. Shape: `{:repository {:owner "octocat" :name "hello-world" :branch "main"}}` — `:owner` and `:name` are required non-blank strings; `:branch` is optional. Forwarded as `cloud.repository.*` on `session.create`. Not accepted on `resume-session` (matches upstream `ResumeSessionConfig`). When `:cloud` is set and `:session-id` is omitted, the SDK defers id assignment to the server and registers the session under the server-returned id (upstream PR #1479). (upstream PR #1306) |
| `:mcp-oauth-token-storage` | keyword | Controls where MCP OAuth tokens are persisted. `#{:persistent :in-memory}`. Default is server-side (persistent). Set to `:in-memory` in multi-tenant hosts that must not leak tokens to disk. Wire-encoded as `mcpOAuthTokenStorage`. (upstream PR #1326) |
| `:embedding-cache-storage` | keyword | `#{:persistent :in-memory}`. Controls where the embedding cache lives. Wire-encoded as `embeddingCacheStorage`. (upstream PR #1474) |
| `:skip-embedding-retrieval` | boolean | Skip embedding-based context retrieval. (upstream PR #1474) |
| `:organization-custom-instructions` | string | Organization-wide instructions injected by the host. (upstream PR #1474) |
| `:enable-on-demand-instruction-discovery` | boolean | Auto-discover instruction files on demand. (upstream PR #1474) |
| `:enable-file-hooks` | boolean | Enable file-watcher-style lifecycle hooks. (upstream PR #1474) |
| `:enable-host-git-operations` | boolean | Allow the CLI to run git operations through the host. (upstream PR #1474) |
| `:enable-session-store` | boolean | Enable the disk-backed session store. (upstream PR #1474) |
| `:enable-skills` | boolean | Enable skills discovery and loading. (upstream PR #1474) |
| `:plugin-directories` | vector | Extra plugin directories loaded even when `:enable-config-discovery` is `false`. Wire-encoded as `pluginDirectories`. (upstream PR #1482) |
| `:reasoning-summary` | string | `"none"` / `"concise"` / `"detailed"`. Controls inclusion/granularity of reasoning summaries on assistant turns. Wire-encoded as `reasoningSummary`. String-valued for consistency with `:reasoning-effort`. |
| `:context-tier` | keyword | `#{:default :long-context}` selects the model context tier. On create/resume, omit the key to leave the runtime default unchanged; explicit `nil` is invalid because the official session config does not expose `null`. `switch-model!` / `set-model!` retain their existing `nil` option, which omits `contextTier` from that RPC. Wire values are `"default"` / `"long_context"`. |
| `:skip-custom-instructions` | boolean | Skip loading user-level custom instruction files. Forwarded via `session.options.update` (NOT `session.create`). Defaulted to `true` in `:empty` mode. (upstream PR #1428) |
| `:custom-agents-local-only` | boolean | Restrict custom-agent loading to caller-supplied configs only (no on-disk discovery). Forwarded via `session.options.update`, and — since ([upstream PR #1899](https://github.com/github/copilot-sdk/pull/1899)) — also sent directly (gated on `some?`) on `session.create` and `session.resume` as wire `custom-agents-local-only`. Defaulted to `true` in `:empty` mode. (upstream PR #1428) |
| `:coauthor-enabled` | boolean | Add a Copilot Co-authored-by trailer to commits made by the CLI. Forwarded via `session.options.update`. Defaulted to `false` in `:empty` mode. (upstream PR #1428) |
| `:manage-schedule-enabled` | boolean | Enable the built-in schedule-management tools. Forwarded via `session.options.update`. Defaulted to `false` in `:empty` mode. (upstream PR #1428) |
| `:open-canvases` | vector | (resume-session / join-session only) Seed the open-canvases snapshot when reconnecting. Each entry requires `:instance-id`, `:extension-id`, and `:canvas-id`; optional keys are `:extension-name`, `:title`, `:status`, `:url`, `:input`, and `:icon` (a host-local PNG path). Caller-defined `:input` keys are preserved verbatim through wire conversion (no kebab→camel re-casing). See [`open-canvases`](#open-canvases). ([upstream PR #1604](https://github.com/github/copilot-sdk/pull/1604)) |
| `:memory` | map | Persistent-memory configuration. Shape: `{:enabled boolean}`. Sent on **both** `session.create` and `session.resume`; omitted entirely when the key is absent (never wire `null`). Wire-encoded as `memory`. In `:mode :empty` it is defaulted to `{:enabled false}` (caller can override). (upstream [PR #1617](https://github.com/github/copilot-sdk/pull/1617)) |

#### `resume-session`

```clojure
(copilot/resume-session client session-id config)
```

Resume an existing session by ID. The `config` map accepts the same options as `create-session` (except `:session-id`), including per-session `:github-token`, plus:

| Option | Type | Description |
|---|---|---|
| `:disable-resume?` | boolean | When true, skip emitting the session.resume event (default: false). Explicit `false` is forwarded as `disableResume: false`; omission leaves the runtime default unchanged. |
| `:continue-pending-work?` | boolean | When true, the runtime re-emits any pending `permission.requested` and external tool calls so handlers can re-respond on resume; default false treats pending work as interrupted. Forwarded as `continuePendingWork` on `session.resume`. |
| `:large-output` | map | Tool output handling config. Forwarded on `session.resume` as the official SDK's `largeOutput` field. |

When `:mcp-servers` is present, the SDK sends the converted server configuration
as `mcpServers` in the `session.resume` request. This applies to blocking and
async resume, and therefore to `join-session`. Omitting the key omits
`mcpServers`; an empty map sends an empty configuration.

When `:on-permission-request` is set to `default-join-session-permission-handler`, the SDK sends `requestPermission: false` on the wire, telling the CLI that this client does not handle permission requests. Any other handler sends `requestPermission: true`.

```clojure
;; Resume with a different model and reasoning effort
(copilot/resume-session client "session-123"
  {:model "claude-sonnet-4"
   :reasoning-effort "high"
   :on-permission-request copilot/approve-all})

;; Resume without handling permissions (join-style)
(copilot/resume-session client "session-123"
  {:on-permission-request copilot/default-join-session-permission-handler})
```

#### `<create-session`

```clojure
(copilot/<create-session client config)
```

Async version of `create-session`. Returns a channel that delivers a `CopilotSession`.

Configuration validation, connection setup, and local session preparation happen
before the result channel is returned. For standard local creates and cloud
creates with a caller-supplied `:session-id`, this includes constructing the
session filesystem handler. A failure in any of those steps throws
synchronously. A cloud create with `:cloud` and no `:session-id` must defer
filesystem-handler construction until the server assigns an ID; that failure,
RPC failures, and later setup failures are delivered as a `Throwable` on the
channel. The RPC wait parks instead of blocking, making the returned channel
safe to consume inside `go` blocks. In the deferred cloud case, the
`:create-session-fs-handler` factory runs on the JSON-RPC reader thread before
the response can complete. It must return promptly and must not issue SDK RPCs
or wait for session events.

```clojure
(require '[clojure.core.async :refer [go <!]])

(go
  (let [result (<! (copilot/<create-session client {:model "gpt-5.4"
                                                    :on-permission-request copilot/approve-all}))]
    (if (instance? Throwable result)
      (println "Error:" (ex-message result))
      (let [answer (<! (copilot/<send! result {:prompt "Hello"}))]
        (println answer)))))
```

#### `<resume-session`

```clojure
(copilot/<resume-session client session-id config)
```

Async version of `resume-session`. Returns a channel that delivers a
`CopilotSession`.

Same config options as `resume-session`. Configuration validation, connection
setup, and local session preparation — including construction of any session
filesystem handler — happen before the result channel is returned and throw
synchronously on failure. RPC and later setup failures are delivered as a
`Throwable` on the channel. The RPC wait parks, so the returned channel is safe
to consume inside `go` blocks.

```clojure
(go
  (let [result (<! (copilot/<resume-session
                    client
                    "session-123"
                    {:on-permission-request copilot/approve-all}))]
    (if (instance? Throwable result)
      (throw result)
      (<! (copilot/<send! result {:prompt "Continue"})))))
```

#### `join-session`

```clojure
(copilot/join-session config)
```

Join the current foreground session from an extension running as a child process of the Copilot CLI. Reads the `SESSION_ID` environment variable, creates a child-process client, and resumes the session with `:disable-resume?` defaulting to `true`. It accepts `:request-extensions?`, `:extension-info`, and the join-only `:requested-environment-variables`, but rejects the create/resume-only `:extension-sdk-path`. Session-scoped `:github-token` and `:github-token-provider` authentication are supported; the provider uses the same callback contract, lifecycle, and transport-security requirements as `resume-session`.

Returns a map with `:client` and `:session` keys. When the request vector is
non-empty, the map also contains `:granted-environment-variables`. Its string
keys preserve the requested environment names, and it contains only values the
parent CLI granted for those exact names. The map may be empty when none were
approved. The caller is responsible for stopping the client when done.

Throws if `SESSION_ID` is not set in the environment.

In addition to the `resume-session` config options, `join-session` accepts:

| Option | Type | Description |
|---|---|---|
| `:factories` | vector | `define-factory` handles to register as [Agent Factories (Experimental)](#agent-factories-experimental) for this session. Join-only — not accepted by `create-session` or `resume-session`. |
| `:requested-environment-variables` | vector of non-blank strings | Names the extension asks the parent CLI to grant. Omission and `[]` send no wire field; explicit `nil` is invalid. Approved values are returned under `:granted-environment-variables`, filtered to these exact names. ([upstream PR #2348](https://github.com/github/copilot-sdk/pull/2348)) |

```clojure
(let [{:keys [client session granted-environment-variables]}
      (copilot/join-session
       {:on-permission-request copilot/approve-all
        :requested-environment-variables ["GITHUB_TOKEN"]
        :tools [my-tool]})]
  (when-let [token (get granted-environment-variables "GITHUB_TOKEN")]
    ;; Supply token to extension-owned code without logging it.
    (use-token token))
  ;; use session...
  (copilot/stop! client))
```

> **Return-shape difference vs the official SDK.** The Node.js
> `joinSession(config)` returns the `CopilotSession` directly and hides the
> client it creates internally. Clojure has no implicit/global client, so
> `join-session` returns **both** the `:client` and the `:session`: the
> caller owns the client's lifecycle and must call [`stop!`](#stop) on it
> when finished. Bind the returned map's `:session` where a Node.js caller
> would use the awaited return value, and keep the `:client` for cleanup.
> Node.js also installs approved environment grants into `process.env`; the JVM
> cannot portably do that, so Clojure returns the filtered
> `:granted-environment-variables` map instead.
> See [Naming and shape differences vs the official SDK](#naming-and-shape-differences-vs-the-official-sdk).

#### `ping`

```clojure
(copilot/ping client)
(copilot/ping client message)
```

Ping the server to check connectivity. Returns `{:message "..." :timestamp ... :protocol-version ...}`.

#### `get-status`

```clojure
(copilot/get-status client)
```

Get CLI status including version and protocol information. Returns `{:version "0.0.389" :protocol-version 2}`.

#### `get-auth-status`

```clojure
(copilot/get-auth-status client)
```

Get current authentication status. Returns:
```clojure
{:authenticated? true
 :auth-type :user        ; :user | :env | :gh-cli | :hmac | :api-key | :token
 :host "github.com"
 :login "username"
 :status-message "Authenticated as username"}
```

#### `list-models`

```clojure
(copilot/list-models client)
```

List available models with their metadata. Results are cached per client connection.
When `:on-list-models` handler is provided in client options, calls the handler
instead of the RPC method (no connection required).
Requires authentication (unless `:on-list-models` is provided). Returns a vector of model info maps:
```clojure
[{:id "gpt-5.4"
  :name "GPT-5.4"
  :vendor "openai"
  :family "gpt-5.4"
  :version "gpt-5.4"
  :max-input-tokens 128000
  :max-output-tokens 16384
  :preview? false
  :model-capabilities {:supports {:vision true
                                  :reasoning-effort false
                                  :adaptive-thinking :optional}
                       :limits {:max-prompt-tokens 128000
                                :max-output-tokens 16384
                                :max-context-window-tokens 128000
                                :vision
                                {:supported-media-types ["image/png" "image/jpeg"]
                                 :max-prompt-images 10
                                 :max-prompt-image-size 20971520}}}
  :model-policy {:policy-state "enabled"
                 :terms "..."}
  :model-billing {:multiplier 1.0
                  :token-prices {:input-price 0.00000125
                                 :output-price 0.00001
                                 :cache-price 0.0000003125
                                 :long-context {:input-price 0.0000025
                                                :output-price 0.00002}}
                  :promo {:ends-at "2026-08-01T00:00:00Z"
                          :id "summer-promo"
                          :discount-percent 25
                          :message "25% off until August"}}
  ;; Model picker categorization (CLI 1.0.46+):
  :model-picker-category "powerful"            ;; "lightweight" | "versatile" | "powerful"
  :model-picker-price-category "very_high"     ;; "low" | "medium" | "high" | "very_high"
  ;; For models supporting reasoning:
  :supported-reasoning-efforts ["low" "medium" "high" "xhigh"]
  :default-reasoning-effort "medium"}
 ...]
```

The optional `:promo` billing map contains:

| Key | Type | Required | Description |
|-----|------|----------|-------------|
| `:ends-at` | string | yes | Promotion end time |
| `:id` | string | no | Promotion identifier |
| `:discount-percent` | number from 0 through 100 | no | Percentage discount |
| `:message` | string | no | Display message |

List all models with their billing multiplier:

```clojure
(require '[github.copilot-sdk :as copilot])

(copilot/with-client [client]
  (doseq [m (copilot/list-models client)]
    (println (:id m) (str "x" (get-in m [:model-billing :multiplier])))))
;; prints:
;; gpt-5.4 x1.0
;; claude-sonnet-4.5 x1.0
;; o1 x2.0
;; ...
```

#### `list-tools`

```clojure
(copilot/list-tools client)
(copilot/list-tools client "gpt-5.4")
```

List available tools with their metadata. Pass an optional model string to get model-specific tool overrides.

```clojure
(copilot/list-tools client)
;; => [{:name "read_file"
;;      :namespaced-name "builtin.read_file"
;;      :description "Read a file from disk"
;;      :parameters {...}
;;      :instructions "..."}
;;     ...]

;; Print all tool names
(doseq [tool (copilot/list-tools client)]
  (println (:name tool) "-" (:description tool)))
```

Each tool info map contains:

| Key | Type | Required | Description |
|-----|------|----------|-------------|
| `:name` | string | yes | Short tool name |
| `:namespaced-name` | string | no | Fully qualified tool name |
| `:description` | string | yes | Human-readable description |
| `:parameters` | map | no | JSON Schema of tool parameters |
| `:instructions` | string | no | Usage instructions for the tool |

#### `get-quota`

```clojure
(copilot/get-quota client)
```

Get account quota information. Returns a map of quota type (string) to quota snapshot maps.

```clojure
(copilot/get-quota client)
;; => {"chat" {:entitlement-requests 1000
;;             :used-requests 42
;;             :remaining-percentage 95.8
;;             :overage 0
;;             :overage-allowed-with-exhausted-quota? false
;;             :reset-date "2025-02-01T00:00:00Z"}}

(let [quotas (copilot/get-quota client)]
  (doseq [[type snapshot] quotas]
    (println type ":" (:remaining-percentage snapshot) "% remaining")))
```

Each quota snapshot map contains:

| Key | Type | Description |
|-----|------|-------------|
| `:entitlement-requests` | number | Total allowed requests |
| `:used-requests` | number | Requests used so far |
| `:remaining-percentage` | number | Percentage of quota remaining |
| `:overage` | number | Number of requests over quota |
| `:overage-allowed-with-exhausted-quota?` | boolean | Whether overage is allowed when quota is exhausted |
| `:reset-date` | string (optional) | ISO 8601 date when quota resets |

#### `mcp-config-list` / `mcp-config-add!` / `mcp-config-update!` / `mcp-config-remove!`

> **Experimental:** These wrap server-level MCP configuration RPCs and may change.

```clojure
;; List configured MCP servers
(copilot/mcp-config-list client)
;; => {:servers [...]}

;; Add a new MCP server config
(copilot/mcp-config-add! client {:name "my-server"
                                  :command "npx"
                                  :args ["-y" "@modelcontextprotocol/server-filesystem" "/tmp"]
                                  :tools ["*"]})

;; Update an existing config
(copilot/mcp-config-update! client {:name "my-server" :tools ["read_file"]})

;; Remove a config
(copilot/mcp-config-remove! client {:name "my-server"})
```

#### `state`

```clojure
(copilot/state client)
```

Get current connection state: `:disconnected` | `:connecting` | `:connected` | `:error`

#### `notifications`

```clojure
(copilot/notifications client)
```

Get a channel that receives non-session notifications. The channel is buffered; notifications are dropped if it fills.

#### `on-lifecycle-event`

```clojure
;; Subscribe to all lifecycle events
(def unsub (copilot/on-lifecycle-event client
             (fn [event]
               (println (:lifecycle-event-type event) (:session-id event)))))

;; Subscribe to a specific event type
(def unsub (copilot/on-lifecycle-event client :session.created
             (fn [event]
               (println "New session:" (:session-id event)))))

;; Unsubscribe
(unsub)
```

Subscribe to session lifecycle events dispatched by the CLI server. The handler receives an event map with:

| Key | Type | Description |
|-----|------|-------------|
| `:lifecycle-event-type` | keyword | One of `:session.created`, `:session.deleted`, `:session.updated`, `:session.foreground`, `:session.background` |
| `:session-id` | string | The session ID |
| `:metadata` | map (optional) | Contains `:start-time`, `:modified-time`, and optionally `:summary` |

**Two arities:**
- `(on-lifecycle-event client handler)` — wildcard, receives all lifecycle events
- `(on-lifecycle-event client event-type handler)` — receives only events matching `event-type`

Returns an unsubscribe function. Call it with no arguments to remove the handler.

Handlers are called synchronously on the notification router's go-loop. Keep handlers fast; offload heavy work to another thread or channel.

#### `list-sessions`

```clojure
(copilot/list-sessions client)
(copilot/list-sessions client {:repository "owner/repo" :branch "main"})
```

List available sessions. Pass an optional filter map to narrow results by context fields.

**Filter options:**

| Key | Type | Description |
|-----|------|-------------|
| `:cwd` | string | Filter by working directory |
| `:git-root` | string | Filter by git repository root |
| `:repository` | string | Filter by repository (e.g., `"owner/repo"`) |
| `:branch` | string | Filter by branch name |

Returns a vector of session metadata maps with `:start-time` and `:modified-time` as `java.time.Instant`. Sessions may include a `:context` map with the session's working directory and repository info.

```clojure
(copilot/list-sessions client)
;; => [{:session-id "abc-123"
;;      :start-time #inst "2025-01-15T10:00:00Z"
;;      :modified-time #inst "2025-01-15T10:05:00Z"
;;      :summary "Refactoring auth module"
;;      :remote? false
;;      :context {:cwd "/home/user/project"
;;                :git-root "/home/user/project"
;;                :repository "owner/repo"
;;                :branch "main"}}
;;     ...]
```

#### `get-session-metadata`

```clojure
(copilot/get-session-metadata client session-id)
```

Get metadata for a specific session by ID. Returns the session metadata map if found, or `nil` if the session does not exist. Provides an efficient O(1) lookup instead of calling `list-sessions` and filtering client-side.

The returned map has the same shape as entries returned by `list-sessions`:
- `:session-id` — session ID string
- `:start-time` — `java.time.Instant` when the session was created
- `:modified-time` — `java.time.Instant` of last modification
- `:remote?` — boolean, true if the session is remote
- `:summary` — optional summary string
- `:context` — optional map with `:cwd` and optional `:git-root`, `:repository`, `:branch`

```clojure
(def metadata (copilot/get-session-metadata client "session-abc123"))
;; => {:session-id "session-abc123"
;;     :start-time #object[java.time.Instant 0x... "2025-01-15T10:00:00Z"]
;;     :modified-time #object[java.time.Instant 0x... "2025-01-15T10:05:00Z"]
;;     :remote? false
;;     :summary "Refactoring auth module"
;;     :context {:cwd "/home/user/project"}}

(copilot/get-session-metadata client "non-existent-id")
;; => nil
```

#### `delete-session!`

```clojure
(copilot/delete-session! client session-id)
```

Delete a session and its data from disk. Unlike `disconnect!` (which gracefully closes an active session), `delete-session!` removes persisted session data by ID.

#### `get-last-session-id`

```clojure
(copilot/get-last-session-id client)
```

Get the ID of the most recently updated session.

#### `get-foreground-session-id`

```clojure
(copilot/get-foreground-session-id client)
```

Get the foreground session ID. Returns the session ID or nil. Only applicable in TUI+server mode.

#### `set-foreground-session-id!`

```clojure
(copilot/set-foreground-session-id! client session-id)
```

Set the foreground session. Requests the TUI to switch to displaying the specified session. Only applicable in TUI+server mode.

---

## CopilotSession

Represents a single conversation session.

### Methods

#### `send!`

```clojure
(copilot/send! session options)
```

Send a message to the session. Returns immediately with the message ID.

**Options:**

| Key | Type | Description |
|-----|------|-------------|
| `:prompt` | string | The message/prompt to send |
| `:attachments` | vector | File attachments (see below) |
| `:mode` | keyword | `:enqueue` or `:immediate` |
| `:agent-mode` | keyword | `#{:interactive :plan :autopilot :shell}`. Per-message agent mode. Wire-encoded as `agentMode`. (upstream PR #1438) |
| `:display-prompt` | string | Alternate prompt shown in the timeline UI instead of `:prompt`. Useful when the model-facing prompt contains machinery or context that should not be surfaced to the end user. Wire-encoded as `displayPrompt`. (upstream PR #1470) |
| `:request-headers` | map | Extra HTTP headers (string→string) forwarded to the model provider for this request. Merged with provider-level `:headers`. (upstream PR #1094) |
| `:source` | keyword or map | Optional message provenance. Use `:user`, `:system`, or `{:agent-id "..."}`. Agent IDs are opaque strings, including the empty string, and serialize as `agent-<id>`. Omission sends no wire key; explicit `nil` is invalid. Remote backends may echo the source locally without forwarding it end to end. ([upstream PR #2573](https://github.com/github/copilot-sdk/pull/2573)) |

```clojure
(copilot/send! session
  {:prompt "Review this change"
   :source {:agent-id "reviewer"}})
```

**Attachment types:**

| Type | Required Keys | Optional Keys | Description |
|------|--------------|---------------|-------------|
| `:file` | `:type`, `:path` | `:display-name`, `:line-range` | File attachment |
| `:directory` | `:type`, `:path` | `:display-name`, `:line-range` | Directory attachment |
| `:selection` | `:type`, `:file-path`, `:display-name` | `:selection-range`, `:text` | Code selection attachment |
| `:github-reference` | `:type`, `:number`, `:title`, `:reference-type`, `:state`, `:url` | — | GitHub issue, PR, or discussion reference |
| `:blob` | `:type`, `:data`, `:mime-type` | `:display-name` | Inline base64-encoded data (e.g. images) |

`:line-range` is a map with `:start` and `:end` line numbers (zero-based) to restrict the attachment to a range of lines:

```clojure
(copilot/send! session
  {:prompt "Explain this function"
   :attachments [{:type :file
                  :path "/path/to/file.clj"
                  :line-range {:start 10 :end 25}}]})
```

Selection range is a map with `:start` and `:end` positions, each containing `:line` and `:character`:

```clojure
(copilot/send! session
  {:prompt "Explain this code"
   :attachments [{:type :selection
                  :file-path "/path/to/file.clj"
                  :display-name "my-fn"
                  :selection-range {:start {:line 10 :character 0}
                                   :end {:line 25 :character 0}}
                  :text "(defn my-fn [...] ...)"}]})
```

#### `send-and-wait!`

```clojure
(copilot/send-and-wait! session options)
(copilot/send-and-wait! session options timeout-ms)
```
Send a message and block until the session becomes idle. Returns the final assistant message event.
Default timeout is `60000` ms (60 seconds), matching the upstream Node.js SDK. The timeout controls how long to wait for `session.idle`; it does not abort in-flight agent work.
An idle event whose wire `:mode` is the string `"autopilot"` is a turn boundary,
not a terminal event, so the wait continues. Keyword `:autopilot` is not a
supported event payload value.

#### `send-async`

```clojure
(copilot/send-async session options)
```

Send a message and return a core.async channel that receives all events for this
message, closing on an ordinary idle event. Autopilot idle events are emitted
without closing the channel.
Safe for use inside `go` blocks — no blocking operations.
Supports `:timeout-ms` in options (default: `60000`, set to `nil` to disable).
On timeout, the channel emits a final `:copilot/session.error` event whose data
includes `:timeout-ms`, releases the event subscription and send lock, then
closes.

#### `send-async-with-id`

```clojure
(copilot/send-async-with-id session options)
```

Send a message and return `{:message-id :events-ch}` for correlating responses.
Supports `:timeout-ms` in options (default: `60000`, set to `nil` to disable).
The event channel follows `send-async`, including its final timeout event.

#### `<send!`

```clojure
(copilot/<send! session options)
```

Async equivalent of `send-and-wait!` for use inside `go` blocks. Returns a channel that yields the final content string.
Supports `:timeout-ms` in options (default: `60000`, set to `nil` to disable).
Session errors and timeouts close the channel after delivering the latest
assistant content, if any; otherwise the channel closes without a value.

Combined with `<create-session`, enables fully non-blocking pipelines:

```clojure
(go
  (let [session (<! (copilot/<create-session client {:model "gpt-5.4"
                                                     :on-permission-request copilot/approve-all}))
        answer  (<! (copilot/<send! session {:prompt "Explain monads"}))]
    (println answer)))
```

#### `<send-and-wait!`

```clojure
(copilot/<send-and-wait! session options)
```

Async equivalent of `send-and-wait!` for use inside `go` blocks. Returns a channel that yields the final assistant message **event** — the same shape as `send-and-wait!`'s successful return value (content lives under `[:data :content]`), or closes with nothing if no assistant message was received.
Supports `:timeout-ms` in options (default: `60000`, set to `nil` to disable).
Autopilot idle events do not terminate the wait.

Error semantics differ from `send-and-wait!`: where `send-and-wait!` throws on `:copilot/session.error` or timeout, this variant never surfaces those — the channel closes (delivering the last assistant message if one arrived, otherwise nothing), consistent with `<send!`.

```clojure
(go
  (let [session (<! (copilot/<create-session client {:on-permission-request copilot/approve-all}))
        event   (<! (copilot/<send-and-wait! session {:prompt "Explain monads"}))]
    (println (get-in event [:data :content]))))
```

Use `<send!` when you only need the content string; use `<send-and-wait!` when you need the full event (metadata, id, etc.).

#### `events`

```clojure
(copilot/events session)
```

Get the core.async `mult` for session events. Use `tap` to subscribe:

```clojure
(let [ch (chan 100)]
  (tap (copilot/events session) ch)
  (go-loop []
    (when-let [event (<! ch)]
      (println event)
      (recur))))
```

#### `events->chan`

```clojure
(copilot/events->chan session {:buffer 256
                               :xf (filter #(= :copilot/assistant.message (:type %)))})
```

Subscribe to session events with optional buffer size and transducer.

#### `subscribe-events`

```clojure
(copilot/subscribe-events session)
```

Subscribe to session events. Returns a channel (sliding buffer, size 1024) that receives events.
This is a convenience wrapper around `(tap (copilot/events session) ch)`.

##### Event Drop Behavior

Session events are delivered via core.async `mult` to a per-subscriber **sliding-buffer**
channel. Because a sliding buffer never blocks on `put!`, `mult` is never stalled by a slow
subscriber. **If a subscriber falls behind and its buffer fills, the oldest buffered events
are dropped for that subscriber only** to make room for new ones.

Key points:
- **Per-subscriber**: Each subscriber is independent. A slow subscriber drops its own oldest
  events without affecting delivery to other subscribers.
- **Oldest-first**: When the buffer is full, the oldest buffered events are dropped, not the
  newest — subscribers always see the most recent events.
- **Silent**: No error, warning, or indication that a drop occurred.
- **Not recoverable**: Dropped events are gone for that subscriber.

With the default 1024 buffer, drops are unlikely unless a subscriber completely stops
reading. For most use cases, this is not a concern.

#### `unsubscribe-events!`

```clojure
(copilot/unsubscribe-events! session ch)
```

Unsubscribe a channel from session events.

#### `abort!`

```clojure
(copilot/abort! session)
```

Abort the currently processing message.

#### `get-messages`

```clojure
(copilot/get-messages session)
```

Get all events/messages from this session.

#### `handle-pending-tool-call!` / `<handle-pending-tool-call!`

```clojure
(copilot/handle-pending-tool-call! session
                                   {:request-id "tool-req-7"
                                    :result "STATUS_OK"})
;; or async:
(copilot/<handle-pending-tool-call! session {:request-id "tool-req-7"
                                             :error "lookup failed"})
```

Resolve a tool call that was not auto-handled (because `:handler` was omitted
from `define-tool`). The args map accepts `:request-id` plus either `:result`
(string or full result map) or `:error` (string). Sent on the wire as
`session.tools.handlePendingToolCall`. (upstream PR #1308)

#### `handle-pending-permission-request!` / `<handle-pending-permission-request!`

```clojure
(copilot/handle-pending-permission-request! session
                                            {:request-id "perm-req-3"
                                             :result {:kind :approve-once}})
```

Resolve a permission request that was not auto-handled (because
`:on-permission-request` was omitted from the session config). The result map
must contain a `:kind` other than `:no-result`. Sent on the wire as
`session.permissions.handlePendingPermissionRequest`. These helpers are
experimental, matching their upstream annotation. (upstream PR #1308)

#### `get-current-model`

```clojure
(copilot/get-current-model session)
;; => "gpt-5.4"
```

Get the current model for this session. Returns the model ID string, or nil if none set.

#### `switch-model!`

```clojure
(copilot/switch-model! session "claude-sonnet-4.5")
;; => "claude-sonnet-4.5"

;; With model capabilities override (upstream PR #1029):
(copilot/switch-model! session "gpt-5.4"
  {:model-capabilities {:supports {:vision true}
                        :limits {:max-prompt-tokens 128000}}})
```

Switch the model for this session mid-conversation. Returns the new model ID string, or nil.

Optional opts map:
- `:reasoning-effort` — Reasoning effort level ("low", "medium", "high", "xhigh", or "max")
- `:reasoning-summary` — Reasoning summary mode ("none", "concise", "detailed"). Wire-encoded as `reasoningSummary`.
- `:context-tier` — Context window tier for models that support it: `:default` or `:long-context` (upstream PR #1522). Wire-encoded as `contextTier` with values `"default"` / `"long_context"`.
- `:model-capabilities` — Model capabilities override map, e.g. `{:supports {:vision true} :limits {:max-prompt-tokens 128000}}`

#### `set-model!`

```clojure
(copilot/set-model! session "claude-sonnet-4.5")
;; => "claude-sonnet-4.5"
```

Alias for `switch-model!`, matching the upstream SDK's `setModel()` API.

```clojure
(copilot/with-client-session [session {:model "gpt-5.4"
                                       :on-permission-request copilot/approve-all}]
  (println "Before:" (copilot/get-current-model session))
  (copilot/set-model! session "claude-sonnet-4.5")
  (println "After:" (copilot/get-current-model session)))
;; prints:
;; Before: gpt-5.4
;; After: claude-sonnet-4.5
```

#### `log!`

```clojure
(copilot/log! session "Processing started")
(copilot/log! session "Something went wrong" {:level "error"})
(copilot/log! session "Temporary note" {:ephemeral? true})
```

Log a message to the session timeline. Returns the event ID string.

**Options (optional map):**

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `:level` | string | `"info"` | Log severity: `"info"`, `"warning"`, or `"error"` |
| `:ephemeral?` | boolean | `false` | When `true`, the message is transient and not persisted to disk |

#### `disconnect!`

```clojure
(copilot/disconnect! session)
```

Disconnect the SDK from the session without destroying the runtime session.
The runtime is detached before local resources are released. An unsuccessful
response is retried exactly once; a second unsuccessful response throws and
keeps the local session connected for an explicit retry.

Transport exceptions are not retried automatically. A timeout, interruption,
or other detach failure leaves local resources connected only while the client
transport remains live. Actual connection loss performs client-wide local
cleanup, so retry requires reconnecting and resuming the runtime session.
`disconnect!` intentionally applies no client-side timeout because an ambiguous
timeout cannot determine whether runtime ownership was detached. Use
`force-stop!` when a wedged transport prevents graceful client shutdown.
Interrupted threads retain their interrupted status.
While one disconnect is in progress, concurrent callers wait for that operation
without sending another runtime request, then observe the same result or
exception instance.

#### `destroy!` *(deprecated)*

```clojure
(copilot/destroy! session)
```

**Deprecated.** Use `disconnect!` instead. `destroy!` delegates to `disconnect!` and will be removed in a future release.

#### `session-id`

```clojure
(copilot/session-id session)
```

Get the session's unique identifier.

#### `workspace-path`

```clojure
(copilot/workspace-path session)
```

Get the session workspace path when provided by the CLI (may be nil).

#### `session-config`

```clojure
(copilot/session-config session)
;; => {:model "gpt-5.4", :streaming? true, :reasoning-effort "high", ...}
```

Get the configuration that was used to create this session.

#### `client`

```clojure
(copilot/client session)
```

Get the client that owns this session.

---

### Experimental RPC Methods

> **Note:** These are experimental APIs wrapping emerging CLI RPC methods. They may change in future releases.

```clojure
(require '[github.copilot-sdk.session :as session])

;; List available skills
(session/skills-list my-session)
;; => {:skills [{:name "update-docs" :source-location "project" ...} ...]}

;; Enable/disable MCP servers
(session/mcp-enable! my-session "my-server")
(session/mcp-disable! my-session "my-server")

;; Get/set agent mode
(session/mode-get my-session)
;; => {:mode "interactive"}
(session/mode-set! my-session "plan")

;; Read/update session plan
(session/plan-read my-session)
;; => {:exists? true :content "# Plan\n..." :file-path "/path/to/plan.md"}
(session/plan-update! my-session "# Updated Plan\n...")
(session/plan-delete! my-session)

;; Workspace file operations
(session/workspace-list-files my-session)
;; => {:files ["notes.md" "data.json"]}
(session/workspace-read-file my-session "notes.md")
;; => {:content "..."}
(session/workspace-create-file! my-session "output.txt" "result data")

;; Custom agent management
(session/agent-list my-session)
;; => {:agents [{:name "researcher" ...} ...]}
(session/agent-select! my-session "researcher")
(session/agent-get-current my-session)
;; => {:name "researcher"}
(session/agent-deselect! my-session)
```

**Skills**

| Function | Description |
|----------|-------------|
| `session/skills-list` | List available skills. Returns map with `:skills`. |
| `session/skills-enable!` | Enable a skill by name. |
| `session/skills-disable!` | Disable a skill by name. |
| `session/skills-reload!` | Reload all skills. |

**Queued commands**

The CLI emits `:copilot/command.queued` events when a slash-command is
dispatched for client-side execution. Each event carries a `:request-id`
and `:command`. Clients respond via `respond-to-queued-command!`:

```clojure
;; Inside an event handler that observes :copilot/command.queued
(session/respond-to-queued-command! session
                                    {:request-id (:request-id event-data)
                                     :handled? true
                                     :stop-processing-queue? false})

;; Or, to let the CLI fall back to default handling:
(session/respond-to-queued-command! session
                                    {:request-id (:request-id event-data)
                                     :handled? false})
```

| Function | Description |
|----------|-------------|
| `session/respond-to-queued-command!` | Acknowledge a `command.queued` event (experimental). |

**MCP Servers**

| Function | Description |
|----------|-------------|
| `session/mcp-list` | List configured MCP servers. |
| `session/mcp-enable!` | Enable an MCP server by name. |
| `session/mcp-disable!` | Disable an MCP server by name. |
| `session/mcp-reload!` | Reload all MCP servers. |

**Extensions**

| Function | Description |
|----------|-------------|
| `session/extensions-list` | List extensions. |
| `session/extensions-enable!` | Enable an extension by ID. |
| `session/extensions-disable!` | Disable an extension by ID. |
| `session/extensions-reload!` | Reload all extensions. |

**Mode**

| Function | Description |
|----------|-------------|
| `session/mode-get` | Get current agent mode. Returns `{:mode "interactive"\|"plan"\|"autopilot"}`. |
| `session/mode-set!` | Set agent mode. Accepts `"interactive"`, `"plan"`, or `"autopilot"`. |

**Plan**

| Function | Description |
|----------|-------------|
| `session/plan-read` | Read the session plan file. Returns `{:exists? :content :file-path}`. |
| `session/plan-update!` | Update the plan file content. |
| `session/plan-delete!` | Delete the plan file. |

**Workspace**

| Function | Description |
|----------|-------------|
| `session/workspace-list-files` | List files in the session workspace. Returns `{:files [...]}`. |
| `session/workspace-read-file` | Read a workspace file by relative path. Returns `{:content "..."}`. |
| `session/workspace-create-file!` | Create a file in the workspace with given path and content. |

**Agents**

| Function | Description |
|----------|-------------|
| `session/agent-list` | List available custom agents. Returns `{:agents [...]}`. |
| `session/agent-get-current` | Get the currently selected agent. Returns `{:name "..."}` or `{:name nil}`. |
| `session/agent-select!` | Select a custom agent by name. |
| `session/agent-deselect!` | Deselect the current custom agent. |
| `session/agent-reload!` | Reload all custom agents. |

**Fleet**

| Function | Description |
|----------|-------------|
| `session/fleet-start!` | Start parallel sub-sessions. Accepts a params map. |

**Other**

| Function | Description |
|----------|-------------|
| `session/plugins-list` | List plugins. |
| `session/compaction-compact!` | Trigger manual context compaction (uses `session.history.compact` RPC). |
| `session/history-truncate!` | Trigger manual context truncation. |
| `session/history-clear-context!` | Clear conversation context and start a new one with a given prompt. Also available as the top-level `copilot/history-clear-context!` facade wrapper. |
| `session/sessions-fork!` | Fork the current session. |
| `session/shell-exec!` | Execute a shell command. |
| `session/shell-kill!` | Kill a running shell process. |

```clojure
;; session/history-clear-context! or copilot/history-clear-context!
(session/history-clear-context! my-session "Let's start fresh: focus on the auth module.")
;; => {:messages-cleared 42}
```

([upstream PR #2129](https://github.com/github/copilot-sdk/pull/2129))

**Session Name**

| Function | Description |
|----------|-------------|
| `session/session-name-get` | Get the session name (or auto-generated summary). Returns `{:name "..."}`. |
| `session/session-name-set!` | Set the session name (1–100 characters). |

```clojure
(session/session-name-get my-session)
;; => {:name "My debugging session"}

(session/session-name-set! my-session "Refactoring auth module")
```

**Workspace (Extended)**

| Function | Description |
|----------|-------------|
| `session/workspace-get-workspace` | Get current workspace metadata. Returns `{:workspace {...}}`. |

```clojure
(session/workspace-get-workspace my-session)
;; => {:workspace {:path "/home/user/project" ...}}
```

**MCP Discovery**

| Function | Description |
|----------|-------------|
| `session/mcp-discover` | Discover MCP servers in a directory. Accepts optional opts map with `:working-directory`. |

```clojure
(session/mcp-discover my-session)

(session/mcp-discover my-session {:working-directory "/path/to/project"})
```

**Usage Metrics**

| Function | Description |
|----------|-------------|
| `session/usage-get-metrics` | Get usage metrics for the session. |

```clojure
(session/usage-get-metrics my-session)
```

**Remote Sessions** (experimental, upstream PR #1192)

| Function | Description |
|----------|-------------|
| `session/remote-enable` | Enable remote steerability for the session. Returns `{:url <string?> :remote-steerable <boolean>}`. Optional 2-arity `opts` map accepts `:mode` set to `:off`, `:export`, or `:on` (upstream CLI 1.0.48-1). |
| `session/remote-disable` | Disable remote steerability for the session. Returns `nil`. |

```clojure
(session/remote-enable my-session)
;; => {:url "https://copilot-remote.test/abc" :remote-steerable true}

;; Optional per-session mode (upstream CLI 1.0.48-1):
;; - :off    — disable remote
;; - :export — export session events to Mission Control without remote steering
;; - :on     — export + enable remote steering
(session/remote-enable my-session {:mode :export})
;; => {:remote-steerable false}

(session/remote-disable my-session)
;; => nil
```

---

## UI Elicitation

Request structured user input via interactive dialogs. Check host support before calling.

```clojure
(require '[github.copilot-sdk :as copilot])
```

### `capabilities`

```clojure
(copilot/capabilities session)
;; => {:ui {:elicitation true}}
```

Get the host capabilities map reported when the session was created or resumed.

### `open-canvases`

```clojure
(copilot/open-canvases session)
;; => [{:instance-id "i1" :canvas-id "diff" :extension-id "ext.x"
;;      :icon "/tmp/diff.png"}]
```

Get the current open-canvases snapshot for `session`. Returns a vector of
canvas-instance maps. The snapshot is initialized from `session.resume` and
updated by `:copilot/session.canvas.opened` / `:copilot/session.canvas.closed`
events. `session.create` does NOT populate it (matches upstream Node.js).

> **Parity exclusion:** The upstream experimental `Canvas` / `createCanvas`
> authoring and provider API is intentionally absent. This SDK can observe and
> restore open canvases, but cannot declare canvases or handle provider
> open/close/action callbacks. `:canvas-provider` supplies provider identity
> only.

Each entry has required keys `:instance-id`, `:extension-id`, `:canvas-id`,
and optional `:extension-name`, `:title`, `:status`, `:url`, `:input`, and
`:icon` (a host-local PNG path). Closing an instance that's not in the snapshot
is a silent no-op (idempotent); malformed payloads (missing required field or
wrong type) log a warning and leave the snapshot unchanged.

The `:input` map (caller-defined opaque data on each canvas) is preserved
verbatim through wire conversion. Keys you receive (e.g. via the canvas
opened event or after a resume) round-trip back to the CLI without
camelCasing — including `snake_case` and nested keys.

#### Seeding `open-canvases` on resume

To restore canvases after reconnecting, pass `:open-canvases` to
[`resume-session`](#resume-session) or [`join-session`](#join-session). The
shape mirrors what `(open-canvases session)` returned previously:

```clojure
(let [snap (copilot/open-canvases old-session)]
  (copilot/resume-session client session-id
                          {:on-permission-request copilot/approve-all
                           :open-canvases snap}))
```

The SDK preserves caller-defined `:input` keys verbatim on the wire (they are
sent as JSON object fields with the original key names, unchanged by Clojure's
kebab-case conversion).

### `elicitation-supported?`

```clojure
(copilot/elicitation-supported? session)
;; => true
```

Return `true` if the CLI host supports interactive elicitation dialogs.

### `confirm!`

```clojure
(copilot/confirm! session message)
```

Show a confirmation dialog. Returns `true` if the user confirms, `false` if they decline or cancel. Throws if elicitation is not supported.

```clojure
(when (copilot/elicitation-supported? session)
  (when (copilot/confirm! session "Deploy to production?")
    (println "Deploying...")))
```

### `select!`

```clojure
(copilot/select! session message options)
```

Show a selection dialog with the given options. Returns the selected value as a string, or `nil` if the user declines or cancels. Throws if elicitation is not supported.

```clojure
(when-let [env (copilot/select! session "Choose environment" ["staging" "production"])]
  (println "Selected:" env))
```

### `input!`

```clojure
(copilot/input! session message)
(copilot/input! session message opts)
```

Show a text input dialog. Returns the entered text as a string, or `nil` if the user declines or cancels. Throws if elicitation is not supported.

**Options:**

| Key | Type | Description |
|-----|------|-------------|
| `:title` | string | Title for the input field |
| `:description` | string | Description text |
| `:min-length` | integer | Minimum input length |
| `:max-length` | integer | Maximum input length |
| `:format` | string | Input format (`"email"`, `"uri"`, `"date"`, `"date-time"`) |
| `:default` | string | Default value |

```clojure
(when-let [name (copilot/input! session "Enter your name"
                  {:min-length 1
                   :max-length 100})]
  (println "Hello," name))
```

### `ui-elicitation!`

```clojure
(copilot/ui-elicitation! session params)
```

Raw elicitation request for custom JSON schemas. `params` is a map with `:message` and `:requested-schema` keys. Returns a map with `:action` (`"accept"`, `"decline"`, or `"cancel"`) and `:content`. Throws if elicitation is not supported.

```clojure
(copilot/ui-elicitation! session
  {:message "Configure deployment"
   :requested-schema {:type "object"
                      :properties {"env" {:type "string" :enum ["staging" "production"]}
                                   "replicas" {:type "number" :default 3}}
                      :required ["env"]}})
;; => {:action "accept", :content {:env "staging", :replicas 3}}
```

---

## MCP OAuth Handler

Some MCP servers require interactive (browser-based) OAuth. Register an
`:on-mcp-auth-request` handler to take over that flow; without it, the runtime
falls back to a browserless cached-token path and never prompts. (upstream PR #1669)

```clojure
(require '[github.copilot-sdk :as copilot])

(def session
  (copilot/create-session
    client
    {:on-permission-request copilot/approve-all
     :on-mcp-auth-request
     (fn [request ctx]
       ;; request is the McpAuthRequest; ctx is {:session-id "..."}
       (let [token (acquire-oauth-token! (:server-url request))]
         (if token
           {:access-token token :token-type "Bearer" :expires-in 3600}
           {:kind :cancelled})))}))
```

When the handler is provided, the SDK registers interest in the
`mcp.oauth_required` event (before the `session.create`/`session.resume` runtime
work begins) so the runtime delegates the OAuth request to your handler. The
handler is invoked with two arguments:

1. An `McpAuthRequest` map (the event data).
2. A context map `{:session-id "..."}`.

The handler may return the result directly or a `core.async` channel yielding it.

**`McpAuthRequest` fields:**

| Key | Type | Description |
|-----|------|-------------|
| `:request-id` | string | Opaque id correlating the request with its response. |
| `:server-name` | string | Configured name of the MCP server. |
| `:server-url` | string | URL of the MCP server requiring auth. |
| `:reason` | string | Why authentication is needed. |
| `:www-authenticate-params` | map | (optional) Parsed `WWW-Authenticate` challenge params. |
| `:resource-metadata` | string | (optional) Raw OAuth protected-resource metadata document. |
| `:static-client-config` | map | (optional) Pre-registered OAuth client config. |

**Result mapping:** Return a map with `:access-token` (plus optional
`:token-type` and `:expires-in`) to answer with a token. Return `nil`,
`{:kind :cancelled}`, or throw to cancel the request — a thrown handler never
wedges the pending request (errors are swallowed and treated as a cancel,
matching upstream).

---

## Event Types

Sessions emit various events during processing. All event types are namespaced keywords prefixed with `copilot/`.

### Exported Constants

```clojure
;; All event types
copilot/event-types
;; => #{:copilot/session.idle :copilot/assistant.message ...}

;; Session lifecycle events
copilot/session-events
;; => #{:copilot/session.start :copilot/session.idle ...}

;; Assistant response events  
copilot/assistant-events
;; => #{:copilot/assistant.message :copilot/assistant.message_delta ...}

;; Tool execution events
copilot/tool-events
;; => #{:copilot/tool.execution_start :copilot/tool.execution_complete ...}

;; Interaction flow events (permission, user input, elicitation)
copilot/interaction-events
;; => #{:copilot/permission.requested :copilot/permission.completed
;;      :copilot/user_input.requested :copilot/user_input.completed
;;      :copilot/elicitation.requested :copilot/elicitation.completed
;;      :copilot/external_tool.requested :copilot/external_tool.completed
;;      :copilot/mcp.oauth_required :copilot/mcp.oauth_completed
;;      :copilot/command.queued :copilot/command.execute
;;      :copilot/command.completed :copilot/commands.changed
;;      :copilot/exit_plan_mode.requested :copilot/exit_plan_mode.completed}
```

For schema 1.0.83-1, `:copilot/assistant.server_tool_progress` also belongs to
`copilot/assistant-events`. `:copilot/session.managed_settings_enforced` and
`:copilot/session.managed_settings_resolved` belong to `copilot/session-events`.
`:copilot/tool_search.activated` intentionally belongs only to the master
`copilot/event-types` set, not `copilot/interaction-events` or `copilot/tool-events`.

The generated wire schemas also contain the internal `assistant.turn_retry`
(additional model inference metadata within an existing turn) and
`model.call_start` (model API dispatch metadata) events. They are wire-only and
intentionally excluded from every curated public event set. Experimental
HydraFusion routing events likewise remain generated wire evidence and are not
curated as public idiom events. The experimental `reasoningBlocks` field on
`assistant.message` also remains generated wire evidence rather than a stable
curated idiom field.

### `evt` — Event Keyword Helper

```clojure
(copilot/evt :session.info)      ;; => :copilot/session.info
(copilot/evt :assistant.message) ;; => :copilot/assistant.message
```

Convert an unqualified event keyword to a namespace-qualified `:copilot/` keyword. Throws `IllegalArgumentException` if the keyword is not a valid event type.

### Event Reference

Curated `::specs/result` and `::specs/error` values accept recursive JSON:
`nil`, strings, booleans, finite non-ratio numbers, vectors, and maps whose keys
are strings or keywords and whose values recursively satisfy the same contract.
Event-specific specs may impose a narrower shape. Generated event envelopes
remain open at the top-level data map for forward-compatible fields, while
nested schema objects marked closed by upstream reject unknown keys.

| Event Type | Description |
|------------|-------------|
| `:copilot/session.start` | Session created |
| `:copilot/session.resume` | Session resumed |
| `:copilot/session.error` | Session error occurred; data requires `:error-type` and `:message`, with optional `:stack`, `:status-code`, `:provider-call-id`, `:url`, and `:remediation`. Remediation values are `"sign_in"`, `"switch_account"`, `"show_account"`, `"review_sandbox_policy"`, and `"allow_sandbox_outbound"`. |
| `:copilot/session.idle` | Session finished processing. When the event's `:data` includes `:mode "autopilot"`, this idle is a nonterminal turn boundary rather than the end of processing — see [`send-and-wait!`](#send-and-wait), [`query-seq!`](#query-seq), and [`query-chan`](#query-chan) for how the SDK's blocking/streaming helpers treat autopilot idle events. |
| `:copilot/session.info` | Informational session update |
| `:copilot/session.model_change` | Session model changed; data requires `:new-model` and may include `:previous-model`, `:previous-reasoning-effort`, `:reasoning-effort`, and `:source`. Known sources include `"model_command"`, `"config_command"`, `"model_picker"`, `"automatic"`, `"startup"`, `"managed_settings"`, `"agent"`, and `"sdk"`. |
| `:copilot/session.handoff` | Session handed off to another agent; data: `{:remote-session-id "..." :host "https://github.com"}` (both optional) |
| `:copilot/session.usage_info` | Token usage information |
| `:copilot/session.context_changed` | Session context (cwd, repo, branch) changed |
| `:copilot/session.title_changed` | Session title updated |
| `:copilot/session.warning` | Session warning; data requires `:warning-type` and `:message`, with optional `:url` and the same `:remediation` values as `session.error`. |
| `:copilot/session.shutdown` | Session is shutting down. Optional `:agent-metrics` maps agent keywords to `{:model-metrics {...} :total-api-duration-ms N :total-nano-aiu N}` plus optional `:agent-name` and `:agent-display-name`, enabling per-agent accounting alongside the session totals. |
| `:copilot/session.truncation` | Context window truncated |
| `:copilot/session.snapshot_rewind` | Session state rolled back |
| `:copilot/session.context_cleared` | Conversation context cleared and restarted with a new prompt (via `history-clear-context!`); data: `{:messages-cleared N}` (required) with optional `:initial-message` (the prompt used to start the new context) (upstream PR #2129) |
| `:copilot/session.compaction_start` | Context compaction started (infinite sessions); data: `{:model "..." :current-tokens N :token-limit N :trigger "..."}` (all optional). `:trigger` is one of `"threshold"`, `"context_limit_retry"`, `"manual"`, `"memory_pressure"`, `"model_switch"` (upstream schema 1.0.79-5/6) |
| `:copilot/session.compaction_complete` | Context compaction completed (infinite sessions); data: `{:success bool}` (required) with optional `:error "..."`, `:status-code N`, `:token-limit N`, `:trigger "..."` (same `:trigger` enum as `compaction_start`), `:behavior-model-id` (string — model identifier used for the compaction behavior/summarization pass; upstream schema 1.0.83-1) (upstream schema 1.0.79-5/6) |
| `:copilot/session.mode_changed` | Session agent mode changed; data: `{:previous-mode "...", :new-mode "..."}` |
| `:copilot/session.mode_notice_delivered` | A mode notice was delivered to the model; data requires `:mode` (`"interactive"`, `"plan"`, or `"autopilot"`) and may include string `:content`. The payload remains open for additive runtime fields. |
| `:copilot/session.plan_changed` | Session plan created/updated/deleted; data: `{:operation "create"/"update"/"delete"}` |
| `:copilot/session.workspace_file_changed` | Workspace file created or updated; data: `{:path "...", :operation "create"/"update"}` |
| `:copilot/session.task_complete` | Task completed by the session agent; data (all optional): `:summary "..."`, `:aborted? false`, `:outcome` (`"completed"`, `"continue"`, or `"blocked"`), `:objective-id`, `:reason`, `:success` (upstream schema 1.0.79-5/6) |
| `:copilot/session.todos_changed` | Signal-only: the agent's todos / todo-deps table was written. **No payload.** Events arrive in order; debounce on arrival if needed (upstream schema 1.0.63) |
| `:copilot/session.schedule_created` | Scheduled prompt registered via `/every`; data: `{:id <pos-int> :interval-ms <pos-int> :prompt "..."}` (upstream schema 1.0.42) |
| `:copilot/session.schedule_cancelled` | Scheduled prompt cancelled from the schedule manager dialog; data: `{:id <pos-int>}` (upstream schema 1.0.42) |
| `:copilot/session.autopilot_objective_changed` | Autopilot objective lifecycle events; data: `{:operation #{"create" "update" "delete"}}` (required) with optional `:id` (integer) and `:status` (upstream schema 1.0.56). The `:status` enum is widened to include `"active"`, `"paused"`, `"cap_reached"`, `"completed"`. |
| `:copilot/session.permissions_changed` | **Experimental.** Per-session permission mode changed; data: `{:mode <mode> :previous-mode <mode>}` with optional `:assisted-approval-model`, where mode is one of `"manual"`, `"assisted"`, or `"allow-all"` (upstream schema 1.0.81-5). |
| `:copilot/session.session_limits_changed` | Session limits changed; data: `{:session-limits {:max-ai-credits <number>}}`, where a `nil` `:session-limits` clears the active limits (upstream schema 1.0.67) |
| `:copilot/session.usage_checkpoint` | Durable usage checkpoint for reconstructing aggregate accounting on resume; data: `{:total-nano-aiu <number>}` with optional `:total-premium-requests <number>` (upstream schema 1.0.67) |
| `:copilot/session.auto_mode_resolved` | Auto model-selection resolved the model for the first prompt of an auto-mode session; data includes `:chosen-model`, optional `:candidate-models`, `:category-scores`, `:confidence`, `:predicted-label`, `:reasoning-bucket` (experimental; upstream schema 1.0.70-0) |
| `:copilot/session.managed_settings_enforced` | Experimental ephemeral enforcement of enterprise managed settings for a concrete user- or host-initiated governed action. Data: `{:action "bypass_permissions_blocked" :setting <string> :fail-closed <boolean> :message <string>}` with optional `:escalation` in `#{"allow_all" "approve_all" "auto_approval" "unrestricted_paths" "unrestricted_urls"}`. |
| `:copilot/session.managed_settings_resolved` | Experimental ephemeral snapshot of effective enterprise managed settings and their authority, emitted when policy is applied or reapplied at session start, on resume, or on account switch. Data: `{:source #{"server" "device" "none"} :server-managed <boolean> :device-managed <boolean> :fail-closed <boolean> :bypass-permissions-disabled <boolean> :managed-keys [<string> ...]}` with optional opaque JSON `:settings`. |
| `:copilot/session.schedule_rearmed` | Self-paced schedule re-armed for its next run |
| `:copilot/session.binary_asset` | Canonical bytes for a content-addressed binary asset shared by reference across events |
| `:copilot/session.extensions.attachments_pushed` | Extension pushed attachments into the session |
| `:copilot/skill.invoked` | Skill invocation triggered; data requires `:name`, `:path`, and `:content`, with optional `:description`, `:allowed-tools`, `:plugin-name`, `:plugin-version`, `:disable-model-invocation`, and string `:source`. Known source values include `"project"`, `"inherited"`, `"personal-copilot"`, `"personal-agents"`, `"plugin"`, `"custom"`, `"builtin"`, `"remote"`, and `"sdk"`; the field remains open for additional runtime-provided identifiers. SDK-provided skills may use an empty path. |
| `:copilot/user.message` | User message added; data requires `:content` and may include correlation fields `:message-id`, `:turn-id`, and `:interaction-id`, plus `:source`, `:transformed-content`, and `:is-autopilot-continuation`. |
| `:copilot/pending_messages.modified` | Pending message queue updated |
| `:copilot/assistant.turn_start` | Assistant turn started |
| `:copilot/assistant.intent` | Assistant intent update |
| `:copilot/assistant.reasoning` | Model reasoning (if supported); optional data: `:rte` (opaque round-trip encrypted reasoning token, for providers that require it to be replayed back) (upstream schema 1.0.79-5/6) |
| `:copilot/assistant.reasoning_delta` | Streaming reasoning chunk |
| `:copilot/assistant.message_start` | Streaming assistant message start metadata |
| `:copilot/assistant.message` | Complete assistant response; optional data: `:chunk-index`, `:chunk-count` (position/count when the response was split across multiple messages), `:citations` (see [Citations](#citations-experimental)), and `:rte` (upstream schema 1.0.79-5/6). Each `:tool-requests` entry may include `:type` (`"function"` or `"custom"`) and hosted-program attribution as `:caller {:caller-id "..." :type "program"}`. Its `:arguments` is validated only as recursive JSON (`nil`, strings, booleans, finite non-ratio numbers, vectors, and maps with string or keyword keys); source-defined keys are preserved verbatim rather than kebab-cased. |
| `:copilot/assistant.message_delta` | Streaming response chunk |
| `:copilot/assistant.streaming_delta` | Response size update during streaming; data: `{:total-response-size-bytes N}` |
| `:copilot/assistant.turn_end` | Assistant turn completed |
| `:copilot/assistant.usage` | Token usage and cost for an individual API call. Required: `:model` (string). Optional: `:input-tokens`, `:output-tokens`, `:reasoning-tokens`, `:accepted-prediction-tokens`, `:rejected-prediction-tokens`, `:cache-read-tokens`, `:cache-write-tokens`, `:cache-expires-at` (`java.time.Instant` — when the prompt cache expires), `:service-request-id` (string — `x-copilot-service-request-id` for CAPI log correlation), `:api-endpoint`, `:api-call-id`, `:provider-call-id`, `:content-filter-triggered` (boolean), `:finish-reason` (string), `:cost`, `:duration`, `:time-to-first-token-ms`, `:ttft-ms`, `:output-ttft-ms` (finite non-negative number, time to first *output* token, distinct from `:ttft-ms`; upstream schema 1.0.83-1), `:inter-token-latency-ms`, `:reasoning-effort`, `:reasoning-summary` (`"none"`, `"concise"`, or `"detailed"`), `:initiator`, `:parent-tool-call-id` (deprecated), `:copilot-usage`, `:quota-snapshots`, `:interaction-type`, `:is-auto`, `:is-byok`, `:max-output-tokens`, `:max-prompt-tokens`, `:transport` (`"http"` or `"websocket"`), `:rte` ([upstream PR #2074](https://github.com/github/copilot-sdk/pull/2074); `:interaction-type`/`:rte` added in upstream schema 1.0.79-5/6) |
| `:copilot/assistant.idle` | Main agent's processing loop went idle, including while related background work (running sub-agents or in-flight attached shell commands) is still pending (upstream schema 1.0.66) |
| `:copilot/assistant.tool_call_delta` | Streaming tool-call argument input chunk; data includes `:tool-call-id`, `:input-delta`, optional `:tool-name`, `:tool-type` (upstream schema 1.0.69-3) |
| `:copilot/assistant.server_tool_progress` | Ephemeral live progress for a provider-hosted server tool before the finalized `serverTools` envelope arrives on the terminal `assistant.message`. Data: `{:output-index <integer> :kind <string> :status <string>}`; only `"web_search"` is currently emitted for `:kind`, and `:status` is `"in_progress"`, `"searching"`, or `"completed"`. |
| `:copilot/model.call_failure` | Failed LLM API call metadata for telemetry; data requires `:source` (`"top_level"`, `"subagent"`, or `"mcp_sampling"`) and may include string `:interaction-type`. |
| `:copilot/model.call_finished` | Completed model dispatch metadata; data requires `:turn-id`, non-negative `:dispatch-duration-ms`, `:outcome` (`"success"`, `"error"`, `"cancelled"`, or `"rejected"`), and positive `:edit-classifier-version`. Optional fields: `:interaction-id` and `:contains-built-in-file-edit-request`. The payload remains open for additive runtime fields. |
| `:copilot/abort` | Current message aborted |
| `:copilot/tool.user_requested` | Tool execution requested by user |
| `:copilot/tool.execution_start` | Tool execution started; data includes `:tool-call-id`, `:tool-name`, optional `:arguments` (an opaque JSON object with source-defined, non-kebab-cased keys), `:parent-tool-call-id`, `:mcp-server-name`, `:mcp-tool-name`, `:model` |
| `:copilot/tool.execution_progress` | Tool execution progress update |
| `:copilot/tool.execution_partial_result` | Tool execution partial result |
| `:copilot/tool.execution_complete` | Tool execution completed; data may include optional `:structured-content` (arbitrary structured tool result) (upstream schema 1.0.63) and `:result` (recursive opaque JSON). An error may include `:message`, `:code`, and the same `:remediation` values as `session.error`. Generated wire validation still enforces known result variants, including the shell-exit variant's `:exit-code`/`:shell-id`/`:type "shell_exit"` and optional `:cwd`/`:output-file-path`/`:output-preview`/`:output-truncated`; `:output-file-path` was added in upstream schema 1.0.83-1. |
| `:copilot/tool_search.activated` | Persisted generic client-side tool activations restored when a session resumes. Data: `{:strategy <string> :tool-names [<string> ...]}`. |
| `:copilot/subagent.started` | Subagent started; data includes `:tool-call-id`, `:agent-name`, `:agent-display-name`, and `:agent-description`, with optional `:factory-run-id`, `:model`, `:resumable` (boolean), `:agent-type` (string), `:execution-mode` (string), `:parent-id` (string — task-registry id of the spawning subagent; unrelated to the envelope-level `:parent-id`) (subagent lifecycle additions upstream schema 1.0.83-1) ([upstream PR #2072](https://github.com/github/copilot-sdk/pull/2072)) |
| `:copilot/subagent.configured` | Effective subagent execution configuration; data requires string `:model` and boolean `:multi-turn`, with optional string `:reasoning-effort` and `:context-tier`. The payload remains open for additive runtime fields. |
| `:copilot/subagent.completed` | Subagent completed; data includes `:tool-call-id`, `:agent-name`, `:agent-display-name`, and optional `:cancelled`, `:model`, `:total-tool-calls`, `:total-tokens`, `:duration-ms`, `:first-dispatched-model`, `:configured-model-preference`, `:explicit-model-override`, `:model-override-reason` (strings), `:explicit-model-matches-preference`, and `:configured-model-matches-actual` (booleans). `:cancelled true` means cancellation tore down the subagent; cancellation still reports completion rather than failure. |
| `:copilot/subagent.failed` | Subagent failed; data includes `:tool-call-id`, `:agent-name`, `:agent-display-name`, `:error`, optional `:model`, `:total-tool-calls`, `:total-tokens`, `:duration-ms`, `:first-dispatched-model`, `:configured-model-preference`, `:explicit-model-override`, `:model-override-reason` (strings), `:explicit-model-matches-preference`, and `:configured-model-matches-actual` (booleans). |
| `:copilot/subagent.selected` | Subagent selected |
| `:copilot/subagent.deselected` | Subagent deselected |
| `:copilot/hook.start` | Hook invocation started; data requires `:hook-invocation-id`, `:hook-type`, with optional `:parent-tool-call-id` (upstream schema 1.0.83-1) |
| `:copilot/hook.progress` | Ephemeral progress update from a long-running hook; data: `{:message "..."}` (upstream schema 1.0.56). |
| `:copilot/hook.end` | Hook invocation finished; data requires `:hook-invocation-id`, `:hook-type`, and `:success`, with optional `:parent-tool-call-id` and closed `:error` map. The error requires string `:message`, permits optional string `:stack` and `:source`, and rejects other keys (upstream schema 1.0.83-1). |
| `:copilot/system.message` | System message emitted |
| `:copilot/system.notification` | System notification with a structured `:kind` discriminator: `agent_completed`, `agent_idle`, `new_inbox_message`, `shell_completed`, `shell_detached_completed`, `instruction_discovered`, `factory_completed`, or `unclassified`. Each known kind validates its required and optional fields; agent kinds may include `:display-name`. |
| `:copilot/permission.requested` | Permission request initiated; optional `:agent-mode` identifies the requesting mode (`"interactive"`, `"plan"`, or `"autopilot"`), and `:resolved-by-hook` indicates a hook already handled it. For the MCP tool-permission variant (`:server-name`/`:tool-name`/`:tool-title` present), optional `:can-offer-server-wide-approval` indicates the host may offer a server-wide approval option. |
| `:copilot/permission.completed` | Permission request resolved. Approved nested `:result` values may include `:managed-approval-handled`, indicating that managed policy handled the request. |
| `:copilot/user_input.requested` | User input requested from agent |
| `:copilot/user_input.completed` | User input received |
| `:copilot/elicitation.requested` | Elicitation request initiated |
| `:copilot/elicitation.completed` | Elicitation request resolved |
| `:copilot/external_tool.requested` | External tool call requested (v3); data includes `:request-id`, `:session-id`, `:tool-call-id`, `:tool-name`, and optional string-or-nil `:provider-id` for host routing. |
| `:copilot/external_tool.completed` | External tool call completed (v3) |
| `:copilot/mcp.oauth_required` | MCP server requires OAuth authentication |
| `:copilot/mcp.oauth_completed` | MCP OAuth authentication completed |
| `:copilot/mcp.headers_refresh_required` | Dynamic headers refresh request for a remote MCP server (upstream schema 1.0.66) |
| `:copilot/mcp.headers_refresh_completed` | MCP headers refresh request completed (upstream schema 1.0.66) |
| `:copilot/mcp.tools.list_changed` | Remote MCP server signalled its tool list changed; data includes `:server-name` (upstream schema 1.0.70) |
| `:copilot/mcp.resources.list_changed` | Remote MCP server signalled its resource list changed; data includes `:server-name` (upstream schema 1.0.70) |
| `:copilot/mcp.prompts.list_changed` | Remote MCP server signalled its prompt list changed; data includes `:server-name` (upstream schema 1.0.70) |
| `:copilot/command.queued` | Command queued for execution |
| `:copilot/command.execute` | Command execution started |
| `:copilot/command.completed` | Command execution completed |
| `:copilot/commands.changed` | Available commands list changed |
| `:copilot/exit_plan_mode.requested` | Exit from plan mode requested; data includes `:summary`, `:actions`, `:recommended-action`, and may identify the active `:model`. |
| `:copilot/exit_plan_mode.completed` | Exit from plan mode completed |
| `:copilot/auto_mode_switch.requested` | Auto mode switch request requiring user approval |
| `:copilot/auto_mode_switch.completed` | Auto mode switch completed |
| `:copilot/session.tools_updated` | Session tools list updated (e.g., after model change) |
| `:copilot/session.background_tasks_changed` | Background tasks status changed |
| `:copilot/session.skills_loaded` | Skills loaded for the session |
| `:copilot/session.mcp_servers_loaded` | MCP servers loaded for the session. Each server may include `:source`, plugin identity, `:error`, and `:server-metadata {:instructions <string-or-nil>}`. |
| `:copilot/session.mcp_server_status_changed` | MCP server status changed |
| `:copilot/session.mcp_server_removed` | MCP server was removed; data: `{:server-name "..."}` |
| `:copilot/session.mcp_server_needs_reconnect` | MCP server requires reconnection; data: `{:server-name "..."}` |
| `:copilot/session.extensions_loaded` | Extensions loaded for the session |
| `:copilot/session.custom_agents_updated` | Custom agents list updated. Agent entries may include ordered `:models` preferences and `:model-policy` (`"preferred"` or `"required"`). |
| `:copilot/session.custom_notification` | Custom Skill notification (Notify block); ephemeral. Data: `{:source "<ext-id>" :name "<event>" :payload <any> :subject {<k> <v>} :version <pos-int>}` (`:subject` and `:version` are optional; `:subject` keys are preserved verbatim — see PR #1292, CLI 1.0.48) |
| `:copilot/sampling.requested` | MCP sampling request initiated; ephemeral |
| `:copilot/sampling.completed` | MCP sampling request completed; ephemeral |
| `:copilot/session_limits_exhausted.requested` | Session AI-credit limit reached; the runtime requests a limit decision (add/set/unset/cancel); ephemeral observable event (upstream schema 1.0.67) |
| `:copilot/session_limits_exhausted.completed` | Session-limit decision completed; ephemeral observable event (upstream schema 1.0.67) |
| `:copilot/session.remote_steerable_changed` | Session remote steering capability changed; data: `{:remote-steerable true/false}` |
| `:copilot/capabilities.changed` | Session capabilities dynamically changed (e.g., elicitation support); ephemeral. Data: `{:ui {:elicitation true/false}}` |
| `:copilot/mcp_app.tool_call_complete` | An MCP App tool call completed (upstream schema 1.0.52-4, SEP-1865); ephemeral. Data: `{:server-name ... :tool-name ... :duration-ms ... :success bool :arguments {...} :result {...}}` — `:arguments` and `:result` are opaque source-defined maps whose keys are preserved verbatim (not kebab-cased). |
| `:copilot/session.canvas.opened` | A canvas (auxiliary UI surface) was opened in the session; ephemeral. Data: `{:instance-id ... :canvas-id ... :extension-id ... :reopen bool :availability "ready"|"stale" :extension-name? ... :title? ... :status? ... :url? ... :input? {...}}`. The SDK upserts the entry into the [`open-canvases`](#open-canvases) snapshot before publishing. |
| `:copilot/session.canvas.closed` | A canvas was closed; ephemeral. Data: `{:instance-id ... :canvas-id ... :extension-id ...}`. The SDK removes the matching entry from the [`open-canvases`](#open-canvases) snapshot before publishing. (upstream PR #1604) |
| `:copilot/session.canvas.registry_changed` | The set of canvases the host can offer changed; ephemeral. |
| `:copilot/session.canvas.unavailable` | An open canvas instance's provider dropped (e.g. the extension is reloading mid-session); ephemeral. The host should keep the panel mounted and surface a reconnecting state. (upstream schema 1.0.66) |
| `:copilot/session.canvas.recorded` | Durable record that a canvas instance is open, used to restore open canvases on cold session resume. Omits the transient `:url` and `:availability`. |
| `:copilot/factory.run_updated` | An [Agent Factory](#agent-factories-experimental) run's status changed; data: `{:run-id "..." :revision N}` (both required). Consumed internally by `wait-for-run!`/`<wait-for-run!` to detect terminal status. (upstream PR #2114) |
| `:copilot/session.canvas.removed` | Durable record that a canvas instance was closed, superseding a prior `canvas.recorded` during resume replay. |

### Citations (Experimental)

Read citations from the final `:copilot/assistant.message` event. The
`:citations` key is optional even when `:enable-citations` is true.

```clojure
{:type :copilot/assistant.message
 :data {:message-id "message-1"
        :content "Clojure was created by Rich Hickey."
        :citations
        {:sources [{:id "source-1"
                    :provider "client"
                    :title "Language notes"
                    :path "docs/languages.md"}]
         :spans [{:start-index 23
                  :end-index 34
                  :references [{:source-id "source-1"
                                :cited-text "Rich Hickey"
                                :location {:type "char"
                                           :start-index 100
                                           :end-index 111}
                                :provider-metadata
                                {:search-result-index 0}}]}]}}}
```

`:sources` contains deduplicated source maps. Each source requires `:id` and
`:provider` (`"anthropic"`, `"openai"`, or `"client"`); `:title`, `:url`, and
`:path` are optional.

Each span requires `:start-index`, `:end-index`, and `:references`. Span offsets
index the final message `:content` in UTF-16 code units: start is zero-based and
inclusive, end is exclusive. Clojure strings are Java strings, so these offsets
can be passed directly to `.substring`.

Each reference requires `:source-id`; `:cited-text`, `:location`, and
`:provider-metadata` are optional. `:provider-metadata` accepts any JSON value
(`nil`, scalar, vector, or map). It follows normal recursive `wire->clj`
conversion: map keys are normalized to kebab-case keywords, including maps
nested in vectors (`search_result_index` becomes `:search-result-index`).
Citation metadata is not an opaque-key preservation surface.

Location maps use a string `:type`: `"char"` with `:start-index` /
`:end-index`, `"page"` with `:start-page` / `:end-page`, or `"block"` with
`:start-block` / `:end-block`.

### Example: Handling Events

```clojure
(copilot/with-client-session [session {:streaming? true
                                       :on-permission-request copilot/approve-all}]
  (let [ch (chan 256)]
    (tap (copilot/events session) ch)
    (go-loop []
      (when-let [event (<! ch)]
        (case (:type event)
          :copilot/assistant.message_delta
          (print (get-in event [:data :delta-content]))
          
          :copilot/session.usage_info
          (println "Tokens:" (get-in event [:data :current-tokens]))
          
          :copilot/session.idle
          (println "\nDone!")
          
          nil)
        (recur)))
    (copilot/send! session {:prompt "Hello"})))
```

---

## Streaming

Enable streaming to receive assistant response chunks as they're generated:

```clojure
(def session (copilot/create-session client
               {:model "gpt-5.4"
                :streaming? true
                :on-permission-request copilot/approve-all}))

(let [ch (chan 100)]
  (tap (copilot/events session) ch)
  (go-loop []
    (when-let [event (<! ch)]
      (case (:type event)
        :copilot/assistant.message_delta
          ;; Streaming chunk - print incrementally
          (print (get-in event [:data :delta-content]))

        :copilot/assistant.reasoning_delta
          ;; Streaming reasoning (model-dependent). Send to stderr.
          (binding [*out* *err*]
            (print (get-in event [:data :delta-content])))

        :copilot/assistant.reasoning
          (binding [*out* *err*]
            (println "\n--- Final Reasoning ---")
            (println (get-in event [:data :content])))

        :copilot/assistant.message
          ;; Final complete message
          (println "\n--- Final ---")
          (println (get-in event [:data :content]))

        nil)
      (recur))))

(copilot/send! session {:prompt "Solve a logic puzzle and show your reasoning."})
```

When `:streaming? true`:
- `:copilot/assistant.message_delta` events contain incremental text in `:delta-content`
- `:copilot/assistant.reasoning_delta` events contain incremental reasoning in `:delta-content` (model-dependent)
- Accumulate delta values to build the full response progressively
- The final `:copilot/assistant.message` event always contains the complete content

---

## Observability

The SDK supports two independent telemetry mechanisms.

### OpenTelemetry export (client `:telemetry`)

Pass a `:telemetry` map in the **client** options to enable OpenTelemetry export on the
spawned CLI. Presence of the map enables OTel; all sub-keys are optional:

```clojure
(def client
  (copilot/client
    {:telemetry {:otlp-endpoint "http://localhost:4318"
                 :exporter-type "otlp"
                 :source-name   "my-app"
                 :capture-content? false}}))
```

| Key | Type | Description | CLI env var |
|-----|------|-------------|-------------|
| `:otlp-endpoint` | string | OTLP HTTP endpoint to export spans to | `OTEL_EXPORTER_OTLP_ENDPOINT` |
| `:otlp-protocol` | string | OTLP wire protocol: `"http/json"` or `"http/protobuf"` | `OTEL_EXPORTER_OTLP_PROTOCOL` |
| `:file-path` | string | Write spans to a local file instead of/alongside OTLP | `COPILOT_OTEL_FILE_EXPORTER_PATH` |
| `:exporter-type` | string | Exporter selection | `COPILOT_OTEL_EXPORTER_TYPE` |
| `:source-name` | string | Service / source name attached to spans | `COPILOT_OTEL_SOURCE_NAME` |
| `:capture-content?` | boolean | Capture prompt/response content in spans. **Defaults to off** — only enable in trusted environments, as it records message content | `OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT` |

When `:telemetry` is present the SDK sets `COPILOT_OTEL_ENABLED=true` on the CLI process.
(upstream PR #785, [PR #1648](https://github.com/github/copilot-sdk/pull/1648))

#### Distributed trace propagation (`:on-get-trace-context`)

To stitch CLI spans into a caller-managed distributed trace, provide a zero-arg
`:on-get-trace-context` function in the **client** options. The SDK calls it **per request**
to capture a fresh trace context — on session create, session resume, and every message
send — forwarding only `:traceparent` and `:tracestate`:

```clojure
(def client
  (copilot/client
    {:on-get-trace-context
     (fn [] {:traceparent "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
             :tracestate  "rojo=00f067aa0ba902b7"})}))
```

### GitHub telemetry forwarding (`:on-github-telemetry`)

> **@experimental / Internal.** This mirrors the official SDK's option set but is
> not a stable public surface. Shapes may change upstream.

Register a one-arg `:on-github-telemetry` callback in the **client** options to
receive the runtime's forwarded GitHub telemetry. Registering the callback is what
opts in: the SDK adds `enableGitHubTelemetryForwarding: true` to the `connect`
handshake — so the first session's un-replayable `session.start` telemetry is
forwarded (upstream PR #1909) — as well as to the wire params of both
`session.create` and `session.resume` (the flag is omitted entirely when no
callback is set — `false` is never sent). The runtime then emits connection-global
`gitHubTelemetry.event` notifications, each passed to the callback:

```clojure
(def client
  (copilot/client
    {:on-github-telemetry
     (fn [notification]
       ;; notification => {:session-id "..." :restricted false :event {...}}
       (tap> notification))}))
```

The callback runs on the client's notification loop. A throwing callback is caught
and logged (WARN) — it cannot corrupt JSON-RPC dispatch. No reply is sent (these are
notifications, not requests).

**Notification shape:**

| Key | Type | Description |
|-----|------|-------------|
| `:session-id` | string | Session the event originated from |
| `:restricted` | boolean | Whether the event is restricted |
| `:event` | map | The telemetry event (see below) |

**Event shape** — scalar keys are kebab-cased from the wire (`:kind`, `:created-at`,
`:model-call-id`, `:session-id`, `:copilot-tracking-id`, `:exp-assignment-context`),
plus an optional `:client` map of client-info scalars (`:cli-version`, `:os-platform`,
`:os-version`, `:os-arch`, `:node-version`, `:copilot-plan`, `:client-type`,
`:client-name`, `:is-staff`, `:dev-device-id`).

Three event sub-maps are **opaque source-defined data** and pass through **verbatim** —
their keys are **not** kebab-cased:

| Sub-map | Wire value type | Note |
|---------|-----------------|------|
| `:properties` | string → string | Keys preserved exactly as sent |
| `:metrics` | string → number | Keys preserved exactly as sent |
| `:features` | string → string | Keys preserved exactly as sent |

Do not rely on those keys being Clojure-idiomatic; treat them as an opaque bag keyed
by the upstream-defined strings-as-keywords. (upstream [PR #1835](https://github.com/github/copilot-sdk/pull/1835))

### Internal session telemetry (`:enable-session-telemetry?`)

`:enable-session-telemetry?` is a **session** config flag that controls the CLI's own
internal usage telemetry — independent of the OpenTelemetry export above. It defaults to
enabled for GitHub-authenticated sessions and is **always disabled** when a BYOK
`:provider` is configured. In `:mode :empty` it is defaulted to `false` as one of the
multi-tenant hardening defaults (the caller can still set it explicitly). Set it to
`false` to opt out:

```clojure
(def session
  (copilot/create-session client
    {:enable-session-telemetry? false
     :on-permission-request copilot/approve-all}))
```

(upstream PR #1224)

---

## Advanced Usage

### Manual Server Control

```clojure
(def client (copilot/client {:auto-start? false}))

;; Start manually
(copilot/start! client)

;; Use client...

;; Stop manually
(copilot/stop! client)
```

### Client Mode (Empty)

`:mode :empty` configures the client for multi-tenant SaaS hosts that must
isolate sessions from the local machine — no on-disk state from a
specific user account leaks into a session. The default `:copilot-cli`
mode preserves historical CLI behavior. (upstream PR #1428)

```clojure
(require '[github.copilot-sdk :as copilot]
         '[github.copilot-sdk.tool-set :as tool-set])

(def client
  (copilot/client
    {:mode :empty
     ;; At least ONE of :copilot-home / :session-fs / :cli-url /
     ;; :is-child-process? is required so the CLI has a tenant-scoped
     ;; storage root. Using both is fine and common:
     :copilot-home "/srv/tenants/acme/copilot-home"
     :session-fs   {:initial-cwd "/srv/tenants/acme/cwd"
                    :session-state-path "/srv/tenants/acme/state"
                    :conventions "posix"}}))

(def session
  (copilot/create-session client
    {:on-permission-request copilot/approve-all
     ;; Required in :empty mode (use [] to allow nothing — the key must
     ;; be present so silently-empty filters can't happen):
     :available-tools tool-set/isolated
     ;; Required when client has :session-fs:
     :create-session-fs-handler (fn [_session] my-fs-handler)}))
```

What `:empty` mode enforces (vs `:copilot-cli`):

- **Constructor validation**: at least one of `:copilot-home`,
  `:session-fs`, `:cli-url`, or `:is-child-process?` must be supplied
  (so the CLI never falls back to the user's home directory). The SDK
  also forces `COPILOT_DISABLE_KEYTAR=1` on the spawned CLI.
- **Session validation**: every `create-session` / `resume-session` call
  must provide `:available-tools` (an empty vector is legitimate). When
  the client has `:session-fs`, `:create-session-fs-handler` is also
  required (this applies to both modes).
- **Safe session defaults** (spread UNDER caller config — caller always wins):
  `:enable-session-telemetry? false`, `:mcp-oauth-token-storage :in-memory`,
  `:skip-embedding-retrieval true`, `:embedding-cache-storage :in-memory`,
  `:enable-on-demand-instruction-discovery false`, `:enable-file-hooks false`,
  `:enable-host-git-operations false`, `:enable-session-store false`,
  `:enable-skills false`, `:memory {:enabled false}`.
- **System message normalization**: the SDK strips the `environment_context`
  section from the system message (or promotes `:append` to `:customize`) so
  no host-environment context leaks. If the caller already provides their own
  `environment_context` override in `:customize` mode, it is preserved verbatim.
- **Post-create options**: a follow-up `session.options.update` RPC sets
  `:skip-custom-instructions true`, `:custom-agents-local-only true`,
  `:coauthor-enabled false`, `:manage-schedule-enabled false`, and forces
  `:installed-plugins []`. It also defaults `:included-builtin-skills` to `[]`
  unless the caller supplies an explicit allowlist. On failure, the SDK cleans
  up the half-configured session before propagating the error.

Both modes always emit `:tool-filter-precedence "excluded"` on
`session.create` and `session.resume`, and reject bare `"*"` in
`:available-tools` / `:excluded-tools` at the SDK boundary.

### Tool Sets

Use [`github.copilot-sdk.tool-set`](#tool-sets) to construct `:available-tools` /
`:excluded-tools` lists with built-in helpers. Mirrors the upstream
`BuiltInTools` constants. (upstream PR #1428)

```clojure
(require '[github.copilot-sdk.tool-set :as tool-set])

;; Source-qualified single tool — patterns are "<source>:<name>",
;; source is one of "builtin", "mcp", or "custom":
(tool-set/builtin "ask_user")     ; => "builtin:ask_user"
(tool-set/builtin "*")            ; => "builtin:*"   (all built-ins)
(tool-set/mcp "*")                ; => "mcp:*"      (all MCP tools)
(tool-set/custom "my_tool")       ; => "custom:my_tool"

;; Vector of patterns:
(tool-set/builtins ["task" "skill"])
;; => ["builtin:task" "builtin:skill"]

;; The "Isolated" preset matches BuiltInTools.Isolated upstream —
;; every built-in that is safely session-bounded (no host I/O):
tool-set/isolated
;; => ["builtin:ask_user" "builtin:task_complete" "builtin:exit_plan_mode" ...]
```

The constructors enforce well-formed entries: a bare `"*"` is rejected (the SDK
also rejects it in `:available-tools` / `:excluded-tools` at the session
boundary — apps must explicitly opt into a source so an absent source can
never silently grant access to unexpected tools). The runtime always receives
`:tool-filter-precedence "excluded"` on `session.create` / `session.resume`
so the ordering between allow and deny lists is deterministic.

### Tools

Let the CLI call back into your process when the model needs capabilities you provide:

```clojure
(require '[github.copilot-sdk :as copilot])

(def lookup-tool
  (copilot/define-tool "lookup_issue"
    {:description "Fetch issue details from our tracker"
     :metadata {:owner "issues-team"}
     :parameters {:type "object"
                  :properties {:id {:type "string"
                                    :description "Issue identifier"}}
                  :required ["id"]}
     :handler (fn [{:keys [id]} invocation]
                (let [issue (fetch-issue id)]
                  (copilot/result-success issue)))}))

(def session (copilot/create-session client
               {:model "gpt-5.4"
                :tools [lookup-tool]
                :on-permission-request copilot/approve-all}))
```

When Copilot invokes `lookup_issue`, the SDK automatically runs your handler and responds to the CLI.

Both `define-tool` and `define-tool-from-spec` accept these common options:

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `:defer` | `:auto` or `:never` | runtime default | Per-tool lazy-loading policy; distinct from session `:tool-search` configuration |
| `:description` | string or `nil` | `nil` | Human-readable tool description |
| `:handler` | fn | `nil` | Two-argument function receiving parsed arguments and the invocation map |
| `:is-terminal?` | boolean | `nil` | When true, a successful call ends the agent turn instead of feeding the tool result back to the model. A failed call leaves the loop running so the model can inspect the error and retry. Gated on `some?`; explicit `false` is forwarded and an absent key is omitted. Serialized as wire `isTerminal`. ([upstream PR #2129](https://github.com/github/copilot-sdk/pull/2129)) |
| `:metadata` | map | `nil` | Opaque host-defined metadata forwarded with the tool definition. An explicit `{}` is preserved; `nil` or an absent key is omitted |
| `:overrides-built-in-tool` | boolean | `nil` | Allow this definition to replace a built-in tool with the same name |

Use `:parameters` with `define-tool` or `:spec` with `define-tool-from-spec`.
Metadata is not interpreted. String keys preserve their exact spelling; keyword
keys follow the SDK's normal kebab-case to camelCase wire conversion.

The handler invocation map contains:

| Key | Type | Required | Description |
|-----|------|----------|-------------|
| `:session-id` | string | yes | Session identifier |
| `:tool-call-id` | string | yes | Tool call identifier |
| `:tool-name` | string | yes | Invoked tool name |
| `:arguments` | any | yes | Parsed arguments, without key conversion |
| `:cancel-chan` | core.async channel | yes | Closes when the handler finishes, the runtime reports the call complete, successful session teardown cancels the invocation, or the client connection closes unexpectedly |
| `:available-tools` | vector | no | Current tool metadata snapshot, provided only to the `tool_search_tool` handler |
| `:traceparent` | string | no | W3C trace parent |
| `:tracestate` | string | no | W3C trace state |

Each `:available-tools` entry contains:

| Key | Type | Required | Description |
|-----|------|----------|-------------|
| `:name` | string | yes | Tool name |
| `:description` | string | yes | Human-readable description |
| `:namespaced-name` | string | no | Fully qualified tool name |
| `:mcp-server-name` | string | no | MCP server name |
| `:mcp-tool-name` | string | no | MCP tool name |
| `:input-schema` | map | no | Tool input JSON Schema |
| `:defer-loading` | boolean | no | Whether the runtime deferred loading the tool |

Failure to fetch the current metadata snapshot does not fail the tool call. The
SDK invokes `tool_search_tool` without `:available-tools` instead.

Handlers that launch asynchronous work should stop it when `:cancel-chan`
closes. Completion and cancellation race atomically: once cancellation wins,
the SDK suppresses any late handler result or error. A failed `disconnect!`
does not cancel pending invocations because local session ownership is
preserved. Unexpected connection loss disconnects every local session and
cancels its pending invocations.

**Declaration-only tools (manual resolution):**

The `:handler` key is **optional** (upstream PR #1308). When omitted, the SDK does not auto-respond to tool calls — the call surfaces as a `:copilot/external_tool.requested` event with a pending request id, and the application resolves it later via `handle-pending-tool-call!`. Useful for human-in-the-loop UIs or out-of-process tool execution.

```clojure
(def manual-tool
  (copilot/define-tool "manual_lookup"
    {:description "Look up status manually"
     :parameters {:type "object"
                  :properties {:id {:type "string"}}
                  :required ["id"]}}))
;; …later, after a human reviews the request:
(copilot/handle-pending-tool-call! session
                                   {:request-id "tool-req-7"
                                    :result "STATUS_OK"})
```

**Overriding built-in tools:**

Set `:overrides-built-in-tool true` to override a built-in tool (e.g., `grep`, `edit_file`). Without this flag, defining a tool whose name clashes with a built-in tool causes an error.

```clojure
(def custom-grep
  (copilot/define-tool "grep"
    {:description "Custom grep with project-specific filtering"
     :overrides-built-in-tool true
     :parameters {:type "object"
                  :properties {:pattern {:type "string"
                                         :description "Search pattern"}}
                  :required ["pattern"]}
     :handler (fn [{:keys [pattern]} _invocation]
                (copilot/result-success (my-custom-grep pattern)))}))
```

**Deferring tools:**

Set `:defer` to `:auto` or `:never` (upstream PR #1632) to control whether a tool may be *deferred* — loaded lazily via tool search rather than always pre-loaded into the model's context. `:auto` (the default) lets the runtime defer the tool; `:never` forces it to be pre-loaded. Deferring large tool sets keeps the active context smaller.

```clojure
(def always-loaded
  (copilot/define-tool "critical_action"
    {:description "A tool that must always be available"
     :defer :never
     :parameters {:type "object" :properties {}}
     :handler (fn [_ _] (copilot/result-success "done"))}))
```

The keyword is converted to the wire string (`:auto` -> `"auto"`, `:never` -> `"never"`); when `:defer` is omitted the field is not sent and the runtime applies its default.

**Handler return values:**

| Return Type | Description |
|-------------|-------------|
| String | Automatically wrapped as success result |
| Map with `:result-type` | Full control over result metadata |
| core.async channel | Async result (yields string or map) |

Object tool results contain:

| Key | Type | Required | Description |
|-----|------|----------|-------------|
| `:text-result-for-llm` | string | yes | Text returned to the model |
| `:result-type` | keyword or string | yes | One of `success`, `failure`, `rejected`, `denied`, or `timeout` |
| `:binary-results-for-llm` | vector | no | Binary image or resource results |
| `:error` | string | no | Error details |
| `:session-log` | string | no | Session log text |
| `:tool-telemetry` | map | no | Map of string bucket names to JSON object maps. Nested values may be JSON strings, finite numbers, booleans, `nil`, vectors, or maps with string keys. Sets, symbols, functions, keyword keys, non-finite numbers, and non-object bucket values are invalid. |
| `:tool-references` | collection of strings | no | Tool names referenced by the result |

**Result helpers:**

```clojure
(copilot/result-success "It worked!")
(copilot/result-success
  "It worked!"
  {"metrics" {"match_count" 3
              "cached" false}})
(copilot/result-failure "It failed" "error details")
(copilot/result-denied "Permission denied")
(copilot/result-rejected "Invalid parameters")
```

**MCP result conversion:**

Convert an MCP `CallToolResult` into the SDK's `ToolResultObject` format with `convert-mcp-call-tool-result`:

```clojure
(require '[github.copilot-sdk.tools :as tools])

(tools/convert-mcp-call-tool-result
  {:content [{:type "text" :text "Hello from MCP"}]
   :is-error false})
;; => {:text-result-for-llm "Hello from MCP", :result-type "success"}

(tools/convert-mcp-call-tool-result
  {:content [{:type "text" :text "Something went wrong"}]
   :is-error true})
;; => {:text-result-for-llm "Something went wrong", :result-type "failure"}
```

The input map uses Clojure-idiomatic keys:

| Key | Type | Description |
|-----|------|-------------|
| `:content` | vector | Content blocks, each with `:type` and type-specific fields |
| `:is-error` | boolean | When true, the result-type is `"failure"` |

Supported content block types:

| Type | Fields | Description |
|------|--------|-------------|
| `"text"` | `:text` | Text content, joined with newlines |
| `"image"` | `:data`, `:mime-type` | Base64-encoded image, added to `:binary-results-for-llm` |
| `"resource"` | `:resource` with `:uri`, `:text`, `:blob`, `:mime-type` | Resource content (text and/or binary) |

### Commands

Register slash commands that users can invoke in the TUI. Define each command as a map with `:name`, optional `:description`, and `:command-handler`, then pass them via `:commands` in session config. Create, resume, and join send an omitted description as the empty string required by the runtime.

```clojure
(def my-commands
  [{:name "deploy"
    :description "Deploy the current project"
    :command-handler (fn [{:keys [session-id command-name args]}]
                       (println "Deploying with args:" args))}
   {:name "status"
    :description "Show project status"
    :command-handler (fn [{:keys [session-id command-name args]}]
                       (println "All systems operational"))}])

(def session (copilot/create-session client
               {:model "gpt-5.4"
                :commands my-commands
                :on-permission-request copilot/approve-all}))
```

**Command definition keys:**

| Key | Type | Required | Description |
|-----|------|----------|-------------|
| `:name` | string | yes | Command name (without leading slash) |
| `:description` | string | no | Description shown in TUI command list; defaults to `""` on the wire |
| `:command-handler` | fn | yes | Handler function |

The handler receives a context map:

| Key | Description |
|-----|-------------|
| `:session-id` | The session ID |
| `:command` | Full command string |
| `:command-name` | Matched command name |
| `:args` | Arguments after the command name |

The handler may return `nil` or a core.async channel (awaited automatically).

### System Message Customization

Control the system prompt:

```clojure
(def session (copilot/create-session client
               {:model "gpt-5.4"
                :on-permission-request copilot/approve-all
                :system-message
                  {:content "
<workflow_rules>
- Always check for security vulnerabilities
- Suggest performance improvements when applicable
</workflow_rules>
"}}))
```

The SDK auto-injects environment context, tool instructions, and security guardrails. Your `:content` is appended after SDK-managed sections.

For full control (removes all guardrails), use `:mode :replace`:

```clojure
(copilot/create-session client
  {:model "gpt-5.4"
   :on-permission-request copilot/approve-all
   :system-message {:mode :replace
                    :content "You are a helpful assistant."}})
```

#### Customize Mode

The `:customize` mode enables section-level overrides of the system prompt. Twelve sections are configurable:

| Section | Description |
|---------|-------------|
| `:preamble` | Agent identity preamble and mode statement (upstream PR #1713) |
| `:identity` | Section group covering the identity preamble and its sibling sub-sections (tone, tool efficiency, etc.) |
| `:tone` | Response style, conciseness rules, output formatting |
| `:tool-efficiency` | Tool usage patterns, parallel calling, batching |
| `:environment-context` | CWD, OS, git root, directory listing, available tools |
| `:code-change-rules` | Coding rules, linting/testing, ecosystem tools, style |
| `:guidelines` | Tips, behavioral best practices |
| `:safety` | Environment limitations, prohibited actions, security |
| `:tool-instructions` | Per-tool usage instructions |
| `:custom-instructions` | Repository and organization custom instructions |
| `:runtime-instructions` | Runtime-provided context (system notifications, memories, mode-specific instructions, content-exclusion policy) — added in upstream PR #1377 |
| `:last-instructions` | End-of-prompt instructions |

Each section supports static actions (`:replace`, `:remove`, `:append`, `:prepend`, `:preserve`) and transform callbacks (1-arity functions). `:preserve` is a no-op marker that opts an individually-addressable section out of a group-level `:remove` — e.g. keep `:tone` when removing the `:identity` group (upstream PR #1713).

```clojure
(require '[github.copilot-sdk :as copilot])

(def session
  (copilot/create-session client
    {:on-permission-request copilot/approve-all
     :system-message
     {:mode :customize
      :sections {:identity {:action :replace
                            :content "You are Acme Assistant."}
                 :tone {:action :append
                        :content "\nAlways respond in bullet points."}
                 :code-change-rules {:action :remove}}
      :content "Additional instructions here."}}))
```

Transform callbacks receive the current section content and return the replacement:

```clojure
(def session
  (copilot/create-session client
    {:on-permission-request copilot/approve-all
     :system-message
     {:mode :customize
      :sections {:identity {:action (fn [current]
                                     (clojure.string/replace current
                                       "GitHub Copilot" "Acme Assistant"))}}}}))
```

Inspect available sections with the `system-prompt-sections` constant:

```clojure
copilot/system-prompt-sections
;; => {:preamble {:description "Agent identity preamble and mode statement"}
;;     :identity {:description "Section group covering the identity preamble ..."}
;;     :tone {:description "Response style, conciseness rules, ..."}
;;     :runtime-instructions {:description "Runtime instructions injected ..."} ...}
```

Available section keys: `:preamble` (added in upstream PR #1713), `:identity`,
`:tone`, `:tool-efficiency`, `:environment-context`, `:code-change-rules`,
`:guidelines`, `:safety`, `:tool-instructions`, `:custom-instructions`,
`:runtime-instructions` (added in upstream PR #1377), `:last-instructions`.

> **Naming note** — Upstream renamed `SystemPromptSection` →
> `SystemMessageSection` in the TypeScript SDK. The Clojure SDK keeps
> `system-prompt-sections` as the canonical name (for back-compat) and
> exposes `system-message-sections` as an alias.

Unknown section keywords are allowed — they gracefully fall back to appending content to additional instructions.

### Default Agent Tool Exclusions

Hide tools from the built-in/default agent while keeping them available to custom agents with `:default-agent`.

```clojure
(require '[github.copilot-sdk :as copilot])

(def repo-index-tool
  (copilot/define-tool "repo_index_search"
    {:description "Search the private repository index"
     :parameters {:type "object"
                  :properties {:query {:type "string"}}
                  :required ["query"]}
     :handler (fn [{:keys [query]} _]
                (str "index results for " query))}))

(def session
  (copilot/create-session client
    {:on-permission-request copilot/approve-all
     :tools [repo-index-tool]
     :custom-agents [{:agent-name "repo-auditor"
                      :agent-prompt "Audit repository changes."
                      :agent-tools ["repo_index_search"]
                      :agent-reasoning-effort "high"}]
     :default-agent {:excluded-tools ["repo_index_search"]}}))
```

The default agent cannot call `repo_index_search`. The `repo-auditor` custom agent can still call it because custom-agent tool assignment is independent of `:default-agent`. Its reasoning effort is overridden to `"high"`; omit `:agent-reasoning-effort` to let the runtime resolve effort from the model configuration, inheriting the parent session's effort only when the agent uses the same model.

### Config Directory and Skills

`:config-directory` overrides where the CLI reads its config and state
(e.g., `~/.copilot`). The deprecated `:config-dir` alias remains accepted.
It does not define custom agents. Custom agents are provided via `:custom-agents`.

```clojure
(def session (copilot/create-session client
               {:model "gpt-5.4"
                :on-permission-request copilot/approve-all
                :config-directory "/tmp/copilot-config"
                :skill-directories ["/path/to/skills" "/opt/team-skills"]
                :disabled-skills ["legacy-skill" "experimental-skill"]}))
```

### Large Tool Output Handling (Experimental)

> **Note:** This is a CLI protocol feature not exposed in the official `@github/copilot-sdk`.
> The `outputDir` and `maxSizeBytes` settings may be ignored by some CLI versions due to
> a known issue where session-level config is not applied during `session.send` execution.
> The CLI's default behavior (30KB threshold, system tmpdir) applies regardless.

Configure how large tool outputs are handled before being sent back to the model:

```clojure
(def session (copilot/create-session client
               {:model "gpt-5.4"
                :on-permission-request copilot/approve-all
                :large-output {:enabled true
                               :max-size-bytes 65536
                               :output-dir "/tmp/copilot-tool-output"}}))
```

When a tool output exceeds the configured size, the CLI writes the full output to a temp file,
and the tool result delivered to the model contains a short message with the file path and preview.
You can see this message in `:tool.execution_complete` events:

```clojure
(let [events (copilot/subscribe-events session)]
  (go-loop []
    (when-let [event (<! events)]
        (when (= :tool.execution_complete (:type event))
          (when-let [content (get-in event [:data :result :content])]
            (println "Tool output message:\n" content)))
      (recur))))
```

Note: large output handling is applied by the CLI for built-in tools (like the shell tool).
For external tools you define in the SDK, consider handling oversized outputs yourself
(e.g., write to a file and return a short preview).

### Infinite Sessions

Infinite sessions enable automatic context compaction, allowing conversations to continue
beyond the model's context window limit. When the context approaches capacity, the CLI
automatically compacts older messages while preserving important context.

```clojure
;; Enable with defaults (enabled by default)
(def session (copilot/create-session client
               {:model "gpt-5.4"
                :on-permission-request copilot/approve-all}))

;; Explicit configuration
(def session (copilot/create-session client
               {:model "gpt-5.4"
                :on-permission-request copilot/approve-all
                :infinite-sessions {:enabled true
                                    :background-compaction-threshold 0.80
                                    :buffer-exhaustion-threshold 0.95}}))

;; Disable infinite sessions
(def session (copilot/create-session client
               {:model "gpt-5.4"
                :on-permission-request copilot/approve-all
                :infinite-sessions {:enabled false}}))
```

**Configuration options:**

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `:enabled` | boolean | `true` | Enable infinite sessions |
| `:background-compaction-threshold` | number | `0.80` | Context utilization (0.0-1.0) at which background compaction starts |
| `:buffer-exhaustion-threshold` | number | `0.95` | Context utilization (0.0-1.0) at which session blocks until compaction completes |

**How it works:**

1. When context reaches the background threshold (default 80%), compaction starts asynchronously
2. The session continues processing while compaction runs in the background
3. If context reaches the buffer exhaustion threshold (default 95%), the session blocks until compaction completes
4. Compaction preserves essential context while removing older, less relevant messages

**Compaction events:**

Sessions emit `:session.compaction_start` and `:session.compaction_complete` events during compaction:

```clojure
(let [ch (copilot/subscribe-events session)]
  (go-loop []
    (when-let [event (<! ch)]
      (case (:type event)
        :session.compaction_start
        (println "Compaction started...")

        :session.compaction_complete
        (println "Compaction complete")

        nil)
      (recur))))
```

### Agent Factories (Experimental)

> **Note:** Agent Factories are `@experimental` upstream
> ([upstream PR #2114](https://github.com/github/copilot-sdk/pull/2114)).
> The API may change in future releases. The upstream `v1.0.11` experimental
> `argsSchema` authoring addition is intentionally not exposed while this
> surface remains experimental.

An Agent Factory is an extension-authored, named workflow that a session can run: it
declares its own phases and optional limits, executes with reverse-RPC access to the
parent session (spawn nested agent turns, run journaled/idempotent steps, fan out
work in parallel or as a pipeline), and reports durable, resumable progress back to
the CLI.
Factories are registered per-session via [`join-session`](#join-session)'s
`:factories` option and approved via the [`:factory` permission kind](#permission-handling).

Most of this API is namespace-qualified only — require the namespace directly:

```clojure
(require '[github.copilot-sdk.factory :as factory]
         '[github.copilot-sdk.session :as session])
```

**Defining a factory**

```clojure
(def summarize-repo
  (factory/define-factory
    {:meta {:name "summarize-repo"
            :description "Summarize a repository's structure and recent activity"
            :phases [{:title "Scan" :detail "List top-level files and directories"}
                     {:title "Summarize"}]}
     :run (fn [{:keys [args phase log step agent parallel] session :session}]
            (phase "Scan")
            (let [files (step "list-files" #(session/workspace-list-files session))]
              (log (str "Found " (count (:files files)) " files"))
              (phase "Summarize")
              (let [summaries (parallel
                                (mapv (fn [f]
                                        #(agent (str "Summarize " f)))
                                      (:files files)))]
                {:file-count (count (:files files))
                 :summaries summaries})))}))
```

`define-factory` is also exposed as a top-level facade, `copilot/define-factory`
(synchronous only — no `<define-factory` twin, since validation does no I/O).

`:meta` is validated eagerly by `define-factory`:

| Field | Type | Required? | Notes |
|-------|------|-----------|-------|
| `:name` | string | yes | Non-blank. Must be unique within a `join-session` call's `:factories` vector. |
| `:description` | string | yes | Non-blank. |
| `:phases` | vector of maps | yes | Each phase is `{:title string :detail string (optional)}`. `:title` must be non-blank and unique across phases. |
| `:limits` | map (optional) | no | Validated only when present; see below. |

| Limit key | Type | Constraint |
|-----------|------|------------|
| `:max-concurrent-subagents` | positive integer | — |
| `:max-total-subagents` | positive integer | — |
| `:timeout-seconds` | positive finite number | ≤ `2147483.647` |
| `:max-ai-credits` | positive finite number | must round to a positive nano-AIU value |

Resource limits are optional. An omitted limit leaves that dimension unbounded,
except that an omitted `:max-concurrent-subagents` falls back to
`:max-total-subagents` when the latter is set. Set a ceiling only when the factory's
cost profile is known or the user explicitly requested one. Do not guess limits on a
user's behalf: an invented ceiling does not make a run safer and can stop healthy
work with `factory_limit_reached` after the run has already spent credits. Bound
broad fan-out with the factory's own workload counters instead.

Model-initiated `run_factory` requests still require permission and show the
effective limits. Direct SDK calls to `run-factory!` and `resume-factory!` do not
request permission, so callers are responsible for choosing any ceilings
deliberately.

`:run` must be a function of one argument, the [context map](#the-run-context-map)
described below, and returns (directly or via a core.async channel, `Future`, promise,
or delay) a JSON-safe value — `nil`, a string, boolean, finite number, or a nested
vector/map thereof. Return `factory/json-null` to report an explicit JSON `null`
result (as opposed to `nil`, which means "no result").

Other namespace-qualified-only helpers:

| Function | Description |
|----------|-------------|
| `factory/factory-handle?` | Return true when `value` is a handle created by `define-factory`. |
| `factory/factory-meta` | Return a handle's `:meta` map. |
| `factory/terminal-status?` | Return true when a run `:status` (keyword or string) is terminal (`:completed`, `:halted`, `:cancelled`, `:error`). Also exposed as `copilot/factory-terminal-status?`. |
| `factory/json-null` | Sentinel value for an explicit JSON `null` factory result. |

**Driving factory execution**

Register handles via `join-session`'s `:factories` option, then drive execution from any
client connected to that session (typically the parent CLI, but any joined client can
call these). Each function has a top-level `copilot` facade wrapper (all `^:experimental`)
and an async `<`-prefixed twin returning a core.async channel:

```clojure
(require '[github.copilot-sdk :as copilot])

;; By name (the factory must be registered on the joined session) or by handle:
(copilot/run-factory! session "summarize-repo" {:args {:path "."}})
;; => {:run-id "..." :status :completed :result {...} ...}

;; Async twin — channel yields the result, or the caught Throwable directly:
(let [ch (copilot/<run-factory! session "summarize-repo" {:args {:path "."}})]
  (let [result (<!! ch)]
    (if (instance? Throwable result)
      (throw result)
      result)))
```

| Sync function (`factory/...`) | Top-level facade | Wire method | Description |
|--------------------------------|-------------------|-------------|-------------|
| `run!` | `run-factory!` | `session.factory.run` | Start a factory by name or handle. 2-arity `[session name-or-handle]` or 3-arity with an options map: `:args` (passed to `:run` as `:args`), `:limits` (overrides declared limits, re-validated), `:resume-from-run-id` (delegates to `resume!`). Waits for terminal status via `wait-for-run!` if the initial response is non-terminal. |
| `resume!` | `resume-factory!` | `session.factory.resume` | Resume a durable, previously-started run by `run-id`. 2-arity `[session run-id]` or 3-arity with `{:limits ...}`. See [error classification](#resume-error-classification) below. |
| `get-run` | `get-factory-run` | `session.factory.getRun` | Read the latest durable envelope for a run. `[session run-id]`. |
| `wait-for-run!` | `wait-for-factory-run!` | (polls `get-run` + listens for `:copilot/factory.run_updated`) | Block until a run reaches a terminal status. 2-arity or 3-arity with `{:cancel-chan :poll-interval-ms}` (`:poll-interval-ms` default `5000`; `:cancel-chan` aborts the *wait*, not the run, throwing `ex-info` `{:type :factory-wait-cancelled :run-id ...}`). Requires the session to be connected (has an active event stream). |
| `list-runs` | `list-factory-runs` | `session.factory.listRuns` | List all durable runs for the session, in creation order. `[session]`. |
| `get-run-detail` | `get-factory-run-detail` | `session.factory.getRunDetail` | Read durable phases, agent turns, and recent progress for a run. `[session run-id]`. |
| `get-run-progress` | `get-factory-run-progress` | `session.factory.getRunProgress` | Page durable progress lines. 2-arity or 3-arity with an options map merged into the wire params (e.g. pagination cursors). |
| `cancel!` | `cancel-factory-run!` | `session.factory.cancel` | Request cancellation from the CLI. The runtime's reverse `factory.abort` request then marks active local executions cancelled and closes their `:cancel-chan`. Returns the resulting terminal envelope. `[session run-id]`. |

All of the above return a run envelope map with at least `:run-id` and a keywordized
`:status` (one of `:running`, `:completed`, `:halted`, `:cancelled`, `:error`, or other
non-terminal statuses reported by the CLI).

**Async twins**: every function above has a `<`-prefixed twin (e.g. `factory/<run!`,
`copilot/<run-factory!`) with the same arities, running the call on a thread pool and
returning a core.async channel. The channel yields the successful result **or the
caught `Throwable` directly** (not wrapped) — always check `(instance? Throwable result)`
before using the value.

#### Resume error classification

`resume!`/`<resume!`/`resume-factory!`/`<resume-factory!` reclassify specific wire
error codes into a stable `ex-info` shape: `{:type :factory-resume-error :code <kebab-keyword>}`
(original exception preserved as the cause). Other errors are rethrown unchanged.

| Wire code | `:code` |
|-----------|---------|
| `"not_found"` | `:not-found` |
| `"non_resumable"` | `:non-resumable` |
| `"already_active"` | `:already-active` |
| `"reapproval_declined"` | `:reapproval-declined` |
| `"no_approval_provider"` | `:no-approval-provider` |

```clojure
(try
  (factory/resume! session run-id)
  (catch clojure.lang.ExceptionInfo e
    (case (:code (ex-data e))
      :reapproval-declined (println "Approver declined re-approval")
      :non-resumable (println "This run cannot be resumed")
      (throw e))))
```

#### The `:run` context map

Every factory's `:run` function receives a single context map, built fresh per
execution:

| Key | Type | Description |
|-----|------|-------------|
| `:run-id` | string | The durable run's ID. |
| `:args` | JSON value | The original `run!` arguments (default `{}`), persisted across `resume!`. |
| `:session` | `CopilotSession` | The session the factory is running within. |
| `:cancel-chan` | channel | Closes when the run is cancelled/aborted. |
| `:cancelled?` | `(fn [])` | Returns true once the run has been cancelled/aborted. |
| `:agent` | `(fn [prompt] ...)` / `(fn [prompt options] ...)` | Runs a nested agent turn and returns its result. `options` may include `:label`, `:schema`, `:model`. |
| `:step` | `(fn [key producer] ...)` / `(fn [key producer options] ...)` | Runs `producer` (a 0-arg fn) once per unique `key` and journals the JSON-safe result for replay on resume; a repeated `key` returns the cached result without re-running `producer`. Pass `{:volatile? true}` to skip journaling/caching entirely. |
| `:parallel` | `(fn [thunks] ...)` | Runs a vector of 0-arg thunks concurrently (bounded at 4096 items) and returns their results in order; a non-fatal thunk error yields `nil` in its slot, a fatal error (abort, or an inner `:agent`/`:step` RPC failure) rethrows. |
| `:pipeline` | `(fn [items & stages] ...)` | Threads each item in `items` (bounded at 4096) sequentially through `stages`, each called as `(stage previous-value original-item index)`; same fatal/non-fatal error semantics as `:parallel`. |
| `:phase` | `(fn [text] ...)` | Buffers a phase-transition progress line; the SDK flushes before `:agent`/`:step` calls and when the run finishes. |
| `:log` | `(fn [text] ...)` | Buffers a log progress line; the SDK flushes before `:agent`/`:step` calls and when the run finishes. |

`:agent`, `:step`, `:parallel`, and `:pipeline` all throw `ex-info` with
`{:type :factory-aborted :run-id ...}` if the run is cancelled while they're executing.
Values returned from `:step` producers and from `:run` itself are validated as JSON-safe
before being journaled or returned; a non-JSON-safe value throws `ex-info` with
`{:value-type "..."}`.

### Permission Handling

The SDK uses a **deny-by-default** permission model. All permission requests
(file writes, shell commands, URL fetches, custom tool execution, etc.) are denied unless your
session config provides an `:on-permission-request` handler. The handler is
optional on `create-session`, `resume-session`, and `join-session` (upstream
PR #1308): when omitted, permission requests are not auto-resolved and
applications must resolve them via `handle-pending-permission-request!`.
`join-session` historically defaulted to `{:kind :no-result}` and continues to
behave that way.

Use `approve-all` to opt into approving everything:

```clojure
(def session (copilot/create-session client
               {:on-permission-request copilot/approve-all}))
```

The `:permission-kind` field in permission requests identifies the type of action requiring approval:

| Permission Kind | Description |
|----------------|-------------|
| `:shell` | Shell command execution |
| `:write` | File system write operation |
| `:mcp` | MCP tool invocation |
| `:read` | File system read operation |
| `:url` | URL fetch / HTTP request |
| `:custom-tool` | SDK-registered custom tool invocation |
| `:memory` | Memory storage operation (subject, fact, citations) |
| `:hook` | Hook-triggered permission check |
| `:extension-management` | Install, enable, disable, or manage an extension |
| `:extension-permission-access` | Extension access to another permission surface |
| `:extension-env-access` | Extension request for named environment variables |
| `:factory` | Agent Factory run or authoring approval (see [Agent Factories (Experimental)](#agent-factories-experimental)) |

Custom-tool permission requests may include boolean `:skip-permission`, recording
that the tool declaration asked the runtime to bypass its normal prompt.

Memory permission events include additional data fields:

| Field | Type | Description |
|-------|------|-------------|
| `:memory-action` | `:store` or `:vote` | The memory operation type |
| `:memory-direction` | `:upvote` or `:downvote` | Vote direction (when action is `:vote`) |
| `:memory-reason` | string | Reason for the memory operation |
| `:repo-nwo` | non-blank string | Repository scope in `"owner/name"` form, when applicable |
| `:scope` | `"repository"` or `"user"` | Storage scope requested by the memory operation |

Extension environment access requests require both the extension identity and
at least one requested name:

| Field | Type | Description |
|-------|------|-------------|
| `:extension-name` | non-blank string | Extension asking for access |
| `:environment-variables` | non-empty vector of non-blank strings | Environment variable names presented for approval |

Factory permission requests (`:permission-kind :factory`) include additional data fields describing the run or authoring request awaiting approval ([upstream PR #2114](https://github.com/github/copilot-sdk/pull/2114)):

| Field | Type | Description |
|-------|------|-------------|
| `:operation` | `"run"` or `"author"` | Whether the factory is being run or authored |
| `:name` | string | Factory name |
| `:description` | string | Factory description |
| `:phases` | vector of maps | `{:title string, :detail string (optional)}` — the phases the factory declares |
| `:approval-key` | string | Stable key for persisting an approval decision across runs |
| `:can-persist-approval` | boolean | Whether `:approve-for-session`/`:approve-for-location` are meaningful for this request |
| `:max-concurrent-subagents`, `:max-total-subagents`, `:timeout-seconds`, `:max-ai-credits` | number (optional) | Effective runtime limits for this run |
| `:declared-max-concurrent-subagents`, `:declared-max-total-subagents`, `:declared-timeout-seconds`, `:declared-max-ai-credits` | number (optional) | The limits as declared by the factory definition, shown to approvers alongside the effective limits above |

For fine-grained control, provide your own handler. When the CLI needs
approval, it sends a JSON-RPC `permission.request` to the SDK. Your
`:on-permission-request` callback must return a map compatible with the
permission result payload; the SDK wraps this into the JSON-RPC response
as `{:result <your-map>}`:

Your handler receives two arguments: the `request` map (the permission
request payload) and a `ctx` map with:

| Key | Type | Description |
|-----|------|-------------|
| `:session-id` | string | The session the request originated from |
| `:managed-settings-enabled?` | boolean | `true` when the session has enterprise-managed settings active (`:managed-settings` config or `:enable-managed-settings?`); `false` otherwise |

`ctx` is passed to every `:on-permission-request` handler, including
`approve-all` and `default-join-session-permission-handler`.
([upstream PR #2139](https://github.com/github/copilot-sdk/pull/2139))

The `permission_bash.clj` example demonstrates both an allowed and a denied
shell command and prints the full permission request payload so you can inspect
fields like `:full-command-text`, `:commands`, and `:possible-paths`.

```clojure
;; Approve this request once
{:kind :approve-once}

;; Approve and remember for the session
{:kind :approve-for-session
 :approval {:kind :commands
            :command-identifiers ["echo"]}}

;; Approve and persist for the project location
{:kind :approve-for-location
 :approval {:kind :write}
 :location-key "/path/to/project"}

;; Reject with optional user-facing detail
{:kind :reject
 :feedback "Not allowed"}

;; No user confirmation is available
{:kind :user-not-available}

;; Extension declines to answer (another handler may respond)
{:kind :no-result}
```

`:managed-approval-handled` is runtime-owned metadata on approved
`:copilot/permission.completed` event results. It is not a valid outbound
permission decision and permission handlers must not return it.

Legacy Clojure permission result kinds such as `:approved` and
`:denied-by-rules` remain accepted and are normalized before the SDK sends the
decision to the CLI.

#### `resolvedByHook` — Hook-Resolved Permissions

When the runtime resolves a permission request via a `permissionRequest` hook, the
`permission.requested` event includes `:resolved-by-hook true`. The SDK automatically
skips the client's `:on-permission-request` handler and does not send the
`handlePendingPermissionRequest` RPC — the event is still published to event subscribers
for observability.

#### `attributed-permission-result`

```clojure
(require '[github.copilot-sdk :as copilot])

(defn permission-handler [_request _ctx]
  (copilot/attributed-permission-result
   {:kind :approve-once}
   {:outcome :auto-approved
    :source :host-policy
    :surface :sdk}))

(copilot/attributed-permission-result?
 (permission-handler nil nil))
;; => true
```

Attach informational context describing how and where a permission decision was
made. Reapplying `attributed-permission-result` replaces the previous context
rather than nesting it. Permission behavior is determined only by the inner
result. These helpers are experimental, matching their upstream annotation.

| Context key | Allowed values |
|-------------|----------------|
| `:outcome` | `:auto-approved`, `:autopilot-denied`, `:prompted-user` |
| `:source` | `:assisted-approval`, `:human-response`, `:host-policy`, `:unattended-fallback` |
| `:surface` | `:tui`, `:prompt-mode`, `:copilot-app`, `:sdk`, `:acp` |

The SDK converts these keywords to the runtime's snake-case strings and sends
`decisionContext` beside `result`. Plain permission decisions omit
`decisionContext` entirely. An attributed `{:kind :no-result}` still suppresses
the response RPC. Handlers may return attributed results directly or through a
core.async channel.

The context may also include the experimental `:response-capability`, one of
`:interactive`, `:headless`, or `:none`. The SDK serializes the keyword as the
exact wire string and omits the field when absent. This field is excluded from
stable SDK parity certification and may change independently of stable APIs.
([upstream PR #2294](https://github.com/github/copilot-sdk/pull/2294))

#### `approve-all`

```clojure
(copilot/approve-all request ctx)
```

A convenience permission handler that approves all permission requests by
returning `{:kind :approve-once}`. Equivalent to the upstream Node.js SDK
`approveAll` export.

**Fail-closed under managed settings**: when `ctx` reports
`:managed-settings-enabled? true` (see `:managed-settings` /
`:enable-managed-settings?` in the session-config table), `approve-all`
*throws* `ex-info` with message `"approve-all cannot be used when managed
settings are enabled"` and `{:session-id (:session-id ctx)}` — host-side
auto-approval must not bypass managed policy. When the request itself sets
`:managed-approval-required true` (or omits `false`), `approve-all` returns
`{:kind :no-result}` instead of approving, deferring the decision to managed
approval. Otherwise it returns `{:kind :approve-once}` as before.
([upstream PR #2139](https://github.com/github/copilot-sdk/pull/2139))

Pass as the `:on-permission-request` value in session config:

```clojure
(copilot/create-session client {:on-permission-request copilot/approve-all})
```

#### `default-join-session-permission-handler`

```clojure
(copilot/default-join-session-permission-handler request ctx)
```

Returns `{:kind :no-result}` — the CLI handles permission decisions itself. When used with `resume-session`, the SDK sends `requestPermission: false` on the wire, telling the CLI that this client does not want to handle permission requests.

Use this when reconnecting to a session where the original client already established permission handling:

```clojure
(copilot/resume-session client "session-123"
  {:on-permission-request copilot/default-join-session-permission-handler})
```

Equivalent to the upstream Node.js SDK `defaultJoinSessionPermissionHandler` export.

### User Input Handling

When the agent needs input from the user (via `ask_user` tool), the `:on-user-input-request`
handler is called. Return a response map with the user's input:

Use `:ask-user-variant :legacy` for this legacy question shape or
`:ask-user-variant :elicitation` for structured elicitation. Omitting the
option preserves the runtime default. The elicitation variant is handled by
`:on-elicitation-request`, not by the legacy user-input callback.

```clojure
(def session (copilot/create-session client
               {:model "gpt-5.4"
                :on-permission-request copilot/approve-all
                :on-user-input-request
                (fn [request invocation]
                  ;; request contains {:question "..." :choices [...] :allow-freeform true/false}
                  (println "Agent asks:" (:question request))
                  (when-let [choices (:choices request)]
                    (println "Choices:" choices))
                  ;; Return user's response
                  ;; :answer is required, :was-freeform defaults to true
                  {:answer (read-line)
                   :was-freeform true})}))
```

The request map includes:
- `:question` - The question being asked
- `:choices` - Optional list of choices for multiple choice questions
- `:allow-freeform` - Whether freeform text input is allowed

The response map should include:
- `:answer` - The user's answer (string, required). `:response` is also accepted for convenience.
- `:was-freeform` - Whether the answer was freeform (boolean, defaults to true)

### Elicitation Provider

Provide a handler for elicitation requests from the agent. This enables the SDK client to act as a UI provider for form-based dialogs.

```clojure
(require '[github.copilot-sdk :as copilot])

(def session
  (copilot/create-session client
    {:on-permission-request copilot/approve-all
     :on-elicitation-request
     (fn [{:keys [session-id message requested-schema mode]}]
       (println "Elicitation for session" session-id ":" message)
       {:action "accept"
        :content {:name "user-input"}})}))
```

The handler receives a single `ElicitationContext` map:

| Key | Type | Description |
|-----|------|-------------|
| `:session-id` | string | Session that triggered the request |
| `:message` | string | What information is needed from the user |
| `:requested-schema` | map | JSON Schema describing form fields (optional) |
| `:mode` | string | `"form"` for structured input, `"url"` for browser redirect (optional) |
| `:elicitation-source` | string | Source that initiated the request, e.g. MCP server name (optional) |
| `:url` | string | URL to open in browser, url mode only (optional) |

Return an `ElicitationResult` map:

| Key | Type | Description |
|-----|------|-------------|
| `:action` | string | `"accept"`, `"decline"`, or `"cancel"` |
| `:content` | map | Optional field values for `"accept"`. Omit for `"decline"` or `"cancel"`; absent content is omitted on the wire, never serialized as JSON `null`. |

If the handler throws, the SDK sends `{:action "cancel"}` to prevent the request from hanging.

When `:on-elicitation-request` is set, the session advertises `requestElicitation=true` in the create/resume RPC. Capabilities are updated dynamically via `capabilities.changed` events.

### Session Filesystem

Virtualize per-session storage with custom filesystem handlers. The runtime routes all session-scoped file I/O (event logs, large outputs, checkpoints) through the provided callbacks.

Configure the client with `:session-fs`:

```clojure
(require '[github.copilot-sdk :as copilot])

(def client
  (copilot/client {:session-fs {:initial-cwd "/home/user/project"
                                :session-state-path "/sessions"
                                :conventions "posix"}}))
```

Provide a provider factory per session:

```clojure
(def session
  (copilot/create-session client
    {:on-permission-request copilot/approve-all
     :create-session-fs-handler
     (fn [_session]
       (let [store (atom {})]
         {:read-file (fn [path]
                       (or (get @store path)
                           (throw (ex-info "missing file" {:code "ENOENT"}))))
          :write-file (fn [path content _mode]
                        (swap! store assoc path content))
          :append-file (fn [path content _mode]
                         (swap! store update path str content))
          :exists (fn [path]
                    (contains? @store path))
          :stat (fn [path]
                  {:is-file true
                   :is-directory false
                   :size (count (get @store path ""))
                   :mtime "2026-01-01T00:00:00Z"
                   :birthtime "2026-01-01T00:00:00Z"})
          :mkdir (fn [_path _recursive _mode] nil)
          :readdir (fn [_path] [])
          :readdir-with-types (fn [_path] [])
          :rm (fn [path _recursive _force]
                (swap! store dissoc path))
          :rename (fn [src dest]
                    (swap! store
                      (fn [s]
                        (-> s
                            (assoc dest (get s src ""))
                            (dissoc src)))))}))}))
```

Provider functions use direct arguments and throw on failure. Errors with `{:code "ENOENT"}` become structured `SessionFsError` maps with code `"ENOENT"`; all other exceptions become `"UNKNOWN"`. `create-session` and `resume-session` automatically adapt provider-style factory returns to the low-level RPC handler contract.

Use `create-session-fs-adapter` when you need the low-level handler map explicitly:

```clojure
(require '[clojure.java.io :as io])

(def provider
  {:read-file slurp
   :write-file (fn [path content _mode] (spit path content))
   :append-file (fn [path content _mode] (spit path content :append true))
   :exists (fn [path] (.exists (io/file path)))
   :stat (fn [path] {:is-file (.isFile (io/file path))
                     :is-directory (.isDirectory (io/file path))
                     :size (.length (io/file path))})
   :mkdir (fn [path _recursive _mode] (.mkdirs (io/file path)))
   :readdir (fn [path] (vec (.list (io/file path))))
   :readdir-with-types (fn [_path] [])
   :rm (fn [path _recursive _force] (clojure.java.io/delete-file path true))
   :rename (fn [src dest] (.renameTo (io/file src) (io/file dest)))})

(def handler
  (copilot/create-session-fs-adapter provider))
```

The low-level handler map requires the 10 core FS operations below. The three
`:sqlite-*` keys are optional and only required when the client advertises
`:capabilities {:sqlite true}` on its `:session-fs` config (see
[SQLite support](#sqlite-support-optional)).

| Key | Params | Returns |
|-----|--------|---------|
| `:read-file` | `{:session-id :path}` | `{:content "..."}` |
| `:write-file` | `{:session-id :path :content :mode}` | nil |
| `:append-file` | `{:session-id :path :content :mode}` | nil |
| `:exists` | `{:session-id :path}` | `{:exists true/false}` |
| `:stat` | `{:session-id :path}` | `{:is-file :is-directory :size :mtime :birthtime}` |
| `:mkdir` | `{:session-id :path :recursive :mode}` | nil |
| `:readdir` | `{:session-id :path}` | `{:entries [...]}` |
| `:readdir-with-types` | `{:session-id :path}` | `{:entries [...]}` |
| `:rm` | `{:session-id :path :recursive :force}` | nil |
| `:rename` | `{:session-id :src :dest}` | nil |
| `:sqlite-query` _(optional)_ | `{:session-id :query-type :query :params}` | `{:rows [...] :columns [...] :rows-affected n}` |
| `:sqlite-exists` _(optional)_ | `{:session-id}` | `{:exists true/false}` |
| `:sqlite-transaction` _(optional)_ | `{:session-id :statements [...]}` | `{:results [...]}` or `{:results [] :error {:error-class ... :message ...}}` ([upstream PR #2140](https://github.com/github/copilot-sdk/pull/2140)) |

Handler functions may return values directly or via core.async channels.

#### SQLite support (optional)

To handle `sessionFs.sqliteQuery` and `sessionFs.sqliteExists` (upstream PR #1299),
add a nested `:sqlite` map to the provider and advertise the capability on the
client config:

```clojure
(def client
  (copilot/client {:session-fs {:initial-cwd "/home/user/project"
                                :session-state-path "/sessions"
                                :conventions "posix"
                                :capabilities {:sqlite true}}}))

(def session
  (copilot/create-session client
    {:on-permission-request copilot/approve-all
     :create-session-fs-handler
     (fn [_session]
       {;; ... all 10 fs operations above ...
        :sqlite {:query (fn [query-type sql params]
                          ;; query-type is one of :exec, :query, :run
                          ;; params is the raw bind-parameter map (keys preserved verbatim, e.g. :$userId)
                          {:rows [{:n 1}] :columns ["n"] :rows-affected 0})
                 :exists (fn [] true)
                 :transaction (fn [statements]
                                ;; statements is a vector of
                                ;; {:query-type :query :query :params} maps to
                                ;; run atomically; return one result map per statement.
                                ;; Throw github.copilot-sdk.session/session-fs-sqlite-transaction-failure
                                ;; to report a classified failure (see below).
                                (mapv (fn [_stmt] {:rows [] :columns [] :rows-affected 1})
                                      statements))}})}))
```

`:transaction` is optional — a provider that omits it responds to
`sessionFs.sqliteTransaction` with `{:results [] :error {:error-class "fatal"
:message "SQLite transactions are not supported by this provider"}}`.

Use `session-fs-sqlite-transaction-failure` to report a classified failure from
inside a `:transaction` handler, and `session-fs-sqlite-transaction-failure?` to
recognize one:

```clojure
(require '[github.copilot-sdk.session :as session])

(session/session-fs-sqlite-transaction-failure
  "database is locked" :busy-or-locked)
;; => an ex-info the handler can throw; ex-data has
;;    {:type :session-fs-sqlite-transaction-failure :error-class :busy-or-locked}

(session/session-fs-sqlite-transaction-failure? some-exception) ;; => true/false
```

`error-class` is one of `:busy-or-locked`, `:post-commit-ambiguous`, or the
default `:fatal` (single-arity call). Classified failures are wire-mapped to
`"busyOrLocked"`, `"postCommitAmbiguous"`, and `"fatal"` respectively; any
uncaught exception thrown from `:transaction` is also reported as `"fatal"`
with its message. ([upstream PR #2140](https://github.com/github/copilot-sdk/pull/2140))

Notes:

- `:capabilities {:sqlite true}` is required when sqlite is advertised; declaring
  it without supplying `:sqlite` in the provider throws at session creation.
- SQL bind-parameter map keys (e.g. `$userId`) bypass kebab-case conversion and
  arrive at the handler verbatim.
- Result row column-name keys (e.g. `:user_id`, `:created_at`) round-trip
  verbatim on the outgoing wire path — they are not converted to camelCase,
  matching upstream Node.js semantics where provider rows are forwarded
  untouched.
- SQLite handler exceptions propagate as JSON-RPC errors (not wrapped as
  `SessionFsError`).

### Session Hooks

Lifecycle hooks allow custom logic at various points during the session:

```clojure
(def session (copilot/create-session client
               {:model "gpt-5.4"
                :on-permission-request copilot/approve-all
                :hooks
                {:on-pre-tool-use
                 (fn [input invocation]
                   ;; Called before each tool execution
                   ;; input contains {:tool-name "..." :arguments {...}}
                   (println "About to use tool:" (:tool-name input))
                   ;; Return nil to proceed, or a modified input map
                   nil)

                 :on-post-tool-use
                 (fn [input invocation]
                   ;; Called after each *successful* tool execution
                   ;; input contains {:tool-name "..." :tool-args {...} :tool-result {...}}
                   ;; For failed tool calls, register :on-post-tool-use-failure below.
                   (println "Tool completed:" (:tool-name input))
                   nil)

                 :on-post-tool-use-failure
                 (fn [input invocation]
                   ;; Called after a tool execution whose result was `"failure"`
                   ;; (upstream PR #1421). :on-post-tool-use only fires for
                   ;; successful results, so register this handler to observe
                   ;; failed tool outcomes. Note: `"rejected"`, `"denied"`, and
                   ;; `"timeout"` results do NOT currently trigger this hook —
                   ;; only `"failure"` does.
                   ;; input contains {:tool-name "..." :tool-args {...}
                   ;;                 :error "failure message string"
                   ;;                 :session-id "..." :timestamp 12345}
                   ;; Optional return: {:additional-context "..."} is appended as
                   ;; hidden guidance to the model alongside the failed result.
                   ;; Other fields (e.g. :modified-result, :suppress-output) are
                   ;; not honored for failure hooks.
                   (println "Tool failed:" (:tool-name input) (:error input))
                   {:additional-context "Tip: try `ls` first to see available files."})

                 :on-pre-mcp-tool-call
                 (fn [input invocation]
                   ;; Called before each MCP tool call is dispatched (upstream PR #1366).
                   ;; input contains
                   ;;   {:server-name "..." :tool-name "..." :arguments {...}
                   ;;    :tool-call-id "..." :_meta {...} :session-id "..."
                   ;;    :timestamp 12345}
                   ;; :arguments and :_meta are opaque MCP payloads and are
                   ;; passed through verbatim (NOT kebab-cased recursively).
                   ;; Return nil/{} to preserve the existing _meta on the
                   ;; outgoing MCP request, {:meta-to-use {...}} to replace
                   ;; it, or {:meta-to-use nil} to remove it.
                   (println "Pre-MCP call:" (:server-name input) (:tool-name input))
                   {:meta-to-use {:traceId "my-trace-id"}})

                 :on-user-prompt-submitted
                 (fn [input invocation]
                   ;; Called when user sends a prompt
                   (println "User prompt:" (:prompt input))
                   nil)

                 :on-user-prompt-transformed
                 (fn [input invocation]
                   ;; Called after the runtime transforms a submitted prompt
                   ;; (e.g. slash-command/skill expansion) but before it is sent
                   ;; to the model (upstream PR #2254).
                   ;; input contains {:prompt "..." :transformed-prompt "..."
                   ;;                 :timestamp 12345 :cwd "..."}
                   ;; Return {:modified-transformed-prompt "..."} (wire
                   ;; modifiedTransformedPrompt) to further rewrite the prompt
                   ;; actually sent to the model, or nil to leave it as-is.
                   (println "Transformed prompt:" (:transformed-prompt input))
                   nil)

                 :on-session-start
                 (fn [input invocation]
                   (println "Session started")
                   nil)

                 :on-session-end
                 (fn [input invocation]
                   (println "Session ended")
                   nil)

                 :on-agent-stop
                 (fn [{:keys [stop-hook-active]} _invocation]
                   (when-not stop-hook-active
                     {:decision "block"
                      :reason "Run the final validation and fix any failures."}))

                 :on-error-occurred
                 (fn [input invocation]
                   (println "Error:" (:error input))
                   nil)}}))
```

All hooks receive an `input` map (contents vary by hook type) and an `invocation` map
containing `{:session-id ...}`. Hooks may return `nil` to proceed normally, or in some
cases return a modified value.

`:on-agent-stop` fires when the top-level agent reaches a natural terminal stop. Its
input contains base `:timestamp` (Unix milliseconds) and `:cwd` (string), SDK-added
`:session-id`, and optional kebab-cased `:stop-reason`, `:transcript-path`, and
`:stop-hook-active`; its invocation map is `{:session-id ...}`. Return
`{:decision "block" :reason "..."}` to keep the agent running and enqueue the reason.
Return `nil`, or throw from the handler, to let the agent stop. When
`:stop-hook-active` is true, a previous block already forced a continuation; use it to
avoid indefinite re-blocking.
([upstream PR #2054](https://github.com/github/copilot-sdk/pull/2054))

### Reasoning Effort

For models that support reasoning (like o1), you can control the reasoning effort level:

```clojure
;; Check model capabilities
(let [models (copilot/list-models client)]
  (doseq [m models
          :when (:supports-reasoning-effort m)]
    (println (:name m) "supports reasoning:"
             (:supported-reasoning-efforts m)
             "default:" (:default-reasoning-effort m))))

;; Create session with reasoning effort
(def session (copilot/create-session client
               {:model "o1"
                :reasoning-effort "high"
                :on-permission-request copilot/approve-all})) ; "low", "medium", "high", "xhigh", or "max"
```

### Multiple Sessions

```clojure
(def session1 (copilot/create-session client {:model "gpt-5.4"
                                              :on-permission-request copilot/approve-all}))
(def session2 (copilot/create-session client {:model "claude-sonnet-4.5"
                                              :on-permission-request copilot/approve-all}))

;; Both sessions are independent
(copilot/send-and-wait! session1 {:prompt "Hello from session 1"})
(copilot/send-and-wait! session2 {:prompt "Hello from session 2"})
```

### File Attachments

```clojure
;; File attachment
(copilot/send! session
  {:prompt "Analyze this file"
   :attachments [{:type :file
                  :path "/path/to/file.clj"
                  :display-name "My File"}]})

;; File attachment with line range (restrict to lines 10-25)
(copilot/send! session
  {:prompt "Explain this section"
   :attachments [{:type :file
                  :path "/path/to/file.clj"
                  :line-range {:start 10 :end 25}}]})

;; Selection attachment (code range)
(copilot/send! session
  {:prompt "What does this function do?"
   :attachments [{:type :selection
                  :file-path "/path/to/file.clj"
                  :display-name "my-function"
                  :selection-range {:start {:line 10 :character 0}
                                   :end {:line 25 :character 0}}
                  :text "(defn my-function [...] ...)"}]})
```

### Blob Attachments

Send inline base64-encoded data (e.g. images) without writing to disk:

```clojure
;; Blob attachment (inline base64 data)
(copilot/send! session
  {:prompt "Describe this image"
   :attachments [{:type :blob
                  :data "iVBORw0KGgoAAAANSUhEUg..."
                  :mime-type "image/png"
                  :display-name "screenshot.png"}]})
```

### Connecting to External Server

```clojure
;; Connect to an existing CLI server (no process spawned)
(def client (copilot/client {:cli-url "localhost:8080"}))
(copilot/start! client)
```

---

## Error Handling

```clojure
(try
  (let [session (copilot/create-session client
                                       {:on-permission-request copilot/approve-all})]
    (copilot/send! session {:prompt "Hello"}))
  (catch Exception e
    (println "Error:" (ex-message e))))
```
