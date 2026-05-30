package ru.souz.backend.llm

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import ru.souz.backend.app.BackendProviderRetryPolicy
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider

class RetryingLlmChatApiTest {
    @Test
    fun `429 response retries with retry after delay before succeeding`() = runTest {
        val recordedDelays = mutableListOf<Long>()
        val api = RetryingLlmChatApi(
            delegate = ScriptedRetryChatApi(
                responses = listOf(
                    LLMResponse.Chat.Error(429, "rate limited; retry-after=1200"),
                    okResponse("done"),
                )
            ),
            provider = LlmProvider.OPENAI,
            retryPolicy = BackendProviderRetryPolicy(
                max429Retries = 2,
                backoffBaseMs = 100L,
                backoffMaxMs = 5_000L,
            ),
            delayMillis = { value -> recordedDelays.add(value) },
        )

        val response = api.message(
            LLMRequest.Chat(
                model = "gpt-5.2",
                messages = listOf(LLMRequest.Message(role = LLMMessageRole.user, content = "hello")),
            )
        )

        assertIs<LLMResponse.Chat.Ok>(response)
        assertEquals(listOf(1_200L), recordedDelays)
    }

    @Test
    fun `429 response stops after bounded retries`() = runTest {
        val api = RetryingLlmChatApi(
            delegate = ScriptedRetryChatApi(
                responses = listOf(
                    LLMResponse.Chat.Error(429, "rate limited"),
                    LLMResponse.Chat.Error(429, "rate limited again"),
                    LLMResponse.Chat.Error(429, "final"),
                )
            ),
            provider = LlmProvider.QWEN,
            retryPolicy = BackendProviderRetryPolicy(
                max429Retries = 2,
                backoffBaseMs = 50L,
                backoffMaxMs = 100L,
            ),
            delayMillis = {},
        )

        val response = api.message(
            LLMRequest.Chat(
                model = "qwen-max",
                messages = listOf(LLMRequest.Message(role = LLMMessageRole.user, content = "hello")),
            )
        )

        assertIs<LLMResponse.Chat.Error>(response)
        assertEquals(429, response.status)
        assertEquals("final", response.message)
    }

    private fun okResponse(content: String): LLMResponse.Chat.Ok =
        LLMResponse.Chat.Ok(
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
            model = "test-model",
            usage = LLMResponse.Usage(4, 2, 6, 0),
        )
}

private class ScriptedRetryChatApi(
    private val responses: List<LLMResponse.Chat>,
) : LLMChatAPI {
    private var index: Int = 0

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat =
        responses.getOrElse(index++) { responses.last() }

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> =
        flowOf(message(body))

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings =
        error("Embeddings are not used in this test.")

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile =
        error("File upload is not used in this test.")

    override suspend fun downloadFile(fileId: String): String? =
        error("File download is not used in this test.")

    override suspend fun balance(): LLMResponse.Balance =
        error("Balance is not used in this test.")
}
