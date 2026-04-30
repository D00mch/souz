package ru.souz.agent

import com.fasterxml.jackson.databind.ObjectMapper
import ru.souz.GraphBasedAgent
import ru.souz.LuaGraphBasedAgent
import ru.souz.agent.nodes.NodesClassification
import ru.souz.agent.nodes.NodesCommon
import ru.souz.agent.nodes.NodesErrorHandling
import ru.souz.agent.nodes.NodesLLM
import ru.souz.agent.nodes.NodesLua
import ru.souz.agent.nodes.NodesMCP
import ru.souz.agent.nodes.NodesSkills
import ru.souz.agent.nodes.NodesSummarization
import ru.souz.agent.runtime.AgentToolExecutor
import ru.souz.agent.runtime.LuaRuntime
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
    private val telemetry: AgentTelemetry,
    private val errorMessages: AgentErrorMessages,
    private val llmApi: LLMChatAPI,
    private val apiClassifier: UserMessageClassifier,
    private val localClassifier: UserMessageClassifier,
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
        val nodesClassification = NodesClassification(
            settingsProvider = settingsProvider,
            logObjectMapper = logObjectMapper,
            apiClassifier = apiClassifier,
            localClassifier = localClassifier,
            toolCatalog = toolCatalog,
            toolsFilter = toolsFilter,
        )
        val nodesLLM = NodesLLM(llmApi = llmApi, settingsProvider = settingsProvider)
        val nodesLua = NodesLua(llmApi = llmApi, luaRuntime = LuaRuntime(agentToolExecutor))
        val nodesErrorHandling = NodesErrorHandling(errorMessages)
        val nodesMcp = NodesMCP(mcpToolProvider)
        val nodesSkills = NodesSkills(desktopInfoRepository)
        val nodesSummarization = NodesSummarization(llmApi = llmApi, nodesCommon = nodesCommon)
        val contextFactory = AgentContextFactory(
            settingsProvider = settingsProvider,
            systemPromptResolver = SystemPromptResolver(),
            toolCatalog = toolCatalog,
            toolsFilter = toolsFilter,
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
        )
        val luaGraphAgent = LuaGraphBasedAgent(
            logObjectMapper = logObjectMapper,
            nodesLua = nodesLua,
            nodesCommon = nodesCommon,
            nodesClassify = nodesClassification,
            nodesErrorHandling = nodesErrorHandling,
            nodesSummarization = nodesSummarization,
            nodesMCP = nodesMcp,
            nodesSkills = nodesSkills,
        )
        val executor = AgentExecutor(
            agentProvider = { id ->
                when (id) {
                    AgentId.GRAPH -> graphAgent
                    AgentId.LUA_GRAPH -> luaGraphAgent
                }
            }
        )
        return AgentExecutionKernel(
            contextFactory = contextFactory,
            executor = executor,
        )
    }
}
