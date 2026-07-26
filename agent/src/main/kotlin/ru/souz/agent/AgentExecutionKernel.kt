package ru.souz.agent

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineScope
import ru.souz.GraphBasedAgent
import ru.souz.SkillsGraphBasedAgent
import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.agent.nodes.NodesClassification
import ru.souz.agent.nodes.NodesCommon
import ru.souz.agent.nodes.NodesErrorHandling
import ru.souz.agent.nodes.NodesLLM
import ru.souz.agent.nodes.NodesMCP
import ru.souz.agent.nodes.NodesMemory
import ru.souz.agent.nodes.NodesSkills
import ru.souz.agent.nodes.NodesSummarization
import ru.souz.agent.runtime.AgentToolExecutor
import ru.souz.agent.skills.SkillActivationPipeline
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.spi.AgentDesktopInfoRepository
import ru.souz.agent.spi.AgentErrorMessages
import ru.souz.agent.spi.AgentRuntimeEnvironment
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.agent.spi.DefaultBrowserProvider
import ru.souz.agent.spi.McpToolProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.json.JsonUtils
import ru.souz.llms.restJsonMapper
import ru.souz.memory.ConversationMemoryRuntime
import ru.souz.memory.NoopConversationMemoryRuntime
import ru.souz.tool.UserMessageClassifier

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
    private val defaultBrowserProvider: DefaultBrowserProvider,
    private val runtimeEnvironment: AgentRuntimeEnvironment,
    private val mcpToolProvider: McpToolProvider,
    private val skillCommandTool: LLMToolSetup? = null,
    private val getSkillsTool: LLMToolSetup,
    private val getKnowledgeTool: LLMToolSetup,
    private val runtimeCommandTool: LLMToolSetup,
    private val knowledgeStore: ConversationKnowledgeStore,
    private val telemetry: AgentTelemetry,
    private val errorMessages: AgentErrorMessages,
    private val llmApi: LLMChatAPI,
    private val apiClassifier: UserMessageClassifier,
    private val localClassifier: UserMessageClassifier,
    private val skillRegistryRepository: SkillRegistryRepository,
    private val memoryRuntime: ConversationMemoryRuntime = NoopConversationMemoryRuntime,
    private val captureScope: CoroutineScope,
) {
    fun create(): AgentExecutionKernel {
        val agentToolExecutor = AgentToolExecutor(telemetry)
        val jsonUtils = JsonUtils(restJsonMapper)
        val nodesCommon = NodesCommon(
            desktopInfoRepository = desktopInfoRepository,
            settingsProvider = settingsProvider,
            agentToolExecutor = agentToolExecutor,
            defaultBrowserProvider = defaultBrowserProvider,
            runtimeEnvironment = runtimeEnvironment,
            knowledgeStore = knowledgeStore,
        )
        val nodesMemory = NodesMemory(memoryRuntime = memoryRuntime, captureScope = captureScope)
        val nodesClassification = NodesClassification(
            settingsProvider = settingsProvider,
            logObjectMapper = logObjectMapper,
            apiClassifier = apiClassifier,
            localClassifier = localClassifier,
            toolCatalog = toolCatalog,
            toolsFilter = toolsFilter,
        )
        val nodesLLM = NodesLLM(llmApi = llmApi, settingsProvider = settingsProvider)
        val nodesErrorHandling = NodesErrorHandling(errorMessages)
        val nodesMcp = NodesMCP(mcpToolProvider)
        val nodesSkills = NodesSkills(
            pipeline = SkillActivationPipeline.from(
                registryRepository = skillRegistryRepository,
                llmApi = llmApi,
                settingsProvider = settingsProvider,
                jsonUtils = jsonUtils,
            ),
            skillCommandTool = skillCommandTool,
        )
        val nodesSummarization = NodesSummarization(llmApi = llmApi, nodesCommon = nodesCommon)
        val contextFactory = AgentContextFactory(
            settingsProvider = settingsProvider,
            systemPromptResolver = SystemPromptResolver(),
            toolCatalog = toolCatalog,
        )
        val graphAgent = GraphBasedAgent(
            logObjectMapper = logObjectMapper,
            nodesLLM = nodesLLM,
            nodesCommon = nodesCommon,
            nodesClassify = nodesClassification,
            nodesErrorHandling = nodesErrorHandling,
            nodesSummarization = nodesSummarization,
            nodesMCP = nodesMcp,
            nodesSkills = nodesSkills,
            nodesMemory = nodesMemory,
        )
        val skillsGraphAgent = SkillsGraphBasedAgent(
            logObjectMapper = logObjectMapper,
            nodesLLM = nodesLLM,
            nodesCommon = nodesCommon,
            nodesErrorHandling = nodesErrorHandling,
            nodesSummarization = nodesSummarization,
            nodesMemory = nodesMemory,
            getSkillsTool = getSkillsTool,
            getKnowledgeTool = getKnowledgeTool,
            runtimeCommandTool = runtimeCommandTool,
        )
        val executor = AgentExecutor(
            agentProvider = { agentId ->
                when (agentId) {
                    AgentId.GRAPH -> graphAgent
                    AgentId.SKILLS_GRAPH -> skillsGraphAgent
                }
            },
        )
        return AgentExecutionKernel(
            contextFactory = contextFactory,
            executor = executor,
        )
    }
}
