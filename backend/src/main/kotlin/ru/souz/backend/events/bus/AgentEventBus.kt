package ru.souz.backend.events.bus

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.Channel
import ru.souz.backend.events.model.isPublicClientEvent
import ru.souz.backend.events.model.AgentEventEnvelope

class AgentEventBus(private val metrics: ru.souz.backend.metrics.BackendMetrics? = null) {
    private class Subscriber(val acceptsClientCommands: Boolean, metrics: ru.souz.backend.metrics.BackendMetrics?) {
        val events = Channel<AgentEventEnvelope>(AgentEventLimits.LIVE_BUFFER_SIZE, onUndeliveredElement = {
            if (!it.durable && it.isPublicClientEvent() && acceptsClientCommands) metrics?.dropped("disconnect")
        })
        val commands = Channel<AgentEventEnvelope>(AgentEventLimits.LIVE_BUFFER_SIZE, onUndeliveredElement = {
            if (acceptsClientCommands && it.isPublicClientEvent()) metrics?.dropped("disconnect")
        })
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
            val sent = subscriber.events.trySend(event)
            if (sent.isClosed) {
                if (!event.durable && event.isPublicClientEvent() && subscriber.acceptsClientCommands) metrics?.dropped("disconnect")
                closedTargets += subscriber
            } else if (sent.isFailure) {
                // Preserve DROP_OLDEST behavior while counting actual evictions of live events.
                subscriber.events.tryReceive().getOrNull()?.let {
                    if (!it.durable && it.isPublicClientEvent() && subscriber.acceptsClientCommands) metrics?.dropped("queue_overflow")
                }
                val retry = subscriber.events.trySend(event)
                if (retry.isFailure && !event.durable && event.isPublicClientEvent() && subscriber.acceptsClientCommands) {
                    metrics?.dropped(if (retry.isClosed) "disconnect" else "queue_overflow")
                }
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
