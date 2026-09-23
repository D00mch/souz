package ru.souz.backend.e2e

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.http.BackendHttpRoutes

class BackendPublicMultiChatWebSocketE2eTest {
    @Test
    fun `MDC correlates resolved threads and provider work without leaking across frames or replay`() =
        backendE2eTest("e2e_multi_mdc") {
            val logs = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
            val logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            val appender = object : AppenderBase<ILoggingEvent>() {
                override fun append(event: ILoggingEvent) {
                    logs += event.formattedMessage to event.mdcPropertyMap.toMap()
                }
            }.apply { start() }
            logger.addAppender(appender)
            try {
                val users = List(2) { UUID.randomUUID().toString() }
                withMultiChatSocket { socket ->
                    val chats = users.map { request(socket, createFrame(it))["chatId"].asText() }
                    val terminals = chats.mapIndexed { index, chat -> submit(socket, chat, users[index], "submit") } +
                        listOf(submit(socket, chats[0], users[0], "next"))
                    request(socket, historyFrame(chats[0], "history", "user", "threadless history"))
                    request(socket, subscribeFrame(chats[0], 0), duplicate = false)
                    assertEquals(terminals[0], readJson(socket))
                    assertEquals(terminals[2], readJson(socket))
                    eventually("live and replay event logs") {
                        logs.count { (message, _) -> message == "WebSocket event sent" }.takeIf { it == 5 }
                    }

                    val owners = chats.zip(users).toMap()
                    val threadChats = terminals.associate { it["threadId"].asText() to it["chatId"].asText() }
                    val acknowledgements = logs.filter { (message, _) -> message.startsWith("WebSocket ack sent") }
                    terminals.forEachIndexed { index, event ->
                        val requestId = if (index == 2) "next" else "submit"
                        val fields = acknowledgements.single { (_, fields) ->
                            fields["chatId"] == event["chatId"].asText() && fields["clientRequestId"] == requestId
                        }.second
                        assertEquals(event["threadId"].asText(), fields["threadId"])
                        assertEquals(owners[fields["chatId"]], fields["userId"])
                    }
                    acknowledgements.filter { (_, fields) -> fields["clientRequestId"] in setOf("create", "history", "subscribe") }
                        .forEach { (_, fields) -> assertFalse(fields.containsKey("threadId")) }
                    logs.filter { (message, _) -> message == "WebSocket event sent" || message.startsWith("Public client event stored") }
                        .forEach { (_, fields) ->
                            assertEquals(threadChats[fields["threadId"]], fields["chatId"])
                            assertEquals(owners[fields["chatId"]], fields["userId"])
                            assertFalse(fields.containsKey("clientRequestId"))
                        }
                    assertTrue(llm.requestLogContexts.isNotEmpty())
                    assertEquals(threadChats.keys, llm.requestLogContexts.map { it["threadId"] }.toSet())
                    llm.requestLogContexts.forEach { fields ->
                        assertEquals(threadChats[fields["threadId"]], fields["chatId"])
                        assertEquals(owners[fields["chatId"]], fields["userId"])
                        assertEquals(if (fields["threadId"] == terminals[2]["threadId"].asText()) "next" else "submit", fields["initialClientRequestId"])
                        assertFalse(fields.containsKey("clientRequestId"))
                        assertFalse(fields.containsKey("socketId"))
                    }
                    val decodingFailures = listOf(
                        createFrame(users[0]),
                        subscribeFrame(chats[0]),
                        unsubscribeFrame(chats[0]),
                        messageFrame(chats[0], users[0], "decode-message"),
                        historyFrame(chats[0], "decode-history", "user", "history"),
                        """{"kind":"tool.result","chatId":"${chats[0]}","threadId":"${threadChats.keys.first()}","toolCallId":"tool","status":"succeeded","result":{}}""",
                        """{"kind":"thread.cancel","chatId":"${chats[0]}","threadId":"${threadChats.keys.first()}","requestId":"decode-cancel"}""",
                    ).map { raw -> json.readTree(raw).deepCopy<ObjectNode>().put("unexpected", true).toString() to "decode_frame" }
                    val rejections = decodingFailures + listOf(
                        createFrame("not-a-uuid") to "create_chat",
                        subscribeFrame(chats[0]).replace("\"requestId\":\"subscribe\"", "\"requestId\":\" \"") to "resolve_chat",
                        messageFrame(chats[0], users[1], "wrong-owner") to "handle_frame",
                        historyFrame(chats[0], "wrong-role", "system", "history") to "handle_frame",
                    )
                    rejections.forEach { (raw, stage) ->
                        val before = logs.size
                        val ack = request(socket, raw, status = "rejected")
                        val (message, fields) = logs.drop(before).single { it.first.startsWith("WebSocket frame rejected") }
                        assertEquals("invalid_request", ack["error"]["code"].asText())
                        assertTrue(message.contains("stage=$stage code=invalid_request"), message)
                        assertTrue(message.contains("details=${ack["error"].path("details").takeUnless { it.isMissingNode }}"), message)
                        assertEquals(json.readTree(raw)["kind"].asText(), fields["kind"])
                        assertEquals(stage == "decode_frame", ack["error"].has("details"))
                    }
                    chats.forEach { chat ->
                        val before = logs.size
                        request(socket, unsubscribeFrame(chat), duplicate = false)
                        val closed = logs.drop(before).single { it.first == "WebSocket subscription closed" }.second
                        assertEquals(chat, closed["chatId"])
                    }
                }
            } finally {
                logger.detachAppender(appender)
                appender.stop()
            }
        }

    @Test
    fun `creation shares HTTP idempotency and distinguishes users on one connection`() =
        backendE2eTest("e2e_multi_create") {
            val users = List(2) { UUID.randomUUID().toString() }
            withMultiChatSocket { socket ->
                val created = users.map { user ->
                    request(socket, createFrame(user, title = "  My chat  "), duplicate = false).also {
                        assertEquals("chat.create", it["type"].asText())
                        assertEquals(user, it["userId"].asText())
                    }
                }
                assertNotEquals(created[0]["chatId"], created[1]["chatId"])
                created.forEach { ack ->
                    request(socket, subscribeFrame(ack["chatId"].asText()), duplicate = true)
                }
                val chatId = created[0]["chatId"].asText()
                client.patch(BackendHttpRoutes.chatTitle(chatId)) {
                    trusted(users[0])
                    jsonBody("""{"title":"Changed later"}""")
                }.also { assertEquals(HttpStatusCode.OK, it.status) }
                val duplicate = request(socket, createFrame(users[0], title = "My chat"))
                assertEquals(created[0].deepCopy<ObjectNode>().put("duplicate", true), duplicate)
                val conflict = request(socket, createFrame(users[0], title = "Different"), status = "rejected")
                assertEquals("idempotency_conflict", conflict["error"]["code"].asText())
                assertEquals(users[0], conflict["userId"].asText())
                assertTrue(conflict["chatId"].isNull)

                val httpRetry = client.post(BackendHttpRoutes.CHATS) {
                    jsonBody("""{"userId":"${users[0]}","requestId":"create","clientType":"backend","title":"My chat"}""")
                }
                assertEquals(HttpStatusCode.OK, httpRetry.status)
                assertEquals(chatId, httpRetry.jsonBody()["chat"]["id"].asText())
                val httpChat = createPublicChat(users[0], "from-http")
                assertEquals(httpChat, request(socket, createFrame(users[0], "from-http"))["chatId"].asText())
                assertTrue(llm.requests.isEmpty())
            }
        }

    @Test
    fun `concurrent creation on one or two backends distinguishes duplicates and conflicts`() =
        backendE2eTest("e2e_multi_create_race") {
            val primary = this
            withPeerBackend { peer ->
                for (other in listOf(primary, peer)) {
                    withMultiChatSocket { first ->
                        other.withMultiChatSocket { second ->
                            for (title in listOf(null, "Different")) {
                                val userId = UUID.randomUUID().toString()
                                val replies = coroutineScope {
                                    val a = async { primary.request(first, createFrame(userId), status = null) }
                                    val b = async { other.request(second, createFrame(userId, title = title), status = null) }
                                    listOf(a.await(), b.await())
                                }
                                if (title == null) {
                                    assertEquals(setOf("accepted"), replies.map { it["status"].asText() }.toSet())
                                    assertEquals(setOf(false, true), replies.map { it["duplicate"].asBoolean() }.toSet())
                                    assertEquals(replies[0].deepCopy<ObjectNode>().put("duplicate", true),
                                        replies[1].deepCopy<ObjectNode>().put("duplicate", true))
                                } else {
                                    val accepted = replies.single { it["status"].asText() == "accepted" }
                                    val rejected = replies.single { it["status"].asText() == "rejected" }
                                    assertFalse(accepted["duplicate"].asBoolean())
                                    assertEquals("idempotency_conflict", rejected["error"]["code"].asText())
                                }
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun `chats stay isolated and tool results and cancellation work after reconnect without subscriptions`() =
        backendE2eTest("e2e_multi_routing", llm = clientToolLlm()) {
            val users = List(2) { UUID.randomUUID().toString() }
            val tools = withMultiChatSocket { socket ->
                val chats = users.map { request(socket, createFrame(it))["chatId"].asText() }
                chats.mapIndexed { index, chat ->
                    val history = request(socket, historyFrame(chat, "history", "user", "context-$index"))
                    assertEquals(chat, history["chatId"].asText())
                    submit(socket, chat, users[index], "same-request", "prompt-$index")
                }
            }
            withMultiChatSocket { socket ->
                assertNotEquals(tools[0]["threadId"], tools[1]["threadId"])
                tools.forEach { assertEquals("tool.call.started", it["type"].asText()) }
                val wrongChat = toolResult(tools[0]).replace(tools[0]["chatId"].asText(), tools[1]["chatId"].asText())
                assertEquals("tool_call_not_found", request(socket, wrongChat, status = "rejected")["error"]["code"].asText())
                val cancel = """{"kind":"thread.cancel","chatId":${tools[0]["chatId"]},"requestId":"cancel","threadId":${tools[0]["threadId"]}}"""
                assertEquals(tools[0]["chatId"], request(socket, cancel)["chatId"])
                val status = readJson(socket)
                assertEquals("thread.status", status["type"].asText())
                assertEquals(tools[0]["chatId"], status["chatId"])
                val accepted = request(socket, toolResult(tools[1]))
                assertEquals(tools[1]["chatId"], accepted["chatId"])
                tools.forEachIndexed { index, tool ->
                    request(socket, subscribeFrame(tool["chatId"].asText(), tool["seq"].asLong()), duplicate = false)
                    readTerminal(socket, tool, if (index == 0) "thread.cancelled" else "thread.completed")
                }
                llm.requests.forEach { request ->
                    val content = request.messages.joinToString { it.content }
                    assertFalse(content.contains("context-0") && content.contains("context-1"))
                }
            }
        }

    @Test
    fun `fresh submits and creation retries restore live-only subscriptions after reconnect and unsubscribe`() =
        backendE2eTest("e2e_multi_live_only") {
            val userId = UUID.randomUUID().toString()
            val chat = createPublicChat(userId)
            withPublicSocket(chat) { legacy -> submit(legacy, chat, userId, "old") }
            withMultiChatSocket { socket ->
                val live = submit(socket, chat, userId, "new")
                assertEquals("thread.completed", live["type"].asText())
                request(socket, unsubscribeFrame(chat), duplicate = false)
                submit(socket, chat, userId, "after-unsubscribe")
            }
            withMultiChatSocket { socket ->
                repeat(2) { attempt ->
                    val retry = request(socket, createFrame(userId, "create-1"), duplicate = true)
                    assertEquals(chat, retry["chatId"].asText())
                    request(socket, subscribeFrame(chat), duplicate = true)
                    // No historical terminal may appear between the creation and next submit acknowledgements.
                    submit(socket, chat, userId, "after-create-retry-$attempt")
                    request(socket, unsubscribeFrame(chat), duplicate = false)
                }
            }
        }

    @Test
    fun `duplicate submits restore live-only subscriptions after reconnect and unsubscribe while rejections do not`() =
        backendE2eTest("e2e_multi_submit_retry", llm = clientToolLlm()) {
            val userId = UUID.randomUUID().toString()
            val chat = createPublicChat(userId)
            val tool = withMultiChatSocket { submit(it, chat, userId, "submit") }
            withMultiChatSocket { socket ->
                repeat(2) { attempt ->
                    if (attempt > 0) request(socket, unsubscribeFrame(chat), duplicate = false)
                    request(socket, messageFrame(chat, UUID.randomUUID().toString(), "wrong-owner"), status = "rejected")
                    request(socket, unsubscribeFrame(chat), duplicate = true)
                    val retry = request(socket, messageFrame(chat, userId, "submit"), duplicate = true)
                    assertEquals(tool["threadId"], retry["thread"]["id"])
                    assertEquals("thread.status", readJson(socket)["type"].asText())
                    request(socket, subscribeFrame(chat), duplicate = true)
                }
                request(socket, toolResult(tool))
                readTerminal(socket, tool)
            }
        }

    @Test
    fun `unsubscribe isolates chats and sockets while preserving pending tools history and replay`() =
        backendE2eTest("e2e_multi_unsubscribe", llm = clientToolLlm()) {
            val user = UUID.randomUUID().toString()
            val chats = listOf(createPublicChat(user, "a"), createPublicChat(user, "b"))
            withMultiChatSocket { first ->
                val tools = chats.map { submit(first, it, user, "submit") }
                withMultiChatSocket { second ->
                    request(second, subscribeFrame(chats[0], tools[0]["seq"].asLong()), duplicate = false)
                    val ack = request(first, unsubscribeFrame(chats[0]), duplicate = false)
                    assertEquals("chat.unsubscribe", ack["type"].asText())
                    assertEquals("unsubscribe", ack["requestId"].asText())
                    assertEquals(chats[0], ack["chatId"].asText())
                    assertTrue(ack["error"].isNull)
                    request(first, unsubscribeFrame(chats[0]), duplicate = true)
                    // Replay retains the pending tool and its original deadline, without restarting the task.
                    val callsBefore = llm.requests.size
                    request(first, subscribeFrame(chats[0], 0), duplicate = false)
                    assertEquals(tools[0], readJson(first))
                    assertEquals(callsBefore, llm.requests.size)
                    request(first, unsubscribeFrame(chats[0]), duplicate = false)
                    request(first, historyFrame(chats[0], "history", "user", "saved while unsubscribed"))
                    request(first, unsubscribeFrame(chats[1]).dropLast(1) + ",\"afterSeq\":0}", status = "rejected")
                    request(first, toolResult(tools[0]))
                    val terminal = readTerminal(second, tools[0])
                    // The first socket still receives B, but must not receive A's terminal.
                    request(first, toolResult(tools[1]))
                    readTerminal(first, tools[1])
                    request(first, subscribeFrame(chats[0], tools[0]["seq"].asLong()), duplicate = false)
                    assertEquals(terminal, readJson(first))
                    submit(first, chats[0], user, "next")
                    assertTrue(llm.requests.last().messages.any { it.content.contains("saved while unsubscribed") })
                }
            }
        }

    @Test
    fun `unsubscribe ACK fences concurrent events until resubscription`() =
        backendE2eTest("e2e_multi_unsubscribe_race") {
            val user = UUID.randomUUID().toString()
            val chat = createPublicChat(user)
            withMultiChatSocket { observer ->
                withMultiChatSocket { producer ->
                    var cursor = 0L
                    repeat(10) { index ->
                        request(observer, subscribeFrame(chat, cursor), duplicate = false)
                        val terminal = coroutineScope {
                            val execution = async { submit(producer, chat, user, "submit-$index") }
                            observer.send(Frame.Text(unsubscribeFrame(chat)))
                            var frame = readJson(observer)
                            // A send already in flight may win the race, but only before the ACK.
                            while (frame["kind"].asText() == "event") {
                                assertEquals(chat, frame["chatId"].asText())
                                frame = readJson(observer)
                            }
                            assertEquals("chat.unsubscribe", frame["type"].asText())
                            assertEquals("accepted", frame["status"].asText())
                            assertFalse(frame["duplicate"].asBoolean())
                            execution.await()
                        }
                        request(observer, unsubscribeFrame(chat), duplicate = true)
                        assertEquals(null, withTimeoutOrNull(100) { readJson(observer) })
                        request(observer, subscribeFrame(chat, cursor), duplicate = false)
                        assertEquals(terminal, readJson(observer))
                        cursor = terminal["seq"].asLong()
                        request(observer, unsubscribeFrame(chat), duplicate = false)
                    }
                }
            }
        }

    @Test
    fun `reconnect and cursor replacement replay each chat independently without executing input`() =
        backendE2eTest("e2e_multi_replay", llm = clientToolLlm()) {
            val userId = UUID.randomUUID().toString()
            val chats = listOf(createPublicChat(userId, "a"), createPublicChat(userId, "b"))
            val tools = withMultiChatSocket { socket ->
                chats.map { submit(socket, it, userId, "submit") }
            }
            val callsBefore = llm.requests.size
            withMultiChatSocket { socket ->
                request(socket, subscribeFrame(chats[0]), duplicate = false)
                assertEquals(tools[0], readJson(socket))
                request(socket, subscribeFrame(chats[1], tools[1]["seq"].asLong()), duplicate = false)
                chats.forEach { chat ->
                    request(socket, subscribeFrame(chat), duplicate = true)
                }
                repeat(2) {
                    request(socket, subscribeFrame(chats[0], 0), duplicate = false)
                    assertEquals(tools[0], readJson(socket))
                }
                assertEquals(callsBefore, llm.requests.size)
                request(socket, toolResult(tools[1]))
                readTerminal(socket, tools[1])

                request(socket, subscribeFrame(chats[0], tools[0]["seq"].asLong()), duplicate = false)
                // An invalid replacement must preserve the active live subscription.
                request(socket, subscribeFrame(chats[0], -1), status = "rejected")
                request(socket, """{"kind":"thread.cancel","chatId":"${chats[0]}","requestId":"cancel","threadId":${tools[0]["threadId"]}}""")
                assertEquals("thread.status", readJson(socket)["type"].asText())
                val terminal = readTerminal(socket, tools[0], "thread.cancelled")

                val callsAfter = llm.requests.size
                request(socket, subscribeFrame(chats[0], 0), duplicate = false)
                assertEquals(tools[0], readJson(socket))
                assertEquals(terminal, readJson(socket))
                assertEquals(callsAfter, llm.requests.size)
            }
        }

    @Test
    fun `recoverable frame errors preserve correlation and do not subscribe`() =
        backendE2eTest("e2e_multi_invalid") {
            val userId = UUID.randomUUID().toString()
            val chat = createPublicChat(userId)
            val missing = UUID.randomUUID().toString()
            val mobile = client.post(BackendHttpRoutes.CHATS) {
                jsonBody("""{"userId":"$userId","requestId":"mobile","clientType":"mobile_app"}""")
            }.jsonBody()["chat"]["id"].asText()
            withMultiChatSocket { socket ->
                val invalid = listOf(
                    createFrame("not-a-uuid"),
                    createFrame(userId, " "),
                    createFrame(userId).replace("\"payload\":", "\"extra\":true,\"payload\":"),
                    createFrame(userId).replace("\"userId\":", "\"clientType\":\"mobile_app\",\"userId\":"),
                    subscribeFrame("not-a-uuid"),
                    subscribeFrame(missing),
                    subscribeFrame(mobile),
                    unsubscribeFrame("not-a-uuid"),
                    unsubscribeFrame(missing),
                    unsubscribeFrame(mobile),
                    unsubscribeFrame(chat).replace("\"requestId\":\"unsubscribe\"", "\"requestId\":\" \""),
                    unsubscribeFrame(chat).replace(",\"requestId\":\"unsubscribe\"", ""),
                    unsubscribeFrame(chat).dropLast(1) + ",\"afterSeq\":0}",
                    messageFrame(mobile, userId, "wrong-client"),
                    subscribeFrame(chat).replace("\"requestId\":\"subscribe\"", "\"requestId\":\" \""),
                    messageFrame(chat, UUID.randomUUID().toString(), "wrong-owner"),
                    historyFrame(missing, "missing-chat", "user", "history"),
                ) + listOf("-1", "1.5", "null", "\"1\"", "9223372036854775808").map { cursor ->
                    subscribeFrame(chat).dropLast(1) + ",\"afterSeq\":$cursor}"
                }
                invalid.forEach { raw ->
                    val ack = request(socket, raw, status = "rejected", duplicate = false)
                    assertEquals(json.readTree(raw).path("requestId").asText("invalid"), ack["requestId"].asText())
                }
                // History alone must not create a subscription either.
                request(socket, historyFrame(chat, "history", "user", "saved"))
                request(socket, subscribeFrame(chat), duplicate = false)
                assertTrue(llm.requests.isEmpty())
            }
        }

    @Test
    fun `new route enforces backend client type feature flag and policy close`() {
        backendE2eTest("e2e_multi_boundary") {
            assertEquals(HttpStatusCode.BadRequest, client.get(BackendHttpRoutes.WS).status)
            assertEquals(HttpStatusCode.Unauthorized, client.post(BackendHttpRoutes.WS).status)
            webSocketClient().use { client ->
                listOf("", "?clientType=mobile_app", "?clientType=invalid").forEach { query ->
                    val socket = client.webSocketSession("${BackendHttpRoutes.WS}$query")
                    assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, socket.closeReason.await()?.code)
                    socket.close()
                }
            }
            listOf("not JSON", "[]", "{\"kind\":\"unknown\"}").forEach { raw ->
                withMultiChatSocket { socket ->
                    socket.send(Frame.Text(raw))
                    assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, socket.closeReason.await()?.code)
                }
            }
            val chat = createPublicChat(UUID.randomUUID().toString())
            withPublicSocket(chat) { socket ->
                socket.send(Frame.Text(unsubscribeFrame(chat)))
                assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, socket.closeReason.await()?.code)
            }
        }
        backendE2eTest("e2e_multi_disabled", featureFlags = BackendFeatureFlags(wsEvents = false)) {
            withMultiChatSocket { socket ->
                assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, socket.closeReason.await()?.code)
            }
        }
    }

    @Test
    fun `channel tools coexist with target threads and isolate results without replay or persistence`() =
        backendE2eTest("e2e_channel_tools") {
            val user = UUID.randomUUID().toString()
            withMultiChatSocket { socket ->
                val source = request(socket, createFrame(user, "source"))["chatId"].asText()
                val target = request(socket, createFrame(user, "target"))["chatId"].asText()
                val foreign = request(socket, createFrame(UUID.randomUUID().toString()))["chatId"].asText()
                llm.requestSkillForPrompt("local", "user.ask", mapOf("question" to "Continue?"))
                llm.requestSkillForPrompt("remote", "orion.call", mapOf("channelId" to target, "utterance" to "включи Pink Floyd"))
                val local = submit(socket, target, user, "local", "local")
                val sourceAck = request(socket, messageFrame(source, user, "remote", text = "remote"))
                assertEquals("thread.status", readJson(socket)["type"].asText())
                val remote = readJson(socket)
                assertEquals("tool.call.started", remote["type"].asText())
                assertEquals(target, remote["chatId"].asText())
                assertTrue(remote["seq"].isNull)
                assertNotEquals(local["threadId"], remote["threadId"])
                assertEquals("orion.call", remote["payload"]["name"].asText())
                assertEquals(json.readTree("""{"utterance":"включи Pink Floyd"}"""), remote["payload"]["arguments"])
                assertFalse(remote["payload"].has("target"))
                assertEquals(HttpStatusCode.NotFound, client.get(
                    "${BackendHttpRoutes.chatThread(target, remote["threadId"].asText())}?clientType=backend",
                ).status)
                for (wrongChat in listOf(source, foreign)) {
                    val wrongResult = toolResult(remote).replace(target, wrongChat)
                    assertEquals("tool_call_not_found", request(socket, wrongResult, status = "rejected")["error"]["code"].asText())
                }
                request(socket, toolResult(remote, """{"reply":"Включаю Pink Floyd"}"""))
                val completed = readJson(socket)
                assertEquals("thread.completed", completed["type"].asText())
                assertEquals(sourceAck["thread"]["id"], completed["threadId"])
                assertTrue(llm.requests.last().messages.last { it.name == "RunSkillCommand" }.content.contains("Включаю Pink Floyd"))
                assertEquals("tool_call_not_found", request(socket, toolResult(remote), status = "rejected")["error"]["code"].asText())
                request(socket, toolResult(local))
                val localCompleted = readTerminal(socket, local)
                request(socket, subscribeFrame(target, 0), duplicate = false)
                assertEquals(local, readJson(socket))
                assertEquals(localCompleted, readJson(socket))
                // A subsequent ACK also proves there was no extra replayed remote call.
                request(socket, unsubscribeFrame(target))
            }
        }

    @Test
    fun `channel calls are available to HTTP executions without an attached client`() =
        backendE2eTest("e2e_http_channel_tool") {
            val user = UUID.randomUUID().toString()
            val source = createPublicChat(user)
            val target = createPublicChat(user, "target")
            client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(user)
                jsonBody("""{"defaultModel":"${E2E_LOCAL_MODEL.alias}"}""")
            }
            llm.requestSkill("orion.call", mapOf("channelId" to target, "utterance" to "тише"))
            withPublicSocket(target) { socket ->
                client.post(BackendHttpRoutes.chatMessages(source)) {
                    trusted(user)
                    jsonBody("""{"content":"remote"}""")
                }.also { assertEquals(HttpStatusCode.OK, it.status) }
                val remote = readJson(socket)
                assertTrue(remote["seq"].isNull)
                assertEquals("orion.call", remote["payload"]["name"].asText())
                request(socket, toolResult(remote))
                eventually("HTTP tool result") {
                    client.get(BackendHttpRoutes.chatMessages(source)) { trusted(user) }.jsonBody()["items"]
                        .firstOrNull { it["role"].asText() == "assistant" }
                }
                assertTrue(client.get(BackendHttpRoutes.chatEvents(target)) { trusted(user) }.jsonBody()["items"].isEmpty)
            }
        }

    @Test
    fun `invalid unavailable and unowned channel targets fail and cancellation discards pending calls`() =
        backendE2eTest("e2e_channel_tool_failures") {
            val user = UUID.randomUUID().toString()
            val offline = createPublicChat(user, "offline")
            val archived = createPublicChat(user, "archived")
            client.post(BackendHttpRoutes.archiveChat(archived)) { trusted(user) }
            val mobile = client.post(BackendHttpRoutes.CHATS) {
                jsonBody("""{"userId":"$user","requestId":"mobile","clientType":"mobile_app"}""")
            }.jsonBody()["chat"]["id"].asText()
            withMultiChatSocket { socket ->
                val source = request(socket, createFrame(user, "source"))["chatId"].asText()
                val foreign = request(socket, createFrame(UUID.randomUUID().toString()))["chatId"].asText()
                val invalid: List<Any> = listOf("", "not-a-uuid", 123, UUID.randomUUID().toString(), foreign, archived, mobile, offline)
                invalid.forEachIndexed { index, target ->
                    val prompt = "invalid-$index"
                    llm.requestSkillForPrompt(prompt, "orion.call", mapOf("channelId" to target, "utterance" to "тише"))
                    assertEquals("thread.completed", submit(socket, source, user, prompt, prompt)["type"].asText())
                    assertTrue(llm.requests.last().messages.last { it.name == "RunSkillCommand" }.content.contains("client_context_missing"))
                }
                request(socket, subscribeFrame(offline))
                llm.requestSkillForPrompt("cancel", "orion.call", mapOf("channelId" to offline, "utterance" to "тише"))
                val ack = request(socket, messageFrame(source, user, "cancel", text = "cancel"))
                readJson(socket) // thread.status
                val remote = readJson(socket)
                request(socket, """{"kind":"thread.cancel","chatId":"$source","requestId":"stop","threadId":${ack["thread"]["id"]}}""")
                assertEquals("thread.status", readJson(socket)["type"].asText())
                assertEquals("thread.cancelled", readJson(socket)["type"].asText())
                assertEquals("tool_call_not_found", request(socket, toolResult(remote), status = "rejected")["error"]["code"].asText())
            }
        }

    private suspend fun BackendE2eScope.request(
        socket: DefaultClientWebSocketSession, raw: String, status: String? = "accepted", duplicate: Boolean? = null,
    ): JsonNode {
        socket.send(Frame.Text(raw))
        return readJson(socket).also {
            assertEquals("ack", it["kind"].asText(), it.toString())
            status?.let { expected -> assertEquals(expected, it["status"].asText(), raw) }
            duplicate?.let { expected -> assertEquals(expected, it["duplicate"].asBoolean(), raw) }
        }
    }

    private suspend fun BackendE2eScope.readTerminal(
        socket: DefaultClientWebSocketSession, tool: JsonNode, type: String = "thread.completed",
    ): JsonNode = readJson(socket).also {
        assertEquals("event", it["kind"].asText(), it.toString())
        assertEquals(type, it["type"].asText())
        assertEquals(tool["chatId"], it["chatId"])
        assertEquals(tool["threadId"], it["threadId"])
        assertTrue(it["seq"].asLong() > tool["seq"].asLong())
    }

    private fun clientToolLlm() = E2eLlmApi().apply {
        requestSkill("user.ask", mapOf("question" to "Continue?"))
    }

    private suspend fun BackendE2eScope.submit(
        socket: DefaultClientWebSocketSession, chat: String, user: String, requestId: String, text: String = "execute this",
    ): JsonNode {
        val ack = request(socket, messageFrame(chat, user, requestId, text = text))
        assertEquals(chat, ack["chatId"].asText())
        val status = readJson(socket)
        assertEquals("thread.status", status["type"].asText())
        assertEquals(ack["thread"]["id"], status["threadId"])
        return readJson(socket).also {
            assertEquals("event", it["kind"].asText())
            assertEquals(chat, it["chatId"].asText())
            assertEquals(ack["thread"]["id"], it["threadId"])
        }
    }

    private fun createFrame(user: String, requestId: String = "create", title: String? = null): String =
        """{"kind":"chat.create","requestId":"$requestId","payload":{"userId":"$user","title":${title?.let { "\"$it\"" } ?: "null"}}}"""

    private fun subscribeFrame(chat: String, afterSeq: Long? = null): String =
        """{"kind":"chat.subscribe","chatId":"$chat","requestId":"subscribe"${afterSeq?.let { ",\"afterSeq\":$it" } ?: ""}}"""

    private fun unsubscribeFrame(chat: String): String =
        """{"kind":"chat.unsubscribe","chatId":"$chat","requestId":"unsubscribe"}"""

    private fun toolResult(tool: JsonNode, result: String = """{"answer":"yes"}"""): String =
        """{"kind":"tool.result","chatId":${tool["chatId"]},"threadId":${tool["threadId"]},"toolCallId":${tool["payload"]["toolCallId"]},"status":"succeeded","result":$result}"""
}
