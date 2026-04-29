package ru.souz.backend

import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
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

    @Test
    fun `sendAgentRequest returns agent response contract`() = runTest {
        val api = FakeChatApi { "agent reply to ${it.messages.last().content}" }
        val request = agentRequest(contextSize = 16_000)
        val service = ChatService(
            chatApi = api,
            settings = { BackendChatSettings(model = "ignored") },
            systemPrompt = "system",
        )

        val response = service.sendAgentRequest(request)

        assertEquals(request.requestId, response.requestId)
        assertEquals(request.conversationId, response.conversationId)
        UUID.fromString(response.userMessageId)
        UUID.fromString(response.assistantMessageId)
        assertEquals("agent reply to Напиши короткое резюме проекта", response.content)
        assertEquals(LLMModel.Max.alias, response.model)
        assertEquals("GIGA", response.provider)
        assertEquals(16_000, response.contextSize)
        assertEquals(AgentUsage(10, 5, 15, 1), response.usage)

        val llmRequest = api.requests.single()
        assertEquals(LLMModel.Max.alias, llmRequest.model)
        assertEquals(16_000, llmRequest.maxTokens)
        assertEquals(
            listOf(LLMMessageRole.system, LLMMessageRole.user),
            llmRequest.messages.map { it.role },
        )
    }

    @Test
    fun `sendAgentRequest rejects duplicate requestId`() = runTest {
        val request = agentRequest()
        val service = ChatService(
            chatApi = FakeChatApi { "ok" },
            settings = { BackendChatSettings(model = "ignored") },
        )

        service.sendAgentRequest(request)
        val error = assertFailsWith<BackendRequestException> {
            service.sendAgentRequest(request)
        }

        assertEquals(409, error.statusCode)
    }

    @Test
    fun `sendAgentRequest rejects active conversation conflict`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val firstRequest = agentRequest()
        val secondRequest = agentRequest(
            userId = firstRequest.userId,
            conversationId = firstRequest.conversationId,
        )
        val service = ChatService(
            chatApi = FakeChatApi {
                started.complete(Unit)
                release.await()
                "ok"
            },
            settings = { BackendChatSettings(model = "ignored") },
        )

        val firstCall = async { service.sendAgentRequest(firstRequest) }
        started.await()
        val error = assertFailsWith<BackendRequestException> {
            service.sendAgentRequest(secondRequest)
        }
        release.complete(Unit)
        firstCall.await()

        assertEquals(409, error.statusCode)
    }

    @Test
    fun `sendAgentRequest rejects invalid payload`() = runTest {
        val service = ChatService(
            chatApi = FakeChatApi { "unused" },
            settings = { BackendChatSettings(model = "ignored") },
        )

        val error = assertFailsWith<BackendRequestException> {
            service.sendAgentRequest(agentRequest(prompt = " "))
        }

        assertEquals(400, error.statusCode)
    }

    @Test
    fun `sendAgentRequest returns 404 when user or conversation is missing`() = runTest {
        val service = ChatService(
            chatApi = FakeChatApi { "unused" },
            settings = { BackendChatSettings(model = "ignored") },
            agentConversationExists = { _, _ -> false },
        )

        val error = assertFailsWith<BackendRequestException> {
            service.sendAgentRequest(agentRequest())
        }

        assertEquals(404, error.statusCode)
    }
}

private class FakeChatApi(
    private val reply: suspend (LLMRequest.Chat) -> String,
) : LLMChatAPI {
    var errorResponse: LLMResponse.Chat.Error? = null
    val requests = ArrayList<LLMRequest.Chat>()
    var usage: LLMResponse.Usage = LLMResponse.Usage(10, 5, 15, 1)

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
        requests += body
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

private fun agentRequest(
    requestId: String = uuid(),
    userId: String = uuid(),
    conversationId: String = uuid(),
    prompt: String = "Напиши короткое резюме проекта",
    model: String = LLMModel.Max.alias,
    contextSize: Int = 16_000,
    source: String = "web",
    locale: String = "ru-RU",
    timeZone: String = "Europe/Moscow",
): AgentRequest =
    AgentRequest(
        requestId = requestId,
        userId = userId,
        conversationId = conversationId,
        prompt = prompt,
        model = model,
        contextSize = contextSize,
        source = source,
        locale = locale,
        timeZone = timeZone,
    )

private fun uuid(): String = UUID.randomUUID().toString()
