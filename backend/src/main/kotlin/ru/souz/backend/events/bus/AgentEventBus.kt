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
        // Channel owns atomic eviction, including races with other publishers and receivers.
        val events = Channel<AgentEventEnvelope>(AgentEventLimits.LIVE_BUFFER_SIZE, BufferOverflow.DROP_OLDEST,
            onUndeliveredElement = { dropped(it, "undelivered") })
        val commands = Channel<AgentEventEnvelope>(AgentEventLimits.LIVE_BUFFER_SIZE,
            onUndeliveredElement = { dropped(it, "undelivered") })

        fun dropped(event: AgentEventEnvelope, reason: String) {
            if (acceptsClientCommands && !event.durable && event.isPublicClientEvent()) metrics?.dropped(reason)
        }
    }

    private val subscribers =
        ConcurrentHashMap<AgentEventStreamKey, Set<Subscriber>>()

    fun hasSubscriber(userId: String, chatId: UUID): Boolean =
        subscribers[AgentEventStreamKey(userId, chatId)]?.any { it.acceptsClientCommands } == true

    fun liveChatIds(userId: String): List<UUID> = subscribers.keys.asSequence()
        .filter { it.userId == userId && hasSubscriber(userId, it.chatId) }.map { it.chatId }.toList()

    suspend fun subscribe(userId: String, chatId: UUID, acceptsClientCommands: Boolean = true): AgentEventSubscription {
        val key = AgentEventStreamKey(userId = userId, chatId = chatId)
        val subscriber = Subscriber(acceptsClientCommands, metrics)
        subscribers.compute(key) { _, existing ->
            existing.orEmpty() + subscriber
        }
        return AgentEventSubscription(
            events = subscriber.events,
            commands = subscriber.commands,
            close = {
                subscribers.computeIfPresent(key) { _, existing ->
                    (existing - subscriber).takeUnless { it.isEmpty() }
                }
                subscriber.events.cancel()
                subscriber.commands.cancel()
            },
        )
    }

    fun publishCommand(event: AgentEventEnvelope): Boolean {
        val targets = subscribers[AgentEventStreamKey(event.userId, event.chatId)] ?: return false
        var accepted = false
        targets.forEach { subscriber ->
            if (subscriber.acceptsClientCommands) {
                val result = subscriber.commands.trySend(event)
                if (result.isSuccess) accepted = true
                else subscriber.dropped(event, if (result.isClosed) "disconnect" else "queue_full")
            }
        }
        return accepted
    }

    suspend fun publish(event: AgentEventEnvelope) {
        val key = AgentEventStreamKey(userId = event.userId, chatId = event.chatId)
        // Each publication keeps a stable snapshot while subscriptions change atomically per key.
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
        val closed = closedTargets.toSet()
        subscribers.computeIfPresent(key) { _, existing ->
            (existing - closed).takeUnless { it.isEmpty() }
        }
    }
}
