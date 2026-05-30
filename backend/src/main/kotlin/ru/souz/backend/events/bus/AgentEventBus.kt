package ru.souz.backend.events.bus

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import ru.souz.backend.events.model.AgentEventEnvelope

class AgentEventBus {
    private val subscribers =
        ConcurrentHashMap<AgentEventStreamKey, MutableSet<Channel<AgentEventEnvelope>>>()

    suspend fun subscribe(userId: String, chatId: UUID): AgentEventSubscription {
        val key = AgentEventStreamKey(userId = userId, chatId = chatId)
        val channel = Channel<AgentEventEnvelope>(
            capacity = AgentEventLimits.LIVE_BUFFER_SIZE,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        subscribers.compute(key) { _, existing ->
            (existing ?: ConcurrentHashMap.newKeySet()).apply {
                add(channel)
            }
        }
        return AgentEventSubscription(
            events = channel,
            close = {
                subscribers.computeIfPresent(key) { _, existing ->
                    existing.remove(channel)
                    existing.takeUnless { it.isEmpty() }
                }
                channel.close()
            },
        )
    }

    suspend fun publish(event: AgentEventEnvelope) {
        val key = AgentEventStreamKey(userId = event.userId, chatId = event.chatId)
        val targets = subscribers[key]?.toList().orEmpty()
        if (targets.isEmpty()) {
            return
        }
        val closedTargets = ArrayList<Channel<AgentEventEnvelope>>()
        targets.forEach { channel ->
            if (channel.trySend(event).isFailure) {
                closedTargets += channel
            }
        }
        if (closedTargets.isEmpty()) {
            return
        }
        subscribers.computeIfPresent(key) { _, existing ->
            existing.removeAll(closedTargets.toSet())
            existing.takeUnless { it.isEmpty() }
        }
    }
}
