# Named OpenAI-compatible connections

Set `SOUZ_LLM_CONFIG_FILE` to a JSON file readable by the backend, and supply the referenced API keys through server environment variables. Configuration is loaded once at startup; restart after edits.

```json
{
  "openaiConnections": {
    "router": {"baseUrl": "https://openrouter.ai/api/v1", "apiKeyEnv": "ROUTER_API_KEY"},
    "test": {"baseUrl": "https://llm.example.com/v1", "apiKeyEnv": "TEST_API_KEY"}
  },
  "models": {
    "test-chat": {"connection": "test", "modelId": "custom-model", "parameters": {"thinking": {"type": "disabled"}}},
    "tv-agent": {"connection": "router", "modelId": "provider/model"}
  },
  "reasoningEfforts": {"gpt-5-mini": "low"},
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

`subagentModels` and `summarization` are optional. A child without an explicit model inherits the parent's named selection. Dedicated summarization uses its own connection and shallow-merges `OPENAI_SUMMARIZATION_PARAMETERS` with the selected model's `parameters`, with model values winning. Existing `OPENAI_*`, summarization model/connection/parameter settings and `SUBAGENT_MODELS_JSON` remain usable when no named override is configured.

Named model `parameters` are optional OpenAI-compatible body defaults scoped to the connection and model, including DeepSeek `thinking`. Supply a JSON object and leave agent-controlled fields to the runtime. See the [config parser](../src/main/kotlin/ru/souz/backend/config/NamedLlmConfig.kt) for reserved fields and startup validation.

The optional `reasoningEfforts` map supplies nonblank effort strings for built-in OpenAI and Codex models. Keys accept built-in selectors, normalize to aliases, and reject duplicates after normalization; custom selectors and other providers are rejected. `models` contains only named connections. Explicit request effort overrides configured effort; omitted defaults stay unset. Both streaming and ordinary calls use these defaults, including child calls. Providers validate supported effort values.

`CONTEXT_SIZE` is the single server context window for conversation, subagents and summaries (default `125000`). Startup rejects values that are not integers of at least `24000` tokens. All configured models must support that window; no model-specific effective window is calculated. Users and HTTP request options cannot override it, and continuations use the running server value.

The [agent context-budget invariant](../../agent/docs/skills-oriented-graph.md#invariant) defines input limits, compaction, and conversation and summary output caps. Classification caps output at 512 tokens. The explicit summary output limit overrides any output limit in `OPENAI_SUMMARIZATION_PARAMETERS`.

Use unique, case-sensitive names distinct from built-in selectors, legacy subagent IDs and `OPENAI_MODEL`. Keep secrets in the environment. Different connections can serve identical upstream IDs with separate server keys. A user's legacy OpenAI key cannot override a named connection. Embeddings keep their own provider configuration. [Request routing](../src/main/kotlin/ru/souz/backend/llm/BackendExecutionLlmChatApi.kt)

`routing` is optional and enables complexity-aware execution routing; it requires `JEV_TOKEN`, otherwise startup fails. Each target names a configured or built-in model other than `openai-compatible-custom`. Both targets must support `CONTEXT_SIZE`: a 32k/128k pair needs a common value such as `32000`. A new turn is routed only when it runs on the server default model: no model in the request and no different `defaultModel` in the user's settings, and both targets selectable for the user. Jev decides whether the turn is simple or complex; the agent's sequence is described in [model routing](../../agent/docs/execution-lifecycle.md#model-routing). The chosen model replaces the execution's model when the turn commits, so an option continuation resumes on it. `execution.started` still reports the model the turn started with.

Run `./gradlew :backend:test` with Docker. See [request routing tests](../src/test/kotlin/ru/souz/backend/llm/BackendExecutionLlmChatApiTest.kt) and the [default-model routing workflow](../src/test/kotlin/ru/souz/backend/e2e/BackendModelRoutingE2eTest.kt).
