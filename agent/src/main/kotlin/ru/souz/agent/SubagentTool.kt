package ru.souz.agent

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
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
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.tool.ToolCategory

/** A tool that builds and awaits a fresh child graph from host-prepared settings and capabilities. */
class SubagentTool(
    private val llmApi: LLMChatAPI,
    private val settingsProvider: AgentSettingsProvider,
    private val telemetry: AgentTelemetry = AgentTelemetry.NONE,
    private val logObjectMapper: ObjectMapper = restJsonMapper,
    private val prepare: suspend (Input, ToolInvocationMeta) -> Setup,
) : LLMToolSetup {
    private val logger = LoggerFactory.getLogger(SubagentTool::class.java)

    data class Input(
        val task: String,
        val skillIds: List<String> = emptyList(),
        val model: String? = null,
        val maxTurns: Int = 32,
    )

    data class Setup(
        val settings: AgentSettings,
        val tools: List<LLMToolSetup>,
        val systemPrompt: String,
    )

    override val fn = LLMRequest.Function(
        name = NAME,
        description = "Delegate a self-contained task to a child agent and wait for its final answer. " +
            "Include all needed context in task. The child sees only selected enabled tools or file-backed Skills, " +
            "starts with a fresh history, and cannot spawn another child. Empty skillIds grants no tools.",
        parameters = LLMRequest.Parameters(
            type = "object",
            properties = mapOf(
                "task" to LLMRequest.Property("string", "Required task and all context needed to complete it."),
                "skillIds" to LLMRequest.Property(
                    "array", "Exact enabled compiled-tool names or file-backed Skill IDs. Defaults to no tools.",
                    items = LLMRequest.Property("string"),
                ),
                "model" to LLMRequest.Property("string", "Optional available model in the parent's provider. Defaults to the parent's model."),
                "maxTurns" to LLMRequest.Property("integer", "Maximum child LLM calls, from 1 to 128. Defaults to 32."),
            ),
            required = listOf("task"),
        ),
    )

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
        invoke(functionCall, ToolInvocationMeta.localDefault())

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta): LLMRequest.Message {
        // Return failures as tool results so parent graph retries cannot replay child side effects.
        val result = try {
            val input = try {
                restJsonMapper.convertValue(functionCall.arguments, Input::class.java)
            } catch (error: IllegalArgumentException) {
                throw SubagentInputException("invalid_subagent_input", error.message ?: "Invalid subagent arguments.")
            }
            if (input.task.isBlank() || input.maxTurns !in 1..128) {
                throw SubagentInputException("invalid_subagent_input", "task must be nonblank and maxTurns must be between 1 and 128.")
            }
            val setup = prepare(input, meta)
            val selectedTools = setup.tools.associateBy { it.fn.name }
            require(selectedTools.size == setup.tools.size) { "Subagent tool names must be unique." }
            val childContext = AgentContext(
                input = input.task,
                settings = setup.settings.copy(tools = AgentTools(
                    byCategory = emptyMap(),
                    byName = selectedTools,
                    categoryByName = selectedTools.keys.associateWith { setup.settings.tools.categoryByName[it] ?: ToolCategory.CHAT },
                )),
                history = emptyList(),
                activeTools = selectedTools.values.map { it.fn },
                systemPrompt = setup.systemPrompt,
                toolInvocationMeta = meta,
                runtimeEventSink = AgentRuntimeEventSink.NONE,
            )
            val result = executionGraph(input.maxTurns).start(childContext) { step, node, from, _ ->
                val nodeInput = (from as AgentContext<*>).input
                val prettyInput = runCatching { logObjectMapper.writeValueAsString(nodeInput) }.getOrElse { nodeInput.toString() }
                logger.debug("Step: {}, node: {}, input: {}", step.index, node.name, prettyInput)
            }
            currentCoroutineContext().ensureActive()
            mapOf("result" to result.input)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val code = when (error) {
                is SubagentInputException -> error.code
                is SubagentTurnLimitException -> "subagent_turn_limit"
                else -> "subagent_failed"
            }
            mapOf("error" to mapOf("code" to code, "message" to (error.message ?: "Subagent execution failed.")))
        }
        return LLMRequest.Message(
            role = LLMMessageRole.function,
            content = restJsonMapper.writeValueAsString(result),
            name = functionCall.name,
        )
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

    companion object {
        const val NAME = "SpawnSubagent"
    }
}

class SubagentInputException(val code: String, message: String) : IllegalArgumentException(message)

private class SubagentTurnLimitException(maxTurns: Int) :
    IllegalStateException("Subagent reached its limit of $maxTurns model turns without a final answer.")
