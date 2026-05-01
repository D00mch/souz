package ru.souz.backend.http

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import ru.souz.backend.TestSettingsProvider
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse

class BackendStage5EventRouteTest {
    private val json = jacksonObjectMapper()

    @Test
    fun `streaming path persists created delta completed events for correct execution`() = testApplication {
        val context = streamingRouteTestContext(
            llmApi = StreamingEventChatApi(
                chunksByPrompt = mapOf("stream me" to listOf("assistant ", "reply ", "to stream me")),
            ),
        )
        val chat = chat(
            userId = "user-a",
            title = "Streaming chat",
            createdAt = Instant.parse("2026-05-01T09:00:00Z"),
            updatedAt = Instant.parse("2026-05-01T09:00:00Z"),
        )
        runBlocking {
            context.chatRepository.create(chat)
        }
        application {
            backendApplication(
                agentService = context.agentService,
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                internalAgentToken = { "legacy-token" },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                chatService = context.chatService,
                messageService = context.messageService,
                executionService = context.executionService,
            )
        }

        val response = client.post("/v1/chats/${chat.id}/messages") {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"stream me"}""")
        }
        val payload = json.readTree(response.bodyAsText())
        val executionId = UUID.fromString(payload["execution"]["id"].asText())
        val assistantMessageId = payload["assistantMessage"]["id"].asText()
        val events = runBlocking { context.eventRepository.listByChat("user-a", chat.id) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            listOf(
                AgentEventType.EXECUTION_STARTED,
                AgentEventType.MESSAGE_CREATED,
                AgentEventType.MESSAGE_DELTA,
                AgentEventType.MESSAGE_DELTA,
                AgentEventType.MESSAGE_DELTA,
                AgentEventType.MESSAGE_COMPLETED,
                AgentEventType.EXECUTION_FINISHED,
            ),
            events.map { it.type },
        )
        assertTrue(events.all { it.executionId == executionId })
        assertEquals(assistantMessageId, events.first { it.type == AgentEventType.MESSAGE_CREATED }.payload["messageId"])
        assertEquals(
            listOf("assistant ", "reply ", "to stream me"),
            events.filter { it.type == AgentEventType.MESSAGE_DELTA }.map { it.payload.getValue("delta") },
        )
        assertEquals(
            "assistant reply to stream me",
            events.first { it.type == AgentEventType.MESSAGE_COMPLETED }.payload["content"],
        )
    }

    @Test
    fun `non streaming path keeps sync response contract and skips delta events`() = testApplication {
        val context = streamingRouteTestContext(
            llmApi = StreamingEventChatApi(
                chunksByPrompt = mapOf("plain reply" to listOf("assistant ", "reply ", "to plain reply")),
            ),
        )
        val chat = chat(userId = "user-a", title = "Non-streaming chat")
        runBlocking {
            context.chatRepository.create(chat)
        }
        application {
            backendApplication(
                agentService = context.agentService,
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                internalAgentToken = { "legacy-token" },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                chatService = context.chatService,
                messageService = context.messageService,
                executionService = context.executionService,
            )
        }

        val patchResponse = client.patch("/v1/me/settings") {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"streamingMessages":false}""")
        }
        val response = client.post("/v1/chats/${chat.id}/messages") {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"plain reply"}""")
        }
        val payload = json.readTree(response.bodyAsText())
        val events = runBlocking { context.eventRepository.listByChat("user-a", chat.id) }

        assertEquals(HttpStatusCode.OK, patchResponse.status)
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("plain reply", payload["message"]["content"].asText())
        assertEquals("assistant reply to plain reply", payload["assistantMessage"]["content"].asText())
        assertEquals(
            listOf(
                AgentEventType.EXECUTION_STARTED,
                AgentEventType.MESSAGE_CREATED,
                AgentEventType.MESSAGE_COMPLETED,
                AgentEventType.EXECUTION_FINISHED,
            ),
            events.map { it.type },
        )
    }

    @Test
    fun `parallel streaming executions in different chats and users do not mix event rows`() = testApplication {
        val api = GateControlledStreamingEventChatApi()
        val context = streamingRouteTestContext(
            llmApi = api,
        )
        val userAChat = chat(userId = "user-a", title = "Chat A")
        val userBChat = chat(userId = "user-b", title = "Chat B")
        runBlocking {
            context.chatRepository.create(userAChat)
            context.chatRepository.create(userBChat)
        }
        application {
            backendApplication(
                agentService = context.agentService,
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                internalAgentToken = { "legacy-token" },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                chatService = context.chatService,
                messageService = context.messageService,
                executionService = context.executionService,
            )
        }

        runBlocking {
            val firstResponse = async {
                client.post("/v1/chats/${userAChat.id}/messages") {
                    trustedHeaders("user-a")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"A1"}""")
                }
            }
            val secondResponse = async {
                client.post("/v1/chats/${userBChat.id}/messages") {
                    trustedHeaders("user-b")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"B1"}""")
                }
            }

            api.awaitStarted("A1")
            api.awaitStarted("B1")
            api.release()

            val firstPayload = json.readTree(firstResponse.await().bodyAsText())
            val secondPayload = json.readTree(secondResponse.await().bodyAsText())
            val firstExecutionId = UUID.fromString(firstPayload["execution"]["id"].asText())
            val secondExecutionId = UUID.fromString(secondPayload["execution"]["id"].asText())
            val firstEvents = context.eventRepository.listByChat("user-a", userAChat.id)
            val secondEvents = context.eventRepository.listByChat("user-b", userBChat.id)

            assertTrue(firstEvents.isNotEmpty())
            assertTrue(secondEvents.isNotEmpty())
            assertTrue(firstEvents.all { it.executionId == firstExecutionId })
            assertTrue(secondEvents.all { it.executionId == secondExecutionId })
            assertTrue(firstEvents.none { it.executionId == secondExecutionId })
            assertTrue(secondEvents.none { it.executionId == firstExecutionId })
        }
    }

    @Test
    fun `failed execution persists terminal event without breaking message contract`() = testApplication {
        val context = routeTestContext(llmApi = FailingChatApi())
        val chat = chat(userId = "user-a", title = "Failing")
        runBlocking {
            context.chatRepository.create(chat)
        }
        application {
            backendApplication(
                agentService = context.agentService,
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                internalAgentToken = { "legacy-token" },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                chatService = context.chatService,
                messageService = context.messageService,
                executionService = context.executionService,
            )
        }

        val response = client.post("/v1/chats/${chat.id}/messages") {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"trigger failure"}""")
        }
        val payload = json.readTree(response.bodyAsText())
        val events = runBlocking { context.eventRepository.listByChat("user-a", chat.id) }
        val storedMessages = runBlocking { context.messageRepository.list("user-a", chat.id) }
        val execution = runBlocking { context.executionRepository.listByChat("user-a", chat.id).single() }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("agent_execution_failed", payload["error"]["code"].asText())
        assertEquals(listOf(AgentEventType.EXECUTION_STARTED, AgentEventType.EXECUTION_FAILED), events.map { it.type })
        assertEquals(listOf("trigger failure"), storedMessages.map { it.content })
        assertEquals(AgentExecutionStatus.FAILED, execution.status)
        assertNull(execution.assistantMessageId)
    }

    @Test
    fun `cancelled execution persists terminal event without breaking sync cancel contract`() = testApplication {
        val api = CancellableChatApi()
        val context = routeTestContext(llmApi = api)
        val chat = chat(userId = "user-a", title = "Cancellable")
        runBlocking {
            context.chatRepository.create(chat)
        }
        application {
            backendApplication(
                agentService = context.agentService,
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                internalAgentToken = { "legacy-token" },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                chatService = context.chatService,
                messageService = context.messageService,
                executionService = context.executionService,
            )
        }

        runBlocking {
            val sendResponse = async {
                client.post("/v1/chats/${chat.id}/messages") {
                    trustedHeaders("user-a")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"cancel me"}""")
                }
            }
            api.awaitStarted("cancel me")
            val activeExecution = assertNotNull(context.executionRepository.findActive("user-a", chat.id))

            val cancelResponse = client.post("/v1/chats/${chat.id}/cancel-active") {
                trustedHeaders("user-a")
            }
            val cancelPayload = json.readTree(cancelResponse.bodyAsText())
            val cancelledResponse = sendResponse.await()
            val cancelledPayload = json.readTree(cancelledResponse.bodyAsText())
            val events = context.eventRepository.listByChat("user-a", chat.id)
            val storedMessages = context.messageRepository.list("user-a", chat.id)
            val storedExecution = assertNotNull(context.executionRepository.getByChat("user-a", chat.id, activeExecution.id))

            assertEquals(HttpStatusCode.OK, cancelResponse.status)
            assertEquals(activeExecution.id.toString(), cancelPayload["execution"]["id"].asText())
            assertEquals(HttpStatusCode.Conflict, cancelledResponse.status)
            assertEquals("agent_execution_cancelled", cancelledPayload["error"]["code"].asText())
            assertEquals(
                listOf(AgentEventType.EXECUTION_STARTED, AgentEventType.EXECUTION_CANCELLED),
                events.map { it.type },
            )
            assertEquals(listOf("cancel me"), storedMessages.map { it.content })
            assertEquals(AgentExecutionStatus.CANCELLED, storedExecution.status)
            assertNull(storedExecution.assistantMessageId)
        }
    }
}

private fun streamingRouteTestContext(
    llmApi: LLMChatAPI,
): RouteTestContext =
    routeTestContext(
        llmApi = llmApi,
        settingsProvider = TestSettingsProvider().apply {
            gigaChatKey = "giga-key"
            qwenChatKey = "qwen-key"
            contextSize = 24_000
            temperature = 0.6f
            useStreaming = true
        },
        featureFlags = BackendFeatureFlags(
            streamingMessages = true,
            toolEvents = true,
        ),
    )

private class StreamingEventChatApi(
    private val chunksByPrompt: Map<String, List<String>>,
) : LLMChatAPI {
    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
        if (body.isClassificationRequest()) {
            return reply(body, "HELP 90")
        }
        return reply(body, "assistant reply to ${body.conversationPrompt()}")
    }

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> = flow {
        val prompt = body.conversationPrompt()
        val chunks = chunksByPrompt.getValue(prompt)
        chunks.forEachIndexed { index, chunk ->
            emit(
                LLMResponse.Chat.Ok(
                    choices = listOf(
                        LLMResponse.Choice(
                            message = LLMResponse.Message(
                                content = chunk,
                                role = LLMMessageRole.assistant,
                                functionsStateId = null,
                            ),
                            index = 0,
                            finishReason = if (index == chunks.lastIndex) {
                                LLMResponse.FinishReason.stop
                            } else {
                                null
                            },
                        )
                    ),
                    created = index.toLong(),
                    model = body.model,
                    usage = LLMResponse.Usage(7, index + 1, 8 + index, 0),
                )
            )
        }
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

private class GateControlledStreamingEventChatApi : LLMChatAPI {
    private val startedByPrompt = LinkedHashMap<String, CompletableDeferred<Unit>>()
    private val release = CompletableDeferred<Unit>()

    suspend fun awaitStarted(prompt: String) {
        startedByPrompt.getOrPut(prompt) { CompletableDeferred() }.await()
    }

    fun release() {
        release.complete(Unit)
    }

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
        if (body.isClassificationRequest()) {
            return reply(body, "HELP 90")
        }
        return reply(body, "assistant reply to ${body.conversationPrompt()}")
    }

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> = flow {
        val prompt = body.conversationPrompt()
        startedByPrompt.getOrPut(prompt) { CompletableDeferred() }.complete(Unit)
        release.await()
        listOf("assistant ", "reply ", "to $prompt").forEachIndexed { index, chunk ->
            emit(
                LLMResponse.Chat.Ok(
                    choices = listOf(
                        LLMResponse.Choice(
                            message = LLMResponse.Message(
                                content = chunk,
                                role = LLMMessageRole.assistant,
                                functionsStateId = null,
                            ),
                            index = 0,
                            finishReason = if (index == 2) LLMResponse.FinishReason.stop else null,
                        )
                    ),
                    created = index.toLong(),
                    model = body.model,
                    usage = LLMResponse.Usage(7, index + 1, 8 + index, 0),
                )
            )
        }
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

private class CancellableStreamingEventChatApi : LLMChatAPI {
    private val startedByPrompt = LinkedHashMap<String, CompletableDeferred<Unit>>()

    suspend fun awaitStarted(prompt: String) {
        startedByPrompt.getOrPut(prompt) { CompletableDeferred() }.await()
    }

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
        if (body.isClassificationRequest()) {
            return reply(body, "HELP 90")
        }
        return reply(body, "assistant reply to ${body.conversationPrompt()}")
    }

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> = flow {
        startedByPrompt.getOrPut(body.conversationPrompt()) { CompletableDeferred() }.complete(Unit)
        awaitCancellation()
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
