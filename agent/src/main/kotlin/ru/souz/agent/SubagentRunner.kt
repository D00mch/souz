package ru.souz.agent

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.slf4j.LoggerFactory
import ru.souz.agent.graph.Graph
import ru.souz.agent.graph.Node
import ru.souz.agent.graph.RetryPolicy
import ru.souz.agent.graph.buildGraph
import ru.souz.agent.nodes.NodesLLM
import ru.souz.agent.nodes.inputToHistoryNode
import ru.souz.agent.nodes.responseToStringNode
import ru.souz.agent.nodes.toolUseNode
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.agent.runtime.AgentToolExecutor
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.agent.state.AgentTools
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.tool.ToolCategory

/** Runs each delegated task in a fresh graph with only its explicitly selected tools. */
class SubagentRunner(
    private val llmApi: LLMChatAPI,
    private val settingsProvider: AgentSettingsProvider,
    private val telemetry: AgentTelemetry = AgentTelemetry.NONE,
    private val logObjectMapper: ObjectMapper = restJsonMapper,
) {
    private val logger = LoggerFactory.getLogger(SubagentRunner::class.java)

    suspend fun execute(
        task: String,
        settings: AgentSettings,
        tools: List<LLMToolSetup>,
        systemPrompt: String,
        toolInvocationMeta: ToolInvocationMeta,
        maxTurns: Int = 32,
    ): AgentExecutionResult {
        require(maxTurns in 1..128) { "Subagent maxTurns must be between 1 and 128." }
        val selectedTools = tools.associateBy { it.fn.name }
        require(selectedTools.size == tools.size) { "Subagent tool names must be unique." }
        val childContext = AgentContext(
            input = task,
            settings = settings.copy(tools = AgentTools(
                byCategory = emptyMap(),
                byName = selectedTools,
                categoryByName = selectedTools.keys.associateWith { settings.tools.categoryByName[it] ?: ToolCategory.CHAT },
            )),
            history = emptyList(),
            activeTools = tools.map { it.fn },
            systemPrompt = systemPrompt,
            toolInvocationMeta = toolInvocationMeta,
            runtimeEventSink = AgentRuntimeEventSink.NONE,
        )
        val result = executionGraph(maxTurns).start(childContext) { step, node, from, _ ->
            val input = (from as AgentContext<*>).input
            val prettyInput = runCatching { logObjectMapper.writeValueAsString(input) }.getOrElse { input.toString() }
            logger.debug("Step: {}, node: {}, input: {}", step.index, node.name, prettyInput)
        }
        currentCoroutineContext().ensureActive()
        return AgentExecutionResult(output = result.input, context = result)
    }

    // Provider retries remain in the supplied API; graph retries must not replay tools.
    private fun executionGraph(maxTurns: Int): Graph<String, String> = buildGraph(name = "Subagent", retryPolicy = RetryPolicy()) {
        var turns = 0
        val inputToHistory = inputToHistoryNode()
        val turnLimit = Node<String, String>("Check turn limit") { ctx ->
            if (turns >= maxTurns) throw SubagentTurnLimitException(maxTurns)
            turns += 1
            ctx
        }
        val chat = NodesLLM(llmApi, settingsProvider).chat("LLM")
        val chatOk = Node<LLMResponse.Chat, LLMResponse.Chat.Ok>("Chat.Ok") { ctx ->
            ctx.map {
                when (val response = ctx.input) {
                    is LLMResponse.Chat.Ok -> response
                    is LLMResponse.Chat.Error -> error(
                        "Subagent model request failed (${response.status}): ${response.message}",
                    )
                }
            }
        }
        val toolUse = toolUseNode(AgentToolExecutor(telemetry))
        val finalAnswer = responseToStringNode()

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

class SubagentTurnLimitException(val maxTurns: Int) :
    IllegalStateException("Subagent reached its limit of $maxTurns model turns without a final answer.")
