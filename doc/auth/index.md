# Authentication

The Copilot SDK for Clojure supports multiple authentication methods. Choose the method that best matches your deployment scenario.

## Authentication Methods

| Method | Use Case | Copilot Subscription Required |
|--------|----------|-------------------------------|
| [GitHub Signed-in User](#github-signed-in-user) | Interactive apps where users sign in with GitHub | Yes |
| [OAuth GitHub App](#oauth-github-app) | Apps acting on behalf of users via OAuth | Yes |
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

1. **Explicit `:github-token`** — Token passed directly to client constructor
2. **HMAC key** — `CAPI_HMAC_KEY` or `COPILOT_HMAC_KEY` environment variables
3. **Direct API token** — `GITHUB_COPILOT_API_TOKEN` with `COPILOT_API_URL`
4. **Environment variable tokens** — `COPILOT_GITHUB_TOKEN` → `GH_TOKEN` → `GITHUB_TOKEN`
5. **Stored OAuth credentials** — From previous `copilot` CLI login
6. **GitHub CLI** — `gh auth` credentials

## Disabling Auto-Login

To prevent the SDK from automatically using stored credentials or `gh` CLI auth:

```clojure
(copilot/with-client [client {:use-logged-in-user? false}]
  ;; Only uses explicit tokens (github-token or env vars)
  )
```

## Next Steps

- [BYOK Documentation](./byok.md) — Use your own API keys
- [Getting Started Guide](../getting-started.md) — Build your first Copilot-powered app
- [MCP Servers](../mcp/overview.md) — Connect to external tools
