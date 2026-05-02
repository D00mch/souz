package ru.souz.backend.http

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ru.souz.backend.TestSettingsProvider
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.events.model.AgentEventType
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText

private val stage6Json = jacksonObjectMapper()

class BackendStage6EventRouteTest {
    @Test
    fun `websocket returns replay first and then live events`() = testApplication {
        val api = GateControlledStage6StreamingChatApi()
        val context = stage6RouteTestContext(api)
        val chat = chat(userId = "user-a", title = "WS replay and live")
        runBlocking {
            context.chatRepository.create(chat)
            context.eventRepository.append(
                userId = "user-a",
                chatId = chat.id,
                executionId = null,
                type = ru.souz.backend.events.model.AgentEventType.EXECUTION_STARTED,
                payload = mapOf("executionId" to "replay-execution"),
                createdAt = Instant.parse("2026-05-01T10:00:00Z"),
            )
        }
        installStage6Application(context)
        val wsClient = createClient {
            install(WebSockets)
        }

        runBlocking {
            val session = wsClient.webSocketSession("/v1/chats/${chat.id}/ws?afterSeq=0") {
                trustedHeaders("user-a")
            }
            val replayEvent = session.receiveEvent()
            assertEquals(1L, replayEvent["seq"].asLong())
            assertTrue(replayEvent["durable"].asBoolean())
            assertEquals("execution.started", replayEvent["type"].asText())

            val sendResponse = async {
                client.post("/v1/chats/${chat.id}/messages") {
                    trustedHeaders("user-a")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"hello ws"}""")
                }
            }
            api.awaitStarted("hello ws")

            val acceptedPayload = stage6Json.readTree(sendResponse.await().bodyAsText())
            assertEquals("running", acceptedPayload["execution"]["status"].asText())
            assertTrue(acceptedPayload["assistantMessage"] == null || acceptedPayload["assistantMessage"].isNull)

            api.release("hello ws")

            val liveEvents = buildList {
                repeat(7) {
                    add(session.receiveEvent())
                }
            }
            val liveTypes = liveEvents.map { it["type"].asText() }
            val deltaEvents = liveEvents.filter { it["type"].asText() == "message.delta" }

            assertEquals(
                listOf(
                    "execution.started",
                    "message.delta",
                    "message.delta",
                    "message.delta",
                    "message.created",
                    "message.completed",
                    "execution.finished",
                ),
                liveTypes,
            )
            assertEquals(3, deltaEvents.size)
            assertTrue(deltaEvents.all { it["durable"].asBoolean().not() })
            assertTrue(deltaEvents.all { it["seq"].isNull })
            assertTrue(liveEvents.filterNot { it["type"].asText() == "message.delta" }.all { it["durable"].asBoolean() })
            assertTrue(liveEvents.filterNot { it["type"].asText() == "message.delta" }.all { it["seq"].isNumber })
            session.close()
        }
    }

    @Test
    fun `websocket reconnect with afterSeq replays only missed events`() = testApplication {
        val api = GateControlledStage6StreamingChatApi(chunksByPrompt = mapOf("reconnect me" to listOf("part-1 ", "part-2")))
        val context = stage6RouteTestContext(api)
        val chat = chat(userId = "user-a", title = "Reconnect")
        runBlocking {
            context.chatRepository.create(chat)
        }
        installStage6Application(context)
        val wsClient = createClient {
            install(WebSockets)
        }

        runBlocking {
            val firstSession = wsClient.webSocketSession("/v1/chats/${chat.id}/ws?afterSeq=0") {
                trustedHeaders("user-a")
            }
            val sendResponse = async {
                client.post("/v1/chats/${chat.id}/messages") {
                    trustedHeaders("user-a")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"reconnect me"}""")
                }
            }
            api.awaitStarted("reconnect me")

            val acceptedPayload = stage6Json.readTree(sendResponse.await().bodyAsText())
            assertEquals("running", acceptedPayload["execution"]["status"].asText())

            api.release("reconnect me")

            val firstFour = buildList {
                repeat(4) {
                    add(firstSession.receiveEvent())
                }
            }
            val lastSeenSeq = firstFour.last { it["durable"].asBoolean() }["seq"].asLong()
            firstSession.close()

            val secondSession = wsClient.webSocketSession("/v1/chats/${chat.id}/ws?afterSeq=$lastSeenSeq") {
                trustedHeaders("user-a")
            }
            val replayedEvents = buildList {
                repeat(2) {
                    add(secondSession.receiveEvent())
                }
            }
            val replayedTypes = replayedEvents.map { it["type"].asText() }
            assertEquals(
                listOf("message.completed", "execution.finished"),
                replayedTypes,
            )
            assertTrue(replayedEvents.all { it["durable"].asBoolean() })
            assertTrue(replayedEvents.all { it["seq"].isNumber })
            assertTrue(replayedEvents.none { it["type"].asText() == "message.delta" })
            secondSession.close()
        }
    }

    @Test
    fun `streaming failure after live delta does not persist partial assistant message`() = testApplication {
        val context = stage6RouteTestContext(FailingAfterDeltaStage6StreamingChatApi())
        val chat = chat(userId = "user-a", title = "Fail after delta")
        runBlocking {
            context.chatRepository.create(chat)
        }
        installStage6Application(context)
        val wsClient = createClient {
            install(WebSockets)
        }

        runBlocking {
            val session = wsClient.webSocketSession("/v1/chats/${chat.id}/ws?afterSeq=0") {
                trustedHeaders("user-a")
            }

            val response = client.post("/v1/chats/${chat.id}/messages") {
                trustedHeaders("user-a")
                contentType(ContentType.Application.Json)
                setBody("""{"content":"fail after delta"}""")
            }
            val payload = stage6Json.readTree(response.bodyAsText())
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("running", payload["execution"]["status"].asText())

            val liveEvents = buildList {
                repeat(3) {
                    add(session.receiveEvent())
                }
            }
            val durableEvents = context.eventRepository.listByChat("user-a", chat.id)
            val storedMessages = context.messageRepository.list("user-a", chat.id)
            val storedExecution = context.executionRepository.listByChat("user-a", chat.id).single()

            assertEquals(
                listOf("execution.started", "message.delta", "execution.failed"),
                liveEvents.map { it["type"].asText() },
            )
            assertEquals(
                listOf(AgentEventType.EXECUTION_STARTED, AgentEventType.EXECUTION_FAILED),
                durableEvents.map { it.type },
            )
            assertEquals(listOf("fail after delta"), storedMessages.map { it.content })
            assertEquals("failed", storedExecution.status.value)
            assertNull(storedExecution.assistantMessageId)
            session.close()
        }
    }

    @Test
    fun `streaming cancel after live delta does not persist partial assistant message`() = testApplication {
        val api = CancellableAfterDeltaStage6StreamingChatApi()
        val context = stage6RouteTestContext(api)
        val chat = chat(userId = "user-a", title = "Cancel after delta")
        runBlocking {
            context.chatRepository.create(chat)
        }
        installStage6Application(context)
        val wsClient = createClient {
            install(WebSockets)
        }

        runBlocking {
            val session = wsClient.webSocketSession("/v1/chats/${chat.id}/ws?afterSeq=0") {
                trustedHeaders("user-a")
            }

            val response = client.post("/v1/chats/${chat.id}/messages") {
                trustedHeaders("user-a")
                contentType(ContentType.Application.Json)
                setBody("""{"content":"cancel after delta"}""")
            }
            val payload = stage6Json.readTree(response.bodyAsText())
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("running", payload["execution"]["status"].asText())

            api.awaitDeltaSent("cancel after delta")
            val cancelResponse = client.post("/v1/chats/${chat.id}/cancel-active") {
                trustedHeaders("user-a")
            }
            val cancelPayload = stage6Json.readTree(cancelResponse.bodyAsText())

            val liveEvents = buildList {
                repeat(3) {
                    add(session.receiveEvent())
                }
            }
            val durableEvents = context.eventRepository.listByChat("user-a", chat.id)
            val storedMessages = context.messageRepository.list("user-a", chat.id)
            val storedExecution = context.executionRepository.listByChat("user-a", chat.id).single()

            assertEquals(HttpStatusCode.OK, cancelResponse.status)
            assertEquals("cancelling", cancelPayload["execution"]["status"].asText())
            assertEquals(
                listOf("execution.started", "message.delta", "execution.cancelled"),
                liveEvents.map { it["type"].asText() },
            )
            assertEquals(
                listOf(AgentEventType.EXECUTION_STARTED, AgentEventType.EXECUTION_CANCELLED),
                durableEvents.map { it.type },
            )
            assertEquals(listOf("cancel after delta"), storedMessages.map { it.content })
            assertEquals("cancelled", storedExecution.status.value)
            assertNull(storedExecution.assistantMessageId)
            session.close()
        }
    }

    @Test
    fun `http replay returns only owned chat events after requested seq`() = testApplication {
        val context = stage6RouteTestContext(ImmediateStage6StreamingChatApi())
        val ownedChat = chat(userId = "user-a", title = "Owned")
        val foreignChat = chat(userId = "user-b", title = "Foreign")
        runBlocking {
            context.chatRepository.create(ownedChat)
            context.chatRepository.create(foreignChat)
            context.eventRepository.append(
                userId = "user-a",
                chatId = ownedChat.id,
                executionId = null,
                type = ru.souz.backend.events.model.AgentEventType.EXECUTION_STARTED,
                payload = mapOf("executionId" to "a-1"),
                createdAt = Instant.parse("2026-05-01T10:00:00Z"),
            )
            context.eventRepository.append(
                userId = "user-a",
                chatId = ownedChat.id,
                executionId = null,
                type = ru.souz.backend.events.model.AgentEventType.MESSAGE_CREATED,
                payload = mapOf("messageId" to "m-2"),
                createdAt = Instant.parse("2026-05-01T10:00:01Z"),
            )
            context.eventRepository.append(
                userId = "user-b",
                chatId = foreignChat.id,
                executionId = null,
                type = ru.souz.backend.events.model.AgentEventType.EXECUTION_STARTED,
                payload = mapOf("executionId" to "b-1"),
                createdAt = Instant.parse("2026-05-01T10:00:02Z"),
            )
        }
        installStage6Application(context)

        val response = client.get("/v1/chats/${ownedChat.id}/events?afterSeq=1") {
            trustedHeaders("user-a")
        }
        val payload = stage6Json.readTree(response.bodyAsText())

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, payload["items"].size())
        assertEquals(2L, payload["items"][0]["seq"].asLong())
        assertEquals(ownedChat.id.toString(), payload["items"][0]["chatId"].asText())
        assertEquals("message.created", payload["items"][0]["type"].asText())
    }

    @Test
    fun `http replay route defaults clamps and validates limit`() = testApplication {
        val context = stage6RouteTestContext(ImmediateStage6StreamingChatApi())
        val chat = chat(userId = "user-a", title = "Many events")
        runBlocking {
            context.chatRepository.create(chat)
            repeat(1_005) { index ->
                context.eventRepository.append(
                    userId = "user-a",
                    chatId = chat.id,
                    executionId = null,
                    type = ru.souz.backend.events.model.AgentEventType.MESSAGE_CREATED,
                    payload = mapOf("index" to index.toString()),
                    createdAt = Instant.parse("2026-05-01T10:00:00Z").plusSeconds(index.toLong()),
                )
            }
        }
        installStage6Application(context)

        val defaultResponse = client.get("/v1/chats/${chat.id}/events") {
            trustedHeaders("user-a")
        }
        val clampedResponse = client.get("/v1/chats/${chat.id}/events?limit=9999") {
            trustedHeaders("user-a")
        }
        val pagedResponse = client.get("/v1/chats/${chat.id}/events?afterSeq=1000&limit=9999") {
            trustedHeaders("user-a")
        }
        val zeroResponse = client.get("/v1/chats/${chat.id}/events?limit=0") {
            trustedHeaders("user-a")
        }
        val negativeResponse = client.get("/v1/chats/${chat.id}/events?limit=-1") {
            trustedHeaders("user-a")
        }

        val defaultItems = stage6Json.readTree(defaultResponse.bodyAsText())["items"]
        val clampedItems = stage6Json.readTree(clampedResponse.bodyAsText())["items"]
        val pagedItems = stage6Json.readTree(pagedResponse.bodyAsText())["items"]

        assertEquals(HttpStatusCode.OK, defaultResponse.status)
        assertEquals(100, defaultItems.size())
        assertEquals(100L, defaultItems.last()["seq"].asLong())

        assertEquals(HttpStatusCode.OK, clampedResponse.status)
        assertEquals(1_000, clampedItems.size())
        assertEquals(1_000L, clampedItems.last()["seq"].asLong())

        assertEquals(HttpStatusCode.OK, pagedResponse.status)
        assertEquals(5, pagedItems.size())
        assertEquals(1_001L, pagedItems.first()["seq"].asLong())
        assertEquals(1_005L, pagedItems.last()["seq"].asLong())

        assertEquals(HttpStatusCode.BadRequest, zeroResponse.status)
        assertEquals("invalid_request", stage6Json.readTree(zeroResponse.bodyAsText())["error"]["code"].asText())

        assertEquals(HttpStatusCode.BadRequest, negativeResponse.status)
        assertEquals("invalid_request", stage6Json.readTree(negativeResponse.bodyAsText())["error"]["code"].asText())
    }

    @Test
    fun `websocket and http replay routes are controlled errors when ws events are disabled`() = testApplication {
        val context = routeTestContext(
            llmApi = ImmediateStage6StreamingChatApi(),
            settingsProvider = TestSettingsProvider().apply {
                gigaChatKey = "giga-key"
                qwenChatKey = "qwen-key"
                contextSize = 24_000
                temperature = 0.6f
                useStreaming = true
            },
            featureFlags = BackendFeatureFlags(
                wsEvents = false,
                streamingMessages = true,
                toolEvents = true,
            ),
        )
        val chat = chat(userId = "user-a", title = "Flags")
        runBlocking {
            context.chatRepository.create(chat)
        }
        installStage6Application(context)

        val replayResponse = client.get("/v1/chats/${chat.id}/events?afterSeq=0") {
            trustedHeaders("user-a")
        }
        val replayPayload = stage6Json.readTree(replayResponse.bodyAsText())
        val wsRouteResponse = client.get("/v1/chats/${chat.id}/ws?afterSeq=0") {
            trustedHeaders("user-a")
        }
        val wsRoutePayload = stage6Json.readTree(wsRouteResponse.bodyAsText())

        assertEquals(HttpStatusCode.NotFound, replayResponse.status)
        assertEquals("feature_disabled", replayPayload["error"]["code"].asText())
        assertEquals(HttpStatusCode.NotFound, wsRouteResponse.status)
        assertEquals("feature_disabled", wsRoutePayload["error"]["code"].asText())
    }

    @Test
    fun `streaming plus ws events returns running execution quickly and final output arrives via event stream`() = testApplication {
        val api = GateControlledStage6StreamingChatApi()
        val context = stage6RouteTestContext(api)
        val chat = chat(userId = "user-a", title = "Async messages")
        runBlocking {
            context.chatRepository.create(chat)
        }
        installStage6Application(context)
        val wsClient = createClient {
            install(WebSockets)
        }

        runBlocking {
            val session = wsClient.webSocketSession("/v1/chats/${chat.id}/ws?afterSeq=0") {
                trustedHeaders("user-a")
            }

            val response = async {
                client.post("/v1/chats/${chat.id}/messages") {
                    trustedHeaders("user-a")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"async please"}""")
                }
            }
            api.awaitStarted("async please")

            val responseValue = response.await()
            val payload = stage6Json.readTree(responseValue.bodyAsText())
            assertEquals(HttpStatusCode.OK, responseValue.status)
            assertEquals("running", payload["execution"]["status"].asText())
            assertTrue(payload["assistantMessage"] == null || payload["assistantMessage"].isNull)

            val storedExecution = context.executionRepository.listByChat("user-a", chat.id).single()
            assertEquals("running", storedExecution.status.value)
            assertNull(storedExecution.assistantMessageId)

            api.release("async please")

            val terminalEvents = ArrayList<String>()
            repeat(7) {
                terminalEvents += session.receiveEvent()["type"].asText()
            }
            assertTrue("message.completed" in terminalEvents)
            assertTrue("execution.finished" in terminalEvents)

            val storedMessages = context.messageRepository.list("user-a", chat.id)
            assertEquals(listOf("async please", "assistant reply to async please"), storedMessages.map { it.content })
            session.close()
        }
    }

    @Test
    fun `sync contract stays unchanged when ws events are off even if streaming is enabled`() = testApplication {
        val context = routeTestContext(
            llmApi = ImmediateStage6StreamingChatApi(),
            settingsProvider = TestSettingsProvider().apply {
                gigaChatKey = "giga-key"
                qwenChatKey = "qwen-key"
                contextSize = 24_000
                temperature = 0.6f
                useStreaming = true
            },
            featureFlags = BackendFeatureFlags(
                wsEvents = false,
                streamingMessages = true,
                toolEvents = true,
            ),
        )
        val chat = chat(userId = "user-a", title = "Sync fallback")
        runBlocking {
            context.chatRepository.create(chat)
        }
        installStage6Application(context)

        val response = client.post("/v1/chats/${chat.id}/messages") {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"keep sync"}""")
        }
        val payload = stage6Json.readTree(response.bodyAsText())

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("keep sync", payload["message"]["content"].asText())
        assertEquals("assistant reply to keep sync", payload["assistantMessage"]["content"].asText())
        assertEquals("completed", payload["execution"]["status"].asText())
    }

    @Test
    fun `websocket stream stays isolated by user and chat`() = testApplication {
        val api = GateControlledStage6StreamingChatApi()
        val context = stage6RouteTestContext(api)
        val userAChat = chat(userId = "user-a", title = "A")
        val userBChat = chat(userId = "user-b", title = "B")
        runBlocking {
            context.chatRepository.create(userAChat)
            context.chatRepository.create(userBChat)
        }
        installStage6Application(context)
        val wsClient = createClient {
            install(WebSockets)
        }

        runBlocking {
            val userASession = wsClient.webSocketSession("/v1/chats/${userAChat.id}/ws?afterSeq=0") {
                trustedHeaders("user-a")
            }

            val sendA = async {
                client.post("/v1/chats/${userAChat.id}/messages") {
                    trustedHeaders("user-a")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"A-stream"}""")
                }
            }
            val sendB = async {
                client.post("/v1/chats/${userBChat.id}/messages") {
                    trustedHeaders("user-b")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"B-stream"}""")
                }
            }
            api.awaitStarted("A-stream")
            api.awaitStarted("B-stream")
            sendA.await()
            sendB.await()
            api.release("A-stream")
            api.release("B-stream")

            val ownEvents = ArrayList<JsonNode>()
            repeat(7) {
                ownEvents.add(userASession.receiveEvent())
            }
            assertTrue(ownEvents.all { it["chatId"].asText() == userAChat.id.toString() })
            assertTrue(ownEvents.all { it["payload"].toString().contains("B-stream").not() })

            assertNoFrame(userASession)
            userASession.close()
        }
    }
}

private fun ApplicationTestBuilder.installStage6Application(context: RouteTestContext) {
    this.application {
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
            eventService = context.eventService,
            featureFlags = context.featureFlags,
        )
    }
}

private fun stage6RouteTestContext(llmApi: LLMChatAPI): RouteTestContext =
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
            wsEvents = true,
            streamingMessages = true,
            toolEvents = true,
        ),
    )

private suspend fun DefaultClientWebSocketSession.receiveEvent(): JsonNode =
    stage6Json.readTree((incoming.receive() as Frame.Text).readText())

private suspend fun assertNoFrame(session: DefaultClientWebSocketSession) {
    try {
        withTimeout(250) {
            session.incoming.receive()
        }
        assertFalse(true, "Expected no extra websocket frame.")
    } catch (_: TimeoutCancellationException) {
    }
}

private class ImmediateStage6StreamingChatApi : LLMChatAPI {
    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
        if (body.isClassificationRequest()) {
            return reply(body, "HELP 90")
        }
        return reply(body, "assistant reply to ${body.conversationPrompt()}")
    }

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> = flow {
        listOf("assistant ", "reply ", "to ${body.conversationPrompt()}").forEachIndexed { index, chunk ->
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
                    usage = LLMResponse.Usage(5, index + 1, 6 + index, 0),
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

private class GateControlledStage6StreamingChatApi(
    private val chunksByPrompt: Map<String, List<String>> = emptyMap(),
) : LLMChatAPI {
    private val startedByPrompt = LinkedHashMap<String, CompletableDeferred<Unit>>()
    private val releaseByPrompt = LinkedHashMap<String, CompletableDeferred<Unit>>()

    suspend fun awaitStarted(prompt: String) {
        startedByPrompt.getOrPut(prompt) { CompletableDeferred() }.await()
    }

    fun release(prompt: String) {
        releaseByPrompt.getOrPut(prompt) { CompletableDeferred() }.complete(Unit)
    }

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
        if (body.isClassificationRequest()) {
            return reply(body, "HELP 90")
        }
        return reply(body, "assistant reply to ${body.conversationPrompt()}")
    }

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> = flow {
        val prompt = body.conversationPrompt()
        val chunks = chunksByPrompt[prompt] ?: listOf("assistant ", "reply ", "to $prompt")
        startedByPrompt.getOrPut(prompt) { CompletableDeferred() }.complete(Unit)
        releaseByPrompt.getOrPut(prompt) { CompletableDeferred() }.await()
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
                            finishReason = if (index == chunks.lastIndex) LLMResponse.FinishReason.stop else null,
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

private class FailingAfterDeltaStage6StreamingChatApi : LLMChatAPI {
    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
        if (body.isClassificationRequest()) {
            return reply(body, "HELP 90")
        }
        return reply(body, "assistant reply to ${body.conversationPrompt()}")
    }

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> = flow {
        emit(
            LLMResponse.Chat.Ok(
                choices = listOf(
                    LLMResponse.Choice(
                        message = LLMResponse.Message(
                            content = "partial ",
                            role = LLMMessageRole.assistant,
                            functionsStateId = null,
                        ),
                        index = 0,
                        finishReason = null,
                    )
                ),
                created = 0L,
                model = body.model,
                usage = LLMResponse.Usage(7, 1, 8, 0),
            )
        )
        error("simulated streaming failure")
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

private class CancellableAfterDeltaStage6StreamingChatApi : LLMChatAPI {
    private val deltaSentByPrompt = LinkedHashMap<String, CompletableDeferred<Unit>>()

    suspend fun awaitDeltaSent(prompt: String) {
        deltaSentByPrompt.getOrPut(prompt) { CompletableDeferred() }.await()
    }

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
        if (body.isClassificationRequest()) {
            return reply(body, "HELP 90")
        }
        return reply(body, "assistant reply to ${body.conversationPrompt()}")
    }

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> = flow {
        val prompt = body.conversationPrompt()
        emit(
            LLMResponse.Chat.Ok(
                choices = listOf(
                    LLMResponse.Choice(
                        message = LLMResponse.Message(
                            content = "partial ",
                            role = LLMMessageRole.assistant,
                            functionsStateId = null,
                        ),
                        index = 0,
                        finishReason = null,
                    )
                ),
                created = 0L,
                model = body.model,
                usage = LLMResponse.Usage(7, 1, 8, 0),
            )
        )
        deltaSentByPrompt.getOrPut(prompt) { CompletableDeferred() }.complete(Unit)
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
