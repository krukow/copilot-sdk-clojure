# BYOK (Bring Your Own Key)

BYOK allows you to use the Copilot SDK with your own API keys from model providers, bypassing GitHub Copilot authentication. This is useful for enterprise deployments, custom model hosting, or when you want direct billing with your model provider.

## Supported Providers

| Provider | `:provider-type` | Notes |
|----------|------------------|-------|
| OpenAI | `:openai` | OpenAI API and OpenAI-compatible endpoints |
| Azure OpenAI / Azure AI Foundry | `:azure` | Azure-hosted models |
| Anthropic | `:anthropic` | Claude models |
| Ollama | `:openai` | Local models via OpenAI-compatible API |
| Other OpenAI-compatible | `:openai` | vLLM, LiteLLM, etc. |

## Quick Start: Azure AI Foundry

```clojure
(require '[krukow.copilot-sdk :as copilot])
(require '[krukow.copilot-sdk.helpers :as h])

(copilot/with-client-session [session
                              {:model "gpt-5.2-codex"
                               :provider {:provider-type :openai
                                          :base-url "https://your-resource.openai.azure.com/openai/v1/"
                                          :wire-api :responses
                                          :api-key (System/getenv "FOUNDRY_API_KEY")}}]
  (println (h/query "What is 2+2?" :session session)))
```

## Quick Start: OpenAI Direct

```clojure
(copilot/with-client-session [session
                              {:model "gpt-5.2"
                               :provider {:provider-type :openai
                                          :base-url "https://api.openai.com/v1"
                                          :api-key (System/getenv "OPENAI_API_KEY")}}]
  (println (h/query "Hello!" :session session)))
```

## Quick Start: Ollama (Local)

```clojure
;; No API key needed for local Ollama
(copilot/with-client-session [session
                              {:model "llama3"
                               :provider {:provider-type :openai
                                          :base-url "http://localhost:11434/v1"}}]
  (println (h/query "Hello!" :session session)))
```

## Quick Start: Anthropic

```clojure
(copilot/with-client-session [session
                              {:model "claude-sonnet-4"
                               :provider {:provider-type :anthropic
                                          :base-url "https://api.anthropic.com"
                                          :api-key (System/getenv "ANTHROPIC_API_KEY")}}]
  (println (h/query "Hello!" :session session)))
```

## Provider Configuration Reference

### `:provider` Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `:base-url` | string | **Yes** | API endpoint URL |
| `:provider-type` | keyword | No | `:openai`, `:azure`, or `:anthropic` (default: `:openai`) |
| `:wire-api` | keyword | No | `:completions` or `:responses` (default: `:completions`) |
| `:api-key` | string | No | API key (optional for local providers like Ollama) |
| `:bearer-token` | string | No | Bearer token auth (takes precedence over `:api-key`) |
| `:azure-options` | map | No | Azure-specific options (see below) |

### Azure Options

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `:azure-api-version` | string | `"2024-10-21"` | Azure API version |

### Wire API Format

The `:wire-api` setting determines which OpenAI API format to use:

- **`:completions`** (default) — Chat Completions API (`/chat/completions`). Use for most models.
- **`:responses`** — Responses API. Use for GPT-5 series models that support the newer responses format.

### Provider-Type Notes

**`:openai`** — Works with OpenAI API and any OpenAI-compatible endpoint. `:base-url` should include the full path (e.g., `"https://api.openai.com/v1"`).

**`:azure`** — For native Azure OpenAI endpoints. `:base-url` should be just the host (e.g., `"https://my-resource.openai.azure.com"`). Do NOT include `/openai/v1/` in the URL — the SDK handles path construction.

**`:anthropic`** — For direct Anthropic API access. Uses Claude-specific API format.

## Example Configurations

### Azure OpenAI (Native Azure Endpoint)

Use `:azure` type for endpoints at `*.openai.azure.com`:

```clojure
{:model "gpt-5.2"
 :provider {:provider-type :azure
            :base-url "https://my-resource.openai.azure.com"
            :api-key (System/getenv "AZURE_OPENAI_KEY")
            :azure-options {:azure-api-version "2024-10-21"}}}
```

### Azure AI Foundry (OpenAI-Compatible Endpoint)

For Azure AI Foundry deployments with `/openai/v1/` endpoints, use `:openai`:

```clojure
{:model "gpt-5.2-codex"
 :provider {:provider-type :openai
            :base-url "https://your-resource.openai.azure.com/openai/v1/"
            :api-key (System/getenv "FOUNDRY_API_KEY")
            :wire-api :responses}}
```

### Bearer Token Authentication

Some providers require bearer token authentication instead of API keys:

```clojure
{:model "my-model"
 :provider {:provider-type :openai
            :base-url "https://my-custom-endpoint.example.com/v1"
            :bearer-token (System/getenv "MY_BEARER_TOKEN")}}
```

## Limitations

### Identity Limitations

BYOK authentication is **key-based only**. The following are NOT supported:

- ❌ Microsoft Entra ID (Azure AD) managed identities or service principals
- ❌ Third-party identity providers (OIDC, SAML, etc.)
- ❌ Azure Managed Identity

You must use an API key or bearer token that you manage yourself.

### Feature Limitations

- **Model availability** — Only models supported by your provider
- **Rate limiting** — Subject to your provider's rate limits, not Copilot's
- **Usage tracking** — Tracked by your provider, not GitHub Copilot
- **Premium requests** — Do not count against Copilot premium request quotas

## Troubleshooting

### "Model not specified" Error

When using BYOK, the `:model` parameter is **required**:

```clojure
;; ❌ Error: Model required with custom provider
{:provider {:provider-type :openai :base-url "..."}}

;; ✅ Correct: Model specified
{:model "gpt-5.2"
 :provider {:provider-type :openai :base-url "..."}}
```

### Azure Endpoint Type Confusion

For Azure OpenAI endpoints (`*.openai.azure.com`), use the correct type:

```clojure
;; ❌ Wrong: Using :openai type with native Azure endpoint
{:provider {:provider-type :openai
            :base-url "https://my-resource.openai.azure.com"}}

;; ✅ Correct: Using :azure type
{:provider {:provider-type :azure
            :base-url "https://my-resource.openai.azure.com"}}
```

However, if your Azure AI Foundry deployment provides an OpenAI-compatible endpoint path (e.g., `/openai/v1/`), use `:openai`:

```clojure
;; ✅ Correct: OpenAI-compatible Azure AI Foundry endpoint
{:provider {:provider-type :openai
            :base-url "https://your-resource.openai.azure.com/openai/v1/"}}
```

### Connection Refused (Ollama)

Ensure Ollama is running and accessible:

```bash
# Check Ollama is running
curl http://localhost:11434/v1/models

# Start Ollama if not running
ollama serve
```

### Authentication Failed

1. Verify your API key is correct and not expired
2. Check the `:base-url` matches your provider's expected format
3. For bearer tokens, ensure the full token is provided (not just a prefix)

## Next Steps

- [Authentication Overview](./index.md) — All authentication methods
- [Getting Started Guide](../getting-started.md) — Build your first Copilot-powered app
