package ru.souz

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.Flow
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
import ru.souz.agent.nodes.NodesSkillInventory
import ru.souz.agent.nodes.NodesToolUseWithKnowledge
import ru.souz.agent.nodes.NodesSummarization
import ru.souz.agent.nodes.SKILL_INVENTORY_NODE_NAME
import ru.souz.agent.runtime.GraphExecutionDelegate
import ru.souz.agent.state.AgentContext
import ru.souz.agent.runtime.GraphExecutionDelegateImpl
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup

class GraphBasedAgent internal constructor(
    logObjectMapper: ObjectMapper,
    private val nodesLLM: NodesLLM,
    private val nodesCommon: NodesCommon,
    private val nodesClassify: NodesClassification,
    private val nodesErrorHandling: NodesErrorHandling,
    private val nodesSummarization: NodesSummarization,
    private val nodesMCP: NodesMCP,
    private val nodesSkillInventory: NodesSkillInventory,
    private val nodesToolUseWithKnowledge: NodesToolUseWithKnowledge,
    private val nodesMemory: NodesMemory,
    getSkillByNameTool: LLMToolSetup,
    getKnowledgeTool: LLMToolSetup,
    searchKnowledgeTool: LLMToolSetup,
    searchMemoryTool: LLMToolSetup,
    runtimeCommandTool: LLMToolSetup,
    private val executionDelegate: GraphExecutionDelegate = GraphExecutionDelegateImpl(
        logObjectMapper = logObjectMapper,
        loggerClass = GraphBasedAgent::class.java,
    ),
) : TraceableAgent {

    override val sideEffects: Flow<AgentStreamChunk> = nodesLLM.sideEffects
    private val alwaysInlineResultTools = listOf(getSkillByNameTool, getKnowledgeTool, searchKnowledgeTool)
    private val coreTools = alwaysInlineResultTools + searchMemoryTool + runtimeCommandTool

    private val graph: Graph<String, String> = buildGraph(name = "Agent") {
        val chatSubgraph: Node<String, LLMResponse.Chat> = nodesLLM.chat("LLM")
        val chatOk: Node<LLMResponse.Chat, LLMResponse.Chat.Ok> = Node("Chat.Ok") { ctx ->
            ctx.map { ctx.input as LLMResponse.Chat.Ok }
        }
        val chatErrorToFinish: Node<LLMResponse.Chat, String> = nodesErrorHandling.chatErrorToFinish()
        val contextEnrich: Node<String, String> = nodesCommon.nodeAppendAdditionalData()
        val memoryRecall: Node<String, String> = nodesMemory.recall()
        val nodeClassify: Node<String, String> = nodesClassify.node(CLASSIFY_NODE_NAME)
        val nodeSkillInventory: Node<String, String> = nodesSkillInventory.node(
            skillTools = coreTools,
            name = SKILL_INVENTORY_NODE_NAME,
        )
        val nodeMcp: Node<String, String> = nodesMCP.nodeProvideMcpTools("MCP Node")
        val inputToHistory: Node<String, String> = nodesCommon.inputToHistory()
        val toolUse: Node<LLMResponse.Chat.Ok, String> = nodesToolUseWithKnowledge.node(
            alwaysInlineToolNames = alwaysInlineResultTools.mapTo(mutableSetOf()) { it.fn.name },
        )
        val finalizeTurn: Node<LLMResponse.Chat.Ok, String> = nodesMemory.finalizeTurn(
            summarization = nodesSummarization.summarize(),
        )

        nodeInput.edgeTo(inputToHistory)
        inputToHistory.edgeTo(memoryRecall)
        memoryRecall.edgeTo(nodeClassify)
        nodeClassify.edgeTo(nodeSkillInventory)
        nodeSkillInventory.edgeTo(nodeMcp)
        nodeMcp.edgeTo(contextEnrich)
        contextEnrich.edgeTo(chatSubgraph)
        chatSubgraph.edgeTo { ctx ->
            when (ctx.input) {
                is LLMResponse.Chat.Error -> chatErrorToFinish
                is LLMResponse.Chat.Ok -> chatOk
            }
        }
        chatOk.edgeTo { ctx -> if (ctx.input.isToolUse) toolUse else finalizeTurn }
        toolUse.edgeTo(chatSubgraph)
        finalizeTurn.edgeTo(nodeFinish)
        chatErrorToFinish.edgeTo(nodeFinish)
    }

    override suspend fun cancelActiveJob() {
        executionDelegate.cancelActiveJob()
    }

    override suspend fun execute(ctx: AgentContext<String>): String =
        executeWithTrace(ctx).output

    override suspend fun executeWithTrace(
        ctx: AgentContext<String>,
        onActiveRunReady: suspend () -> Unit,
        onStep: GraphStepCallback?,
    ): AgentExecutionResult = executionDelegate.executeWithTrace(graph = graph, ctx = ctx, onStep = onStep)

    private val LLMResponse.Chat.Ok.isToolUse get() = choices.any { it.message.functionCall != null }
}
