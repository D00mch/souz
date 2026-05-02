package ru.souz.backend.agent.runtime

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.events.bus.AgentEventBus
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.storage.memory.MemoryAgentEventRepository
import ru.souz.backend.storage.memory.MemoryAgentExecutionRepository
import ru.souz.backend.storage.memory.MemoryChatRepository
import ru.souz.backend.storage.memory.MemoryMessageRepository
import ru.souz.backend.storage.memory.MemoryOptionRepository
import ru.souz.backend.storage.memory.MemoryToolCallRepository
import ru.souz.llms.LLMModel

class BackendAgentRuntimeEventSinkTest {
    @Test
    fun `llm delta publishes live event without persisting assistant message`() = runTest {
        val fixture = sinkFixture()
        val stream = fixture.eventService.openStream(userId = fixture.chat.userId, chatId = fixture.chat.id)

        try {
            fixture.sink.emit(AgentRuntimeEvent.LlmMessageDelta("chunk-1"))

            val liveEvent = withTimeout(1_000) { stream.liveEvents.receive() }
            val replayEvents = fixture.eventRepository.listByChat(fixture.chat.userId, fixture.chat.id)
            val messages = fixture.messageRepository.list(fixture.chat.userId, fixture.chat.id)
            val execution = fixture.executionRepository.get(fixture.chat.userId, fixture.execution.id)

            assertEquals(AgentEventType.MESSAGE_DELTA, liveEvent.type)
            assertFalse(liveEvent.durable)
            assertNull(liveEvent.seq)
            assertEquals("chunk-1", liveEvent.payload["delta"])
            assertTrue(replayEvents.isEmpty())
            assertTrue(messages.isEmpty())
            assertNull(execution?.assistantMessageId)
        } finally {
            stream.close()
        }
    }

    @Test
    fun `empty llm delta is ignored`() = runTest {
        val fixture = sinkFixture()
        val stream = fixture.eventService.openStream(userId = fixture.chat.userId, chatId = fixture.chat.id)

        try {
            fixture.sink.emit(AgentRuntimeEvent.LlmMessageDelta(""))

            val replayEvents = fixture.eventRepository.listByChat(fixture.chat.userId, fixture.chat.id)
            val messages = fixture.messageRepository.list(fixture.chat.userId, fixture.chat.id)
            val execution = fixture.executionRepository.get(fixture.chat.userId, fixture.execution.id)

            assertTrue(replayEvents.isEmpty())
            assertTrue(messages.isEmpty())
            assertNull(execution?.assistantMessageId)
            assertNoLiveEvent(stream)
        } finally {
            stream.close()
        }
    }

    @Test
    fun `successful completion creates one final assistant message after live deltas`() = runTest {
        val fixture = sinkFixture()

        fixture.sink.emit(AgentRuntimeEvent.LlmMessageDelta("Hello"))
        fixture.sink.emit(AgentRuntimeEvent.LlmMessageDelta(" world"))
        val completedMessage = fixture.sink.completeAssistantMessage("Hello world")

        val replayEvents = fixture.eventRepository.listByChat(fixture.chat.userId, fixture.chat.id)
        val messages = fixture.messageRepository.list(fixture.chat.userId, fixture.chat.id)
        val execution = fixture.executionRepository.get(fixture.chat.userId, fixture.execution.id)

        assertEquals(listOf(AgentEventType.MESSAGE_CREATED, AgentEventType.MESSAGE_COMPLETED), replayEvents.map { it.type })
        assertEquals(1, messages.size)
        assertEquals(completedMessage, messages.single())
        assertEquals("Hello world", messages.single().content)
        assertEquals(completedMessage.id, execution?.assistantMessageId)
    }

    @Test
    fun `failed execution after live deltas does not persist partial assistant message`() = runTest {
        val fixture = sinkFixture()

        fixture.sink.emit(AgentRuntimeEvent.LlmMessageDelta("partial"))
        fixture.sink.emitExecutionFailed(
            errorCode = "agent_execution_failed",
            errorMessage = "simulated failure",
        )

        val replayEvents = fixture.eventRepository.listByChat(fixture.chat.userId, fixture.chat.id)
        val messages = fixture.messageRepository.list(fixture.chat.userId, fixture.chat.id)
        val execution = fixture.executionRepository.get(fixture.chat.userId, fixture.execution.id)

        assertEquals(listOf(AgentEventType.EXECUTION_FAILED), replayEvents.map { it.type })
        assertTrue(messages.isEmpty())
        assertNull(execution?.assistantMessageId)
    }

    @Test
    fun `cancelled execution after live deltas does not persist partial assistant message`() = runTest {
        val fixture = sinkFixture()

        fixture.sink.emit(AgentRuntimeEvent.LlmMessageDelta("partial"))
        fixture.sink.emitExecutionCancelled()

        val replayEvents = fixture.eventRepository.listByChat(fixture.chat.userId, fixture.chat.id)
        val messages = fixture.messageRepository.list(fixture.chat.userId, fixture.chat.id)
        val execution = fixture.executionRepository.get(fixture.chat.userId, fixture.execution.id)

        assertEquals(listOf(AgentEventType.EXECUTION_CANCELLED), replayEvents.map { it.type })
        assertTrue(messages.isEmpty())
        assertNull(execution?.assistantMessageId)
    }
}

private suspend fun assertNoLiveEvent(stream: ru.souz.backend.events.bus.AgentEventStream) {
    val result = withTimeout(250) { stream.liveEvents.tryReceive().getOrNull() }
    assertNull(result)
}

private suspend fun sinkFixture(): SinkFixture {
    val chatRepository = MemoryChatRepository()
    val messageRepository = MemoryMessageRepository()
    val executionRepository = MemoryAgentExecutionRepository()
    val eventRepository = MemoryAgentEventRepository()
    val chat = Chat(
        id = UUID.randomUUID(),
        userId = "user-a",
        title = "Streaming sink",
        archived = false,
        createdAt = Instant.parse("2026-05-01T10:00:00Z"),
        updatedAt = Instant.parse("2026-05-01T10:00:00Z"),
    )
    val execution = AgentExecution(
        id = UUID.randomUUID(),
        userId = chat.userId,
        chatId = chat.id,
        userMessageId = null,
        assistantMessageId = null,
        status = AgentExecutionStatus.RUNNING,
        requestId = null,
        clientMessageId = null,
        model = LLMModel.OpenAIGpt52,
        provider = LLMModel.OpenAIGpt52.provider,
        startedAt = Instant.parse("2026-05-01T10:01:00Z"),
        finishedAt = null,
        cancelRequested = false,
        errorCode = null,
        errorMessage = null,
        usage = null,
        metadata = emptyMap(),
    )
    chatRepository.create(chat)
    executionRepository.create(execution)
    val eventService = AgentEventService(
        chatRepository = chatRepository,
        eventRepository = eventRepository,
        eventBus = AgentEventBus(),
    )
    return SinkFixture(
        chat = chat,
        execution = execution,
        messageRepository = messageRepository,
        executionRepository = executionRepository,
        eventRepository = eventRepository,
        eventService = eventService,
        sink = BackendAgentRuntimeEventSink(
            userId = chat.userId,
            chatId = chat.id,
            executionId = execution.id,
            messageRepository = messageRepository,
            optionRepository = MemoryOptionRepository(),
            executionRepository = executionRepository,
            eventService = eventService,
            toolCallRepository = MemoryToolCallRepository(),
            streamingMessagesEnabled = true,
            toolEventsEnabled = false,
        ),
    )
}

private data class SinkFixture(
    val chat: Chat,
    val execution: AgentExecution,
    val messageRepository: MemoryMessageRepository,
    val executionRepository: MemoryAgentExecutionRepository,
    val eventRepository: MemoryAgentEventRepository,
    val eventService: AgentEventService,
    val sink: BackendAgentRuntimeEventSink,
)
