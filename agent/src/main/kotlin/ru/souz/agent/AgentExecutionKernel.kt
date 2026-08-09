package ru.souz.agent

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineScope
import ru.souz.DirectToolGraphBasedAgent
import ru.souz.agent.nodes.NodesClassification
import ru.souz.agent.nodes.NodesCommon
import ru.souz.agent.nodes.NodesErrorHandling
import ru.souz.agent.nodes.NodesLLM
import ru.souz.agent.nodes.NodesMCP
import ru.souz.agent.nodes.NodesMemory
import ru.souz.agent.nodes.NodesSummarization
import ru.souz.agent.runtime.AgentToolExecutor
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
    alwaysAvailableToolNames: Set<String> = emptySet(),
    private val telemetry: AgentTelemetry,
    private val errorMessages: AgentErrorMessages,
    private val llmApi: LLMChatAPI,
    private val apiClassifier: UserMessageClassifier,
    private val localClassifier: UserMessageClassifier,
    private val memoryRuntime: ConversationMemoryRuntime = NoopConversationMemoryRuntime,
    private val captureScope: CoroutineScope,
) {
    private val alwaysAvailableToolNames = alwaysAvailableToolNames.toSet()

    fun create(): AgentExecutionKernel {
        val agentToolExecutor = AgentToolExecutor(telemetry)
        val nodesCommon = NodesCommon(
            desktopInfoRepository = desktopInfoRepository,
            settingsProvider = settingsProvider,
            agentToolExecutor = agentToolExecutor,
            defaultBrowserProvider = defaultBrowserProvider,
            runtimeEnvironment = runtimeEnvironment,
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
        val nodesSummarization = NodesSummarization(llmApi = llmApi, nodesCommon = nodesCommon)
        val contextFactory = AgentContextFactory(
            settingsProvider = settingsProvider,
            systemPromptResolver = SystemPromptResolver(),
            toolCatalog = toolCatalog,
            availableAgents = listOf(AgentId.GRAPH),
        )
        val graphAgent = DirectToolGraphBasedAgent(
            logObjectMapper = logObjectMapper,
            nodesLLM = nodesLLM,
            nodesCommon = nodesCommon,
            nodesClassify = nodesClassification,
            nodesErrorHandling = nodesErrorHandling,
            nodesSummarization = nodesSummarization,
            nodesMCP = nodesMcp,
            nodesMemory = nodesMemory,
            alwaysAvailableToolNames = alwaysAvailableToolNames,
        )
        val executor = AgentExecutor(
            agentProvider = { graphAgent },
            availableAgents = listOf(AgentId.GRAPH),
        )
        return AgentExecutionKernel(
            contextFactory = contextFactory,
            executor = executor,
        )
    }
}
