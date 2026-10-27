package ru.souz.backend.e2e

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.websocket.Frame
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.io.TempDir
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.llms.LLMMessageRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackendPluginDiagnosticsE2eTest {
    @TempDir lateinit var root: Path

    private fun install(): Path = root.also {
        Files.copy(Path.of(System.getProperty("souz.test.githubJar")), it.resolve("github.jar"))
    }

    @Test fun `wrong names types and enum values are actionable before network and correct on next call`() {
        val calls = AtomicInteger()
        backendE2eTest("plugin_validation", pluginDirectory = install(), pluginHttpClient = {
            HttpClient(MockEngine { calls.incrementAndGet(); respond("[]") })
        }) {
            val owner = UUID.randomUUID().toString()
            val cases = listOf(
                mapOf("owner" to "secret-owner", "repo" to "secret-repo"),
                mapOf("repository" to 42),
                mapOf("repository" to "octo/demo", "state" to "secret-state"),
                mapOf("repository" to "octo/demo", "perPage" to "secret-number"),
                mapOf("repository" to "octo/demo", "token" to "secret-token"),
            )
            for (arguments in cases) {
                val error = callSkill(owner, "github__list_issues", arguments)["error"]
                assertEquals("plugin_invalid_arguments", error["code"].asText())
                assertTrue(error["details"].isArray)
                assertEquals("repository", error["inputSchema"]["required"].single().asText())
                assertFalse(error.toString().contains("secret-"))
            }
            assertEquals(0, calls.get())
            assertEquals("ok", callSkill(owner, "github__list_issues", mapOf("repository" to "octo/demo"))["status"].asText())
            assertEquals(1, calls.get())
        }
    }

    @Test fun `schema selection then invocation emits a safe durable domain receipt without client execution`() {
        val calls = AtomicInteger()
        val selected = mutableListOf<Map<String, Any>>()
        val llm = E2eLlmApi { request ->
            val previous = request.messages.lastOrNull { it.role == LLMMessageRole.function }
            val arguments = when {
                previous == null -> mapOf("skillId" to "github__list_issues")
                previous.content.contains("inputSchema") -> mapOf("skillId" to "github__list_issues", "arguments" to mapOf("repository" to "octo/demo", "perPage" to 1))
                else -> null
            }
            if (arguments == null) reply(request, "Rate limited")
            else { selected += arguments; toolCallReply(request, "RunSkillCommand", arguments) }
        }
        backendE2eTest("plugin_receipt", pluginDirectory = install(), llm = llm, pluginHttpClient = {
            HttpClient(MockEngine {
                calls.incrementAndGet()
                respond("secret remote body", HttpStatusCode.TooManyRequests, headersOf("Retry-After" to listOf("60"), "X-RateLimit-Reset" to listOf("2000000000")))
            })
        }) {
            val owner = UUID.randomUUID().toString()
            val chat = createPublicChat(owner)
            withMultiChatSocket { socket ->
                socket.send(Frame.Text(messageFrame(chat, owner, "inspect", text = "Inspect issues")))
                assertEquals("accepted", readJson(socket)["status"].asText())
                assertEquals("thread.status", readJson(socket)["type"].asText())
                val event = readJson(socket)
                assertEquals("plugin.call.finished", event["type"].asText())
                val payload = event["payload"]
                assertEquals("github", payload["pluginId"].asText())
                assertEquals("github__list_issues", payload["toolName"].asText())
                assertEquals("succeeded", payload["status"].asText())
                assertEquals("rate_limited", payload["outcome"].asText())
                assertEquals(64, payload["digest"].asText().length)
                assertTrue(payload["version"].asText().isNotBlank())
                assertFalse(payload.has("arguments") || payload.has("result") || payload.toString().contains("secret"))
                assertEquals("thread.completed", readJson(socket)["type"].asText()) // No tool.result sent.
                assertEquals(2, selected.size)
                assertFalse(selected[0].containsKey("arguments"))
                assertEquals(1, calls.get())
                assertEquals(60, lastSkillResult()["retryAfterSeconds"].asInt())
                assertEquals(2000000000, lastSkillResult()["rateLimitReset"].asInt())
                val replay = client.get(BackendHttpRoutes.chatEvents(chat)) { trusted(owner) }.jsonBody()["items"]
                assertEquals(payload, replay.single { it["type"].asText() == "plugin.call.finished" }["payload"])
                socket.send(Frame.Text("""{"kind":"chat.subscribe","chatId":"$chat","requestId":"replay","afterSeq":0}"""))
                assertEquals("accepted", readJson(socket)["status"].asText())
                assertEquals(event, readJson(socket))
                assertEquals("thread.completed", readJson(socket)["type"].asText())
            }
        }
    }

    @Test fun `size limit survives GitHub mapping with corrective guidance`() =
        backendE2eTest("plugin_size", pluginDirectory = install(), pluginHttpClient = {
            HttpClient(MockEngine { respond("x".repeat(1024 * 1024 + 1)) })
        }) {
            val result = callSkill(UUID.randomUUID().toString(), "github__list_issues", mapOf("repository" to "octo/demo"))
            assertEquals("plugin_http_body_too_large", result["error"]["code"].asText())
            assertTrue(result["error"]["message"].asText().contains("perPage"))
        }
}
