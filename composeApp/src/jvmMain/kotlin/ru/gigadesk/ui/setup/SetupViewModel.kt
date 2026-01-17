package ru.gigadesk.ui.setup

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.gigadesk.audio.Say
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.ui.BaseViewModel

class SetupViewModel(
    override val di: DI,
) : BaseViewModel<SetupState, SetupEvent, SetupEffect>(), DIAware {

    private val logger = LoggerFactory.getLogger(SetupViewModel::class.java)
    private val settingsProvider: SettingsProvider by di.instance()
    private val say: Say by di.instance()

    init {
        viewModelScope.launch {
            val gigaChatKey = settingsProvider.gigaChatKey.orEmpty()
            val saluteSpeechKey = settingsProvider.saluteSpeechKey.orEmpty()
            updateKeysState(gigaChatKey, saluteSpeechKey)
            setState {
                copy(canProceed = gigaChatKey.isNotBlank() && saluteSpeechKey.isNotBlank())
            }
        }
    }

    override fun initialState(): SetupState = SetupState()

    override suspend fun handleEvent(event: SetupEvent) {
        when (event) {
            is SetupEvent.InputGigaChatKey -> {
                settingsProvider.gigaChatKey = event.key
                updateKeysState(event.key, currentState.saluteSpeechKey)
            }
            is SetupEvent.InputSaluteSpeechKey -> {
                settingsProvider.saluteSpeechKey = event.key
                updateKeysState(currentState.gigaChatKey, event.key)
            }
            SetupEvent.ChooseVoice -> runCatching { say.chooseVoice() }
                .onFailure { logger.warn("Failed to open voice settings", it) }
            SetupEvent.Proceed -> {
                if (currentState.canProceed) {
                    send(SetupEffect.OpenMain)
                }
            }
        }
    }

    override suspend fun handleSideEffect(effect: SetupEffect) = Unit

    private suspend fun updateKeysState(gigaChatKey: String, saluteSpeechKey: String) {
        val messages = resolveMissingMessages(gigaChatKey, saluteSpeechKey)
        setState {
            copy(
                gigaChatKey = gigaChatKey,
                saluteSpeechKey = saluteSpeechKey,
                missingMessages = messages,
                canProceed = gigaChatKey.isNotBlank() && saluteSpeechKey.isNotBlank()
            )
        }
    }

    private fun resolveMissingMessages(gigaChatKey: String, saluteSpeechKey: String): List<String> {
        val noGigaChatKey = gigaChatKey.isBlank()
        val noGigaVoiceKey = saluteSpeechKey.isBlank()
        val messages = mutableListOf<String>()

        if (noGigaChatKey && noGigaVoiceKey) {
            messages += "Не могу найти ключи для GigaChat и Salute Speech"
        } else if (noGigaChatKey) {
            messages += "Не могу найти ключи для GigaChat"
        } else if (noGigaVoiceKey) {
            messages += "Не могу найти ключи для Salute Speech"
        }
        return messages
    }
}
