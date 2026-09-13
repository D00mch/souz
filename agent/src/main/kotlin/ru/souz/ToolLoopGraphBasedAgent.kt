package ru.souz

import com.fasterxml.jackson.databind.ObjectMapper
import ru.souz.agent.Agent
import ru.souz.agent.AgentExecutionResult
import ru.souz.agent.AgentTurnLimitException
import ru.souz.agent.GraphStepCallback
import ru.souz.agent.graph.Graph
import ru.souz.agent.graph.Node
import ru.souz.agent.graph.RetryPolicy
import ru.souz.agent.graph.buildGraph
import ru.souz.agent.nodes.NodesLLM
import ru.souz.agent.nodes.NodesPlain
import ru.souz.agent.runtime.AgentToolExecutor
import ru.souz.agent.runtime.GraphExecutionDelegate
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.agent.state.AgentContext
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMResponse
import ru.souz.llms.restJsonMapper

/** A bounded model/tool loop using the caller's history, tools, and event sink. */
class ToolLoopGraphBasedAgent(
    llmApi: LLMChatAPI,
    settingsProvider: AgentSettingsProvider,
    private val maxTurns: Int = 32,
    telemetry: AgentTelemetry = AgentTelemetry.NONE,
    logObjectMapper: ObjectMapper = restJsonMapper,
) : Agent {
    init {
        require(maxTurns > 0) { "maxTurns must be positive." }
    }

    private val nodesLLM = NodesLLM(llmApi, settingsProvider)
    private val toolExecutor = AgentToolExecutor(telemetry)
    private val executionDelegate = GraphExecutionDelegate(logObjectMapper, ToolLoopGraphBasedAgent::class.java)
    override val sideEffects = nodesLLM.sideEffects

    override suspend fun cancelActiveJob() = executionDelegate.cancelActiveJob()

    override suspend fun execute(
        ctx: AgentContext<String>,
        onActiveRunReady: suspend () -> Unit,
        onStep: GraphStepCallback?,
    ): AgentExecutionResult {
        cancelActiveJob()
        onActiveRunReady()
        return executionDelegate.executeWithTrace(executionGraph(), ctx, onStep)
    }

    // Provider retries remain in the supplied API; graph retries must not replay tools.
    private fun executionGraph(): Graph<String, String> = buildGraph(name = "Tool loop", retryPolicy = RetryPolicy()) {
        var turns = 0
        val inputToHistory = NodesPlain.inputToHistory()
        val turnLimit = Node<String, String>("Check turn limit") { ctx ->
            if (turns >= maxTurns) throw AgentTurnLimitException(maxTurns)
            turns += 1
            ctx
        }
        val chat = nodesLLM.chat("LLM")
        val chatOk = Node<LLMResponse.Chat, LLMResponse.Chat.Ok>("Chat.Ok") { ctx ->
            ctx.map {
                when (val response = ctx.input) {
                    is LLMResponse.Chat.Ok -> response
                    is LLMResponse.Chat.Error -> error(
                        "Model request failed (${response.status}): ${response.message}",
                    )
                }
            }
        }
        val toolUse = NodesPlain.toolUse(toolExecutor)
        val finalAnswer = NodesPlain.responseToString()

        nodeInput.edgeTo(inputToHistory)
        inputToHistory.edgeTo(turnLimit)
        turnLimit.edgeTo(chat)
        chat.edgeTo(chatOk)
        chatOk.edgeTo { ctx ->
            if (ctx.input.choices.any { it.message.functionCall != null }) toolUse else finalAnswer
        }
        toolUse.edgeTo(turnLimit)
        finalAnswer.edgeTo(nodeFinish)
    }
}
