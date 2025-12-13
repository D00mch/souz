package ru.abledo.ui.settings

import androidx.lifecycle.viewModelScope
import io.ktor.util.logging.debug
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.abledo.db.SettingsProvider
import ru.abledo.giga.GigaResponse
import ru.abledo.giga.GigaRestChatAPI
import ru.abledo.ui.BaseViewModel
import ru.abledo.ui.settings.SettingsEvent.InputSupportEmail
import ru.abledo.ui.settings.SettingsEvent.SendLogsToSupport

class SettingsViewModel(
    override val di: DI,
) : BaseViewModel<SettingsState, SettingsEvent, SettingsEffect>(), DIAware {

    private val l = LoggerFactory.getLogger(GigaRestChatAPI::class.java)
    private val keysProvider: SettingsProvider by di.instance()
    private val chatApi: GigaRestChatAPI by di.instance()
    private val supportLogSender = SupportLogSender()

    init {
        viewModelScope.launch {
            setState {
                copy(
                    gigaChatKey = keysProvider.gigaChatKey ?: "",
                    saluteSpeechKey = keysProvider.saluteSpeechKey ?: "",
                    useFewShotExamples = keysProvider.useFewShotExamples,
                    supportEmail = keysProvider.supportEmail ?: DEFAULT_SUPPORT_EMAIL,
                )
            }
            fetchBalance()
        }
    }

    override fun initialState(): SettingsState = SettingsState()

    override suspend fun handleEvent(event: SettingsEvent) {
        l.debug { "handleEvent: $event" }
        when(event) {
            is SettingsEvent.InputGigaChatKey -> {
                keysProvider.gigaChatKey = event.key
                setState { copy(gigaChatKey = event.key) }
                fetchBalance()
            }
            is SettingsEvent.InputSaluteSpeechKey -> {
                keysProvider.saluteSpeechKey = event.key
                setState { copy(saluteSpeechKey = event.key) }
            }
            is SettingsEvent.InputUseFewShotExamples -> {
                keysProvider.useFewShotExamples = event.enabled
                setState { copy(useFewShotExamples = event.enabled) }
            }
            is InputSupportEmail -> {
                keysProvider.supportEmail = event.email
                setState { copy(supportEmail = event.email, sendLogsMessage = null) }
            }
            is SendLogsToSupport -> sendLogs()
            SettingsEvent.RefreshBalance -> fetchBalance()
            SettingsEvent.GoToMain -> {
                send(SettingsEffect.CloseScreen)
            }
        }
    }

    override suspend fun handleSideEffect(effect: SettingsEffect) {
        when (effect) {
            SettingsEffect.CloseScreen -> l.debug { "ignore effect: $effect" }
        }
    }

    private fun sendLogs() = ioLaunch {
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

    private fun fetchBalance() = ioLaunch {
        val key = currentState.gigaChatKey
        if (key.isBlank()) {
            setState { copy(balance = emptyList(), balanceError = "Укажите GigaChat ключ", isBalanceLoading = false) }
            return@ioLaunch
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