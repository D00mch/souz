package ru.souz.backend.e2e

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.close
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.websocket.Frame
import kotlin.test.assertTrue
import ru.souz.backend.config.BackendFeatureFlags
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import ru.souz.agent.spi.TurnClassifier
import ru.souz.agent.spi.TurnClassification
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
            turnClassifier = TurnClassifier { classified.incrementAndGet(); TurnClassification(verdict(), false) },
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
            verdict = { error("Jev unavailable") }
            assertEquals("strong-model answer" to "route-strong", turn("Anything"))
            assertEquals(listOf("strong-model"), children)

            assertEquals("default-model answer" to "default-chat", turn("Explicit", ""","options":{"model":"default-chat"}"""))
            client.patch(BackendHttpRoutes.SETTINGS) { trusted(userId); jsonBody("""{"defaultModel":"route-strong"}""") }
            assertEquals("strong-model answer" to "route-strong", turn("Chosen"))
            assertEquals("default-model answer" to "route-strong", turn("Fail over", ""","options":{"model":"route-strong"}"""))
            assertEquals(6, classified.get())
        }
    }

    @Test
    fun `initial wait is live only and independent of narration`() {
        var decision: TurnClassification? = null
        backendE2eTest("e2e_initial_wait", featureFlags = BackendFeatureFlags(wsEvents = true),
            turnClassifier = TurnClassifier { decision ?: error("Jev unavailable") }) {
            val user = UUID.randomUUID().toString()
            val decisions = listOf(TurnClassification(false, false), TurnClassification(false, true),
                TurnClassification(true, false), TurnClassification(true, true), null)
            for (narrate in listOf(false, true)) for ((index, next) in decisions.withIndex()) {
                decision = next
                val prompt = "turn-$narrate-$index"
                val chat = createPublicChat(user, requestId = prompt)
                client.patch(BackendHttpRoutes.SETTINGS) { trusted(user); jsonBody("""{"narrateSteps":$narrate}""") }
                llm.pausePromptUntilReleased(prompt)
                try {
                    withPublicSocket(chat) { socket ->
                        val frame = messageFrame(chat, user, prompt, text = prompt)
                        socket.send(Frame.Text(frame))
                        assertEquals("accepted", readJson(socket)["status"].asText())
                        assertEquals("thread.status", readJson(socket)["type"].asText())
                        if (next?.expectedFast != true) {
                            val wait = readJson(socket)
                            assertEquals("assistant.message", wait["type"].asText())
                            assertTrue(wait["seq"].isNull)
                            assertTrue(wait["payload"]["content"].asText().isNotBlank())
                        }
                        llm.awaitPrompt(prompt)
                        socket.send(Frame.Text(frame))
                        assertEquals("accepted", readJson(socket)["status"].asText())
                        assertEquals("thread.status", readJson(socket)["type"].asText())
                        llm.releasePrompt(prompt)
                        assertEquals("thread.completed", readJson(socket)["type"].asText())
                    }
                    webSocketClient().use { client ->
                        val replay = client.webSocketSession("${BackendHttpRoutes.chatWebSocket(chat)}?clientType=backend&afterSeq=0")
                        try {
                            assertEquals("thread.completed", readJson(replay)["type"].asText())
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
