package ru.souz.backend.client

import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.souz.backend.channels.ChannelDeliveryService
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.events.bus.AgentEventBus
import ru.souz.backend.events.bus.AgentEventLimits
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.events.model.PublicToolCallStartedPayload
import ru.souz.backend.toolcall.repository.ToolCallRepository
import ru.souz.backend.toolcall.repository.ToolCallContext
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper

@OptIn(ExperimentalCoroutinesApi::class)
class BackendChannelToolsTest {
    @Test
    fun `channel publication waits for source acknowledgement but not for an HTTP runtime`() = runTest {
        with(Fixture()) {
            val subscription = bus.subscribe(target.userId, target.id)
            registry.register(source, ClientDevice(target.userId, "device", "tv_box", emptySet()), "submit")
            try {
                val pending = async(start = CoroutineStart.UNDISPATCHED) { skill.invoke(call, meta) }
                assertTrue(subscription.commands.tryReceive().isFailure)
                assertFalse(pending.isCompleted)
                registry.ackSent(source, "submit")
                subscription.commands.receive()
                pending.cancelAndJoin()
                registry.discard(source)

                val http = async(start = CoroutineStart.UNDISPATCHED) { skill.invoke(call, meta) }
                val command = subscription.commands.receive()
                http.cancelAndJoin()
                repeat(AgentEventLimits.LIVE_BUFFER_SIZE) { assertTrue(bus.publishCommand(command)) }
                assertTrue(skill.invoke(call, meta).content.contains("client_tool_busy"))
                assertTrue(registry.isEmpty())
            } finally {
                subscription.close()
            }
        }
    }

    @Test
    fun `receipt wins timeout before ACK and callers are released on ACK failure cancellation or expiry`() = runTest {
        for (finish in listOf("ack", "write failure", "cancellation", "timeout")) {
            with(Fixture()) {
                val subscription = bus.subscribe(target.userId, target.id)
                try {
                    val pending = async(start = CoroutineStart.UNDISPATCHED) { skill.invoke(call, meta) }
                    val command = subscription.commands.receive()
                    val payload = assertIs<PublicToolCallStartedPayload>(command.payload)
                    val frame = ToolResultFrame("tool.result", target.id.toString(), command.executionId.toString(),
                        payload.toolCallId, "succeeded", restJsonMapper.readTree("{\"reply\":\"done\"}"))
                    // Queue the timeout but receive the result before its cancellation is processed.
                    advanceTimeBy(60_000)
                    if (finish == "timeout") {
                        val context = ToolCallContext(target.userId, frame.chatId, frame.threadId, frame.toolCallId)
                        assertNull(registry.acceptChannelTool(context, Instant.parse(payload.deadlineAt)))
                        runCurrent()
                        assertTrue(pending.await().content.contains("client_tool_timed_out"))
                    } else {
                        val handled = service.handleToolResult(target, frame)
                        assertEquals("accepted", assertIs<ToolResultAck>(handled.response).status)
                        advanceTimeBy(60_000)
                        runCurrent()
                        assertFalse(pending.isCompleted, finish)
                        assertEquals("rejected", assertIs<ToolResultAck>(service.handleToolResult(target, frame).response).status)
                        when (finish) {
                            "ack" -> {
                                handled.afterSend()
                                assertEquals(frame.result.toString(), pending.await().content)
                            }
                            "write failure" -> {
                                handled.onSendFailure()
                                assertTrue(pending.await().content.contains("client_tool_failed"))
                            }
                            else -> {
                                pending.cancelAndJoin()
                                handled.afterSend()
                            }
                        }
                    }
                    assertTrue(registry.isEmpty())
                    assertEquals("rejected", assertIs<ToolResultAck>(service.handleToolResult(target, frame).response).status)
                } finally {
                    subscription.close()
                }
            }
        }
    }

    private class Fixture {
        val source = UUID.randomUUID()
        val target = Chat(UUID.randomUUID(), "user", null, false, Instant.EPOCH, Instant.EPOCH)
        val chats = mockk<ChatRepository> {
            coEvery { get(target.userId, target.id) } returns target
        }
        val tools = mockk<ToolCallRepository> { coEvery { get(any<ToolCallContext>()) } returns null }
        val bus = AgentEventBus()
        val events = AgentEventService(chats, mockk(), bus)
        val registry = ClientThreadRuntimeRegistry()
        val startedAt = Instant.now()
        val skill = BackendClientSkills(registry, tools, events, ChannelDeliveryService(chats, mockk(), events), now = { startedAt })
            .toolsByCategory.values.firstNotNullOf { it["orion.call"] }
        val service = PublicClientService(chats, mockk(), mockk(), tools, mockk(), registry)
        val call = LLMResponse.FunctionCall("orion.call", mapOf("channelId" to target.id.toString(), "utterance" to "play"))
        val meta = ToolInvocationMeta(target.userId, requestId = source.toString())
    }
}
