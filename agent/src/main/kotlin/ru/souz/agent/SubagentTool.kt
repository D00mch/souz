package ru.souz.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper

/** Prepares an isolated context and awaits a fresh agent from the host-supplied factory. */
class SubagentTool(
    private val createAgent: (maxTurns: Int) -> Agent,
    modelChoices: List<String>? = null,
    private val prepare: suspend (Input, ToolInvocationMeta) -> Setup,
) : LLMToolSetup {
    data class Input(
        val task: String,
        val skillIds: List<String> = emptyList(),
        val model: String? = null,
        val maxTurns: Int = 32,
    )

    /** [settings] must contain only the child's selected tools; schemas are derived from that lookup. */
    data class Setup(
        val settings: AgentSettings,
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
                "model" to LLMRequest.Property("string", "Optional exact model ID from the advertised choices. Defaults to the parent's model.", enum = modelChoices),
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
            val childContext = AgentContext(
                input = input.task,
                settings = setup.settings,
                history = emptyList(),
                activeTools = setup.settings.tools.byName.values.map { it.fn },
                systemPrompt = setup.systemPrompt,
                toolInvocationMeta = meta,
                runtimeEventSink = AgentRuntimeEventSink.NONE,
            )
            val result = createAgent(input.maxTurns).execute(childContext)
            currentCoroutineContext().ensureActive()
            mapOf("result" to result.output)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val code = when (error) {
                is SubagentInputException -> error.code
                is AgentTurnLimitException -> "subagent_turn_limit"
                else -> "subagent_failed"
            }
            val progress = if (error is AgentTurnLimitException) {
                mapOf("status" to "incomplete", "progress" to turnLimitProgress(error))
            } else emptyMap()
            mapOf("error" to mapOf("code" to code, "message" to (error.message ?: "Subagent execution failed."))) + progress
        }
        return LLMRequest.Message(
            role = LLMMessageRole.function,
            content = restJsonMapper.writeValueAsString(result),
            name = functionCall.name,
        )
    }

    private fun turnLimitProgress(error: AgentTurnLimitException): Map<String, Any> = mapOf(
        "modelTurns" to error.maxTurns,
        "sideEffectsMayHaveOccurred" to true,
        "completedToolCallCount" to error.toolResults.size,
        "omittedToolCallCount" to (error.toolResults.size - MAX_REPORTED_TOOL_CALLS).coerceAtLeast(0),
        "completedToolCalls" to error.toolResults.takeLast(MAX_REPORTED_TOOL_CALLS).map { result ->
            val fields = mapOf(
                "toolCallId" to result.functionsStateId, "name" to result.name, "result" to result.content,
            )
            val bounded = fields.mapValues { (key, value) ->
                value?.take(if (key == "result") MAX_TOOL_RESULT_CHARS else MAX_TOOL_ID_CHARS)
            }
            bounded + ("truncated" to (bounded != fields))
        },
    )

    companion object {
        const val NAME = "SpawnSubagent"
        private const val MAX_REPORTED_TOOL_CALLS = 8
        private const val MAX_TOOL_RESULT_CHARS = 1024
        private const val MAX_TOOL_ID_CHARS = 256
    }
}

class SubagentInputException(val code: String, message: String) : IllegalArgumentException(message)
