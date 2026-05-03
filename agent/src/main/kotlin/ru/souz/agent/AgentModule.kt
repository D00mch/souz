package ru.souz.agent

import com.fasterxml.jackson.databind.ObjectMapper
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import ru.souz.GraphBasedAgent
import ru.souz.agent.nodes.NodesClassification
import ru.souz.agent.nodes.NodesCommon
import ru.souz.agent.nodes.NodesErrorHandling
import ru.souz.agent.nodes.NodesLLM
import ru.souz.agent.nodes.NodesMCP
import ru.souz.agent.nodes.NodesSkills
import ru.souz.agent.nodes.NodesSummarization
import ru.souz.agent.runtime.AgentToolExecutor
import ru.souz.agent.skills.productionSkillActivationPipeline
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.agent.spi.AgentRuntimeEnvironment
import ru.souz.agent.spi.SystemAgentRuntimeEnvironment
import ru.souz.agent.session.GraphSessionRepository
import ru.souz.agent.session.GraphSessionService
import ru.souz.llms.json.JsonUtils
import ru.souz.llms.restJsonMapper
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
    bindSingleton { AgentToolExecutor(instance<AgentTelemetry>()) }
    bindSingleton { NodesErrorHandling(instance()) }
    bindSingleton { NodesCommon(instance(), instance(), instance(), instance(), instance()) }
    bindSingleton { NodesLLM(instance(), instance()) }
    bindSingleton { NodesMCP(instance()) }
    bindSingleton { JsonUtils(restJsonMapper) }
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
    bindSingleton {
        productionSkillActivationPipeline(
            registryRepository = instance<SkillRegistryRepository>(),
            llmApi = instance(),
            settingsProvider = instance(),
            jsonUtils = instance(),
        )
    }
    bindSingleton { NodesSkills(instance()) }
    bindSingleton { SystemPromptResolver() }
    bindSingleton<AgentRuntimeEnvironment> { SystemAgentRuntimeEnvironment }
    bindSingleton { AgentContextFactory(instance(), instance(), instance()) }
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
        AgentExecutor(
            agentProvider = { instance<GraphBasedAgent>() }
        )
    }
    bindSingleton { AgentFacade(instance(), instance(), instance(), instance(), instance()) }
}
