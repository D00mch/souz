package ru.souz.backend.agent.runtime

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse

class CumulativeUsageTrackingChatApiTest {
    @Test
    fun `usage collector sums multiple non streaming llm calls`() = runTest {
        val api = CumulativeUsageTrackingChatApi(
            delegate = ScriptedUsageChatApi(
                responses = listOf(
                    okResponse(content = "first", usage = LLMResponse.Usage(3, 2, 5, 0)),
                    okResponse(content = "second", usage = LLMResponse.Usage(4, 3, 7, 0)),
                )
            ),
            initialUsage = LLMResponse.Usage(0, 0, 0, 0),
        )

        api.message(sampleRequest())
        api.message(sampleRequest())

        assertEquals(7, api.cumulativeUsage().promptTokens)
        assertEquals(5, api.cumulativeUsage().completionTokens)
        assertEquals(12, api.cumulativeUsage().totalTokens)
    }

    @Test
    fun `usage collector adds only incremental streaming deltas on top of previous usage`() = runTest {
        val api = CumulativeUsageTrackingChatApi(
            delegate = ScriptedUsageChatApi(
                streamResponses = listOf(
                    okResponse(content = "a", usage = LLMResponse.Usage(7, 1, 8, 0)),
                    okResponse(content = "b", usage = LLMResponse.Usage(7, 2, 9, 0)),
                    okResponse(content = "c", usage = LLMResponse.Usage(7, 3, 10, 0)),
                )
            ),
            initialUsage = LLMResponse.Usage(4, 2, 6, 0),
        )

        api.messageStream(sampleRequest()).toList()

        assertEquals(11, api.cumulativeUsage().promptTokens)
        assertEquals(5, api.cumulativeUsage().completionTokens)
        assertEquals(16, api.cumulativeUsage().totalTokens)
    }

    private fun sampleRequest(): LLMRequest.Chat =
        LLMRequest.Chat(
            model = "test-model",
            messages = listOf(LLMRequest.Message(role = LLMMessageRole.user, content = "hello")),
        )
}

private class ScriptedUsageChatApi(
    private val responses: List<LLMResponse.Chat.Ok> = emptyList(),
    private val streamResponses: List<LLMResponse.Chat.Ok> = emptyList(),
) : LLMChatAPI {
    private var messageIndex: Int = 0

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat =
        responses[messageIndex++]

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> = flow {
        streamResponses.forEach { emit(it) }
    }

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings =
        error("Embeddings are not used in this test.")

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile =
        error("File upload is not used in this test.")

    override suspend fun downloadFile(fileId: String): String? =
        error("File download is not used in this test.")

    override suspend fun balance(): LLMResponse.Balance =
        error("Balance is not used in this test.")
}

private fun okResponse(
    content: String,
    usage: LLMResponse.Usage,
): LLMResponse.Chat.Ok =
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
        usage = usage,
    )
