package ru.souz.backend.e2e

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlin.test.assertTrue
import ru.souz.backend.config.BackendFeatureFlags
import kotlin.test.Test
import kotlin.test.assertEquals
import ru.souz.agent.spi.TaskComplexityClassifier
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.llm.withNamedLlmConfig
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.llms.restJsonMapper

class BackendModelRoutingE2eTest {
    @Test
    fun `default-model turns run on the routed model, fall back when it fails and keep chosen models`() = withNamedLlmConfig(ROUTING_CONFIG) { source ->
        val children = CopyOnWriteArrayList<String>()
        val http = HttpClient(MockEngine { request ->
            val body = restJsonMapper.readTree(request.body.toByteArray())
            val model = body["model"].asText()
            val last = body["messages"].last()["content"].asText()
            if (model == "lite-model") assertTrue(body.path("tools").isMissingNode, "Simple provider request must omit tools")
            if (last == "Fail over" && model == "strong-model") return@MockEngine respond("overloaded", HttpStatusCode.ServiceUnavailable)
            if (last == "child task") children += model
            // A parent asked to delegate spawns a child without choosing its model.
            val delegate = last.startsWith("Delegate") && body.path("tools").any { it["function"]["name"].asText() == "SpawnSubagent" }
            val message = if (delegate) {
                """{"role":"assistant","tool_calls":[{"index":0,"id":"spawn","type":"function",""" +
                    """"function":{"name":"SpawnSubagent","arguments":"{\"task\":\"child task\"}"}}]}"""
            } else {
                """{"role":"assistant","content":"$model answer"}"""
            }
            respond(
                """{"choices":[{"index":0,"message":$message,"finish_reason":"${if (delegate) "tool_calls" else "stop"}"}],"created":1}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) { providerHttpClientDefaults() }
        val classified = AtomicInteger()
        var verdict: () -> Boolean = { false }
        backendE2eTest(
            "e2e_model_routing", settingsSource = source, mockProvider = false,
            providerClients = ProviderHttpClients(standard = http, openAi = http),
            complexityClassifier = TaskComplexityClassifier { _, _ -> classified.incrementAndGet(); verdict() },
        ) {
            val userId = UUID.randomUUID().toString()
            client.post(BackendHttpRoutes.ONBOARDING_COMPLETE) { trusted(userId); jsonBody("{}") }

            /** Answer text and the model persisted on the execution. */
            suspend fun turn(content: String, options: String = ""): Pair<String, String?> {
                val chatId = createPublicChat(userId, requestId = content)
                client.post(BackendHttpRoutes.chatMessages(chatId)) { trusted(userId); jsonBody("""{"content":"$content"$options}""") }
                val answer = eventually("$content answered") {
                    client.get(BackendHttpRoutes.chatMessages(chatId)) { trusted(userId) }.jsonBody()["items"]
                        .last()["content"].asText().takeIf { it.endsWith(" answer") }
                }
                return answer to backend.executionRepository.listByChat(userId, UUID.fromString(chatId)).single().model
            }

            assertEquals("lite-model answer" to "route-lite", turn("Delegate a simple task"))
            verdict = { true }
            assertEquals("strong-model answer" to "route-strong", turn("Delegate a complex task"))
            // The first configured model is the only accessible entry of the default-model order.
            assertEquals("default-model answer" to "route-strong", turn("Fail over"))
            verdict = { error("Jev unavailable") }
            assertEquals("default-model answer" to "default-chat", turn("Anything"))
            assertEquals(listOf("strong-model"), children)

            assertEquals("default-model answer" to "default-chat", turn("Explicit", ""","options":{"model":"default-chat"}"""))
            client.patch(BackendHttpRoutes.SETTINGS) { trusted(userId); jsonBody("""{"defaultModel":"route-strong"}""") }
            assertEquals("strong-model answer" to "route-strong", turn("Chosen"))
            assertEquals(4, classified.get())
        }
    }

    @Test
    fun `initial wait is live only and independent of narration`() = withNamedLlmConfig("""{
        "routing":{"simple":{"model":"${E2E_MODEL.alias}"},"complex":{"model":"${E2E_MODEL.alias}"}}
    }""") { source ->
        var decision: Boolean? = null
        backendE2eTest("e2e_initial_wait", settingsSource = source, featureFlags = BackendFeatureFlags(wsEvents = true),
            complexityClassifier = TaskComplexityClassifier { _, _ -> decision ?: error("Jev unavailable") }) {
            val user = UUID.randomUUID().toString()
            val decisions = listOf(false, true, null)
            for (narrate in listOf(false, true)) for ((index, next) in decisions.withIndex()) {
                decision = next
                val prompt = "turn-$narrate-$index"
                val chat = createPublicChat(user, requestId = prompt)
                client.patch(BackendHttpRoutes.SETTINGS) { trusted(user); jsonBody("""{"narrateSteps":$narrate}""") }
                llm.pausePromptUntilReleased(prompt)
                try {
                    withPublicSocket(chat) { socket ->
                        val frame = messageFrame(chat, user, prompt, text = prompt).replace("\"model\": \"${E2E_MODEL.alias}\",", "")
                        socket.send(Frame.Text(frame))
                        assertEquals("accepted", readJson(socket, includeProgress = true)["status"].asText())
                        assertEquals("thread.status", readJson(socket, includeProgress = true)["type"].asText())
                        if (next != false) {
                            val wait = readJson(socket, includeProgress = true)
                            assertEquals("assistant.message", wait["type"].asText())
                            assertTrue(wait["seq"].isNull)
                            assertTrue(wait["payload"]["content"].asText().isNotBlank())
                        }
                        llm.awaitPrompt(prompt)
                        socket.send(Frame.Text(frame))
                        assertEquals("accepted", readJson(socket, includeProgress = true)["status"].asText())
                        assertEquals("thread.status", readJson(socket, includeProgress = true)["type"].asText())
                        llm.releasePrompt(prompt)
                        assertEquals("thread.completed", readJson(socket, includeProgress = true)["type"].asText())
                    }
                    webSocketClient().use { client ->
                        val replay = client.webSocketSession("${BackendHttpRoutes.chatWebSocket(chat)}?clientType=backend&afterSeq=0")
                        try {
                            assertEquals("thread.completed", readJson(replay, includeProgress = true)["type"].asText())
                        } finally { replay.close() }
                    }
                    val messages = client.get(BackendHttpRoutes.chatMessages(chat)) { trusted(user) }.jsonBody()["items"]
                    assertEquals(listOf(prompt, "assistant reply to $prompt"), messages.map { it["content"].asText() })
                } finally {
                    llm.releasePrompt(prompt)
                }
            }
        }
    }

    @Test
    fun `configured builtin reasoning reaches provider and explicit turn effort wins`() = withNamedLlmConfig(
        """{"models":{"gpt-5-mini":{"parameters":{"reasoning_effort":"low"}}}}""",
        environment = mapOf("OPENAI_API_KEY" to "test-openai-key", "GIGA_MODEL" to "gpt-5-mini"),
    ) { source ->
        val efforts = CopyOnWriteArrayList<String>()
        val http = HttpClient(MockEngine { request ->
            val body = restJsonMapper.readTree(request.body.toByteArray())
            assertEquals("gpt-5-mini", body["model"].asText())
            efforts += body["reasoning_effort"].asText()
            respond("""{"choices":[{"index":0,"message":{"role":"assistant","content":"done"},"finish_reason":"stop"}],"created":1}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { providerHttpClientDefaults() }
        backendE2eTest("e2e_builtin_effort", settingsSource = source, mockProvider = false,
            providerClients = ProviderHttpClients(standard = http, openAi = http)) {
            val user = UUID.randomUUID().toString()
            client.post(BackendHttpRoutes.ONBOARDING_COMPLETE) { trusted(user); jsonBody("{}") }
            for ((index, options) in listOf("", ""","options":{"reasoningEffort":"high"}""").withIndex()) {
                val chat = createPublicChat(user, requestId = "effort-$index")
                client.post(BackendHttpRoutes.chatMessages(chat)) {
                    trusted(user); jsonBody("""{"content":"Hello $index"$options}""")
                }
                eventually("effort turn completed") {
                    client.get(BackendHttpRoutes.chatMessages(chat)) { trusted(user) }.jsonBody()["items"]
                        .last()["content"].asText().takeIf { it == "done" }
                }
            }
            assertEquals(listOf("low", "high"), efforts)
        }
    }

    private companion object {
        val ROUTING_CONFIG = """{
          "openaiConnections": {"router": {"baseUrl":"https://router.test/v1", "apiKeyEnv":"ROUTER_KEY"}},
          "models": {
            "default-chat": {"connection":"router", "modelId":"default-model"},
            "route-lite": {"connection":"router", "modelId":"lite-model"},
            "route-strong": {"connection":"router", "modelId":"strong-model"}
          },
          "routing": {"simple": {"model":"route-lite"}, "complex": {"model":"route-strong"}}
        }"""
    }
}
