package ru.souz.backend.http

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
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
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import ru.souz.agent.AgentId
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.client.BackendClientSkills
import ru.souz.backend.client.ClientContractException
import ru.souz.backend.client.ClientDevice
import ru.souz.backend.client.MessageSubmitFrame
import ru.souz.backend.client.ToolResultFrame
import ru.souz.backend.events.bus.AgentEventBus
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.model.PublicToolCallStartedPayload
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.execution.repository.AgentExecutionRepository
import ru.souz.backend.settings.model.UserSettings
import ru.souz.backend.testutil.repository.MemoryAgentExecutionRepository
import ru.souz.backend.testutil.repository.MemoryToolCallRepository
import ru.souz.backend.toolcall.model.ToolCall
import ru.souz.backend.toolcall.model.ToolCallStatus
import ru.souz.backend.toolcall.repository.ToolCallContext
import ru.souz.backend.toolcall.repository.ToolCallRepository
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta
import ru.souz.tool.ToolCategory

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
            val status = json.readTree((session.incoming.receive() as Frame.Text).readText())
            val terminal = json.readTree((session.incoming.receive() as Frame.Text).readText())

            assertEquals("ack", ack["kind"].asText())
            assertEquals("accepted", ack["status"].asText())
            assertEquals(1, ack["submission"]["inputSeq"].asInt())
            assertEquals("status", status["kind"].asText())
            assertEquals("thread.status", status["type"].asText())
            assertEquals(ack["thread"]["id"].asText(), status["threadId"].asText())
            assertEquals("event", terminal["kind"].asText())
            assertEquals("thread.completed", terminal["type"].asText(), terminal.toString())
            assertEquals(ack["thread"]["id"].asText(), terminal["threadId"].asText())
            val queried = client.get(
                "${BackendHttpRoutes.chatThread(chatId, ack["thread"]["id"].asText())}?clientType=backend"
            )
            val queriedStatus = json.readTree(queried.bodyAsText())
            assertEquals(HttpStatusCode.OK, queried.status)
            assertEquals("completed", queriedStatus["status"].asText())
            assertFalse(queriedStatus["alive"].asBoolean())

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
            val firstStatus = json.readTree((session.incoming.receive() as Frame.Text).readText())
            val threadId = firstAck["thread"]["id"].asText()
            assertEquals("thread.status", firstStatus["type"].asText())
            assertEquals(threadId, firstStatus["threadId"].asText())
            val runningStatusResponse = client.get("${BackendHttpRoutes.chatThread(chatId, threadId)}?clientType=backend")
            val runningStatus = json.readTree(runningStatusResponse.bodyAsText())
            assertEquals(HttpStatusCode.OK, runningStatusResponse.status)
            assertEquals("running", runningStatus["status"].asText())
            assertTrue(runningStatus["alive"].asBoolean())
            assertTrue(runningStatus["acceptsInput"].asBoolean())
            api.awaitStarted("Первое сообщение")

            session.send(Frame.Text(messageFrame(chatId, userId, "message-2", threadId, "Второе сообщение", "device-2")))
            val secondAck = json.readTree((session.incoming.receive() as Frame.Text).readText())
            val secondStatus = json.readTree((session.incoming.receive() as Frame.Text).readText())
            api.awaitStarted("Второе сообщение")
            api.release()
            val terminal = json.readTree((session.incoming.receive() as Frame.Text).readText())

            assertEquals(2, secondAck["submission"]["inputSeq"].asInt())
            assertEquals(2, secondAck["thread"]["revision"].asInt())
            assertFalse(secondAck["thread"]["created"].asBoolean())
            assertEquals(2, secondStatus["revision"].asInt())
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
    fun `message meta model overrides model while omitted locale and time zone use persisted settings`() = testApplication {
        val api = CapturingChatApi()
        val context = publicContext(api)
        install(context)
        val userId = UUID.randomUUID().toString()
        runBlocking {
            context.userSettingsRepository.save(
                UserSettings(
                    userId = userId,
                    locale = Locale.forLanguageTag("en-US"),
                    timeZone = ZoneId.of("America/New_York"),
                )
            )
        }
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
                    messageFrame(chatId, userId, "message-1", null, "Используй qwen", "device-1")
                        .replace(
                            """"locale":"ru-RU","timeZone":"Europe/Moscow"""",
                            """"model":"${LLMModel.QwenMax.alias}"""",
                        )
                )
            )
            val ack = json.readTree((session.incoming.receive() as Frame.Text).readText())
            val status = json.readTree((session.incoming.receive() as Frame.Text).readText())
            val terminal = json.readTree((session.incoming.receive() as Frame.Text).readText())
            val execution = context.executionRepository.getByChat(
                userId,
                UUID.fromString(chatId),
                UUID.fromString(ack["thread"]["id"].asText()),
            )

            assertEquals("thread.status", status["type"].asText())
            assertEquals("thread.completed", terminal["type"].asText())
            assertEquals(LLMModel.QwenMax.alias, api.finalRequests.last().model)
            assertEquals(LLMModel.QwenMax, execution?.model)
            assertEquals("en-US", execution?.metadata?.get("locale"))
            assertEquals("America/New_York", execution?.metadata?.get("timeZone"))
            session.close()
        }
    }

    @Test
    fun `invalid initial model is rejected before registering thread state`() = runBlocking {
        val context = publicContext()
        val userId = UUID.randomUUID().toString()
        val chat = context.chatService.createClient(userId, "create-1", "backend", null).chat
        val frame = json.treeToValue(
            json.readTree(
                messageFrame(chat.id.toString(), userId, "message-1", null, "Привет", "device-1")
                    .replace(
                        "\"timeZone\":\"Europe/Moscow\"",
                        "\"timeZone\":\"Europe/Moscow\",\"model\":\"unknown-model\"",
                    )
            ),
            MessageSubmitFrame::class.java,
        )

        val error = assertFailsWith<ClientContractException> {
            context.publicClientService.handleMessage(chat, frame)
        }

        assertEquals("invalid_request", error.code)
        assertTrue(context.clientThreadRegistry.isEmpty())
        assertTrue(context.executionRepository.listByChat(userId, chat.id).isEmpty())
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
                    val ack = json.readTree((session.incoming.receive() as Frame.Text).readText())
                    val status = json.readTree((session.incoming.receive() as Frame.Text).readText())
                    ack to status
                }
            }.awaitAll()
            val ackPayloads = acknowledgements.map { it.first }

            assertEquals(listOf(false, true), ackPayloads.map { it["duplicate"].asBoolean() }.sorted())
            assertEquals(1, ackPayloads.map { it["thread"]["id"].asText() }.distinct().size)
            assertEquals(1, acknowledgements.map { it.second["threadId"].asText() }.distinct().size)
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
    fun `client websocket skill waits for an idempotent client result`() = runBlocking {
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
        val clientSkills = BackendClientSkills(
            registry = context.clientThreadRegistry,
            toolCallRepository = context.toolCallRepository,
            eventService = context.eventService,
        )
        val tool = requireNotNull(clientSkills.toolsByCategory[ToolCategory.CHAT]?.get("user.ask"))
        requireNotNull(clientSkills.toolsByCategory[ToolCategory.APPLICATIONS]?.get("device.media.open"))
        val invocation = async {
            tool.invoke(
                LLMResponse.FunctionCall("user.ask", mapOf("question" to "Какой жанр?")),
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
        assertEquals("user.ask", payload.name)
        assertEquals("Какой жанр?", payload.arguments["question"].asText())
        assertTrue(result.content.contains("Ужасы"))
        assertFalse(handledPayload["duplicate"].asBoolean())
        assertTrue(duplicatePayload["duplicate"].asBoolean())
        assertEquals("rejected", conflictingPayload["status"].asText())
        assertEquals("idempotency_conflict", conflictingPayload["error"]["code"].asText())
    }

    @Test
    fun `tool result completion race handles already terminal results by payload hash`() = runBlocking {
        val cases: List<Pair<Boolean, (String) -> String>> = listOf(
            true to { payloadHash -> payloadHash },
            false to { payloadHash -> "different:$payloadHash" },
        )

        cases.forEach { (matchingHash, terminalPayloadHash) ->
            val fixture = toolResultRaceFixture(terminalPayloadHash)

            val handled = fixture.context.publicClientService.handleToolResult(fixture.chat, fixture.frame)
            val ack = json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(handled.response)

            if (matchingHash) {
                assertEquals("accepted", ack["status"].asText())
                assertTrue(ack["duplicate"].asBoolean())
            } else {
                assertEquals("rejected", ack["status"].asText())
                assertEquals("idempotency_conflict", ack["error"]["code"].asText())
            }
        }
    }

    @Test
    fun `tool result error accepts documented details object`() {
        val mapper = jacksonObjectMapper()
            .registerKotlinModule()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

        val frame = mapper.readValue(
            """
            {
              "kind": "tool.result",
              "chatId": "${UUID.randomUUID()}",
              "threadId": "${UUID.randomUUID()}",
              "toolCallId": "call-1",
              "status": "failed",
              "error": {
                "code": "internal_error",
                "message": "failed",
                "details": {"traceId": "trace-1"}
              }
            }
            """.trimIndent(),
            ToolResultFrame::class.java,
        )

        assertEquals("trace-1", frame.error?.details?.path("traceId")?.asText())
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
        val clientSkills = BackendClientSkills(
            registry = context.clientThreadRegistry,
            toolCallRepository = context.toolCallRepository,
            eventService = context.eventService,
        )
        val tool = requireNotNull(clientSkills.toolsByCategory[ToolCategory.CHAT]?.get("user.ask"))
        val invocation = async {
            tool.invoke(
                LLMResponse.FunctionCall("user.ask", mapOf("question" to "Продолжить?")),
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
    fun `late successful tool result is rejected and records timeout`() = runBlocking {
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
        val toolCallId = UUID.randomUUID().toString()
        val contextKey = ToolCallContext(userId, chat.id.toString(), threadId.toString(), toolCallId)
        context.toolCallRepository.startClientCall(
            context = contextKey,
            name = "user.ask",
            deviceId = "device-tv",
            argumentsJson = "{}",
            deadlineAt = Instant.now().minusSeconds(1),
        )

        val handled = context.publicClientService.handleToolResult(
            chat,
            ToolResultFrame(
                kind = "tool.result",
                chatId = chat.id.toString(),
                threadId = threadId.toString(),
                toolCallId = toolCallId,
                status = "succeeded",
                result = json.readTree("""{"answer":"Поздно"}"""),
            ),
        )
        handled.afterSend()
        val ack = json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(handled.response)
        val stored = context.toolCallRepository.get(contextKey)

        assertEquals("rejected", ack["status"].asText())
        assertEquals("client_tool_timed_out", ack["error"]["code"].asText())
        assertEquals(ToolCallStatus.TIMED_OUT, stored?.status)
        assertTrue(stored?.errorJson.orEmpty().contains("client_tool_timed_out"))
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
            val messageStatus = json.readTree((session.incoming.receive() as Frame.Text).readText())
            val threadId = messageAck["thread"]["id"].asText()
            assertEquals(threadId, messageStatus["threadId"].asText())
            api.awaitStarted("Отмени меня")
            session.send(
                Frame.Text(
                    """{"kind":"thread.cancel","chatId":"$chatId","requestId":"cancel-1","threadId":"$threadId","reason":"user_requested"}"""
                )
            )
            val cancelAck = json.readTree((session.incoming.receive() as Frame.Text).readText())
            val cancelStatus = json.readTree((session.incoming.receive() as Frame.Text).readText())
            val terminal = json.readTree((session.incoming.receive() as Frame.Text).readText())

            assertEquals("accepted", cancelAck["status"].asText())
            assertEquals("cancel-1", cancelAck["requestId"].asText())
            assertEquals(threadId, cancelStatus["threadId"].asText())
            assertEquals("thread.cancelled", terminal["type"].asText())
            assertFalse(context.clientThreadRegistry.contains(UUID.fromString(threadId)))
            session.close()
        }
    }

    private suspend fun toolResultRaceFixture(
        terminalPayloadHash: (String) -> String,
    ): ToolResultRaceFixture {
        val repository = LostCompletionRaceToolCallRepository(terminalPayloadHash)
        val context = publicContext(toolCallRepository = repository)
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
        val toolCallId = UUID.randomUUID().toString()
        repository.startClientCall(
            context = ToolCallContext(userId, chat.id.toString(), threadId.toString(), toolCallId),
            name = "user.ask",
            deviceId = "device-tv",
            argumentsJson = "{}",
            deadlineAt = Instant.now().plusSeconds(60),
        )
        return ToolResultRaceFixture(
            context = context,
            chat = chat,
            frame = ToolResultFrame(
                kind = "tool.result",
                chatId = chat.id.toString(),
                threadId = threadId.toString(),
                toolCallId = toolCallId,
                status = "succeeded",
                result = json.readTree("""{"answer":"Да"}"""),
            ),
        )
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

    private fun publicContext(
        llmApi: ru.souz.llms.LLMChatAPI = CapturingChatApi(),
        toolCallRepository: ToolCallRepository = MemoryToolCallRepository(),
        executionRepository: AgentExecutionRepository = MemoryAgentExecutionRepository(),
    ): RouteTestContext = routeTestContext(
        llmApi = llmApi,
        toolCallRepository = toolCallRepository,
        executionRepository = executionRepository,
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

private data class ToolResultRaceFixture(
    val context: RouteTestContext,
    val chat: Chat,
    val frame: ToolResultFrame,
)

private class LostCompletionRaceToolCallRepository(
    private val terminalPayloadHash: (String) -> String,
    private val delegate: MemoryToolCallRepository = MemoryToolCallRepository(),
) : ToolCallRepository by delegate {
    override suspend fun completeClientCall(
        context: ToolCallContext,
        status: ToolCallStatus,
        resultJson: String?,
        errorJson: String?,
        payloadHash: String,
        receivedAt: Instant,
    ): ToolCall? {
        delegate.completeClientCall(
            context = context,
            status = status,
            resultJson = resultJson,
            errorJson = errorJson,
            payloadHash = terminalPayloadHash(payloadHash),
            receivedAt = receivedAt,
        )
        return null
    }
}
