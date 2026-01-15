package ru.gigadesk.ui.settings

import androidx.lifecycle.viewModelScope
import io.ktor.util.logging.debug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.gigadesk.agent.DEFAULT_SYSTEM_PROMPT
import ru.gigadesk.agent.GraphBasedAgent
import ru.gigadesk.db.ConfigStore
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.giga.GigaResponse
import ru.gigadesk.giga.GigaRestChatAPI
import ru.gigadesk.tool.config.ToolSoundConfig
import ru.gigadesk.tool.ToolRunBashCommand
import ru.gigadesk.tool.calendar.CalendarAppleScriptCommands
import ru.gigadesk.ui.BaseViewModel
import ru.gigadesk.ui.settings.SettingsEvent.*

class SettingsViewModel(
    override val di: DI,
) : BaseViewModel<SettingsState, SettingsEvent, SettingsEffect>(), DIAware {

    private val l = LoggerFactory.getLogger(GigaRestChatAPI::class.java)
    private val keysProvider: SettingsProvider by di.instance()
    private val chatApi: GigaRestChatAPI by di.instance()
    private val graphBasedAgent: GraphBasedAgent by di.instance()
    private val supportLogSender = SupportLogSender()

    init {
        viewModelScope.launch {
            val voiceSpeed = ConfigStore.get(ToolSoundConfig.SPEED_KEY, ToolSoundConfig.DEFAULT_SPEED)
            setState {
                copy(
                    gigaChatKey = keysProvider.gigaChatKey ?: "",
                    saluteSpeechKey = keysProvider.saluteSpeechKey ?: "",
                    useFewShotExamples = keysProvider.useFewShotExamples,
                    gigaModel = keysProvider.gigaModel,
                    supportEmail = keysProvider.supportEmail ?: DEFAULT_SUPPORT_EMAIL,
                    systemPrompt = keysProvider.systemPrompt ?: DEFAULT_SYSTEM_PROMPT,
                    defaultCalendar = keysProvider.defaultCalendar,
                    voiceSpeed = voiceSpeed,
                    voiceSpeedInput = voiceSpeed.toString(),
                )
            }
            fetchBalance()
            fetchCalendars()
        }
    }

    override fun initialState(): SettingsState = SettingsState()

    override suspend fun handleEvent(event: SettingsEvent) {
        l.debug { "handleEvent: $event" }
        when(event) {
            is InputGigaChatKey -> {
                keysProvider.gigaChatKey = event.key
                setState { copy(gigaChatKey = event.key) }
                fetchBalance()
            }
            is InputSaluteSpeechKey -> {
                keysProvider.saluteSpeechKey = event.key
                setState { copy(saluteSpeechKey = event.key) }
            }
            is InputUseFewShotExamples -> {
                keysProvider.useFewShotExamples = event.enabled
                setState { copy(useFewShotExamples = event.enabled) }
            }
            is SelectModel -> {
                graphBasedAgent.updateModel(event.model)
                setState { copy(gigaModel = event.model) }
            }
            is InputSupportEmail -> {
                keysProvider.supportEmail = event.email
                setState { copy(supportEmail = event.email, sendLogsMessage = null, sendLogsPath = null) }
            }
            is InputSystemPrompt -> {
                graphBasedAgent.updateSystemPrompt(event.prompt)
                setState { copy(systemPrompt = event.prompt) }
                send(SettingsEffect.NotifyOnSystemPrompt)
            }
            is InputVoiceSpeed -> {
                val normalized = event.speed.filter { it.isDigit() }
                val newSpeed = normalized.toIntOrNull()
                if (newSpeed != null) {
                    ConfigStore.put(ToolSoundConfig.SPEED_KEY, newSpeed)
                }
                setState { copy(voiceSpeedInput = normalized, voiceSpeed = newSpeed ?: voiceSpeed) }
            }
            ResetSystemPrompt -> {
                graphBasedAgent.resetSystemPrompt()
                send(SettingsEffect.NotifyOnSystemPrompt)
                setState { copy(systemPrompt = DEFAULT_SYSTEM_PROMPT) }
            }
            is SendLogsToSupport -> sendLogs()
            RefreshBalance -> fetchBalance()
            GoToMain -> {
                send(SettingsEffect.CloseScreen)
            }
            is SelectDefaultCalendar -> {
                keysProvider.defaultCalendar = event.name
                setState { copy(defaultCalendar = event.name) }
            }
            FetchCalendars -> fetchCalendars()
        }
    }

    override suspend fun handleSideEffect(effect: SettingsEffect) = when (effect) {
        SettingsEffect.CloseScreen,
        SettingsEffect.NotifyOnSystemPrompt -> l.debug { "ignore effect: $effect" }
    }

    private fun fetchCalendars() = viewModelScope.launch(Dispatchers.IO) {
        setState { copy(isLoadingCalendars = true) }

        val cmd = CalendarAppleScriptCommands.listCalendarsCommand("")

        val result = runCatching {
            ToolRunBashCommand.sh(cmd)
        }.getOrElse {
            l.error("Error fetching calendars", it)
            ""
        }

        val calendars = result
            .lines()
            .asSequence()
            .map { it.trim() }
            .filter { it.startsWith("- ") }
            .map { it.removePrefix("- ").trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toList()

        setState {
            copy(
                availableCalendars = calendars,
                isLoadingCalendars = false
            )
        }
    }

    private fun sendLogs() = viewModelScope.launch(Dispatchers.IO) {
        val email = currentState.supportEmail.ifBlank { DEFAULT_SUPPORT_EMAIL }
        val resolvedLogDir = runCatching { supportLogSender.logDirectory().toAbsolutePath().toString() }.getOrNull()
        setState {
            copy(
                isSendingLogs = true,
                sendLogsMessage = null,
                supportEmail = email,
                sendLogsPath = resolvedLogDir
            )
        }

        val result = runCatching { supportLogSender.sendLatestLogs(email) }
        result
            .onSuccess { sendResult ->
                setState {
                    copy(
                        isSendingLogs = false,
                        sendLogsMessage = sendResult.message,
                        supportEmail = sendResult.recipient,
                        sendLogsPath = sendResult.logArchive.toAbsolutePath().toString(),
                    )
                }
            }
            .onFailure { error ->
                setState {
                    copy(
                        isSendingLogs = false,
                        sendLogsMessage = error.message ?: "Не удалось отправить логи",
                        sendLogsPath = resolvedLogDir,
                    )
                }
            }
    }

    private fun fetchBalance() = viewModelScope.launch(Dispatchers.IO) {
        if (currentState.isBalanceLoading) return@launch

        val key = currentState.gigaChatKey
        if (key.isBlank()) {
            setState { copy(balance = emptyList(), balanceError = "Укажите GigaChat ключ", isBalanceLoading = false) }
            return@launch
        }

        setState { copy(isBalanceLoading = true, balanceError = null) }

        when (val result = chatApi.balance()) {
            is GigaResponse.Balance.Ok -> setState {
                copy(
                    balance = result.balance,
                    isBalanceLoading = false,
                    balanceError = null,
                )
            }

            is GigaResponse.Balance.Error -> setState {
                copy(
                    balance = emptyList(),
                    isBalanceLoading = false,
                    balanceError = result.message,
                )
            }
        }
    }
}
