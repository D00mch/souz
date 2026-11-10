package ru.souz.mcp

import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.mockk
import java.math.BigDecimal
import java.math.BigInteger
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.tool.RuntimePassThroughToolsFilter
import ru.souz.tool.ToolCategory
import ru.souz.tool.skills.ToolGetSkillByName
import ru.souz.tool.skills.ToolGetSkillsByCategory
import ru.souz.tool.skills.ToolGetSkillsNamesByCategory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Timeout(15)
class McpSkillDiscoveryTest {
    @Test
    fun `mixed categories can page beyond the MCP tool count limit`() = runBlocking {
        val server = McpTestServer {
            restJsonMapper.createObjectNode().apply {
                val tools = putArray("tools")
                repeat(10_000) { index ->
                    tools.addObject().apply {
                        put("name", "remote$index")
                        putObject("inputSchema").put("type", "object")
                    }
                }
            }
        }
        val compiled = (0 until 100).map { index ->
            object : LLMToolSetup {
                override val fn = LLMRequest.Function(name = "compiled$index")
                override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
                    error("Discovery must not invoke a tool.")
            }
        }
        server.client.use { http ->
            val mcp = McpToolCatalog("owner", listOf(McpServerConfig("server", "Server", URL)), http)
            try {
                mcp.load("server")
                val catalog = AgentToolCatalog.compose(
                    AgentToolCatalog(mapOf(ToolCategory.APPLICATIONS to compiled)),
                    mcp.catalog,
                )
                val names = ToolGetSkillsNamesByCategory({ catalog }, RuntimePassThroughToolsFilter)
                val detail = ToolGetSkillByName({ catalog }, RuntimePassThroughToolsFilter, mockk())
                val categories = ToolGetSkillsByCategory(detail, names)
                for (tool in listOf(names, categories)) {
                    val seen = mutableSetOf<String>()
                    var offset: Int? = 10_000
                    while (offset != null) {
                        val response = restJsonMapper.readTree(tool.invoke(
                            LLMResponse.FunctionCall(tool.fn.name, mapOf(
                                "category" to "APPLICATIONS", "offset" to offset, "limit" to 50,
                            )),
                            ToolInvocationMeta(userId = "owner"),
                        ).content)
                        assertTrue(response.path("error").isMissingNode || response.path("error").isNull)
                        assertEquals(0, response.path("errors").size())
                        assertEquals(10_100, response.path("totalSkills").asInt())
                        val entries = response.path(if (tool === names) "skillNames" else "skills")
                        assertTrue(entries.size() > 0)
                        entries.forEach { entry ->
                            assertTrue(seen.add(if (entry.isTextual) entry.asText() else entry.path("skillId").asText()))
                        }
                        offset = response.path("nextOffset").takeUnless { it.isNull }?.asInt()
                    }
                    assertEquals(100, seen.size)
                }
                assertEquals(1, server.recorded().count { it.path("method").asText() == "tools/list" })
            } finally {
                mcp.close()
            }
        }
    }

    @Test
    fun `MCP and category discovery reject malformed pagination before reading a catalogue`() = runBlocking {
        val server = McpTestServer { error("Invalid pagination must not contact the server.") }
        server.client.use { http ->
            val mcp = McpToolCatalog("owner", listOf(McpServerConfig("server", "Server", URL)), http)
            val provider = { error("Invalid pagination must not resolve a catalogue.") }
            val names = ToolGetSkillsNamesByCategory(provider, RuntimePassThroughToolsFilter)
            val detail = ToolGetSkillByName(provider, RuntimePassThroughToolsFilter, mockk())
            val categories = ToolGetSkillsByCategory(detail, names)
            val invalid = listOf(
                "offset" to -1,
                "offset" to 1.9,
                "offset" to "2",
                "offset" to Long.MAX_VALUE,
                "offset" to BigInteger("18446744073709551616"),
                "offset" to BigDecimal("1.00000000000000000001"),
                "offset" to Double.NaN,
                "limit" to 0,
                "limit" to 51,
                "limit" to 1.9,
                "limit" to "2",
                "limit" to true,
                "limit" to Double.POSITIVE_INFINITY,
            ).map { (field, value) ->
                mapOf("serverId" to "server", "category" to "APPLICATIONS", field to value)
            } + listOf("offset", "limit").map { field ->
                restJsonMapper.readValue<Map<String, Any>>(
                    """{"serverId":"server","category":"APPLICATIONS","$field":null}"""
                )
            }
            try {
                for (tool in listOf(mcp.discoveryTool, names, categories)) {
                    for (arguments in invalid) {
                        val response = restJsonMapper.readTree(tool.invoke(
                            LLMResponse.FunctionCall(tool.fn.name, arguments),
                            ToolInvocationMeta(userId = "owner"),
                        ).content)
                        val code = if (tool === categories) response.path("errors")[0].path("code")
                            else response.path("error").path("code")
                        assertEquals(
                            if (tool === mcp.discoveryTool) "mcp_invalid_arguments" else "categories_unavailable",
                            code.asText(),
                            "${tool.fn.name}: $arguments",
                        )
                    }
                }
                assertTrue(server.recorded().isEmpty())
            } finally {
                mcp.close()
            }
        }
    }

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
