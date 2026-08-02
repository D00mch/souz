package ru.souz.backend.http

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import ru.souz.agent.AgentId
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.client.BackendClientToolCatalog
import ru.souz.backend.client.ClientDevice
import ru.souz.backend.client.ToolResultFrame
import ru.souz.backend.client.USER_ASK_SKILL
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.model.PublicToolCallStartedPayload
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.toolcall.model.ToolCallStatus
import ru.souz.backend.toolcall.repository.ToolCallContext
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta
import ru.souz.tool.ToolCategory
import java.time.Instant

class BackendPublicClientContractRouteTest {
    private val json = jacksonObjectMapper()

    @Test
    fun `create chat is public strict and idempotent`() = testApplication {
        val context = publicContext()
        install(context)
        val userId = UUID.randomUUID().toString()
        val body = """{"userId":"$userId","requestId":"create-1","clientType":"backend","title":" Demo "}"""

        val created = client.post(BackendHttpRoutes.CHATS) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val retried = client.post(BackendHttpRoutes.CHATS) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val conflicting = client.post(BackendHttpRoutes.CHATS) {
            contentType(ContentType.Application.Json)
            setBody(body.replace("Demo", "Other"))
        }
        val unknownField = client.post(BackendHttpRoutes.CHATS) {
            contentType(ContentType.Application.Json)
            setBody(body.dropLast(1) + ",\"extra\":true}")
        }

        val firstPayload = json.readTree(created.bodyAsText())
        val retryPayload = json.readTree(retried.bodyAsText())
        assertEquals(HttpStatusCode.Created, created.status)
        assertFalse(firstPayload["duplicate"].asBoolean())
        assertEquals("Demo", firstPayload["chat"]["title"].asText())
        assertEquals(HttpStatusCode.OK, retried.status)
        assertTrue(retryPayload["duplicate"].asBoolean())
        assertEquals(firstPayload["chat"]["id"].asText(), retryPayload["chat"]["id"].asText())
        assertEquals(HttpStatusCode.Conflict, conflicting.status)
        assertEquals(HttpStatusCode.BadRequest, unknownField.status)
    }

    @Test
    fun `chat socket accepts a message and emits one terminal event`() = testApplication {
        val context = publicContext()
        install(context)
        val userId = UUID.randomUUID().toString()
        val create = client.post(BackendHttpRoutes.CHATS) {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"$userId","requestId":"create-1","clientType":"backend"}""")
        }
        val chatId = json.readTree(create.bodyAsText())["chat"]["id"].asText()
        val wsClient = createClient { install(WebSockets) }

        runBlocking {
            val session = wsClient.webSocketSession("${BackendHttpRoutes.chatWebSocket(chatId)}?clientType=backend")
            session.send(
                Frame.Text(
                    """{"kind":"message.submit","chatId":"$chatId","requestId":"message-1","payload":{"device":{"userId":"$userId","deviceId":"device-1","deviceType":"tv_box","capabilities":["speech","screen","device_tools"]},"content":{"type":"text","source":"voice","text":"Привет"},"meta":{"locale":"ru-RU","timeZone":"Europe/Moscow"}}}"""
                )
            )
            val ack = json.readTree((session.incoming.receive() as Frame.Text).readText())
            val terminal = json.readTree((session.incoming.receive() as Frame.Text).readText())

            assertEquals("ack", ack["kind"].asText())
            assertEquals("accepted", ack["status"].asText())
            assertEquals(1, ack["submission"]["inputSeq"].asInt())
            assertEquals("event", terminal["kind"].asText())
            assertEquals("thread.completed", terminal["type"].asText(), terminal.toString())
            assertEquals(ack["thread"]["id"].asText(), terminal["threadId"].asText())

            session.send(
                Frame.Text(
                    messageFrame(
                        chatId = chatId,
                        userId = userId,
                        requestId = "message-after-terminal",
                        threadId = ack["thread"]["id"].asText(),
                        text = "Слишком поздно",
                        deviceId = "device-1",
                    )
                )
            )
            val rejected = json.readTree((session.incoming.receive() as Frame.Text).readText())
            assertEquals("rejected", rejected["status"].asText())
            assertEquals("thread_already_terminal", rejected["error"]["code"].asText())
            assertEquals(
                1,
                context.messageRepository.list(userId, UUID.fromString(chatId)).count { it.role.value == "user" },
            )
            session.close()

            val replay = wsClient.webSocketSession(
                "${BackendHttpRoutes.chatWebSocket(chatId)}?clientType=backend&afterSeq=0"
            )
            val replayedTerminal = json.readTree((replay.incoming.receive() as Frame.Text).readText())
            assertEquals(terminal["seq"].asLong(), replayedTerminal["seq"].asLong())
            assertEquals("thread.completed", replayedTerminal["type"].asText())
            replay.close()
        }
    }

    @Test
    fun `running skills thread accepts a second message on the same socket`() = testApplication {
        val api = GateControlledChatApi()
        val context = publicContext(api)
        install(context)
        val userId = UUID.randomUUID().toString()
        val create = client.post(BackendHttpRoutes.CHATS) {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"$userId","requestId":"create-1","clientType":"backend"}""")
        }
        val chatId = json.readTree(create.bodyAsText())["chat"]["id"].asText()
        val wsClient = createClient { install(WebSockets) }

        runBlocking {
            val session = wsClient.webSocketSession("${BackendHttpRoutes.chatWebSocket(chatId)}?clientType=backend")
            session.send(Frame.Text(messageFrame(chatId, userId, "message-1", null, "Первое сообщение", "device-1")))
            val firstAck = json.readTree((session.incoming.receive() as Frame.Text).readText())
            val threadId = firstAck["thread"]["id"].asText()
            api.awaitStarted("Первое сообщение")

            session.send(Frame.Text(messageFrame(chatId, userId, "message-1", null, "Первое сообщение", "device-1")))
            val duplicateAck = json.readTree((session.incoming.receive() as Frame.Text).readText())
            session.send(Frame.Text(messageFrame(chatId, userId, "message-1", null, "Другой текст", "device-1")))
            val conflictingAck = json.readTree((session.incoming.receive() as Frame.Text).readText())

            session.send(Frame.Text(messageFrame(chatId, userId, "message-2", threadId, "Второе сообщение", "device-2")))
            val secondAck = json.readTree((session.incoming.receive() as Frame.Text).readText())
            api.awaitStarted("Второе сообщение")
            api.release()
            val terminal = json.readTree((session.incoming.receive() as Frame.Text).readText())

            assertTrue(duplicateAck["duplicate"].asBoolean())
            assertEquals(1, duplicateAck["submission"]["inputSeq"].asInt())
            assertEquals("rejected", conflictingAck["status"].asText())
            assertEquals("idempotency_conflict", conflictingAck["error"]["code"].asText())
            assertEquals(2, secondAck["submission"]["inputSeq"].asInt())
            assertEquals(2, secondAck["thread"]["revision"].asInt())
            assertFalse(secondAck["thread"]["created"].asBoolean())
            assertEquals("thread.completed", terminal["type"].asText())
            val messages = context.messageRepository.list(userId, UUID.fromString(chatId))
            assertEquals(listOf("1", "2"), messages.filter { it.role.value == "user" }.map { it.metadata["inputSeq"] })
            val execution = context.executionRepository.getByChat(userId, UUID.fromString(chatId), UUID.fromString(threadId))
            assertEquals(2, execution?.revision)
            assertTrue(execution?.latestDeviceContextJson.orEmpty().contains("device-2"))
            session.close()
        }
    }

    @Test
    fun `concurrent retries of the same message execute once`() = testApplication {
        val api = GateControlledChatApi()
        val context = publicContext(api)
        install(context)
        val userId = UUID.randomUUID().toString()
        val create = client.post(BackendHttpRoutes.CHATS) {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"$userId","requestId":"create-1","clientType":"backend"}""")
        }
        val chatId = json.readTree(create.bodyAsText())["chat"]["id"].asText()
        val wsClient = createClient { install(WebSockets) }

        runBlocking {
            val firstSession = wsClient.webSocketSession("${BackendHttpRoutes.chatWebSocket(chatId)}?clientType=backend")
            val secondSession = wsClient.webSocketSession("${BackendHttpRoutes.chatWebSocket(chatId)}?clientType=backend")
            val frameText = messageFrame(chatId, userId, "message-1", null, "Один раз", "device-1")
            val acknowledgements = listOf(firstSession, secondSession).map { session ->
                async {
                    session.send(Frame.Text(frameText))
                    json.readTree((session.incoming.receive() as Frame.Text).readText())
                }
            }.awaitAll()

            assertEquals(listOf(false, true), acknowledgements.map { it["duplicate"].asBoolean() }.sorted())
            assertEquals(1, acknowledgements.map { it["thread"]["id"].asText() }.distinct().size)
            assertEquals(
                1,
                context.messageRepository.list(userId, UUID.fromString(chatId)).count { it.role.value == "user" },
            )

            api.awaitStarted("Один раз")
            api.release()
            val terminal = json.readTree((firstSession.incoming.receive() as Frame.Text).readText())
            assertEquals("thread.completed", terminal["type"].asText())
            withTimeout(2_000) {
                while (context.clientThreadRegistry.contains(UUID.fromString(terminal["threadId"].asText()))) {
                    delay(10)
                }
            }
            firstSession.close()
            secondSession.close()
        }
    }

    @Test
    fun `user ask is a tool backed skill that waits for an idempotent client result`() = runBlocking {
        val context = publicContext()
        val userId = UUID.randomUUID().toString()
        val chat = context.chatService.createClient(userId, "create-1", "backend", null).chat
        val threadId = UUID.randomUUID()
        context.executionRepository.create(
            AgentExecution(
                id = threadId,
                userId = userId,
                chatId = chat.id,
                userMessageId = null,
                assistantMessageId = null,
                status = AgentExecutionStatus.RUNNING,
                requestId = null,
                clientMessageId = null,
                model = null,
                provider = null,
                startedAt = Instant.now(),
                finishedAt = null,
                cancelRequested = false,
                errorCode = null,
                errorMessage = null,
                usage = null,
                metadata = emptyMap(),
            )
        )
        context.clientThreadRegistry.register(
            threadId,
            ClientDevice(userId, "device-tv", "tv_box", setOf("speech", "screen", "device_tools")),
        )
        val catalog = BackendClientToolCatalog(
            context.clientThreadRegistry,
            context.toolCallRepository,
            context.eventService,
        )
        val tool = requireNotNull(catalog.toolsByCategory[ToolCategory.CHAT]?.get(USER_ASK_SKILL))
        val invocation = async {
            tool.invoke(
                LLMResponse.FunctionCall(USER_ASK_SKILL, mapOf("question" to "Какой жанр?")),
                ToolInvocationMeta(userId, chat.id.toString(), threadId.toString()),
            )
        }
        val started = withTimeout(2_000) {
            while (true) {
                context.eventRepository.listByChat(userId, chat.id).firstOrNull {
                    it.type == AgentEventType.TOOL_CALL_STARTED
                }?.let { return@withTimeout it }
                delay(10)
            }
            error("unreachable")
        }
        val payload = started.payload as PublicToolCallStartedPayload
        val handled = context.publicClientService.handleToolResult(
            chat,
            ToolResultFrame(
                kind = "tool.result",
                chatId = chat.id.toString(),
                threadId = threadId.toString(),
                toolCallId = payload.toolCallId,
                status = "succeeded",
                result = json.readTree("""{"answer":"Ужасы"}"""),
            ),
        )
        val duplicate = context.publicClientService.handleToolResult(
            chat,
            ToolResultFrame(
                kind = "tool.result",
                chatId = chat.id.toString(),
                threadId = threadId.toString(),
                toolCallId = payload.toolCallId,
                status = "succeeded",
                result = json.readTree("""{"answer":"Ужасы"}"""),
            ),
        )
        duplicate.afterSend()
        val result = invocation.await()
        val conflicting = context.publicClientService.handleToolResult(
            chat,
            ToolResultFrame(
                kind = "tool.result",
                chatId = chat.id.toString(),
                threadId = threadId.toString(),
                toolCallId = payload.toolCallId,
                status = "succeeded",
                result = json.readTree("""{"answer":"Комедия"}"""),
            ),
        )
        val handledPayload = json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(handled.response)
        val duplicatePayload = json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(duplicate.response)
        val conflictingPayload = json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(conflicting.response)

        assertEquals("device-tv", payload.deviceId)
        assertEquals("Какой жанр?", payload.arguments["question"].asText())
        assertTrue(result.content.contains("Ужасы"))
        assertFalse(handledPayload["duplicate"].asBoolean())
        assertTrue(duplicatePayload["duplicate"].asBoolean())
        assertEquals("rejected", conflictingPayload["status"].asText())
        assertEquals("idempotency_conflict", conflictingPayload["error"]["code"].asText())
    }

    @Test
    fun `cancelling a client tool persists its terminal state`() = runBlocking {
        val context = publicContext()
        val userId = UUID.randomUUID().toString()
        val chat = context.chatService.createClient(userId, "create-1", "backend", null).chat
        val threadId = UUID.randomUUID()
        context.executionRepository.create(
            AgentExecution(
                id = threadId,
                userId = userId,
                chatId = chat.id,
                userMessageId = null,
                assistantMessageId = null,
                status = AgentExecutionStatus.RUNNING,
                requestId = null,
                clientMessageId = null,
                model = null,
                provider = null,
                startedAt = Instant.now(),
                finishedAt = null,
                cancelRequested = false,
                errorCode = null,
                errorMessage = null,
                usage = null,
                metadata = emptyMap(),
            )
        )
        context.clientThreadRegistry.register(
            threadId,
            ClientDevice(userId, "device-tv", "tv_box", setOf("speech", "screen", "device_tools")),
        )
        val catalog = BackendClientToolCatalog(
            context.clientThreadRegistry,
            context.toolCallRepository,
            context.eventService,
        )
        val tool = requireNotNull(catalog.toolsByCategory[ToolCategory.CHAT]?.get(USER_ASK_SKILL))
        val invocation = async {
            tool.invoke(
                LLMResponse.FunctionCall(USER_ASK_SKILL, mapOf("question" to "Продолжить?")),
                ToolInvocationMeta(userId, chat.id.toString(), threadId.toString()),
            )
        }
        val started = withTimeout(2_000) {
            while (true) {
                context.eventRepository.listByChat(userId, chat.id).firstOrNull {
                    it.type == AgentEventType.TOOL_CALL_STARTED
                }?.let { return@withTimeout it }
                delay(10)
            }
            error("unreachable")
        }
        val payload = started.payload as PublicToolCallStartedPayload

        invocation.cancelAndJoin()

        val stored = context.toolCallRepository.get(
            ToolCallContext(userId, chat.id.toString(), threadId.toString(), payload.toolCallId)
        )
        assertEquals(ToolCallStatus.CANCELLED, stored?.status)
        assertTrue(stored?.errorJson.orEmpty().contains("client_tool_cancelled"))
    }

    @Test
    fun `thread cancel is acknowledged before the cancelled terminal event`() = testApplication {
        val api = CancellableChatApi()
        val context = publicContext(api)
        install(context)
        val userId = UUID.randomUUID().toString()
        val create = client.post(BackendHttpRoutes.CHATS) {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"$userId","requestId":"create-1","clientType":"backend"}""")
        }
        val chatId = json.readTree(create.bodyAsText())["chat"]["id"].asText()
        val wsClient = createClient { install(WebSockets) }

        runBlocking {
            val session = wsClient.webSocketSession("${BackendHttpRoutes.chatWebSocket(chatId)}?clientType=backend")
            session.send(Frame.Text(messageFrame(chatId, userId, "message-1", null, "Отмени меня", "device-1")))
            val messageAck = json.readTree((session.incoming.receive() as Frame.Text).readText())
            val threadId = messageAck["thread"]["id"].asText()
            api.awaitStarted("Отмени меня")
            session.send(
                Frame.Text(
                    """{"kind":"thread.cancel","chatId":"$chatId","requestId":"cancel-1","threadId":"$threadId","reason":"user_requested"}"""
                )
            )
            val cancelAck = json.readTree((session.incoming.receive() as Frame.Text).readText())
            val terminal = json.readTree((session.incoming.receive() as Frame.Text).readText())
            session.send(
                Frame.Text(
                    """{"kind":"thread.cancel","chatId":"$chatId","requestId":"cancel-1","threadId":"$threadId","reason":"user_requested"}"""
                )
            )
            val duplicateAck = json.readTree((session.incoming.receive() as Frame.Text).readText())
            session.send(
                Frame.Text(
                    """{"kind":"thread.cancel","chatId":"$chatId","requestId":"cancel-1","threadId":"$threadId","reason":"device_disconnected"}"""
                )
            )
            val conflictingAck = json.readTree((session.incoming.receive() as Frame.Text).readText())

            assertEquals("accepted", cancelAck["status"].asText())
            assertEquals("cancel-1", cancelAck["requestId"].asText())
            assertEquals("thread.cancelled", terminal["type"].asText())
            assertEquals("accepted", duplicateAck["status"].asText())
            assertTrue(duplicateAck["duplicate"].asBoolean())
            assertEquals("rejected", conflictingAck["status"].asText())
            assertEquals("idempotency_conflict", conflictingAck["error"]["code"].asText())
            assertFalse(context.clientThreadRegistry.contains(UUID.fromString(threadId)))
            session.close()
        }
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.install(context: RouteTestContext) {
        application {
            backendApplication(
                BackendHttpDependencies(
                    bootstrapService = context.bootstrapService,
                    selectedModel = { context.settingsProvider.gigaModel.alias },
                    ensureTrustedUser = context.userRepository::ensureUser,
                    chatService = context.chatService,
                    executionService = context.executionService,
                    eventService = context.eventService,
                    publicClientService = context.publicClientService,
                    featureFlags = context.featureFlags,
                )
            )
        }
    }

    private fun publicContext(llmApi: ru.souz.llms.LLMChatAPI = CapturingChatApi()): RouteTestContext = routeTestContext(
        llmApi = llmApi,
        featureFlags = BackendFeatureFlags(wsEvents = true, streamingMessages = false, toolEvents = true),
        agentId = AgentId.SKILLS_GRAPH,
    )

    private fun messageFrame(
        chatId: String,
        userId: String,
        requestId: String,
        threadId: String?,
        text: String,
        deviceId: String,
    ): String {
        val thread = threadId?.let { ",\"threadId\":\"$it\"" }.orEmpty()
        return """{"kind":"message.submit","chatId":"$chatId","requestId":"$requestId"$thread,"payload":{"device":{"userId":"$userId","deviceId":"$deviceId","deviceType":"tv_box","capabilities":["speech","screen","device_tools"]},"content":{"type":"text","source":"voice","text":"$text"},"meta":{"locale":"ru-RU","timeZone":"Europe/Moscow"}}}"""
    }
}
