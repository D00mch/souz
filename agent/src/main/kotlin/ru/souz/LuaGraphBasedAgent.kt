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
import ru.souz.agent.nodes.NodesLua
import ru.souz.agent.nodes.NodesMCP
import ru.souz.agent.nodes.NodesSummarization
import ru.souz.agent.runtime.GraphExecutionDelegate
import ru.souz.agent.state.AgentContext
import ru.souz.agent.runtime.GraphExecutionDelegateImpl
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMResponse

class LuaGraphBasedAgent internal constructor(
    logObjectMapper: ObjectMapper,
    private val nodesLua: NodesLua,
    private val nodesCommon: NodesCommon,
    private val nodesClassify: NodesClassification,
    private val nodesErrorHandling: NodesErrorHandling,
    private val nodesSummarization: NodesSummarization,
    private val nodesMCP: NodesMCP,
    private val executionDelegate: GraphExecutionDelegate = GraphExecutionDelegateImpl(
        logObjectMapper = logObjectMapper,
        loggerClass = LuaGraphBasedAgent::class.java,
    ),
) : TraceableAgent {

    override val sideEffects: Flow<String> = nodesLua.sideEffects

    private val graph: Graph<String, String> = buildGraph(name = "LuaAgent") {
        val contextEnrich: Node<String, String> = nodesCommon.nodeAppendAdditionalData()
        val nodeClassify: Node<String, String> = nodesClassify.node(CLASSIFY_NODE_NAME)
        val nodeMcp: Node<String, String> = nodesMCP.nodeProvideMcpTools("MCP Node")
        val inputToHistory: Node<String, String> = nodesCommon.inputToHistory()
        val planLua: Node<String, LLMResponse.Chat> = nodesLua.plan()
        val chatOk: Node<LLMResponse.Chat, LLMResponse.Chat.Ok> = Node("Chat.Ok") { ctx ->
            ctx.map { ctx.input as LLMResponse.Chat.Ok }
        }
        val chatErrorToFinish: Node<LLMResponse.Chat, String> = nodesErrorHandling.chatErrorToFinish()
        val summary: Node<LLMResponse.Chat.Ok, String> = nodesSummarization.summarize()
        val chatResponseToString: Node<LLMResponse.Chat.Ok, String> = nodesCommon.responseToString("Chat.Ok -> String")
        val responseToCode: Node<String, String> = nodesLua.responseToCode()
        val runLua: Node<String, NodesLua.LuaExecutionResult> = nodesLua.execute()
        val runLuaOk: Node<NodesLua.LuaExecutionResult, String> = nodesLua.executeSuccessToString()
        val runLuaOutputToChatOk: Node<String, LLMResponse.Chat.Ok> = Node("Lua Output -> Chat.Ok") { ctx ->
            ctx.map {
                LLMResponse.Chat.Ok(
                    choices = listOf(
                        LLMResponse.Choice(
                            message = LLMResponse.Message(
                                content = ctx.input,
                                role = LLMMessageRole.assistant,
                                functionsStateId = null,
                            ),
                            index = 0,
                            finishReason = LLMResponse.FinishReason.stop,
                        )
                    ),
                    created = System.currentTimeMillis(),
                    model = ctx.settings.model,
                    usage = LLMResponse.Usage(0, 0, 0, 0),
                )
            }
        }
        val runLuaError: Node<NodesLua.LuaExecutionResult, NodesLua.LuaExecutionResult.Failure> =
            nodesLua.executeFailureToRepair()
        val repairLua: Node<NodesLua.LuaExecutionResult.Failure, LLMResponse.Chat> = nodesLua.repair()

        nodeInput.edgeTo(inputToHistory)
        inputToHistory.edgeTo(nodeClassify)
        nodeClassify.edgeTo(nodeMcp)
        nodeMcp.edgeTo(contextEnrich)
        contextEnrich.edgeTo(planLua)
        planLua.edgeTo { ctx ->
            when (ctx.input) {
                is LLMResponse.Chat.Error -> chatErrorToFinish
                is LLMResponse.Chat.Ok -> chatOk
            }
        }
        chatOk.edgeTo(chatResponseToString)
        chatResponseToString.edgeTo(responseToCode)
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
                is LLMResponse.Chat.Error -> chatErrorToFinish
                is LLMResponse.Chat.Ok -> chatOk
            }
        }
        runLuaOk.edgeTo(runLuaOutputToChatOk)
        runLuaOutputToChatOk.edgeTo(summary)
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
}
