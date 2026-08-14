package ru.souz.llms

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class TokenLoggingChatApiTest {
    @Test
    fun `logs successful non streaming responses`() = runTest {
        val request = request()
        val response = okResponse()
        val logged = mutableListOf<Pair<LLMResponse.Chat.Ok, LLMRequest.Chat>>()
        val api = TokenLoggingChatApi(
            delegate = StubChatApi(messageResponse = response),
            tokenLogging = tokenLogging { result, body -> logged += result to body },
        )

        assertSame(response, api.message(request))
        assertEquals(listOf(response to request), logged)
    }

    @Test
    fun `does not log errors or streaming responses`() = runTest {
        var logCount = 0
        val streamResponse = okResponse()
        val api = TokenLoggingChatApi(
            delegate = StubChatApi(
                messageResponse = LLMResponse.Chat.Error(500, "failed"),
                streamResponses = listOf(streamResponse),
            ),
            tokenLogging = tokenLogging { _, _ -> logCount += 1 },
        )

        api.message(request())
        assertEquals(listOf(streamResponse), api.messageStream(request()).toList())
        assertEquals(0, logCount)
    }

    @Test
    fun `logging failure cannot replace a successful response`() = runTest {
        val response = okResponse()
        val failures = mutableListOf<Throwable>()
        val loggingFailure = IllegalStateException("log failed")
        val api = TokenLoggingChatApi(
            delegate = StubChatApi(messageResponse = response),
            tokenLogging = tokenLogging { _, _ -> throw loggingFailure },
            onLoggingFailure = {
                failures += it
                error("observer failed")
            },
        )

        assertSame(response, api.message(request()))
        assertSame(loggingFailure, failures.single())
    }

    @Test
    fun `logging cancellation is rethrown`() = runTest {
        val cancellation = CancellationException("cancelled")
        val api = TokenLoggingChatApi(
            delegate = StubChatApi(messageResponse = okResponse()),
            tokenLogging = tokenLogging { _, _ -> throw cancellation },
        )

        assertSame(cancellation, assertFailsWith<CancellationException> { api.message(request()) })
    }

    private fun tokenLogging(
        log: suspend (LLMResponse.Chat.Ok, LLMRequest.Chat) -> Unit,
    ): TokenLogging = object : TokenLogging {
        override suspend fun logTokenUsage(result: LLMResponse.Chat.Ok, body: LLMRequest.Chat) = log(result, body)
    }

    private fun request() = LLMRequest.Chat(
        model = "test-model",
        messages = listOf(LLMRequest.Message(role = LLMMessageRole.user, content = "hello")),
    )

    private fun okResponse() = LLMResponse.Chat.Ok(
        choices = emptyList(),
        created = 1,
        model = "test-model",
        usage = LLMResponse.Usage(1, 2, 3, 0),
    )
}

private class StubChatApi(
    private val messageResponse: LLMResponse.Chat,
    private val streamResponses: List<LLMResponse.Chat> = emptyList(),
) : LLMChatAPI {
    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat = messageResponse
    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> = flowOf(*streamResponses.toTypedArray())
    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings = error("unused")
    override suspend fun uploadFile(file: File): LLMResponse.UploadFile = error("unused")
    override suspend fun downloadFile(fileId: String): String? = error("unused")
    override suspend fun balance(): LLMResponse.Balance = error("unused")
}
