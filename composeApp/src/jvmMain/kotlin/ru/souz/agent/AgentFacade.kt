package ru.souz.agent

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import org.slf4j.LoggerFactory
import ru.souz.agent.engine.AgentContext
import ru.souz.agent.engine.AgentSettings
import ru.souz.agent.impl.GraphBasedAgent
import ru.souz.agent.impl.LuaGraphBasedAgent
import ru.souz.agent.session.GraphSessionService
import ru.souz.db.SettingsProvider
import ru.souz.giga.GigaModel
import ru.souz.tool.ToolsFactory
import ru.souz.tool.ToolActionListener

@OptIn(ExperimentalCoroutinesApi::class)
class AgentFacade(
    private val settingsProvider: SettingsProvider,
    private val systemPromptResolver: SystemPromptResolver,
    private val sessionService: GraphSessionService,
    private val toolsFactory: ToolsFactory,
    private val graphBasedAgent: GraphBasedAgent,
    private val luaGraphBasedAgent: LuaGraphBasedAgent,
) {
    private val l = LoggerFactory.getLogger(AgentFacade::class.java)

    val availableAgents: List<AgentId> = listOf(AgentId.LUA_GRAPH, AgentId.GRAPH)

    private val _activeAgentId = MutableStateFlow(normalizedActiveAgent(settingsProvider.activeAgentId))
    val activeAgentId: StateFlow<AgentId> = _activeAgentId.asStateFlow()

    private val _currentContext = MutableStateFlow(createInitialContext(_activeAgentId.value))
    val currentContext: StateFlow<AgentContext<String>> = _currentContext.asStateFlow()

    val sideEffects: Flow<String> = _activeAgentId.flatMapLatest { id -> agentById(id).sideEffects }

    fun setActiveAgent(agentId: AgentId) {
        val normalized = normalizedActiveAgent(agentId)
        if (normalized == _activeAgentId.value) return

        cancelActiveJob()
        settingsProvider.activeAgentId = normalized
        _activeAgentId.value = normalized
        clearContext()
    }

    fun updateSystemPrompt(prompt: String) {
        val model = settingsProvider.gigaModel
        settingsProvider.setSystemPromptForAgentModel(_activeAgentId.value, model, prompt)
        _currentContext.tryEmit(_currentContext.value.copy(systemPrompt = prompt))
    }

    fun resetSystemPrompt() {
        val model = settingsProvider.gigaModel
        settingsProvider.setSystemPromptForAgentModel(_activeAgentId.value, model, null)
        val prompt = defaultPromptFor(_activeAgentId.value, model)
        _currentContext.tryEmit(_currentContext.value.copy(systemPrompt = prompt))
    }

    fun clearContext(): Boolean {
        cancelActiveJob()
        return _currentContext.tryEmit(createInitialContext(_activeAgentId.value))
    }

    fun setContext(ctx: AgentContext<String>): Boolean {
        cancelActiveJob()
        return _currentContext.tryEmit(ctx)
    }

    fun setModel(model: GigaModel): String {
        settingsProvider.gigaModel = model
        val prompt = settingsProvider.getSystemPromptForAgentModel(_activeAgentId.value, model)
            ?: defaultPromptFor(_activeAgentId.value, model)
        val newSettings = _currentContext.value.settings.copy(model = model.alias)
        _currentContext.tryEmit(
            _currentContext.value.copy(settings = newSettings, systemPrompt = prompt)
        )
        return prompt
    }

    fun setTemperature(temperature: Float) {
        settingsProvider.temperature = temperature
        val newSettings = _currentContext.value.settings.copy(temperature = temperature)
        _currentContext.tryEmit(_currentContext.value.copy(settings = newSettings))
    }

    fun setContextSize(contextSize: Int) {
        settingsProvider.contextSize = contextSize
        val newSettings = _currentContext.value.settings.copy(contextSize = contextSize)
        _currentContext.tryEmit(_currentContext.value.copy(settings = newSettings))
    }

    fun cancelActiveJob() {
        agentById(_activeAgentId.value).cancelActiveJob()
    }

    suspend fun execute(input: String, toolActionListener: ToolActionListener? = null): String {
        cancelActiveJob()
        val seed = _currentContext.value.copy(input = input)
        val agent = agentById(_activeAgentId.value)

        sessionService.startTask(input)
        return try {
            val result = agent.executeWithTrace(
                ctx = seed,
                onStep = { step, node, from, to ->
                    sessionService.onStep(step, node, from, to)
                },
                toolActionListener = toolActionListener,
            )
            _currentContext.emit(result.context)
            result.output
        } finally {
            runCatching { sessionService.finishTask() }
                .onFailure { e -> l.warn("sessionService fail", e) }
        }
    }

    private fun createInitialContext(agentId: AgentId): AgentContext<String> {
        val model = settingsProvider.gigaModel
        val settings = AgentSettings(
            model = model.alias,
            temperature = settingsProvider.temperature,
            toolsByCategory = toolsFactory.toolsByCategory,
            contextSize = settingsProvider.contextSize,
        )
        val prompt = settingsProvider.getSystemPromptForAgentModel(agentId, model)
            ?: defaultPromptFor(agentId, model)
        val allFunctions = settings.tools.byName.values.map { it.fn }

        return AgentContext(
            input = "",
            settings = settings,
            history = emptyList(),
            activeTools = allFunctions,
            systemPrompt = prompt,
        )
    }

    private fun defaultPromptFor(agentId: AgentId, model: GigaModel): String =
        systemPromptResolver.defaultPrompt(
            agentId = agentId,
            model = model,
            regionProfile = settingsProvider.regionProfile,
        )

    private fun normalizedActiveAgent(agentId: AgentId): AgentId =
        if (agentId in availableAgents) agentId else AgentId.default

    private fun agentById(agentId: AgentId): TraceableAgent = when (agentId) {
        AgentId.GRAPH -> graphBasedAgent
        AgentId.LUA_GRAPH -> luaGraphBasedAgent
    }
}
