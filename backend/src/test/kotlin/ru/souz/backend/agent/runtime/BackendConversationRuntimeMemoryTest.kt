package ru.souz.backend.agent.runtime

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.backend.TestSettingsProvider
import ru.souz.backend.TestSkillRegistryRepository
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.session.InMemoryAgentSessionRepository
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.memory.CompletedTurnMemoryInput
import ru.souz.memory.ConversationMemoryRuntime

class BackendConversationRuntimeMemoryTest {
    @Test
    fun `backend runtime can provide memory runtime to shared executor`() = runTest {
        val chatApi = RecordingChatApi()
        val memoryRuntime = RecordingMemoryRuntime()
        val runtimeFactory = BackendConversationRuntimeFactory(
            baseSettingsProvider = TestSettingsProvider().apply {
                gigaChatKey = "giga-key"
                contextSize = 24_000
                temperature = 0.6f
                useStreaming = false
            },
            llmApiFactory = { chatApi },
            sessionRepository = InMemoryAgentSessionRepository(),
            logObjectMapper = jacksonObjectMapper(),
            systemPrompt = "backend base prompt",
            skillRegistryRepository = TestSkillRegistryRepository,
            memoryRuntime = memoryRuntime,
        )
        val conversationKey = AgentConversationKey(
            userId = "backend-user",
            conversationId = UUID.randomUUID().toString(),
        )
        val request = BackendConversationTurnRequest(
            prompt = "hello",
            model = "GigaChat-Max",
            contextSize = 24_000,
            locale = "ru-RU",
            timeZone = "Europe/Moscow",
            executionId = "execution-1",
            temperature = 0.6f,
            systemPrompt = "backend base prompt",
            streamingMessages = false,
        )

        val runtime = runtimeFactory.create(conversationKey, request)
        val execution = runtime.execute(
            request = request,
            eventSink = AgentRuntimeEventSink.NONE,
        )

        assertEquals("backend assistant", execution.output)
        val agentRequest = chatApi.requests.last { requestBody ->
            requestBody.messages.any { it.role == LLMMessageRole.system && it.content.startsWith("backend base prompt") }
        }
        assertEquals(
            "backend base prompt\n\nRelevant memory:\n- Backend memory.",
            agentRequest.messages.first().content,
        )
        assertEquals(
            CompletedTurnMemoryInput(
                conversationId = conversationKey.conversationId,
                userMessageId = "execution-1",
                assistantMessageId = null,
                userMessage = "hello",
                assistantMessage = "backend assistant",
            ),
            memoryRuntime.capturedTurns.single(),
        )
        assertTrue(memoryRuntime.buildCalls.single().contains("hello"))
    }

    private class RecordingMemoryRuntime : ConversationMemoryRuntime {
        val buildCalls = mutableListOf<String>()
        val capturedTurns = mutableListOf<CompletedTurnMemoryInput>()

        override suspend fun buildSystemPrompt(
            baseSystemPrompt: String,
            userMessage: String,
            conversationId: String?,
        ): String {
            buildCalls += "$conversationId:$userMessage"
            return "$baseSystemPrompt\n\nRelevant memory:\n- Backend memory."
        }

        override suspend fun captureCompletedTurn(input: CompletedTurnMemoryInput) {
            capturedTurns += input
        }
    }

    private class RecordingChatApi : LLMChatAPI {
        val requests = mutableListOf<LLMRequest.Chat>()

        override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
            requests += body
            val content = if (body.messages.firstOrNull()?.content?.contains("выбрать минимальный") == true) {
                "CHAT 99"
            } else {
                "backend assistant"
            }
            return LLMResponse.Chat.Ok(
                choices = listOf(
                    LLMResponse.Choice(
                        message = LLMResponse.Message(
                            content = content,
                            role = LLMMessageRole.assistant,
                            functionsStateId = null,
                        ),
                        index = 0,
                        finishReason = LLMResponse.FinishReason.stop,
                    )
                ),
                created = 1L,
                model = body.model,
                usage = LLMResponse.Usage(1, 1, 2, 0),
            )
        }

        override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> =
            error("Streaming is not used in this test.")

        override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings =
            error("Embeddings are not used in this test.")

        override suspend fun uploadFile(file: File): LLMResponse.UploadFile =
            error("File upload is not used in this test.")

        override suspend fun downloadFile(fileId: String): String? =
            error("File download is not used in this test.")

        override suspend fun balance(): LLMResponse.Balance =
            error("Balance is not used in this test.")
    }
}
