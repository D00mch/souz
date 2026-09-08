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
import ru.souz.tool.ToolCategory

/** Prepares an isolated context and awaits a fresh agent from the host-supplied factory. */
class SubagentTool(
    private val createAgent: (maxTurns: Int) -> Agent,
    private val prepare: suspend (Input, ToolInvocationMeta) -> Setup,
) : LLMToolSetup {
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
            val childContext = AgentContext(
                input = input.task,
                settings = setup.settings,
                history = emptyList(),
                activeTools = emptyList(),
                systemPrompt = setup.systemPrompt,
                toolInvocationMeta = meta,
                runtimeEventSink = AgentRuntimeEventSink.NONE,
            ).withOnlyTools(setup.tools, setup.tools.associate {
                it.fn.name to (setup.settings.tools.categoryByName[it.fn.name] ?: ToolCategory.CHAT)
            })
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
            mapOf("error" to mapOf("code" to code, "message" to (error.message ?: "Subagent execution failed.")))
        }
        return LLMRequest.Message(
            role = LLMMessageRole.function,
            content = restJsonMapper.writeValueAsString(result),
            name = functionCall.name,
        )
    }

    companion object {
        const val NAME = "SpawnSubagent"
    }
}

class SubagentInputException(val code: String, message: String) : IllegalArgumentException(message)
