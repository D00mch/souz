package ru.souz.mcp

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.tool.skills.ToolGetSkillByName
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Timeout(15)
class McpToolCatalogTest {
    @Test
    fun `loaded tools share discovery schemas and remain scoped to their owner and execution`() = runBlocking {
        val server = McpTestServer { request ->
            restJsonMapper.readTree(if (request.path("method").asText() == "tools/list") {
                """{"tools":[{"name":"create","description":"Create an event","inputSchema":{"type":"object","properties":{"value":{"anyOf":[{"type":"integer"},{"type":"null"}]}},"required":["value"]}}]}"""
            } else """{"content":[{"type":"text","text":"created"}],"isError":false}""")
        }
        server.client.use { http ->
            val catalog = McpToolCatalog("owner", listOf(McpServerConfig("calendar", "Calendar", URL),
                McpServerConfig("other", "Other calendar", URL)), http)
            try {
                assertTrue(catalog.catalog.toolsByCategory.values.all { it.isEmpty() })
                val first = catalog.load("calendar").single()
                assertEquals(listOf(first), catalog.load("calendar"))
                val second = catalog.load("other").single()
                assertFalse(first.skillId == second.skillId)
                assertEquals(2, server.recorded().count { it.path("method").asText() == "tools/list" })
                val tool = catalog.catalog.toolsByCategory.values.first { first.skillId in it }.getValue(first.skillId)
                val rejected = tool.invoke(LLMResponse.FunctionCall(first.skillId, emptyMap()), ToolInvocationMeta(userId = "other"))
                assertEquals("mcp_identity_mismatch", restJsonMapper.readTree(rejected.content).path("error").path("code").asText())
                val result = tool.invoke(LLMResponse.FunctionCall(first.skillId, mapOf("value" to 4)), ToolInvocationMeta(userId = "owner"))
                assertFalse(restJsonMapper.readTree(result.content).path("isError").asBoolean())
                assertEquals("create", server.recorded().last { it.path("method").asText() == "tools/call" }.path("params").path("name").asText())
                val details = ToolGetSkillByName({ catalog.catalog },
                    mockk { every { applyFilter(any()) } answers { firstArg() } }, mockk())
                    .invoke(LLMResponse.FunctionCall("GetSkillByName", mapOf("skillId" to first.skillId)), ToolInvocationMeta(userId = "owner"))
                assertEquals(2, restJsonMapper.readTree(details.content).path("skill").path("inputSchema").path("properties")
                    .path("value").path("anyOf").size())
            } finally {
                catalog.close()
            }
            val result = catalog.discoveryTool.invoke(LLMResponse.FunctionCall(McpToolCatalog.LOAD_TOOL_NAME,
                mapOf("serverId" to "calendar")), ToolInvocationMeta(userId = "owner"))
            assertTrue(restJsonMapper.readTree(result.content).has("error"))
        }
    }

    @Test
    fun `discovery pages summaries and rejects unknown servers without repeating the listing`() = runBlocking {
        val server = McpTestServer {
            restJsonMapper.createObjectNode().apply {
                val tools = putArray("tools")
                repeat(23) { index -> tools.addObject().apply {
                    put("name", "tool$index")
                    putObject("inputSchema").put("type", "object")
                } }
            }
        }
        server.client.use { http ->
            val catalog = McpToolCatalog("owner", listOf(McpServerConfig("calendar", "Calendar", URL)), http)
            try {
                suspend fun discover(arguments: Map<String, Any>) = restJsonMapper.readTree(catalog.discoveryTool.invoke(
                    LLMResponse.FunctionCall(McpToolCatalog.LOAD_TOOL_NAME, arguments), ToolInvocationMeta(userId = "owner")).content)
                val first = discover(mapOf("serverId" to "calendar"))
                assertEquals(20, first.path("tools").size())
                assertEquals(20, first.path("nextOffset").asInt())
                assertEquals(23, first.path("totalTools").asInt())
                val last = discover(mapOf("serverId" to "calendar", "offset" to 20))
                assertEquals(3, last.path("tools").size())
                assertTrue(last.path("nextOffset").isNull)
                assertEquals("mcp_invalid_arguments", discover(mapOf("serverId" to "calendar", "limit" to 51))
                    .path("error").path("code").asText())
                assertEquals("mcp_server_unavailable", discover(mapOf("serverId" to "missing"))
                    .path("error").path("code").asText())
                assertEquals(1, server.recorded().count { it.path("method").asText() == "tools/list" })
            } finally { catalog.close() }
        }
    }

    @Test
    fun `server identity and tool identity cannot alias through separators`() {
        assertFalse(McpToolCatalog.toolId("a.b", "c") == McpToolCatalog.toolId("a", "b.c"))
        assertEquals(McpToolCatalog.toolId("calendar", "create"), McpToolCatalog.toolId("calendar", "create"))
    }

    companion object {
        private const val URL = "https://mcp.example.test/mcp"
    }
}
