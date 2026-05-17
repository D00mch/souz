# Backend

The `:backend` module is a JVM HTTP server build for Souz without Compose UI startup, audio capture, hotkeys, or desktop-only tools. It exposes `/health` plus a trusted-proxy `/v1/**` API and reuses the shared `:agent` execution kernel for chat turns.

## Routes

- `GET /health` returns process and selected-model status.
- `GET /v1/bootstrap` returns backend features, storage mode, server-visible models/tools, and effective settings for the trusted user, including public `interfaceLanguage`, `requestTimeoutMillis`, and `useFewShotExamples`.
- `GET /v1/onboarding/state` returns first-run onboarding requirements, current effective settings, and model-access hints for the trusted user.
- `POST /v1/onboarding/complete` persists first-run preferences, optionally accepts `interfaceLanguage`, `requestTimeoutMillis`, and `useFewShotExamples`, and marks onboarding as completed for the trusted user.
- `GET /v1/me/settings` and `PATCH /v1/me/settings` read and persist public user settings without changing the `{ settings: ... }` envelope shape.
- `GET /v1/me/provider-keys`, `PUT /v1/me/provider-keys/{provider}`, and `DELETE /v1/me/provider-keys/{provider}` manage encrypted per-user provider keys.
- `GET /v1/chats`, `POST /v1/chats`, `PATCH /v1/chats/{chatId}/title`, `POST /v1/chats/{chatId}/archive`, and `POST /v1/chats/{chatId}/unarchive` manage owned chats.
- `GET /v1/chats/{chatId}/messages` lists visible product messages only.
- `POST /v1/chats/{chatId}/messages` creates a user message, persists an `AgentExecution`, and either completes synchronously or returns `running` for WS-driven streaming.
- `GET /v1/chats/{chatId}/telegram-bot`, `PUT /v1/chats/{chatId}/telegram-bot`, and `DELETE /v1/chats/{chatId}/telegram-bot` manage Telegram bot bindings for an owned chat without returning the raw token or token hash.
- `GET /v1/chats/{chatId}/events` and `WS /v1/chats/{chatId}/ws` replay durable events and subscribe to live per-chat updates.
- `POST /v1/options/{optionId}/answer` resumes the original execution after a pending option is answered.
- `POST /v1/chats/{chatId}/cancel-active` and `POST /v1/chats/{chatId}/executions/{executionId}/cancel` cancel active executions.

## Identity And Safety

- `/v1/**` trusts identity only from `X-User-Id` and `X-Souz-Proxy-Auth`.
- `X-User-Id` is treated as an opaque string, validated for shape, and provisioned through `UserRepository.ensureUser(userId)` before the request reaches settings/chat/provider-key services.
- Missing proxy configuration returns structured `backend_misconfigured`; invalid or missing trusted headers return `untrusted_proxy`, `missing_user_identity`, or `invalid_user_identity`.
- The backend tool catalog is restricted to backend-safe categories and intentionally excludes desktop-only tools and `WebImageSearch`.

## Runtime Model

- Chat turns resolve effective settings from server defaults, persisted user intent, feature flags, and per-request overrides; request-scoped backend execution now also applies effective `requestTimeoutMillis` to provider clients and strips tool few-shot examples when `useFewShotExamples=false`.
- Per-user onboarding completion now lives alongside persisted user settings; `/v1/bootstrap` and `/v1/onboarding/state` normalize missing settings, legacy partial settings payloads, and invalid provider-key rows into stable responses instead of surfacing them as misconfiguration errors.
- Execution persists product messages separately from `agent_conversation_state`; runtime-only continuation state stays inside `AgentStateRepository`.
- `conversationId = chatId.toString()` is the stable runtime identity for chat execution.
- `BackendConversationRuntimeFactory` rebuilds a request-scoped runtime from persisted session state, while `AgentExecutionService` owns product execution lifecycle, cancellation, and option continuation.
- Telegram bot bindings stay disabled unless `ENABLE_BACKEND_TG_FEATURE=true`. When enabled, they validate tokens through Telegram `getMe`, call `deleteWebhook(drop_pending_updates=true)` before switching to long polling, store a per-chat binding in backend storage with encrypted-at-rest bot tokens plus a hashed one-time link secret, return only safe binding metadata to the UI together with a one-time `/start <secret>` command on bind, keep new/rebound bindings pending until that exact secret arrives from a private Telegram chat, ignore stale/non-matching pre-link messages, bind permanently to the first matching private `from.id + chat.id`, reject later private traffic from other Telegram accounts, feed accepted Telegram text into `AgentExecutionService.executeChatTurn(...)` with `clientMessageId = "telegram:<bindingId>:<updateId>"` and `streamingMessages = false`, renew the per-binding poller lease while an update is in flight, fence reply/checkpoint side effects on active lease ownership, send the final assistant response back to Telegram in <=4096-char chunks with a short fallback reply when needed, and advance `lastUpdateId` only after each update has been processed under the current lease owner.
- Backend runtime sandboxes are resolved per user only: singleton runtime tools receive `ToolInvocationMeta.userId`, and backend sandbox scope currently omits `conversationId`.
- `message.delta` stays live-only, while durable events such as `execution.started`, `message.created`, `message.completed`, `tool.call.*`, `option.*`, `execution.finished`, `execution.failed`, and `execution.cancelled` are persisted and replayable. `message.created` now carries `clientMessageId` when the originating user message had one so web clients can reconcile optimistic sends against WS echoes.

## Storage

- Storage modes: `memory`, `filesystem`, `postgres`.
- `memory` uses bounded in-process repositories to reduce accidental OOM risk.
- `filesystem` stores per-user data under `SOUZ_BACKEND_DATA_DIR` / `souz.backend.dataDir` using URL-safe user path segments and append-only logs for messages, executions, options, events, and tool calls; Telegram bot bindings live beside the chat as `telegram-bot.json` with encrypted token payload plus linked Telegram-user metadata.
- `postgres` uses JDBC + HikariCP + Flyway, allocates message/event sequence numbers per chat, enforces one active execution per chat, persists durable replay only when `SOUZ_FEATURE_DURABLE_EVENT_REPLAY=true`, uses optimistic locking on `agent_conversation_state`, keeps Telegram binding token hashes unique, and stores per-binding poller lease ownership in `telegram_bot_bindings` for multi-instance polling safety.
- Telegram bot tokens now use `TELEGRAM_TOKEN_ENCRYPTION_KEY` / `souz.telegram.tokenEncryptionKey` for AES-GCM encryption at rest. Telegram link secrets are stored only as hashes. Legacy rows copied forward from the old plaintext column still need to be rebound or rewritten by the application path before they stop relying on the plaintext compatibility fallback. Older pending-link rows created before the one-time-secret flow should be rebound so the UI can issue a fresh `/start <secret>` command.

## Config

- Feature flags:
  - `SOUZ_FEATURE_WS_EVENTS` / `souz.backend.feature.wsEvents`
  - `SOUZ_FEATURE_STREAMING_MESSAGES` / `souz.backend.feature.streamingMessages`
  - `SOUZ_FEATURE_TOOL_EVENTS` / `souz.backend.feature.toolEvents`
  - `SOUZ_FEATURE_OPTIONS` / `souz.backend.feature.options`
  - `SOUZ_FEATURE_DURABLE_EVENT_REPLAY` / `souz.backend.feature.durableEventReplay`
  - `ENABLE_BACKEND_TG_FEATURE` / `souz.backend.feature.telegramBot`
- Telegram:
  - `TELEGRAM_TOKEN_ENCRYPTION_KEY` / `souz.telegram.tokenEncryptionKey`
  - `SOUZ_TELEGRAM_POLLING_MAX_CONCURRENCY` / `souz.telegram.pollingMaxConcurrency`
- Storage mode:
  - `SOUZ_STORAGE_MODE` / `souz.backend.storageMode`
- Filesystem root:
  - `SOUZ_BACKEND_DATA_DIR` / `souz.backend.dataDir`
- Postgres:
  - `SOUZ_BACKEND_DB_HOST` / `souz.backend.db.host`
  - `SOUZ_BACKEND_DB_PORT` / `souz.backend.db.port`
  - `SOUZ_BACKEND_DB_NAME` / `souz.backend.db.name`
  - `SOUZ_BACKEND_DB_USER` / `souz.backend.db.user`
  - `SOUZ_BACKEND_DB_PASSWORD` / `souz.backend.db.password`
  - `SOUZ_BACKEND_DB_SCHEMA` / `souz.backend.db.schema`
  - `SOUZ_BACKEND_DB_MAX_POOL_SIZE` / `souz.backend.db.maxPoolSize`
  - `SOUZ_BACKEND_DB_CONNECTION_TIMEOUT_MS` / `souz.backend.db.connectionTimeoutMs`

## Structure

```text
backend/
├── build.gradle.kts
├── AGENTS.md
└── src/
    ├── main/kotlin/ru/souz/backend/
    │   ├── agent/        # Runtime glue, event sink, persisted session adapters
    │   ├── app/          # Entry point, lifecycle, DI, process config
    │   ├── bootstrap/    # /v1/bootstrap assembly
    │   ├── chat/         # Chat/message models, repositories, services
    │   ├── common/       # Shared backend exception types
    │   ├── config/       # Feature-flag and env/property readers
    │   ├── events/       # Durable/live event models, bus, services
    │   ├── execution/    # Execution models, repositories, lifecycle services
    │   ├── http/         # Ktor server, DTOs, routes, validation
    │   ├── keys/         # Provider-key models, repositories, services
    │   ├── onboarding/   # First-run onboarding state and completion service
    │   ├── options/      # Option models, repositories, services
    │   ├── security/     # Trusted proxy request identity
    │   ├── settings/     # User settings models, repositories, resolver, service
    │   ├── storage/      # Memory/filesystem/postgres implementations
    │   ├── telegram/     # Telegram bot binding models, API client, service, long-polling
    │   ├── toolcall/     # Tool-call audit models and repositories
    │   └── user/         # User repository abstraction
    └── test/kotlin/ru/souz/backend/
```
