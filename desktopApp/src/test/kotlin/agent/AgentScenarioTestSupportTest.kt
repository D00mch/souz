package agent

import kotlinx.coroutines.test.runTest
import org.kodein.di.direct
import org.kodein.di.instance
import ru.souz.agent.AgentContextFactory
import ru.souz.agent.AgentId
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.agent.spi.DefaultBrowserProvider
import ru.souz.agent.spi.McpToolProvider
import ru.souz.agent.spi.SkillToolBindingTags
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.memory.ConversationMemoryRuntime
import ru.souz.memory.NoopConversationMemoryRuntime
import ru.souz.tool.RuntimePassThroughToolsFilter
import ru.souz.tool.ToolCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AgentScenarioTestSupportTest {
    @Test
    fun `scenario catalog exposes only declared mocks and delegates invocation`() = runTest {
        val productionFileTool = RecordingTool("FindFilesByName", "production metadata")
        val productionBrowserTool = RecordingTool("CreateNewBrowserTab", "must stay unavailable")
        val mockedFileTool = RecordingTool("FindFilesByName", "mock metadata")
        val productionCatalog = catalog(
            ToolCategory.FILES to listOf(productionFileTool),
            ToolCategory.BROWSER to listOf(productionBrowserTool),
        )

        val scenarioCatalog = ScenarioAgentToolCatalog(
            productionCatalog = productionCatalog,
            mockedTools = listOf(mockedFileTool),
        )

        assertEquals(setOf(ToolCategory.FILES), scenarioCatalog.toolsByCategory.keys)
        val exposedTool = scenarioCatalog.toolsByCategory.getValue(ToolCategory.FILES).getValue("FindFilesByName")
        assertEquals(productionFileTool.fn, exposedTool.fn)
        assertFalse(
            scenarioCatalog.toolsByCategory.values.any { "CreateNewBrowserTab" in it },
            "Undeclared browser tools must not be discoverable or executable.",
        )

        val meta = ToolInvocationMeta(userId = "scenario-test")
        val result = exposedTool.invoke(
            LLMResponse.FunctionCall(name = "FindFilesByName", arguments = mapOf("fileName" to "report.txt")),
            meta,
        )

        assertEquals("mock result", result.content)
        assertEquals(1, mockedFileTool.invocations)
        assertEquals(meta, mockedFileTool.lastMeta)
        assertEquals(0, productionFileTool.invocations)
        assertEquals(0, productionBrowserTool.invocations)
    }

    @Test
    fun `scenario catalog rejects duplicate and unknown mock names`() {
        val productionCatalog = catalog(
            ToolCategory.FILES to listOf(RecordingTool("FindFilesByName", "production")),
        )

        assertFailsWith<IllegalArgumentException> {
            ScenarioAgentToolCatalog(
                productionCatalog = productionCatalog,
                mockedTools = listOf(
                    RecordingTool("FindFilesByName", "first"),
                    RecordingTool("FindFilesByName", "second"),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ScenarioAgentToolCatalog(
                productionCatalog = productionCatalog,
                mockedTools = listOf(RecordingTool("UnknownTool", "unknown")),
            )
        }
    }

    @Test
    fun `agent selector defaults to graph and accepts storage values case insensitively`() {
        assertEquals(AgentId.GRAPH, parseScenarioAgentId(null))
        assertEquals(AgentId.GRAPH, parseScenarioAgentId(" GRAPH "))
        assertEquals(AgentId.SKILLS_GRAPH, parseScenarioAgentId("SkIlLs"))
        assertFailsWith<IllegalStateException> { parseScenarioAgentId("other") }
    }

    @Test
    fun `scenario DI keeps skills and host integrations hermetic`() = runTest {
        val mockedFileTool = RecordingTool("FindFilesByName", "mock metadata")
        val di = AgentScenarioTestSupport(LLMModel.LocalGemma4_E4B_It)
            .createScenarioDi(listOf(mockedFileTool))

        val catalog: AgentToolCatalog = di.direct.instance()
        assertEquals(setOf(ToolCategory.FILES), catalog.toolsByCategory.keys)
        val exposedNames = catalog.toolsByCategory.values.flatMap { it.keys }
        assertEquals(listOf("FindFilesByName"), exposedNames)
        val contextFactory: AgentContextFactory = di.direct.instance()
        assertEquals(
            setOf("FindFilesByName"),
            contextFactory.create(AgentId.GRAPH).settings.tools.byName.keys,
        )

        val getSkillsNamesByCategory: LLMToolSetup = di.direct.instance(
            tag = SkillToolBindingTags.GET_SKILLS_NAMES_BY_CATEGORY_TOOL,
        )
        val meta = ToolInvocationMeta(userId = "scenario-test")
        val getSkillsNamesResult = getSkillsNamesByCategory.invoke(
            LLMResponse.FunctionCall(
                name = getSkillsNamesByCategory.fn.name,
                arguments = mapOf("category" to ToolCategory.FILES.name),
            ),
            meta,
        )
        val skillIds = restJsonMapper.readTree(getSkillsNamesResult.content)
            .path("skillNames")
            .map { it.asText() }
        assertEquals(listOf("FindFilesByName"), skillIds)

        val getSkillByName: LLMToolSetup = di.direct.instance(tag = SkillToolBindingTags.GET_SKILL_BY_NAME_TOOL)
        val getSkillResult = getSkillByName.invoke(
            LLMResponse.FunctionCall(
                name = getSkillByName.fn.name,
                arguments = mapOf("skillId" to "FindFilesByName"),
            ),
            meta,
        )
        assertEquals(
            "FindFilesByName",
            restJsonMapper.readTree(getSkillResult.content).path("skill").path("skillId").asText(),
        )

        val runSkill: LLMToolSetup = di.direct.instance(tag = SkillToolBindingTags.RUNTIME_COMMAND_TOOL)
        val runSkillResult = runSkill.invoke(
            LLMResponse.FunctionCall(
                name = runSkill.fn.name,
                arguments = mapOf(
                    "skillId" to "FindFilesByName",
                    "arguments" to mapOf("fileName" to "report.txt"),
                ),
            ),
            meta,
        )
        assertEquals("mock result", runSkillResult.content)
        assertEquals(1, mockedFileTool.invocations)

        val repository: SkillRegistryRepository = di.direct.instance()
        assertTrue(repository.listSkills(meta.userId).isEmpty())
        assertNull(repository.loadSkillBundle(meta.userId, SkillId("installed-browser-skill")))
        assertTrue(di.direct.instance<McpToolProvider>().tools().isEmpty())
        assertSame(NoopConversationMemoryRuntime, di.direct.instance<ConversationMemoryRuntime>())
        assertNull(di.direct.instance<DefaultBrowserProvider>().defaultBrowserDisplayName())

        val toolsFilter: AgentToolsFilter = di.direct.instance()
        assertSame(RuntimePassThroughToolsFilter, toolsFilter)
        assertSame(catalog.toolsByCategory, toolsFilter.applyFilter(catalog.toolsByCategory))
    }
}

private class RecordingTool(
    name: String,
    description: String,
) : LLMToolSetup {
    override val fn = LLMRequest.Function(
        name = name,
        description = description,
        parameters = LLMRequest.Parameters(
            type = "object",
            properties = mapOf("value" to LLMRequest.Property("string", "value")),
        ),
    )
    var invocations: Int = 0
    var lastMeta: ToolInvocationMeta? = null

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
        invoke(functionCall, ToolInvocationMeta.localDefault())

    override suspend fun invoke(
        functionCall: LLMResponse.FunctionCall,
        meta: ToolInvocationMeta,
    ): LLMRequest.Message {
        invocations += 1
        lastMeta = meta
        return LLMRequest.Message(
            role = LLMMessageRole.function,
            content = "mock result",
            name = functionCall.name,
        )
    }
}

private fun catalog(
    vararg categories: Pair<ToolCategory, List<LLMToolSetup>>,
): AgentToolCatalog = object : AgentToolCatalog {
    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> = categories.associate { (category, tools) ->
        category to tools.associateBy { it.fn.name }
    }
}
