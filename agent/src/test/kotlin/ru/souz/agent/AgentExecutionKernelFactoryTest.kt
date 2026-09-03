package ru.souz.agent

import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import ru.souz.agent.skills.registry.SkillBundleProvider
import ru.souz.agent.spi.AgentDesktopInfoRepository
import ru.souz.agent.spi.AgentErrorMessages
import ru.souz.agent.spi.AgentRuntimeEnvironment
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.agent.spi.DefaultBrowserProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.restJsonMapper
import ru.souz.tool.ToolCategory
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentExecutionKernelFactoryTest {
    @Test
    fun `request scoped kernel exposes one steerable graph and normalizes unsupported agent ids`() = runTest {
        val kernel = AgentExecutionKernelFactory(
            logObjectMapper = restJsonMapper,
            settingsProvider = mockk<AgentSettingsProvider>(relaxed = true),
            desktopInfoRepository = mockk<AgentDesktopInfoRepository>(relaxed = true),
            toolCatalog = EmptyToolCatalog,
            toolsFilter = PassThroughToolsFilter,
            skillBundleProvider = mockk<SkillBundleProvider>(relaxed = true),
            defaultBrowserProvider = mockk<DefaultBrowserProvider>(relaxed = true),
            runtimeEnvironment = mockk<AgentRuntimeEnvironment>(relaxed = true),
            getSkillByNameTool = coreTool("GetSkillByName"),
            getSkillsByCategoryTool = coreTool("GetSkillsByCategory"),
            getSkillsNamesByCategoryTool = coreTool("GetSkillsNamesByCategory"),
            getKnowledgeTool = coreTool("GetKnowledge"),
            searchKnowledgeTool = coreTool("SearchKnowledge"),
            searchMemoryTool = coreTool("SearchMemory"),
            runtimeCommandTool = coreTool("RunSkillCommand"),
            knowledgeStore = null,
            telemetry = AgentTelemetry.NONE,
            errorMessages = mockk<AgentErrorMessages>(relaxed = true),
            llmApi = mockk<LLMChatAPI>(relaxed = true),
            captureScope = backgroundScope,
        ).create()

        assertEquals(listOf(AgentId.SKILLS_GRAPH), kernel.contextFactory.availableAgents)
        assertEquals(AgentId.SKILLS_GRAPH, kernel.contextFactory.normalizeAgentId(AgentId.GRAPH))
    }
}

private object EmptyToolCatalog : AgentToolCatalog {
    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> = emptyMap()
}

private object PassThroughToolsFilter : AgentToolsFilter {
    override fun applyFilter(
        toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>,
    ): Map<ToolCategory, Map<String, LLMToolSetup>> = toolsByCategory
}

private fun coreTool(name: String): LLMToolSetup = object : LLMToolSetup {
    override val fn = LLMRequest.Function(
        name = name,
        description = name,
        parameters = LLMRequest.Parameters(type = "object"),
    )

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
        LLMRequest.Message(
            role = LLMMessageRole.function,
            content = "{}",
            name = functionCall.name,
        )
}
