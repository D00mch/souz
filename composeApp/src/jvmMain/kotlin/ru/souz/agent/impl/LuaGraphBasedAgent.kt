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
import ru.souz.agent.nodes.NodesLua
import ru.souz.agent.nodes.NodesMCP
import ru.souz.agent.runtime.GraphExecutionDelegateImpl
import ru.souz.agent.session.GraphSessionService

class LuaGraphBasedAgent(
    di: DI,
    logObjectMapper: ObjectMapper,
) : TraceableAgent {

    private val nodesLua: NodesLua by di.instance()
    private val nodesCommon: NodesCommon by di.instance()
    private val nodesClassify: NodesClassification by di.instance()
    private val nodesMCP: NodesMCP by di.instance()
    private val executionDelegate = GraphExecutionDelegateImpl(logObjectMapper, LuaGraphBasedAgent::class.java)

    override val sideEffects: Flow<String> = nodesLua.sideEffects

    override val graph: Graph<String, String> = buildGraph(name = "LuaAgent") {
        val contextEnrich: Node<String, String> = nodesCommon.nodeAppendAdditionalData()
        val nodeClassify: Node<String, String> = nodesClassify.node(GraphSessionService.NODE_NAME_CLASSIFY)
        val nodeMcp: Node<String, String> = nodesMCP.nodeProvideMcpTools("MCP Node")
        val inputToHistory: Node<String, String> = nodesCommon.inputToHistory()
        val planLua: Node<String, String> = nodesLua.plan()
        val runLua: Node<String, String> = nodesLua.execute()

        nodeInput.edgeTo(inputToHistory)
        inputToHistory.edgeTo(nodeClassify)
        nodeClassify.edgeTo(nodeMcp)
        nodeMcp.edgeTo(contextEnrich)
        contextEnrich.edgeTo(planLua)
        planLua.edgeTo(runLua)
        runLua.edgeTo(nodeFinish)
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
