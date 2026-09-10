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
                        assertEquals(setOf(false, true), replies.map { it["duplicate"].asBoolean() }.toSet())
                        assertEquals(replies[0].deepCopy<ObjectNode>().put("duplicate", true),
                            replies[1].deepCopy<ObjectNode>().put("duplicate", true))
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
                val retry = request(socket, createFrame(userId, "create-1"), duplicate = true)
                assertEquals(chat, retry["chatId"].asText())
                request(socket, subscribeFrame(chat), duplicate = true)
                // No historical terminal may appear between the creation and next submit acknowledgements.
                submit(socket, chat, userId, "after-create-retry")
            }
        }

    @Test
    fun `duplicate submits auto subscribe without replaying the previous tool event`() =
        backendE2eTest("e2e_multi_submit_retry", llm = clientToolLlm()) {
            val userId = UUID.randomUUID().toString()
            val chat = createPublicChat(userId)
            val tool = withMultiChatSocket { submit(it, chat, userId, "submit") }
            withMultiChatSocket { socket ->
                val retry = request(socket, messageFrame(chat, userId, "submit"), duplicate = true)
                assertEquals(tool["threadId"], retry["thread"]["id"])
                assertEquals("thread.status", readJson(socket)["type"].asText())
                request(socket, toolResult(tool))
                readTerminal(socket, tool)
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
                chats.forEach { chat ->
                    request(socket, subscribeFrame(chat), duplicate = true)
                }
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
                    messageFrame(mobile, userId, "wrong-client"),
                    subscribeFrame(chat).replace("\"requestId\":\"subscribe\"", "\"requestId\":\" \""),
                    messageFrame(chat, UUID.randomUUID().toString(), "wrong-owner"),
                    historyFrame(missing, "missing-chat", "user", "history"),
                ) + listOf("-1", "1.5", "null", "\"1\"", "9223372036854775808").map { cursor ->
                    subscribeFrame(chat).dropLast(1) + ",\"afterSeq\":$cursor}"
                }
                invalid.forEach { raw ->
                    val ack = request(socket, raw, status = "rejected", duplicate = false)
                    assertEquals(json.readTree(raw)["requestId"], ack["requestId"])
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
        }
        backendE2eTest("e2e_multi_disabled", featureFlags = BackendFeatureFlags(wsEvents = false)) {
            withMultiChatSocket { socket ->
                assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, socket.closeReason.await()?.code)
            }
        }
    }

    private suspend fun BackendE2eScope.request(
        socket: DefaultClientWebSocketSession, raw: String, status: String = "accepted", duplicate: Boolean? = null,
    ): JsonNode {
        socket.send(Frame.Text(raw))
        return readJson(socket).also {
            assertEquals("ack", it["kind"].asText(), it.toString())
            assertEquals(status, it["status"].asText(), raw)
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
            assertFalse(it["payload"].has("target"))
        }
    }

    private fun createFrame(user: String, requestId: String = "create", title: String? = null): String =
        """{"kind":"chat.create","requestId":"$requestId","payload":{"userId":"$user","title":${title?.let { "\"$it\"" } ?: "null"}}}"""

    private fun subscribeFrame(chat: String, afterSeq: Long? = null): String =
        """{"kind":"chat.subscribe","chatId":"$chat","requestId":"subscribe"${afterSeq?.let { ",\"afterSeq\":$it" } ?: ""}}"""

    private fun toolResult(tool: JsonNode): String =
        """{"kind":"tool.result","chatId":${tool["chatId"]},"threadId":${tool["threadId"]},"toolCallId":${tool["payload"]["toolCallId"]},"status":"succeeded","result":{"answer":"yes"}}"""
}
