package ru.souz.backend.events.bus

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import ru.souz.backend.events.model.AgentEventEnvelope

class AgentEventBus {
    private class Subscriber {
        val events = Channel<AgentEventEnvelope>(AgentEventLimits.LIVE_BUFFER_SIZE, BufferOverflow.DROP_OLDEST)
        val commands = Channel<AgentEventEnvelope>(AgentEventLimits.LIVE_BUFFER_SIZE)
    }

    private val subscribers =
        ConcurrentHashMap<AgentEventStreamKey, MutableSet<Subscriber>>()

    fun hasSubscriber(userId: String, chatId: UUID): Boolean =
        subscribers[AgentEventStreamKey(userId, chatId)]?.isNotEmpty() == true

    suspend fun subscribe(userId: String, chatId: UUID): AgentEventSubscription {
        val key = AgentEventStreamKey(userId = userId, chatId = chatId)
        val subscriber = Subscriber()
        subscribers.compute(key) { _, existing ->
            (existing ?: ConcurrentHashMap.newKeySet()).apply {
                add(subscriber)
            }
        }
        return AgentEventSubscription(
            events = subscriber.events,
            commands = subscriber.commands,
            close = {
                subscribers.computeIfPresent(key) { _, existing ->
                    existing.remove(subscriber)
                    existing.takeUnless { it.isEmpty() }
                }
                subscriber.events.close()
                subscriber.commands.close()
            },
        )
    }

    fun publishCommand(event: AgentEventEnvelope): Boolean {
        val targets = subscribers[AgentEventStreamKey(event.userId, event.chatId)] ?: return false
        var accepted = false
        targets.forEach { if (it.commands.trySend(event).isSuccess) accepted = true }
        return accepted
    }

    suspend fun publish(event: AgentEventEnvelope) {
        val key = AgentEventStreamKey(userId = event.userId, chatId = event.chatId)
        // Iterate the concurrent set directly; toList's size-based fast path races with disconnect.
        val targets = subscribers[key] ?: return
        val closedTargets = ArrayList<Subscriber>()
        targets.forEach { subscriber ->
            if (subscriber.events.trySend(event).isFailure) {
                closedTargets += subscriber
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
