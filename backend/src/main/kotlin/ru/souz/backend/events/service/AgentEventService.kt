package ru.souz.backend.events.service

import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.UUID
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.events.bus.AgentEventBus
import ru.souz.backend.events.bus.AgentEventStream
import ru.souz.backend.events.model.AgentEvent
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.repository.AgentEventRepository
import ru.souz.backend.http.BackendV1Exception

class AgentEventService(
    private val chatRepository: ChatRepository,
    private val eventRepository: AgentEventRepository,
    private val eventBus: AgentEventBus,
) {
    suspend fun append(
        userId: String,
        chatId: UUID,
        executionId: UUID?,
        type: AgentEventType,
        payload: Map<String, String>,
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

    suspend fun listByChat(
        userId: String,
        chatId: UUID,
        afterSeq: Long? = null,
        limit: Int = Int.MAX_VALUE,
    ): List<AgentEvent> {
        requireOwnedChat(userId, chatId)
        return eventRepository.listByChat(
            userId = userId,
            chatId = chatId,
            afterSeq = afterSeq,
            limit = limit,
        )
    }

    suspend fun openStream(
        userId: String,
        chatId: UUID,
        afterSeq: Long? = null,
    ): AgentEventStream {
        requireOwnedChat(userId, chatId)
        val subscription = eventBus.subscribe(userId, chatId)
        val replay = eventRepository.listByChat(
            userId = userId,
            chatId = chatId,
            afterSeq = afterSeq,
            limit = Int.MAX_VALUE,
        )
        return AgentEventStream(
            replay = replay,
            liveEvents = subscription.events,
            close = { subscription.close() },
        )
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
