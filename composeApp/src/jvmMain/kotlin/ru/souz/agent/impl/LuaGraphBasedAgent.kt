package ru.souz.agent.impl

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.Flow
import org.kodein.di.DI
import org.kodein.di.instance
import ru.souz.agent.AgentExecutionResult
import ru.souz.agent.GraphStepCallback
import ru.souz.agent.TraceableAgent
import ru.souz.agent.engine.AgentContext
import ru.souz.agent.engine.Graph
import ru.souz.agent.engine.Node
import ru.souz.agent.engine.buildGraph
import ru.souz.agent.nodes.NodesClassification
import ru.souz.agent.nodes.NodesCommon
import ru.souz.agent.nodes.NodesErrorHandling
import ru.souz.agent.nodes.NodesLua
import ru.souz.agent.nodes.NodesMCP
import ru.souz.agent.nodes.NodesSummarization
import ru.souz.agent.runtime.GraphExecutionDelegateImpl
import ru.souz.agent.session.GraphSessionService
import ru.souz.giga.GigaResponse

class LuaGraphBasedAgent(
    di: DI,
    logObjectMapper: ObjectMapper,
) : TraceableAgent {

    private val nodesLua: NodesLua by di.instance()
    private val nodesCommon: NodesCommon by di.instance()
    private val nodesClassify: NodesClassification by di.instance()
    private val nodesErrorHandling: NodesErrorHandling by di.instance()
    private val nodesSummarization: NodesSummarization by di.instance()
    private val nodesMCP: NodesMCP by di.instance()
    private val executionDelegate = GraphExecutionDelegateImpl(logObjectMapper, LuaGraphBasedAgent::class.java)

    override val sideEffects: Flow<String> = nodesLua.sideEffects

    override val graph: Graph<String, String> = buildGraph(name = "LuaAgent") {
        val contextEnrich: Node<String, String> = nodesCommon.nodeAppendAdditionalData()
        val nodeClassify: Node<String, String> = nodesClassify.node(GraphSessionService.NODE_NAME_CLASSIFY)
        val nodeMcp: Node<String, String> = nodesMCP.nodeProvideMcpTools("MCP Node")
        val inputToHistory: Node<String, String> = nodesCommon.inputToHistory()
        val planLua: Node<String, GigaResponse.Chat> = nodesLua.plan()
        val chatOk: Node<GigaResponse.Chat, GigaResponse.Chat.Ok> = Node("Chat.Ok") { ctx ->
            ctx.map { ctx.input as GigaResponse.Chat.Ok }
        }
        val chatErrorToFinish: Node<GigaResponse.Chat, String> = nodesErrorHandling.chatErrorToFinish()
        val summary: Node<GigaResponse.Chat.Ok, String> = nodesSummarization.summarize()
        val responseToCode: Node<String, String> = nodesLua.responseToCode()
        val runLua: Node<String, NodesLua.LuaExecutionResult> = nodesLua.execute()
        val runLuaOk: Node<NodesLua.LuaExecutionResult, String> = nodesLua.executeSuccessToString()
        val runLuaError: Node<NodesLua.LuaExecutionResult, NodesLua.LuaExecutionResult.Failure> =
            nodesLua.executeFailureToRepair()
        val repairLua: Node<NodesLua.LuaExecutionResult.Failure, GigaResponse.Chat> = nodesLua.repair()

        nodeInput.edgeTo(inputToHistory)
        inputToHistory.edgeTo(nodeClassify)
        nodeClassify.edgeTo(nodeMcp)
        nodeMcp.edgeTo(contextEnrich)
        contextEnrich.edgeTo(planLua)
        planLua.edgeTo { ctx ->
            when (ctx.input) {
                is GigaResponse.Chat.Error -> chatErrorToFinish
                is GigaResponse.Chat.Ok -> chatOk
            }
        }
        chatOk.edgeTo(summary)
        summary.edgeTo(responseToCode)
        responseToCode.edgeTo(runLua)
        runLua.edgeTo { ctx ->
            when (ctx.input) {
                is NodesLua.LuaExecutionResult.Success -> runLuaOk
                is NodesLua.LuaExecutionResult.Failure -> runLuaError
            }
        }
        runLuaError.edgeTo(repairLua)
        repairLua.edgeTo { ctx ->
            when (ctx.input) {
                is GigaResponse.Chat.Error -> chatErrorToFinish
                is GigaResponse.Chat.Ok -> chatOk
            }
        }
        runLuaOk.edgeTo(nodeFinish)
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
}
