package ru.souz.agent

import kotlinx.coroutines.flow.Flow
import ru.souz.agent.state.AgentContext
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

interface Agent {
    val sideEffects: Flow<AgentStreamChunk>
    suspend fun execute(ctx: AgentContext<String>): String
    suspend fun cancelActiveJob()
}

data class AgentExecutionResult(
    val output: String,
    val context: AgentContext<String>,
)
