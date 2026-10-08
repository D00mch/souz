package ru.souz.backend.events.bus

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import ru.souz.backend.events.model.AgentEventEnvelope
import ru.souz.backend.events.model.isPublicClientEvent
import ru.souz.backend.metrics.BackendMetrics

class AgentEventBus(private val metrics: BackendMetrics? = null) {
    private class Subscriber(val acceptsClientCommands: Boolean, private val metrics: BackendMetrics?) {
        private val undelivered: (AgentEventEnvelope) -> Unit = { dropped(it, "undelivered") }
        val events = Channel(AgentEventLimits.LIVE_BUFFER_SIZE, BufferOverflow.DROP_OLDEST, undelivered)
        val commands = Channel(AgentEventLimits.LIVE_BUFFER_SIZE, onUndeliveredElement = undelivered)

        fun dropped(event: AgentEventEnvelope, reason: String) {
            if (acceptsClientCommands && !event.durable && event.isPublicClientEvent()) metrics?.dropped(reason)
        }
    }

    private val subscribers =
        ConcurrentHashMap<AgentEventStreamKey, MutableSet<Subscriber>>()

    fun hasSubscriber(userId: String, chatId: UUID): Boolean =
        subscribers[AgentEventStreamKey(userId, chatId)]?.any { it.acceptsClientCommands } == true

    suspend fun subscribe(userId: String, chatId: UUID, acceptsClientCommands: Boolean = true): AgentEventSubscription {
        val key = AgentEventStreamKey(userId = userId, chatId = chatId)
        val subscriber = Subscriber(acceptsClientCommands, metrics)
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
                subscriber.events.cancel()
                subscriber.commands.cancel()
            },
        )
    }

    fun publishCommand(event: AgentEventEnvelope): Boolean {
        val targets = subscribers[AgentEventStreamKey(event.userId, event.chatId)] ?: return false
        var accepted = false
        targets.forEach { if (it.acceptsClientCommands && it.commands.trySend(event).isSuccess) accepted = true }
        return accepted
    }

    suspend fun publish(event: AgentEventEnvelope) {
        val key = AgentEventStreamKey(userId = event.userId, chatId = event.chatId)
        // Iterate the concurrent set directly; toList's size-based fast path races with disconnect.
        val targets = subscribers[key] ?: return
        val closedTargets = ArrayList<Subscriber>()
        targets.forEach { subscriber ->
            if (subscriber.events.trySend(event).isFailure) {
                subscriber.dropped(event, "disconnect")
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
