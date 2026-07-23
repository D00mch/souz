package ru.souz

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.Flow
import ru.souz.agent.AgentExecutionResult
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
import ru.souz.agent.nodes.NodesSkills
import ru.souz.agent.nodes.NodesSummarization
import ru.souz.agent.nodes.SKILLS_ACTIVATION_NODE_NAME
import ru.souz.agent.runtime.GraphExecutionDelegate
import ru.souz.agent.runtime.AgentToolBatchResume
import ru.souz.agent.state.AgentContext
import ru.souz.agent.runtime.GraphExecutionDelegateImpl
import ru.souz.llms.LLMResponse

class GraphBasedAgent internal constructor(
    logObjectMapper: ObjectMapper,
    private val nodesLLM: NodesLLM,
    private val nodesCommon: NodesCommon,
    private val nodesClassify: NodesClassification,
    private val nodesErrorHandling: NodesErrorHandling,
    private val nodesSummarization: NodesSummarization,
    private val nodesMCP: NodesMCP,
    private val nodesSkills: NodesSkills,
    private val executionDelegate: GraphExecutionDelegate = GraphExecutionDelegateImpl(
        logObjectMapper = logObjectMapper,
        loggerClass = GraphBasedAgent::class.java,
    ),
) : TraceableAgent {

    override val sideEffects: Flow<String> = nodesLLM.sideEffects

    private val graph: Graph<String, String> = buildGraph(name = "Agent") {
        val chatSubgraph: Node<String, LLMResponse.Chat> = nodesLLM.chat("LLM")
        val chatOk: Node<LLMResponse.Chat, LLMResponse.Chat.Ok> = Node("Chat.Ok") { ctx ->
            ctx.map { ctx.input as LLMResponse.Chat.Ok }
        }
        val chatErrorToFinish: Node<LLMResponse.Chat, String> = nodesErrorHandling.chatErrorToFinish()
        val contextEnrich: Node<String, String> = nodesCommon.nodeAppendAdditionalData()
        val nodeClassify: Node<String, String> = nodesClassify.node(CLASSIFY_NODE_NAME)
        val nodeSkillsActivation: Node<String, String> = nodesSkills.node(SKILLS_ACTIVATION_NODE_NAME)
        val nodeMcp: Node<String, String> = nodesMCP.nodeProvideMcpTools("MCP Node")
        val inputToHistory: Node<String, String> = nodesCommon.inputToHistory()
        val toolUse: Node<LLMResponse.Chat.Ok, String> = nodesCommon.toolUse()
        val summary: Node<LLMResponse.Chat.Ok, String> = nodesSummarization.summarize()

        nodeInput.edgeTo(inputToHistory)
        inputToHistory.edgeTo(nodeClassify)
        nodeClassify.edgeTo(nodeSkillsActivation)
        nodeSkillsActivation.edgeTo(nodeMcp)
        nodeMcp.edgeTo(contextEnrich)
        contextEnrich.edgeTo(chatSubgraph)
        chatSubgraph.edgeTo { ctx ->
            when (ctx.input) {
                is LLMResponse.Chat.Error -> chatErrorToFinish
                is LLMResponse.Chat.Ok -> chatOk
            }
        }
        chatOk.edgeTo { ctx -> if (ctx.input.isToolUse) toolUse else summary }
        toolUse.edgeTo(chatSubgraph)
        summary.edgeTo(nodeFinish)
        chatErrorToFinish.edgeTo(nodeFinish)
    }

    /**
     * Dedicated continuation graph for a durably stored tool batch.
     *
     * It intentionally omits input-to-history, classification, skill activation, MCP discovery,
     * and context enrichment. The stored batch is handled first and then rejoins the ordinary
     * LLM/tool/summary loop.
     */
    private val toolBatchResumeGraph: Graph<AgentToolBatchResume, String> =
        buildGraph(name = "AgentToolBatchResume") {
            val resumeToolUse: Node<AgentToolBatchResume, String> = nodesCommon.resumeToolUse()
            val chatSubgraph: Node<String, LLMResponse.Chat> = nodesLLM.chat("Resume LLM")
            val chatOk: Node<LLMResponse.Chat, LLMResponse.Chat.Ok> = Node("Resume Chat.Ok") { ctx ->
                ctx.map { ctx.input as LLMResponse.Chat.Ok }
            }
            val chatErrorToFinish: Node<LLMResponse.Chat, String> =
                nodesErrorHandling.chatErrorToFinish("Resume Chat.Error->Finish")
            val toolUse: Node<LLMResponse.Chat.Ok, String> = nodesCommon.toolUse("Resume toolUse")
            val summary: Node<LLMResponse.Chat.Ok, String> = nodesSummarization.summarize("Resume Summary")

            nodeInput.edgeTo(resumeToolUse)
            resumeToolUse.edgeTo(chatSubgraph)
            chatSubgraph.edgeTo { ctx ->
                when (ctx.input) {
                    is LLMResponse.Chat.Error -> chatErrorToFinish
                    is LLMResponse.Chat.Ok -> chatOk
                }
            }
            chatOk.edgeTo { ctx -> if (ctx.input.isToolUse) toolUse else summary }
            toolUse.edgeTo(chatSubgraph)
            summary.edgeTo(nodeFinish)
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
    ): AgentExecutionResult = executionDelegate.executeWithTrace(graph = graph, ctx = ctx, onStep = onStep)

    override suspend fun resumeToolBatchWithTrace(
        ctx: AgentContext<AgentToolBatchResume>,
        onStep: GraphStepCallback?,
    ): AgentExecutionResult = executionDelegate.executeWithTrace(
        graph = toolBatchResumeGraph,
        ctx = ctx,
        onStep = onStep,
    )

    private val LLMResponse.Chat.Ok.isToolUse get() = choices.any { it.message.functionCall != null }
}
