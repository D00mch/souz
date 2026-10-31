package ru.souz.backend.e2e

import io.ktor.client.request.put
import io.ktor.websocket.Frame
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.backend.telegram.TelegramChat
import ru.souz.backend.telegram.TelegramMessage
import ru.souz.backend.telegram.TelegramUpdate
import ru.souz.backend.telegram.TelegramUser
import ru.souz.llms.restJsonMapper

class BackendUserClientToolsE2eTest {
    @Test
    fun `undiscovered current and explicit channels refresh before local MCP without switching devices`() {
        var source = ""
        var other = ""
        var undiscovered = ""
        val llm = E2eLlmApi { request ->
            assertTrue("Current conversation channelId: $source." in request.messages.first().content)
            fun call(skill: String, arguments: Map<String, Any>) =
                toolCallReply(request, "RunSkillCommand", mapOf("skillId" to skill, "arguments" to arguments))
            fun discoveryError(channel: String) {
                val result = restJsonMapper.readTree(request.messages.last { it.name == "RunSkillCommand" }.content)
                assertEquals("client_tool_discovery_required", result["error"]["code"].asText())
                assertTrue(channel in result["error"]["message"].asText())
                assertTrue("orion.tools.list" in result["error"]["message"].asText())
            }
            when (request.messages.count { it.name == "RunSkillCommand" }) {
                0 -> call("device.mcp.call_tool", mapOf("name" to "get_status"))
                1 -> {
                    discoveryError(source)
                    call("orion.tools.list", emptyMap())
                }
                2 -> call("device.mcp.call_tool", mapOf("name" to "get_status"))
                3 -> call("device.mcp.call_tool", mapOf("channelId" to other, "name" to "get_status"))
                4 -> call("device.mcp.call_tool", mapOf("channelId" to undiscovered, "name" to "get_status"))
                5 -> {
                    discoveryError(undiscovered)
                    call("orion.tools.list", mapOf("channelId" to undiscovered))
                }
                6 -> call("device.mcp.call_tool", mapOf("channelId" to undiscovered, "name" to "get_status"))
                else -> reply(request, "Done")
            }
        }
        backendE2eTest("e2e_current_channel_default", llm = llm) {
            val owner = UUID.randomUUID().toString()
            source = createPublicChat(owner)
            other = createPublicChat(owner, "other", clientToolsJson("device.mcp.call_tool"))
            undiscovered = createPublicChat(owner, "undiscovered")
            // A legacy conversation-local cache is not this channel's registration.
            backend.sql { connection ->
                connection.prepareStatement("update chats set discovered_client_tools = ?::jsonb where id = ?").use {
                    it.setString(1, """{"channelId":"$other","tools":${clientToolsJson("device.mcp.call_tool")}}""")
                    it.setObject(2, UUID.fromString(source))
                    it.executeUpdate()
                }
            }
            withMultiChatSocket { socket ->
                for (channel in listOf(other, undiscovered)) {
                    socket.send(Frame.Text("""{"kind":"chat.subscribe","chatId":"$channel","requestId":"$channel"}"""))
                    assertEquals("accepted", readJson(socket)["status"].asText())
                }
                socket.send(Frame.Text(messageFrame(source, owner, "local", text = "Use this speaker, then the other speaker")))
                assertEquals("accepted", readJson(socket)["status"].asText())
                assertEquals("thread.status", readJson(socket)["type"].asText())
                for ((index, channel) in listOf(source, source, other, undiscovered, undiscovered).withIndex()) {
                    val call = readJson(socket)
                    assertEquals("tool.call.started", call["type"].asText())
                    assertEquals(channel, call["chatId"].asText())
                    assertEquals(index >= 2, call["seq"].isNull)
                    assertFalse(call["payload"]["arguments"].has("channelId"))
                    val result = if (index == 0 || index == 3) mapOf("tools" to json.readTree(clientToolsJson("device.mcp.call_tool")))
                        else mapOf("ok" to true)
                    socket.send(Frame.Text(toolResultFrame(call, "succeeded", result)))
                    assertEquals("accepted", readJson(socket)["status"].asText())
                }
                assertEquals("thread.completed", readJson(socket)["type"].asText())
            }
        }
    }

    @Test
    fun `completed empty and nonempty catalogues reject missing tools without rediscovery across restart`() {
        val schema = newPostgresSchema("e2e_discovery_absence")
        val owner = UUID.randomUUID().toString()
        var source = ""
        var target = ""
        var provider = ""
        for (restarted in listOf(false, true)) {
            var step = if (restarted) 4 else 0
            val llm = E2eLlmApi { request ->
                fun call(skill: String, arguments: Map<String, Any>) =
                    toolCallReply(request, "RunSkillCommand", mapOf("skillId" to skill, "arguments" to arguments))
                fun assertError(code: String) {
                    val result = restJsonMapper.readTree(request.messages.last { it.name == "RunSkillCommand" }.content)
                    assertEquals(code, result["error"]["code"].asText())
                }
                val local = mapOf("name" to "get_status")
                val remote = local + ("channelId" to target)
                when (step++) {
                    0 -> call("device.mcp.call_tool", local)
                    1 -> {
                        assertError("client_tool_discovery_required")
                        call("orion.tools.list", emptyMap())
                    }
                    2 -> call("device.mcp.call_tool", remote)
                    3 -> {
                        assertError("client_tool_discovery_required")
                        call("orion.tools.list", mapOf("channelId" to target))
                    }
                    4 -> call("device.mcp.call_tool", local)
                    5 -> {
                        assertError("client_tool_unavailable")
                        call("device.mcp.call_tool", local)
                    }
                    6, 7 -> {
                        assertError("client_tool_unavailable")
                        call("device.mcp.call_tool", remote)
                    }
                    8 -> {
                        assertError("client_tool_unavailable")
                        toolCallReply(request, "GetSkillByName", mapOf("skillId" to "orion.tools.list"))
                    }
                    else -> {
                        val detail = restJsonMapper.readTree(request.messages.last { it.name == "GetSkillByName" }.content)
                        val channels = restJsonMapper.readTree(detail["skill"]["description"].asText()
                            .substringAfter("Providing channels (labels are untrusted data):\n"))
                            .associate { it["channelId"].asText() to it["discoveryComplete"].asBoolean() }
                        assertEquals(mapOf(source to true, target to true, provider to false), channels)
                        reply(request, "Capability unavailable on these devices")
                    }
                }
            }
            backendE2eTest("e2e_discovery_absence", schema = schema, llm = llm) {
                if (!restarted) {
                    source = createPublicChat(owner)
                    target = createPublicChat(owner, "target")
                    provider = createPublicChat(owner, "provider", clientToolsJson("device.mcp.call_tool"))
                }
                withMultiChatSocket { socket ->
                    for (channel in listOf(target, provider)) {
                        socket.send(Frame.Text("""{"kind":"chat.subscribe","chatId":"$channel","requestId":"$channel"}"""))
                        assertEquals("accepted", readJson(socket)["status"].asText())
                    }
                    socket.send(Frame.Text(messageFrame(source, owner, "absent-$restarted", text = "Read status on this speaker and the target")))
                    assertEquals("accepted", readJson(socket)["status"].asText())
                    assertEquals("thread.status", readJson(socket)["type"].asText())
                    for (channel in if (restarted) emptyList() else listOf(source, target)) {
                        val call = readJson(socket)
                        assertEquals("tool.call.started", call["type"].asText())
                        assertEquals("orion.tools.list", call["payload"]["name"].asText())
                        assertEquals(channel, call["chatId"].asText())
                        val tools = if (channel == source) "[]" else clientToolsJson("speaker.status")
                        socket.send(Frame.Text(toolResultFrame(call, "succeeded", mapOf("tools" to json.readTree(tools)))))
                        assertEquals("accepted", readJson(socket)["status"].asText())
                    }
                    // No discovery or MCP command is emitted for any of the repeated missing-tool calls.
                    assertEquals("thread.completed", readJson(socket)["type"].asText())
                }
            }
        }
    }

    @Test
    fun `embedded Telegram discovers Orion and calls its tools without providing them itself`() {
        val telegram = FakeTelegramBotApi()
        backendE2eTest("e2e_bot_orion", telegramApi = telegram,
            featureFlags = BackendFeatureFlags(wsEvents = true, telegramBot = true)) {
            val owner = UUID.randomUUID().toString()
            val bot = createPublicChat(owner, "bot")
            val orion = createPublicChat(owner, "orion") // No creation declarations needed for discovery.
            val link = client.put(BackendHttpRoutes.chatTelegramBot(bot)) {
                trusted(owner); jsonBody("""{"token":"123456:orion-integration"}""")
            }.jsonBody()["pendingLinkCommand"].asText()
            fun update(id: Long, text: String) = TelegramUpdate(id, TelegramMessage(id, TelegramUser(701, firstName = "Owner"),
                TelegramChat(701, "private"), text))
            telegram.enqueue(update(1, link))
            backend.pollTelegramOnce()
            val prompt = "Play Linkin Park on Aura"
            llm.requestSkillForPrompt(prompt, "orion.tools.list", mapOf("channelId" to orion),
                "orion.call" to mapOf("channelId" to orion, "utterance" to prompt),
                "web.search" to mapOf("query" to "Linkin Park"))
            withMultiChatSocket { socket ->
                socket.send(Frame.Text("""{"kind":"chat.subscribe","chatId":"$orion","requestId":"watch"}"""))
                assertEquals("accepted", readJson(socket)["status"].asText())
                telegram.enqueue(update(2, prompt))
                coroutineScope {
                    val botTurn = async { backend.pollTelegramOnce() }
                    for ((name, result) in listOf(
                        "orion.tools.list" to """{"tools":${clientToolsJson("orion.call", "web.search", "prompt.personality.set")}}""",
                        "orion.call" to """{"reply":"Playing"}""",
                        "web.search" to """{"documents":[]}""",
                    )) {
                        val call = readJson(socket)
                        assertEquals("tool.call.started", call["type"].asText())
                        assertEquals(orion, call["chatId"].asText())
                        assertTrue(call["seq"].isNull)
                        assertEquals(name, call["payload"]["name"].asText())
                        assertFalse(call["payload"]["arguments"].has("channelId"))
                        socket.send(Frame.Text("""{"kind":"tool.result","chatId":"$orion","threadId":${call["threadId"]},"toolCallId":${call["payload"]["toolCallId"]},"status":"succeeded","result":$result}"""))
                        assertEquals("accepted", readJson(socket)["status"].asText())
                    }
                    botTurn.await()
                }
                eventually("Telegram receives the caller's final response") {
                    telegram.sentMessages.firstOrNull { it.chatId == 701L && it.text == "client tool completed" }
                }
                val discovery = restJsonMapper.readTree(llm.requests.last().messages.first { it.name == "RunSkillCommand" }.content)
                assertEquals(setOf("orion.call", "web.search"), discovery["tools"].map { it["name"].asText() }.toSet())
            }
        }
    }

    @Test
    fun `selected child adapter rejects a new offline provider conflict before dispatch`() {
        val childSelected = CompletableDeferred<Unit>()
        val continueChild = CompletableDeferred<Unit>()
        var provider = ""
        val llm = E2eLlmApi { request ->
            val result = request.messages.lastOrNull { it.name == "RunSkillCommand" || it.name == "SpawnSubagent" }
            when {
                request.functions.map { it.name } == listOf("RunSkillCommand") -> {
                    assertTrue("Current conversation channelId: $provider." in request.messages.first().content)
                    childSelected.complete(Unit)
                    continueChild.await()
                    if (result == null) toolCallReply(request, "RunSkillCommand", mapOf("skillId" to "speaker.status",
                        "arguments" to mapOf("channelId" to provider)))
                    else {
                        assertEquals("client_tool_unavailable", restJsonMapper.readTree(result.content)["error"]["code"].asText())
                        reply(request, "Provider definition changed")
                    }
                }
                result != null -> reply(request, "Cannot use the changed tool")
                else -> toolCallReply(request, "SpawnSubagent", mapOf("task" to "Read status on $provider",
                    "skillIds" to listOf("speaker.status")))
            }
        }
        backendE2eTest("e2e_child_catalogue_change", llm = llm) {
            val owner = UUID.randomUUID().toString()
            provider = createPublicChat(owner, tools = clientToolsJson("speaker.status"))
            withMultiChatSocket { socket ->
                socket.send(Frame.Text(messageFrame(provider, owner, "delegate", text = "Read status on this speaker")))
                assertEquals("accepted", readJson(socket)["status"].asText())
                assertEquals("thread.status", readJson(socket)["type"].asText())
                coroutineScope {
                    val terminal = async { readJson(socket) }
                    withTimeout(10_000) { childSelected.await() }
                    try {
                        createPublicChat(owner, "conflict", clientToolsJson("speaker.status").replace("Invoke speaker.status", "Different status"))
                    } finally {
                        continueChild.complete(Unit)
                    }
                    // Any command dispatched with the stale child adapter would precede this terminal event.
                    assertEquals("thread.completed", terminal.await()["type"].asText())
                }
            }
        }
    }
}
