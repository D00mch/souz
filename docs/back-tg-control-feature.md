# Спецификация: привязка Telegram-бота к Souz-чату

## 1. Цель

Добавить возможность:

```text
Пользователь создает чат на фронте
→ указывает token Telegram-бота
→ backend сохраняет привязку bot token ↔ chat_id ↔ user_id
→ backend long-polling читает сообщения из Telegram
→ каждое text-сообщение отправляется в Souz chat как user message
→ агент выполняет задачу через существующий AgentExecutionService
```

Фича должна жить в `souz` backend, потому что backend поставляется как независимое решение. `souz-proxy` не должен содержать Telegram business logic.

Souz уже имеет HTTP backend с trusted-proxy auth, chat lifecycle, message execution, WebSocket streaming и несколькими storage-режимами, поэтому Telegram-интеграция должна подключаться к существующему backend-пайплайну, а не создавать отдельный агентный сценарий. ([GitHub][1])

---

## 2. Что делаем в MVP

В MVP делаем только:

```text
Telegram text message
→ Souz backend
→ user message в чат
→ запуск агента
```

Не делаем:

```text
allowedTelegramUserId
bot_username
webhook
группы
voice
files
ответ агента обратно в Telegram
настройки доступа по Telegram user id
```

Token считается доступом к чату. Если token утек — пользователь удаляет бота из чата.

---

## 3. Архитектура

```text
Frontend в souz-proxy
  → PUT /v1/chats/{chatId}/telegram-bot

souz-proxy backend
  → просто прокидывает /v1/** в souz backend
  → добавляет X-User-Id и X-Souz-Proxy-Auth

souz backend
  → проверяет chat ownership
  → сохраняет Telegram binding
  → polling service читает Telegram getUpdates
  → вызывает AgentExecutionService.executeChatTurn(...)
```

`proxy` уже реверс-проксирует HTTP `/v1/{...}` и добавляет trusted headers `X-User-Id` и `X-Souz-Proxy-Auth`, поэтому новые `/v1/chats/{chatId}/telegram-bot` endpoints можно реализовать только в backend, без отдельной логики в proxy. ([GitHub][2]) ([GitHub][2])

---

## 4. Backend: новые API endpoints

Добавить в `souz` backend:

```http
GET /v1/chats/{chatId}/telegram-bot
PUT /v1/chats/{chatId}/telegram-bot
DELETE /v1/chats/{chatId}/telegram-bot
```

### 4.1 GET

Возвращает состояние привязки без token.

```json
{
  "telegramBot": {
    "enabled": true,
    "createdAt": "2026-05-04T10:00:00Z",
    "updatedAt": "2026-05-04T10:00:00Z"
  }
}
```

Если бот не привязан:

```json
{
  "telegramBot": null
}
```

### 4.2 PUT

Создает или изменяет Telegram-бота для чата.

```json
{
  "token": "123456:ABCDEF"
}
```

Поведение:

```text
1. Взять userId из RequestIdentity / trusted proxy headers.
2. Проверить, что chatId принадлежит userId.
3. Проверить token через Telegram getMe.
4. Посчитать bot_token_hash.
5. Если этот token уже привязан к другому chat_id — вернуть 409.
6. Если у этого chat_id уже есть binding — заменить token.
7. Сбросить last_update_id.
8. Сохранить binding.
9. Polling service подхватит binding на следующем цикле.
```

Response:

```json
{
  "telegramBot": {
    "enabled": true,
    "createdAt": "2026-05-04T10:00:00Z",
    "updatedAt": "2026-05-04T10:00:00Z"
  }
}
```

### 4.3 DELETE

Удаляет Telegram-бота из чата.

```text
DELETE /v1/chats/{chatId}/telegram-bot
```

Поведение:

```text
1. Взять userId.
2. Проверить, что chatId принадлежит userId.
3. Удалить binding по userId + chatId.
4. Polling прекратится после текущего getUpdates цикла.
```

Response:

```json
{
  "telegramBot": null
}
```

---

## 5. Backend: модель данных

### 5.1 Таблица

```sql
create table telegram_bot_bindings (
    id uuid primary key,
    user_id text not null,
    chat_id uuid not null,

    bot_token text not null,
    bot_token_hash text not null,

    last_update_id bigint not null default 0,

    enabled boolean not null default true,

    created_at timestamp not null,
    updated_at timestamp not null,

    unique(chat_id),
    unique(bot_token_hash)
);
```

Для MVP можно хранить `bot_token` открыто. Следующий безопасный шаг — заменить на `bot_token_encrypted`.

### 5.2 Почему оставляем `user_id`

`ChatRepository` сейчас работает с чатом через пару `userId + chatId`, поэтому хранение `user_id` в binding позволяет без расширения всех repository-реализаций запускать агентный turn напрямую. ([GitHub][3])

`AgentExecutionService.executeChatTurn(...)` уже принимает `userId`, `chatId`, `content` и `clientMessageId`, проверяет ownership чата через `requireOwnedChat`, создает execution и обрабатывает конфликт активного запуска. ([GitHub][4])

---

## 6. Backend: новые классы

```text
backend/src/main/kotlin/ru/souz/backend/telegram/
  TelegramBotBinding.kt
  TelegramBotBindingRepository.kt
  MemoryTelegramBotBindingRepository.kt
  FileTelegramBotBindingRepository.kt
  PostgresTelegramBotBindingRepository.kt
  TelegramBotBindingService.kt
  TelegramBotPollingService.kt
  TelegramBotApi.kt
  TelegramRoutes.kt
```

Если в проекте уже есть общий паттерн `memory/filesystem/postgres`, сделать реализации repository в том же стиле. Backend уже заявлен как поддерживающий memory/filesystem/Postgres storage. ([GitHub][1])

---

## 7. Backend: TelegramBotApi

Пока скопировать из desktop-реализации низкоуровневые DTO и API-клиент.

Нужный минимум:

```kotlin
interface TelegramBotApi {
    suspend fun getMe(token: String): TelegramGetMeResponse

    suspend fun getUpdates(
        token: String,
        offset: Long?,
        timeoutSeconds: Int = 30,
        allowedUpdates: List<String> = listOf("message"),
    ): TelegramUpdatesResponse

    suspend fun sendMessage(
        token: String,
        chatId: Long,
        text: String,
    )
}
```

Telegram Bot API официально поддерживает получение updates через `getUpdates` или webhook; для MVP нужен именно `getUpdates`, потому что он работает через long polling и не требует публичного callback URL. ([Telegram][5])

`getUpdates` принимает `offset`, `timeout` и `allowed_updates`; Telegram отдельно рекомендует пересчитывать offset после ответа, чтобы избежать дублей. ([Telegram][5])

`sendMessage` можно добавить сразу, но использовать только для коротких технических ответов вроде “Принял, выполняю.” Telegram требует `chat_id` и `text` для отправки текстового сообщения. ([Telegram][5])

---

## 8. Backend: polling service

### 8.1 Общая логика

```kotlin
class TelegramBotPollingService(
    private val repository: TelegramBotBindingRepository,
    private val botApi: TelegramBotApi,
    private val executionService: AgentExecutionService,
    private val scope: BackendApplicationScope,
) {
    fun start() {
        scope.launch {
            while (isActive) {
                val bindings = repository.listEnabled()

                for (binding in bindings) {
                    launch {
                        pollBinding(binding)
                    }
                }

                delay(1_000)
            }
        }
    }

    private suspend fun pollBinding(binding: TelegramBotBinding) {
        val updates = botApi.getUpdates(
            token = binding.botToken,
            offset = binding.lastUpdateId + 1,
            timeoutSeconds = 30,
            allowedUpdates = listOf("message"),
        )

        for (update in updates.result) {
            repository.updateLastUpdateId(binding.id, update.updateId)

            val message = update.message ?: continue
            val text = message.text?.trim().orEmpty()
            if (text.isBlank()) continue

            executionService.executeChatTurn(
                userId = binding.userId,
                chatId = binding.chatId,
                content = text,
                clientMessageId = "telegram:${update.updateId}",
            )

            botApi.sendMessage(
                token = binding.botToken,
                chatId = message.chat.id,
                text = "Принял, выполняю."
            )
        }
    }
}
```

### 8.2 Важные детали

`last_update_id` обновлять лучше **до** запуска агента. Тогда если агент упал, Telegram update не будет бесконечно переигрываться.

`clientMessageId = "telegram:${update.updateId}"` нужен для идемпотентности на уровне message/execution pipeline, если она уже используется или будет добавлена.

Если `executeChatTurn` вернул `409 chat_already_has_active_execution`, отправить в Telegram:

```text
В этом чате уже выполняется задача. Попробуй позже.
```

`executeChatTurn` уже создает user message, обновляет chat timestamp, переводит execution в `RUNNING` и запускает background execution, если текущие настройки требуют асинхронного выполнения. ([GitHub][4])

---

## 9. Backend: validation и ошибки

### PUT errors

```text
400 invalid_telegram_bot_token
Telegram getMe не прошел.

404 chat_not_found
Чат не найден или не принадлежит userId.

409 telegram_bot_already_bound
Этот bot token уже привязан к другому чату.

500 telegram_bot_bind_failed
Неожиданная ошибка сохранения/валидации.
```

### Polling errors

```text
401/403 от Telegram
→ пометить binding disabled=false или оставить enabled=true?
```

Для MVP лучше:

```text
enabled = false
last_error = "telegram_unauthorized"
```

Но если не хочется расширять таблицу, можно только логировать ошибку и продолжать retry. Я бы добавил поля:

```sql
last_error text null,
last_error_at timestamp null
```

Это сильно помогает в UI.

---

## 10. Frontend в `souz-proxy`

Фронт сейчас уже ходит в `/v1/chats` через общий API wrapper. Например, `getChats`, `createChat`, `updateChatTitle`, `archiveChat` используют `/v1/chats...` endpoints. ([GitHub][6])

Добавить API-файл или расширить `frontend/src/api/chats.ts`:

```ts
export type TelegramBotBindingDto = {
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
  lastError?: string | null;
  lastErrorAt?: string | null;
};

export async function getChatTelegramBot(
  chatId: string
): Promise<TelegramBotBindingDto | null> {
  const response = await apiGet<{ telegramBot: TelegramBotBindingDto | null }>(
    `/v1/chats/${chatId}/telegram-bot`
  );

  return response.telegramBot;
}

export async function upsertChatTelegramBot(
  chatId: string,
  token: string
): Promise<TelegramBotBindingDto> {
  const response = await apiPut<{ telegramBot: TelegramBotBindingDto }>(
    `/v1/chats/${chatId}/telegram-bot`,
    { token }
  );

  return response.telegramBot;
}

export async function deleteChatTelegramBot(
  chatId: string
): Promise<void> {
  await apiDelete(`/v1/chats/${chatId}/telegram-bot`);
}
```

Если сейчас нет `apiPut` / `apiDelete`, добавить их рядом с `apiGet`, `apiPost`, `apiPatch`.

### UI

В настройках чата добавить блок:

```text
Telegram bot

[ input: Bot token ]

[Save / Update bot]
[Remove bot]
```

Состояния:

```text
Not connected
Connected
Saving
Removing
Error
```

Текст в UI:

```text
Вставьте token Telegram-бота. Все текстовые сообщения, отправленные этому боту, будут попадать в этот Souz-чат как сообщения пользователя.
```

После сохранения token не показывать. Показывать только:

```text
Telegram bot connected
```

Для изменения — пользователь просто вставляет новый token и нажимает “Update”.

---

## 11. `souz-proxy` backend

Специальных доработок почти нет.

Нужно только убедиться, что:

```text
PUT /v1/chats/{chatId}/telegram-bot
GET /v1/chats/{chatId}/telegram-bot
DELETE /v1/chats/{chatId}/telegram-bot
```

проходят через существующий reverse proxy.

Текущий proxy уже матчится на `/v1/{...}` и отправляет запрос в backend, вырезая опасные headers и добавляя trusted identity headers. ([GitHub][2])

Если в proxy frontend HTTP wrapper нет `PUT`/`DELETE`, это изменение frontend-клиента, а не backend proxy.

---

## 12. Поэтапная реализация

### Этап 1. Backend storage и API shell

Сделать:

```text
TelegramBotBinding model
TelegramBotBindingRepository interface
Memory/File/Postgres implementations
migration для Postgres
GET / PUT / DELETE routes
```

Acceptance criteria:

```text
GET возвращает null для чата без бота.
PUT сохраняет token binding.
PUT повторно на тот же chat заменяет token.
DELETE удаляет binding.
PUT с token, уже привязанным к другому chat, возвращает 409.
```

---

### Этап 2. TelegramBotApi

Сделать:

```text
Скопировать минимальный TelegramBotApi из desktop.
Оставить только getMe, getUpdates, sendMessage.
Добавить DTO только для нужных полей.
```

DTO минимум:

```kotlin
data class TelegramUpdatesResponse(
    val ok: Boolean,
    val result: List<TelegramUpdate> = emptyList(),
)

data class TelegramUpdate(
    @SerialName("update_id")
    val updateId: Long,
    val message: TelegramMessage? = null,
)

data class TelegramMessage(
    @SerialName("message_id")
    val messageId: Long,
    val chat: TelegramChat,
    val text: String? = null,
)

data class TelegramChat(
    val id: Long,
    val type: String? = null,
)
```

Acceptance criteria:

```text
PUT проверяет token через getMe.
Невалидный token возвращает 400.
Валидный token сохраняется.
```

---

### Этап 3. Polling service

Сделать:

```text
TelegramBotPollingService
старт сервиса при запуске backend application
poll enabled bindings
getUpdates(offset = last_update_id + 1, timeout = 30)
text message -> executeChatTurn(...)
```

Acceptance criteria:

```text
Сообщение в Telegram попадает в Souz chat как USER message.
Агент запускается так же, как при обычном сообщении из UI.
last_update_id обновляется.
Повторный polling не дублирует уже обработанные Telegram messages.
```

---

### Этап 4. Frontend

Сделать:

```text
API методы get/upsert/delete Telegram bot binding.
UI block в настройках чата.
Save / Update / Remove flow.
```

Acceptance criteria:

```text
Можно привязать token к чату.
Можно заменить token.
Можно удалить token.
После удаления сообщения старому боту больше не попадают в чат.
Token после сохранения не отображается.
```

---

### Этап 5. Ошибки и polish

Сделать:

```text
last_error / last_error_at в binding
корректные error codes
логирование polling ошибок
короткие Telegram replies:
  "Принял, выполняю."
  "В этом чате уже выполняется задача. Попробуй позже."
  "Не удалось выполнить команду."
```

Acceptance criteria:

```text
Пользователь видит ошибку привязки token.
Polling не валит весь backend при ошибке одного bot token.
Ошибка одного бота не останавливает polling других ботов.
```

---

## 13. Итоговый минимальный контракт

Главный backend endpoint:

```http
PUT /v1/chats/{chatId}/telegram-bot
Content-Type: application/json

{
  "token": "123456:ABCDEF"
}
```

Главный backend action при Telegram message:

```kotlin
executionService.executeChatTurn(
    userId = binding.userId,
    chatId = binding.chatId,
    content = telegramMessage.text,
    clientMessageId = "telegram:${update.updateId}",
)
```

Главное правило архитектуры:

```text
Telegram-интеграция — часть souz backend.
souz-proxy backend — без Telegram business logic.
Frontend в proxy — только UI и вызовы /v1/chats/{chatId}/telegram-bot.
```
Да — я бы добавил в спецификацию отдельный раздел **“Жесткий контракт API и runtime semantics”**. Ниже готовый текст, который можно почти напрямую вставить в документ.

---

# 14. Жесткий контракт API

## 14.1 Общие правила

Все endpoints живут только в `souz backend`:

```http
GET    /v1/chats/{chatId}/telegram-bot
PUT    /v1/chats/{chatId}/telegram-bot
DELETE /v1/chats/{chatId}/telegram-bot
```

`souz-proxy` не содержит Telegram business logic. Это соответствует текущей архитектуре proxy: он уже матчит HTTP `/v1/{...}`, вырезает опасные headers и добавляет `X-User-Id` / `X-Souz-Proxy-Auth` перед проксированием в backend. ([GitHub][1])

`userId` никогда не передается в body, path или query. Backend берет его только из `RequestIdentity`, сформированного trusted-proxy auth.

`chatId` должен быть UUID. Если `chatId` не парсится как UUID:

```http
400 bad_request
```

Если чат не найден или не принадлежит пользователю:

```http
404 chat_not_found
```

Такой контракт совпадает с текущим ownership-паттерном: `ChatRepository.get(userId, chatId)` ищет чат по паре `userId + chatId`, а `AgentExecutionService.requireOwnedChat` возвращает `404 chat_not_found`. ([GitHub][2])

---

## 14.2 Response DTO

Backend response всегда использует envelope:

```kotlin
@Serializable
data class TelegramBotBindingResponse(
    val telegramBot: TelegramBotBindingDto?
)

@Serializable
data class TelegramBotBindingDto(
    val enabled: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastError: String? = null,
    val lastErrorAt: Instant? = null,
)
```

В response запрещено возвращать:

```text
bot_token
bot_token_hash
last_update_id
telegram chat id
telegram user id
```

`lastError` — machine-readable code, не human-readable текст.

Допустимые значения `lastError` для MVP:

```text
telegram_unauthorized
telegram_conflict_webhook_enabled
telegram_rate_limited
telegram_network_error
telegram_unknown_error
```

---

## 14.3 Error envelope

Все ошибки Telegram binding API возвращаются в стандартном v1 error envelope:

```json
{
  "error": {
    "code": "telegram_bot_already_bound",
    "message": "Telegram bot is already bound to another chat."
  }
}
```

Коды должны быть стабильными, потому что frontend будет строить UI именно по `error.code`.

---

# 15. GET /v1/chats/{chatId}/telegram-bot

## Request

```http
GET /v1/chats/8f2c5a4d-6d5c-4c6e-bc83-50c5a4a72b2a/telegram-bot
```

Body отсутствует.

## Success: binding exists

```http
200 OK
Content-Type: application/json
```

```json
{
  "telegramBot": {
    "enabled": true,
    "createdAt": "2026-05-04T10:00:00Z",
    "updatedAt": "2026-05-04T10:00:00Z",
    "lastError": null,
    "lastErrorAt": null
  }
}
```

## Success: binding does not exist

```http
200 OK
Content-Type: application/json
```

```json
{
  "telegramBot": null
}
```

## Errors

```text
400 bad_request       chatId is not UUID
404 chat_not_found    chat does not exist or is not owned by current user
```

---

# 16. PUT /v1/chats/{chatId}/telegram-bot

## Request

```http
PUT /v1/chats/8f2c5a4d-6d5c-4c6e-bc83-50c5a4a72b2a/telegram-bot
Content-Type: application/json
```

```json
{
  "token": "123456:ABCDEF"
}
```

## Request DTO

```kotlin
@Serializable
data class UpsertTelegramBotBindingRequest(
    val token: String
)
```

Validation:

```text
token required
token must be string
token.trim() must not be blank
token length <= 4096
```

Не надо делать жесткую regex-валидацию формата token. Источником истины является Telegram `getMe`: Telegram Bot API описывает `getMe` как метод для проверки authentication token, который не требует параметров и возвращает basic info about the bot. ([Telegram][3])

## Success

`PUT` всегда возвращает `200 OK`, и для create, и для update. Это upsert endpoint.

```http
200 OK
Content-Type: application/json
```

```json
{
  "telegramBot": {
    "enabled": true,
    "createdAt": "2026-05-04T10:00:00Z",
    "updatedAt": "2026-05-04T10:05:00Z",
    "lastError": null,
    "lastErrorAt": null
  }
}
```

## Required behavior

```text
1. Extract userId from RequestIdentity.
2. Parse chatId as UUID.
3. Verify chat ownership via ChatRepository.get(userId, chatId).
4. Trim token.
5. Validate token via TelegramBotApi.getMe(token).
6. If Telegram getMe returns ok=false or HTTP 401/403:
   return 400 invalid_telegram_bot_token.
7. Compute bot_token_hash.
8. If bot_token_hash exists for another chat_id:
   return 409 telegram_bot_already_bound.
9. If binding exists for this chat_id:
   replace token, replace bot_token_hash, set last_update_id = 0.
10. If binding does not exist:
   create binding with last_update_id = 0.
11. Set enabled = true.
12. Clear last_error and last_error_at.
13. Return TelegramBotBindingResponse.
```

## Errors

```text
400 bad_request
Invalid JSON, missing token, blank token, token too long, invalid chatId UUID.

400 invalid_telegram_bot_token
Telegram getMe failed because token is invalid or unauthorized.

404 chat_not_found
Chat does not exist or is not owned by current user.

409 telegram_bot_already_bound
Same bot token is already bound to another chat.

500 telegram_bot_bind_failed
Unexpected storage or validation failure.
```

`409 telegram_bot_already_bound` срабатывает только если token уже привязан к **другому** `chat_id`.

Повторный `PUT` того же token в тот же chat — успешный idempotent update.

---

# 17. DELETE /v1/chats/{chatId}/telegram-bot

## Request

```http
DELETE /v1/chats/8f2c5a4d-6d5c-4c6e-bc83-50c5a4a72b2a/telegram-bot
```

Body отсутствует.

## Success

`DELETE` должен быть idempotent.

Если binding был — удалить.

Если binding не было — все равно вернуть success после проверки ownership.

```http
200 OK
Content-Type: application/json
```

```json
{
  "telegramBot": null
}
```

## Required behavior

```text
1. Extract userId from RequestIdentity.
2. Parse chatId as UUID.
3. Verify chat ownership.
4. Delete binding by chatId.
5. Return { "telegramBot": null }.
```

## Errors

```text
400 bad_request
Invalid chatId UUID.

404 chat_not_found
Chat does not exist or is not owned by current user.

500 telegram_bot_delete_failed
Unexpected storage failure.
```

---

# 18. Storage contract

## 18.1 SQL schema

Лучше сразу добавить `last_error` и `last_error_at`, чтобы UI мог показывать degraded state.

```sql
create table telegram_bot_bindings (
    id uuid primary key,
    user_id text not null,
    chat_id uuid not null,

    bot_token text not null,
    bot_token_hash text not null,

    last_update_id bigint not null default 0,

    enabled boolean not null default true,
    last_error text null,
    last_error_at timestamp null,

    created_at timestamp not null,
    updated_at timestamp not null,

    unique(chat_id),
    unique(bot_token_hash)
);

create index telegram_bot_bindings_enabled_idx
    on telegram_bot_bindings(enabled);

create index telegram_bot_bindings_user_chat_idx
    on telegram_bot_bindings(user_id, chat_id);
```

## 18.2 Repository interface

```kotlin
interface TelegramBotBindingRepository {
    suspend fun getByChat(chatId: UUID): TelegramBotBinding?

    suspend fun getByUserAndChat(
        userId: String,
        chatId: UUID,
    ): TelegramBotBinding?

    suspend fun findByTokenHash(
        botTokenHash: String,
    ): TelegramBotBinding?

    suspend fun listEnabled(): List<TelegramBotBinding>

    suspend fun upsertForChat(
        userId: String,
        chatId: UUID,
        botToken: String,
        botTokenHash: String,
        now: Instant,
    ): TelegramBotBinding

    suspend fun deleteByChat(
        chatId: UUID,
    )

    suspend fun updateLastUpdateId(
        id: UUID,
        lastUpdateId: Long,
        updatedAt: Instant = Instant.now(),
    )

    suspend fun markError(
        id: UUID,
        lastError: String,
        lastErrorAt: Instant = Instant.now(),
        disable: Boolean = false,
    )

    suspend fun clearError(
        id: UUID,
        updatedAt: Instant = Instant.now(),
    )
}
```

---

# 19. TelegramBotApi contract

Telegram Bot API принимает Bot API запросы по HTTPS в формате `https://api.telegram.org/bot<token>/METHOD_NAME`, а response содержит `ok`; при неуспехе может содержать `description` и `error_code`. ([Telegram][3])

```kotlin
interface TelegramBotApi {
    suspend fun getMe(token: String): TelegramGetMeResponse

    suspend fun getUpdates(
        token: String,
        offset: Long?,
        timeoutSeconds: Int = 30,
        allowedUpdates: List<String> = listOf("message"),
    ): TelegramUpdatesResponse

    suspend fun sendMessage(
        token: String,
        chatId: Long,
        text: String,
    )
}
```

`getUpdates` должен использовать `offset = last_update_id + 1`, `timeout = 30`, `allowed_updates = ["message"]`. Telegram официально описывает `getUpdates` как long polling метод, а `offset` должен быть на 1 больше максимального ранее полученного `update_id`; документация также рекомендует пересчитывать offset после ответа, чтобы избежать дублей. ([Telegram][3])

`sendMessage` в MVP используется только для коротких технических ответов. Telegram требует `chat_id` и `text`, а `text` ограничен 1–4096 символами после parsing. ([Telegram][3])

---

# 20. Polling contract

## 20.1 Processing semantics

Для каждого enabled binding:

```text
offset = binding.lastUpdateId + 1
timeoutSeconds = 30
allowedUpdates = ["message"]
```

Для каждого update:

```text
1. Сначала сохранить last_update_id = update.update_id.
2. Если update.message отсутствует — skip.
3. Если message.text отсутствует или blank после trim — skip.
4. Вызвать AgentExecutionService.executeChatTurn(...).
5. Если executeChatTurn успешен — отправить "Принял, выполняю."
6. Если executeChatTurn вернул 409 chat_already_has_active_execution —
   отправить "В этом чате уже выполняется задача. Попробуй позже."
7. Для любых прочих ошибок executeChatTurn —
   залогировать и отправить "Не удалось выполнить команду."
```

`last_update_id` обновляется **до** запуска агента. Это намеренный контракт: если агент упал, Telegram update не переигрывается бесконечно.

## 20.2 Execution call

```kotlin
executionService.executeChatTurn(
    userId = binding.userId,
    chatId = binding.chatId,
    content = text,
    clientMessageId = "telegram:${update.updateId}",
)
```

Это подключается к существующему pipeline: `executeChatTurn` уже проверяет ownership, создает execution, мапит конфликт активного запуска в `409 chat_already_has_active_execution`, добавляет USER message, обновляет timestamp чата и переводит execution в `RUNNING`. ([GitHub][4])

## 20.3 Error handling

```text
Telegram 401/403:
  markError(lastError = "telegram_unauthorized", disable = true)

Telegram 409 webhook conflict:
  markError(lastError = "telegram_conflict_webhook_enabled", disable = false)

Telegram 429:
  markError(lastError = "telegram_rate_limited", disable = false)
  respect retry_after if available

Network timeout / IOException:
  markError(lastError = "telegram_network_error", disable = false)

Unexpected Telegram response:
  markError(lastError = "telegram_unknown_error", disable = false)
```

Ошибка одного binding не должна останавливать polling других binding.

---

# 21. Frontend API contract

В `souz-proxy frontend` добавить методы рядом с текущими chat API. Сейчас `chats.ts` импортирует `apiGet`, `apiPost`, `apiPatch` и реализует chat endpoints поверх `/v1/chats`; значит Telegram методы можно добавить в тот же API слой, а `apiPut` / `apiDelete` добавить в HTTP wrapper, если их еще нет. ([GitHub][5])

```ts
export type TelegramBotBindingDto = {
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
  lastError?: string | null;
  lastErrorAt?: string | null;
};

export async function getChatTelegramBot(
  chatId: string
): Promise<TelegramBotBindingDto | null> {
  const response = await apiGet<{
    telegramBot: TelegramBotBindingDto | null;
  }>(`/v1/chats/${chatId}/telegram-bot`);

  return response.telegramBot;
}

export async function upsertChatTelegramBot(
  chatId: string,
  token: string
): Promise<TelegramBotBindingDto> {
  const response = await apiPut<{
    telegramBot: TelegramBotBindingDto;
  }>(`/v1/chats/${chatId}/telegram-bot`, { token });

  return response.telegramBot;
}

export async function deleteChatTelegramBot(
  chatId: string
): Promise<void> {
  await apiDelete<{ telegramBot: null }>(
    `/v1/chats/${chatId}/telegram-bot`
  );
}
```

Frontend не должен хранить token после успешного save. После `PUT` поле input очищается.

---

# 22. Финальное зафиксированное решение

Я бы явно зафиксировал три продуктовых решения:

```text
1. DELETE idempotent:
   удаление несуществующей привязки возвращает 200 { telegramBot: null }.

2. PUT idempotent для того же token + chat:
   повторное сохранение того же token в тот же chat возвращает 200.

3. 401/403 от Telegram в polling:
   binding выключается: enabled = false, last_error = "telegram_unauthorized".
```

Это убирает главные двусмысленности для backend, frontend и тестов.
