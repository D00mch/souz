package ru.souz.agent

import kotlinx.coroutines.flow.Flow
import ru.souz.agent.state.AgentContext
import ru.souz.llms.LLMResponse

sealed interface AgentSideEffect {
    @JvmInline
    value class Text(val v: String) : AgentSideEffect

    data class Fn(val call: LLMResponse.FunctionCall) : AgentSideEffect
}

interface Agent {
    val sideEffects: Flow<String>
    suspend fun execute(ctx: AgentContext<String>): String
    fun cancelActiveJob()
}

data class AgentExecutionResult(
    val output: String,
    val context: AgentContext<String>,
)
