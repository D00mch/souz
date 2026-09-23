package ru.souz.backend.events.bus

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ru.souz.backend.events.model.AgentEvent
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.model.AgentLiveEvent
import ru.souz.backend.events.model.RawAgentEventPayload

class AgentEventBusTest {
    @Test
    fun `slow subscriber keeps commands rejects overflow and bounds droppable notifications`() = runTest {
        val userId = "user-a"
        val chatId = UUID.randomUUID()
        val bus = AgentEventBus()
        val subscription = bus.subscribe(userId = userId, chatId = chatId)
        val totalEvents = AgentEventLimits.LIVE_BUFFER_SIZE + 32
        val command = AgentLiveEvent(UUID.randomUUID(), userId, chatId, null,
            AgentEventType.TOOL_CALL_STARTED, RawAgentEventPayload(emptyMap()), Instant.EPOCH)

        try {
            repeat(AgentEventLimits.LIVE_BUFFER_SIZE) { assertTrue(bus.publishCommand(command)) }
            assertFalse(bus.publishCommand(command))
            // A full subscriber must not prevent another subscription from accepting a command.
            val available = bus.subscribe(userId, chatId)
            try {
                assertTrue(bus.publishCommand(command))
                assertEquals(command, available.commands.receive())
            } finally {
                available.close()
            }
            withTimeout(1_000) {
                repeat(totalEvents) { index ->
                    bus.publish(
                        durableEvent(
                            userId = userId,
                            chatId = chatId,
                            seq = index + 1L,
                        )
                    )
                }
            }

            val receivedSeqs = buildList<Long> {
                repeat(AgentEventLimits.LIVE_BUFFER_SIZE) {
                    add(subscription.events.receive().seq ?: error("Expected durable event seq"))
                }
            }
            val expectedFirstSeq = (totalEvents - AgentEventLimits.LIVE_BUFFER_SIZE + 1).toLong()

            assertEquals((expectedFirstSeq..totalEvents.toLong()).toList(), receivedSeqs)
            assertTrue(subscription.events.tryReceive().isFailure)
            repeat(AgentEventLimits.LIVE_BUFFER_SIZE) { assertEquals(command, subscription.commands.receive()) }
            assertTrue(subscription.commands.tryReceive().isFailure)
            assertTrue(bus.publishCommand(command))
        } finally {
            subscription.close()
        }
        assertFalse(bus.publishCommand(command))
    }

    @Test
    fun `concurrent subscribers on the same stream all receive the published event`() = runTest {
        val userId = "user-a"
        val chatId = UUID.randomUUID()
        val bus = AgentEventBus()

        val subscriptions = withContext(Dispatchers.Default) {
            List(32) {
                async {
                    bus.subscribe(userId = userId, chatId = chatId)
                }
            }.awaitAll()
        }

        try {
            val event = durableEvent(userId = userId, chatId = chatId, seq = 1L)

            bus.publish(event)

            subscriptions.forEach { subscription ->
                assertEquals(event, withTimeout(1_000) { subscription.events.receive() })
            }
        } finally {
            subscriptions.forEach { subscription -> subscription.close() }
        }
    }

    @Test
    fun `publication tolerates concurrent subscription and disconnect`() = runTest {
        val bus = AgentEventBus()
        val event = durableEvent("user-churn", UUID.randomUUID(), 1L)
        withContext(Dispatchers.Default) {
            listOf(
                async { repeat(20_000) { bus.subscribe(event.userId, event.chatId).close() } },
                async { repeat(20_000) { bus.publish(event) } },
            ).awaitAll()
        }
        val remaining = bus.subscribe(event.userId, event.chatId)
        try {
            bus.publish(event)
            assertEquals(event, withTimeout(1_000) { remaining.events.receive() })
        } finally {
            remaining.close()
        }
    }

    private fun durableEvent(
        userId: String,
        chatId: UUID,
        seq: Long,
    ): AgentEvent = AgentEvent(
        id = UUID.randomUUID(),
        userId = userId,
        chatId = chatId,
        executionId = null,
        seq = seq,
        type = AgentEventType.MESSAGE_DELTA,
        payload = RawAgentEventPayload(mapOf("seq" to seq.toString())),
        createdAt = Instant.parse("2026-05-02T10:00:00Z"),
    )
}
