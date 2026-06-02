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
    fun `obvious read only and dangerous tools get mapped risks`() = runTest {
        val provider = AgentToolAmbientCapabilityProvider(
            toolCatalog = FakeToolCatalog(
                mapOf(
                    ToolCategory.FILES to mapOf(
                        "list_files" to fakeTool("list_files", "List files"),
                        "modify_file" to fakeTool("modify_file", "Modify files"),
                    ),
                    ToolCategory.MAIL to mapOf(
                        "send_mail" to fakeTool("send_mail", "Send mail"),
                    ),
                    ToolCategory.CALCULATOR to mapOf(
                        "calculate" to fakeTool("calculate", "Calculate"),
                    ),
                )
            ),
            toolsFilter = FakeAgentToolsFilter { it },
        )

        val byId = provider.capabilities().associateBy { it.id }

        assertEquals(AmbientCapabilityRisk.LOW, byId.getValue("tool:FILES:list_files").risk)
        assertEquals(AmbientCapabilityRisk.HIGH, byId.getValue("tool:FILES:modify_file").risk)
        assertEquals(AmbientCapabilityRisk.HIGH, byId.getValue("tool:MAIL:send_mail").risk)
        assertEquals(AmbientCapabilityRisk.LOW, byId.getValue("tool:CALCULATOR:calculate").risk)
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
    fun `default manifest renderer uses compact prompt budget`() = runTest {
        val capabilities = List(40) { index ->
            AmbientCapability(
                id = "tool:FILES:T$index",
                kind = AmbientCapabilityKind.TOOL,
                category = "FILES",
                name = "T$index",
                description = "description ".repeat(40),
                examples = listOf("пример ".repeat(40)),
            )
        }

        val manifest = AmbientCapabilityManifestRenderer().render(capabilities)

        assertTrue(manifest.length <= 900)
        assertTrue(manifest.lines().all { line -> line.isNotBlank() })
        assertTrue(manifest.lines().all { line -> line.startsWith("tool|") || line.startsWith("detail|") })
    }

    @Test
    fun `manifest renderer adds heard to task examples for available action categories`() = runTest {
        val manifest = AmbientCapabilityManifestRenderer(maxChars = 1_500).render(
            listOf(
                capability(
                    id = "tool:CALENDAR:CalendarCreateEvent",
                    category = "CALENDAR",
                    name = "CalendarCreateEvent",
                    description = "Create an event in macOS Calendar.",
                ),
                capability(
                    id = "tool:WEB_SEARCH:InternetSearch",
                    category = "WEB_SEARCH",
                    name = "InternetSearch",
                    description = "Short factual internet lookup for current facts and weather.",
                ),
            )
        )

        assertTrue(manifest.contains("example|"))
        assertTrue(manifest.contains("надо поставить в шесть встречу"))
        assertTrue(manifest.contains("какая погода в Москве"))
        assertTrue(manifest.contains("tool:CALENDAR:CalendarCreateEvent"))
        assertTrue(manifest.contains("tool:WEB_SEARCH:InternetSearch"))
    }

    @Test
    fun `manifest renderer keeps tool ids before examples under tight budget`() = runTest {
        val manifest = AmbientCapabilityManifestRenderer(maxChars = 320).render(
            listOf(
                capability(
                    id = "tool:CALENDAR:CalendarCreateEvent",
                    category = "CALENDAR",
                    name = "CalendarCreateEvent",
                    description = "Create an event in macOS Calendar.",
                ),
                capability(
                    id = "tool:WEB_SEARCH:InternetSearch",
                    category = "WEB_SEARCH",
                    name = "InternetSearch",
                    description = "Short factual internet lookup for current facts and weather.",
                ),
                capability(
                    id = "tool:NOTES:CreateNote",
                    category = "NOTES",
                    name = "CreateNote",
                    description = "Create note.",
                ),
            )
        )

        val lines = manifest.lines()
        assertTrue(lines.take(3).all { it.startsWith("tool|") })
        assertTrue(manifest.contains("tool:WEB_SEARCH:InternetSearch"))
    }

    @Test
    fun `manifest renderer includes compact descriptions and tool few shot examples`() = runTest {
        val manifest = AmbientCapabilityManifestRenderer(maxChars = 900).render(
            listOf(
                capability(
                    id = "tool:CALENDAR:CalendarListEvents",
                    category = "CALENDAR",
                    name = "CalendarListEvents",
                    description = "List events from a specific calendar for a specific date.",
                    examples = listOf("Какие встречи у меня сегодня?"),
                ),
            )
        )

        assertTrue(manifest.contains("detail|tool:CALENDAR:CalendarListEvents|"))
        assertTrue(manifest.contains("List events from a specific calendar"))
        assertTrue(manifest.contains("Какие встречи у меня сегодня?"))
    }

    @Test
    fun `manifest renderer keeps only complete capability lines when truncated`() = runTest {
        val manifest = AmbientCapabilityManifestRenderer(maxChars = 55).render(
            listOf(
                capability(id = "tool:FILES:FirstTool", name = "FirstTool"),
                capability(id = "tool:FILES:SecondTool", name = "SecondTool"),
            )
        )

        assertEquals("tool|tool:FILES:FirstTool|FirstTool", manifest)
        assertFalse(manifest.contains("Second"))
    }

    @Test
    fun `manifest renderer prioritizes common ambient action categories`() = runTest {
        val manifest = AmbientCapabilityManifestRenderer(maxChars = 80).render(
            listOf(
                capability(id = "tool:FILES:ListFiles", category = "FILES", name = "ListFiles"),
                capability(id = "tool:CALENDAR:CalendarListEvents", category = "CALENDAR", name = "CalendarListEvents"),
            )
        )

        assertTrue(manifest.contains("tool:CALENDAR:CalendarListEvents"))
        assertFalse(manifest.contains("tool:FILES:ListFiles"))
    }

    @Test
    fun `empty skill provider does not crash`() = runTest {
        assertEquals(emptyList(), EmptyAmbientSkillCapabilityProvider.capabilities())
    }

    private fun capability(
        id: String,
        category: String = "FILES",
        name: String,
        description: String = "description",
        examples: List<String> = emptyList(),
    ): AmbientCapability = AmbientCapability(
        id = id,
        kind = AmbientCapabilityKind.TOOL,
        category = category,
        name = name,
        description = description,
        examples = examples,
    )

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
