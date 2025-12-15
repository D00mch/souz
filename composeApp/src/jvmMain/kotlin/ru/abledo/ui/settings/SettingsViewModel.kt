package ru.abledo.ui.settings

import androidx.lifecycle.viewModelScope
import io.ktor.util.logging.debug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.abledo.agent.DEFAULT_SYSTEM_PROMPT
import ru.abledo.agent.GraphBasedAgent
import ru.abledo.db.SettingsProvider
import ru.abledo.giga.GigaResponse
import ru.abledo.giga.GigaRestChatAPI
import ru.abledo.tool.ToolRunBashCommand
import ru.abledo.tool.calendar.CalendarAppleScriptCommands
import ru.abledo.ui.BaseViewModel
import ru.abledo.ui.settings.SettingsEvent.*

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
            setState {
                copy(
                    gigaChatKey = keysProvider.gigaChatKey ?: "",
                    saluteSpeechKey = keysProvider.saluteSpeechKey ?: "",
                    useFewShotExamples = keysProvider.useFewShotExamples,
                    supportEmail = keysProvider.supportEmail ?: DEFAULT_SUPPORT_EMAIL,
                    systemPrompt = keysProvider.systemPrompt ?: DEFAULT_SYSTEM_PROMPT,
                    defaultCalendar = keysProvider.defaultCalendar
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
            is InputSupportEmail -> {
                keysProvider.supportEmail = event.email
                setState { copy(supportEmail = event.email, sendLogsMessage = null) }
            }
            is InputSystemPrompt -> {
                graphBasedAgent.updateSystemPrompt(event.prompt)
                setState { copy(systemPrompt = event.prompt) }
                send(SettingsEffect.NotifyOnSystemPrompt)
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
        setState { copy(isSendingLogs = true, sendLogsMessage = null, supportEmail = email) }

        val result = runCatching { supportLogSender.sendLatestLogs(email) }
        result
            .onSuccess { sendResult ->
                setState {
                    copy(
                        isSendingLogs = false,
                        sendLogsMessage = sendResult.message,
                        supportEmail = sendResult.recipient,
                    )
                }
            }
            .onFailure { error ->
                setState {
                    copy(
                        isSendingLogs = false,
                        sendLogsMessage = error.message ?: "Не удалось отправить логи",
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