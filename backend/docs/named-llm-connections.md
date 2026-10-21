# Named OpenAI-compatible connections

Set `SOUZ_LLM_CONFIG_FILE` to a JSON file readable by the backend, and supply the referenced API keys through server environment variables. Configuration is loaded once at startup; restart after edits.

```json
{
  "openaiConnections": {
    "router": {"baseUrl": "https://openrouter.ai/api/v1", "apiKeyEnv": "ROUTER_API_KEY"},
    "test": {"baseUrl": "https://llm.example.com/v1", "apiKeyEnv": "TEST_API_KEY"}
  },
  "models": {
    "test-chat": {"connection": "test", "modelId": "custom-model"},
    "tv-agent": {"connection": "router", "modelId": "provider/model"}
  },
  "subagentModels": ["tv-agent"],
  "summarization": {"model": "tv-agent"}
}
```

All configured model names are advertised in `/v1/bootstrap`; no per-user allowlist is required. Assign a name with trusted `PATCH /v1/me/settings`, for example `{"defaultModel":"test-chat"}`. HTTP `options.model` and WebSocket `payload.meta.model` accept the same names. Settings and execution records persist the name. Unknown or removed names fail explicitly. When no built-in or local model is accessible, the first model in this file is the default for new users and the fallback for unavailable built-in selections.

`GET /health` reports the server default using the same selection policy, without user settings or user-managed keys.

`subagentModels` and `summarization` are optional. A child without an explicit model inherits the parent's named selection. Dedicated summarization uses its own connection and preserves `OPENAI_SUMMARIZATION_PARAMETERS`. Existing `OPENAI_*`, summarization model/connection/parameter settings and `SUBAGENT_MODELS_JSON` remain usable when no named override is configured.

`CONTEXT_SIZE` is the single server context window for conversation, subagents and summaries (default `125000`). It must be a positive integer and is validated at startup. All configured models must support that window. Users and HTTP request options cannot override it, and continuations use the running server value.

Conversation requests reserve 4,096 output tokens and a 512-token safety margin. Summaries reserve 1,024 output tokens and the same margin; classification uses a 512-token output limit. Compaction starts at 80% of the context window, or earlier if the output reservation requires it. One summary request includes the full history before the last user message; an oversized summary request leaves history unchanged. `OPENAI_SUMMARIZATION_PARAMETERS` still controls other provider parameters; the explicit request output limit takes precedence.

Names are case-sensitive, nonblank, trimmed and must not collide with built-in selectors, legacy subagent IDs or the trimmed `OPENAI_MODEL` value. These collisions reject startup; shared upstream `modelId` values remain valid. Invalid URLs (including ports outside 1–65535), unknown fields, duplicate JSON keys, missing secrets and unresolved references reject startup. Only environment variable names belong in the JSON file.

Backend settings and execution records carry public model names as strings; the shared agent carries the same name. The backend resolves it to the exact upstream model ID immediately before a chat or streaming call, and caches adapters by provider and named model. Different connections can serve identical IDs without sharing credentials. Named connections always use their server key, independently of a user's legacy OpenAI key. Embeddings retain their existing provider configuration.

Verify with `./gradlew :backend:test`. Existing configuration, request-routing and backend E2E tests cover malformed configuration, identical upstream IDs, independent keys/endpoints, streaming, user selections and child calls.
