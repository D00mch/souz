package ru.souz.backend.http.routes

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.souz.backend.events.model.AgentEvent
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.model.MessageCreatedPayload
import ru.souz.backend.events.model.MessageDeltaPayload

class EventRoutesPublicFilterTest {
    private fun messageCreatedEvent(executionId: UUID?): AgentEvent = AgentEvent(
        id = UUID.randomUUID(),
        userId = "user-1",
        chatId = UUID.randomUUID(),
        executionId = executionId,
        seq = 1,
        type = AgentEventType.MESSAGE_CREATED,
        payload = MessageCreatedPayload(UUID.randomUUID(), 1, "assistant", "hello"),
        createdAt = Instant.now(),
    )

    @Test
    fun `message created without an execution id is admitted as an out-of-band channel push`() {
        assertTrue(messageCreatedEvent(executionId = null).isPublicClientEvent())
    }

    @Test
    fun `message created with an execution id stays filtered out, exactly as before`() {
        assertFalse(messageCreatedEvent(executionId = UUID.randomUUID()).isPublicClientEvent())
    }

    @Test
    fun `unrelated internal event types stay filtered out`() {
        val event = AgentEvent(
            id = UUID.randomUUID(),
            userId = "user-1",
            chatId = UUID.randomUUID(),
            executionId = null,
            seq = 1,
            type = AgentEventType.MESSAGE_DELTA,
            payload = MessageDeltaPayload(UUID.randomUUID(), "delta"),
            createdAt = Instant.now(),
        )

        assertFalse(event.isPublicClientEvent())
    }
}
