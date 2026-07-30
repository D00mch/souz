package ru.souz.agent

import com.fasterxml.jackson.databind.ObjectMapper
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
import ru.souz.agent.nodes.NodesMemory
import ru.souz.agent.nodes.NodesSkillInventory
import ru.souz.agent.nodes.NodesToolUseWithKnowledge
import ru.souz.agent.nodes.NodesSummarization
import ru.souz.agent.runtime.AgentToolExecutor
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.validation.SkillApprovalGate
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.agent.spi.AgentRuntimeEnvironment
import ru.souz.agent.spi.SkillToolBindingTags
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
        )
    }
    bindSingleton {
        NodesToolUseWithKnowledge(
            nodesCommon = instance(),
            knowledgeStore = instanceOrNull<ConversationKnowledgeStore>(),
        )
    }
    bindSingleton {
        NodesSkillInventory(
            toolCatalog = instance(),
            toolsFilter = instance(),
            skillRegistryRepository = instance<SkillRegistryRepository>(),
        )
    }
    bindSingleton { NodesMemory(instance(), instance()) }
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
        SkillApprovalGate.from(
            registryRepository = instance<SkillRegistryRepository>(),
            llmApi = instance(),
            settingsProvider = instance(),
            jsonUtils = instance(),
        )
    }
    bindSingleton { SystemPromptResolver() }
    bindSingleton<AgentRuntimeEnvironment> { SystemAgentRuntimeEnvironment }
    bindSingleton { AgentContextFactory(instance(), instance(), instance()) }
    bindSingleton {
        AgentCoreTools(
            getSkillByNameTool = instance(tag = SkillToolBindingTags.GET_SKILL_BY_NAME_TOOL),
            getSkillsByCategoryTool = instance(tag = SkillToolBindingTags.GET_SKILLS_BY_CATEGORY_TOOL),
            getSkillsNamesByCategoryTool = instance(tag = SkillToolBindingTags.GET_SKILLS_NAMES_BY_CATEGORY_TOOL),
            getKnowledgeTool = instance(tag = SkillToolBindingTags.GET_KNOWLEDGE_TOOL),
            searchKnowledgeTool = instance(tag = SkillToolBindingTags.SEARCH_KNOWLEDGE_TOOL),
            searchMemoryTool = instance(tag = SkillToolBindingTags.SEARCH_MEMORY_TOOL),
            runtimeCommandTool = instance(tag = SkillToolBindingTags.RUNTIME_COMMAND_TOOL),
        )
    }
    bindSingleton {
        GraphBasedAgent(
            logObjectMapper = instance<ObjectMapper>(tag = logObjectMapperTag),
            nodesLLM = instance(),
            nodesCommon = instance(),
            nodesClassify = instance(),
            nodesErrorHandling = instance(),
            nodesSummarization = instance(),
            nodesMCP = instance(),
            nodesSkillInventory = instance(),
            nodesToolUseWithKnowledge = instance(),
            nodesMemory = instance(),
            coreTools = instance(),
        )
    }
    bindSingleton {
        SkillsGraphBasedAgent(
            logObjectMapper = instance<ObjectMapper>(tag = logObjectMapperTag),
            nodesLLM = instance(),
            nodesCommon = instance(),
            nodesErrorHandling = instance(),
            nodesSummarization = instance(),
            nodesMemory = instance(),
            nodesSkillInventory = instance(),
            nodesToolUseWithKnowledge = instance(),
            coreTools = instance(),
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
        )
    }
    bindSingleton { AgentFacade(instance(), instance(), instance(), instance(), instance()) }
}
