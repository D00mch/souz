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
  "summarization": {"model": "tv-agent"},
  "routing": {
    "simple": {"model": "test-chat"},
    "complex": {"model": "tv-agent"}
  }
}
```

All configured model names are advertised in `/v1/bootstrap`; no per-user allowlist is required. Assign a name with trusted `PATCH /v1/me/settings`, for example `{"defaultModel":"test-chat"}`. HTTP `options.model` and WebSocket `payload.meta.model` accept the same names. Settings and execution records persist the name. Unknown or removed names fail explicitly. When no built-in model is accessible, the first model in this file is the default for new users and the fallback for unavailable built-in selections.

`GET /health` reports the server default using the same selection policy, without user settings or user-managed keys.

`subagentModels` and `summarization` are optional. A child without an explicit model inherits the parent's named selection. Dedicated summarization uses its own connection and preserves `OPENAI_SUMMARIZATION_PARAMETERS`. Existing `OPENAI_*`, summarization model/connection/parameter settings and `SUBAGENT_MODELS_JSON` remain usable when no named override is configured.

`CONTEXT_SIZE` is the single server context window for conversation, subagents and summaries (default `125000`). Startup rejects values that are not integers of at least `24000` tokens. All configured models must support that window; no model-specific effective window is calculated. Users and HTTP request options cannot override it, and continuations use the running server value.

Prepared conversation and summary input must fit 80% of `CONTEXT_SIZE`, including instructions and tool schemas. Compaction starts when input exceeds that limit; exactly 80% fits. One summary call includes the full history before the last user message and the summarization prompt. If that request cannot fit, history cannot be compacted, or the rebuilt conversation still exceeds 80%, the conversation is rejected. Empty and length-truncated summaries are rejected. System instructions and the active user/tool exchange stay intact.

`maxTokens` only limits provider output and does not reduce the input budget: conversation uses 4,096 tokens, summaries 1,024, and classification 512. `OPENAI_SUMMARIZATION_PARAMETERS` still controls other provider parameters; the explicit request output limit takes precedence.

Names are case-sensitive, nonblank, trimmed and must not collide with built-in selectors, legacy subagent IDs or the trimmed `OPENAI_MODEL` value. These collisions reject startup; shared upstream `modelId` values remain valid. Invalid URLs (including ports outside 1–65535), unknown fields, duplicate JSON keys, missing secrets and unresolved references reject startup. Only environment variable names belong in the JSON file.

Backend settings and execution records carry public model names as strings; the shared agent carries the same name. The backend resolves it to the exact upstream model ID immediately before a chat or streaming call, and caches adapters by provider and named model. Different connections can serve identical IDs without sharing credentials. Named connections always use their server key, independently of a user's legacy OpenAI key. Embeddings retain their existing provider configuration.

`routing` is optional and enables complexity-aware execution routing. Turn classification uses `JEV_TOKEN`; unavailable or failed classification selects the complex target, retains recall and sends initial waiting. Each target names a configured or built-in model other than `openai-compatible-custom`. Both targets must support `CONTEXT_SIZE`: a 32k/128k pair needs a common value such as `32000`. A new turn is routed only when it runs on the server default model: no model in the request and no different `defaultModel` in the user's settings, and both targets selectable for the user. Before memory and Skill preparation, Jev independently decides complexity and recall; the agent's sequence is described in [model routing](../../agent/docs/execution-lifecycle.md#model-routing). The chosen model replaces the execution's model when the turn commits, so an option continuation resumes on it. `execution.started` still reports the model the turn started with.

Verify with `./gradlew :backend:test`. Existing configuration, request-routing and backend E2E tests cover malformed configuration, identical upstream IDs, independent keys/endpoints, streaming, user selections and child calls.
