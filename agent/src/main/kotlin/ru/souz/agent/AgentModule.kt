package ru.souz.agent

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineScope
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import org.kodein.di.instanceOrNull
import ru.souz.GraphBasedAgent
import ru.souz.SkillsGraphBasedAgent
import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.agent.nodes.NodesClassification
import ru.souz.agent.nodes.NodesCommon
import ru.souz.agent.nodes.NodesErrorHandling
import ru.souz.agent.nodes.NodesLLM
import ru.souz.agent.nodes.NodesMCP
import ru.souz.agent.nodes.NodesSkills
import ru.souz.agent.nodes.NodesSummarization
import ru.souz.agent.runtime.AgentToolExecutor
import ru.souz.agent.skills.SkillActivationPipeline
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.agent.spi.AgentRuntimeEnvironment
import ru.souz.agent.spi.SkillToolBindingTags
import ru.souz.agent.spi.SystemAgentRuntimeEnvironment
import ru.souz.agent.session.GraphSessionRepository
import ru.souz.agent.session.GraphSessionService
import ru.souz.llms.json.JsonUtils
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.restJsonMapper
import ru.souz.memory.ConversationMemoryRuntime
import ru.souz.tool.UserMessageClassifier

fun agentDiModule(
    logObjectMapperTag: Any? = null,
    apiClassifierTag: Any? = null,
    localClassifierTag: Any? = null,
    skillCommandToolTag: Any? = null,
    graphSessionRepositoryTag: Any? = null,
): DI.Module = DI.Module("agent") {
    bindSingleton {
        graphSessionRepositoryTag
            ?.let { tag -> instance<GraphSessionRepository>(tag = tag) }
            ?: GraphSessionRepository()
    }
    bindSingleton {
        GraphSessionService(
            repository = instance(),
            logObjectMapper = instance<ObjectMapper>(tag = logObjectMapperTag),
        )
    }
    bindSingleton { AgentToolExecutor(instance<AgentTelemetry>()) }
    bindSingleton { NodesErrorHandling(instance()) }
    bindSingleton {
        NodesCommon(
            desktopInfoRepository = instance(),
            settingsProvider = instance(),
            agentToolExecutor = instance(),
            defaultBrowserProvider = instance(),
            runtimeEnvironment = instance(),
            memoryRuntime = instance(),
            knowledgeStore = instanceOrNull<ConversationKnowledgeStore>(),
        )
    }
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
        SkillActivationPipeline.from(
            registryRepository = instance<SkillRegistryRepository>(),
            llmApi = instance(),
            settingsProvider = instance(),
            jsonUtils = instance(),
        )
    }
    bindSingleton {
        NodesSkills(
            pipeline = instance(),
            skillCommandTool = skillCommandToolTag?.let { tag -> instance<LLMToolSetup>(tag = tag) },
        )
    }
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
        SkillsGraphBasedAgent(
            logObjectMapper = instance<ObjectMapper>(tag = logObjectMapperTag),
            nodesLLM = instance(),
            nodesCommon = instance(),
            nodesErrorHandling = instance(),
            nodesSummarization = instance(),
            getSkillsTool = instance(tag = SkillToolBindingTags.GET_SKILLS_TOOL),
            getKnowledgeTool = instance(tag = SkillToolBindingTags.GET_KNOWLEDGE_TOOL),
            runtimeCommandTool = instance(tag = SkillToolBindingTags.RUNTIME_COMMAND_TOOL),
        )
    }
    bindSingleton {
        AgentExecutor(
            agentProvider = { agentId ->
                when (agentId) {
                    AgentId.GRAPH -> instance<GraphBasedAgent>()
                    AgentId.SKILLS_GRAPH -> instance<SkillsGraphBasedAgent>()
                }
            },
            memoryRuntime = instance<ConversationMemoryRuntime>(),
            captureScope = instance<CoroutineScope>(),
        )
    }
    bindSingleton { AgentFacade(instance(), instance(), instance(), instance(), instance()) }
}
