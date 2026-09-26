package ru.souz.backend.http

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import ru.souz.backend.events.bus.AgentEventStream
import ru.souz.backend.events.model.AgentEvent
import ru.souz.backend.events.model.AgentEventEnvelope
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.model.AgentLiveEvent
import ru.souz.backend.events.model.AssistantMessagePayload
import ru.souz.backend.events.model.PublicToolCallStartedPayload
import ru.souz.backend.events.model.ThreadCompletedPayload
import ru.souz.backend.http.routes.forwardPublicEvents
import ru.souz.llms.restJsonMapper

class PublicClientEventStreamTest {
    @Test
    fun `catch-up suppresses progress overtaken by tools or completion including initial replay`() = runTest {
        for (initialReplay in listOf(false, true)) for (tool in listOf(false, true)) {
            val live = Channel<AgentEventEnvelope>(Channel.UNLIMITED)
            val progress = progress()
            val durable = AgentEvent(
                UUID.randomUUID(), progress.userId, progress.chatId, progress.executionId, 6,
                if (tool) AgentEventType.TOOL_CALL_STARTED else AgentEventType.THREAD_COMPLETED,
                if (tool) PublicToolCallStartedPayload("tool", "user.ask", arguments = restJsonMapper.createObjectNode())
                else ThreadCompletedPayload("done"),
                Instant.EPOCH,
            )
            var stored = if (initialReplay) listOf(durable) else emptyList()
            val stream = AgentEventStream(
                replay = stored, liveEvents = live, commands = Channel(), close = {},
                replayAfter = { after -> stored.filter { it.seq > after } }, initialSeq = 5,
            )
            val ready = CompletableDeferred<Unit>()
            val sent = mutableListOf<AgentEventEnvelope>()
            val forwarding = async { stream.forwardPublicEvents(ready) { sent += it } }
            ready.await()
            stored = listOf(durable)
            live.send(progress)
            live.send(durable)
            live.close()
            forwarding.await()
            assertEquals<List<AgentEventEnvelope>>(listOf(durable), sent)
        }
    }

    @Test
    fun `live blocks preserve order and repetition without advancing replay cursor`() = runTest {
        val first = progress()
        val second = first.copy(id = UUID.randomUUID(), payload = AssistantMessagePayload("Second"))
        val repeated = first.copy(id = UUID.randomUUID())
        val live = Channel<AgentEventEnvelope>(Channel.UNLIMITED)
        val blocks = listOf(first, second, repeated)
        blocks.forEach { live.send(it) }
        live.close()
        val cursors = mutableListOf<Long>()
        val stream = AgentEventStream(
            replay = emptyList(), liveEvents = live, commands = Channel(), close = {},
            replayAfter = { cursors += it; emptyList() }, initialSeq = 5,
        )
        val sent = mutableListOf<AgentEventEnvelope>()
        stream.forwardPublicEvents(CompletableDeferred()) { sent += it }
        assertEquals<List<AgentEventEnvelope>>(blocks, sent)
        assertEquals(listOf(5L, 5L, 5L, 5L), cursors)
        assertEquals(mapOf("content" to "Working"), first.toPublicDto().payload)
        assertEquals(null, first.toPublicDto().seq)
    }

    private fun progress() = AgentLiveEvent(
        UUID.randomUUID(), "user", UUID.randomUUID(), UUID.randomUUID(),
        AgentEventType.ASSISTANT_MESSAGE, AssistantMessagePayload("Working"), Instant.EPOCH,
        discardAfterSeq = 5,
    )
}
