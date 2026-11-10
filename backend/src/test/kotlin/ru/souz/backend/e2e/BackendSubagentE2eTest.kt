package ru.souz.backend.e2e

import io.ktor.client.request.get
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.websocket.Frame
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.config.BackendConfigSource
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.llm.withNamedLlmConfig
import ru.souz.backend.memory.hindsight.MEMORY_MUTATION_NOTICE
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.restJsonMapper
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.http.providerHttpClientDefaults
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import ru.souz.backend.attachments.AttachmentMinio
import ru.souz.llms.FileContent

class BackendSubagentE2eTest {
    @Test
    fun `configured child deadline expires through the production kernel and parent can finish`() {
        val stopped = CompletableDeferred<Unit>()
        backendE2eTest(
            schemaPrefix = "e2e_subagent_timeout",
            settingsSource = object : BackendConfigSource {
                override fun env(key: String): String? = if (key == "SUBAGENT_TASK_TIMEOUT_MS") "1000" else null
                override fun property(key: String): String? = null
            },
            llm = E2eLlmApi { request ->
                if (request.functions.isEmpty()) {
                    try { awaitCancellation() } finally { stopped.complete(Unit) }
                } else {
                    val result = request.messages.lastOrNull { it.role == LLMMessageRole.function }
                    when (result?.name) {
                        "SpawnSubagent" -> waitForChild(request, result.content)
                        "WaitSubagent" -> {
                            val outcome = restJsonMapper.readTree(result.content)
                            assertEquals("failed", outcome["status"].asText())
                            assertEquals("subagent_timeout", outcome["error"]["code"].asText())
                            assertTrue("1s" in outcome["error"]["message"].asText())
                            assertTrue(outcome["sideEffectsMayHaveOccurred"].asBoolean())
                            assertTrue(stopped.isCompleted)
                            reply(request, "parent recovered from child timeout")
                        }
                        else -> toolCallReply(request, "SpawnSubagent", mapOf("task" to "Wait indefinitely"))
                    }
                }
            },
        ) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            assertEquals(HttpStatusCode.OK, client.post(BackendHttpRoutes.chatMessages(chatId)) {
                trusted(userId); jsonBody("""{"content":"Delegate a slow task"}""")
            }.status)
            eventually("parent recovery from configured child timeout") {
                client.get(BackendHttpRoutes.chatMessages(chatId)) { trusted(userId) }.jsonBody()["items"]
                    .takeIf { it.last()["content"].asText() == "parent recovered from child timeout" }
            }
            assertTrue(stopped.isCompleted)
        }
    }

    @Test
    fun `parent and children share a channel with independent calls and selective cancellation`() {
        val imageBase64 = Base64.getEncoder().encodeToString(ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray())
        for (cancelFirst in listOf(false, true)) {
            val bothStarted = CompletableDeferred<Unit>()
            var step = 0
            var childFile: FileContent? = null
            var firstId = ""
            var secondId = ""
            val survivor = if (cancelFirst) "second" else "first"
            backendE2eTest(
                schemaPrefix = "e2e_subagent_parallel",
                attachments = AttachmentMinio.config(),
                llm = E2eLlmApi { request ->
                    val result = request.messages.lastOrNull { it.role == LLMMessageRole.function }
                    if (request.functions.map { it.name } == listOf("RunSkillCommand")) {
                        val child = request.conversationPrompt()
                        if (result == null) {
                            toolCallReply(request, "RunSkillCommand", mapOf(
                                "skillId" to "user.ask", "arguments" to mapOf("question" to child),
                            ))
                        } else {
                            assertEquals(child, restJsonMapper.readTree(result.content)["answer"].asText())
                            childFile = result.files.single()
                            assertTrue(result.replyFiles.isEmpty())
                            reply(request, "$child done")
                        }
                    } else when (step++) {
                        0 -> reply(request, "").copy(choices = listOf("first", "second").mapIndexed { index, task ->
                            val choice = toolCallReply(request, "SpawnSubagent", mapOf(
                                "task" to task, "skillIds" to listOf("user.ask"),
                            )).choices.single()
                            choice.copy(index = index, message = choice.message.copy(functionsStateId = "spawn-$index"))
                        })
                        1 -> {
                            val ids = request.messages.filter { it.name == "SpawnSubagent" }
                                .map { restJsonMapper.readTree(it.content)["subagentId"].asText() }
                            assertEquals(2, ids.distinct().size)
                            firstId = ids[0]
                            secondId = ids[1]
                            bothStarted.await()
                            toolCallReply(request, "CancelSubagent", mapOf("subagentId" to if (cancelFirst) firstId else secondId))
                        }
                        2 -> {
                            val status = restJsonMapper.readTree(result!!.content)["status"].asText()
                            assertTrue(status == "cancelling" || status == "cancelled")
                            toolCallReply(request, "WaitSubagent", mapOf("subagentId" to if (cancelFirst) firstId else secondId))
                        }
                        3 -> {
                            assertEquals("cancelled", restJsonMapper.readTree(result!!.content)["status"].asText())
                            toolCallReply(request, "RunSkillCommand", mapOf("skillId" to "user.ask", "arguments" to mapOf("question" to "parent")))
                        }
                        4 -> {
                            assertEquals("parent", restJsonMapper.readTree(result!!.content)["answer"].asText())
                            toolCallReply(request, "WaitSubagent", mapOf("subagentId" to if (cancelFirst) secondId else firstId))
                        }
                        else -> {
                            val done = restJsonMapper.readTree(result!!.content)
                            assertEquals("completed", done["status"].asText())
                            assertEquals("$survivor done", done["result"].asText())
                            assertTrue(childFile != null)
                            assertTrue(result.files.isEmpty(), "Unselected child screenshots are not reply attachments")
                            assertTrue(result.replyFiles.isEmpty())
                            reply(request, "parent and $survivor finished")
                        }
                    }
                },
            ) {
                val owner = UUID.randomUUID().toString()
                val chat = createPublicChat(owner, tools = clientToolsJson("user.ask"))
                withPublicSocket(chat) { session ->
                    session.send(Frame.Text(messageFrame(chat, owner, "delegate", text = "delegate two questions")))
                    val ack = readJson(session)
                    assertEquals("accepted", ack["status"].asText())
                    val thread = ack["thread"]["id"].asText()
                    assertEquals("thread.status", readJson(session)["type"].asText())
                    // Both device commands must arrive before either receives a result.
                    val calls = List(2) {
                        val call = readJson(session)
                        assertEquals("tool.call.started", call["type"].asText())
                        assertEquals(thread, call["threadId"].asText())
                        assertEquals("user.ask", call["payload"]["name"].asText())
                        assertTrue(call["seq"].isNull)
                        call
                    }.associateBy { it["payload"]["arguments"]["question"].asText() }
                    assertEquals(setOf("first", "second"), calls.keys)
                    bothStarted.complete(Unit)
                    val parent = readJson(session)
                    assertEquals("tool.call.started", parent["type"].asText())
                    assertEquals("parent", parent["payload"]["arguments"]["question"].asText())
                    val cancelled = calls.getValue(if (cancelFirst) "first" else "second")
                    session.send(Frame.Text(toolResultFrame(cancelled, "succeeded", mapOf("answer" to "late"))))
                    val rejected = readJson(session)
                    assertEquals("rejected", rejected["status"].asText())
                    assertEquals("tool_call_not_found", rejected["error"]["code"].asText())
                    val remaining = calls.getValue(survivor)
                    for (call in if (cancelFirst) listOf(parent, remaining) else listOf(remaining, parent)) {
                        val question = call["payload"]["arguments"]["question"].asText()
                        val content = if (question == "parent") emptyList() else listOf(mapOf(
                            "type" to "image", "mimeType" to "image/png", "data" to imageBase64,
                        ))
                        session.send(Frame.Text(toolResultFrame(call, "succeeded", mapOf("answer" to question, "content" to content))))
                        assertEquals("accepted", readJson(session)["status"].asText())
                    }
                    val terminal = readJson(session)
                    assertEquals("thread.completed", terminal["type"].asText())
                    assertEquals("parent and $survivor finished", terminal["payload"]["response"].asText())
                }
            }
        }
    }

    @Test
    fun `legacy and named remote children preserve credentials and usage`() = withNamedLlmConfig { namedSource ->
        listOf(false, true).forEach { streaming ->
            val childModel = if (streaming) "tv-agent" else "My-Deployment/v2"
            var childRequests = 0
            val http = HttpClient(MockEngine { request ->
                val body = restJsonMapper.readTree(request.body.toByteArray())
                assertEquals(if (streaming) "shared-model" else childModel, body["model"].asText())
                assertEquals(streaming, body["stream"].asBoolean())
                assertEquals(if (streaming) "Bearer router-secret" else "Bearer configured-child-key", request.headers[HttpHeaders.Authorization])
                assertEquals(if (streaming) "router.test" else "api.openai.com", request.url.host)
                assertFalse(body.has("provider"))
                assertEquals(listOf("RunSkillCommand"), body["tools"].map { it["function"]["name"].asText() })
                assertTrue("speaker.say" in body["messages"][0]["content"].asText())
                childRequests++
                val reply = """{"choices":[{"index":0,"message":{"role":"assistant","content":"private remote answer"},"delta":{"role":"assistant","content":"private remote answer"},"finish_reason":"stop"}],"created":1,"usage":{"prompt_tokens":11,"completion_tokens":4,"total_tokens":15}}"""
                respond(
                    if (streaming) "data: $reply\n\ndata: [DONE]\n\n" else reply,
                    headers = headersOf(HttpHeaders.ContentType, if (streaming) "text/event-stream" else "application/json"),
                )
            }) { providerHttpClientDefaults() }
            val source = object : BackendConfigSource {
                override fun env(key: String): String? = if (streaming) namedSource.env(key) else when (key) {
                    "SUBAGENT_MODELS_JSON" -> """{"OPENAI":["$childModel"]}"""
                    "OPENAI_API_KEY" -> "configured-child-key"
                    else -> null
                }
                override fun property(key: String): String? = null
            }
            backendE2eTest(
                schemaPrefix = "e2e_cross_provider",
                featureFlags = BackendFeatureFlags(wsEvents = true, streamingMessages = true),
                settingsSource = source,
                providerClients = ProviderHttpClients(standard = http, openAi = http),
                llm = E2eLlmApi { request ->
                    val result = request.messages.lastOrNull { it.role == LLMMessageRole.function }
                    if (result?.name == "SpawnSubagent") waitForChild(request, result.content)
                    else if (result != null) {
                        assertEquals("private remote answer", restJsonMapper.readTree(result.content)["result"]?.asText(), result.content)
                        reply(request, "parent completed")
                    } else {
                        val choices = request.functions.single { it.name == "SpawnSubagent" }.parameters.properties.getValue("model").enum
                        assertEquals(setOf(childModel, E2E_MODEL.alias), choices?.toSet())
                        toolCallReply(request, "SpawnSubagent", mapOf("task" to "Answer privately", "model" to childModel, "skillIds" to listOf("speaker.say")))
                    }
                },
            ) {
                val userId = UUID.randomUUID().toString()
                val chatId = createPublicChat(userId, tools = clientToolsJson("speaker.say"))
                client.patch(BackendHttpRoutes.SETTINGS) {
                    trusted(userId)
                    jsonBody("""{"defaultModel":"${E2E_MODEL.alias}","streamingMessages":$streaming}""")
                }
                client.post(BackendHttpRoutes.chatMessages(chatId)) { trusted(userId); jsonBody("""{"content":"Delegate"}""") }
                val events = eventually("cross-provider completion") {
                    client.get(BackendHttpRoutes.chatEvents(chatId)) { trusted(userId) }.jsonBody()["items"]
                        .takeIf { items -> items.any { it["type"].asText() == "execution.finished" } }
                }
                assertEquals(1, childRequests)
                assertEquals(3, llm.requests.size)
                val usage = events.single { it["type"].asText() == "execution.finished" }["payload"]
                assertEquals(32, usage["promptTokens"].asInt())
                assertEquals(13, usage["completionTokens"].asInt())
                assertEquals(45, usage["totalTokens"].asInt())
                val messages = client.get(BackendHttpRoutes.chatMessages(chatId)) { trusted(userId) }.jsonBody()["items"]
                assertEquals(listOf("user", "assistant"), messages.map { it["role"].asText() })
                assertEquals(listOf("Delegate", "parent completed"), messages.map { it["content"].asText() })
                assertEquals("parent completed", events.single { it["type"].asText() == "message.completed" }["payload"]["content"].asText())
            }
        }
    }

    @Test
    fun `both sockets deliver parent progress before child tools without replay or child text`() {
        val releaseChild = CompletableDeferred<Unit>()
        val blocks = listOf("Let me check.", "Another detail.", "Let me check.")
        val task = "Ask the user for a genre and report it."
        backendE2eTest(
            schemaPrefix = "e2e_subagent_client",
            featureFlags = BackendFeatureFlags(wsEvents = true, streamingMessages = true, toolEvents = true),
            llm = E2eLlmApi { request ->
                val result = request.messages.lastOrNull { it.role == LLMMessageRole.function }
                when {
                    request.functions.map { it.name } == listOf("RunSkillCommand") -> {
                        releaseChild.await()
                        if (result != null) reply(request, "private child answer: Horror")
                        else toolCallReply(request, "RunSkillCommand", mapOf("skillId" to "user.ask", "arguments" to mapOf("question" to "Which genre?"))).let { response ->
                            response.copy(choices = response.choices.map { it.copy(message = it.message.copy(content = "private child progress")) })
                        }
                    }
                    result?.name == "SpawnSubagent" -> waitForChild(request, result.content)
                    result?.name == "WaitSubagent" -> reply(request, "parent final answer")
                    else -> toolCallReply(request, "SpawnSubagent", mapOf("task" to task, "skillIds" to listOf("user.ask")), blocks)
                }
            },
        ) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId, tools = clientToolsJson("user.ask"))
            client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(userId)
                jsonBody("""{"streamingMessages":true,"narrateSteps":true,"systemPrompt":"custom base"}""")
            }
            withPublicSocket(chatId) { session -> withMultiChatSocket { observer ->
                try {
                    observer.send(Frame.Text("""{"kind":"chat.subscribe","chatId":"$chatId","requestId":"watch"}"""))
                    assertEquals("accepted", readJson(observer, includeProgress = true)["status"].asText())
                    session.send(Frame.Text(messageFrame(chatId, userId, "delegate", text = "delegate the question", deviceId = "child-device")))
                    val ack = readJson(session, includeProgress = true)
                    assertEquals("accepted", ack["status"].asText())
                    assertEquals("thread.status", readJson(session, includeProgress = true)["type"].asText())
                    val threadId = ack["thread"]["id"].asText()
                    // The tool has started before either subscriber reads or acknowledges progress.
                    llm.awaitPrompt(task)
                    for (socket in listOf(session, observer)) for ((index, content) in blocks.withIndex()) {
                        var event = readJson(socket, includeProgress = true)
                        // A waiting phrase may have won the race against the first narration block.
                        if (index == 0 && event["payload"]["content"].asText() != content) {
                            assertEquals("assistant.message", event["type"].asText())
                            assertTrue(event["payload"]["content"].asText().isNotBlank())
                            event = readJson(socket, includeProgress = true)
                        }
                        assertEquals("assistant.message", event["type"].asText())
                        assertEquals(chatId, event["chatId"].asText())
                        assertEquals(threadId, event["threadId"].asText())
                        assertTrue(event["seq"].isNull)
                        assertEquals(json.createObjectNode().put("content", content), event["payload"])
                    }
                    releaseChild.complete(Unit)
                    val started = readJson(session, includeProgress = true)
                    assertEquals(started, readJson(observer, includeProgress = true))
                    val payload = started["payload"]
                    assertEquals("tool.call.started", started["type"].asText())
                    assertTrue(started["seq"].isNull)
                    assertEquals(chatId, started["chatId"].asText())
                    assertEquals(threadId, started["threadId"].asText())
                    assertEquals("user.ask", payload["name"].asText())
                    assertEquals("child-device", payload["deviceId"].asText())
                    assertEquals("Which genre?", payload["arguments"]["question"].asText())
                    session.send(Frame.Text(
                        """{"kind":"tool.result","chatId":"$chatId","threadId":"$threadId","toolCallId":${payload["toolCallId"]},"status":"succeeded","result":{"answer":"Horror"}}"""
                    ))
                    assertEquals("accepted", readJson(session, includeProgress = true)["status"].asText())
                    val terminal = readJson(session, includeProgress = true)
                    assertEquals("thread.completed", terminal["type"].asText())
                    assertEquals(threadId, terminal["threadId"].asText())
                    assertEquals(terminal, readJson(observer, includeProgress = true))
                    withPublicSocket(chatId) { replay ->
                        assertEquals(terminal, readJson(replay, includeProgress = true))
                        replay.send(Frame.Text(historyFrame(chatId, "history", "user", "passive context")))
                        assertEquals("ack", readJson(replay, includeProgress = true)["kind"].asText())
                    }
                } finally {
                    releaseChild.complete(Unit)
                }
            } }
            assertEquals(5, llm.requests.size)
            val system = llm.requests.first().messages.first().content
            assertTrue("custom base" in system)
            assertTrue("Перед каждым вызовом инструментов" in system)
            assertTrue(MEMORY_MUTATION_NOTICE in system)
            val childRequests = llm.requests.filter { it.functions.map { tool -> tool.name } == listOf("RunSkillCommand") }
            assertEquals(2, childRequests.size)
            assertTrue("# Ask the user" in childRequests.first().messages.first().content)
            assertEquals(2, childRequests.first().messages.size)
            assertFalse(childRequests.first().messages.any { "delegate the question" in it.content || "<skill_inventory>" in it.content })
            val parentResult = llm.requests.last().messages.single { it.name == "WaitSubagent" }
            assertEquals("private child answer: Horror", json.readTree(parentResult.content)["result"].asText())
            assertTrue("private child progress" in llm.streamedChunks)
            assertTrue("private child answer: Horror" in llm.streamedChunks)
            val messages = client.get(BackendHttpRoutes.chatMessages(chatId)) { trusted(userId) }.jsonBody()["items"]
            assertEquals(listOf("delegate the question", "parent final answer", "passive context"), messages.map { it["content"].asText() })
            val events = client.get(BackendHttpRoutes.chatEvents(chatId)) { trusted(userId) }.jsonBody()["items"]
            assertEquals(listOf("thread.completed"), events.map { it["type"].asText() })
            assertEquals("parent final answer", events.last()["payload"]["response"].asText())
        }
    }

    @Test
    fun `proxy child selection excludes compiled web search and returns failure to parent`() =
        backendE2eTest("e2e_subagent_policy", llm = E2eLlmApi { request ->
            if (request.messages.any { it.name == "SpawnSubagent" }) reply(request, "parent recovered")
            else toolCallReply(request, "SpawnSubagent", mapOf(
                "task" to "Search the internet.",
                "skillIds" to listOf("InternetSearch"),
            ))
        }) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId, tools = clientToolsJson("web.search"))
            val settings = client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(userId)
                jsonBody("""{"defaultModel":"${E2E_MODEL.alias}","enabledTools":["InternetSearch"]}""")
            }
            assertEquals(HttpStatusCode.OK, settings.status)
            assertEquals(0, settings.jsonBody()["settings"]["enabledTools"].size())
            assertEquals(HttpStatusCode.OK, client.post(BackendHttpRoutes.chatMessages(chatId)) {
                trusted(userId); jsonBody("""{"content":"Delegate search"}""")
            }.status)
            val messages = eventually("parent recovery from unavailable server search") {
                client.get(BackendHttpRoutes.chatMessages(chatId)) { trusted(userId) }.jsonBody()["items"]
                    .takeIf { it.last()["content"].asText() == "parent recovered" }
            }

            assertEquals(2, llm.requests.size)
            val inventory = llm.requests.first().messages.first().content
            assertTrue("- WEB_SEARCH: web.search" in inventory)
            assertFalse("InternetSearch" in inventory)
            val toolResult = llm.requests.last().messages.single { it.name == "SpawnSubagent" }
            assertEquals("skill_not_found", json.readTree(toolResult.content)["error"]["code"].asText())
            assertEquals("parent recovered", messages.last()["content"].asText())
        }
}

private fun waitForChild(request: ru.souz.llms.LLMRequest.Chat, spawnResult: String) = toolCallReply(request,
    "WaitSubagent", mapOf("subagentId" to restJsonMapper.readTree(spawnResult)["subagentId"].asText(), "timeoutMs" to 30000))
