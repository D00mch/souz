package ru.souz.mcp

import com.fasterxml.jackson.module.kotlin.readValue
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
import ru.souz.tool.skills.ToolGetSkillsByCategory
import ru.souz.tool.skills.ToolGetSkillsNamesByCategory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Timeout(15)
class McpSkillDiscoveryTest {
    @Test
    fun `mixed category pages use one snapshot and continue beyond the MCP tool count limit`() = runBlocking {
        val server = McpTestServer { mcpTools(10_000) }
        val compiled = AgentToolCatalog(mapOf(ToolCategory.APPLICATIONS to (0 until 100).map { index ->
            object : LLMToolSetup {
                override val fn = LLMRequest.Function(name = "compiled$index")
                override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
                    error("Discovery must not invoke a tool.")
            }
        }))
        server.withCatalog("server") { mcp ->
            mcp.load("server")
            var reads = 0
            val provider = { reads++; AgentToolCatalog.compose(compiled, mcp.catalog) }
            val names = ToolGetSkillsNamesByCategory(provider, RuntimePassThroughToolsFilter)
            val details = ToolGetSkillsByCategory(provider, RuntimePassThroughToolsFilter)
            for ((tool, field) in listOf(names to "skillNames", details to "skills")) {
                suspend fun page(offset: Int, extra: Map<String, Any> = emptyMap()) = run {
                    val before = reads
                    val response = tool.callJson(mapOf("category" to "APPLICATIONS", "offset" to offset) + extra)
                    assertEquals(before + 1, reads)
                    assertTrue(response.path("error").isMissingNode || response.path("error").isNull)
                    assertEquals(0, response.path("errors").size())
                    assertEquals(10_100, response.path("totalSkills").asInt())
                    response
                }
                for (offset in listOf(0, 20)) {
                    val response = page(offset)
                    assertEquals(20, response.path(field).size())
                    assertEquals(offset + 20, response.path("nextOffset").asInt())
                }
                val seen = mutableSetOf<String>()
                var offset: Int? = 10_000
                while (offset != null) {
                    val response = page(offset, mapOf("limit" to 50))
                    assertTrue(response.path(field).size() > 0)
                    response.path(field).forEach { entry ->
                        assertTrue(seen.add(if (entry.isTextual) entry.asText() else entry.path("skillId").asText()))
                    }
                    offset = response.path("nextOffset").takeUnless { it.isNull }?.asInt()
                }
                assertEquals(100, seen.size)
            }
            assertEquals(1, server.recorded("tools/list").size)
        }
    }

    @Test
    fun `MCP and category discovery reject malformed pagination before reading a catalogue`() = runBlocking {
        val server = McpTestServer { error("Invalid pagination must not contact the server.") }
        server.withCatalog("server") { mcp ->
            val provider = { error("Invalid pagination must not resolve a catalogue.") }
            val names = ToolGetSkillsNamesByCategory(provider, RuntimePassThroughToolsFilter)
            val details = ToolGetSkillsByCategory(provider, RuntimePassThroughToolsFilter)
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
            for (tool in listOf(mcp.discoveryTool, names, details)) {
                for (arguments in invalid) {
                    val response = tool.callJson(arguments)
                    val error = if (tool === details) response.path("errors")[0] else response.path("error")
                    assertEquals(
                        if (tool === mcp.discoveryTool) "mcp_invalid_arguments" else "categories_unavailable",
                        error.path("code").asText(), "${tool.fn.name}: $arguments",
                    )
                }
            }
            assertTrue(server.recorded().isEmpty())
        }
    }

}
