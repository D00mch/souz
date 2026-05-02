# Backend

The `:backend` module is a JVM HTTP build for Souz without Compose UI startup, voice capture, speech recognition, hotkeys, or desktop-only agent tools.

It reuses shared runtime components from `:runtime` and exposes a small REST surface:

- `GET /health` returns process and selected-model status.
- `GET /v1/bootstrap` returns trusted-proxy bootstrap metadata for web/server-mode clients.
- `GET /v1/me/settings` and `PATCH /v1/me/settings` expose effective per-user backend settings and persist user intent for the public settings subset.
- `GET /v1/chats`, `POST /v1/chats`, `GET /v1/chats/{chatId}/messages`, `GET /v1/chats/{chatId}/events`, `POST /v1/chats/{chatId}/messages`, `POST /v1/options/{optionId}/answer`, `POST /v1/chats/{chatId}/cancel-active`, `POST /v1/chats/{chatId}/executions/{executionId}/cancel`, and `WS /v1/chats/{chatId}/ws` provide the stage-8 chat-oriented REST/WebSocket API with strict ownership checks, explicit `AgentExecution` + `Option` lifecycle persistence, replay/live event delivery, same-execution continuation after an option answer, and cancellation.
- `POST /agent` remains a legacy/debug internal route that accepts authenticated agent requests and returns a single assistant response with message IDs and token usage.

`/agent` exposes the shared runtime tool catalog for backend-safe tools (files, text/web lookup, calculator, analytics, and non-UI config). The backend intentionally omits `WebImageSearch` so startup does not initialize Apache Tika's external parser probes for host binaries such as `ffmpeg`.

`/v1/**` never accepts `userId` from body/query. Identity is accepted only from trusted proxy headers:

- `X-User-Id` as an opaque non-blank string.
- `X-Souz-Proxy-Auth` matched against `SOUZ_BACKEND_PROXY_TOKEN` or `souz.backend.proxyToken`.

If the proxy token is missing, `/v1/**` rejects requests with a structured `backend_misconfigured` error instead of falling back to an unsafe mode.

`POST /agent` requires `Content-Type: application/json`, `Authorization: Bearer <internal-agent-token>`, and `X-Request-Id: <uuid>`. The token is read from `SOUZ_BACKEND_AGENT_TOKEN` or `souz.backend.agentToken`. The request body `requestId` must match `X-Request-Id`.

The backend keeps `/agent` conversation snapshots through the legacy `AgentSessionRepository`, now backed by the stage-2 product `AgentStateRepository`. Stage-8/10 reuses the same shared runtime for `/v1/chats/{chatId}/messages`, but chat turns resolve effective settings from server defaults + persisted per-user settings + request overrides, persist visible `messages` separately from `agent state`, use `conversationId = chatId.toString()` for runtime identity, and keep synthetic option-continuation input inside agent state only. Product repositories now support in-memory storage, filesystem storage under `SOUZ_BACKEND_DATA_DIR` / `souz.backend.dataDir` (default `data/` relative to the backend process working directory), and Postgres storage via JDBC + HikariCP + Flyway with `SOUZ_BACKEND_DB_*` / `souz.backend.db.*` settings. Memory mode keeps bounded LRU snapshots (10_000 entities per repository) so an accidental prod launch in `memory` mode is less likely to OOM. Filesystem mode stores `settings.json`, `chat.json`, `messages.jsonl`, `agent-state.json`, `executions.jsonl`, `options.jsonl`, `events.jsonl`, and `tool-calls.jsonl`, uses a stable URL-safe encoded directory name for opaque `userId` values instead of raw path injection, keeps `messages/executions/options/tool-calls` as append-only snapshot logs with last-write-wins reload, and tolerates corrupted `agent-state.json` by returning `null` while leaving product message history readable. Postgres mode keeps DB ownership filtering by `userId`, allocates message/event `seq` per chat, updates assistant messages in place without changing `seq`, enforces one active execution per chat through `agent_executions_one_active_per_chat_idx`, persists durable replay only when `SOUZ_FEATURE_DURABLE_EVENT_REPLAY=true`, stores `tool_calls` audit rows with redacted/truncated argument/result/error previews only, and uses `row_version` optimistic locking so stale state writes fail as `state_conflict` instead of overwriting `context_json`. `/agent` still executes each turn from a request-scoped runtime:

- Process scope: shared settings/provider clients, shared runtime tool catalog/filter, backend no-op desktop/MCP host adapters, object mappers, and runtime/cache factories.
- Conversation scope: persisted snapshot only, including history, active agent id, temperature, locale, and time zone.
- Request scope: validated request data, model/context/locale/time-zone overrides for the turn, usage tracking reset, and response assembly.

The backend path still reuses the shared `:agent` execution kernel, but it bypasses desktop-only features that are irrelevant here: `AgentFacade`, graph session logging, shared desktop side-effect subscriptions, MCP tool discovery, and desktop/session logging infrastructure. Stage-5 adds a request-scoped runtime event sink instead, so backend event persistence never relies on shared desktop flows.

Stage-1/9 backend foundation also adds:

- feature flags from env/system properties:
  - `SOUZ_FEATURE_WS_EVENTS` / `souz.backend.feature.wsEvents`
  - `SOUZ_FEATURE_STREAMING_MESSAGES` / `souz.backend.feature.streamingMessages`
  - `SOUZ_FEATURE_TOOL_EVENTS` / `souz.backend.feature.toolEvents`
  - `SOUZ_FEATURE_OPTIONS` / `souz.backend.feature.options`
  - `SOUZ_FEATURE_DURABLE_EVENT_REPLAY` / `souz.backend.feature.durableEventReplay`
- storage mode from `SOUZ_STORAGE_MODE` / `souz.backend.storageMode`, with `memory`, `filesystem`, and `postgres` supported.
- filesystem data root from `SOUZ_BACKEND_DATA_DIR` / `souz.backend.dataDir`, defaulting to `data/` relative to the backend process working directory.
- postgres config from env/system properties:
  - `SOUZ_BACKEND_DB_HOST` / `souz.backend.db.host`, default `127.0.0.1`
  - `SOUZ_BACKEND_DB_PORT` / `souz.backend.db.port`, default `5432`
  - `SOUZ_BACKEND_DB_NAME` / `souz.backend.db.name`, default `souz`
  - `SOUZ_BACKEND_DB_USER` / `souz.backend.db.user`, default `souz`
  - `SOUZ_BACKEND_DB_PASSWORD` / `souz.backend.db.password`, optional
  - `SOUZ_BACKEND_DB_SCHEMA` / `souz.backend.db.schema`, default `public`
  - `SOUZ_BACKEND_DB_MAX_POOL_SIZE` / `souz.backend.db.maxPoolSize`, default `10`
  - `SOUZ_BACKEND_DB_CONNECTION_TIMEOUT_MS` / `souz.backend.db.connectionTimeoutMs`, default `30000`

## Project Structure

```text
backend/
├── build.gradle.kts                            # JVM application build and Ktor server dependencies
├── AGENTS.md                                   # Module notes, routes, and structure
└── src/
    ├── main/
    │   └── kotlin/
    │       └── ru/souz/backend/
    │           ├── app/                        # Entry point, runtime lifecycle, backend DI, process config
    │           ├── http/                       # Ktor server wrapper, routes, DTOs, and v1 error envelopes
    │           ├── agent/                      # Legacy /agent feature internals
    │           ├── bootstrap/                  # /v1/bootstrap response assembly from current backend state
    │           ├── chat/                       # Product chat/message models, repositories, and stage-3 services
    │           ├── execution/                  # Product execution models and repositories
    │           ├── options/                    # Product option models and repositories
    │           ├── events/                     # Product event models and repositories
    │           ├── toolcall/                  # Tool call audit models and repositories
    │           ├── settings/                   # Per-user settings models, repository, effective resolver, and settings service
    │           ├── config/                     # Feature-flag and env/property config readers
    │           ├── security/                   # Trusted proxy request identity extraction for /v1/**
    │           ├── storage/                    # Storage mode enum, stage gating, and memory/filesystem repository impls
    │           └── common/                     # Shared backend exception types
    └── test/
        └── kotlin/
            └── ru/souz/backend/
                ├── BackendAgentServiceTest.kt
                ├── config/BackendFeatureFlagsTest.kt
                └── http/BackendBootstrapRouteTest.kt
```

## Bootstrap Route

`GET /v1/bootstrap` requires both trusted headers above and returns:

- `user.id` from `X-User-Id`
- `features` from backend feature flags
- `storage.mode`
- `capabilities.models` derived from the current `SettingsProvider` + `LlmBuildProfile` semantics, with `serverManagedKey` indicating whether the backend currently has provider access
- `capabilities.tools` from the backend-safe runtime tool catalog only
- `settings` from per-user backend settings resolved as server defaults + persisted user settings + backend feature gating, with safe locale/time-zone/model fallbacks

Errors for `/v1/**` use a structured envelope:

```json
{
  "error": {
    "code": "untrusted_proxy",
    "message": "Trusted proxy authentication is required."
  }
}
```

Stable backend codes:

- `untrusted_proxy`
- `missing_user_identity`
- `backend_misconfigured`
- `internal_error`
- `invalid_request`
- `feature_disabled`
- `chat_not_found`
- `option_not_found`
- `agent_execution_failed`
- `chat_already_has_active_execution`
- `agent_execution_cancelled`
- `execution_not_found`

## Stage-4/8 Chat Routes

Trusted `/v1/**` stage-8 routes are chat-oriented:

- `GET /v1/me/settings` returns effective settings for the current trusted user.
- `PATCH /v1/me/settings` persists user intent for `defaultModel`, `contextSize`, `temperature`, `locale`, `timeZone`, `systemPrompt`, `enabledTools`, `showToolEvents`, and `streamingMessages`, then returns the re-resolved effective settings.
- `GET /v1/chats` lists only the caller's chats, supports `limit` and `includeArchived`, clamps `limit` to a hard cap (`default=50`, `max=100`), and returns `lastMessagePreview` from stored chat messages.
- `POST /v1/chats` creates a new chat owned by the caller.
- `GET /v1/chats/{chatId}/messages` lists only the caller's messages for that chat, never exposes persisted agent runtime state directly, and clamps `limit` to a hard cap (`default=100`, `max=500`).
- `GET /v1/chats/{chatId}/events?afterSeq=` replays only the caller's persisted backend events for that chat using the canonical `AgentEvent.seq` and clamps `limit` to a hard cap (`default=100`, `max=1000`).
- `WS /v1/chats/{chatId}/ws?afterSeq=` replays persisted events with `seq > afterSeq`, then subscribes the caller to live per-chat events from the in-process event bus.
- `POST /v1/chats/{chatId}/messages` validates ownership and payload, resolves effective execution settings, creates a persisted `AgentExecution`, stores the user message, and then:
  - returns the old synchronous contract with `assistantMessage` when `streamingMessages=false` or `wsEvents=false`;
  - returns fast with only `message` + `execution(status=running)` when both effective `streamingMessages=true` and `wsEvents=true`, leaving the final assistant output to the event stream;
  - returns `assistantMessage=null` + `execution(status=waiting_option)` when the runtime requests an option before producing a final assistant answer in the sync path.
- `POST /v1/options/{optionId}/answer` is scoped only by trusted identity, validates pending option ownership without leaking foreign existence, atomically transitions `pending -> answered`, appends canonical `option.answered`, flips the same execution row back to `running`, and resumes continuation in background under the original `executionId`.
- Stage-8 keeps using the same request-scoped runtime event sink from stage-5, creates request-scoped assistant-message placeholders only when streaming deltas actually arrive, updates the persisted assistant message in place, appends durable `AgentEvent` rows such as `execution.started`, `message.created`, `message.completed`, `tool.call.started`, `tool.call.finished`, `tool.call.failed`, `option.requested`, `option.answered`, `execution.finished`, `execution.failed`, and `execution.cancelled`, emits `message.delta` as live-only WebSocket events without repository writes or fake event `seq`, persists tool-call audit rows separately with redacted/truncated previews only, and broadcasts both durable and live-only event envelopes through the stage-6 event bus while keeping HTTP/WS replay backed only by durable repository rows.
- `POST /v1/chats/{chatId}/cancel-active` marks the caller's active execution as `cancelling`, cancels its tracked coroutine job, and returns the updated `execution`.
- `POST /v1/chats/{chatId}/executions/{executionId}/cancel` applies the same cancellation flow for a specific execution scoped to an owned chat.

Ownership is enforced on all stage-8 `/v1/chats/**` endpoints by resolving the chat through `userId + chatId`; foreign chats return structured `chat_not_found` instead of leaking existence details. Option answer routes resolve only through `userId + optionId` and return structured `option_not_found` for both missing and foreign rows. Execution resources are always scoped through an owned chat and are never resolved from caller-controlled `userId`.

`AgentExecutionRepository` is now part of the live request path rather than a stub. Each `/messages` request persists `queued -> running -> waiting_option/completed/failed` transitions, links `userMessageId` and `assistantMessageId`, stores `model/provider/usage/clientMessageId/startedAt/finishedAt`, persists enough execution metadata to resume a paused turn with the same model/context/locale/time-zone/system-prompt/tool-event/streaming settings, and enforces one active execution per `userId + chatId`. Failed executions do not create assistant messages in the non-streaming path and do not overwrite persisted agent state; postgres mode now also marks stale `agent_conversation_state` writes as `state_conflict` and emits `execution.failed`. Cancelled executions transition through `cancelling -> cancelled`. `OptionRepository` is also on the live request path now: `option.requested` persists a heavy `Option` row and marks the execution `waiting_option`, while `POST /v1/options/{optionId}/answer` atomically rejects double answers and resumes the same execution row instead of creating a second execution.

Note for review: postgres mode currently preserves legacy `/agent` by lazily creating an archived `chats` row when a legacy conversation id has no product chat yet, so `agent_conversation_state.chat_id` can keep the documented FK to `chats(id)`.

## Internal Agent Route

`POST /agent` request body:

```json
{
  "requestId": "3addc960-3b7c-4f3b-acf5-eb687c39a7cb",
  "userId": "9d243496-4c5e-4f53-a55e-4fe65092613e",
  "conversationId": "5be87fa3-9b57-4cc8-91ea-02f093851a29",
  "prompt": "Напиши короткое резюме проекта",
  "model": "GigaChat-Max",
  "contextSize": 16000,
  "source": "web",
  "locale": "ru-RU",
  "timeZone": "Europe/Moscow"
}
```

Success response:

```json
{
  "requestId": "3addc960-3b7c-4f3b-acf5-eb687c39a7cb",
  "conversationId": "5be87fa3-9b57-4cc8-91ea-02f093851a29",
  "userMessageId": "834820cb-d9ae-4ce6-aa90-d4676a13d625",
  "assistantMessageId": "2f3694ee-dc58-4aa1-9aa5-9c5c02c9c9a1",
  "content": "Souz — это AI-ассистент...",
  "model": "GigaChat-Max",
  "provider": "GIGA",
  "contextSize": 16000,
  "usage": {
    "promptTokens": 1200,
    "completionTokens": 340,
    "totalTokens": 1540,
    "precachedTokens": 0
  }
}
```

Error mapping:

- `401` missing or invalid internal token.
- `400` invalid JSON, missing required fields, invalid UUIDs, mismatched request id header, invalid `contextSize`, or invalid `timeZone`.
- `404` user or conversation not found when a user/conversation resolver is configured.
- `409` duplicate `requestId` or concurrent active request for the same user conversation.
- `500` LLM/runtime failure.

## Backend Agent Runtime Lifecycle

For `/agent`, `BackendAgentService` is now mostly orchestration:

- validate request
- reject duplicate request ids
- enforce one active request per conversation
- resolve a cached `BackendConversationRuntime`
- execute one turn and assemble the HTTP response

`BackendConversationRuntimeFactory` assembles each request runtime explicitly instead of creating a fresh request-scoped DI container. The runtime loads the persisted in-memory snapshot for the conversation, executes one turn through the shared kernel, and persists the updated snapshot after every successful turn.

Model and `contextSize` remain request-driven. Each turn can change them without losing the conversation history because the runtime reseeds a fresh `AgentContext` from the persisted snapshot and the request’s current model/context window before execution.
