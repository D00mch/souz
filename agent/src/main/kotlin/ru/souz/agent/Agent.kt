package ru.souz.agent

import kotlinx.coroutines.flow.Flow
import ru.souz.agent.state.AgentContext
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse

sealed interface AgentSideEffect {
    data class Text(
        val v: String,
        val streamRevision: Long = 0L,
    ) : AgentSideEffect

    data class Fn(val call: LLMResponse.FunctionCall) : AgentSideEffect
}

/** Text produced by the LLM branch identified by [streamRevision]. */
data class AgentStreamChunk(
    val text: String,
    val streamRevision: Long,
)

/** Durable messages observed before one user input submitted to an active execution. */
data class ActiveRunInput(
    val history: List<LLMRequest.Message> = emptyList(),
    val input: String,
) {
    init {
        require(history.all { it.role == LLMMessageRole.user || it.role == LLMMessageRole.assistant }) {
            "Active-run history supports only user and assistant messages"
        }
    }
}

interface Agent {
    val sideEffects: Flow<AgentStreamChunk>

    suspend fun execute(ctx: AgentContext<String>): String
    suspend fun cancelActiveJob()
}

data class AgentExecutionResult(
    val output: String,
    val context: AgentContext<String>,
)
