# Authentication

The Copilot SDK for Clojure supports multiple authentication methods. Choose the method that best matches your deployment scenario.

## Authentication Methods

| Method | Use Case | Copilot Subscription Required |
|--------|----------|-------------------------------|
| [GitHub Signed-in User](#github-signed-in-user) | Interactive apps where users sign in with GitHub | Yes |
| [OAuth GitHub App](#oauth-github-app) | Apps acting on behalf of users via OAuth | Yes |
| [Session-scoped Token Provider](#session-scoped-token-provider) | Multi-user or long-lived sessions whose user tokens must refresh | Yes |
| [Environment Variables](#environment-variables) | CI/CD, automation, server-to-server | Yes |
| [BYOK (Bring Your Own Key)](./byok.md) | Using your own API keys (Azure AI Foundry, OpenAI, etc.) | No |

## GitHub Signed-in User

This is the default when running the Copilot CLI interactively. Users authenticate via GitHub OAuth device flow, and the SDK uses their stored credentials.

**How it works:**
1. User runs `copilot` CLI and signs in via GitHub OAuth
2. Credentials are stored securely in the system keychain
3. SDK automatically uses stored credentials

```clojure
(require '[github.copilot-sdk :as copilot])

;; Default: uses logged-in user credentials
(copilot/with-client [client {}]
  ;; ...
  )
```

**When to use:**
- Desktop applications
- Development and testing environments
- Any scenario where a user can sign in interactively

## OAuth GitHub App

Use an OAuth GitHub App to authenticate users through your application. This enables Copilot API requests on behalf of users who authorize your app.

**How it works:**
1. User authorizes your OAuth GitHub App
2. Your app receives a user access token (`gho_` or `ghu_` prefix)
3. Pass the token to the SDK via `:github-token`

```clojure
(require '[github.copilot-sdk :as copilot])

(copilot/with-client [client {:github-token user-access-token}]
  ;; :use-logged-in-user? automatically defaults to false
  ;; when :github-token is provided
  )
```

Pass a GitHub token in session config when one client manages sessions for
different users:

```clojure
(require '[github.copilot-sdk :as copilot])

(copilot/with-client [client {}]
  (def alice-session
    (copilot/create-session client
      {:github-token alice-access-token
       :on-permission-request copilot/approve-all}))

  (def bob-session
    (copilot/create-session client
      {:github-token bob-access-token
       :on-permission-request copilot/approve-all}))

  (copilot/disconnect! alice-session)
  (copilot/disconnect! bob-session))
```

Session-level `:github-token` is sent only with `session.create` or
`session.resume`. It does not change the client's process environment or
default authentication for other sessions.

**Supported token types:**
- `gho_` — OAuth user access tokens
- `ghu_` — GitHub App user access tokens
- `github_pat_` — Fine-grained personal access tokens

**Not supported:**
- `ghp_` — Classic personal access tokens (deprecated)

**When to use:**
- Web applications where users sign in via GitHub
- SaaS applications building on top of Copilot
- Multi-user applications making requests on behalf of different users

## Session-scoped Token Provider

Use `:github-token-provider` when a session may outlive its current GitHub user
access token. The callback is local to that session and can refresh credentials
without restarting the client:

```clojure
(require '[github.copilot-sdk :as copilot]
         '[clojure.data.json :as json])
(import '[java.net URI]
        '[java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers])

(def http-client (HttpClient/newHttpClient))

(defn token-provider
  [{:keys [host session-id]}]
  ;; Replace this URI with your own token-issuance endpoint; it must return
  ;; a JSON body with "access_token" and "expires_in".
  (let [uri (URI/create (str "https://auth.example.com/copilot-tokens"
                              "?host=" host "&sessionId=" session-id))
        request (-> (HttpRequest/newBuilder uri) (.GET) (.build))
        response (.send http-client request (HttpResponse$BodyHandlers/ofString))
        {:strs [access_token expires_in]} (json/read-str (.body response))]
    {:kind :token
     :access-token access_token
     :expires-in expires_in
     :token-type "Bearer"}))

(copilot/with-client [client {}]
  (let [session (copilot/create-session
                  client
                  {:github-token-provider token-provider
                   :on-permission-request copilot/approve-all})]
    (try
      (copilot/send-and-wait! session {:prompt "Summarize this repository"})
      (finally
        (copilot/disconnect! session)))))
```

The callback receives:

| Key | Value |
|-----|-------|
| `:host` | GitHub host requesting the credential |
| `:session-id` | Session ID when known; omitted during an initial cloud-session registration if the server assigns the ID |
| `:reason` | `:initial` for the first credential or `:refresh` when the runtime requests renewal |

Return `{:kind :token :access-token ... :expires-in ...}` with an integer expiry
of at least 3,601 seconds and optional `:token-type`, or return
`{:kind :cancelled}`. Either result may be returned directly or through a
core.async channel.

`:github-token-provider` and session `:github-token` are mutually exclusive.
Create, resume, and join requests carry only an opaque registration ID in session
configuration. The callback remains inside the SDK process; when the runtime
requests a credential, its result crosses the JSON-RPC connection to the CLI.
`:github-token-provider` therefore requires a transport the SDK trusts: the
SDK-managed child-process stdio transport, or an external `:cli-url` whose
resolved host is syntactically loopback (`localhost`, `127.0.0.1`, `::1`).
Any other `:cli-url` host is rejected outright at connect time — an `https://`
scheme on `:cli-url` is parsed for host/port only and does not make a remote
endpoint TLS-protected in the SDK's eyes. To reach a CLI on another host,
terminate a protected tunnel (SSH port-forward, VPN, etc.) locally so
`:cli-url` itself resolves to loopback; the SDK never treats a remote address
as safe for `:github-token-provider`. Failed create/resume/join calls roll
back provisional registrations, a successful resume replaces the session's
previous provider, and disconnect, delete, or client stop removes the
registration.
([upstream PR #2412](https://github.com/github/copilot-sdk/pull/2412))

## Environment Variables

For automation, CI/CD, and server-to-server scenarios, authenticate using environment variables.

**Supported environment variables (in priority order):**
1. `COPILOT_GITHUB_TOKEN` — Recommended for explicit Copilot usage
2. `GH_TOKEN` — GitHub CLI compatible
3. `GITHUB_TOKEN` — GitHub Actions compatible

No code changes needed — the SDK automatically detects environment variables:

```clojure
(require '[github.copilot-sdk :as copilot])

;; Token is read from environment variable automatically
(copilot/with-client [client {}]
  ;; ...
  )
```

You can also pass environment variables explicitly:

```clojure
(copilot/with-client [client {:env {"COPILOT_GITHUB_TOKEN" my-token}}]
  ;; ...
  )
```

**When to use:**
- CI/CD pipelines (GitHub Actions, Jenkins, etc.)
- Automated testing
- Server-side applications with service accounts

## BYOK (Bring Your Own Key)

BYOK allows you to use your own API keys from model providers like Azure AI Foundry, OpenAI, or Anthropic. This bypasses GitHub Copilot authentication entirely.

**Key benefits:**
- No GitHub Copilot subscription required
- Use enterprise model deployments
- Direct billing with your model provider
- Support for Azure AI Foundry, OpenAI, Anthropic, and OpenAI-compatible endpoints

See the [BYOK documentation](./byok.md) for complete details.

## Authentication Priority

When multiple authentication methods are available, the CLI uses them in this priority order:

1. **Session credential** — `:github-token-provider` or `:github-token` passed in `create-session` or `resume-session` config for that session
2. **Client `:github-token`** — Token passed directly to the client constructor
3. **HMAC key** — `CAPI_HMAC_KEY` or `COPILOT_HMAC_KEY` environment variables
4. **Direct API token** — `GITHUB_COPILOT_API_TOKEN` with `COPILOT_API_URL`
5. **Environment variable tokens** — `COPILOT_GITHUB_TOKEN` → `GH_TOKEN` → `GITHUB_TOKEN`
6. **Stored OAuth credentials** — From previous `copilot` CLI login
7. **GitHub CLI** — `gh auth` credentials

## Disabling Auto-Login

To prevent the SDK from automatically using stored credentials or `gh` CLI auth:

```clojure
(copilot/with-client [client {:use-logged-in-user? false}]
  ;; Only uses explicit tokens (github-token or env vars)
  )
```

## Next Steps

- [BYOK Documentation](./byok.md) — Use your own API keys
- [Azure Managed Identity](./azure-managed-identity.md) — Azure BYOK without static API keys
- [Getting Started Guide](../getting-started.md) — Build your first Copilot-powered app
- [MCP Servers](../mcp/overview.md) — Connect to external tools
