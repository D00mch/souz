package ru.souz.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import ru.souz.agent.engine.AgentContext
import ru.souz.giga.GigaModel

interface Agent {
    val currentContext: StateFlow<AgentContext<String>>
    val sideEffects: Flow<String>

    fun clearContext(): Boolean
    fun setContext(ctx: AgentContext<String>): Boolean
    fun updateSystemPrompt(prompt: String)
    fun resetSystemPrompt()
    fun setModel(model: GigaModel): String
    fun setTemperature(temperature: Float)
    fun setContextSize(contextSize: Int)
    fun cancelActiveJob()

    suspend fun execute(input: String): String
}
