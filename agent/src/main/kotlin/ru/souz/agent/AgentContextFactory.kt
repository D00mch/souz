package ru.souz.agent

import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.toSystemPromptMessage

class AgentContextFactory(
    private val settingsProvider: AgentSettingsProvider,
    private val systemPromptResolver: SystemPromptResolver,
    private val toolCatalog: AgentToolCatalog,
    private val availableAgents: List<AgentId> = listOf(AgentId.LUA_GRAPH, AgentId.GRAPH),
) {
    fun normalizeAgentId(agentId: AgentId): AgentId =
        if (agentId in availableAgents) agentId else AgentId.default

    fun systemPromptFor(agentId: AgentId, model: LLMModel): String =
        settingsProvider.getSystemPromptForAgentModel(agentId, model)
            ?: systemPromptResolver.defaultPrompt(
                agentId = agentId,
                model = model,
                regionProfile = settingsProvider.regionProfile,
            )

    fun create(agentId: AgentId): AgentContext<String> {
        val normalizedAgentId = normalizeAgentId(agentId)
        val model = settingsProvider.gigaModel
        return create(
            agentId = normalizedAgentId,
            history = emptyList(),
            model = model,
            contextSize = settingsProvider.contextSize,
            temperature = settingsProvider.temperature,
        )
    }

    fun create(
        agentId: AgentId,
        history: List<LLMRequest.Message>,
        model: LLMModel,
        contextSize: Int,
        temperature: Float,
        toolInvocationMeta: ToolInvocationMeta = ToolInvocationMeta.Empty,
    ): AgentContext<String> {
        val normalizedAgentId = normalizeAgentId(agentId)
        val settings = AgentSettings(
            model = model.alias,
            temperature = temperature,
            toolsByCategory = toolCatalog.toolsByCategory,
            contextSize = contextSize,
        )
        val allFunctions = settings.tools.byName.values.map { it.fn }
        val systemPrompt = systemPromptFor(normalizedAgentId, model)

        return AgentContext(
            input = "",
            settings = settings,
            history = normalizeHistory(history, systemPrompt),
            activeTools = allFunctions,
            systemPrompt = systemPrompt,
            toolInvocationMeta = toolInvocationMeta,
        )
    }

    private fun normalizeHistory(
        history: List<LLMRequest.Message>,
        systemPrompt: String,
    ): List<LLMRequest.Message> {
        if (history.isEmpty()) return emptyList()
        val systemMessage = systemPrompt.toSystemPromptMessage()
        return when (history.firstOrNull()?.role) {
            LLMMessageRole.system -> listOf(systemMessage) + history.drop(1)
            else -> listOf(systemMessage) + history
        }
    }
}
