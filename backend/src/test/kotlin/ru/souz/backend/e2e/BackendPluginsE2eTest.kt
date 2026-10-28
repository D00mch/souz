package ru.souz.backend.e2e

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.jupiter.api.io.TempDir
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.plugins.pluginJar
import ru.souz.backend.plugins.BackendPlugins
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.restJsonMapper
import ru.souz.runtime.sandbox.DefaultRuntimeSandboxFactory
import ru.souz.runtime.sandbox.RuntimeSandboxModeResolver
import ru.souz.skilloauth.impl.SkillOAuthCredential
import ru.souz.skilloauth.impl.SkillOAuthTokenCrypto
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.options.model.*

class BackendPluginsE2eTest {
    @TempDir lateinit var root: Path

    @Test fun `unexpected plugin failures correlate diagnostics without logging private messages`() {
        val directory = Files.createDirectories(root.resolve("plugins"))
        pluginJar(directory, handler = "{ throw new IllegalArgumentException(\"private-provider-payload\"); }")
        val logger = LoggerFactory.getLogger(BackendPlugins::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            backendE2eTest("plugin_diagnostics", pluginDirectory = directory) {
                val error = callPlugin(UUID.randomUUID().toString(), "probe__version")["error"]
                assertEquals("plugin_operation_rejected", error["code"].asText())
                val reference = error["message"].asText().substringAfter("diagnostic ID: ")
                UUID.fromString(reference)
                val diagnostic = appender.list.single { it.formattedMessage.contains(reference) }
                assertTrue(diagnostic.formattedMessage.contains("probe__version"))
                assertTrue(diagnostic.formattedMessage.contains("IllegalArgumentException"))
                assertTrue(diagnostic.formattedMessage.contains("fixture.Fixture"))
                assertFalse(diagnostic.formattedMessage.contains("private-provider-payload"))
                assertNull(diagnostic.throwableProxy)
                assertFalse(error.toString().contains("private-provider-payload"))
            }
        } finally { logger.detachAppender(appender); appender.stop() }
    }

    @Test fun `saved option resumes pinned artifact after restart even when disabled and removed`() {
        val directory = Files.createDirectories(root.resolve("plugins"))
        pluginJar(directory)
        backendE2eTest("plugin_resume", pluginDirectory = directory, featureFlags = BackendFeatureFlags(options = true)) {
            val owner = UUID.randomUUID().toString()
            val chat = createPublicChat(owner)
            val initial = client.post(BackendHttpRoutes.chatMessages(chat)) { trusted(owner); jsonBody("""{"content":"prepare a saved wait"}""") }.jsonBody()
            val execution = UUID.fromString(initial["execution"]["id"].asText())
            backend.awaitExecution(execution)
            val option = Option(UUID.randomUUID(), owner, UUID.fromString(chat), execution, OptionKind.GENERIC_SELECTION,
                "Continue", "single", listOf(OptionItem("yes", "Continue")), emptyMap(), OptionStatus.PENDING, null, Instant.now(), null, null)
            backend.optionRepository.save(option)
            // Seed the durable wait boundary; current production tools do not emit ChoiceRequested.
            sql { connection -> connection.prepareStatement("update agent_executions set status = 'waiting_option', finished_at = null where id = ?").use {
                it.setObject(1, execution); assertEquals(1, it.executeUpdate())
            } }
            client.patch(BackendHttpRoutes.SETTINGS) { trusted(owner); jsonBody("""{"disabledPlugins":["probe"]}""") }
            Files.delete(directory.resolve("probe.jar"))
            backend.shutdown()
            val resumedLlm = E2eLlmApi().apply { requestSkill("probe__version", emptyMap()) }
            withPeerBackend(llm = resumedLlm) { peer -> with(peer) {
                val response = client.post(BackendHttpRoutes.optionAnswer(option.id.toString())) {
                    trusted(owner); jsonBody("""{"selectedOptionIds":["yes"]}""")
                }
                assertEquals(HttpStatusCode.OK, response.status)
                backend.awaitExecution(execution)
                assertEquals("one", json.readTree(llm.requests.last().messages.last { it.name == "RunSkillCommand" }.content)["version"].asText())
            } }
        }
    }

    @Test fun `subagents receive only selected enabled plugin tools`() {
        val directory = Files.createDirectories(root.resolve("plugins"))
        pluginJar(directory)
        pluginJar(directory, id = "other")
        val llm = E2eLlmApi { request ->
            if (request.functions.any { it.name == "probe__version" }) {
                assertEquals(listOf("probe__version"), request.functions.map { it.name })
                val output = request.messages.lastOrNull { it.name == "probe__version" }
                if (output == null) toolCallReply(request, "probe__version", emptyMap()) else reply(request, output.content)
            } else if (request.messages.any { it.name == "SpawnSubagent" }) reply(request, "delegated")
            else toolCallReply(request, "SpawnSubagent", mapOf("task" to "Read plugin version", "skillIds" to listOf("probe__version")))
        }
        backendE2eTest("plugin_child", pluginDirectory = directory, llm = llm) {
            val owner = UUID.randomUUID().toString()
            suspend fun delegate(): JsonNode {
                val chat = createPublicChat(owner, UUID.randomUUID().toString())
                val response = client.post(BackendHttpRoutes.chatMessages(chat)) { trusted(owner); jsonBody("""{"content":"Delegate"}""") }.jsonBody()
                backend.awaitExecution(UUID.fromString(response["execution"]["id"].asText()))
                return json.readTree(llm.requests.last().messages.last { it.name == "SpawnSubagent" }.content)
            }
            assertTrue(delegate()["result"].asText().contains("one"))
            client.patch(BackendHttpRoutes.SETTINGS) { trusted(owner); jsonBody("""{"disabledPlugins":["probe"]}""") }
            assertTrue(delegate().has("error"))
        }
    }

    @Test fun `accepted execution retains its version and opt outs persist through reinstall`() {
        val directory = Files.createDirectories(root.resolve("plugins"))
        pluginJar(directory)
        backendE2eTest("plugin_reload", pluginDirectory = directory) {
            val owner = UUID.randomUUID().toString()
            val bootstrap = client.get(BackendHttpRoutes.BOOTSTRAP) { trusted(owner) }.jsonBody()
            assertEquals("probe", bootstrap["capabilities"]["plugins"][0]["id"].asText())
            assertTrue(bootstrap["capabilities"]["plugins"][0]["enabled"].asBoolean())
            val chat = createPublicChat(owner)
            llm.requestSkillForPrompt("old version", "probe__version", emptyMap())
            llm.pausePromptUntilReleased("old version")
            coroutineScope {
                val sending = async { client.post(BackendHttpRoutes.chatMessages(chat)) {
                    trusted(owner); jsonBody("""{"content":"old version"}""")
                } }
                llm.awaitPrompt("old version")
                pluginJar(directory, "two")
                backend.plugins.refresh()
                llm.releasePrompt("old version")
                val accepted = sending.await()
                assertEquals(HttpStatusCode.OK, accepted.status)
                backend.awaitExecution(UUID.fromString(accepted.jsonBody()["execution"]["id"].asText()))
            }
            val oldResult = llm.requests.last().messages.last { it.name == "RunSkillCommand" }.content
            assertEquals("one", json.readTree(oldResult)["version"].asText())
            assertEquals("two", callPlugin(owner, "probe__version")["version"].asText())
            assertEquals(HttpStatusCode.OK, client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(owner); jsonBody("""{"disabledPlugins":["probe"]}""")
            }.status)
            assertTrue(callPlugin(owner, "probe__version").has("error"))
            Files.delete(directory.resolve("probe.jar")); backend.plugins.refresh()
            pluginJar(directory, "three"); backend.plugins.refresh()
            withPeerBackend { peer -> with(peer) {
                val disabled = client.get(BackendHttpRoutes.BOOTSTRAP) { trusted(owner) }.jsonBody()
                assertFalse(disabled["capabilities"]["plugins"][0]["enabled"].asBoolean())
            } }
            assertEquals(HttpStatusCode.BadRequest, client.post(BackendHttpRoutes.CHATS) {
                jsonBody("""{"userId":"$owner","requestId":"reserved","clientType":"backend","tools":[{"name":"probe__version","description":"collision","parameters":{"type":"object"}}]}""")
            }.status)
        }
    }

    @Test fun `real Gmail JAR uses OAuth owned delivery persistent state and sandbox files`() {
        val directory = Files.createDirectories(root.resolve("plugins"))
        Files.copy(Path.of(System.getProperty("souz.test.gmailJar")), directory.resolve("gmail.jar"))
        val home = Files.createDirectories(root.resolve("home"))
        backendE2eTest("plugin_gmail", pluginDirectory = directory, skillOAuth = true, pluginOAuthHttp = ::google,
            sandboxFactory = { settings -> DefaultRuntimeSandboxFactory(settings, RuntimeSandboxModeResolver { "local" }, home, home.resolve("state"), home) }) {
            val owner = UUID.randomUUID().toString()
            val foreign = UUID.randomUUID().toString()
            val target = createPublicChat(owner)
            val now = Instant.now()
            backend.oauthCredentials.upsert(SkillOAuthCredential(owner, "google",
                SkillOAuthTokenCrypto("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=").encrypt("owner-token"), null,
                listOf("https://www.googleapis.com/auth/gmail.readonly"), now.plusSeconds(3600), 1, createdAt = now, updatedAt = now))
            val connection = mapOf("mailboxEmail" to "owner@example.com", "topicName" to "projects/demo/topics/mail",
                "channelType" to "public_client", "channelId" to target, "notificationMode" to "assistant", "config" to "personal")
            val missingConfig = callPlugin(owner, "gmail__status")["error"]
            assertEquals("plugin_invalid_arguments", missingConfig["code"].asText())
            assertEquals("A required argument is missing.", missingConfig["message"].asText())
            assertEquals("configured", callPlugin(owner, "gmail__configure", connection)["status"].asText())
            val message = callPlugin(owner, "gmail__sync", mapOf("config" to "personal"))["message"]
            val claim = mapOf("config" to "personal", "message" to message["message"].asText(), "claim" to message["claim"].asText())
            assertEquals("gmail_read_required", callPlugin(owner, "gmail__deliver", claim + ("text" to "unprepared"))["error"]["code"].asText())
            assertEquals("compose", callPlugin(owner, "gmail__read", claim)["status"].asText())
            assertEquals("delivered", callPlugin(owner, "gmail__deliver", claim + ("text" to "Your invoice arrived"))["status"].asText())
            assertEquals("gmail_claim_inactive", callPlugin(owner, "gmail__deliver", claim + ("text" to "duplicate"))["error"]["code"].asText())
            val messages = client.get(BackendHttpRoutes.chatMessages(target)) { trusted(owner) }.jsonBody()["items"]
            assertEquals(1, messages.count { it["content"].asText() == "Your invoice arrived" })
            assertEquals("reconnect_required", callPlugin(foreign, "gmail__search", mapOf("query" to "in:inbox"))["status"].asText())
            assertTrue(callPlugin(foreign, "gmail__status", mapOf("config" to "personal")).has("error"))
            val file = callPlugin(owner, "gmail__download_attachment", mapOf("message" to "m1", "attachment" to "a1", "filename" to "../../report.pdf"))
            val path = Path.of(file["filePath"].asText())
            assertTrue(path.startsWith(home.resolve("Documents/souz/plugin-files/gmail")))
            assertEquals("%PDF-1.7", Files.readString(path))
            val legacy = Files.createDirectories(home.resolve("legacy"))
            Files.writeString(legacy.resolve("connection.json"), json.writeValueAsString(mapOf("mailboxEmail" to "owner@example.com",
                "topicName" to "projects/demo/topics/mail", "destination" to mapOf("channelType" to "public_client", "channelId" to target))))
            assertEquals("imported", callPlugin(owner, "gmail__import_connection", mapOf("config" to "imported", "source" to legacy.resolve("connection.json").toString()))["status"].asText())
            assertTrue(callPlugin(owner, "gmail__import_connection", mapOf("config" to "unsafe", "source" to "/etc/passwd")).has("error"))
            backend.shutdown()
            withPeerBackend { peer -> with(peer) {
                val state = callPlugin(owner, "gmail__status", mapOf("config" to "personal"))
                assertEquals("101", state["historyCursor"].asText())
                assertEquals(listOf("m1"), state["done"].map { it.asText() })
            } }
        }
    }

    private fun google() = HttpClient(MockEngine { request ->
        assertEquals("Bearer owner-token", request.headers["Authorization"])
        respond(when {
            request.url.encodedPath.endsWith("/profile") -> """{"emailAddress":"owner@example.com","historyId":"100"}"""
            request.url.encodedPath.endsWith("/history") -> """{"historyId":"101","history":[{"messagesAdded":[{"message":{"id":"m1"}}]}]}"""
            "/attachments/" in request.url.encodedPath -> """{"size":8,"data":"JVBERi0xLjc"}"""
            else -> """{"id":"m1","labelIds":["INBOX"],"internalDate":"9999999999999","payload":{"mimeType":"text/plain","body":{"data":"SGVsbG8"}}}"""
        })
    })
}

private suspend fun BackendE2eScope.callPlugin(owner: String, name: String, args: Map<String, Any> = emptyMap()): JsonNode {
    val prompt = "plugin call ${UUID.randomUUID()}"
    llm.requestSkillForPrompt(prompt, name, args)
    val chat = createPublicChat(owner, UUID.randomUUID().toString())
    val response = client.post(BackendHttpRoutes.chatMessages(chat)) { trusted(owner); jsonBody(json.writeValueAsString(mapOf("content" to prompt))) }
    assertEquals(HttpStatusCode.OK, response.status, response.toString())
    val id = UUID.fromString(response.jsonBody()["execution"]["id"].asText())
    backend.awaitExecution(id)
    val request = llm.requests.last { it.messages.any { message -> message.role == LLMMessageRole.user && message.content == prompt } }
    return restJsonMapper.readTree(request.messages.last { it.name == "RunSkillCommand" }.content)
}
