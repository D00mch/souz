package ru.souz.backend.events.bus

import java.util.UUID
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.events.model.AgentEvent

class AgentEventBus {
    private val mutex = Mutex()
    private val subscribers = LinkedHashMap<AgentEventStreamKey, LinkedHashSet<Channel<AgentEvent>>>()

    suspend fun subscribe(userId: String, chatId: UUID): AgentEventSubscription {
        val key = AgentEventStreamKey(userId = userId, chatId = chatId)
        val channel = Channel<AgentEvent>(
            capacity = AgentEventLimits.LIVE_BUFFER_SIZE,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
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
