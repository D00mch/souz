package ru.souz.backend.events.bus

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import ru.souz.backend.events.model.AgentEvent
import ru.souz.backend.events.model.AgentEventType

class AgentEventBusTest {
    @Test
    fun `slow subscriber sees only the latest bounded live window and publisher does not block`() = runTest {
        val userId = "user-a"
        val chatId = UUID.randomUUID()
        val bus = AgentEventBus()
        val subscription = bus.subscribe(userId = userId, chatId = chatId)
        val totalEvents = AgentEventLimits.LIVE_BUFFER_SIZE + 32

        try {
            withTimeout(1_000) {
                repeat(totalEvents) { index ->
                    bus.publish(
                        event(
                            userId = userId,
                            chatId = chatId,
                            seq = index + 1L,
                        )
                    )
                }
            }

            val receivedSeqs = buildList<Long> {
                repeat(AgentEventLimits.LIVE_BUFFER_SIZE) {
                    add(subscription.events.receive().seq)
                }
            }
            val expectedFirstSeq = (totalEvents - AgentEventLimits.LIVE_BUFFER_SIZE + 1).toLong()

            assertEquals((expectedFirstSeq..totalEvents.toLong()).toList(), receivedSeqs)
            assertTrue(subscription.events.tryReceive().isFailure)
        } finally {
            subscription.close()
        }
    }

    private fun event(
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
        payload = mapOf("seq" to seq.toString()),
        createdAt = Instant.parse("2026-05-02T10:00:00Z"),
    )
}
