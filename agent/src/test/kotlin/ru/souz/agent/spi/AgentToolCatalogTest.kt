package ru.souz.agent.spi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.tool.ToolCategory

class AgentToolCatalogTest {
    @Test
    fun `construction copies input collections and preserves category and tool order`() {
        val first = tool("First")
        val files = mutableListOf(first, tool("Second"))
        val input = linkedMapOf(
            ToolCategory.WEB_SEARCH to mutableListOf(tool("Search")),
            ToolCategory.FILES to files,
        )
        val catalog = AgentToolCatalog(input)
        files.clear()
        input.clear()

        assertEquals(ToolCategory.entries, catalog.toolsByCategory.keys.toList())
        assertEquals(listOf("First", "Second"), catalog.toolsByCategory.getValue(ToolCategory.FILES).keys.toList())
        assertSame(first, catalog.toolsByCategory.getValue(ToolCategory.FILES).getValue("First"))
        assertEquals(setOf("Search"), catalog.toolsByCategory.getValue(ToolCategory.WEB_SEARCH).keys)
    }

    @Test
    fun `construction and composition reject duplicate names within and across categories`() {
        for (category in listOf(ToolCategory.FILES, ToolCategory.WEB_SEARCH)) {
            val first = AgentToolCatalog(mapOf(ToolCategory.FILES to listOf(tool("SameName"))))
            val second = AgentToolCatalog(mapOf(category to listOf(tool("SameName"))))
            val composedError = assertFailsWith<IllegalArgumentException> { AgentToolCatalog.compose(first, second) }
            val input = linkedMapOf<ToolCategory, MutableList<LLMToolSetup>>()
            input.getOrPut(ToolCategory.FILES) { mutableListOf() }.add(tool("SameName"))
            input.getOrPut(category) { mutableListOf() }.add(tool("SameName"))
            val constructedError = assertFailsWith<IllegalArgumentException> { AgentToolCatalog(input) }

            assertEquals("Duplicate tool name 'SameName' in categories FILES and $category.", composedError.message)
            assertEquals(composedError.message, constructedError.message)
        }
    }

    @Test
    fun `composition retains source order within each category`() {
        val first = AgentToolCatalog(mapOf(ToolCategory.FILES to listOf(tool("First"), tool("Second"))))
        val second = AgentToolCatalog(mapOf(ToolCategory.FILES to listOf(tool("Third"))))
        val composed = AgentToolCatalog.compose(first, second)

        assertEquals(listOf("First", "Second", "Third"), composed.toolsByCategory.getValue(ToolCategory.FILES).keys.toList())
        assertEquals(listOf("First", "Second"), first.toolsByCategory.getValue(ToolCategory.FILES).keys.toList())
    }

    @Test
    fun `filter and transformation preserve the source and use transformed function names`() {
        val source = AgentToolCatalog(mapOf(
            ToolCategory.FILES to listOf(tool("Read"), tool("Write")),
            ToolCategory.WEB_SEARCH to listOf(tool("Search")),
        ))
        val selected = source.filter { category, tool -> category == ToolCategory.FILES && tool.fn.name == "Read" }
        val transformed = selected.mapTools { category, tool ->
            assertEquals(ToolCategory.FILES, category)
            tool.renamed("ReadSelected")
        }

        assertEquals(setOf("Read", "Write"), source.toolsByCategory.getValue(ToolCategory.FILES).keys)
        assertEquals(setOf("Read"), selected.toolsByCategory.getValue(ToolCategory.FILES).keys)
        assertEquals(setOf("ReadSelected"), transformed.toolsByCategory.getValue(ToolCategory.FILES).keys)
        assertEquals("ReadSelected", transformed.toolsByCategory.getValue(ToolCategory.FILES).getValue("ReadSelected").fn.name)
        assertEquals(emptyMap(), transformed.toolsByCategory.getValue(ToolCategory.WEB_SEARCH))
    }

    @Test
    fun `transformation rejects duplicate function names`() {
        val source = AgentToolCatalog(mapOf(
            ToolCategory.FILES to listOf(tool("Read")),
            ToolCategory.WEB_SEARCH to listOf(tool("Search")),
        ))

        assertFailsWith<IllegalArgumentException> { source.mapTools { _, tool -> tool.renamed("Duplicate") } }
    }

    @Test
    fun `empty constructed and derived catalog maps cannot be mutated`() {
        val source = AgentToolCatalog(mapOf(ToolCategory.FILES to listOf(tool("Immutable"))))
        val catalogs = listOf(
            AgentToolCatalog(), source, source.filter { _, _ -> true },
            source.mapTools { _, tool -> tool.renamed("Mapped") }, AgentToolCatalog.compose(source),
        )
        catalogs.forEach { catalog ->
            assertEquals(ToolCategory.entries, catalog.toolsByCategory.keys.toList())
            @Suppress("UNCHECKED_CAST")
            val categories = catalog.toolsByCategory as MutableMap<ToolCategory, Map<String, LLMToolSetup>>
            assertFailsWith<UnsupportedOperationException> { categories.clear() }
            catalog.toolsByCategory.values.forEach { tools ->
                @Suppress("UNCHECKED_CAST")
                val mutableTools = tools as MutableMap<String, LLMToolSetup>
                assertFailsWith<UnsupportedOperationException> { mutableTools.clear() }
            }
        }
    }
}

private fun tool(name: String): LLMToolSetup = object : LLMToolSetup {
    override val fn = LLMRequest.Function(name = name)

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
        LLMRequest.Message(LLMMessageRole.function, "ok", name = name)
}

private fun LLMToolSetup.renamed(name: String): LLMToolSetup = object : LLMToolSetup by this {
    override val fn = this@renamed.fn.copy(name = name)
}
