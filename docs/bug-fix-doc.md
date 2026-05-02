Ниже — готовая спецификация в формате, который можно почти напрямую копировать в coding agent. Я разбил её на независимые задачи. В каждой задаче есть контекст, цель, конкретные файлы, ожидаемые изменения, ограничения и критерии приёмки.

---

# Спецификация исправлений для PR `server-agent`

## Общие правила для всех задач

Работать в текущем PR/ветке `server-agent`.

Основные цели:

* уменьшить объём самописного и шумного кода;
* убрать риски OOM/DoS;
* не сохранять transient streaming deltas в durable storage;
* отделить durable события от live-only WebSocket событий;
* усилить security вокруг tool events;
* упростить HTTP слой;
* сохранить соответствие `AGENTS.md` и `docs/server-agent-concept.md`.

Не менять публичную бизнес-логику без необходимости. Существующие tests должны проходить. Если тестов на изменяемую область нет — добавить минимальные unit/integration tests.

---

# Task 1. Extract `AgentEventBus` из `AgentEventService` и ввести bounded live buffer

## Prompt для coding agent

Нужно отрефакторить event subsystem.

Сейчас `backend/src/main/kotlin/ru/souz/backend/events/service/AgentEventService.kt` содержит не только application service, но и in-memory event bus / stream / subscription модели. Это смешивает ответственности и усложняет поддержку.

Также сейчас live WebSocket subscriptions используют `Channel<AgentEvent>(Channel.UNLIMITED)`, что создаёт риск OOM при медленном клиенте. Нужно заменить unbounded channel на bounded channel с drop policy.

### Цель

1. Вынести `AgentEventBus` и связанные модели из `AgentEventService.kt`.
2. Убрать `Channel.UNLIMITED`.
3. Ввести отдельные event limits, не привязанные напрямую к repository default limit.
4. Сохранить текущее поведение publish/subscribe, кроме того, что live buffer теперь bounded.

### Файлы

Основные:

```text
backend/src/main/kotlin/ru/souz/backend/events/service/AgentEventService.kt
backend/src/main/kotlin/ru/souz/backend/events/repository/AgentEventRepository.kt
```

Создать новые файлы примерно так:

```text
backend/src/main/kotlin/ru/souz/backend/events/bus/AgentEventBus.kt
backend/src/main/kotlin/ru/souz/backend/events/bus/AgentEventStream.kt
backend/src/main/kotlin/ru/souz/backend/events/bus/AgentEventSubscription.kt
backend/src/main/kotlin/ru/souz/backend/events/bus/AgentEventStreamKey.kt
backend/src/main/kotlin/ru/souz/backend/events/bus/AgentEventLimits.kt
```

Если проектная структура уже использует другой package style — следовать текущему стилю.

### Требуемые изменения

Добавить limits object:

```kotlin
object AgentEventLimits {
    const val DEFAULT_REPLAY_LIMIT = 100
    const val MAX_REPLAY_LIMIT = 1_000
    const val LIVE_BUFFER_SIZE = 256
}
```

В `AgentEventBus.subscribe(...)` заменить:

```kotlin
Channel<AgentEvent>(Channel.UNLIMITED)
```

на:

```kotlin
Channel<AgentEvent>(
    capacity = AgentEventLimits.LIVE_BUFFER_SIZE,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
)
```

Добавить import:

```kotlin
import kotlinx.coroutines.channels.BufferOverflow
```

`DROP_OLDEST` выбран сознательно: live WS events не должны приводить к OOM. Durable replay остаётся source of truth для важных событий.

### Ограничения

* Не использовать `AgentEventRepository.DEFAULT_LIMIT` как capacity live channel. Это разные смыслы: repository default — сколько durable events читать, live buffer — сколько live events держать для медленного клиента.
* Не менять публичный формат события в этой задаче.
* Не менять WebSocket protocol в этой задаче.
* Не добавлять новые зависимости.

### Acceptance criteria

* `AgentEventService.kt` больше не содержит реализацию `AgentEventBus`.
* В коде нет `Channel.UNLIMITED` для agent event subscriptions.
* Live event channel bounded.
* При переполнении live buffer старые events отбрасываются через `DROP_OLDEST`.
* Существующие tests проходят.
* Добавлен или обновлён test, который проверяет, что медленный subscriber не блокирует publisher и не растит unbounded очередь.

---

# Task 2. Добавить hard caps для list/replay endpoints и убрать `Int.MAX_VALUE`

## Prompt для coding agent

Нужно убрать unbounded replay/list reads из event API и HTTP endpoints.

Сейчас в event replay используется `limit = Int.MAX_VALUE`, а query params `limit` для list endpoints не имеют жёсткой верхней границы. Это риск DoS: клиент может запросить слишком много данных за один request.

### Цель

1. Убрать все использования `Int.MAX_VALUE` как limit для events/messages/chats replay/list.
2. Добавить hard caps на query limit.
3. Сделать поведение одинаковым для service и HTTP слоя.

### Файлы

Проверить и изменить:

```text
backend/src/main/kotlin/ru/souz/backend/events/service/AgentEventService.kt
backend/src/main/kotlin/ru/souz/backend/events/repository/AgentEventRepository.kt
backend/src/main/kotlin/ru/souz/backend/http/BackendHttpServer.kt
```

Также проверить repositories:

```text
backend/src/main/kotlin/ru/souz/backend/messages/repository/*
backend/src/main/kotlin/ru/souz/backend/chat/repository/*
backend/src/main/kotlin/ru/souz/backend/events/repository/*
```

### Требуемые изменения

Использовать общий object с лимитами, например:

```kotlin
object AgentEventLimits {
    const val DEFAULT_REPLAY_LIMIT = 100
    const val MAX_REPLAY_LIMIT = 1_000
    const val LIVE_BUFFER_SIZE = 256
}
```

Для HTTP list endpoints добавить похожие caps:

```kotlin
private const val DEFAULT_CHAT_LIMIT = 50
private const val MAX_CHAT_LIMIT = 100

private const val DEFAULT_MESSAGE_LIMIT = 100
private const val MAX_MESSAGE_LIMIT = 500

private const val DEFAULT_EVENT_LIMIT = 100
private const val MAX_EVENT_LIMIT = 1_000
```

Если эти constants лучше вынести в отдельный файл — вынести.

Заменить parsing limit на coerce:

```kotlin
val limit = queryPositiveInt("limit", default = DEFAULT_EVENT_LIMIT)
    .coerceIn(1, MAX_EVENT_LIMIT)
```

Если есть helper `queryPositiveInt`, расширить его:

```kotlin
private fun ApplicationCall.queryPositiveInt(
    name: String,
    default: Int,
    max: Int,
): Int
```

Поведение:

* отсутствует `limit` → default;
* `limit <= 0` → bad request;
* `limit > max` → либо clamp до max, либо bad request.

Выбрать один вариант и применить последовательно. Предпочтительно clamp до max, чтобы API было friendly.

### Ограничения

* Не менять формат ответа.
* Не ломать pagination через `afterSeq` / cursor.
* Не возвращать все события “по умолчанию”.
* Не использовать `Int.MAX_VALUE`, `Long.MAX_VALUE` или аналогичные fake-unbounded limits.

### Acceptance criteria

* В коде нет `limit = Int.MAX_VALUE` для event replay/list.
* Все list/replay endpoints имеют max cap.
* `GET /v1/chats`, `GET /v1/chats/{chatId}/messages`, `GET /v1/chats/{chatId}/events` не могут вернуть неограниченный объём данных.
* Добавлены tests на:

    * default limit;
    * слишком большой limit;
    * invalid negative/zero limit;
    * replay с `afterSeq`.

---

# Task 3. Разделить durable events и live-only events

## Prompt для coding agent

Нужно разделить два разных вида событий:

1. Durable events — сохраняются в `agent_events` repository и могут быть replayed после reconnect.
2. Live-only events — отправляются только текущим WebSocket subscribers и не сохраняются в durable storage.

Сейчас `AgentEventService.append(...)` одновременно persist-ит событие и publish-ит его в live bus. Это мешает сделать `MESSAGE_DELTA` ephemeral-only.

### Цель

Добавить отдельный API для live-only событий, не ломая durable events.

### Файлы

Основные:

```text
backend/src/main/kotlin/ru/souz/backend/events/service/AgentEventService.kt
backend/src/main/kotlin/ru/souz/backend/events/bus/AgentEventBus.kt
backend/src/main/kotlin/ru/souz/backend/events/model/AgentEvent.kt
backend/src/main/kotlin/ru/souz/backend/events/model/AgentEventType.kt
backend/src/main/kotlin/ru/souz/backend/agent/runtime/BackendAgentRuntimeEventSink.kt
backend/src/main/kotlin/ru/souz/backend/http/BackendHttpServer.kt
```

Если `AgentEventBus` ещё не вынесен — вынести минимально в рамках этой задачи.

### Требуемый дизайн

Сохранить существующий `AgentEvent` как durable model.

Добавить live-only model. Например:

```kotlin
data class AgentLiveEvent(
    val id: UUID,
    val userId: String,
    val chatId: UUID,
    val executionId: UUID?,
    val type: AgentEventType,
    val payload: Map<String, String>,
    val createdAt: Instant,
)
```

Важно: live-only event не должен иметь durable `seq`, если он не был сохранён в repository. Не создавать fake seq.

В `AgentEventService` сделать два метода:

```kotlin
suspend fun appendDurable(...): AgentEvent
```

который:

1. сохраняет event в repository;
2. публикует сохранённый durable event в bus.

И:

```kotlin
suspend fun publishLive(...): AgentLiveEvent
```

который:

1. не вызывает repository;
2. публикует событие только в bus.

Можно оставить старый `append(...)` как alias для `appendDurable(...)`, если это уменьшит diff, но желательно переименовать call sites, чтобы семантика была явной.

`AgentEventBus` должен уметь publish both durable and live-only events. Если проще, можно ввести sealed interface:

```kotlin
sealed interface AgentEventEnvelope {
    val userId: String
    val chatId: UUID
    val executionId: UUID?
    val type: AgentEventType
    val payload: Map<String, String>
    val createdAt: Instant
}

data class DurableAgentEventEnvelope(val event: AgentEvent) : AgentEventEnvelope
data class LiveAgentEventEnvelope(val event: AgentLiveEvent) : AgentEventEnvelope
```

Но не усложнять сверх необходимости. Главное — не сохранять live-only events.

### WebSocket protocol

Если WebSocket сейчас отправляет `AgentEvent` с `seq`, для durable events оставить `seq`.

Для live-only events `MESSAGE_DELTA` отправлять без `seq` или с `seq = null`.

Нужно явно различать на клиентском protocol level:

```json
{
  "type": "message.delta",
  "durable": false,
  "seq": null,
  "payload": {
    "delta": "..."
  }
}
```

Если DTO уже существует — расширить его минимально.

### Ограничения

* Durable replay endpoint должен возвращать только durable events из repository.
* Live-only events не должны попадать в `agent_events`.
* Нельзя использовать fake seq для live-only events.
* Не менять semantics durable events.

### Acceptance criteria

* Есть отдельный path для publish live-only events без repository write.
* Durable replay не возвращает `MESSAGE_DELTA`, если delta публикуется как live-only.
* WebSocket subscriber получает durable events и live-only events.
* Live-only events clearly marked as non-durable или не имеют seq.
* Добавлены tests:

    * `appendDurable` сохраняет и publish-ит;
    * `publishLive` publish-ит, но не вызывает repository;
    * replay не содержит live-only deltas.

---

# Task 4. Сделать `MESSAGE_DELTA` live-only и перестать писать streaming chunks в БД

## Prompt для coding agent

Нужно изменить streaming persistence model.

Сейчас каждый `AgentRuntimeEvent.LlmMessageDelta` приводит к:

* `messageRepository.updateContent(...)`;
* persisted `MESSAGE_DELTA` event;
* live publish.

Это создаёт write amplification: сотни или тысячи DB/filesystem writes на один LLM ответ. Финальный ответ всё равно записывается через `completeAssistantMessage(content)`, поэтому partial writes не нужны.

### Цель

1. `MESSAGE_DELTA` отправлять только live WebSocket subscribers.
2. Не сохранять `MESSAGE_DELTA` в `agent_events`.
3. Не обновлять assistant message на каждый delta.
4. Сохранять assistant message только один раз — при successful completion.
5. При failure/cancel не сохранять partial assistant message.

### Файлы

Основные:

```text
backend/src/main/kotlin/ru/souz/backend/agent/runtime/BackendAgentRuntimeEventSink.kt
backend/src/main/kotlin/ru/souz/backend/events/service/AgentEventService.kt
agent/src/main/kotlin/ru/souz/agent/nodes/NodesLLM.kt
agent/src/main/kotlin/ru/souz/agent/runtime/AgentRuntimeEvent.kt
```

Также проверить tests вокруг messages/events.

### Требуемое поведение

Для runtime event:

```kotlin
AgentRuntimeEvent.LlmMessageDelta(content)
```

backend должен:

* если `content` пустой — ничего не делать;
* если streaming включён — отправить live-only event `MESSAGE_DELTA`;
* не создавать assistant message;
* не вызывать `messageRepository.updateContent`;
* не persist-ить `MESSAGE_DELTA` в `agent_events`.

При successful completion:

```kotlin
completeAssistantMessage(finalContent)
```

backend должен:

* создать или обновить assistant message один раз;
* persist-ить durable event `MESSAGE_COMPLETED`;
* publish-ить durable event live subscribers.

При failure/cancel:

* не создавать assistant message из accumulated partial deltas;
* не сохранять partial content;
* persist-ить только execution failed/cancelled event.

### Важный нюанс

В `NodesLLM` уже есть batching через `pending`, но `eventSink.emit(LlmMessageDelta(content))` вызывается до batching. В этой задаче не обязательно менять `NodesLLM`, если backend теперь не persist-ит delta. Но можно дополнительно сделать emit после batching, если это не ломает UX.

Если менять `NodesLLM`, цель — уменьшить количество live events тоже:

* provider chunks складывать в `pending`;
* emit `LlmMessageDelta` только на flush pending;
* finalContent всё равно передавать отдельно.

### Ограничения

* Не сохранять partial assistant content на failure/cancel.
* Не ломать final assistant message.
* Не ломать live streaming UX.
* Не добавлять сложную batching infrastructure, если достаточно live-only delta.
* Durable replay после reconnect не обязан возвращать старые deltas. После completion клиент увидит final assistant message.

### Acceptance criteria

* `LlmMessageDelta` больше не вызывает `messageRepository.updateContent`.
* `LlmMessageDelta` больше не вызывает durable append в `agent_events`.
* `MESSAGE_DELTA` не возвращается из durable replay endpoint.
* Successful LLM response создаёт ровно один final assistant message.
* Failed/cancelled execution не создаёт partial assistant message.
* Tests покрывают:

    * success final message;
    * live delta not persisted;
    * failure no partial message;
    * cancel no partial message.

---

# Task 5. Убрать raw secrets из tool runtime events

## Prompt для coding agent

Нужно усилить security вокруг tool events.

Сейчас `AgentToolExecutor` эмитит runtime events с raw tool arguments и raw result preview. Это опасно: секреты могут быть в аргументах тулов, result content, exception message, URL query params, headers, config values или содержимом файлов.

Текущая redaction на backend sink уровне недостаточна, потому что raw secret уже попадает в generic runtime event. Любой будущий sink/logger/test collector/telemetry может случайно сохранить raw данные.

### Цель

1. Не передавать raw tool arguments в `AgentRuntimeEvent`.
2. Не передавать raw tool result content в `AgentRuntimeEvent`.
3. Делать safe preview / allowlist до `eventSink.emit(...)`.
4. Сохранить полезность events для UI/debugging.

### Файлы

Основные:

```text
agent/src/main/kotlin/ru/souz/agent/runtime/AgentRuntimeEvent.kt
agent/src/main/kotlin/ru/souz/agent/runtime/AgentToolExecutor.kt
backend/src/main/kotlin/ru/souz/backend/agent/runtime/BackendAgentRuntimeEventSink.kt
```

Возможно создать:

```text
agent/src/main/kotlin/ru/souz/agent/runtime/ToolEventSanitizer.kt
```

### Требуемые изменения

Изменить model events.

Вместо raw arguments:

```kotlin
ToolCallStarted(
    toolCallId = ...,
    name = ...,
    arguments = rawJson
)
```

использовать безопасную модель:

```kotlin
data class ToolCallStarted(
    val toolCallId: String,
    val name: String,
    val argumentKeys: List<String>,
    val argumentsPreview: Map<String, String> = emptyMap(),
)
```

Для result:

```kotlin
data class ToolCallFinished(
    val toolCallId: String,
    val name: String,
    val resultPreview: String?,
    val durationMs: Long?,
)
```

`resultPreview` должен быть sanitized и truncated.

Добавить sanitizer:

```kotlin
object ToolEventSanitizer {
    fun safeArgumentPreview(toolName: String, rawArgumentsJson: String): ToolArgumentPreview
    fun safeResultPreview(toolName: String, rawResult: String?, maxLength: Int = 256): String?
    fun safeErrorMessage(error: Throwable, maxLength: Int = 256): String
}
```

Рекомендуемая политика:

* default: не показывать значения аргументов вообще, только keys;
* allowlist для безопасных тулов:

    * search/web tool: `query`, `limit`;
    * file read/list: path и maxBytes, но не content;
    * calculator: expression;
    * choice/user-facing tools: visible label/text if already intended for user.
* config/secrets/provider keys/auth headers: никогда не показывать значения;
* URL query params чистить или не показывать полностью;
* result preview truncate до 256 chars;
* error message sanitize + truncate.

### Backend changes

`BackendAgentRuntimeEventSink` больше не должен пытаться спасать raw tool args regex-ами как основной security mechanism.

Можно оставить backend redaction как defense-in-depth, но она не должна быть единственной защитой.

### Ограничения

* Не логировать raw tool arguments.
* Не persist-ить raw tool arguments.
* Не отправлять raw tool arguments по WebSocket.
* Не persist-ить raw tool result content.
* Не ломать execution of tools. Sanitizer влияет только на events.

### Acceptance criteria

* `AgentRuntimeEvent.ToolCallStarted` не содержит raw arguments JSON.
* `AgentRuntimeEvent.ToolCallFinished` не содержит raw raw result content.
* Tool execution всё ещё получает raw arguments internally, но они не попадают в events.
* Tests:

    * secret-looking arg не появляется в emitted event;
    * password/token/apiKey/header values are redacted/omitted;
    * result preview truncated;
    * exception message sanitized/truncated.

---

# Task 6. Зафиксировать single-thread contract для `AgentRuntimeEventSink`

## Prompt для coding agent

Нужно явно зафиксировать concurrency contract для runtime event sink.

`BackendAgentRuntimeEventSink` хранит mutable per-execution state:

* accumulated assistant content;
* current assistant message;
* requested choice id.

Сейчас код, вероятно, вызывается последовательно, но интерфейс это не документирует. Если runtime начнёт emit-ить events параллельно, состояние может повредиться.

### Цель

1. Документировать, что `AgentRuntimeEventSink` execution-scoped и вызывается sequentially.
2. Добавить safety guard в backend implementation через `Mutex`.
3. Не менять external behavior.

### Файлы

```text
agent/src/main/kotlin/ru/souz/agent/runtime/AgentRuntimeEvent.kt
backend/src/main/kotlin/ru/souz/backend/agent/runtime/BackendAgentRuntimeEventSink.kt
```

Если интерфейс в другом файле — изменить там.

### Требуемые изменения

В интерфейс добавить KDoc:

```kotlin
/**
 * Execution-scoped runtime event sink.
 *
 * Implementations are expected to receive events sequentially for a single
 * execution. Concurrent calls are not part of the contract unless an
 * implementation explicitly documents support for them.
 */
interface AgentRuntimeEventSink {
    suspend fun emit(event: AgentRuntimeEvent)
}
```

В `BackendAgentRuntimeEventSink` добавить KDoc:

```kotlin
/**
 * Not logically concurrent.
 *
 * This sink keeps mutable per-execution state and expects events for one
 * execution to be processed in order. A Mutex is used as a defensive guard
 * against accidental concurrent emit calls.
 */
```

Добавить:

```kotlin
private val emitMutex = Mutex()
```

И:

```kotlin
override suspend fun emit(event: AgentRuntimeEvent) = emitMutex.withLock {
    handleEvent(event)
}
```

Где `handleEvent` содержит текущую логику `emit`.

### Ограничения

* Не использовать global lock.
* Lock должен быть per sink instance.
* Не менять порядок events.
* Не создавать новую coroutine/actor infrastructure в этой задаче.

### Acceptance criteria

* Contract documented.
* Backend sink protected by per-instance `Mutex`.
* Existing behavior unchanged.
* Tests pass.
* No deadlocks: `emit` не должен вызывать сам себя while holding mutex.

---

# Task 7. Исправить coroutine lifecycle в `AgentExecutionService`

## Prompt для coding agent

Нужно упростить и обезопасить coroutine lifecycle в `AgentExecutionService`.

Сейчас service создаёт собственный `CoroutineScope(SupervisorJob() + Dispatchers.Default)` и управляет async execution jobs. Есть риск, что job стартует до регистрации в `ActiveExecutionJobRegistry`, особенно если используется `CoroutineStart.UNDISPATCHED`.

### Цель

1. Убрать старт execution coroutine до регистрации job.
2. Избежать `CoroutineStart.UNDISPATCHED` для async execution.
3. По возможности inject-ить application scope вместо создания unmanaged scope внутри service.
4. Сохранить cancellation semantics.

### Файлы

```text
backend/src/main/kotlin/ru/souz/backend/execution/service/AgentExecutionService.kt
backend/src/main/kotlin/ru/souz/backend/execution/service/ActiveExecutionJobRegistry.kt
backend/src/main/kotlin/ru/souz/backend/BackendApplication.kt
```

Или файлы, где создаётся `AgentExecutionService`.

### Требуемые изменения

Для async execution использовать lazy job:

```kotlin
val job = executionScope.launch(start = CoroutineStart.LAZY) {
    runExecution(...)
}

activeJobs.register(execution.id, job)
job.start()
```

Если registry API неудобный — адаптировать минимально.

Не должно быть окна, где execution уже выполняется, но registry ещё не знает job.

Если сейчас service сам создаёт scope:

```kotlin
private val executionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
```

лучше заменить на constructor parameter:

```kotlin
class AgentExecutionService(
    ...,
    private val executionScope: CoroutineScope,
)
```

Scope должен жить столько же, сколько backend application. Если в проекте уже есть application scope — использовать его.

Если scope injection слишком большой diff, минимально исправить start order сейчас, а scope injection сделать отдельным TODO. Но предпочтительно сделать оба изменения.

### Cancellation

Cancellation должна:

* cancel-ить job из registry;
* переводить execution в cancelled;
* не превращаться в failed;
* не сохранять partial assistant message.

### Ограничения

* Не запускать execution до регистрации active job.
* Не swallowing `CancellationException`.
* Не создавать global singleton scope.
* Не ломать sync execution path.

### Acceptance criteria

* Нет `CoroutineStart.UNDISPATCHED` для async agent execution.
* Job регистрируется до `job.start()`.
* Cancellation после создания execution, но до фактического старта, корректно работает.
* Execution scope управляется application lifecycle или явно documented.
* Tests:

    * async execution registered before start;
    * cancel running execution;
    * cancel before execution body begins;
    * cancellation status is cancelled, not failed.

---

# Task 8. Не ловить `Throwable` в turn runner и корректно пробрасывать cancellation

## Prompt для coding agent

Нужно исправить error handling в backend conversation runtime turn runner.

Сейчас runner ловит `Throwable` и заворачивает всё в `BackendConversationTurnException`. Это опасно для coroutine cancellation: `CancellationException` не должна маскироваться обычной ошибкой.

### Цель

1. Не ловить `Throwable`, если нет крайней необходимости.
2. `CancellationException` всегда пробрасывать как есть.
3. Обычные exceptions заворачивать в domain exception.
4. Не превращать cancellation в failed execution.

### Файлы

```text
backend/src/main/kotlin/ru/souz/backend/agent/runtime/BackendConversationTurnRunner.kt
backend/src/main/kotlin/ru/souz/backend/execution/service/AgentExecutionService.kt
```

### Требуемые изменения

Заменить:

```kotlin
catch (t: Throwable) {
    throw BackendConversationTurnException(..., t)
}
```

на:

```kotlin
catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    throw BackendConversationTurnException(..., e)
}
```

Добавить import:

```kotlin
import kotlinx.coroutines.CancellationException
```

В `AgentExecutionService` проверить обработку ошибок:

* `CancellationException` → execution cancelled;
* остальные exceptions → execution failed.

Если сейчас cancellation распознаётся через wrapped cause — упростить после изменения runner.

### Ограничения

* Не ловить `Error`, `OutOfMemoryError`, `StackOverflowError`.
* Не маскировать cancellation.
* Не менять текст пользовательских ошибок без необходимости.

### Acceptance criteria

* `BackendConversationTurnRunner` не ловит `Throwable`.
* Cancellation пробрасывается без wrapping.
* Cancelled execution не становится failed.
* Tests:

    * runner rethrows `CancellationException`;
    * runner wraps regular exception;
    * service marks cancellation as cancelled.

---

# Task 9. Проверить и удалить hidden/bidirectional Unicode chars

## Prompt для coding agent

GitHub показывает warning, что часть изменённых файлов содержит hidden или bidirectional Unicode text. Нужно проверить и удалить такие символы, если они не нужны явно.

Это security hardening против Trojan Source / bidi chars и случайных zero-width characters.

### Цель

1. Найти hidden/bidi Unicode chars в изменённых файлах.
2. Удалить их или заменить обычными символами.
3. Добавить lightweight check или documented command, чтобы это не повторялось.

### Файлы для проверки минимум

```text
AGENTS.md
agent/src/main/kotlin/ru/souz/agent/AgentExecutor.kt
agent/src/main/kotlin/ru/souz/agent/nodes/NodesCommon.kt
agent/src/main/kotlin/ru/souz/agent/nodes/NodesLLM.kt
agent/src/main/kotlin/ru/souz/agent/nodes/NodesLua.kt
agent/src/main/kotlin/ru/souz/agent/runtime/AgentRuntimeEvent.kt
agent/src/main/kotlin/ru/souz/agent/runtime/AgentToolExecutor.kt
agent/src/main/kotlin/ru/souz/agent/runtime/LuaRuntime.kt
agent/src/main/kotlin/ru/souz/agent/state/AgentContext.kt
```

Также проверить все изменённые файлы в PR.

### Команды для поиска

Выполнить:

```bash
rg -n --pcre2 '[\x{200B}-\x{200F}\x{202A}-\x{202E}\x{2060}-\x{206F}\x{FEFF}]' .
```

И отдельно NBSP:

```bash
rg -n --pcre2 '\x{00A0}' .
```

Если нужно точнее:

```bash
python3 - <<'PY'
from pathlib import Path
import unicodedata

bad_categories = {"Cf"}
extra = {"\u00a0"}

for f in Path(".").rglob("*"):
    if not f.is_file():
        continue
    if ".git" in f.parts or "build" in f.parts:
        continue
    try:
        text = f.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        continue

    for line_no, line in enumerate(text.splitlines(), 1):
        for col, ch in enumerate(line, 1):
            if unicodedata.category(ch) in bad_categories or ch in extra:
                name = unicodedata.name(ch, "UNKNOWN")
                print(f"{f}:{line_no}:{col}: U+{ord(ch):04X} {name}")
PY
```

### Ограничения

* Не менять смысл кода.
* Не делать массовое форматирование unrelated файлов.
* Если символ нужен намеренно — оставить комментарий рядом и добавить allowlist. Но скорее всего такие символы не нужны.

### Acceptance criteria

* В изменённых Kotlin/Markdown файлах нет hidden/bidi Unicode chars.
* `git diff --check` проходит.
* Команды поиска не находят опасных символов в changed files.
* Если добавлен check в CI или Gradle task — он не ломает legitimate Unicode в обычных строках, но ловит bidi/zero-width.

---

# Task 10. Ввести typed payload для agent events вместо `Map<String, String>`

## Prompt для coding agent

Нужно заменить слабую модель event payload.

Сейчас `AgentEvent.payload` — это `Map<String, String>`. Из-за этого сложные структуры превращаются в JSON-string внутри JSON, redaction становится хрупкой, а event consumers вынуждены парсить строки.

### Цель

1. Ввести typed payload model для agent events.
2. Сохранить совместимость storage serialization.
3. Упростить создание и чтение events.
4. Уменьшить риск случайного сохранения sensitive data.

### Файлы

```text
backend/src/main/kotlin/ru/souz/backend/events/model/AgentEvent.kt
backend/src/main/kotlin/ru/souz/backend/events/model/AgentEventType.kt
backend/src/main/kotlin/ru/souz/backend/events/repository/*
backend/src/main/kotlin/ru/souz/backend/agent/runtime/BackendAgentRuntimeEventSink.kt
backend/src/main/kotlin/ru/souz/backend/http/BackendHttpServer.kt
```

### Рекомендуемый дизайн

Добавить sealed payload:

```kotlin
sealed interface AgentEventPayload
```

Payload types:

```kotlin
data class ExecutionStartedPayload(...) : AgentEventPayload
data class ExecutionFinishedPayload(...) : AgentEventPayload
data class ExecutionFailedPayload(...) : AgentEventPayload
data class ExecutionCancelledPayload(...) : AgentEventPayload

data class MessageCompletedPayload(
    val messageId: UUID,
    val role: String,
) : AgentEventPayload

data class ToolCallStartedPayload(
    val toolCallId: String,
    val name: String,
    val argumentKeys: List<String>,
    val argumentsPreview: Map<String, String> = emptyMap(),
) : AgentEventPayload

data class ToolCallFinishedPayload(
    val toolCallId: String,
    val name: String,
    val resultPreview: String?,
    val durationMs: Long?,
) : AgentEventPayload

data class ChoiceRequestedPayload(...) : AgentEventPayload
data class ChoiceAnsweredPayload(...) : AgentEventPayload
```

Для unknown/backward compatibility:

```kotlin
data class RawAgentEventPayload(
    val values: Map<String, String>,
) : AgentEventPayload
```

Storage может всё ещё хранить JSONB/text, но serialization должна быть typed.

Если переход слишком большой, сделать промежуточный слой:

```kotlin
data class AgentEventPayloadDto(
    val type: String,
    val data: JsonObject,
)
```

Но конечная цель — не использовать `Map<String, String>` в domain model.

### Ограничения

* Не ломать существующие persisted events без migration path.
* Если storage уже содержит old payloads, repository должен уметь читать их как `RawAgentEventPayload`.
* Не добавлять raw tool arguments в typed payload.
* `MESSAGE_DELTA`, если live-only, не обязан иметь durable payload.

### Acceptance criteria

* New event creation uses typed payloads.
* Existing storage serialization works.
* Old payloads can still be read or migration added.
* Tool event payloads do not include raw secrets.
* Tests:

    * serialize/deserialize each payload type;
    * read old `Map<String, String>` payload if applicable;
    * HTTP response JSON remains stable or intentionally versioned.

---

# Task 11. Убрать лишний `masterKey` из filesystem provider key repository

## Prompt для coding agent

Нужно убрать лишнюю ответственность из filesystem provider key repository.

Сейчас `FilesystemUserProviderKeyRepository` принимает `masterKey`, хотя encryption/decryption выполняется в `UserProviderKeyService` через secret codec. Repository не должен знать master key, если он его не использует.

### Цель

1. Удалить unused `masterKey` parameter из filesystem repository.
2. Убедиться, что encryption остаётся в service/codec.
3. Упростить wiring.

### Файлы

```text
backend/src/main/kotlin/ru/souz/backend/storage/filesystem/FilesystemUserProviderKeyRepository.kt
backend/src/main/kotlin/ru/souz/backend/providerkeys/service/UserProviderKeyService.kt
backend/src/main/kotlin/ru/souz/backend/BackendApplication.kt
runtime/src/main/kotlin/ru/souz/db/AesGcmSecretCodec.kt
```

Или реальные файлы wiring, если отличаются.

### Требуемые изменения

В `FilesystemUserProviderKeyRepository` удалить constructor parameter:

```kotlin
masterKey: String
```

если он не используется.

До:

```kotlin
class FilesystemUserProviderKeyRepository(
    private val root: Path,
    private val masterKey: String,
)
```

После:

```kotlin
class FilesystemUserProviderKeyRepository(
    private val root: Path,
)
```

Обновить все call sites.

Проверить, что `SOUZ_MASTER_KEY` всё ещё валидируется в config и передаётся туда, где реально нужен — в `AesGcmSecretCodec` / `UserProviderKeyService`.

### Ограничения

* Не ослабить encryption.
* Не сохранять provider keys plaintext.
* Не менять file format без необходимости.
* Не менять public API provider keys.

### Acceptance criteria

* Repository не принимает unused master key.
* Encryption/decryption tests проходят.
* Provider keys по-прежнему сохраняются encrypted.
* Config validation for `SOUZ_MASTER_KEY` остаётся.

---

# Task 12. Разделить `BackendHttpServer.kt` на route modules

## Prompt для coding agent

Нужно уменьшить размер и сложность `BackendHttpServer.kt`.

Сейчас один файл содержит много responsibilities:

* Ktor setup;
* `/v1` routes;
* legacy `/agent`;
* auth helpers;
* request parsing;
* validation;
* response wrappers;
* WebSocket events;
* chats/messages/settings/provider keys/choices.

Это превращается в god file и противоречит целевой структуре из `docs/server-agent-concept.md`, где routes должны быть разделены.

### Цель

Разделить HTTP слой на небольшие route modules без изменения API behavior.

### Файлы

Исходный:

```text
backend/src/main/kotlin/ru/souz/backend/http/BackendHttpServer.kt
```

Создать примерно:

```text
backend/src/main/kotlin/ru/souz/backend/http/routes/BootstrapRoutes.kt
backend/src/main/kotlin/ru/souz/backend/http/routes/SettingsRoutes.kt
backend/src/main/kotlin/ru/souz/backend/http/routes/ProviderKeyRoutes.kt
backend/src/main/kotlin/ru/souz/backend/http/routes/ChatRoutes.kt
backend/src/main/kotlin/ru/souz/backend/http/routes/MessageRoutes.kt
backend/src/main/kotlin/ru/souz/backend/http/routes/EventRoutes.kt
backend/src/main/kotlin/ru/souz/backend/http/routes/ChoiceRoutes.kt
backend/src/main/kotlin/ru/souz/backend/http/routes/LegacyAgentRoutes.kt
backend/src/main/kotlin/ru/souz/backend/http/HttpValidation.kt
backend/src/main/kotlin/ru/souz/backend/http/RequestParsing.kt
backend/src/main/kotlin/ru/souz/backend/http/RouteErrors.kt
```

### Требуемая структура

`BackendHttpServer.kt` должен остаться orchestration/bootstrap файлом:

```kotlin
fun Application.configureBackendHttpServer(dependencies: BackendHttpDependencies) {
    install(...)
    routing {
        v1Routes(dependencies)
        legacyAgentRoutes(dependencies)
    }
}
```

Route modules:

```kotlin
fun Route.chatRoutes(deps: BackendHttpDependencies) { ... }
fun Route.messageRoutes(deps: BackendHttpDependencies) { ... }
fun Route.eventRoutes(deps: BackendHttpDependencies) { ... }
```

Validation helpers вынести:

```kotlin
fun ApplicationCall.requireJsonContent()
fun ApplicationCall.queryPositiveInt(...)
fun ApplicationCall.requireUserIdFromTrustedProxy()
fun ApplicationCall.requireRequestId()
fun ApplicationCall.requireAgentAuthorization(...)
```

### Ограничения

* Не менять URLs.
* Не менять request/response DTOs.
* Не менять status codes, кроме явных bugs.
* Не делать unrelated logic changes.
* Не смешивать этот refactor с event persistence changes, если можно избежать.

### Acceptance criteria

* `BackendHttpServer.kt` стал существенно меньше и содержит только setup/orchestration.
* Routes разделены по feature modules.
* Existing HTTP tests pass.
* Public API paths unchanged.
* Нет дублирования `requireJsonContent` / UUID parsing / query parsing helpers.

---

# Task 13. Усилить bearer token comparison через constant-time compare

## Prompt для coding agent

Нужно сделать небольшое security hardening для bearer token authorization.

Сейчас internal bearer token сравнивается обычным string equality. Для internal API это не самый высокий риск, но лучше использовать constant-time comparison.

### Цель

Заменить обычное сравнение токена на constant-time compare.

### Файлы

```text
backend/src/main/kotlin/ru/souz/backend/http/BackendHttpServer.kt
```

Если auth helpers вынесены:

```text
backend/src/main/kotlin/ru/souz/backend/http/HttpAuth.kt
```

### Требуемые изменения

Добавить helper:

```kotlin
private fun secureEquals(actual: String, expected: String): Boolean {
    val actualBytes = actual.toByteArray(Charsets.UTF_8)
    val expectedBytes = expected.toByteArray(Charsets.UTF_8)
    return MessageDigest.isEqual(actualBytes, expectedBytes)
}
```

Использовать в `requireAgentAuthorization()` или аналогичном helper.

Важно: аккуратно обработать missing/blank token до compare.

### Ограничения

* Не логировать actual token.
* Не включать token в error response.
* Не менять auth scheme.
* Не менять header names.

### Acceptance criteria

* Bearer token comparison uses `MessageDigest.isEqual`.
* Missing/invalid token still returns same status as before.
* Tests:

    * valid token accepted;
    * invalid token rejected;
    * missing token rejected.

---

# Task 14. Разгрузить `AgentExecutionService`

## Prompt для coding agent

Нужно уменьшить responsibilities `AgentExecutionService`.

Сейчас service одновременно:

* создаёт execution;
* пишет user/assistant messages;
* строит runtime request;
* запускает sync/async execution;
* управляет cancellation;
* управляет choices;
* пишет event metadata;
* управляет active jobs;
* обрабатывает failure transitions.

Это делает код сложным и хрупким.

### Цель

Разделить responsibilities без изменения поведения.

### Файлы

Основной:

```text
backend/src/main/kotlin/ru/souz/backend/execution/service/AgentExecutionService.kt
```

Можно создать:

```text
backend/src/main/kotlin/ru/souz/backend/execution/service/AgentExecutionLauncher.kt
backend/src/main/kotlin/ru/souz/backend/execution/service/AgentExecutionFinalizer.kt
backend/src/main/kotlin/ru/souz/backend/execution/service/AgentExecutionRequestFactory.kt
backend/src/main/kotlin/ru/souz/backend/execution/service/AgentChoiceService.kt
```

Или более простое разбиение, если проектная структура другая.

### Рекомендуемое разбиение

Минимально:

1. `AgentExecutionRequestFactory`

    * build runtime request;
    * resolve provider/model/settings;
    * prepare context.

2. `AgentExecutionLauncher`

    * sync/async launch;
    * job registry;
    * coroutine lifecycle.

3. `AgentExecutionFinalizer`

    * mark finished/failed/cancelled;
    * write execution events;
    * call final assistant message logic.

4. `AgentChoiceService`

    * answer choices;
    * continue execution after choice.

Если полный split слишком большой — сначала вынести request factory и launcher. Это уже уменьшит service.

### Ограничения

* Не менять external API.
* Не менять DB schema.
* Не менять execution statuses.
* Не ломать choice continuation.
* Не дублировать transition logic.

### Acceptance criteria

* `AgentExecutionService` стал orchestration layer, а не god service.
* Runtime request construction вынесен.
* Async launch/cancellation logic вынесена или изолирована.
* Tests pass.
* Choice continuation behavior unchanged.
* Нет нового circular dependency.

---

# Task 15. Обновить tests под новую event model

## Prompt для coding agent

После изменений event model нужно обновить tests и добавить coverage на ключевые риски.

### Цель

Добавить tests, которые защищают новые architectural decisions:

* bounded live event bus;
* durable vs live-only events;
* no persisted streaming deltas;
* no partial assistant message on failure/cancel;
* sanitized tool events;
* replay limits;
* cancellation handling.

### Где искать tests

Проверить текущую структуру:

```text
backend/src/test/kotlin
agent/src/test/kotlin
runtime/src/test/kotlin
```

Если tests находятся в других module-specific директориях — следовать существующему стилю.

### Required test cases

#### Event bus

* slow subscriber does not block publisher indefinitely;
* buffer overflow drops old live events instead of OOM;
* unsubscribe closes/removes subscription.

#### Event replay limits

* default replay limit applied;
* max replay limit applied;
* huge requested limit does not return more than max;
* `afterSeq` works with max limit.

#### Durable vs live-only

* durable append writes repository and publishes event;
* live publish does not write repository;
* replay returns durable events only;
* WebSocket/live stream receives live-only delta.

#### Streaming persistence

* `LlmMessageDelta` does not create assistant message;
* `LlmMessageDelta` does not update assistant message content;
* `LlmMessageDelta` does not persist `MESSAGE_DELTA`;
* successful completion persists final assistant message once;
* failed execution persists no partial assistant message;
* cancelled execution persists no partial assistant message.

#### Tool security

* tool argument with `password`, `token`, `apiKey`, `authorization` does not appear in event;
* raw result content is truncated/sanitized;
* exception message is truncated/sanitized;
* backend persisted payload contains only safe preview.

#### Cancellation

* `CancellationException` is not wrapped by turn runner;
* cancelled execution marked cancelled;
* regular exception marked failed.

### Ограничения

* Не писать brittle tests, которые зависят от exact timing без virtual time.
* Для coroutine tests использовать существующий test dispatcher/style проекта.
* Не тестировать implementation details сильнее, чем нужно.

### Acceptance criteria

* Добавлен meaningful coverage для всех listed areas или явно documented, почему конкретный case неприменим.
* Tests stable.
* Tests fail на старом поведении с persisted deltas / unbounded replay.
* Tests pass после исправлений.

---

# Рекомендуемый порядок выполнения

Хотя задачи сформулированы независимо, безопаснее делать их в таком порядке:

1. Task 1 — extract bus + bounded channel.
2. Task 2 — hard caps.
3. Task 3 — durable/live-only split.
4. Task 4 — live-only `MESSAGE_DELTA`.
5. Task 5 — tool event sanitization.
6. Task 6 — sink single-thread contract.
7. Task 8 — cancellation wrapping.
8. Task 7 — execution coroutine lifecycle.
9. Task 11 — remove unused `masterKey`.
10. Task 13 — constant-time token compare.
11. Task 12 — split HTTP routes.
12. Task 10 — typed payloads.
13. Task 14 — split `AgentExecutionService`.
14. Task 9 — hidden Unicode cleanup.
15. Task 15 — final test pass / coverage gaps.

Самые критичные перед merge:

```text
Task 1
Task 2
Task 3
Task 4
Task 5
Task 7
Task 8
Task 9
```

Можно не блокировать merge на полном split `BackendHttpServer.kt` и typed payload migration, если нужно быстро стабилизировать PR, но live-only deltas, bounded buffers, replay caps и sanitized tool events лучше сделать обязательно.
