package ru.souz.backend.agent.runtime

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import ru.souz.backend.TestSkillRegistryRepository
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.backend.TestSettingsProvider
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.session.InMemoryAgentSessionRepository
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse

class BackendConversationRuntimeTurnRunnerTest {
    @Test
    fun `runner rethrows cancellation exception`() = runTest {
        val failure = CancellationException("runner cancelled")
        val runner = runtimeTurnRunner(failure)

        val thrown = assertFailsWith<CancellationException> {
            runner.run(
                conversationKey = conversationKey(),
                request = turnRequest(),
                eventSink = AgentRuntimeEventSink.NONE,
            )
        }

        assertEquals(failure.message, thrown.message)
    }

    @Test
    fun `runner wraps regular exception`() = runTest {
        val failure = IllegalStateException("runner failed")
        val runner = runtimeTurnRunner(failure)
        val initialUsage = LLMResponse.Usage(
            promptTokens = 11,
            completionTokens = 7,
            totalTokens = 18,
            precachedTokens = 3,
        )

        val thrown = assertFailsWith<BackendConversationTurnException> {
            runner.run(
                conversationKey = conversationKey(),
                request = turnRequest(),
                eventSink = AgentRuntimeEventSink.NONE,
                initialUsage = initialUsage,
            )
        }

        assertIs<IllegalStateException>(thrown.cause)
        assertEquals(failure.message, thrown.cause?.message)
        assertEquals(initialUsage, thrown.usage)
    }
}

private fun runtimeTurnRunner(failure: Throwable): BackendConversationRuntimeTurnRunner {
    val settingsProvider = TestSettingsProvider().apply {
        gigaChatKey = "giga-key"
        contextSize = 24_000
        temperature = 0.6f
        useStreaming = false
    }
    return BackendConversationRuntimeTurnRunner(
        runtimeFactory = BackendConversationRuntimeFactory(
            baseSettingsProvider = settingsProvider,
            llmApiFactory = { ThrowingChatApi(failure) },
            sessionRepository = InMemoryAgentSessionRepository(),
            logObjectMapper = jacksonObjectMapper(),
            systemPrompt = "backend test prompt",
            skillRegistryRepository = TestSkillRegistryRepository,
        )
    )
}

private fun conversationKey(): AgentConversationKey =
    AgentConversationKey(
        userId = "user-runner",
        conversationId = UUID.randomUUID().toString(),
    )

private fun turnRequest(): BackendConversationTurnRequest =
    BackendConversationTurnRequest(
        prompt = "test prompt",
        model = "GigaChat-Max",
        contextSize = 24_000,
        locale = "ru-RU",
        timeZone = "Europe/Moscow",
        executionId = UUID.randomUUID().toString(),
        temperature = 0.6f,
        systemPrompt = "backend test prompt",
        streamingMessages = false,
    )

private class ThrowingChatApi(
    private val failure: Throwable,
) : LLMChatAPI {
    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat = throw failure

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
