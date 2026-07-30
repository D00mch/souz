package ru.souz

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.Flow
import ru.souz.agent.AgentCoreTools
import ru.souz.agent.AgentExecutionResult
import ru.souz.agent.GraphStepCallback
import ru.souz.agent.TraceableAgent
import ru.souz.agent.graph.Graph
import ru.souz.agent.graph.Node
import ru.souz.agent.graph.buildGraph
import ru.souz.agent.nodes.NodesCommon
import ru.souz.agent.nodes.NodesErrorHandling
import ru.souz.agent.nodes.NodesLLM
import ru.souz.agent.nodes.NodesMemory
import ru.souz.agent.nodes.NodesSkillInventory
import ru.souz.agent.nodes.NodesToolUseWithKnowledge
import ru.souz.agent.nodes.NodesSummarization
import ru.souz.agent.nodes.SKILL_INVENTORY_NODE_NAME
import ru.souz.agent.runtime.GraphExecutionDelegate
import ru.souz.agent.runtime.GraphExecutionDelegateImpl
import ru.souz.agent.state.AgentContext
import ru.souz.llms.LLMResponse

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
    private val coreTools: AgentCoreTools,
    private val executionDelegate: GraphExecutionDelegate = GraphExecutionDelegateImpl(
        logObjectMapper = logObjectMapper,
        loggerClass = SkillsGraphBasedAgent::class.java,
    ),
) : TraceableAgent {
    override val sideEffects: Flow<String> = nodesLLM.sideEffects

    private val graph: Graph<String, String> = buildGraph(name = "Skills Agent") {
        val inputToHistory = nodesCommon.inputToHistory()
        val memoryRecall = nodesMemory.recall()
        val skillInventory = nodesSkillInventory.node(
            skillTools = emptyList(),
            name = SKILL_INVENTORY_NODE_NAME,
        )
        val contextEnrich = nodesCommon.nodeAppendAdditionalData()
        val chat = nodesLLM.chat("LLM")
        val chatOk: Node<LLMResponse.Chat, LLMResponse.Chat.Ok> = Node("Chat.Ok") { ctx ->
            ctx.map { ctx.input as LLMResponse.Chat.Ok }
        }
        val toolUse = nodesToolUseWithKnowledge.node(
            alwaysInlineToolNames = coreTools.alwaysInlineResultToolNames,
        )
        val finalizeTurn = nodesMemory.finalizeTurn(
            summarization = nodesSummarization.summarize(),
        )
        val chatErrorToFinish = nodesErrorHandling.chatErrorToFinish()

        nodeInput.edgeTo(inputToHistory)
        inputToHistory.edgeTo(memoryRecall)
        memoryRecall.edgeTo(skillInventory)
        skillInventory.edgeTo(contextEnrich)
        contextEnrich.edgeTo(chat)
        chat.edgeTo { ctx ->
            when (ctx.input) {
                is LLMResponse.Chat.Error -> chatErrorToFinish
                is LLMResponse.Chat.Ok -> chatOk
            }
        }
        chatOk.edgeTo { ctx -> if (ctx.input.isToolUse) toolUse else finalizeTurn }
        toolUse.edgeTo(chat)
        finalizeTurn.edgeTo(nodeFinish)
        chatErrorToFinish.edgeTo(nodeFinish)
    }

    override fun cancelActiveJob() {
        executionDelegate.cancelActiveJob()
    }

    override suspend fun execute(ctx: AgentContext<String>): String =
        executeWithTrace(ctx).output

    override suspend fun executeWithTrace(
        ctx: AgentContext<String>,
        onStep: GraphStepCallback?,
    ): AgentExecutionResult {
        val restrictedContext = nodesSkillInventory.restrictToTools(ctx, coreTools.skillsGraphTools)
        return executionDelegate.executeWithTrace(graph = graph, ctx = restrictedContext, onStep = onStep)
    }

    private val LLMResponse.Chat.Ok.isToolUse get() = choices.any { it.message.functionCall != null }
}
