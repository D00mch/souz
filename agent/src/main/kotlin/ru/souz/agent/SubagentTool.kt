package ru.souz.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.agent.runtime.SubagentTasks
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper

/** Starts isolated children owned by the current parent execution. */
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
        val reasoningEffort: String? = null,
    )

    /** [settings] must contain only the child's selected tools; schemas are derived from that lookup. */
    data class Setup(
        val settings: AgentSettings,
        val systemPrompt: String,
    )

    override val fn = LLMRequest.Function(
        name = NAME,
        description = "Start a self-contained child task and return its subagentId immediately. " +
            "Continue working, inspect with GetSubagentStatus, wait with WaitSubagent, or stop it with CancelSubagent. " +
            "Unfinished children are cancelled when you finish; collect needed results before your final answer. " +
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
                "reasoningEffort" to LLMRequest.Property(
                    "string", "Optional child reasoning effort. Omit to use provider defaults; support depends on the provider and model.",
                    enum = REASONING_EFFORTS,
                ),
            ),
            required = listOf("task"),
        ),
    )

    val tools: List<LLMToolSetup> = listOf(this) + listOf(STATUS, WAIT, CANCEL).map(::SubagentLifecycleTool)

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
        invoke(functionCall, ToolInvocationMeta.localDefault())

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta): LLMRequest.Message {
        return subagentMessage(functionCall) {
            val input = try {
                restJsonMapper.convertValue(functionCall.arguments, Input::class.java)
            } catch (error: IllegalArgumentException) {
                throw SubagentInputException("invalid_subagent_input", error.message ?: "Invalid subagent arguments.")
            }
            if (input.task.isBlank() || input.maxTurns !in 1..128) {
                throw SubagentInputException("invalid_subagent_input", "task must be nonblank and maxTurns must be between 1 and 128.")
            }
            if (input.reasoningEffort != null && input.reasoningEffort !in REASONING_EFFORTS) {
                throw SubagentInputException("invalid_subagent_input", "reasoningEffort must be one of $REASONING_EFFORTS.")
            }
            val tasks = SubagentTasks.current()
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
            val id = tasks.spawn {
                try {
                    val result = createAgent(input.maxTurns).execute(childContext)
                    currentCoroutineContext().ensureActive()
                    mapOf("status" to "completed", "result" to result.output)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    subagentFailure(error) + mapOf("status" to "failed") +
                        if (error is AgentTurnLimitException) mapOf("progress" to turnLimitProgress(error)) else emptyMap()
                }
            }
            mapOf("subagentId" to id)
        }
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
        const val STATUS = "GetSubagentStatus"
        const val WAIT = "WaitSubagent"
        const val CANCEL = "CancelSubagent"
        val NAMES = setOf(NAME, STATUS, WAIT, CANCEL)
        private val REASONING_EFFORTS = listOf("minimal", "low", "medium", "high")
        private const val MAX_REPORTED_TOOL_CALLS = 8
        private const val MAX_TOOL_RESULT_CHARS = 1024
        private const val MAX_TOOL_ID_CHARS = 256
    }
}

private class SubagentLifecycleTool(private val name: String) : LLMToolSetup {
    data class Input(val subagentId: String, val timeoutMs: Long = 1000)

    override val fn = LLMRequest.Function(
        name = name,
        description = when (name) {
            SubagentTool.CANCEL -> "Cancel one child without stopping the parent or other children. Cancellation is cooperative and does not undo side effects."
            SubagentTool.WAIT -> "Wait up to timeoutMs for a child. A timeout returns running without cancelling it."
            else -> "Read a child's status and final result or error without waiting. Results remain readable until this parent execution ends."
        },
        parameters = LLMRequest.Parameters(
            "object",
            properties = mapOf("subagentId" to LLMRequest.Property("string", "ID returned by SpawnSubagent in this execution.")) +
                if (name == SubagentTool.WAIT) mapOf("timeoutMs" to LLMRequest.Property("integer", "Wait from 0 to 30000 milliseconds; default 1000. Zero polls.")) else emptyMap(),
            required = listOf("subagentId"),
        ),
    )

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message = subagentMessage(functionCall) {
        val input = try {
            restJsonMapper.convertValue(functionCall.arguments, Input::class.java)
        } catch (error: IllegalArgumentException) {
            throw SubagentInputException("invalid_subagent_input", "Expected subagentId and optional integer timeoutMs.")
        }
        if (input.subagentId.isBlank() || input.timeoutMs !in 0..30_000) {
            throw SubagentInputException("invalid_subagent_input", "subagentId must be nonblank and timeoutMs must be between 0 and 30000.")
        }
        SubagentTasks.current().status(input.subagentId, if (name == SubagentTool.WAIT) input.timeoutMs else 0, name == SubagentTool.CANCEL)
    }
}

private suspend fun subagentMessage(call: LLMResponse.FunctionCall, execute: suspend () -> Map<String, Any>): LLMRequest.Message {
    val result = try {
        execute()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        subagentFailure(error)
    }
    currentCoroutineContext().ensureActive()
    return LLMRequest.Message(LLMMessageRole.function, restJsonMapper.writeValueAsString(result), name = call.name)
}

private fun subagentFailure(error: Exception): Map<String, Any> = mapOf("error" to mapOf(
    "code" to when (error) {
        is SubagentInputException -> error.code
        is AgentTurnLimitException -> "subagent_turn_limit"
        else -> "subagent_failed"
    },
    "message" to (error.message ?: "Subagent execution failed."),
))

class SubagentInputException(val code: String, message: String) : IllegalArgumentException(message)
