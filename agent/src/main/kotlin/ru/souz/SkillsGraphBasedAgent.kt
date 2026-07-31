package ru.souz

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import ru.souz.agent.AgentExecutionResult
import ru.souz.agent.GraphStepCallback
import ru.souz.agent.TraceableAgent
import ru.souz.agent.graph.Graph
import ru.souz.agent.graph.buildGraph
import ru.souz.agent.nodes.NodesCommon
import ru.souz.agent.nodes.NodesErrorHandling
import ru.souz.agent.nodes.NodesLLM
import ru.souz.agent.nodes.NodesMemory
import ru.souz.agent.nodes.NodesSkillInventory
import ru.souz.agent.nodes.NodesToolUseWithKnowledge
import ru.souz.agent.nodes.NodesSummarization
import ru.souz.agent.nodes.SKILL_INVENTORY_NODE_NAME
import ru.souz.agent.nodes.ContinuationNodes
import ru.souz.agent.nodes.ContinuationNodes.ChatRoute
import ru.souz.agent.runtime.ActiveRunInputController
import ru.souz.agent.runtime.GraphExecutionDelegate
import ru.souz.agent.runtime.GraphExecutionDelegateImpl
import ru.souz.agent.state.AgentContext
import ru.souz.llms.LLMToolSetup

/**
 * Agent graph whose model always sees only the core skill-discovery, Knowledge, and execution tools.
 * Other capabilities are discovered and invoked through skills rather than injected directly.
 */
class SkillsGraphBasedAgent internal constructor(
    logObjectMapper: ObjectMapper,
    private val nodesLLM: NodesLLM,
    private val nodesCommon: NodesCommon,
    private val nodesErrorHandling: NodesErrorHandling,
    private val nodesSummarization: NodesSummarization,
    private val nodesMemory: NodesMemory,
    private val nodesSkillInventory: NodesSkillInventory,
    private val nodesToolUseWithKnowledge: NodesToolUseWithKnowledge,
    getSkillByNameTool: LLMToolSetup,
    getSkillsByCategoryTool: LLMToolSetup,
    getSkillsNamesByCategoryTool: LLMToolSetup,
    getKnowledgeTool: LLMToolSetup,
    searchKnowledgeTool: LLMToolSetup,
    runtimeCommandTool: LLMToolSetup,
    private val executionDelegate: GraphExecutionDelegate = GraphExecutionDelegateImpl(
        logObjectMapper = logObjectMapper,
        loggerClass = SkillsGraphBasedAgent::class.java,
    ),
) : TraceableAgent {
    override val sideEffects: Flow<String> = nodesLLM.sideEffects

    private val alwaysInlineResultTools = listOf(
        getSkillByNameTool,
        getSkillsByCategoryTool,
        getSkillsNamesByCategoryTool,
        getKnowledgeTool,
        searchKnowledgeTool,
    )
    private val coreTools = alwaysInlineResultTools + runtimeCommandTool
    private val activeRun = MutableStateFlow<ActiveRunInputController?>(null)

    private fun graph(controller: ActiveRunInputController): Graph<String, String> = buildGraph(name = "Skills Agent") {
        val inputToHistory = nodesCommon.inputToHistory()
        val memoryRecall = nodesMemory.recall()
        val skillInventory = nodesSkillInventory.node(
            skillTools = emptyList(),
            name = SKILL_INVENTORY_NODE_NAME,
        )
        val contextEnrich = nodesCommon.nodeAppendAdditionalData()
        val runNodes = ContinuationNodes(nodesLLM, controller)
        val toolUse = nodesToolUseWithKnowledge.node(
            alwaysInlineToolNames = alwaysInlineResultTools.mapTo(mutableSetOf()) { it.fn.name },
        )
        val finalizeTurn = nodesMemory.finalizeTurn(
            summarization = nodesSummarization.summarize(),
        )
        val chatErrorToFinish = nodesErrorHandling.chatErrorToFinish()

        nodeInput.edgeTo(inputToHistory)
        inputToHistory.edgeTo(memoryRecall)
        memoryRecall.edgeTo(skillInventory)
        skillInventory.edgeTo(contextEnrich)
        contextEnrich.edgeTo(runNodes.chat)
        runNodes.chat.edgeTo(runNodes.processChat)
        runNodes.processChat.edgeTo { ctx ->
            when (ctx.input) {
                ChatRoute.Replan -> runNodes.replan
                is ChatRoute.Tool -> runNodes.toolResponse
                is ChatRoute.Final -> runNodes.finalResponse
                is ChatRoute.Error -> runNodes.errorResponse
            }
        }
        runNodes.replan.edgeTo(runNodes.chat)
        runNodes.toolResponse.edgeTo(toolUse)
        toolUse.edgeTo(runNodes.queuedInputAfterTools)
        runNodes.queuedInputAfterTools.edgeTo(runNodes.chat)
        runNodes.finalResponse.edgeTo(finalizeTurn)
        runNodes.errorResponse.edgeTo(chatErrorToFinish)
        finalizeTurn.edgeTo(nodeFinish)
        chatErrorToFinish.edgeTo(nodeFinish)
    }

    override fun cancelActiveJob() {
        activeRun.value?.close()
        executionDelegate.cancelActiveJob()
    }

    override suspend fun submitToActiveRun(input: String): Boolean =
        activeRun.value?.submit(input) ?: false

    override suspend fun execute(ctx: AgentContext<String>): String =
        executeWithTrace(ctx).output

    override suspend fun executeWithTrace(
        ctx: AgentContext<String>,
        onStep: GraphStepCallback?,
    ): AgentExecutionResult {
        val restrictedContext = nodesSkillInventory.restrictToTools(ctx, coreTools)
        val controller = ActiveRunInputController()
        val executionGraph = graph(controller)
        activeRun.value = controller
        return try {
            executionDelegate.executeWithTrace(graph = executionGraph, ctx = restrictedContext, onStep = onStep)
        } finally {
            controller.close()
            activeRun.compareAndSet(controller, null)
        }
    }
}
