package ru.souz.backend.events.service

import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.common.normalizePositiveLimit
import ru.souz.backend.events.bus.AgentEventBus
import ru.souz.backend.events.bus.AgentEventLimits
import ru.souz.backend.events.bus.AgentEventStream
import ru.souz.backend.events.model.AgentEvent
import ru.souz.backend.events.model.AgentEventPayload
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.model.AgentLiveEvent
import ru.souz.backend.events.model.PublicToolCallStartedPayload
import ru.souz.backend.events.repository.AgentEventRepository
import ru.souz.backend.http.BackendV1Exception

class AgentEventService(
    private val chatRepository: ChatRepository,
    private val eventRepository: AgentEventRepository,
    private val eventBus: AgentEventBus,
) {
    private val terminalMutex = Mutex()
    private val logger = LoggerFactory.getLogger(AgentEventService::class.java)

    suspend fun appendDurable(
        userId: String,
        chatId: UUID,
        executionId: UUID?,
        type: AgentEventType,
        payload: AgentEventPayload,
        id: UUID = UUID.randomUUID(),
        createdAt: Instant = Instant.now(),
    ): AgentEvent {
        if (type.isPublicTerminal() && executionId != null) {
            return terminalMutex.withLock {
                eventRepository.findTerminal(executionId)?.let { return@withLock it }
                appendAndPublish(userId, chatId, executionId, type, payload, id, createdAt)
            }
        }
        return appendAndPublish(userId, chatId, executionId, type, payload, id, createdAt)
    }

    private suspend fun appendAndPublish(
        userId: String,
        chatId: UUID,
        executionId: UUID?,
        type: AgentEventType,
        payload: AgentEventPayload,
        id: UUID,
        createdAt: Instant,
    ): AgentEvent {
        val event = eventRepository.append(
            userId = userId,
            chatId = chatId,
            executionId = executionId,
            type = type,
            payload = payload,
            id = id,
            createdAt = createdAt,
        )
        val shouldPublish = event.id == id
        if (event.isPublicClientDiagnosticEvent()) {
            logger.info(
                "Public client event stored chatId={} threadId={} seq={} type={} published={}",
                event.chatId,
                event.executionId,
                event.seq,
                event.type.value,
                shouldPublish,
            )
        }
        if (shouldPublish) eventBus.publish(event)
        return event
    }

    suspend fun append(
        userId: String,
        chatId: UUID,
        executionId: UUID?,
        type: AgentEventType,
        payload: AgentEventPayload,
        id: UUID = UUID.randomUUID(),
        createdAt: Instant = Instant.now(),
    ): AgentEvent = appendDurable(
        userId = userId,
        chatId = chatId,
        executionId = executionId,
        type = type,
        payload = payload,
        id = id,
        createdAt = createdAt,
    )

    suspend fun publishLive(
        userId: String,
        chatId: UUID,
        executionId: UUID?,
        type: AgentEventType,
        payload: AgentEventPayload,
        id: UUID = UUID.randomUUID(),
        createdAt: Instant = Instant.now(),
    ): AgentLiveEvent {
        val event = AgentLiveEvent(
            id = id,
            userId = userId,
            chatId = chatId,
            executionId = executionId,
            type = type,
            payload = payload,
            createdAt = createdAt,
        )
        eventBus.publish(event)
        return event
    }

    suspend fun listByChat(
        userId: String,
        chatId: UUID,
        afterSeq: Long? = null,
        limit: Int = AgentEventLimits.DEFAULT_REPLAY_LIMIT,
    ): List<AgentEvent> {
        requireOwnedChat(userId, chatId)
        val normalizedLimit = normalizePositiveLimit(limit, AgentEventLimits.MAX_REPLAY_LIMIT)
        return eventRepository.listByChat(
            userId = userId,
            chatId = chatId,
            afterSeq = afterSeq,
            limit = normalizedLimit,
        )
    }

    suspend fun openPublicStream(
        userId: String,
        chatId: UUID,
        afterSeq: Long? = 0,
    ): AgentEventStream {
        requireOwnedChat(userId, chatId)
        val subscription = eventBus.subscribe(userId, chatId)
        // Close the subscription before rethrowing cancellation.
        try {
            // A null cursor starts at the durable tail, after live signal registration.
            val initialSeq = afterSeq ?: eventRepository.latestSeq(userId, chatId)
            return AgentEventStream(
                replay = if (afterSeq == null) emptyList() else listPublicStreamReplay(userId, chatId, afterSeq),
                liveEvents = subscription.events,
                close = { subscription.close() },
                replayAfter = { seq -> listPublicStreamReplay(userId, chatId, seq) },
                initialSeq = initialSeq,
            )
        } catch (error: Throwable) {
            withContext(NonCancellable) { subscription.close() }
            throw error
        }
    }

    private suspend fun listPublicStreamReplay(
        userId: String,
        chatId: UUID,
        afterSeq: Long,
    ): List<AgentEvent> =
        eventRepository.listByChat(
            userId = userId,
            chatId = chatId,
            afterSeq = afterSeq,
            limit = Int.MAX_VALUE,
        )

    private suspend fun requireOwnedChat(userId: String, chatId: UUID) {
        if (chatRepository.get(userId, chatId) == null) {
            throw BackendV1Exception(
                status = HttpStatusCode.NotFound,
                code = "chat_not_found",
                message = "Chat not found.",
            )
        }
    }
}

private fun AgentEventType.isPublicTerminal(): Boolean =
    this == AgentEventType.THREAD_COMPLETED ||
        this == AgentEventType.THREAD_FAILED ||
        this == AgentEventType.THREAD_CANCELLED

private fun AgentEvent.isPublicClientDiagnosticEvent(): Boolean = when (type) {
    AgentEventType.THREAD_COMPLETED, AgentEventType.THREAD_FAILED, AgentEventType.THREAD_CANCELLED -> true
    AgentEventType.TOOL_CALL_STARTED -> payload is PublicToolCallStartedPayload
    AgentEventType.MESSAGE_CREATED -> executionId == null
    else -> false
}