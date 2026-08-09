package ru.souz.agent

import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import ru.souz.agent.spi.AgentDesktopInfoRepository
import ru.souz.agent.spi.AgentErrorMessages
import ru.souz.agent.spi.AgentRuntimeEnvironment
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.spi.DefaultBrowserProvider
import ru.souz.agent.spi.McpToolProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.restJsonMapper
import ru.souz.tool.ToolCategory
import ru.souz.tool.UserMessageClassifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentExecutionKernelFactoryTest {
    @Test
    fun `request scoped kernel exposes one steerable graph and normalizes legacy agent ids`() = runTest {
        val kernel = AgentExecutionKernelFactory(
            logObjectMapper = restJsonMapper,
            settingsProvider = mockk<AgentSettingsProvider>(relaxed = true),
            desktopInfoRepository = mockk<AgentDesktopInfoRepository>(relaxed = true),
            toolCatalog = EmptyToolCatalog,
            toolsFilter = PassThroughToolsFilter,
            defaultBrowserProvider = mockk<DefaultBrowserProvider>(relaxed = true),
            runtimeEnvironment = mockk<AgentRuntimeEnvironment>(relaxed = true),
            mcpToolProvider = mockk<McpToolProvider>(relaxed = true),
            telemetry = AgentTelemetry.NONE,
            errorMessages = mockk<AgentErrorMessages>(relaxed = true),
            llmApi = mockk<LLMChatAPI>(relaxed = true),
            apiClassifier = mockk<UserMessageClassifier>(relaxed = true),
            localClassifier = mockk<UserMessageClassifier>(relaxed = true),
            captureScope = backgroundScope,
        ).create()

        assertEquals(listOf(AgentId.GRAPH), kernel.contextFactory.availableAgents)
        assertEquals(AgentId.GRAPH, kernel.contextFactory.normalizeAgentId(AgentId.SKILLS_GRAPH))
        assertTrue(kernel.executor.supportsActiveRunInput(AgentId.GRAPH))
        assertTrue(kernel.executor.supportsActiveRunInput(AgentId.SKILLS_GRAPH))
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
