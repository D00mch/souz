package ru.souz.backend.http

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import ru.souz.backend.events.model.AgentEvent
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.model.MessageCreatedPayload
import ru.souz.backend.events.model.ThreadCompletedPayload

class BackendV1DtosPublicEventTest {
    @Test
    fun `toPublicDto keeps threadId null only for the out-of-band message created case`() {
        val event = AgentEvent(
            id = UUID.randomUUID(),
            userId = "user-1",
            chatId = UUID.randomUUID(),
            executionId = null,
            seq = 1,
            type = AgentEventType.MESSAGE_CREATED,
            payload = MessageCreatedPayload(UUID.randomUUID(), 1, "assistant", "hello"),
            createdAt = Instant.now(),
        )

        assertNull(event.toPublicDto().threadId)
    }

    @Test
    fun `toPublicDto still fails fast for a thread-scoped event with no execution id`() {
        val event = AgentEvent(
            id = UUID.randomUUID(),
            userId = "user-1",
            chatId = UUID.randomUUID(),
            executionId = null,
            seq = 1,
            type = AgentEventType.THREAD_COMPLETED,
            payload = ThreadCompletedPayload("done"),
            createdAt = Instant.now(),
        )

        assertFailsWith<IllegalArgumentException> { event.toPublicDto() }
    }

    @Test
    fun `toPublicDto keeps a non-null threadId for a normal thread-scoped event`() {
        val executionId = UUID.randomUUID()
        val event = AgentEvent(
            id = UUID.randomUUID(),
            userId = "user-1",
            chatId = UUID.randomUUID(),
            executionId = executionId,
            seq = 1,
            type = AgentEventType.THREAD_COMPLETED,
            payload = ThreadCompletedPayload("done"),
            createdAt = Instant.now(),
        )

        assertEquals(executionId.toString(), event.toPublicDto().threadId)
    }
}
