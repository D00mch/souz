package ru.souz.agent

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineScope
import ru.souz.SkillsGraphBasedAgent
import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.agent.nodes.NodesCommon
import ru.souz.agent.nodes.NodesErrorHandling
import ru.souz.agent.nodes.NodesLLM
import ru.souz.agent.nodes.NodesMemory
import ru.souz.agent.nodes.NodesSkillInventory
import ru.souz.agent.nodes.NodesSummarization
import ru.souz.agent.nodes.NodesToolUseWithKnowledge
import ru.souz.agent.runtime.AgentToolExecutor
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
import ru.souz.llms.LLMToolSetup
import ru.souz.memory.ConversationMemoryRuntime
import ru.souz.memory.NoopConversationMemoryRuntime

class AgentExecutionKernel(
    val contextFactory: AgentContextFactory,
    val executor: AgentExecutor,
)

class AgentExecutionKernelFactory(
    private val logObjectMapper: ObjectMapper,
    private val settingsProvider: AgentSettingsProvider,
    private val desktopInfoRepository: AgentDesktopInfoRepository,
    private val toolCatalog: AgentToolCatalog,
    private val toolsFilter: AgentToolsFilter,
    private val skillBundleProvider: SkillBundleProvider,
    private val defaultBrowserProvider: DefaultBrowserProvider,
    private val runtimeEnvironment: AgentRuntimeEnvironment,
    private val getSkillByNameTool: LLMToolSetup,
    private val getSkillsByCategoryTool: LLMToolSetup,
    private val getSkillsNamesByCategoryTool: LLMToolSetup,
    private val getKnowledgeTool: LLMToolSetup,
    private val searchKnowledgeTool: LLMToolSetup,
    private val searchMemoryTool: LLMToolSetup,
    private val runtimeCommandTool: LLMToolSetup,
    private val knowledgeStore: ConversationKnowledgeStore?,
    private val telemetry: AgentTelemetry,
    private val errorMessages: AgentErrorMessages,
    private val llmApi: LLMChatAPI,
    private val memoryRuntime: ConversationMemoryRuntime = NoopConversationMemoryRuntime,
    private val captureScope: CoroutineScope,
) {
    fun create(): AgentExecutionKernel {
        val agentToolExecutor = AgentToolExecutor(telemetry)
        val nodesCommon = NodesCommon(
            desktopInfoRepository = desktopInfoRepository,
            settingsProvider = settingsProvider,
            agentToolExecutor = agentToolExecutor,
            defaultBrowserProvider = defaultBrowserProvider,
            runtimeEnvironment = runtimeEnvironment,
        )
        val nodesSkillInventory = NodesSkillInventory(
            toolCatalog = toolCatalog,
            toolsFilter = toolsFilter,
            skillBundleProvider = skillBundleProvider,
        )
        val nodesToolUseWithKnowledge = NodesToolUseWithKnowledge(
            nodesCommon = nodesCommon,
            knowledgeStore = knowledgeStore,
        )
        val nodesMemory = NodesMemory(memoryRuntime = memoryRuntime, captureScope = captureScope)
        val nodesLLM = NodesLLM(llmApi = llmApi, settingsProvider = settingsProvider)
        val nodesErrorHandling = NodesErrorHandling(errorMessages)
        val nodesSummarization = NodesSummarization(llmApi = llmApi, nodesCommon = nodesCommon)
        val coreTools = AgentCoreTools(
            getSkillByName = getSkillByNameTool,
            getSkillsByCategory = getSkillsByCategoryTool,
            getSkillsNamesByCategory = getSkillsNamesByCategoryTool,
            getKnowledge = getKnowledgeTool,
            searchKnowledge = searchKnowledgeTool,
            searchMemory = searchMemoryTool,
            runtimeCommand = runtimeCommandTool,
        )
        val availableAgents = listOf(AgentId.SKILLS_GRAPH)
        val contextFactory = AgentContextFactory(
            settingsProvider = settingsProvider,
            systemPromptResolver = SystemPromptResolver(),
            toolCatalog = toolCatalog,
            availableAgents = availableAgents,
        )
        val skillsGraphAgent = SkillsGraphBasedAgent(
            logObjectMapper = logObjectMapper,
            nodesLLM = nodesLLM,
            nodesCommon = nodesCommon,
            nodesErrorHandling = nodesErrorHandling,
            nodesSummarization = nodesSummarization,
            nodesMemory = nodesMemory,
            nodesSkillInventory = nodesSkillInventory,
            nodesToolUseWithKnowledge = nodesToolUseWithKnowledge,
            coreTools = coreTools,
        )
        val executor = AgentExecutor(
            agentProvider = { skillsGraphAgent },
            availableAgents = availableAgents,
        )
        return AgentExecutionKernel(
            contextFactory = contextFactory,
            executor = executor,
        )
    }
}
