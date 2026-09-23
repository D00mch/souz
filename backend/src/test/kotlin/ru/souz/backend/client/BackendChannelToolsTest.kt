package ru.souz.backend.client

import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import ru.souz.backend.channels.ChannelDeliveryService
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.events.bus.AgentEventBus
import ru.souz.backend.events.service.AgentEventService
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta

class BackendChannelToolsTest {
    @Test
    fun `channel publication waits for source acknowledgement but not for an HTTP runtime`() = runTest {
        val source = UUID.randomUUID()
        val target = Chat(UUID.randomUUID(), "user", null, false, Instant.EPOCH, Instant.EPOCH)
        val chats = mockk<ChatRepository>()
        coEvery { chats.get(target.userId, target.id) } returns target
        val bus = AgentEventBus()
        val events = AgentEventService(chats, mockk(), bus)
        val registry = ClientThreadRuntimeRegistry()
        val skill = BackendClientSkills(registry, mockk(), events, ChannelDeliveryService(chats, mockk(), events))
            .toolsByCategory.values.firstNotNullOf { it["orion.call"] }
        val call = LLMResponse.FunctionCall("orion.call", mapOf("channelId" to target.id.toString(), "utterance" to "play"))
        val meta = ToolInvocationMeta(target.userId, requestId = source.toString())
        val subscription = bus.subscribe(target.userId, target.id)
        registry.register(source, ClientDevice(target.userId, "device", "tv_box", emptySet()), "submit")
        try {
            val pending = async(start = CoroutineStart.UNDISPATCHED) { skill.invoke(call, meta) }
            assertTrue(subscription.events.tryReceive().isFailure)
            assertFalse(pending.isCompleted)
            registry.ackSent(source, "submit")
            subscription.events.receive()
            pending.cancelAndJoin()
            registry.discard(source)

            val http = async(start = CoroutineStart.UNDISPATCHED) { skill.invoke(call, meta) }
            subscription.events.receive()
            http.cancelAndJoin()
            assertTrue(registry.isEmpty())
        } finally {
            subscription.close()
        }
    }
}
