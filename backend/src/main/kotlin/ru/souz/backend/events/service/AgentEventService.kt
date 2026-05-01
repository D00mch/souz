package ru.souz.backend.events.service

import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.chat.repository.ChatRepository
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

class AgentEventBus {
    private val mutex = Mutex()
    private val subscribers = LinkedHashMap<AgentEventStreamKey, LinkedHashSet<Channel<AgentEvent>>>()

    suspend fun subscribe(userId: String, chatId: UUID): AgentEventSubscription {
        val key = AgentEventStreamKey(userId = userId, chatId = chatId)
        val channel = Channel<AgentEvent>(Channel.UNLIMITED)
        mutex.withLock {
            subscribers.getOrPut(key) { LinkedHashSet() }.add(channel)
        }
        return AgentEventSubscription(
            events = channel,
            close = {
                mutex.withLock {
                    subscribers[key]?.remove(channel)
                    if (subscribers[key].isNullOrEmpty()) {
                        subscribers.remove(key)
                    }
                }
                channel.close()
            },
        )
    }

    suspend fun publish(event: AgentEvent) {
        val key = AgentEventStreamKey(userId = event.userId, chatId = event.chatId)
        val targets = mutex.withLock { subscribers[key]?.toList().orEmpty() }
        if (targets.isEmpty()) {
            return
        }
        val closedTargets = ArrayList<Channel<AgentEvent>>()
        targets.forEach { channel ->
            if (channel.trySend(event).isFailure) {
                closedTargets += channel
            }
        }
        if (closedTargets.isEmpty()) {
            return
        }
        mutex.withLock {
            val existing = subscribers[key] ?: return@withLock
            existing.removeAll(closedTargets.toSet())
            if (existing.isEmpty()) {
                subscribers.remove(key)
            }
        }
    }
}

data class AgentEventStream(
    val replay: List<AgentEvent>,
    val liveEvents: ReceiveChannel<AgentEvent>,
    val close: suspend () -> Unit,
)

data class AgentEventSubscription(
    val events: ReceiveChannel<AgentEvent>,
    val close: suspend () -> Unit,
)

private data class AgentEventStreamKey(
    val userId: String,
    val chatId: UUID,
)
