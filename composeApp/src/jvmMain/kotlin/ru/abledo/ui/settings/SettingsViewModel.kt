package ru.abledo.ui.settings

import io.ktor.util.logging.debug
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.abledo.db.ConfigStore
import ru.abledo.giga.GigaRestChatAPI
import ru.abledo.ui.BaseViewModel

class SettingsViewModel(
    override val di: DI,
) : BaseViewModel<SettingsState, SettingsEvent, SettingsEffect>(), DIAware {

    private val l = LoggerFactory.getLogger(GigaRestChatAPI::class.java)
    private val configStore: ConfigStore by di.instance()

    override fun initialState(): SettingsState = SettingsState(
        gigaChatKey = configStore.get(GIGA_CHAT_KEY) ?: "",
        saluteSpeechKey = configStore.get(SALUTE_SPEECH_KEY) ?: ""
    )

    override suspend fun handleEvent(event: SettingsEvent) {
        l.debug { "handleEvent: $event" }
        when(event) {
            is SettingsEvent.InputGigaChatKey -> {
                configStore.put(GIGA_CHAT_KEY, event.key)
                setState { copy(gigaChatKey = event.key) }
            }
            is SettingsEvent.InputSaluteSpeechKey -> {
                configStore.put(SALUTE_SPEECH_KEY, event.key)
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

    companion object {
        const val GIGA_CHAT_KEY = "GIGA_CHAT_KEY"
        const val SALUTE_SPEECH_KEY = "SALUTE_SPEECH_KEY"
    }
}