package ru.souz.backend

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse

class BackendChatServiceTest {
    @Test
    fun `sendMessage appends user and assistant messages and hides system prompt`() = runTest {
        val service = ChatService(
            chatApi = FakeChatApi { "reply to ${it.messages.last().content}" },
            settings = { BackendChatSettings(model = "test-model") },
            systemPrompt = "system",
        )

        val response = service.sendMessage("hello")

        assertEquals(
            listOf(
                ChatHistoryMessage("user", "hello"),
                ChatHistoryMessage("assistant", "reply to hello"),
            ),
            response.history,
        )
    }

    @Test
    fun `sendMessage keeps prior history`() = runTest {
        val api = FakeChatApi { "reply ${it.messages.count { msg -> msg.role == LLMMessageRole.user }}" }
        val service = ChatService(
            chatApi = api,
            settings = { BackendChatSettings(model = "test-model") },
            systemPrompt = "system",
        )

        service.sendMessage("one")
        val response = service.sendMessage("two")

        assertEquals(
            listOf(
                ChatHistoryMessage("user", "one"),
                ChatHistoryMessage("assistant", "reply 1"),
                ChatHistoryMessage("user", "two"),
                ChatHistoryMessage("assistant", "reply 2"),
            ),
            response.history,
        )
    }

    @Test
    fun `sendMessage rejects blank message`() = runTest {
        val service = ChatService(
            chatApi = FakeChatApi { "unused" },
            settings = { BackendChatSettings(model = "test-model") },
        )

        val error = assertFailsWith<BackendRequestException> {
            service.sendMessage("   ")
        }

        assertEquals(400, error.statusCode)
        assertEquals(emptyList(), service.history().history)
    }

    @Test
    fun `LLM error does not mutate history`() = runTest {
        val service = ChatService(
            chatApi = FakeChatApi { error("unused") }.apply {
                errorResponse = LLMResponse.Chat.Error(401, "bad key")
            },
            settings = { BackendChatSettings(model = "test-model") },
        )

        val error = assertFailsWith<BackendRequestException> {
            service.sendMessage("hello")
        }

        assertEquals(502, error.statusCode)
        assertEquals(emptyList(), service.history().history)
    }
}

private class FakeChatApi(
    private val reply: suspend (LLMRequest.Chat) -> String,
) : LLMChatAPI {
    var errorResponse: LLMResponse.Chat.Error? = null
    var usage: LLMResponse.Usage = LLMResponse.Usage(10, 5, 15, 1)

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
        errorResponse?.let { return it }
        return LLMResponse.Chat.Ok(
            choices = listOf(
                LLMResponse.Choice(
                    message = LLMResponse.Message(
                        content = reply(body),
                        role = LLMMessageRole.assistant,
                        functionCall = null,
                        functionsStateId = null,
                    ),
                    index = 0,
                    finishReason = LLMResponse.FinishReason.stop,
                )
            ),
            created = 0,
            model = body.model,
            usage = usage,
        )
    }

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> =
        error("Streaming is not used in backend tests.")

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings =
        error("Embeddings are not used in backend tests.")

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile =
        error("File upload is not used in backend tests.")

    override suspend fun downloadFile(fileId: String): String? =
        error("File download is not used in backend tests.")

    override suspend fun balance(): LLMResponse.Balance =
        error("Balance is not used in backend tests.")
}
