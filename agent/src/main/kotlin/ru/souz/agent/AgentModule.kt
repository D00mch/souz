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
import ru.souz.agent.nodes.NodesSummarization
import ru.souz.agent.runtime.AgentToolExecutor
import ru.souz.agent.runtime.LuaRuntime
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
    bindSingleton { NodesCommon(instance(), instance(), instance(), instance()) }
    bindSingleton { NodesLLM(instance(), instance()) }
    bindSingleton { LuaRuntime(instance()) }
    bindSingleton { NodesLua(instance(), instance()) }
    bindSingleton { NodesMCP(instance()) }
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
    bindSingleton { AgentContextFactory(instance(), instance(), instance()) }
    bindSingleton { GraphBasedAgent(di, instance<ObjectMapper>(tag = logObjectMapperTag)) }
    bindSingleton { LuaGraphBasedAgent(di, instance<ObjectMapper>(tag = logObjectMapperTag)) }
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
