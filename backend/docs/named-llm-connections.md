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
  "summarization": {"model": "tv-agent", "contextSize": 32000}
}
```

All configured model names are advertised in `/v1/bootstrap`; no per-user allowlist is required. Assign a name with trusted `PATCH /v1/me/settings`, for example `{"defaultModel":"test-chat"}`. HTTP `options.model` and WebSocket `payload.meta.model` accept the same names. Settings and execution records persist the name. Unknown or removed names fail explicitly.

`subagentModels` and `summarization` are optional. A child without an explicit model inherits the parent's named selection. Dedicated summarization uses its own connection and preserves `OPENAI_SUMMARIZATION_PARAMETERS`; its optional `contextSize` controls the summary context window. Existing `OPENAI_*`, `OPENAI_SUMMARIZATION_*` and `SUBAGENT_MODELS_JSON` settings remain usable when no named override is configured.

Names are case-sensitive, nonblank, trimmed and must not collide with built-in selectors or legacy subagent IDs. Invalid URLs, unknown fields, duplicate JSON keys, missing secrets and unresolved references reject startup. Only environment variable names belong in the JSON file.

The shared agent carries the model name. The backend resolves it to the exact upstream model ID immediately before a chat or streaming call, and caches adapters by provider and connection. Different connections can serve identical IDs without sharing credentials. Named connections always use their server key, independently of a user's legacy OpenAI key. Embeddings retain their existing provider configuration.

Verify with `./gradlew :backend:test :llms:test :sharedLogic:jvmTest`. Existing configuration, request-routing and backend E2E tests cover malformed configuration, identical upstream IDs, independent keys/endpoints, streaming, user selections and child calls.
