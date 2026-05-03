package ru.souz.backend.events.service

import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.UUID
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.common.normalizePositiveLimit
import ru.souz.backend.events.bus.AgentEventBus
import ru.souz.backend.events.bus.AgentEventLimits
import ru.souz.backend.events.bus.AgentEventStream
import ru.souz.backend.events.model.AgentEvent
import ru.souz.backend.events.model.AgentLiveEvent
import ru.souz.backend.events.model.AgentEventPayload
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.repository.AgentEventRepository
import ru.souz.backend.http.BackendV1Exception

class AgentEventService(
    private val chatRepository: ChatRepository,
    private val eventRepository: AgentEventRepository,
    private val eventBus: AgentEventBus,
) {
    suspend fun appendDurable(
        userId: String,
        chatId: UUID,
        executionId: UUID?,
        type: AgentEventType,
        payload: AgentEventPayload,
        id: UUID = UUID.randomUUID(),
        createdAt: Instant = Instant.now(),
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
        eventBus.publish(event)
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

    suspend fun openStream(
        userId: String,
        chatId: UUID,
        afterSeq: Long? = null,
        limit: Int = AgentEventLimits.DEFAULT_REPLAY_LIMIT,
    ): AgentEventStream {
        requireOwnedChat(userId, chatId)
        val subscription = eventBus.subscribe(userId, chatId)
        try {
            val normalizedLimit = normalizePositiveLimit(limit, AgentEventLimits.MAX_REPLAY_LIMIT)
            val replay = eventRepository.listByChat(
                userId = userId,
                chatId = chatId,
                afterSeq = afterSeq,
                limit = normalizedLimit,
            )
            return AgentEventStream(
                replay = replay,
                liveEvents = subscription.events,
                close = { subscription.close() },
            )
        } catch (e: Throwable) {
            subscription.close()
            throw e
        }
    }

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
