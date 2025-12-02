package ru.abledo.ui.settings

import androidx.lifecycle.viewModelScope
import io.ktor.util.logging.debug
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.abledo.db.SettingsProvider
import ru.abledo.giga.GigaRestChatAPI
import ru.abledo.ui.BaseViewModel

class SettingsViewModel(
    override val di: DI,
) : BaseViewModel<SettingsState, SettingsEvent, SettingsEffect>(), DIAware {

    private val l = LoggerFactory.getLogger(GigaRestChatAPI::class.java)
    private val keysProvider: SettingsProvider by di.instance()

    init {
        viewModelScope.launch {
            setState {
                copy(
                    gigaChatKey = keysProvider.gigaChatKey ?: "",
                    saluteSpeechKey = keysProvider.saluteSpeechKey ?: ""
                )
            }
        }
    }

    override fun initialState(): SettingsState = SettingsState()

    override suspend fun handleEvent(event: SettingsEvent) {
        l.debug { "handleEvent: $event" }
        when(event) {
            is SettingsEvent.InputGigaChatKey -> {
                keysProvider.gigaChatKey = event.key
                setState { copy(gigaChatKey = event.key) }
            }
            is SettingsEvent.InputSaluteSpeechKey -> {
                keysProvider.saluteSpeechKey = event.key
                setState { copy(saluteSpeechKey = event.key) }
            }
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
}