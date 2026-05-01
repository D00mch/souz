Ниже — спецификация для реализации server-mode backend + web UI. Я учитываю текущий код: `backend` уже подключён как отдельный Gradle-модуль рядом с `agent`, `runtime`, `llms`, `composeApp`; текущий backend имеет пакеты `agent`, `app`, `common`, `http`, а внутри `agent` уже есть `model`, `runtime`, `service`, `session`. ([GitHub][1])

---

# 1. Концепция

## Цель

Сделать Souz Backend полноценным серверным режимом агента, а не отдельной обёрткой над desktop-приложением.

Целевая схема:

```text
Browser Web UI
  -> Auth Proxy
  -> Souz Backend
  -> Agent Runtime
  -> LLM providers / tools / integrations
```

Backend должен предоставлять явный REST/WebSocket API для web UI:

```text
- пользователи;
- настройки;
- список чатов;
- сообщения;
- запуск агента;
- streaming assistant response;
- tool call events;
- interactive choices;
- cancel;
- reconnect/replay.
```

Web UI **не копирует desktop implementation**, а получает сопоставимые возможности через публичный backend API.

## Что использовать из текущего проекта

Текущий backend уже содержит минимальный HTTP server на Ktor, который сейчас отдаёт `/`, `/health` и `POST /agent`. ([GitHub][2])
`BackendAgentService` уже решает часть задач orchestration: валидирует запрос, не даёт запустить два запроса в один conversation, создаёт runtime и возвращает ответ. ([GitHub][3])
`BackendConversationRuntime` уже создаёт request-scoped runtime, загружает сохранённую agent session, вызывает `executor.execute(...)`, сохраняет обновлённую history и возвращает output/usage. ([GitHub][4])

Это нужно не переписать, а расширить:

```text
старый /agent
  оставить как legacy/debug endpoint

новый API
  сделать chat-oriented:
    /v1/chats
    /v1/chats/{chatId}/messages
    /v1/chats/{chatId}/ws
    /v1/choices/{choiceId}/answer
```

## Важное разделение данных

Нужно хранить отдельно:

```text
messages
  продуктовая история чата, которую видит пользователь

agent_conversation_state
  рабочее сериализованное состояние агента
```

`messages` не являются прямым LLM-контекстом. Это UI/product history: длинная, пагинируемая, полная.

`agent_conversation_state.context_json` содержит рабочее состояние runtime, то есть то, что сейчас концептуально соответствует `ctx.messages` / `result.context.history`. Текущий `AgentConversationSession` уже хранит `history: List<LLMRequest.Message>` и сейчас реализован через временный `InMemoryAgentSessionRepository`. ([GitHub][5])

Backend не должен дублировать summarization/context-management логику. Он должен:

```text
1. загрузить сериализованный agent state;
2. передать его runtime;
3. получить обновлённый state;
4. сохранить обратно.
```

## Streaming

Desktop streaming сейчас завязан на `settingsProvider.useStreaming`: `NodesLLM.chat()` при включённом streaming вызывает `llmApi.messageStream(...)`, аккумулирует чанки и эмитит их в `sideEffects`. ([GitHub][6])
Desktop UI затем подписывается на `AgentFacade.sideEffects`, где текстовые side effects объединены с tool invocations, и обновляет pending assistant message. ([GitHub][7])

Для backend нельзя использовать shared `sideEffects`, потому что при многих пользователях есть риск смешать события разных executions. Нужно добавить **request-scoped event sink**.

Web UI должен получать:

```text
message.created
message.delta
message.completed

tool.call.started
tool.call.finished
tool.call.failed

choice.requested
choice.answered

execution.started
execution.finished
execution.failed
execution.cancelled
```

Панель мыслей в web не нужна. Raw hidden chain-of-thought не стримим. Agent history updates для “thinking panel” тоже не нужны.

## Feature flag для streaming/events

Нужен backend feature flag, чтобы streaming/events можно было полностью выключить:

```text
SOUZ_FEATURE_WS_EVENTS=true|false
SOUZ_FEATURE_STREAMING_MESSAGES=true|false
SOUZ_FEATURE_TOOL_EVENTS=true|false
SOUZ_FEATURE_CHOICES=true|false
```

Если `SOUZ_FEATURE_STREAMING_MESSAGES=false`, backend работает в обычном request/response стиле:

```text
POST /v1/chats/{chatId}/messages
  -> создаёт user message
  -> запускает agent
  -> после завершения создаёт assistant message
  -> возвращает финальный результат
```

Если включено, ответ идёт через WebSocket/events.

---

# 2. Структура файлов

## Текущую структуру не ломать

Текущий backend уже имеет структуру:

```text
backend/src/main/kotlin/ru/souz/backend/
  agent/
    model/
    runtime/
    service/
    session/
  app/
  common/
  http/
```

Пакеты `agent/model`, `agent/runtime`, `agent/service`, `agent/session` уже существуют и используются. ([GitHub][8])

## Целевая структура

Добавить рядом новые product/server пакеты:

```text
backend/src/main/kotlin/ru/souz/backend/
  agent/
    model/
      BackendAgentModels.kt
    runtime/
      BackendConversationRuntime.kt
      BackendAgentHostAdapters.kt
      AgentRuntimeEventSink.kt              // new
      BackendAgentRuntimeEventMapper.kt     // new
    service/
      BackendAgentService.kt                // legacy /agent
      AgentExecutionService.kt              // new
    session/
      AgentSessionRepository.kt             // legacy-compatible
      AgentStateRepository.kt               // new

  chat/
    model/
      Chat.kt
      ChatMessage.kt
      ChatRole.kt
    repository/
      ChatRepository.kt
      MessageRepository.kt
    service/
      ChatService.kt
      MessageService.kt

  execution/
    model/
      AgentExecution.kt
      AgentExecutionStatus.kt
    repository/
      AgentExecutionRepository.kt
    service/
      ChatExecutionLockService.kt
      AgentExecutionOrchestrator.kt
      AgentCancellationService.kt

  choices/
    model/
      Choice.kt
      ChoiceKind.kt
      ChoiceStatus.kt
      ChoiceAnswer.kt
    repository/
      ChoiceRepository.kt
    service/
      ChoiceService.kt

  events/
    model/
      AgentEvent.kt
      AgentEventType.kt
      AgentEventSeq.kt
    repository/
      AgentEventRepository.kt
    service/
      AgentEventBus.kt
      AgentEventReplayBuffer.kt
      WebSocketSessionRegistry.kt

  settings/
    model/
      UserSettings.kt
      EffectiveUserSettings.kt
    repository/
      UserSettingsRepository.kt
    service/
      UserSettingsService.kt
      EffectiveSettingsResolver.kt

  security/
    TrustedProxyIdentity.kt
    RequestIdentity.kt
    RequestIdentityPlugin.kt
    HeaderRedactor.kt

  storage/
    StorageMode.kt
    memory/
      MemoryChatRepository.kt
      MemoryMessageRepository.kt
      MemoryAgentStateRepository.kt
      MemoryAgentExecutionRepository.kt
      MemoryChoiceRepository.kt
      MemoryUserSettingsRepository.kt
      MemoryAgentEventRepository.kt
    filesystem/
      FilesystemChatRepository.kt
      FilesystemMessageRepository.kt
      FilesystemAgentStateRepository.kt
      FilesystemAgentExecutionRepository.kt
      FilesystemChoiceRepository.kt
      FilesystemUserSettingsRepository.kt
      FilesystemAgentEventRepository.kt
    postgres/
      PostgresDataSourceFactory.kt
      PostgresChatRepository.kt
      PostgresMessageRepository.kt
      PostgresAgentStateRepository.kt
      PostgresAgentExecutionRepository.kt
      PostgresChoiceRepository.kt
      PostgresUserSettingsRepository.kt
      PostgresAgentEventRepository.kt
      migrations/

  http/
    BackendHttpServer.kt
    routes/
      BootstrapRoutes.kt
      SettingsRoutes.kt
      ChatRoutes.kt
      MessageRoutes.kt
      ChoiceRoutes.kt
      WebSocketRoutes.kt
      LegacyAgentRoutes.kt
    dto/
      BootstrapDtos.kt
      SettingsDtos.kt
      ChatDtos.kt
      MessageDtos.kt
      EventDtos.kt
      ChoiceDtos.kt
      ErrorDtos.kt
```

## Web module

Добавить отдельный web-модуль в репозитории:

```text
web/
  package.json
  vite.config.ts
  tsconfig.json
  src/
    main.tsx
    api/
      client.ts
      bootstrap.ts
      chats.ts
      messages.ts
      settings.ts
      choices.ts
    ws/
      chatSocket.ts
      eventReducer.ts
    components/
      layout/
      chat/
        ChatList.tsx
        MessageList.tsx
        MessageBubble.tsx
        StreamingAssistantBubble.tsx
        Composer.tsx
        ToolCallsPanel.tsx
        ChoiceCard.tsx
      settings/
        SettingsPanel.tsx
    state/
      chatStore.ts
      settingsStore.ts
    types/
      api.ts
      events.ts
```

Production:

```text
/
  отдаёт собранный frontend

/assets/*
  static assets

/v1/**
  REST API

/v1/chats/{chatId}/ws
  WebSocket
```

---

# 3. Структура БД

## Storage modes

Реализованы три режима:

```text
memory
  dev/test

filesystem
  JSON/JSONL в persistent volume

postgres
  production
```

Переключение:

```text
SOUZ_STORAGE_MODE=memory|filesystem|postgres
SOUZ_BACKEND_DATA_DIR=./data
souz.backend.dataDir=./data
SOUZ_BACKEND_DB_HOST=127.0.0.1
SOUZ_BACKEND_DB_PORT=5432
SOUZ_BACKEND_DB_NAME=souz
SOUZ_BACKEND_DB_USER=souz
SOUZ_BACKEND_DB_PASSWORD=...
SOUZ_BACKEND_DB_SCHEMA=public
SOUZ_BACKEND_DB_MAX_POOL_SIZE=10
SOUZ_BACKEND_DB_CONNECTION_TIMEOUT_MS=30000
souz.backend.db.host=127.0.0.1
souz.backend.db.port=5432
souz.backend.db.name=souz
souz.backend.db.user=souz
souz.backend.db.password=...
souz.backend.db.schema=public
souz.backend.db.maxPoolSize=10
souz.backend.db.connectionTimeoutMs=30000
```

## Memory mode

Используется для unit/integration tests и локального dev.

Хранение:

```text
ConcurrentHashMap<UserId, User>
ConcurrentHashMap<ChatKey, Chat>
ConcurrentHashMap<ChatKey, MutableList<Message>>
ConcurrentHashMap<ChatKey, AgentConversationState>
ConcurrentHashMap<ExecutionId, AgentExecution>
ConcurrentHashMap<ChoiceId, Choice>
ConcurrentHashMap<ChatKey, RingBuffer<AgentEvent>>
```

## Filesystem mode

Структура persistent volume:

```text
data/
  users/
    {encodedUserId}/
      settings.json
      provider-keys.json              // encrypted only, optional
      chats/
        {chatId}/
          chat.json
          messages.jsonl
          agent-state.json
          executions.jsonl
          choices.jsonl
          events.jsonl                // optional, can start disabled
```

`{encodedUserId}` не должен быть raw opaque trusted `userId`. В текущей реализации это стабильный URL-safe base64 сегмент с префиксом, чтобы значение из `X-User-Id` не попадало напрямую в filesystem path.

Правила:

```text
messages.jsonl
  append-only snapshot log, last-write-wins on reload for updateContent

agent-state.json
  write temp file -> fsync -> atomic rename

settings.json
  write temp file -> fsync -> atomic rename

executions.jsonl / choices.jsonl
  append-only snapshot log, last-write-wins on reload

events.jsonl
  append-only, seq continues after restart
```

Дополнительно:

```text
- corruption в agent-state.json не должен ломать чтение product messages;
- backend может вернуть null + warning для agent state и продолжить работать с messages;
- messages остаются независимыми от agent-state, чтобы state можно было пересобрать позже.
```

## Postgres schema

### users

```sql
create table users (
  id text primary key,
  created_at timestamptz not null default now(),
  last_seen_at timestamptz
);
```

`id` — значение из trusted `X-User-Id`.

### user_settings

```sql
create table user_settings (
  user_id text primary key references users(id) on delete cascade,
  settings_json jsonb not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
```

Пример `settings_json`:

```json
{
  "defaultModel": "gpt-4.1",
  "contextSize": 32000,
  "temperature": 0.2,
  "locale": "ru-RU",
  "timeZone": "Europe/Amsterdam",
  "systemPrompt": null,
  "enabledTools": ["telegram", "filesystem"],
  "showToolEvents": true,
  "streamingMessages": true,
  "toolPermissions": {},
  "mcp": {}
}
```

### chats

```sql
create table chats (
  id uuid primary key,
  user_id text not null references users(id) on delete cascade,
  title text,
  archived boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index chats_user_updated_idx
on chats(user_id, updated_at desc);
```

### messages

```sql
create table messages (
  id uuid primary key,
  user_id text not null references users(id) on delete cascade,
  chat_id uuid not null references chats(id) on delete cascade,
  seq bigint not null,
  role text not null,
  content text not null,
  metadata jsonb not null default '{}',
  created_at timestamptz not null default now(),

  unique(user_id, chat_id, seq)
);

create index messages_chat_seq_idx
on messages(user_id, chat_id, seq desc);
```

`role`:

```text
user
assistant
system
tool
```

Для UI обычно нужны только `user` и `assistant`. Tool/system можно хранить при необходимости, но не использовать как agent context напрямую.

### agent_conversation_state

```sql
create table agent_conversation_state (
  user_id text not null references users(id) on delete cascade,
  chat_id uuid not null references chats(id) on delete cascade,

  context_json jsonb not null,
  based_on_message_seq bigint not null,

  updated_at timestamptz not null default now(),
  row_version bigint not null default 0,

  primary key(user_id, chat_id)
);
```

`context_json` содержит versioned serialized runtime state:

```json
{
  "schemaVersion": 1,
  "activeAgentId": "default",
  "history": [],
  "temperature": 0.2,
  "locale": "ru-RU",
  "timeZone": "Europe/Amsterdam"
}
```

В начале это может быть прямой аналог текущего `AgentConversationSession`, потому что сейчас он уже хранит `activeAgentId`, `history`, `temperature`, `locale`, `timeZone`. ([GitHub][5])

Текущее stage-10 поведение для legacy `/agent`: postgres storage lazily materializes an archived `chats` row for unknown legacy conversation ids so `agent_conversation_state` can keep the documented foreign key to `chats(id)`.
Note for review: если legacy `/agent` нужно полностью скрыть от product chat listing даже при `includeArchived=true`, стоит выделить отдельный sentinel-флаг или отдельную таблицу conversation roots.

Optimistic locking:

```sql
update agent_conversation_state
set
  context_json = :context_json,
  based_on_message_seq = :based_on_message_seq,
  updated_at = now(),
  row_version = row_version + 1
where
  user_id = :user_id
  and chat_id = :chat_id
  and row_version = :expected_row_version;
```

### agent_executions

Heavy domain model — сразу как полноценная таблица.

```sql
create table agent_executions (
  id uuid primary key,

  user_id text not null references users(id) on delete cascade,
  chat_id uuid not null references chats(id) on delete cascade,

  user_message_id uuid references messages(id),
  assistant_message_id uuid references messages(id),

  status text not null,

  request_id text,
  client_message_id text,

  model text,
  provider text,

  started_at timestamptz not null default now(),
  finished_at timestamptz,

  cancel_requested boolean not null default false,

  error_code text,
  error_message text,

  usage_json jsonb,
  metadata jsonb not null default '{}'
);

create index agent_executions_chat_started_idx
on agent_executions(user_id, chat_id, started_at desc);

create unique index agent_executions_one_active_per_chat_idx
on agent_executions(user_id, chat_id)
where status in ('queued', 'running', 'waiting_choice', 'cancelling');
```

Statuses:

```text
queued
running
waiting_choice
cancelling
cancelled
completed
failed
```

### choices

Heavy domain model — сразу как полноценная таблица.

```sql
create table choices (
  id uuid primary key,

  user_id text not null references users(id) on delete cascade,
  chat_id uuid not null references chats(id) on delete cascade,
  execution_id uuid not null references agent_executions(id) on delete cascade,

  kind text not null,
  title text,
  selection_mode text not null,

  options_json jsonb not null,
  payload_json jsonb not null default '{}',

  status text not null,

  answer_json jsonb,
  created_at timestamptz not null default now(),
  expires_at timestamptz,
  answered_at timestamptz
);

create index choices_execution_idx
on choices(user_id, chat_id, execution_id);

create index choices_status_idx
on choices(user_id, status);
```

Kinds:

```text
text_edit_variant
telegram_recipient
file_candidate
tool_confirmation
generic_selection
```

Statuses:

```text
pending
answered
cancelled
expired
```

### agent_events

Сразу можно завести таблицу, но включать durable persistence feature flag’ом.

```sql
create table agent_events (
  id uuid primary key,

  user_id text not null references users(id) on delete cascade,
  chat_id uuid not null references chats(id) on delete cascade,
  execution_id uuid references agent_executions(id) on delete cascade,

  seq bigint not null,
  type text not null,
  payload jsonb not null,

  created_at timestamptz not null default now(),

  unique(user_id, chat_id, seq)
);

create index agent_events_chat_seq_idx
on agent_events(user_id, chat_id, seq);
```

### tool_calls

Product audit. Не замена OpenTelemetry.

```sql
create table tool_calls (
  id uuid primary key,

  user_id text not null references users(id) on delete cascade,
  chat_id uuid not null references chats(id) on delete cascade,
  execution_id uuid not null references agent_executions(id) on delete cascade,

  name text not null,
  arguments_json jsonb not null default '{}',
  result_preview text,

  status text not null,
  duration_ms bigint,
  error text,

  created_at timestamptz not null default now()
);

create index tool_calls_execution_idx
on tool_calls(user_id, chat_id, execution_id);
```

### user_provider_keys

Для user-managed keys.

```sql
create table user_provider_keys (
  user_id text not null references users(id) on delete cascade,
  provider text not null,

  encrypted_api_key bytea not null,
  key_hint text not null,

  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),

  primary key(user_id, provider)
);
```

Plaintext API key не хранить.

---

# 4. Описание API

## Общие правила

Все `/v1/**` endpoints получают пользователя только из trusted request identity.

```http
X-User-Id: user-123
X-Souz-Proxy-Auth: internal-secret
```

`userId` нельзя принимать из body/query.

Все ответы ошибок:

```json
{
  "error": {
    "code": "chat_not_found",
    "message": "Chat not found."
  }
}
```

Стабильные backend error codes на текущих стадиях:

```text
untrusted_proxy
missing_user_identity
backend_misconfigured
internal_error
invalid_request
feature_disabled
chat_not_found
choice_not_found
agent_execution_failed
chat_already_has_active_execution
agent_execution_cancelled
execution_not_found
```

## GET /v1/bootstrap

Нужен frontend-у при старте.

Response:

```json
{
  "user": {
    "id": "user-123"
  },
  "features": {
    "wsEvents": true,
    "streamingMessages": true,
    "toolEvents": true,
    "choices": true,
    "durableEventReplay": false
  },
  "storage": {
    "mode": "postgres"
  },
  "capabilities": {
    "models": [
      {
        "provider": "openai",
        "model": "gpt-4.1",
        "serverManagedKey": true,
        "userManagedKey": false
      }
    ],
    "tools": [
      {
        "name": "telegram",
        "enabled": true
      }
    ]
  },
  "settings": {
    "defaultModel": "gpt-4.1",
    "contextSize": 32000,
    "temperature": 0.2,
    "locale": "ru-RU",
    "timeZone": "Europe/Amsterdam",
    "showToolEvents": true,
    "streamingMessages": true
  }
}
```

## GET /v1/me/settings

Возвращает user settings.

## PATCH /v1/me/settings

Request:

```json
{
  "defaultModel": "gpt-4.1",
  "contextSize": 32000,
  "temperature": 0.2,
  "locale": "ru-RU",
  "timeZone": "Europe/Amsterdam",
  "systemPrompt": "optional",
  "enabledTools": ["telegram"],
  "showToolEvents": true,
  "streamingMessages": true
}
```

Response:

```json
{
  "settings": {
    "defaultModel": "gpt-4.1",
    "contextSize": 32000,
    "temperature": 0.2,
    "locale": "ru-RU",
    "timeZone": "Europe/Amsterdam",
    "systemPrompt": "optional",
    "enabledTools": ["telegram"],
    "showToolEvents": true,
    "streamingMessages": true
  }
}
```

## GET /v1/chats

Query:

```text
limit
cursor
includeArchived
```

Response:

```json
{
  "items": [
    {
      "id": "3d3908f3-3b3e-4b44-8a21-4561ec680a21",
      "title": "Новый чат",
      "archived": false,
      "createdAt": "2026-04-30T10:00:00Z",
      "updatedAt": "2026-04-30T10:05:00Z",
      "lastMessagePreview": "Привет..."
    }
  ],
  "nextCursor": null
}
```

## POST /v1/chats

Request:

```json
{
  "title": "Новый чат"
}
```

Response:

```json
{
  "chat": {
    "id": "3d3908f3-3b3e-4b44-8a21-4561ec680a21",
    "title": "Новый чат",
    "archived": false,
    "createdAt": "2026-04-30T10:00:00Z",
    "updatedAt": "2026-04-30T10:00:00Z"
  }
}
```

## GET /v1/chats/{chatId}/messages

Query:

```text
beforeSeq
afterSeq
limit
```

Response:

```json
{
  "items": [
    {
      "id": "92ab...",
      "chatId": "3d39...",
      "seq": 1,
      "role": "user",
      "content": "Привет",
      "metadata": {},
      "createdAt": "2026-04-30T10:00:00Z"
    }
  ],
  "nextBeforeSeq": null
}
```

## POST /v1/chats/{chatId}/messages

Основной endpoint запуска агента.

Request:

```json
{
  "content": "Напиши ответ",
  "clientMessageId": "optional-idempotency-key",
  "options": {
    "model": "gpt-4.1",
    "temperature": 0.2
  }
}
```

Если streaming включён, response быстрый:

```json
{
  "message": {
    "id": "user-message-id",
    "seq": 10,
    "role": "user",
    "content": "Напиши ответ"
  },
  "execution": {
    "id": "execution-id",
    "status": "running"
  }
}
```

Если streaming выключен, можно вернуть финальный результат:

```json
{
  "message": {
    "id": "user-message-id",
    "seq": 10,
    "role": "user",
    "content": "Напиши ответ"
  },
  "assistantMessage": {
    "id": "assistant-message-id",
    "seq": 11,
    "role": "assistant",
    "content": "Финальный ответ"
  },
  "execution": {
    "id": "execution-id",
    "status": "completed"
  }
}
```

## WS /v1/chats/{chatId}/ws

Query:

```text
afterSeq
```

Клиент подключается:

```text
/v1/chats/{chatId}/ws?afterSeq=123
```

Backend:

```text
1. проверяет user identity;
2. проверяет ownership chatId;
3. отдаёт replay events afterSeq;
4. подписывает клиента на live events.
```

## GET /v1/chats/{chatId}/events?afterSeq=...

HTTP replay fallback.

Response:

```json
{
  "items": [
    {
      "seq": 124,
      "chatId": "chat-id",
      "executionId": "execution-id",
      "type": "message.delta",
      "payload": {
        "messageId": "assistant-message-id",
        "delta": "текст"
      },
      "createdAt": "2026-04-30T10:00:01Z"
    }
  ]
}
```

## Event envelope

```json
{
  "seq": 124,
  "chatId": "chat-id",
  "executionId": "execution-id",
  "type": "message.delta",
  "payload": {},
  "createdAt": "2026-04-30T10:00:01Z"
}
```

## Message events

```json
{
  "type": "message.created",
  "payload": {
    "messageId": "assistant-message-id",
    "seq": 11,
    "role": "assistant",
    "content": ""
  }
}
```

```json
{
  "type": "message.delta",
  "payload": {
    "messageId": "assistant-message-id",
    "delta": "часть ответа"
  }
}
```

```json
{
  "type": "message.completed",
  "payload": {
    "messageId": "assistant-message-id",
    "content": "полный финальный ответ"
  }
}
```

## Tool events

```json
{
  "type": "tool.call.started",
  "payload": {
    "toolCallId": "tool-call-id",
    "name": "telegram.send_message",
    "argumentsPreview": {
      "recipient": "Ivan",
      "text": "REDACTED_OR_TRUNCATED"
    }
  }
}
```

```json
{
  "type": "tool.call.finished",
  "payload": {
    "toolCallId": "tool-call-id",
    "name": "telegram.send_message",
    "status": "success",
    "durationMs": 831,
    "resultPreview": "Message sent"
  }
}
```

```json
{
  "type": "tool.call.failed",
  "payload": {
    "toolCallId": "tool-call-id",
    "name": "telegram.send_message",
    "status": "error",
    "durationMs": 831,
    "error": "Permission denied"
  }
}
```

## Choice events

```json
{
  "type": "choice.requested",
  "payload": {
    "choiceId": "choice-id",
    "kind": "text_edit_variant",
    "title": "Выберите вариант",
    "selectionMode": "single",
    "options": [
      {
        "id": "a",
        "label": "Короче",
        "content": "..."
      },
      {
        "id": "b",
        "label": "Формальнее",
        "content": "..."
      }
    ]
  }
}
```

## POST /v1/choices/{choiceId}/answer

Request:

```json
{
  "selectedOptionIds": ["a"],
  "freeText": null,
  "metadata": {}
}
```

Response:

```json
{
  "choice": {
    "id": "choice-id",
    "status": "answered"
  },
  "execution": {
    "id": "execution-id",
    "status": "running"
  }
}
```

## Cancel

```http
POST /v1/chats/{chatId}/cancel-active
```

или:

```http
POST /v1/chats/{chatId}/executions/{executionId}/cancel
```

Response:

```json
{
  "execution": {
    "id": "execution-id",
    "status": "cancelling"
  }
}
```

---

# 5. Описание бизнес-логики

## Identity flow

```text
1. Browser обращается к Auth Proxy.
2. Auth Proxy проверяет пользователя.
3. Proxy удаляет любые user-controlled X-User-Id.
4. Proxy выставляет trusted X-User-Id.
5. Proxy добавляет internal proxy proof:
   - X-Souz-Proxy-Auth
   - или mTLS.
6. Backend принимает user identity только из trusted headers.
```

`userId` в body запрещён.

## Bootstrap flow

```text
GET /v1/bootstrap

1. Extract userId.
2. Ensure user exists.
3. Load user settings or create defaults.
4. Return:
   - user;
   - feature flags;
   - storage mode;
   - available models;
   - available tools;
   - settings.
```

## Create chat flow

```text
POST /v1/chats

1. Extract userId.
2. Create chat.
3. Create empty agent_conversation_state lazily or immediately.
4. Return chat.
```

## Send message flow with streaming enabled

```text
POST /v1/chats/{chatId}/messages

1. Extract userId.
2. Validate chat ownership.
3. Validate content.
4. Resolve effective settings:
   server defaults
   + user settings
   + request options.
5. Start DB transaction.
6. Insert user message into messages.
7. Create agent_execution with status=queued/running.
8. Create placeholder assistant message or delay until first delta.
9. Commit.
10. Start AgentExecution coroutine.
11. Return user message + execution immediately.
12. Runtime emits events into request-scoped event sink.
13. Event sink maps runtime events to AgentEvent.
14. EventBus broadcasts events to WS clients.
15. On final output:
    - complete assistant message;
    - save agent_conversation_state;
    - mark execution completed;
    - emit message.completed and execution.finished.
```

## Send message flow with streaming disabled

```text
POST /v1/chats/{chatId}/messages

1. Same validation.
2. Insert user message.
3. Create agent_execution.
4. Run agent synchronously or in coroutine joined by request.
5. Insert assistant message after final output.
6. Save agent_conversation_state.
7. Mark execution completed.
8. Return user + assistant messages.
```

## Request-scoped event sink

Не использовать общий `AgentFacade.sideEffects` для backend. В desktop это нормально, но на сервере много пользователей и executions.

Нужно добавить в `agent/runtime` что-то вроде:

```kotlin
interface AgentRuntimeEventSink {
    suspend fun emit(event: AgentRuntimeEvent)
}
```

События runtime:

```kotlin
sealed interface AgentRuntimeEvent {
    data class LlmMessageDelta(
        val text: String,
    ) : AgentRuntimeEvent

    data class ToolCallStarted(
        val toolCallId: String,
        val name: String,
        val arguments: Map<String, Any?>,
    ) : AgentRuntimeEvent

    data class ToolCallFinished(
        val toolCallId: String,
        val name: String,
        val resultPreview: String?,
        val durationMs: Long,
    ) : AgentRuntimeEvent

    data class ToolCallFailed(
        val toolCallId: String,
        val name: String,
        val error: String,
        val durationMs: Long,
    ) : AgentRuntimeEvent

    data class ChoiceRequested(
        val choiceId: String,
        val kind: String,
        val title: String?,
        val options: List<ChoiceOption>,
        val selectionMode: String,
    ) : AgentRuntimeEvent
}
```

`AgentExecutor.execute(...)` расширить:

```kotlin
suspend fun execute(
    agentId: AgentId,
    context: AgentContext,
    input: String,
    eventSink: AgentRuntimeEventSink? = null,
): AgentExecutionResult
```

Backend создаёт sink на каждый execution:

```kotlin
val sink = BackendAgentRuntimeEventSink(
    userId = userId,
    chatId = chatId,
    executionId = executionId,
    eventBus = eventBus,
    toolCallRepository = toolCallRepository,
    choiceRepository = choiceRepository,
    redactor = redactor,
)
```

И вызывает:

```kotlin
executor.execute(
    agentId = activeAgentId,
    context = seedContext,
    input = userMessage.content,
    eventSink = sink,
)
```

## Message streaming behavior

Runtime event:

```text
LlmMessageDelta("abc")
```

Backend mapping:

```text
if assistant message not created:
  insert assistant message with empty content
  emit message.created

append delta to in-memory accumulator
emit message.delta
```

При завершении:

```text
finalContent = result.output
update assistant message content = finalContent
emit message.completed
```

Важно: persisted assistant message должен содержать финальный полный текст, а не набор delta.

## Tool event behavior

Runtime/tool layer emits started/finished/failed.

Backend:

```text
1. redacts arguments;
2. stores tool_calls row;
3. emits tool.call.started;
4. updates tool_calls row on finish/fail;
5. emits tool.call.finished/tool.call.failed.
```

Если `SOUZ_FEATURE_TOOL_EVENTS=false`, tool calls можно сохранять в audit, но не отправлять в WS.

## Choices behavior

Когда runtime запрашивает выбор:

```text
1. Backend создаёт choices row.
2. Execution переходит в waiting_choice.
3. Backend emits choice.requested.
4. Frontend показывает ChoiceCard.
5. User отправляет POST /v1/choices/{choiceId}/answer.
6. Backend проверяет:
   - choice belongs to user;
   - status=pending;
   - не истёк expires_at;
   - answer валиден.
7. Backend сохраняет answer.
8. Execution продолжается или создаётся continuation внутри текущего execution.
9. Emits choice.answered.
```

Для MVP можно делать continuation так:

```text
choice answer превращается во внутренний input для runtime continuation
```

Но снаружи это остаётся одним `agent_execution`.

## Agent state save

После успешного execution:

```text
1. Получить result.context.history.
2. Сериализовать в context_json.
3. based_on_message_seq = seq последнего сообщения, учтённого execution.
4. Сохранить через optimistic locking.
```

Если optimistic locking failed:

```text
1. Mark execution failed with state_conflict.
2. Emit execution.failed.
3. Не перетирать чужой context_json.
```

Но основной механизм защиты — one active execution per chat.

## Parallel execution protection

Сразу использовать heavy model:

```text
agent_executions_one_active_per_chat_idx
```

Логика:

```text
1. При создании execution вставка может упасть из-за unique active index.
2. В этом случае вернуть 409:
   chat_already_has_active_execution.
3. Cancel переводит running -> cancelling.
4. Завершение переводит:
   running -> completed
   cancelling -> cancelled
   running -> failed
   waiting_choice -> running/completed/failed
```

## Reconnect/replay

Каждое событие имеет `seq`.

```text
client stores lastSeq
WS reconnects with afterSeq
backend replays events where seq > afterSeq
then subscribes to live stream
```

Первый этап:

```text
in-memory ring buffer per chat
```

Production/debug:

```text
agent_events table
```

Если durable events выключены и buffer потерян:

```text
GET /v1/chats/{chatId}/messages
```

остаётся source of truth для UI history.

---

# 6. Требования безопасности

## Auth boundary

Auth не реализуется внутри агента.

Backend доверяет `X-User-Id` только если:

```text
1. запрос пришёл от trusted proxy;
2. backend недоступен напрямую извне;
3. proxy strip'ает identity headers от клиента;
4. backend проверяет proxy proof:
   - shared secret header;
   - или mTLS.
```

Минимальная production config:

```text
- backend bind на private network;
- firewall / Docker network / Kubernetes NetworkPolicy;
- proxy only access;
- X-Souz-Proxy-Auth secret from env/secret store;
- reject request without valid proxy proof.
```

## Запрет userId в body

Запрещено:

```json
{
  "userId": "..."
}
```

Любые body/query поля `userId` игнорировать или отклонять.

## Ownership checks

Каждый endpoint проверяет:

```text
chat.user_id == request.userId
message.user_id == request.userId
execution.user_id == request.userId
choice.user_id == request.userId
```

## API keys

Два режима:

### Server-managed keys

```text
- ключи в ENV / Docker secrets / Kubernetes secrets / Vault;
- пользователь выбирает только provider/model;
- per-user quotas обязательны.
```

### User-managed keys

```text
- plaintext принимается только при сохранении/обновлении;
- хранится encrypted_api_key;
- key_hint = последние 4–6 символов;
- master key из ENV/Vault/KMS;
- plaintext не логировать;
- plaintext не отправлять во frontend/events/tool_calls/OTel.
```

## Redaction

Перед записью в:

```text
logs
agent_events
tool_calls
OpenTelemetry
frontend payloads
```

надо redaction:

```text
api_key
token
authorization
cookie
password
secret
private_key
access_token
refresh_token
session
```

Tool arguments/result preview должны быть truncated и redacted.

## Raw thoughts / chain-of-thought

В web не делать панель мыслей.

Не стримить:

```text
raw hidden chain-of-thought
internal scratchpad
system prompt
private tool schemas
provider secrets
```

Разрешено стримить:

```text
assistant message deltas
tool call lifecycle events
choice events
execution lifecycle events
```

## Multi-user LLM client safety

Можно использовать общий HTTP client / connection pool, но нельзя хранить в singleton:

```text
currentUserId
currentApiKey
currentModel
currentPrompt
currentConversation
```

Per execution:

```text
userId
credentials
model
settings
context
eventSink
rate-limit bucket
executionId
```

Нужны лимиты:

```text
per-chat active execution = 1
per-user concurrent executions
per-user requests/minute
per-user token quota
global provider concurrency
provider backoff on 429
```

---

# 7. Этапы реализации

## Этап 1. Backend foundation и feature flags

Независимые задачи:

1. Добавить `BackendFeatureFlags`.
2. Добавить `StorageMode`.
3. Добавить `security/RequestIdentity`.
4. Добавить trusted proxy extraction:

    * `X-User-Id`;
    * `X-Souz-Proxy-Auth`;
    * reject direct/untrusted requests.
5. Убрать reliance на `userId` из новых `/v1/**` body.
6. Оставить `/agent` как legacy/debug endpoint.
7. Добавить `GET /v1/bootstrap`.

Acceptance criteria:

```text
- /v1/bootstrap возвращает user/settings/features.
- Без trusted proxy headers запросы отклоняются.
- Старый /agent не является основным API.
```

## Этап 2. Product storage interfaces

Независимые задачи:

1. Добавить модели:

    * `Chat`;
    * `ChatMessage`;
    * `AgentConversationState`;
    * `AgentExecution`;
    * `Choice`;
    * `AgentEvent`.
2. Добавить repository interfaces.
3. Реализовать memory repositories.
4. Реализовать `UserSettingsRepository`.
5. Реализовать `EffectiveSettingsResolver`.

Acceptance criteria:

```text
- memory mode проходит tests.
- settings можно читать/обновлять.
- chats/messages можно создавать и читать.
```

## Этап 3. Chat-oriented REST API

Независимые задачи:

1. `GET /v1/me/settings`.
2. `PATCH /v1/me/settings`.
3. `GET /v1/chats`.
4. `POST /v1/chats`.
5. `GET /v1/chats/{chatId}/messages`.
6. `POST /v1/chats/{chatId}/messages` без streaming.
7. Ownership checks.

Acceptance criteria:

```text
- пользователь видит только свои чаты;
- messages хранятся отдельно от agent state;
- agent state сохраняется отдельно;
- POST message запускает agent и создаёт assistant message.
```

## Этап 4. Heavy AgentExecution domain model

Независимые задачи:

1. Добавить `AgentExecutionRepository`.
2. Создавать execution на каждый user message.
3. Ввести statuses:

    * queued;
    * running;
    * waiting_choice;
    * cancelling;
    * cancelled;
    * completed;
    * failed.
4. Реализовать one active execution per chat.
5. Реализовать cancel endpoints.
6. Связать execution с user message и assistant message.

Acceptance criteria:

```text
- два параллельных POST messages в один chat дают 409;
- cancel переводит execution в cancelling/cancelled;
- assistant response связан с execution.
```

## Этап 5. Request-scoped event sink в agent/runtime

Независимые задачи:

1. Добавить `AgentRuntimeEventSink`.
2. Расширить `AgentExecutor.execute(...)` optional `eventSink`.
3. Передать sink в LLM streaming path.
4. Передать sink в tool execution path.
5. Не использовать shared `AgentFacade.sideEffects` в backend.
6. Backend adapter мапит runtime events в `AgentEvent`.

Acceptance criteria:

```text
- два одновременных execution в разных чатах/пользователях не смешивают events;
- message delta идёт только в правильный chat/execution;
- backend может полностью выключить streaming feature flag’ом.
```

## Этап 6. WebSocket, event bus, replay

Независимые задачи:

1. Добавить `AgentEventBus`.
2. Добавить per-chat seq generator.
3. Добавить in-memory replay buffer.
4. Добавить `WS /v1/chats/{chatId}/ws`.
5. Добавить `GET /v1/chats/{chatId}/events?afterSeq=`.
6. Добавить event broadcasting.
7. Добавить reconnect by `afterSeq`.

Acceptance criteria:

```text
- frontend получает message.created/delta/completed;
- reconnect получает пропущенные events;
- при выключенном WS feature flag endpoint недоступен или возвращает controlled error.
```

## Этап 7. Tool events

Независимые задачи:

1. Добавить tool event mapping:

    * started;
    * finished;
    * failed.
2. Добавить redaction.
3. Добавить `tool_calls` repository.
4. Добавить feature flag `SOUZ_FEATURE_TOOL_EVENTS`.
5. Отображать tool events в web UI panel.

Acceptance criteria:

```text
- tool calls видны в web UI;
- secrets не попадают в payload;
- duration/status/resultPreview сохраняются.
```

## Этап 8. Choices heavy domain model

Независимые задачи:

1. Добавить `ChoiceRepository`.
2. Добавить `ChoiceService`.
3. Добавить `choice.requested`.
4. Добавить `POST /v1/choices/{choiceId}/answer`.
5. Добавить execution status `waiting_choice`.
6. Реализовать validation:

    * ownership;
    * pending status;
    * expiry;
    * option IDs.
7. Реализовать continuation execution после ответа.

Acceptance criteria:

```text
- text_edit_variant работает через общий choices protocol;
- choice survives reconnect;
- повторный answer отклоняется;
- чужой choice недоступен.
```

## Этап 9. Filesystem storage mode

Статус: реализовано в `:backend`.

Реализованные задачи:

1. Реализовать filesystem repositories.
2. JSONL для messages/executions/choices/events.
3. Atomic write для settings/agent-state.
4. Tests на restart/load.
5. Config + DI support для `SOUZ_STORAGE_MODE=filesystem`.
6. `SOUZ_BACKEND_DATA_DIR` / `souz.backend.dataDir` с default `data/`.
7. Safe encoded user directory segment вместо raw opaque `userId`.

Acceptance criteria:

```text
- после restart чаты, messages, settings и agent state восстановлены;
- повреждение agent-state не ломает product messages;
- executions/choices/events и seq у messages/events переживают restart;
- legacy /agent AgentStateRepository round-trip работает и в filesystem mode;
- agent state можно будет пересобрать из messages в будущем.
```

## Этап 10. Postgres storage mode

Статус: реализовано.

Состав реализации:

1. Добавить JDBC/Hikari/Flyway/Testcontainers dependency stack.
2. Добавить migrations.
3. Реализовать Postgres repositories.
4. Реализовать optimistic locking для `agent_conversation_state`.
5. Реализовать unique active execution index.
6. Добавить durable `agent_events` optional.
7. Integration tests через Testcontainers.

Acceptance criteria:

```text
- production mode работает на Postgres;
- параллельные executions в одном chat невозможны;
- state conflict не перетирает context_json;
- replay может работать из DB при включённом durable events.
```

## Этап 11. LLM client isolation и quotas

Независимые задачи:

1. Добавить `LlmClientFactory`.
2. Разделить shared HTTP client и per-execution credentials.
3. Добавить server-managed keys.
4. Добавить user-managed encrypted keys.
5. Добавить per-user/global limits.
6. Добавить provider backoff/retry policy.
7. Добавить usage accounting в `agent_executions.usage_json`.

Acceptance criteria:

```text
- один пользователь не может съесть весь общий provider quota;
- credentials не протекают между users;
- API keys не попадают в logs/events/frontend.
```

## Этап 12. Web UI

Независимые задачи:

1. Создать `web/` React + TypeScript + Vite.
2. Реализовать bootstrap.
3. Реализовать chats list.
4. Реализовать messages pagination.
5. Реализовать composer.
6. Реализовать WebSocket client.
7. Реализовать streaming assistant bubble.
8. Реализовать tool calls panel.
9. Реализовать ChoiceCard.
10. Реализовать settings UI.
11. Подключить production static serving из backend.

Acceptance criteria:

```text
- пользователь открывает web UI;
- видит чаты;
- создаёт чат;
- отправляет сообщение;
- видит streaming assistant response;
- видит tool calls;
- отвечает на choices;
- настройки сохраняются на сервере.
```

---

# Итоговая рекомендация по реализации

Первым делом стоит сделать не Postgres и не web UI, а **server contract**:

```text
identity
feature flags
storage interfaces
chat/messages API
agent_executions
request-scoped event sink
WS event protocol
```

После этого web UI будет строиться поверх стабильного API, а storage modes можно добавлять постепенно: memory → filesystem → postgres.

[1]: https://raw.githubusercontent.com/D00mch/souz/main/settings.gradle.kts "raw.githubusercontent.com"
[2]: https://raw.githubusercontent.com/D00mch/souz/main/backend/src/main/kotlin/ru/souz/backend/http/BackendHttpServer.kt "raw.githubusercontent.com"
[3]: https://raw.githubusercontent.com/D00mch/souz/main/backend/src/main/kotlin/ru/souz/backend/agent/service/BackendAgentService.kt "raw.githubusercontent.com"
[4]: https://raw.githubusercontent.com/D00mch/souz/main/backend/src/main/kotlin/ru/souz/backend/agent/runtime/BackendConversationRuntime.kt "raw.githubusercontent.com"
[5]: https://raw.githubusercontent.com/D00mch/souz/main/backend/src/main/kotlin/ru/souz/backend/agent/session/AgentSessionRepository.kt "raw.githubusercontent.com"
[6]: https://raw.githubusercontent.com/D00mch/souz/main/agent/src/main/kotlin/ru/souz/agent/nodes/NodesLLM.kt "raw.githubusercontent.com"
[7]: https://raw.githubusercontent.com/D00mch/souz/main/agent/src/main/kotlin/ru/souz/agent/AgentFacade.kt "raw.githubusercontent.com"
[8]: https://github.com/D00mch/souz/tree/main/backend/src/main/kotlin/ru/souz/backend "souz/backend/src/main/kotlin/ru/souz/backend at main · D00mch/souz · GitHub"
