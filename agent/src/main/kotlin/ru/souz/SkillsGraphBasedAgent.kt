package ru.souz

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import ru.souz.agent.ActiveRunMailbox
import ru.souz.agent.AgentExecutionResult
import ru.souz.agent.Agent
import ru.souz.agent.AgentStreamChunk
import ru.souz.agent.GraphStepCallback
import ru.souz.agent.AgentCoreTools
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
import ru.souz.agent.nodes.SteerableChat
import ru.souz.agent.runtime.GraphExecutionDelegate
import ru.souz.agent.runtime.GraphExecutionDelegateImpl
import ru.souz.agent.state.AgentContext
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMRequest

/**
 * Agent graph whose model always sees only the core skill-discovery, Knowledge, and execution tools.
 * Other capabilities are discovered and invoked through skills rather than injected directly.
 */
internal class SkillsGraphBasedAgent(
    logObjectMapper: ObjectMapper,
    private val nodesLLM: NodesLLM,
    private val nodesCommon: NodesCommon,
    private val nodesErrorHandling: NodesErrorHandling,
    private val nodesSummarization: NodesSummarization,
    private val nodesMemory: NodesMemory,
    private val nodesSkillInventory: NodesSkillInventory,
    private val nodesToolUseWithKnowledge: NodesToolUseWithKnowledge,
    coreTools: AgentCoreTools,
    private val executionDelegate: GraphExecutionDelegate = GraphExecutionDelegateImpl(
        logObjectMapper = logObjectMapper,
        loggerClass = SkillsGraphBasedAgent::class.java,
    ),
) : Agent {
    override val stream: Flow<AgentStreamChunk> = nodesLLM.sideEffects
    private val alwaysInlineResultTools = coreTools.skillsAlwaysInlineResultTools
    private val skillsCoreTools = coreTools.skillsCoreTools

    private fun graph(mailbox: ActiveRunMailbox): Graph<String, String> = buildGraph(name = "Skills Agent") {
        val inputToHistory = nodesCommon.inputToHistory()
        val memoryRecall = nodesMemory.recall()
        val skillInventory = nodesSkillInventory.node(
            skillTools = emptyList(),
            name = SKILL_INVENTORY_NODE_NAME,
        )
        val contextEnrich = nodesCommon.nodeAppendAdditionalData()
        val chat = SteerableChat(nodesLLM, mailbox)
        val chatOk: Node<LLMResponse.Chat, LLMResponse.Chat.Ok> = Node("Chat.Ok") { ctx ->
            ctx.map { ctx.input as LLMResponse.Chat.Ok }
        }
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

    override fun cancel() = executionDelegate.cancelActiveJob()

    override suspend fun execute(
        context: AgentContext<String>,
        loadPendingHistory: suspend () -> List<LLMRequest.Message>,
        onActiveRunReady: suspend (ActiveRunMailbox) -> Unit,
        onStep: GraphStepCallback?,
    ): AgentExecutionResult {
        cancel()
        val restrictedContext = nodesSkillInventory.restrictToTools(context, skillsCoreTools)
        val mailbox = ActiveRunMailbox(loadPendingHistory)
        val executionGraph = graph(mailbox)
        return try {
            onActiveRunReady(mailbox)
            executionDelegate.executeWithTrace(graph = executionGraph, ctx = restrictedContext, onStep = onStep)
        } finally {
            withContext(NonCancellable) {
                mailbox.close()
            }
        }
    }

    private val LLMResponse.Chat.Ok.isToolUse: Boolean
        get() = choices.any { it.message.functionCall != null }
}
