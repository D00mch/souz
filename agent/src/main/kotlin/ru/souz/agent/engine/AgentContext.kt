package ru.souz.agent.engine

import ru.souz.llms.DEFAULT_MAX_TOKENS
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMToolSetup
import ru.souz.tool.ToolCategory

// Immutable context threaded through the graph
data class AgentContext<I>(
    val input: I,
    val settings: AgentSettings,
    val history: List<LLMRequest.Message>,
    val activeTools: List<LLMRequest.Function>,
    val systemPrompt: String,
) {
    inline fun <reified O> map(
        settings: AgentSettings = this.settings,
        history: List<LLMRequest.Message> = this.history,
        activeTools: List<LLMRequest.Function> = this.activeTools,
        systemPrompt: String = this.systemPrompt,
        transform: (I) -> O = { it as O },
    ): AgentContext<O> = AgentContext(input = transform(input), settings, history, activeTools, systemPrompt)
}

/** Wrapper to simplify accessing tools related data */
data class AgentTools(
    val byCategory: Map<ToolCategory, Map<String, LLMToolSetup>>,
    val byName: Map<String, LLMToolSetup> = byCategory.values.flatMap { it.entries }
        .associate { it.key to it.value },
    val categoryByName: Map<String, ToolCategory> = buildMap {
        byCategory.forEach { (category, name2tool) ->
            name2tool.keys.forEach { name -> put(name, category) }
        }
    }
)

data class AgentSettings(
    val model: String,
    val temperature: Float,
    val tools: AgentTools,
    val contextSize: Int = DEFAULT_MAX_TOKENS,
) {
    constructor(
        model: String,
        temperature: Float,
        toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>,
        contextSize: Int = DEFAULT_MAX_TOKENS,
    ): this(model, temperature, AgentTools(toolsByCategory), contextSize)
}