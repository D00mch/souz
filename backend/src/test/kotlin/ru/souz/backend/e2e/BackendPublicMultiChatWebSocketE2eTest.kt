package ru.souz.backend.e2e

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.http.BackendHttpRoutes

class BackendPublicMultiChatWebSocketE2eTest {
    @Test
    fun `creation shares HTTP idempotency and distinguishes users on one connection`() =
        backendE2eTest("e2e_multi_create") {
            val users = List(2) { UUID.randomUUID().toString() }
            withMultiChatSocket { socket ->
                val created = users.map { user ->
                    request(socket, createFrame(user, title = "  My chat  ")).also {
                        assertEquals("chat.create", it["type"].asText())
                        assertEquals(user, it["userId"].asText())
                        assertEquals("accepted", it["status"].asText())
                        assertFalse(it["duplicate"].asBoolean())
                    }
                }
                assertNotEquals(created[0]["chatId"], created[1]["chatId"])
                created.forEach { ack ->
                    assertTrue(request(socket, subscribeFrame(ack["chatId"].asText()))["duplicate"].asBoolean())
                }
                val chatId = created[0]["chatId"].asText()
                client.patch(BackendHttpRoutes.chatTitle(chatId)) {
                    trusted(users[0])
                    jsonBody("""{"title":"Changed later"}""")
                }.also { assertEquals(HttpStatusCode.OK, it.status) }
                val duplicate = request(socket, createFrame(users[0], title = "My chat"))
                assertEquals(created[0].deepCopy<ObjectNode>().put("duplicate", true), duplicate)
                val conflict = request(socket, createFrame(users[0], title = "Different"))
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
    fun `concurrent creation on separate backend instances returns one durable chat`() =
        backendE2eTest("e2e_multi_create_race") {
            val primary = this
            val userId = UUID.randomUUID().toString()
            withPeerBackend { peer ->
                withMultiChatSocket { first ->
                    peer.withMultiChatSocket { second ->
                        val replies = coroutineScope {
                            val a = async { primary.request(first, createFrame(userId)) }
                            val b = async { peer.request(second, createFrame(userId)) }
                            listOf(a.await(), b.await())
                        }
                        assertEquals(replies[0]["chatId"], replies[1]["chatId"])
                        assertEquals(setOf(false, true), replies.map { it["duplicate"].asBoolean() }.toSet())
                        assertEquals(replies[0].deepCopy<ObjectNode>().put("duplicate", true),
                            replies[1].deepCopy<ObjectNode>().put("duplicate", true))
                    }
                }
            }
        }

    @Test
    fun `history tools and cancellation stay isolated across concurrent chats`() =
        backendE2eTest("e2e_multi_routing", llm = E2eLlmApi().apply {
            requestSkill("user.ask", mapOf("question" to "Continue?"))
        }) {
            val users = List(2) { UUID.randomUUID().toString() }
            withMultiChatSocket { socket ->
                val chats = users.map { request(socket, createFrame(it))["chatId"].asText() }
                val tools = chats.mapIndexed { index, chat ->
                    val history = request(socket, historyFrame(chat, "history", "user", "context-$index"))
                    assertEquals(chat, history["chatId"].asText())
                    submit(socket, chat, users[index], "same-request", "prompt-$index")
                }
                assertNotEquals(tools[0]["threadId"], tools[1]["threadId"])
                tools.forEachIndexed { index, tool ->
                    assertEquals(chats[index], tool["chatId"].asText())
                    assertEquals("tool.call.started", tool["type"].asText())
                }
                val wrongChat = toolResult(tools[0]).replace(chats[0], chats[1])
                assertEquals("tool_call_not_found", request(socket, wrongChat)["error"]["code"].asText())
                val cancel = """{"kind":"thread.cancel","chatId":"${chats[0]}","requestId":"cancel","threadId":${tools[0]["threadId"]}}"""
                val cancelAck = request(socket, cancel)
                assertEquals("accepted", cancelAck["status"].asText())
                assertEquals(chats[0], readJson(socket)["chatId"].asText()) // status
                val cancelled = readJson(socket)
                assertEquals("thread.cancelled", cancelled["type"].asText())
                assertTrue(cancelled["seq"].asLong() > tools[0]["seq"].asLong())
                assertEquals(cancelAck.deepCopy<ObjectNode>().put("duplicate", true), request(socket, cancel))
                readJson(socket) // duplicate status

                val accepted = request(socket, toolResult(tools[1]))
                assertEquals("accepted", accepted["status"].asText())
                assertEquals(chats[1], accepted["chatId"].asText())
                val completed = readJson(socket)
                assertEquals("thread.completed", completed["type"].asText())
                assertEquals(chats[1], completed["chatId"].asText())
                assertTrue(completed["seq"].asLong() > tools[1]["seq"].asLong())
                assertTrue(request(socket, toolResult(tools[1]))["duplicate"].asBoolean())
                llm.requests.forEach { request ->
                    val content = request.messages.joinToString { it.content }
                    assertFalse(content.contains("context-0") && content.contains("context-1"))
                }
            }
        }

    @Test
    fun `automatic subscriptions exclude old events for first submits and creation retries`() =
        backendE2eTest("e2e_multi_live_only") {
            val userId = UUID.randomUUID().toString()
            val chat = createPublicChat(userId)
            withPublicSocket(chat) { legacy -> submit(legacy, chat, userId, "old") }
            withMultiChatSocket { socket ->
                val live = submit(socket, chat, userId, "new")
                assertEquals("thread.completed", live["type"].asText())
            }
            withMultiChatSocket { socket ->
                val retry = request(socket, createFrame(userId, "create-1"))
                assertTrue(retry["duplicate"].asBoolean())
                assertEquals(chat, retry["chatId"].asText())
                assertTrue(request(socket, subscribeFrame(chat))["duplicate"].asBoolean())
                // No historical terminal may appear between the creation and next submit acknowledgements.
                submit(socket, chat, userId, "after-create-retry")
            }
        }

    @Test
    fun `duplicate submits auto subscribe without replaying the previous tool event`() =
        backendE2eTest("e2e_multi_submit_retry", llm = E2eLlmApi().apply {
            requestSkill("user.ask", mapOf("question" to "Still there?"))
        }) {
            val userId = UUID.randomUUID().toString()
            val chat = createPublicChat(userId)
            val raw = messageFrame(chat, userId, "submit")
            lateinit var original: JsonNode
            lateinit var tool: JsonNode
            withMultiChatSocket { socket ->
                original = request(socket, raw)
                readJson(socket)
                tool = readJson(socket)
            }
            withMultiChatSocket { socket ->
                assertEquals(original.deepCopy<ObjectNode>().put("duplicate", true), request(socket, raw))
                assertEquals("thread.status", readJson(socket)["type"].asText())
                assertEquals("accepted", request(socket, toolResult(tool))["status"].asText())
                assertEquals("thread.completed", readJson(socket)["type"].asText())
            }
        }

    @Test
    fun `reconnect replays each chat cursor and subscribe without a cursor keeps the stream`() =
        backendE2eTest("e2e_multi_replay", llm = E2eLlmApi().apply {
            requestSkill("user.ask", mapOf("question" to "Resume?"))
        }) {
            val userId = UUID.randomUUID().toString()
            val chats = listOf(createPublicChat(userId, "a"), createPublicChat(userId, "b"))
            val tools = withMultiChatSocket { socket ->
                chats.map { submit(socket, it, userId, "submit") }
            }
            val callsBefore = llm.requests.size
            withMultiChatSocket { socket ->
                assertFalse(request(socket, subscribeFrame(chats[0]))["duplicate"].asBoolean())
                assertEquals(tools[0], readJson(socket))
                assertEquals("accepted", request(socket, subscribeFrame(chats[1], tools[1]["seq"].asLong()))["status"].asText())
                chats.forEach { chat ->
                    assertTrue(request(socket, subscribeFrame(chat))["duplicate"].asBoolean())
                }
                assertEquals(callsBefore, llm.requests.size)
                tools.reversed().forEach { tool ->
                    assertEquals("accepted", request(socket, toolResult(tool))["status"].asText())
                    val terminal = readJson(socket)
                    assertEquals(tool["chatId"], terminal["chatId"])
                    assertEquals("thread.completed", terminal["type"].asText())
                    assertTrue(terminal["seq"].asLong() > tool["seq"].asLong())
                }
            }
        }

    @Test
    fun `explicit cursor replays an active subscription on the same socket and preserves other chats`() =
        backendE2eTest("e2e_multi_replay_connected", llm = E2eLlmApi().apply {
            requestSkill("user.ask", mapOf("question" to "Replay without disconnect"))
        }) {
            val userId = UUID.randomUUID().toString()
            val chats = listOf(createPublicChat(userId, "a"), createPublicChat(userId, "b"))
            withMultiChatSocket { socket ->
                val tools = chats.map { submit(socket, it, userId, "submit") }
                val callsBefore = llm.requests.size
                repeat(2) {
                    assertFalse(request(socket, subscribeFrame(chats[0], 0))["duplicate"].asBoolean())
                    assertEquals(tools[0], readJson(socket))
                }
                assertEquals(callsBefore, llm.requests.size)
                assertEquals("accepted", request(socket, toolResult(tools[1]))["status"].asText())
                val otherTerminal = readJson(socket)
                assertEquals(chats[1], otherTerminal["chatId"].asText())
                assertEquals("thread.completed", otherTerminal["type"].asText())

                val afterTool = subscribeFrame(chats[0], tools[0]["seq"].asLong())
                assertFalse(request(socket, afterTool)["duplicate"].asBoolean())
                // An invalid replacement must preserve the active live subscription.
                assertEquals("rejected", request(socket, subscribeFrame(chats[0], -1))["status"].asText())
                assertEquals("accepted", request(socket, toolResult(tools[0]))["status"].asText())
                val terminal = readJson(socket)
                assertEquals(chats[0], terminal["chatId"].asText())
                assertEquals("thread.completed", terminal["type"].asText())
                assertTrue(terminal["seq"].asLong() > tools[0]["seq"].asLong())

                val callsAfter = llm.requests.size
                assertFalse(request(socket, subscribeFrame(chats[0], 0))["duplicate"].asBoolean())
                assertEquals(tools[0], readJson(socket))
                assertEquals(terminal, readJson(socket))
                assertFalse(request(socket, subscribeFrame(chats[0], terminal["seq"].asLong()))["duplicate"].asBoolean())
                chats.forEach { chat ->
                    assertTrue(request(socket, subscribeFrame(chat))["duplicate"].asBoolean())
                }
                assertEquals(callsAfter, llm.requests.size)
            }
        }

    @Test
    fun `tool results and cancellation after disconnect work without subscribing`() =
        backendE2eTest("e2e_multi_unsubscribed", llm = E2eLlmApi().apply {
            requestSkill("user.ask", mapOf("question" to "Wait for the client"))
        }) {
            val userId = UUID.randomUUID().toString()
            val chats = listOf(createPublicChat(userId, "a"), createPublicChat(userId, "b"))
            val tools = withMultiChatSocket { socket ->
                chats.map { submit(socket, it, userId, "submit") }
            }
            withMultiChatSocket { socket ->
                assertEquals("accepted", request(socket, toolResult(tools[0]))["status"].asText())
                val cancel = """{"kind":"thread.cancel","chatId":"${chats[1]}","requestId":"cancel","threadId":${tools[1]["threadId"]}}"""
                assertEquals("accepted", request(socket, cancel)["status"].asText())
                assertEquals("thread.status", readJson(socket)["type"].asText())
                tools.forEachIndexed { index, tool ->
                    val subscription = request(socket, subscribeFrame(chats[index], tool["seq"].asLong()))
                    assertFalse(subscription["duplicate"].asBoolean())
                    val terminal = readJson(socket)
                    assertEquals(chats[index], terminal["chatId"].asText())
                    assertEquals(if (index == 0) "thread.completed" else "thread.cancelled", terminal["type"].asText())
                }
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
                    messageFrame(mobile, userId, "wrong-client"),
                    subscribeFrame(chat).replace("\"requestId\":\"subscribe\"", "\"requestId\":\" \""),
                    messageFrame(chat, UUID.randomUUID().toString(), "wrong-owner"),
                    historyFrame(missing, "missing-chat", "user", "history"),
                ) + listOf("-1", "1.5", "null", "\"1\"", "9223372036854775808").map { cursor ->
                    subscribeFrame(chat).dropLast(1) + ",\"afterSeq\":$cursor}"
                }
                invalid.forEach { raw ->
                    val ack = request(socket, raw)
                    assertEquals("rejected", ack["status"].asText(), raw)
                    assertFalse(ack["duplicate"].asBoolean())
                    assertEquals(json.readTree(raw)["requestId"], ack["requestId"])
                }
                // History alone must not create a subscription either.
                assertEquals("accepted", request(socket, historyFrame(chat, "history", "user", "saved"))["status"].asText())
                assertFalse(request(socket, subscribeFrame(chat))["duplicate"].asBoolean())
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
        }
        backendE2eTest("e2e_multi_disabled", featureFlags = BackendFeatureFlags(wsEvents = false)) {
            withMultiChatSocket { socket ->
                assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, socket.closeReason.await()?.code)
            }
        }
    }

    private suspend fun BackendE2eScope.request(socket: DefaultClientWebSocketSession, raw: String): JsonNode {
        socket.send(Frame.Text(raw))
        return readJson(socket).also { assertEquals("ack", it["kind"].asText(), it.toString()) }
    }

    private suspend fun BackendE2eScope.submit(
        socket: DefaultClientWebSocketSession, chat: String, user: String, requestId: String, text: String = "execute this",
    ): JsonNode {
        val ack = request(socket, messageFrame(chat, user, requestId, text = text))
        assertEquals("accepted", ack["status"].asText())
        assertEquals(chat, ack["chatId"].asText())
        val status = readJson(socket)
        assertEquals("thread.status", status["type"].asText())
        assertEquals(ack["thread"]["id"], status["threadId"])
        return readJson(socket).also {
            assertEquals("event", it["kind"].asText())
            assertEquals(ack["thread"]["id"], it["threadId"])
        }
    }

    private fun createFrame(user: String, requestId: String = "create", title: String? = null): String =
        """{"kind":"chat.create","requestId":"$requestId","payload":{"userId":"$user","title":${title?.let { "\"$it\"" } ?: "null"}}}"""

    private fun subscribeFrame(chat: String, afterSeq: Long? = null): String =
        """{"kind":"chat.subscribe","chatId":"$chat","requestId":"subscribe"${afterSeq?.let { ",\"afterSeq\":$it" } ?: ""}}"""

    private fun toolResult(tool: JsonNode): String =
        """{"kind":"tool.result","chatId":${tool["chatId"]},"threadId":${tool["threadId"]},"toolCallId":${tool["payload"]["toolCallId"]},"status":"succeeded","result":{"answer":"yes"}}"""
}
