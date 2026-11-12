package ru.souz.backend.e2e

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import ru.souz.agent.skills.SkillClassifier
import ru.souz.agent.skills.SkillId
import ru.souz.agent.spi.TaskClassification
import ru.souz.agent.spi.TaskComplexityClassifier
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.llms.restJsonMapper
import ru.souz.mcp.McpToolCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackendMcpE2eTest {
    @Test
    fun `credential-free client turns cannot discover or invoke the claimed owner's MCP tools`() {
        val calls = CopyOnWriteArrayList<JsonNode>()
        val closed = CompletableDeferred<Unit>()
        val otherHttp = HttpClient(MockEngine { error("Unexpected provider HTTP request") })
        val skillId = McpToolCatalog.toolId("calendar", "create_event")
        val llm = E2eLlmApi { request ->
            if (request.functions.isEmpty()) return@E2eLlmApi reply(request, "Speculative text response")
            val system = request.messages.first { it.role == LLMMessageRole.system }.content
            assertFalse(system.contains("serverId: \"calendar\""))
            assertFalse(system.contains("serverId: \"music\""))
            assertFalse(request.functions.any { it.name == McpToolCatalog.LOAD_TOOL_NAME })
            val results = request.messages.filter { it.role == LLMMessageRole.function }
            when {
                results.none { it.name == "GetSkillByName" } ->
                    toolCallReply(request, "GetSkillByName", mapOf("skillId" to skillId))
                results.none { it.name == "RunSkillCommand" } -> {
                    assertEquals("skill_unavailable", restJsonMapper.readTree(results.last().content).path("error").path("code").asText())
                    toolCallReply(request, "RunSkillCommand", mapOf("skillId" to skillId, "arguments" to emptyMap<String, Any>()))
                }
                else -> {
                    assertEquals("skill_unavailable", restJsonMapper.readTree(results.last().content).path("error").path("code").asText())
                    reply(request, "Owner MCP tools unavailable")
                }
            }
        }
        backendE2eTest("e2e_mcp_public", llm = llm,
            providerClients = ProviderHttpClients(otherHttp, otherHttp, mcp = mcpHttp(calls, closed)),
            skillClassifier = SkillClassifier { _, descriptions ->
                assertFalse(SkillId("calendar") in descriptions)
                emptySet()
            }, complexityClassifier = TaskComplexityClassifier { _, capabilities ->
                assertFalse("tool-server:calendar" in capabilities)
                TaskClassification(complex = true)
            }) {
            val owner = UUID.randomUUID().toString()
            assertEquals(HttpStatusCode.OK, client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(owner); jsonBody(configuredServers())
            }.status)
            val chat = createPublicChat(owner)
            withPublicSocket(chat) { socket ->
                socket.send(Frame.Text(messageFrame(chat, owner, "public-mcp", text = "Create an owner calendar event")))
                assertEquals("accepted", readJson(socket).path("status").asText())
                assertEquals("thread.status", readJson(socket).path("type").asText())
                assertEquals("thread.completed", readJson(socket).path("type").asText())
            }
            val messages = client.get(BackendHttpRoutes.chatMessages(chat)) { trusted(owner) }.jsonBody().path("items")
            assertEquals("Owner MCP tools unavailable", messages.last().path("content").asText())
            assertTrue(calls.isEmpty(), "Credential-free turns must not contact any owner MCP server")
            assertFalse(closed.isCompleted)
        }
    }

    @Test
    fun `MCP tools execute in the real graph after classifier preloading`() = mcpScenario(preload = true)

    @Test
    fun `MCP tools execute in the same run after negative classification and explicit discovery`() = mcpScenario(preload = false)

    private fun mcpScenario(preload: Boolean) {
        val calls = CopyOnWriteArrayList<JsonNode>()
        val closed = CompletableDeferred<Unit>()
        val http = mcpHttp(calls, closed)
        val skillId = McpToolCatalog.toolId("calendar", "create_event")
        var firstCall = true
        val llm = E2eLlmApi { request ->
            if (request.functions.isEmpty()) return@E2eLlmApi reply(request, "Speculative text response")
            val system = request.messages.first { it.role == LLMMessageRole.system }.content
            assertTrue(system.contains("serverId: \"calendar\""))
            assertTrue(system.contains("serverId: \"music\""))
            assertFalse(system.contains(URL))
            assertTrue(request.functions.any { it.name == McpToolCatalog.LOAD_TOOL_NAME })
            assertFalse(request.functions.any { it.name == skillId })
            if (firstCall) {
                assertFalse(system.contains("slot_marker"))
                assertEquals(if (preload) 1 else 0, calls.count { it.path("method").asText() == "tools/list" })
                firstCall = false
            }
            val results = request.messages.filter { it.role == LLMMessageRole.function }
            when {
                !preload && results.none { it.name == McpToolCatalog.LOAD_TOOL_NAME } ->
                    toolCallReply(request, McpToolCatalog.LOAD_TOOL_NAME, mapOf("serverId" to "calendar"))
                results.none { it.name == "GetSkillByName" } ->
                    toolCallReply(request, "GetSkillByName", mapOf("skillId" to skillId))
                results.none { it.name == "RunSkillCommand" } -> {
                    val detail = restJsonMapper.readTree(results.last { it.name == "GetSkillByName" }.content)
                    assertEquals(2, detail.path("skill").path("inputSchema").path("properties").path("slot_marker").path("anyOf").size())
                    toolCallReply(request, "RunSkillCommand", mapOf("skillId" to skillId,
                        "arguments" to mapOf("slot_marker" to "tomorrow at 15:00")))
                }
                else -> {
                    val result = restJsonMapper.readTree(results.last { it.name == "RunSkillCommand" }.content)
                    assertEquals("created", result.path("structuredContent").path("outcome").asText())
                    reply(request, "Meeting created")
                }
            }
        }
        val otherHttp = HttpClient(MockEngine { error("MCP requests must use their dedicated transport") })
        backendE2eTest("e2e_mcp_$preload", llm = llm, providerClients = ProviderHttpClients(otherHttp, otherHttp, mcp = http),
            skillClassifier = SkillClassifier { _, descriptions ->
                assertEquals(setOf(SkillId("calendar"), SkillId("music")), descriptions.keys)
                if (preload) setOf(SkillId("calendar")) else emptySet()
            }, complexityClassifier = TaskComplexityClassifier { _, capabilities ->
                assertTrue("tool-server:calendar" in capabilities)
                TaskClassification(complex = true)
            }) {
            val user = UUID.randomUUID().toString()
            assertEquals(HttpStatusCode.OK, client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(user); jsonBody(configuredServers())
            }.status)
            val chat = createPublicChat(user)
            val sent = client.post(BackendHttpRoutes.chatMessages(chat)) {
                trusted(user); jsonBody("""{"content":"Create a meeting tomorrow at 15:00"}""")
            }
            assertEquals(HttpStatusCode.OK, sent.status)
            backend.awaitExecution(UUID.fromString(sent.jsonBody().path("execution").path("id").asText()))
            val messages = client.get(BackendHttpRoutes.chatMessages(chat)) { trusted(user) }.jsonBody().path("items")
            assertEquals("Meeting created", messages.last().path("content").asText())
            assertEquals(1, calls.count { it.path("method").asText() == "tools/list" })
            assertEquals(1, calls.count { it.path("method").asText() == "tools/call" })
            assertEquals("create_event", calls.last { it.path("method").asText() == "tools/call" }.path("params").path("name").asText())
            assertTrue(closed.isCompleted, "The execution must close its MCP session before completion")
        }
    }

    @Test
    fun `MCP settings persist per user validate replacements and survive backend restart`() =
        backendE2eTest("e2e_mcp_settings") {
            val user = UUID.randomUUID().toString()
            val saved = client.patch(BackendHttpRoutes.SETTINGS) { trusted(user); jsonBody(configuredServers()) }
            assertEquals(HttpStatusCode.OK, saved.status)
            val expected = saved.jsonBody().path("settings").path("mcp")
            assertEquals("Calendar events", expected.path("calendar").path("description").asText())
            assertEquals(0, client.get(BackendHttpRoutes.SETTINGS) { trusted("other-owner") }.jsonBody().path("settings").path("mcp").size())
            val invalid = listOf(
                mapOf("calendar" to mapOf("url" to "http://127.0.0.1:8765/mcp", "description" to "Internal")),
                mapOf("calendar" to mapOf("url" to "http://[::1]/mcp", "description" to "Internal")),
                mapOf("calendar" to mapOf("url" to "http://169.254.169.254/latest/meta-data/", "description" to "Internal")),
                mapOf("calendar" to mapOf("url" to "http://10.0.0.1/admin", "description" to "Internal")),
                mapOf("bad.id" to mapOf("url" to URL, "description" to "Bad ID")),
                mapOf("calendar" to mapOf("url" to "file:///tmp/mcp", "description" to "Calendar")),
                mapOf("calendar" to mapOf("url" to "https://user:secret@example.test/mcp", "description" to "Calendar")),
                mapOf("calendar" to mapOf("url" to "$URL#fragment", "description" to "Calendar")),
                mapOf("calendar" to mapOf("url" to URL, "description" to "")),
                mapOf("calendar" to mapOf("url" to URL, "description" to "x".repeat(241))),
            )
            for (servers in invalid) {
                assertEquals(HttpStatusCode.BadRequest, client.patch(BackendHttpRoutes.SETTINGS) {
                    trusted(user); jsonBody(json.writeValueAsString(mapOf("mcp" to servers)))
                }.status)
            }
            assertEquals(expected, client.get(BackendHttpRoutes.SETTINGS) { trusted(user) }.jsonBody().path("settings").path("mcp"))
            withPeerBackend { peer ->
                assertEquals(expected, peer.json.readTree(peer.client.get(BackendHttpRoutes.SETTINGS) {
                    trusted(user)
                }.bodyAsText()).path("settings").path("mcp"))
            }
            val normalizedUrl = "HTTPS://bücher.example:443/unused/../mcp tools"
            val accepted = client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(user)
                jsonBody(json.writeValueAsString(mapOf("mcp" to mapOf("normalized" to mapOf(
                    "url" to "  $normalizedUrl  ", "description" to "Normalized URL",
                )))))
            }
            assertEquals(HttpStatusCode.OK, accepted.status)
            assertEquals(normalizedUrl, accepted.jsonBody().path("settings").path("mcp").path("normalized").path("url").asText())
            client.patch(BackendHttpRoutes.SETTINGS) { trusted(user); jsonBody("""{"mcp":{}}""") }
            assertEquals(0, client.get(BackendHttpRoutes.SETTINGS) { trusted(user) }.jsonBody().path("settings").path("mcp").size())
        }

    private fun mcpHttp(calls: MutableList<JsonNode>, closed: CompletableDeferred<Unit>): HttpClient =
        HttpClient(MockEngine { request ->
            if (request.method == HttpMethod.Delete) {
                closed.complete(Unit)
                return@MockEngine respond("", HttpStatusCode.OK)
            }
            if (request.method != HttpMethod.Post) return@MockEngine respond("", HttpStatusCode.MethodNotAllowed)
            val rpc = restJsonMapper.readTree(request.body.toByteArray())
            calls.add(rpc)
            val result = when (rpc.path("method").asText()) {
                "initialize" -> """{"protocolVersion":"2025-11-25","capabilities":{"tools":{}},"serverInfo":{"name":"calendar","version":"1"}}"""
                "notifications/initialized", "notifications/cancelled" -> return@MockEngine respond("", HttpStatusCode.Accepted)
                "tools/list" -> """{"tools":[{"name":"create_event","description":"Create a calendar event","inputSchema":{"type":"object","properties":{"slot_marker":{"anyOf":[{"type":"string"},{"type":"null"}]}},"required":["slot_marker"]}}]}"""
                "tools/call" -> """{"content":[{"type":"text","text":"Created"}],"structuredContent":{"outcome":"created"},"isError":false}"""
                else -> error("Unexpected MCP method")
            }
            respond("""{"jsonrpc":"2.0","id":${rpc["id"]},"result":$result}""",
                headers = headersOf("Content-Type" to listOf("application/json"), "Mcp-Session-Id" to listOf("fixture")))
        }) { providerHttpClientDefaults() }

    private fun configuredServers(): String = """{"mcp":{"calendar":{"enabled":true,"description":"Calendar events","url":"$URL"},"music":{"enabled":true,"description":"Music playback","url":"https://music.example.test/mcp"}}}"""

    companion object {
        private const val URL = "https://calendar.example.test/mcp"
    }
}
