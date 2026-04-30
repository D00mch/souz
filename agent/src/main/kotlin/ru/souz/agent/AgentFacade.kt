package ru.souz.agent

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import org.slf4j.LoggerFactory
import ru.souz.agent.state.AgentContext
import ru.souz.agent.runtime.AgentToolExecutor
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.session.GraphSessionService
import ru.souz.llms.LLMModel

@OptIn(ExperimentalCoroutinesApi::class)
class AgentFacade internal constructor(
    private val settingsProvider: AgentSettingsProvider,
    private val contextFactory: AgentContextFactory,
    private val executor: AgentExecutor,
    private val sessionService: GraphSessionService,
    private val agentToolExecutor: AgentToolExecutor,
) {
    private val l = LoggerFactory.getLogger(AgentFacade::class.java)

    val availableAgents: List<AgentId> = executor.availableAgents

    private val _activeAgentId = MutableStateFlow(contextFactory.normalizeAgentId(settingsProvider.activeAgentId))
    val activeAgentId: StateFlow<AgentId> = _activeAgentId.asStateFlow()

    private val _currentContext = MutableStateFlow(contextFactory.create(_activeAgentId.value))
    val currentContext: StateFlow<AgentContext<String>> = _currentContext.asStateFlow()

    val sideEffects: Flow<AgentSideEffect> = _activeAgentId.flatMapLatest { id ->
        merge(
            executor.sideEffects(id).map { AgentSideEffect.Text(it) },
            agentToolExecutor.toolInvocations.map { AgentSideEffect.Fn(it) },
        )
    }

    fun setActiveAgent(agentId: AgentId) {
        val normalized = contextFactory.normalizeAgentId(agentId)
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
        val prompt = contextFactory.systemPromptFor(_activeAgentId.value, model)
        _currentContext.tryEmit(_currentContext.value.copy(systemPrompt = prompt))
    }

    fun clearContext(): Boolean {
        cancelActiveJob()
        return _currentContext.tryEmit(contextFactory.create(_activeAgentId.value))
    }

    fun setContext(ctx: AgentContext<String>): Boolean {
        cancelActiveJob()
        return _currentContext.tryEmit(ctx)
    }

    fun setModel(model: LLMModel): String {
        settingsProvider.gigaModel = model
        val prompt = contextFactory.systemPromptFor(_activeAgentId.value, model)
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
        executor.cancelActiveJob(_activeAgentId.value)
    }

    suspend fun execute(input: String): String {
        cancelActiveJob()
        sessionService.startTask(input)
        return try {
            val result = executor.executeWithTrace(
                agentId = _activeAgentId.value,
                context = _currentContext.value,
                input = input,
            ) { step, node, from, to ->
                sessionService.onStep(step, node, from, to)
            }
            _currentContext.emit(result.context)
            result.output
        } finally {
            runCatching { sessionService.finishTask() }
                .onFailure { e -> l.warn("sessionService fail", e) }
        }
    }
}
