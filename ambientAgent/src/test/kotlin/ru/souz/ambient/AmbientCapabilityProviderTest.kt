package ru.souz.ambient

import kotlinx.coroutines.test.runTest
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.tool.ToolCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AmbientCapabilityProviderTest {

    @Test
    fun `tool catalog is filtered and no tools are executed`() = runTest {
        val visible = fakeTool("VisibleTool", "Visible description")
        val hidden = fakeTool("HiddenTool", "Hidden description")
        val provider = AgentToolAmbientCapabilityProvider(
            toolCatalog = FakeToolCatalog(
                mapOf(ToolCategory.FILES to mapOf("VisibleTool" to visible, "HiddenTool" to hidden))
            ),
            toolsFilter = FakeAgentToolsFilter { input ->
                mapOf(ToolCategory.FILES to input.getValue(ToolCategory.FILES).filterKeys { it == "VisibleTool" })
            },
        )

        val capabilities = provider.capabilities()

        assertEquals(listOf("tool:FILES:VisibleTool"), capabilities.map { it.id })
        assertFalse(visible.executed)
        assertFalse(hidden.executed)
    }

    @Test
    fun `capability ids are stable and metadata is truncated`() = runTest {
        val longDescription = "d".repeat(500)
        val provider = AgentToolAmbientCapabilityProvider(
            toolCatalog = FakeToolCatalog(
                mapOf(ToolCategory.CALENDAR to mapOf("CreateEvent" to fakeTool("CreateEvent", longDescription)))
            ),
            toolsFilter = FakeAgentToolsFilter { it },
        )

        val capability = provider.capabilities().single()

        assertEquals("tool:CALENDAR:CreateEvent", capability.id)
        assertEquals(AmbientCapabilityKind.TOOL, capability.kind)
        assertEquals("CALENDAR", capability.category)
        assertEquals("CreateEvent", capability.name)
        assertEquals(240, capability.description.length)
        assertEquals(2, capability.examples.size)
        assertTrue(capability.examples.all { it.length <= 160 })
    }

    @Test
    fun `manifest renderer keeps all ids in compact output`() = runTest {
        val capabilities = List(40) { index ->
            AmbientCapability(
                id = "tool:FILES:T$index",
                kind = AmbientCapabilityKind.TOOL,
                category = "FILES",
                name = "T$index",
                description = "description ".repeat(40),
            )
        }

        val manifest = AmbientCapabilityManifestRenderer(maxChars = 1_800).render(capabilities)

        assertTrue(manifest.length <= 1_800)
        capabilities.forEach { capability ->
            assertTrue(manifest.contains(capability.id), "Missing ${capability.id}")
        }
    }

    @Test
    fun `empty skill provider does not crash`() = runTest {
        assertEquals(emptyList(), EmptyAmbientSkillCapabilityProvider.capabilities())
    }

    private fun fakeTool(
        name: String,
        description: String,
    ): FakeTool = FakeTool(
        LLMRequest.Function(
            name = name,
            description = description,
            parameters = LLMRequest.Parameters(type = "object", properties = emptyMap()),
            fewShotExamples = listOf(
                LLMRequest.FewShotExample("пример один ".repeat(20), emptyMap()),
                LLMRequest.FewShotExample("пример два ".repeat(20), emptyMap()),
                LLMRequest.FewShotExample("пример три", emptyMap()),
            ),
        )
    )

    private class FakeTool(override val fn: LLMRequest.Function) : LLMToolSetup {
        var executed: Boolean = false

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message {
            executed = true
            error("Tool execution is not allowed")
        }

        override suspend fun invoke(
            functionCall: LLMResponse.FunctionCall,
            meta: ToolInvocationMeta,
        ): LLMRequest.Message {
            executed = true
            error("Tool execution is not allowed")
        }
    }

    private class FakeToolCatalog(
        override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>,
    ) : AgentToolCatalog

    private class FakeAgentToolsFilter(
        private val block: (Map<ToolCategory, Map<String, LLMToolSetup>>) -> Map<ToolCategory, Map<String, LLMToolSetup>>,
    ) : ru.souz.agent.spi.AgentToolsFilter {
        override fun applyFilter(
            toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>,
        ): Map<ToolCategory, Map<String, LLMToolSetup>> = block(toolsByCategory)
    }
}
