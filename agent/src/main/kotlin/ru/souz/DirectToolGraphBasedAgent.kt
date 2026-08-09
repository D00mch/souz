package ru.souz

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.withContext
import ru.souz.agent.AgentExecutionResult
import ru.souz.agent.AgentStreamChunk
import ru.souz.agent.GraphStepCallback
import ru.souz.agent.TraceableAgent
import ru.souz.agent.graph.Graph
import ru.souz.agent.graph.Node
import ru.souz.agent.graph.buildGraph
import ru.souz.agent.nodes.CLASSIFY_NODE_NAME
import ru.souz.agent.nodes.NodesClassification
import ru.souz.agent.nodes.NodesCommon
import ru.souz.agent.nodes.NodesErrorHandling
import ru.souz.agent.nodes.NodesLLM
import ru.souz.agent.nodes.NodesMCP
import ru.souz.agent.nodes.NodesMemory
import ru.souz.agent.nodes.NodesSummarization
import ru.souz.agent.nodes.SteerableChat
import ru.souz.agent.runtime.ActiveRunInputController
import ru.souz.agent.runtime.GraphExecutionDelegate
import ru.souz.agent.runtime.GraphExecutionDelegateImpl
import ru.souz.agent.state.AgentContext
import ru.souz.llms.LLMResponse

/** Request-scoped, steerable graph that invokes only tools supplied by its host catalog. */
internal class DirectToolGraphBasedAgent(
    logObjectMapper: ObjectMapper,
    private val nodesLLM: NodesLLM,
    private val nodesCommon: NodesCommon,
    private val nodesClassify: NodesClassification,
    private val nodesErrorHandling: NodesErrorHandling,
    private val nodesSummarization: NodesSummarization,
    private val nodesMCP: NodesMCP,
    private val nodesMemory: NodesMemory,
    alwaysAvailableToolNames: Set<String>,
    private val executionDelegate: GraphExecutionDelegate = GraphExecutionDelegateImpl(
        logObjectMapper = logObjectMapper,
        loggerClass = DirectToolGraphBasedAgent::class.java,
    ),
) : TraceableAgent {
    override val supportsActiveRunInput: Boolean = true
    override val sideEffects: Flow<AgentStreamChunk> = nodesLLM.sideEffects

    private val alwaysAvailableToolNames = alwaysAvailableToolNames.toSet()
    private val activeRun = MutableStateFlow<ActiveRunInputController?>(null)

    private fun graph(controller: ActiveRunInputController): Graph<String, String> =
        buildGraph(name = "Direct Tool Agent") {
            val inputToHistory = nodesCommon.inputToHistory()
            val memoryRecall = nodesMemory.recall()
            val classify = nodesClassify.node(CLASSIFY_NODE_NAME)
            val addAlwaysAvailableTools = alwaysAvailableToolsNode()
            val provideMcpTools = nodesMCP.nodeProvideMcpTools("MCP Node")
            val contextEnrich = nodesCommon.nodeAppendAdditionalData()
            val chat = SteerableChat(nodesLLM, controller)
            val chatOk: Node<LLMResponse.Chat, LLMResponse.Chat.Ok> = Node("Chat.Ok") { ctx ->
                ctx.map { ctx.input as LLMResponse.Chat.Ok }
            }
            val toolUse = nodesCommon.toolUse()
            val finalizeTurn = nodesMemory.finalizeTurn(
                summarization = nodesSummarization.summarize(),
            )
            val chatErrorToFinish = nodesErrorHandling.chatErrorToFinish()

            nodeInput.edgeTo(inputToHistory)
            inputToHistory.edgeTo(memoryRecall)
            memoryRecall.edgeTo(classify)
            classify.edgeTo(addAlwaysAvailableTools)
            addAlwaysAvailableTools.edgeTo(provideMcpTools)
            provideMcpTools.edgeTo(contextEnrich)
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

    private fun alwaysAvailableToolsNode(): Node<String, String> = Node("Always Available Tools") { ctx ->
        val alwaysAvailableFunctions = ctx.settings.tools.byName.values
            .asSequence()
            .filter { tool -> tool.fn.name in alwaysAvailableToolNames }
            .map { tool -> tool.fn }
            .toList()
        ctx.map(
            activeTools = (ctx.activeTools + alwaysAvailableFunctions).distinctBy { function -> function.name },
        )
    }

    override suspend fun cancelActiveJob() {
        activeRun.getAndUpdate { null }?.close()
        executionDelegate.cancelActiveJob()
    }

    override suspend fun submitToActiveRun(input: String): Boolean =
        activeRun.value?.submit(input) ?: false

    override suspend fun submitToActiveRunAfter(
        input: String,
        beforePublish: suspend () -> Boolean,
    ): Boolean = activeRun.value?.submitAfter(input, beforePublish) ?: false

    override suspend fun execute(ctx: AgentContext<String>): String =
        executeWithTrace(ctx).output

    override suspend fun executeWithTrace(
        ctx: AgentContext<String>,
        onActiveRunReady: suspend () -> Unit,
        onStep: GraphStepCallback?,
    ): AgentExecutionResult {
        cancelActiveJob()
        val controller = ActiveRunInputController()
        val executionGraph = graph(controller)
        activeRun.value = controller
        return try {
            onActiveRunReady()
            executionDelegate.executeWithTrace(graph = executionGraph, ctx = ctx, onStep = onStep)
        } finally {
            withContext(NonCancellable) {
                controller.close()
                activeRun.compareAndSet(controller, null)
            }
        }
    }

    private val LLMResponse.Chat.Ok.isToolUse: Boolean
        get() = choices.any { choice -> choice.message.functionCall != null }
}
