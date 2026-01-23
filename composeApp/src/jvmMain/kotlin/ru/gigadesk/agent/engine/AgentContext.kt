package ru.gigadesk.agent.engine

import ru.gigadesk.giga.GigaRequest
import ru.gigadesk.giga.GigaToolSetup
import ru.gigadesk.tool.ToolCategory

import ru.gigadesk.agent.planning.ExecutionPlan

// Immutable context threaded through the graph
data class AgentContext<I>(
    val input: I,
    val settings: AgentSettings,
    val history: List<GigaRequest.Message>,
    val activeTools: List<GigaRequest.Function>,
    val systemPrompt: String,
    val plan: ExecutionPlan? = null,
    val isComplex: Boolean = false,
    val relevantCategories: List<ToolCategory> = emptyList(),
) {
    inline fun <reified O> map(
        settings: AgentSettings = this.settings,
        history: List<GigaRequest.Message> = this.history,
        activeTools: List<GigaRequest.Function> = this.activeTools,
        systemPrompt: String = this.systemPrompt,
        plan: ExecutionPlan? = this.plan,
        isComplex: Boolean = this.isComplex,
        relevantCategories: List<ToolCategory> = this.relevantCategories,
        transform: (I) -> O = { it as O },
    ): AgentContext<O> = AgentContext(input = transform(input), settings, history, activeTools, systemPrompt, plan, isComplex, relevantCategories)
}

data class AgentSettings(
    val model: String,
    val temperature: Float,
    val toolsByCategory: Map<ToolCategory, Map<String, GigaToolSetup>>,
    val tools: Map<String, GigaToolSetup> = toolsByCategory.values
        .flatMap { it.entries }
        .associate { it.key to it.value }
)
