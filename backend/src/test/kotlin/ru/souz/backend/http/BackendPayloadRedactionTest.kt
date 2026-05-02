package ru.souz.backend.http

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
import ru.souz.backend.storage.memory.MemoryOptionRepository
import ru.souz.backend.storage.memory.MemoryMessageRepository
import ru.souz.backend.storage.memory.MemoryToolCallRepository
import ru.souz.backend.toolcall.model.ToolCallStatus
import ru.souz.llms.LLMModel
import ru.souz.llms.restJsonMapper

class BackendPayloadRedactionTest {
    @Test
    fun `started event redacts secrets recursively and truncates long values before storage and transport`() = runTest {
        val fixture = toolEventFixture()

        fixture.sink.emit(
            AgentRuntimeEvent.ToolCallStarted(
                toolCallId = "tool-1",
                name = "SendHttpRequest",
                arguments = mapOf(
                    "chatId" to "123",
                    "token" to SK_TEST_SECRET,
                    "nested" to mapOf(
                        "Authorization" to BEARER_SECRET,
                        "items" to listOf(
                            mapOf("refresh_token" to REFRESH_SECRET),
                            mapOf("note" to "hello", "privateKey" to PRIVATE_KEY_SECRET),
                        ),
                    ),
                    "payload" to "x".repeat(2_048),
                ),
            )
        )

        val storedToolCall = fixture.toolCallRepository.get(
            userId = fixture.userId,
            chatId = fixture.chat.id,
            executionId = fixture.execution.id,
            toolCallId = "tool-1",
        )
        val event = fixture.eventRepository.listByChat(fixture.userId, fixture.chat.id).single()
        val transportPayload = event.toDto().payload
        val argumentsPreview = transportPayload["argumentsPreview"]

        assertNotNull(storedToolCall)
        assertEquals(ToolCallStatus.RUNNING, storedToolCall.status)
        assertEquals("tool-1", storedToolCall.toolCallId)
        assertNull(storedToolCall.finishedAt)
        assertNull(storedToolCall.durationMs)
        assertNoSampleSecrets(event.payload.values.joinToString(separator = " "))
        assertNoSampleSecrets(storedToolCall.argumentsJson)
        assertEquals("tool.call.started", event.type.value)
        assertEquals("tool-1", transportPayload["toolCallId"])
        assertEquals("SendHttpRequest", transportPayload["name"])

        val storedArguments = restJsonMapper.readTree(storedToolCall.argumentsJson)
        assertEquals("[REDACTED]", storedArguments["token"].asText())
        assertEquals("[REDACTED]", storedArguments["nested"]["Authorization"].asText())
        assertEquals("[REDACTED]", storedArguments["nested"]["items"][0]["refresh_token"].asText())
        assertEquals("[REDACTED]", storedArguments["nested"]["items"][1]["privateKey"].asText())
        assertTrue(storedArguments["payload"].asText().length < 2_048)

        val transportArguments: JsonNode = restJsonMapper.valueToTree(argumentsPreview)
        assertEquals("[REDACTED]", transportArguments["token"].asText())
        assertEquals("[REDACTED]", transportArguments["nested"]["Authorization"].asText())
        assertTrue(transportArguments["payload"].asText().length < 2_048)
        assertNoSampleSecrets(transportPayload.toString())
    }

    @Test
    fun `finished event redacts result preview updates audit record and keeps same toolCallId`() = runTest {
        val fixture = toolEventFixture()

        fixture.sink.emit(
            AgentRuntimeEvent.ToolCallStarted(
                toolCallId = "tool-2",
                name = "CalendarRead",
                arguments = mapOf("token" to SK_TEST_SECRET),
            )
        )
        fixture.sink.emit(
            AgentRuntimeEvent.ToolCallFinished(
                toolCallId = "tool-2",
                name = "CalendarRead",
                result = """
                    {
                      "access_token": "$SK_TEST_SECRET",
                      "items": [
                        {
                          "title": "${"y".repeat(2_048)}"
                        }
                      ]
                    }
                """.trimIndent(),
                durationMs = 42,
            )
        )

        val events = fixture.eventRepository.listByChat(fixture.userId, fixture.chat.id)
        val finishedEvent = events.last()
        val transportPayload = finishedEvent.toDto().payload
        val storedToolCall = fixture.toolCallRepository.get(
            userId = fixture.userId,
            chatId = fixture.chat.id,
            executionId = fixture.execution.id,
            toolCallId = "tool-2",
        )

        assertNotNull(storedToolCall)
        assertEquals(ToolCallStatus.FINISHED, storedToolCall.status)
        assertEquals("tool-2", storedToolCall.toolCallId)
        assertEquals(42L, storedToolCall.durationMs)
        assertNotNull(storedToolCall.finishedAt)
        assertEquals("tool.call.finished", finishedEvent.type.value)
        assertEquals("tool-2", transportPayload["toolCallId"])
        assertEquals("finished", transportPayload["status"])
        assertEquals(42L, transportPayload["durationMs"])
        assertNoSampleSecrets(finishedEvent.payload.values.joinToString(separator = " "))
        assertNoSampleSecrets(storedToolCall.resultPreview.orEmpty())
        assertNoSampleSecrets(transportPayload.toString())

        val storedPreview = restJsonMapper.readTree(storedToolCall.resultPreview)
        assertEquals("[REDACTED]", storedPreview["access_token"].asText())
        assertTrue(storedPreview["items"][0]["title"].asText().length < 2_048)
    }

    @Test
    fun `failed event stores safe error preview without stacktrace or raw secrets`() = runTest {
        val fixture = toolEventFixture()
        val failure = IllegalStateException("token=$SK_TEST_SECRET $BEARER_SECRET $REFRESH_SECRET $PRIVATE_KEY_SECRET")
        failure.stackTrace = arrayOf(
            StackTraceElement("DangerousStack", "leak", "Dangerous.kt", 99),
        )

        fixture.sink.emit(
            AgentRuntimeEvent.ToolCallStarted(
                toolCallId = "tool-3",
                name = "SecretsTool",
                arguments = mapOf("auth" to BEARER_SECRET),
            )
        )
        fixture.sink.emit(
            AgentRuntimeEvent.ToolCallFailed(
                toolCallId = "tool-3",
                name = "SecretsTool",
                error = failure,
                durationMs = 7,
            )
        )

        val events = fixture.eventRepository.listByChat(fixture.userId, fixture.chat.id)
        val failedEvent = events.last()
        val transportPayload = failedEvent.toDto().payload
        val storedToolCall = fixture.toolCallRepository.get(
            userId = fixture.userId,
            chatId = fixture.chat.id,
            executionId = fixture.execution.id,
            toolCallId = "tool-3",
        )

        assertNotNull(storedToolCall)
        assertEquals(ToolCallStatus.FAILED, storedToolCall.status)
        assertEquals(7L, storedToolCall.durationMs)
        assertEquals("tool-3", transportPayload["toolCallId"])
        assertEquals("failed", transportPayload["status"])
        assertEquals(7L, transportPayload["durationMs"])
        assertNoSampleSecrets(storedToolCall.error.orEmpty())
        assertNoSampleSecrets(failedEvent.payload.values.joinToString(separator = " "))
        assertNoSampleSecrets(transportPayload.toString())
        assertFalse(storedToolCall.error.orEmpty().contains("DangerousStack"))
        assertFalse(storedToolCall.error.orEmpty().contains("Dangerous.kt"))
    }

    @Test
    fun `tool events feature flag off still stores audit row but does not emit frontend events`() = runTest {
        val fixture = toolEventFixture(toolEventsEnabled = false)

        fixture.sink.emit(
            AgentRuntimeEvent.ToolCallStarted(
                toolCallId = "tool-4",
                name = "DisabledTool",
                arguments = mapOf("authorization" to BEARER_SECRET),
            )
        )
        fixture.sink.emit(
            AgentRuntimeEvent.ToolCallFinished(
                toolCallId = "tool-4",
                name = "DisabledTool",
                result = mapOf("token" to SK_TEST_SECRET),
                durationMs = 0,
            )
        )

        val storedToolCall = fixture.toolCallRepository.get(
            userId = fixture.userId,
            chatId = fixture.chat.id,
            executionId = fixture.execution.id,
            toolCallId = "tool-4",
        )

        assertNotNull(storedToolCall)
        assertEquals(ToolCallStatus.FINISHED, storedToolCall.status)
        assertNoSampleSecrets(storedToolCall.argumentsJson)
        assertNoSampleSecrets(storedToolCall.resultPreview.orEmpty())
        assertTrue(fixture.eventRepository.listByChat(fixture.userId, fixture.chat.id).isEmpty())
    }

    @Test
    fun `execution dto never exposes internal metadata`() {
        val executionDto = agentExecution().copy(metadata = mapOf("systemPrompt" to "leak-me")).toDto()

        assertFalse(executionDto.metadata.containsKey("systemPrompt"))
        assertEquals(emptyMap(), executionDto.metadata)
    }

    private suspend fun toolEventFixture(
        toolEventsEnabled: Boolean = true,
    ): ToolEventFixture {
        val chatRepository = MemoryChatRepository()
        val messageRepository: MessageRepository = MemoryMessageRepository()
        val executionRepository: AgentExecutionRepository = MemoryAgentExecutionRepository()
        val eventRepository = MemoryAgentEventRepository()
        val toolCallRepository = MemoryToolCallRepository()
        val chat = chat()
        val execution = agentExecution(chatId = chat.id)
        chatRepository.create(chat)
        executionRepository.create(execution)
        return ToolEventFixture(
            userId = "user-a",
            chat = chat,
            execution = execution,
            eventRepository = eventRepository,
            toolCallRepository = toolCallRepository,
            sink = BackendAgentRuntimeEventSink(
                userId = "user-a",
                chatId = chat.id,
                executionId = execution.id,
                messageRepository = messageRepository,
                optionRepository = MemoryOptionRepository(),
                executionRepository = executionRepository,
                eventService = AgentEventService(
                    chatRepository = chatRepository,
                    eventRepository = eventRepository,
                    eventBus = AgentEventBus(),
                ),
                toolCallRepository = toolCallRepository,
                streamingMessagesEnabled = false,
                toolEventsEnabled = toolEventsEnabled,
            ),
        )
    }

    private fun chat(): Chat =
        Chat(
            id = UUID.randomUUID(),
            userId = "user-a",
            title = "Safety",
            archived = false,
            createdAt = Instant.parse("2026-05-01T10:00:00Z"),
            updatedAt = Instant.parse("2026-05-01T10:00:00Z"),
        )

    private fun agentExecution(
        chatId: UUID = UUID.randomUUID(),
    ): AgentExecution =
        AgentExecution(
            id = UUID.randomUUID(),
            userId = "user-a",
            chatId = chatId,
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

    private fun assertNoSampleSecrets(raw: String) {
        assertFalse(raw.contains(SK_TEST_SECRET), raw)
        assertFalse(raw.contains(BEARER_SECRET), raw)
        assertFalse(raw.contains(REFRESH_SECRET), raw)
        assertFalse(raw.contains(PRIVATE_KEY_SECRET), raw)
    }

    private data class ToolEventFixture(
        val userId: String,
        val chat: Chat,
        val execution: AgentExecution,
        val eventRepository: MemoryAgentEventRepository,
        val toolCallRepository: MemoryToolCallRepository,
        val sink: BackendAgentRuntimeEventSink,
    )

    private companion object {
        const val SK_TEST_SECRET = "sk-test-secret-123"
        const val BEARER_SECRET = "Bearer very-secret-token"
        const val REFRESH_SECRET = "refresh-token-secret"
        const val PRIVATE_KEY_SECRET = "private-key-secret"
    }
}
