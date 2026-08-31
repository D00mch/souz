package ru.souz.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup

class ToolCatalogsTest {
    @Test
    fun `composition follows category declaration order and rejects duplicate names`() {
        val first = immutableToolCatalogFromLists(
            mapOf(ToolCategory.FILES to listOf(tool("SameName")))
        )
        val second = immutableToolCatalogFromLists(
            mapOf(ToolCategory.WEB_SEARCH to listOf(tool("SameName")))
        )

        val error = assertFailsWith<IllegalStateException> {
            composeToolCatalogs(first, second)
        }

        assertEquals(ToolCategory.entries, first.toolsByCategory.keys.toList())
        assertEquals(
            "Duplicate tool name 'SameName' in categories FILES and WEB_SEARCH.",
            error.message,
        )
    }

    @Test
    fun `explicit later-source precedence replaces the earlier tool`() {
        val compiled = tool("ClientOwned")
        val client = tool("ClientOwned")
        val catalog = composeToolCatalogs(
            immutableToolCatalogFromLists(mapOf(ToolCategory.FILES to listOf(compiled))),
            immutableToolCatalogFromLists(mapOf(ToolCategory.DESKTOP to listOf(client))),
            allowLaterSourceOverrides = true,
        )

        assertEquals(emptyMap(), catalog.toolsByCategory.getValue(ToolCategory.FILES))
        assertSame(client, catalog.toolsByCategory.getValue(ToolCategory.DESKTOP).getValue("ClientOwned"))
    }

    @Test
    fun `later-source precedence still rejects duplicates within one source`() {
        val duplicateSource = object : ru.souz.agent.spi.AgentToolCatalog {
            override val toolsByCategory = mapOf(
                ToolCategory.FILES to mapOf("Duplicate" to tool("Duplicate")),
                ToolCategory.DESKTOP to mapOf("Duplicate" to tool("Duplicate")),
            )
        }

        val error = assertFailsWith<IllegalArgumentException> {
            composeToolCatalogs(
                duplicateSource,
                allowLaterSourceOverrides = true,
            )
        }

        assertEquals(
            "Duplicate tool name 'Duplicate' in one source across categories FILES and DESKTOP.",
            error.message,
        )
    }

    @Test
    fun `catalog snapshots cannot be mutated`() {
        val catalog = immutableToolCatalogFromLists(
            mapOf(ToolCategory.FILES to listOf(tool("Immutable")))
        )

        @Suppress("UNCHECKED_CAST")
        val categories = catalog.toolsByCategory as MutableMap<ToolCategory, Map<String, LLMToolSetup>>
        @Suppress("UNCHECKED_CAST")
        val files = catalog.toolsByCategory.getValue(ToolCategory.FILES) as MutableMap<String, LLMToolSetup>

        assertFailsWith<UnsupportedOperationException> { categories.clear() }
        assertFailsWith<UnsupportedOperationException> { files.clear() }
    }
}

private fun tool(name: String): LLMToolSetup = object : LLMToolSetup {
    override val fn = LLMRequest.Function(name = name)

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
        LLMRequest.Message(LLMMessageRole.function, "ok", name = name)
}
