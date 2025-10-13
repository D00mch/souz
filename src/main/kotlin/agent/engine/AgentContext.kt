package com.dumch.agent.engine

import com.dumch.giga.GigaRequest
import com.dumch.giga.GigaToolSetup
import com.dumch.tool.ToolCategory

// Immutable context threaded through the graph
data class AgentContext<I>(
    val input: I,
    val settings: AgentSettings,
    val history: List<GigaRequest.Message>,
    val activeTools: List<GigaRequest.Function>,
    val systemPrompt: String,
) {
    inline fun <reified O> map(
        settings: AgentSettings = this.settings,
        history: List<GigaRequest.Message> = this.history,
        activeTools: List<GigaRequest.Function> = this.activeTools,
        systemPrompt: String = this.systemPrompt,
        transform: (I) -> O,
    ): AgentContext<O> = AgentContext(input = transform(input), settings, history, activeTools, systemPrompt)
}

data class AgentSettings(
    val model: String,
    val temperature: Float,
    val toolsByCategory: Map<ToolCategory, Map<String, GigaToolSetup>>,
    val tools: Map<String, GigaToolSetup> = toolsByCategory.values
        .flatMap { it.entries }
        .associate { it.key to it.value }
)
