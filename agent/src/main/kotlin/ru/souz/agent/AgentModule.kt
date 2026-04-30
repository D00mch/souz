package ru.souz.agent

import com.fasterxml.jackson.databind.ObjectMapper
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
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
import ru.souz.agent.spi.AgentRuntimeEnvironment
import ru.souz.agent.spi.SystemAgentRuntimeEnvironment
import ru.souz.agent.session.GraphSessionRepository
import ru.souz.agent.session.GraphSessionService
import ru.souz.tool.UserMessageClassifier

fun agentDiModule(
    logObjectMapperTag: Any? = null,
    apiClassifierTag: Any? = null,
    localClassifierTag: Any? = null,
): DI.Module = DI.Module("agent") {
    bindSingleton { GraphSessionRepository() }
    bindSingleton {
        GraphSessionService(
            repository = instance(),
            logObjectMapper = instance<ObjectMapper>(tag = logObjectMapperTag),
        )
    }
    bindSingleton { AgentToolExecutor(instance()) }
    bindSingleton { NodesErrorHandling(instance()) }
    bindSingleton { NodesCommon(instance(), instance(), instance(), instance(), instance()) }
    bindSingleton { NodesLLM(instance(), instance()) }
    bindSingleton { LuaRuntime(instance()) }
    bindSingleton { NodesLua(instance(), instance()) }
    bindSingleton { NodesMCP(instance()) }
    bindSingleton { NodesSkills(instance()) }
    bindSingleton { NodesSummarization(instance(), instance()) }
    bindSingleton {
        NodesClassification(
            settingsProvider = instance(),
            logObjectMapper = instance<ObjectMapper>(tag = logObjectMapperTag),
            apiClassifier = instance<UserMessageClassifier>(tag = apiClassifierTag),
            localClassifier = instance<UserMessageClassifier>(tag = localClassifierTag),
            toolCatalog = instance(),
            toolsFilter = instance(),
        )
    }
    bindSingleton { SystemPromptResolver() }
    bindSingleton<AgentRuntimeEnvironment> { SystemAgentRuntimeEnvironment }
    bindSingleton { AgentContextFactory(instance(), instance(), instance(), instance()) }
    bindSingleton {
        GraphBasedAgent(
            logObjectMapper = instance<ObjectMapper>(tag = logObjectMapperTag),
            nodesLLM = instance(),
            nodesCommon = instance(),
            nodesClassify = instance(),
            nodesErrorHandling = instance(),
            nodesSummarization = instance(),
            nodesMCP = instance(),
            nodesSkills = instance(),
        )
    }
    bindSingleton {
        LuaGraphBasedAgent(
            logObjectMapper = instance<ObjectMapper>(tag = logObjectMapperTag),
            nodesLua = instance(),
            nodesCommon = instance(),
            nodesClassify = instance(),
            nodesErrorHandling = instance(),
            nodesSummarization = instance(),
            nodesMCP = instance(),
            nodesSkills = instance(),
        )
    }
    bindSingleton {
        AgentExecutor(
            agentProvider = { id ->
                when (id) {
                    AgentId.GRAPH -> instance<GraphBasedAgent>()
                    AgentId.LUA_GRAPH -> instance<LuaGraphBasedAgent>()
                }
            }
        )
    }
    bindSingleton { AgentFacade(instance(), instance(), instance(), instance(), instance()) }
}
