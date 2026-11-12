package ru.souz.tool.skills

import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.restJsonMapper
import ru.souz.tool.RuntimePassThroughToolsFilter
import ru.souz.tool.ToolCategory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CategoryDiscoveryTest {
    @Test
    fun `category validation is shared and rejects invalid names before reading the catalogue`() = runTest {
        val provider = { error("Invalid categories must not read the catalogue") }
        val names = ToolGetSkillsNamesByCategory(provider, RuntimePassThroughToolsFilter)
        val details = ToolGetSkillsByCategory(provider, RuntimePassThroughToolsFilter)
        for ((category, code) in listOf(" " to "invalid_category", "unknown" to "category_not_found")) {
            assertEquals(code, page(names, category).path("error").path("code").asText())
            assertEquals(code, page(details, category).path("errors")[0].path("code").asText())
        }
    }

    @Test
    fun `detail pages preserve local errors sorting byte limits and oversized entry continuation`() = runTest {
        val catalog = AgentToolCatalog(mapOf(ToolCategory.FILES to listOf(
            tool("z-good"),
            tool("e-medium", ::mediumSchema),
            tool("d-medium", ::mediumSchema),
            tool("c-medium", ::mediumSchema),
            tool("b-large") { restJsonMapper.readTree("""{"description":"${"x".repeat(DISCOVERY_PAGE_BYTES)}"}""") },
            tool("a-broken") { error("Broken schema") },
        )))
        var snapshots = 0
        val details = ToolGetSkillsByCategory({ snapshots++; catalog }, RuntimePassThroughToolsFilter)
        val first = page(details, " files ")
        assertEquals("FILES", first.path("category").asText())
        assertEquals("a-broken", first.path("errors")[0].path("skillId").asText())
        assertEquals("skill_unavailable", first.path("errors")[0].path("code").asText())
        assertEquals(1, first.path("nextOffset").asInt())
        val second = page(details, "FILES", 1)
        assertEquals("skill_detail_too_large", second.path("errors")[0].path("code").asText())
        assertEquals(2, second.path("nextOffset").asInt())
        val middle = page(details, "FILES", 2)
        assertEquals(listOf("c-medium", "d-medium"), middle.path("skills").map { it.path("skillId").asText() })
        assertEquals(4, middle.path("nextOffset").asInt())
        val last = page(details, "FILES", 4)
        assertEquals(listOf("e-medium", "z-good"), last.path("skills").map { it.path("skillId").asText() })
        assertTrue(last.path("nextOffset").isNull)
        assertEquals(4, snapshots)
        for (response in listOf(first, second, middle, last)) {
            assertEquals(6, response.path("totalSkills").asInt())
            assertTrue(restJsonMapper.writeValueAsBytes(response).size <= DISCOVERY_PAGE_BYTES)
        }
    }

    @Test
    fun `schema cancellation propagates instead of becoming a per-entry error`() = runTest {
        val catalog = AgentToolCatalog(mapOf(ToolCategory.FILES to listOf(
            tool("cancel") { throw CancellationException("Cancelled lookup") },
        )))
        val details = ToolGetSkillsByCategory({ catalog }, RuntimePassThroughToolsFilter)
        assertFailsWith<CancellationException> { page(details, "FILES") }
    }

    private fun mediumSchema() = restJsonMapper.createObjectNode().put("description", "x".repeat(6_000))

    private suspend fun page(tool: LLMToolSetup, category: String, offset: Int = 0): JsonNode =
        restJsonMapper.readTree(tool.invoke(LLMResponse.FunctionCall(
            tool.fn.name, mapOf("category" to category, "offset" to offset),
        )).content)

    private fun tool(name: String, schema: () -> JsonNode = { restJsonMapper.createObjectNode() }): LLMToolSetup =
        object : LLMToolSetup, SkillToolSchemas {
            override val fn = LLMRequest.Function(name = name)
            override val skillInputSchema: JsonNode get() = schema()
            override val skillReturnSchema: JsonNode? = null
            override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
                error("Discovery must not invoke tools")
        }
}
