package ru.souz.backend.e2e

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import java.util.UUID
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.llms.restJsonMapper
import ru.souz.runtime.sandbox.DefaultRuntimeSandboxFactory
import ru.souz.runtime.sandbox.RuntimeSandboxModeResolver
import ru.souz.skilloauth.impl.SkillOAuthCredential
import ru.souz.skilloauth.impl.SkillOAuthTokenCrypto

class BackendGmailPluginE2eTest {
    @TempDir lateinit var root: Path

    @Test fun `loaded Gmail uses real OAuth storage owned delivery and persistent plugin state`() {
        val plugins = install()
        backendE2eTest("e2e_gmail", pluginDirectory = plugins, pluginOAuthClient = ::googleClient) {
            val owner = UUID.randomUUID().toString()
            val foreign = UUID.randomUUID().toString()
            val target = createPublicChat(owner)
            authorize(owner)
            assertEquals("configured", gmail(owner, "configure", configuration(target))["status"].asText())
            val synced = gmail(owner, "sync", mapOf("config" to "personal"))
            assertEquals("ready", synced["status"].asText(), synced.toString())
            val claim = synced["message"]["claim"].asText()
            val prepared = gmail(owner, "read", mapOf("config" to "personal", "message" to "m1", "claim" to claim))
            assertEquals("compose", prepared["status"].asText(), prepared.toString())
            assertEquals("Hello from Gmail", prepared["email"]["body"].asText())
            val delivery = restJsonMapper.convertValue(prepared["inputs"], Map::class.java).entries.associate { it.key.toString() to it.value!! } + ("text" to "Gmail notification")
            assertEquals("delivered", gmail(owner, "deliver", delivery)["status"].asText())
            assertTrue(gmail(owner, "deliver", delivery).has("error"))
            val messages = client.get(BackendHttpRoutes.chatMessages(target)) { trusted(owner) }.jsonBody()["items"]
            assertEquals(1, messages.count { it["content"].asText() == "Gmail notification" })
            val reconnect = gmail(foreign, "search", mapOf("query" to "in:inbox"))
            assertEquals("reconnect_required", reconnect["status"].asText())
            assertTrue(reconnect["authorizationUrl"].asText().contains("gmail.readonly"))
            assertTrue(gmail(foreign, "status", mapOf("config" to "personal")).has("error"))
            val foreignTarget = createPublicChat(foreign)
            gmail(owner, "configure", configuration(foreignTarget) + ("config" to "foreign_destination"))
            val foreignClaim = gmail(owner, "sync", mapOf("config" to "foreign_destination"))["message"]["claim"].asText()
            val rejected = gmail(owner, "deliver", mapOf("config" to "foreign_destination", "message" to "m1", "claim" to foreignClaim,
                "channelType" to "public_client", "channelId" to foreignTarget, "text" to "must not arrive"))
            assertEquals("uncertain", rejected["status"].asText())
            assertTrue(client.get(BackendHttpRoutes.chatMessages(foreignTarget)) { trusted(foreign) }.jsonBody()["items"].isEmpty)
            backend.shutdown()
            withPeerBackend { peer -> with(peer) {
                val state = gmail(owner, "status", mapOf("config" to "personal"))
                assertEquals("101", state["historyCursor"].asText())
                assertEquals(listOf("m1"), state["done"].map { it.asText() })
                assertEquals(0, state["pending"].size())
            } }
        }
    }

    @Test fun `loaded Gmail imports sandbox files and writes a validated attachment without base64 results`() {
        val plugins = install()
        val home = Files.createDirectories(root.resolve("home"))
        backendE2eTest("e2e_gmail_files", pluginDirectory = plugins, pluginOAuthClient = ::googleClient,
            sandboxFactory = { settings -> DefaultRuntimeSandboxFactory(settings, RuntimeSandboxModeResolver { "local" }, home, home.resolve("state"), home) }) {
            val owner = UUID.randomUUID().toString()
            val target = createPublicChat(owner)
            authorize(owner)
            val legacy = Files.createDirectories(home.resolve("legacy"))
            Files.writeString(legacy.resolve("connection.json"), restJsonMapper.writeValueAsString(mapOf(
                "mailboxEmail" to "owner@example.com", "topicName" to "projects/demo/topics/gmail",
                "destination" to mapOf("channelType" to "public_client", "channelId" to target), "notificationMode" to "assistant")))
            Files.writeString(legacy.resolve("state.json"), """{"schemaVersion":1,"revision":5,"mailboxEmail":"owner@example.com","status":"ready","historyCursor":"77","startedAtMillis":1,"pending":{},"done":["old"]}""")
            assertEquals("imported", gmail(owner, "import_connection", mapOf("config" to "personal", "source" to legacy.resolve("connection.json").toString()))["status"].asText())
            assertEquals("77", gmail(owner, "status", mapOf("config" to "personal"))["historyCursor"].asText())
            val downloaded = gmail(owner, "download_attachment", mapOf("message" to "m1", "attachment" to "att1", "filename" to "../../report.pdf"))
            assertEquals("downloaded", downloaded["status"].asText(), downloaded.toString())
            val path = Path.of(downloaded["filePath"].asText())
            assertTrue(path.startsWith(home.resolve("Documents/souz/plugin-files/gmail")))
            assertEquals("attachment.pdf", path.fileName.toString())
            assertContentEquals(pdf, Files.readAllBytes(path))
            assertFalse(downloaded.has("data"))
            assertTrue(gmail(owner, "import_connection", mapOf("config" to "outside", "source" to "/etc/passwd")).has("error"))
            assertTrue(Files.exists(legacy.resolve("state.json")))
        }
    }

    private fun install(): Path = Files.createDirectories(root.resolve("plugins")).also {
        Files.copy(Path.of(System.getProperty("souz.test.gmailJar")), it.resolve("gmail.jar"))
    }

    private fun googleClient() = HttpClient(MockEngine { request ->
        assertEquals("Bearer gmail-owner-token", request.headers[HttpHeaders.Authorization])
        val body = when {
            request.url.encodedPath.endsWith("/profile") -> """{"emailAddress":"owner@example.com","historyId":"100"}"""
            request.url.encodedPath.endsWith("/history") -> """{"historyId":"101","history":[{"messagesAdded":[{"message":{"id":"m1"}}]}]}"""
            "/attachments/" in request.url.encodedPath -> """{"size":${pdf.size},"data":"${Base64.getUrlEncoder().withoutPadding().encodeToString(pdf)}"}"""
            else -> """{"id":"m1","internalDate":"9999999999999","labelIds":["INBOX"],"payload":{"mimeType":"text/plain","headers":[{"name":"Subject","value":"Hello"}],"body":{"data":"SGVsbG8gZnJvbSBHbWFpbA"}}}"""
        }
        respond(body)
    })

    private suspend fun BackendE2eScope.authorize(user: String) {
        val now = Instant.now()
        backend.oauthCredentials.upsert(SkillOAuthCredential(user, "google", SkillOAuthTokenCrypto(E2E_TELEGRAM_TOKEN_KEY).encrypt("gmail-owner-token"),
            null, listOf("https://www.googleapis.com/auth/gmail.readonly"), now.plusSeconds(3600), 1, createdAt = now, updatedAt = now))
    }

    private fun configuration(target: String): Map<String, Any> = mapOf("config" to "personal", "mailboxEmail" to "owner@example.com",
        "topicName" to "projects/demo/topics/gmail", "channelType" to "public_client", "channelId" to target, "notificationMode" to "assistant")

    private suspend fun BackendE2eScope.gmail(user: String, command: String, arguments: Map<String, Any>): JsonNode {
        llm.requestSkill("gmail__$command", arguments)
        val chat = createPublicChat(user, UUID.randomUUID().toString())
        val response = client.post(BackendHttpRoutes.chatMessages(chat)) {
            trusted(user); jsonBody("""{"content":"gmail ${UUID.randomUUID()}","options":{"model":"${E2E_LOCAL_MODEL.alias}"}}""")
        }.jsonBody()
        backend.awaitExecution(UUID.fromString(response["execution"]["id"].asText()))
        return restJsonMapper.readTree(llm.requests.last().messages.last { it.name == "RunSkillCommand" }.content)
    }

    private companion object { val pdf = "%PDF-1.7\n".toByteArray() + ByteArray(2 * 1024 * 1024) }
}
