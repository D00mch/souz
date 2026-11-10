package ru.souz.mcp

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.tool.RuntimePassThroughToolsFilter
import ru.souz.tool.skills.ToolGetSkillByName
import ru.souz.tool.skills.ToolGetSkillsByCategory
import ru.souz.tool.skills.ToolGetSkillsNamesByCategory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Timeout(15)
class McpSkillDiscoveryTest {
    @Test
    fun `category pagination takes one snapshot for a thousand remote tools`() = runBlocking {
        val server = McpTestServer {
            restJsonMapper.createObjectNode().apply {
                val tools = putArray("tools")
                repeat(1_000) { index -> tools.addObject().apply {
                    put("name", "tool$index")
                    putObject("inputSchema").put("type", "object")
                } }
            }
        }
        server.client.use { http ->
            val catalog = McpToolCatalog("owner", listOf(McpServerConfig("server", "Server", URL)), http)
            try {
                catalog.load("server")
                var reads = 0
                val provider = { reads++; AgentToolCatalog.compose(AgentToolCatalog(), catalog.catalog) }
                val detail = ToolGetSkillByName(provider, RuntimePassThroughToolsFilter, mockk())
                val names = ToolGetSkillsNamesByCategory(provider, RuntimePassThroughToolsFilter)
                val categories = ToolGetSkillsByCategory(detail, names)
                val meta = ToolInvocationMeta(userId = "owner")
                val page = restJsonMapper.readTree(categories.invoke(LLMResponse.FunctionCall("GetSkillsByCategory",
                    mapOf("category" to "APPLICATIONS")), meta).content)
                assertEquals(1, reads)
                assertEquals(20, page.path("skills").size())
                assertEquals(1_000, page.path("totalSkills").asInt())
                assertEquals(20, page.path("nextOffset").asInt())
                val next = restJsonMapper.readTree(names.invoke(LLMResponse.FunctionCall("GetSkillsNamesByCategory",
                    mapOf("category" to "APPLICATIONS", "offset" to 20)), meta).content)
                assertEquals(20, next.path("skillNames").size())
                assertEquals(40, next.path("nextOffset").asInt())
                assertEquals(2, reads)
            } finally { catalog.close() }
        }
    }

    @Test
    fun `category detail page stops at its byte budget and advances without losing entries`() = runBlocking {
        val server = McpTestServer {
            restJsonMapper.createObjectNode().apply {
                val tools = putArray("tools")
                repeat(4) { index -> tools.addObject().apply {
                    put("name", "tool$index")
                    putObject("inputSchema").apply {
                        put("type", "object")
                        putObject("properties").putObject("value").put("description", "x".repeat(6_000))
                    }
                } }
            }
        }
        server.client.use { http ->
            val catalog = McpToolCatalog("owner", listOf(McpServerConfig("server", "Server", URL)), http)
            try {
                catalog.load("server")
                val names = ToolGetSkillsNamesByCategory({ catalog.catalog }, RuntimePassThroughToolsFilter)
                val detail = ToolGetSkillByName({ catalog.catalog }, RuntimePassThroughToolsFilter, mockk())
                val categories = ToolGetSkillsByCategory(detail, names)
                val result = categories.invoke(LLMResponse.FunctionCall("GetSkillsByCategory",
                    mapOf("category" to "APPLICATIONS")), ToolInvocationMeta(userId = "owner"))
                val json = restJsonMapper.readTree(result.content)
                assertEquals(2, json.path("skills").size())
                assertEquals(2, json.path("nextOffset").asInt())
                assertTrue(result.content.toByteArray(Charsets.UTF_8).size < 16_384)
            } finally { catalog.close() }
        }
    }

    companion object { private const val URL = "https://mcp.example.test/mcp" }
}
