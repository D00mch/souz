Ниже спецификация, которую можно прямо положить в issue/документ и раздать Codex по независимым задачам. Я разделил так, чтобы каждая задача была максимально локальной и не требовала большого рефакторинга всего PR.

# Спецификация исправлений PR #528

## Общие правила для всех задач

Каждая задача должна выполняться как маленький сфокусированный change set.

Общие ограничения:

```text
- Не делать unrelated refactoring.
- Не переписывать весь HTTP/backend слой.
- Не добавлять новые большие архитектурные слои.
- Не менять публичный API без необходимости.
- Сохранять соответствие AGENTS.md, backend/AGENTS.md и docs/server-agent-concept.md.
- Все security-sensitive данные должны быть redacted/truncated до попадания в storage, events, logs и frontend.
- После каждой задачи должны проходить релевантные тесты.
```

---

# Task 1 — Refactor ToolCallRepository to ToolCallContext and String ids

## Цель

Убрать повторение аргументов:

```kotlin
userId, chatId, executionId, toolCallId
```

из всех методов `ToolCallRepository` и связанных реализаций. Одновременно убрать `java.util.UUID` из API tool-call слоя, используя string ids.

## Scope

Затронуть только tool-call код:

```text
- ToolCallRepository
- ToolCallRepository implementations
- Tool call models/entities
- BackendAgentRuntimeEventSink call sites
- Tool-call related tests
- Postgres/filesystem/in-memory storage code, если он использует ToolCallRepository
```

## Требуемое изменение

Добавить маленький context class:

```kotlin
data class ToolCallContext(
    val userId: String,
    val chatId: String,
    val executionId: String,
    val toolCallId: String,
)
```

Обновить интерфейс примерно так:

```kotlin
interface ToolCallRepository {
    suspend fun started(
        context: ToolCallContext,
        name: String,
        argumentsPreview: JsonNode,
        startedAt: Instant,
    )

    suspend fun finished(
        context: ToolCallContext,
        resultPreview: String?,
        finishedAt: Instant,
        durationMs: Long,
    )

    suspend fun failed(
        context: ToolCallContext,
        error: String,
        finishedAt: Instant,
        durationMs: Long,
    )
}
```

## Важное требование по `UUID`

Не использовать `java.util.UUID` в `ToolCallRepository` API.

Если где-то upstream runtime генерирует `UUID`, конвертировать его в строку на границе sink/repository:

```kotlin
toolCallId = event.toolCallId.toString()
```

## DB/storage consistency

Проверить `tool_calls` migration/schema.

Если проектная модель ids — opaque strings, предпочтительно хранить:

```sql
chat_id text not null
execution_id text not null
tool_call_id text not null
```

Если существующие таблицы уже используют `uuid`, допустим временный вариант с explicit conversion только внутри Postgres repository, но не в публичном `ToolCallRepository` API.

## Out of scope

```text
- Не вводить ChatId/ExecutionId/ToolCallId value classes.
- Не менять message/chat repository API.
- Не делать общий id framework.
```

## Tests

Обновить существующие тесты tool events.

Проверить:

```text
- started/finished/failed используют один и тот же ToolCallContext.
- redaction не сломался.
- raw secrets не попадают в stored tool_calls.
- компиляция не содержит java.util.UUID в ToolCallRepository API.
```

## Acceptance criteria

```text
- ToolCallRepository методы принимают ToolCallContext.
- В tool-call repository API нет java.util.UUID.
- Сигнатуры стали короче.
- Все существующие tool-call tests проходят.
```

---

# Task 2 — Fix Flyway migration numbering

## Цель

Устранить дублирующиеся версии Flyway migrations.

## Проблема

В PR есть несколько migration files с одинаковой версией `V2`. Это может сломать Flyway validation и fresh migration.

## Scope

Только migration filenames и, если нужно, тесты/документация storage bootstrap.

## Требуемое изменение

Привести порядок migrations к уникальной последовательности.

Пример:

```text
V1__stage10_postgres_storage.sql
V2__stage11_llm_client_isolation.sql
V3__tool_calls.sql
V4__stage12_option_rename.sql
```

Фактические имена выбрать по текущему состоянию ветки, но версии должны быть уникальными и монотонными.

## Out of scope

```text
- Не менять SQL-схему без необходимости.
- Не добавлять новые migrations кроме переименования/перенумерации текущих.
```

## Tests

Прогнать fresh Postgres migration с пустой БД.

Проверить:

```text
- Flyway не падает на duplicate version.
- Все таблицы создаются.
- tool_calls table существует.
- существующие repository tests проходят.
```

## Acceptance criteria

```text
- Нет двух файлов с одинаковым V-number.
- Fresh migration from scratch проходит.
- Existing migration tests проходят.
```

---

# Task 5 — Implement auto-provision user-id at identity boundary

## Цель

Если proxy успешно авторизовал пользователя и передал trusted `X-User-Id`, backend должен автоматически создать локальную запись пользователя, если её ещё нет.

Это не auth check. Auth уже выполнен proxy. Backend только заводит локальный namespace/registry пользователя.

## Scope

```text
- RequestIdentityResolver или ближайший identity boundary
- UserRepository / UserRegistry
- Postgres user storage
- filesystem/in-memory user storage, если есть такие modes
- tests for first request by new user
```

## Требуемая модель

Flow должен быть таким:

```text
1. Verify X-Souz-Proxy-Auth.
2. Extract trusted X-User-Id.
3. Validate X-User-Id shape.
4. ensureUser(userId).
5. Return RequestIdentity(userId).
6. Все дальнейшие операции используют этот userId.
```

## UserRepository API

Добавить минимальный интерфейс:

```kotlin
interface UserRepository {
    suspend fun ensureUser(userId: String): UserRecord
}
```

Модель:

```kotlin
data class UserRecord(
    val id: String,
    val createdAt: Instant,
    val lastSeenAt: Instant?,
)
```

Если уже есть user model — использовать существующую.

## Shape validation

Даже trusted header должен проходить минимальную защитную проверку:

```text
- userId не blank;
- userId length <= 256;
- no ISO control characters;
- no path separators if raw value где-то может попасть в filesystem;
- filesystem storage обязан использовать encoded/safe path segment, не raw userId.
```

Пример:

```kotlin
private fun validateTrustedUserIdShape(userId: String) {
    if (userId.isBlank()) throw invalidIdentity(...)
    if (userId.length > 256) throw invalidIdentity(...)
    if (userId.any { it.isISOControl() }) throw invalidIdentity(...)
}
```

## Postgres implementation

Использовать upsert:

```sql
insert into users (id, created_at, last_seen_at)
values (?, now(), now())
on conflict (id) do update
set last_seen_at =
  case
    when users.last_seen_at is null
      or users.last_seen_at < now() - interval '10 minutes'
    then now()
    else users.last_seen_at
  end
returning id, created_at, last_seen_at;
```

## Filesystem implementation

Создавать:

```text
data/users/{encodedUserId}/user.json
```

Где `{encodedUserId}` — safe encoded segment.

`user.json`:

```json
{
  "id": "original-id-from-proxy",
  "createdAt": "...",
  "lastSeenAt": "..."
}
```

## Important

Auto-provision должен происходить **до** создания chats/messages/settings/provider keys, чтобы все сущности были привязаны к существующему user namespace.

## Out of scope

```text
- Не добавлять роли/permissions.
- Не проверять пользователя во внешнем auth provider.
- Не reject’ить неизвестного userId, если proxy auth валиден.
- Не делать admin user management UI.
```

## Tests

Добавить тесты:

```text
1. First request with valid proxy token and new X-User-Id creates user.
2. Second request with same X-User-Id не создаёт дубликат.
3. Blank X-User-Id rejected.
4. Too long X-User-Id rejected.
5. Control chars in X-User-Id rejected.
6. Invalid proxy token не вызывает ensureUser.
7. Created chat/message/settings are namespaced under provisioned user.
8. Filesystem mode uses encoded path segment, not raw userId.
```

## Acceptance criteria

```text
- Backend auto-provisions user after trusted proxy auth.
- Unknown userId no longer fails just because it is unknown.
- Invalid/malicious userId shape fails.
- ensureUser is called exactly at identity boundary, not scattered randomly.
```
