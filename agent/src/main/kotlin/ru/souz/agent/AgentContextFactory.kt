package ru.souz.agent

import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMModel

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
        val settings = AgentSettings(
            model = model.alias,
            temperature = settingsProvider.temperature,
            toolsByCategory = toolCatalog.toolsByCategory,
            contextSize = settingsProvider.contextSize,
        )
        val allFunctions = settings.tools.byName.values.map { it.fn }

        return AgentContext(
            input = "",
            settings = settings,
            history = emptyList(),
            activeTools = allFunctions,
            systemPrompt = systemPromptFor(normalizedAgentId, model),
        )
    }
}
