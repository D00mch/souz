package ru.souz.backend.http

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.backend.agent.runtime.BackendAgentRuntimeEventSink
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.events.service.AgentEventBus
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.execution.repository.AgentExecutionRepository
import ru.souz.backend.storage.memory.MemoryAgentEventRepository
import ru.souz.backend.storage.memory.MemoryAgentExecutionRepository
import ru.souz.backend.storage.memory.MemoryChatRepository
import ru.souz.backend.storage.memory.MemoryChoiceRepository
import ru.souz.backend.storage.memory.MemoryMessageRepository
import ru.souz.llms.LLMModel

class BackendPayloadRedactionTest {
    @Test
    fun `tool event payloads are redacted and truncated before persistence and transport`() = runTest {
        val chatRepository = MemoryChatRepository()
        val messageRepository: MessageRepository = MemoryMessageRepository()
        val executionRepository: AgentExecutionRepository = MemoryAgentExecutionRepository()
        val eventRepository = MemoryAgentEventRepository()
        val chat = Chat(
            id = UUID.randomUUID(),
            userId = "user-a",
            title = "Safety",
            archived = false,
            createdAt = Instant.parse("2026-05-01T10:00:00Z"),
            updatedAt = Instant.parse("2026-05-01T10:00:00Z"),
        )
        val execution = AgentExecution(
            id = UUID.randomUUID(),
            userId = "user-a",
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
            metadata = mapOf(
                "systemPrompt" to "api_key=should-not-leak",
                "contextSize" to "16000",
            ),
        )
        chatRepository.create(chat)
        executionRepository.create(execution)
        val sink = BackendAgentRuntimeEventSink(
            userId = "user-a",
            chatId = chat.id,
            executionId = execution.id,
            messageRepository = messageRepository,
            choiceRepository = MemoryChoiceRepository(),
            executionRepository = executionRepository,
            eventService = AgentEventService(
                chatRepository = chatRepository,
                eventRepository = eventRepository,
                eventBus = AgentEventBus(),
            ),
            streamingMessagesEnabled = false,
            toolEventsEnabled = true,
        )

        sink.emit(
            AgentRuntimeEvent.ToolCallStarted(
                toolCallId = "tool-1",
                name = "SendHttpRequest",
                arguments = mapOf(
                    "authorization" to "Bearer top-secret-token",
                    "cookie" to "session=secret-session-value",
                    "payload" to "x".repeat(512),
                ),
            )
        )
        sink.emit(
            AgentRuntimeEvent.ToolCallFinished(
                toolCallId = "tool-1",
                name = "SendHttpRequest",
                resultPreview = "password=hunter2 " + "y".repeat(512),
                durationMs = 120,
            )
        )

        val events = eventRepository.listByChat("user-a", chat.id)
        val transportPayload = events.first().toDto().payload
        val responsePayload = events[1].toDto().payload
        val executionDto = execution.copy(metadata = mapOf("systemPrompt" to "leak-me")).toDto()

        assertTrue(events.all { event ->
            event.payload.values.none { value ->
                value.contains("top-secret-token") || value.contains("secret-session-value") || value.contains("hunter2")
            }
        })
        assertTrue(transportPayload["arguments"].toString().contains("[REDACTED]"))
        assertTrue(responsePayload["resultPreview"].toString().contains("[REDACTED]"))
        assertTrue(transportPayload["arguments"].toString().length <= 256)
        assertTrue(responsePayload["resultPreview"].toString().length <= 256)
        assertFalse(executionDto.metadata.containsKey("systemPrompt"))
        assertEquals(emptyMap(), executionDto.metadata)
    }
}
