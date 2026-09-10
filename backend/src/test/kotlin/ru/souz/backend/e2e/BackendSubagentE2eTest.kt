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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.config.BackendConfigSource
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.restJsonMapper
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.http.providerHttpClientDefaults

class BackendSubagentE2eTest {
    @Test
    fun `configured remote child runs from a local parent with credentials and usage in both modes`() {
        listOf(false, true).forEach { streaming ->
            val childModel = "My-Deployment/v2"
            var childRequests = 0
            val http = HttpClient(MockEngine { request ->
                val body = restJsonMapper.readTree(request.body.toByteArray())
                assertEquals(childModel, body["model"].asText())
                assertEquals(streaming, body["stream"].asBoolean())
                assertEquals("Bearer configured-child-key", request.headers[HttpHeaders.Authorization])
                assertEquals("api.openai.com", request.url.host)
                assertFalse(body.has("provider"))
                childRequests++
                val reply = """{"choices":[{"index":0,"message":{"role":"assistant","content":"private remote answer"},"delta":{"role":"assistant","content":"private remote answer"},"finish_reason":"stop"}],"created":1,"usage":{"prompt_tokens":11,"completion_tokens":4,"total_tokens":15}}"""
                respond(
                    if (streaming) "data: $reply\n\ndata: [DONE]\n\n" else reply,
                    headers = headersOf(HttpHeaders.ContentType, if (streaming) "text/event-stream" else "application/json"),
                )
            }) { providerHttpClientDefaults() }
            val source = object : BackendConfigSource {
                override fun env(key: String): String? = when (key) {
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
                    val result = request.messages.lastOrNull { it.name == "SpawnSubagent" }
                    if (result != null) {
                        assertEquals("private remote answer", restJsonMapper.readTree(result.content)["result"]?.asText(), result.content)
                        reply(request, "parent completed")
                    } else {
                        val choices = request.functions.single { it.name == "SpawnSubagent" }.parameters.properties.getValue("model").enum
                        assertEquals(setOf(childModel, E2E_LOCAL_MODEL.alias), choices?.toSet())
                        toolCallReply(request, "SpawnSubagent", mapOf("task" to "Answer privately", "model" to childModel))
                    }
                },
            ) {
                val userId = UUID.randomUUID().toString()
                val chatId = createPublicChat(userId)
                client.patch(BackendHttpRoutes.SETTINGS) {
                    trusted(userId)
                    jsonBody("""{"defaultModel":"${E2E_LOCAL_MODEL.alias}","streamingMessages":$streaming}""")
                }
                client.post(BackendHttpRoutes.chatMessages(chatId)) { trusted(userId); jsonBody("""{"content":"Delegate"}""") }
                val events = eventually("cross-provider completion") {
                    client.get(BackendHttpRoutes.chatEvents(chatId)) { trusted(userId) }.jsonBody()["items"]
                        .takeIf { items -> items.any { it["type"].asText() == "execution.finished" } }
                }
                assertEquals(1, childRequests)
                assertEquals(2, llm.requests.size)
                val usage = events.single { it["type"].asText() == "execution.finished" }["payload"]
                assertEquals(35, usage["totalTokens"].asInt())
                val messages = client.get(BackendHttpRoutes.chatMessages(chatId)) { trusted(userId) }.jsonBody()["items"]
                assertEquals(listOf("Delegate", "parent completed"), messages.map { it["content"].asText() })
            }
        }
    }

    @Test
    fun `child client tool keeps routing while only parent final reaches public history`() =
        backendE2eTest(
            schemaPrefix = "e2e_subagent_client",
            featureFlags = BackendFeatureFlags(wsEvents = true, streamingMessages = true, toolEvents = true),
            llm = E2eLlmApi { request ->
                val result = request.messages.lastOrNull { it.role == LLMMessageRole.function }
                when {
                    request.functions.map { it.name } == listOf("user.ask") ->
                        if (result == null) toolCallReply(request, "user.ask", mapOf("question" to "Which genre?"))
                        else reply(request, "private child answer: Horror")

                    result?.name == "SpawnSubagent" -> reply(request, "parent final answer")
                    else -> toolCallReply(request, "SpawnSubagent", mapOf(
                        "task" to "Ask the user for a genre and report it.",
                        "skillIds" to listOf("user.ask"),
                    ))
                }
            },
        ) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val settings = client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(userId)
                jsonBody("""{"streamingMessages":true}""")
            }
            assertEquals(HttpStatusCode.OK, settings.status)

            withPublicSocket(chatId) { session ->
                session.send(Frame.Text(messageFrame(chatId, userId, "delegate", text = "delegate the question", deviceId = "child-device")))
                val ack = readJson(session)
                assertEquals("accepted", ack["status"].asText())
                assertEquals("thread.status", readJson(session)["type"].asText())
                val started = readJson(session)
                val threadId = ack["thread"]["id"].asText()
                val payload = started["payload"]
                assertEquals("tool.call.started", started["type"].asText())
                assertEquals(chatId, started["chatId"].asText())
                assertEquals(threadId, started["threadId"].asText())
                assertEquals("user.ask", payload["name"].asText())
                assertEquals("child-device", payload["deviceId"].asText())
                assertEquals("Which genre?", payload["arguments"]["question"].asText())

                session.send(Frame.Text(
                    """{"kind":"tool.result","chatId":"$chatId","threadId":"$threadId","toolCallId":${payload["toolCallId"]},"status":"succeeded","result":{"answer":"Horror"}}"""
                ))
                assertEquals("accepted", readJson(session)["status"].asText())
                val terminal = readJson(session)
                assertEquals("thread.completed", terminal["type"].asText())
                assertEquals(threadId, terminal["threadId"].asText())
            }

            assertEquals(4, llm.requests.size)
            val childRequests = llm.requests.filter { it.functions.map { tool -> tool.name } == listOf("user.ask") }
            assertEquals(2, childRequests.size)
            assertEquals(2, childRequests.first().messages.size)
            assertFalse(childRequests.first().messages.any { "delegate the question" in it.content || "<skill_inventory>" in it.content })
            val parentResult = llm.requests.last().messages.single { it.name == "SpawnSubagent" }
            assertEquals("private child answer: Horror", json.readTree(parentResult.content)["result"].asText())
            assertTrue("private child answer: Horror" in llm.streamedChunks)

            val messages = client.get(BackendHttpRoutes.chatMessages(chatId)) { trusted(userId) }.jsonBody()["items"]
            assertEquals(listOf("user", "assistant"), messages.map { it["role"].asText() })
            assertEquals("parent final answer", messages.last()["content"].asText())
            val events = client.get(BackendHttpRoutes.chatEvents(chatId)) { trusted(userId) }.jsonBody()["items"]
            assertEquals(listOf("tool.call.started", "thread.completed"), events.map { it["type"].asText() })
            assertEquals("parent final answer", events.last()["payload"]["response"].asText())
        }

    @Test
    fun `HTTP execution accounts for child usage and stores only the parent response`() =
        backendE2eTest(
            schemaPrefix = "e2e_subagent_usage",
            featureFlags = BackendFeatureFlags(wsEvents = true, streamingMessages = true, toolEvents = true),
            llm = E2eLlmApi { request ->
                when {
                    request.functions.isEmpty() -> reply(request, "private child text")
                    request.messages.any { it.name == "SpawnSubagent" } -> reply(request, "parent synthesized answer")
                    else -> toolCallReply(request, "SpawnSubagent", mapOf("task" to "Produce a private child answer."))
                }
            },
        ) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(userId)
                jsonBody("""{"defaultModel":"${E2E_LOCAL_MODEL.alias}","streamingMessages":true}""")
            }
            val sent = client.post(BackendHttpRoutes.chatMessages(chatId)) {
                trusted(userId)
                jsonBody("""{"content":"Delegate and synthesize"}""")
            }
            assertEquals(HttpStatusCode.OK, sent.status)
            val events = eventually("parent completion with child usage") {
                client.get(BackendHttpRoutes.chatEvents(chatId)) { trusted(userId) }.jsonBody()["items"].takeIf { items ->
                    items.any { it["type"].asText() == "execution.finished" }
                }
            }
            assertEquals(3, llm.requests.size)
            assertTrue("private child text" in llm.streamedChunks)
            val finished = events.single { it["type"].asText() == "execution.finished" }["payload"]
            assertEquals(21, finished["promptTokens"].asInt())
            assertEquals(9, finished["completionTokens"].asInt())
            assertEquals(30, finished["totalTokens"].asInt())
            val messages = client.get(BackendHttpRoutes.chatMessages(chatId)) { trusted(userId) }.jsonBody()["items"]
            assertEquals(listOf("user", "assistant"), messages.map { it["role"].asText() })
            assertEquals("parent synthesized answer", messages.last()["content"].asText())
            val completed = events.single { it["type"].asText() == "message.completed" }
            assertEquals("parent synthesized answer", completed["payload"]["content"].asText())
        }

    @Test
    fun `child selection obeys client search exclusion and returns failure to parent`() =
        backendE2eTest("e2e_subagent_policy", llm = E2eLlmApi { request ->
            if (request.messages.any { it.name == "SpawnSubagent" }) reply(request, "parent recovered")
            else toolCallReply(request, "SpawnSubagent", mapOf(
                "task" to "Search the internet.",
                "skillIds" to listOf("InternetSearch"),
            ))
        }) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val settings = client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(userId)
                jsonBody("""{"enabledTools":["InternetSearch"]}""")
            }
            assertEquals(HttpStatusCode.OK, settings.status)
            withPublicSocket(chatId) { session ->
                session.send(Frame.Text(messageFrame(chatId, userId, "delegate-search")))
                assertEquals("accepted", readJson(session)["status"].asText())
                assertEquals("thread.status", readJson(session)["type"].asText())
                assertEquals("thread.completed", readJson(session)["type"].asText())
            }

            assertEquals(2, llm.requests.size)
            val inventory = llm.requests.first().messages.first().content
            assertTrue("web.search" in inventory)
            assertFalse("InternetSearch" in inventory)
            val toolResult = llm.requests.last().messages.single { it.name == "SpawnSubagent" }
            assertEquals("skill_not_found", json.readTree(toolResult.content)["error"]["code"].asText())
            val messages = client.get(BackendHttpRoutes.chatMessages(chatId)) { trusted(userId) }.jsonBody()["items"]
            assertEquals("parent recovered", messages.last()["content"].asText())
        }
}
