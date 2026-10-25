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
import ru.souz.agent.nodes.NodesToolUseWithKnowledge
import ru.souz.agent.runtime.AgentToolExecutor
import ru.souz.agent.skills.registry.SkillBundleProvider
import ru.souz.agent.skills.SkillClassifier
import ru.souz.agent.spi.AgentErrorMessages
import ru.souz.agent.spi.AgentRuntimeEnvironment
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.agent.spi.TaskComplexityClassifier
import ru.souz.llms.LLMChatAPI
import ru.souz.memory.ConversationMemoryRuntime
import ru.souz.memory.NoopConversationMemoryRuntime

class AgentExecutionKernel(
    val contextFactory: AgentContextFactory,
    val executor: AgentExecutor,
)

class AgentExecutionKernelFactory(
    private val logObjectMapper: ObjectMapper,
    private val settingsProvider: AgentSettingsProvider,
    private val toolCatalog: AgentToolCatalog,
    private val toolsFilter: AgentToolsFilter,
    private val skillBundleProvider: SkillBundleProvider,
    private val runtimeEnvironment: AgentRuntimeEnvironment,
    private val coreTools: AgentCoreTools,
    private val knowledgeStore: ConversationKnowledgeStore?,
    private val telemetry: AgentTelemetry,
    private val errorMessages: AgentErrorMessages,
    private val llmApi: LLMChatAPI,
    private val conversationInstructions: suspend () -> String = { "" },
    private val memoryRuntime: ConversationMemoryRuntime = NoopConversationMemoryRuntime,
    private val captureScope: CoroutineScope,
    private val automaticMemoryRecall: Boolean = true,
    private val skillClassifier: SkillClassifier? = null,
    private val fallbackSkillClassifier: SkillClassifier? = null,
    private val complexityClassifier: TaskComplexityClassifier? = null,
) {
    fun create(): AgentExecutionKernel {
        val agentToolExecutor = AgentToolExecutor(telemetry)
        val nodesCommon = NodesCommon(
            runtimeEnvironment = runtimeEnvironment,
        )
        val nodesSkillInventory = NodesSkillInventory(
            toolCatalog = toolCatalog,
            toolsFilter = toolsFilter,
            skillBundleProvider = skillBundleProvider,
            llmApi = llmApi,
            skillClassifier = skillClassifier,
            fallbackSkillClassifier = fallbackSkillClassifier,
        )
        val nodesToolUseWithKnowledge = NodesToolUseWithKnowledge(
            agentToolExecutor = agentToolExecutor,
            knowledgeStore = knowledgeStore,
        )
        val nodesMemory = NodesMemory(memoryRuntime, captureScope, automaticMemoryRecall)
        val nodesErrorHandling = NodesErrorHandling(errorMessages)
        val nodesLLM = NodesLLM(llmApi, settingsProvider, conversationInstructions, complexityClassifier,
            routedTools = coreTools::skillsTools, initialWaiting = true)
        val availableAgents = listOf(AgentId.SKILLS_GRAPH)
        val contextFactory = AgentContextFactory(
            settingsProvider = settingsProvider,
            toolCatalog = toolCatalog,
            availableAgents = availableAgents,
        )
        val skillsGraphAgent = SkillsGraphBasedAgent(
            logObjectMapper = logObjectMapper,
            nodesLLM = nodesLLM,
            nodesCommon = nodesCommon,
            nodesErrorHandling = nodesErrorHandling,
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
