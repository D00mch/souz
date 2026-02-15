package ru.gigadesk.ui.setup

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.gigadesk.audio.Say
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.ui.BaseViewModel
import ru.gigadesk.ui.common.configuredApiKeysCount
import ru.gigadesk.ui.common.hasAnyConfiguredApiKey
import java.awt.Desktop
import java.net.URI

class SetupViewModel(
    override val di: DI,
) : BaseViewModel<SetupState, SetupEvent, SetupEffect>(), DIAware {

    private val l = LoggerFactory.getLogger(SetupViewModel::class.java)
    private val settingsProvider: SettingsProvider by di.instance()
    private val say: Say by di.instance()

    init {
        viewModelScope.launch {
            val gigaChatKey = settingsProvider.gigaChatKey.orEmpty()
            val qwenChatKey = settingsProvider.qwenChatKey.orEmpty()
            val aiTunnelKey = settingsProvider.aiTunnelKey.orEmpty()
            val saluteSpeechKey = settingsProvider.saluteSpeechKey.orEmpty()
            updateKeysState(
                gigaChatKey = gigaChatKey,
                qwenChatKey = qwenChatKey,
                aiTunnelKey = aiTunnelKey,
                saluteSpeechKey = saluteSpeechKey,
            )
            val hasAnyConfiguredKey = hasAnyConfiguredApiKey(
                gigaChatKey = gigaChatKey,
                qwenChatKey = qwenChatKey,
                aiTunnelKey = aiTunnelKey,
                saluteSpeechKey = saluteSpeechKey,
            )
            setState {
                copy(shouldProceed = hasAnyConfiguredKey)
            }
            markOnboardingIfNeeded(hasAnyConfiguredKey)
        }
    }

    override fun initialState(): SetupState = SetupState()

    override suspend fun handleEvent(event: SetupEvent) {
        when (event) {
            is SetupEvent.InputGigaChatKey -> {
                settingsProvider.gigaChatKey = event.key
                updateKeysState(
                    gigaChatKey = event.key,
                    qwenChatKey = currentState.qwenChatKey,
                    aiTunnelKey = currentState.aiTunnelKey,
                    saluteSpeechKey = currentState.saluteSpeechKey,
                )
            }

            is SetupEvent.InputQwenChatKey -> {
                settingsProvider.qwenChatKey = event.key
                updateKeysState(
                    gigaChatKey = currentState.gigaChatKey,
                    qwenChatKey = event.key,
                    aiTunnelKey = currentState.aiTunnelKey,
                    saluteSpeechKey = currentState.saluteSpeechKey,
                )
            }

            is SetupEvent.InputAiTunnelKey -> {
                settingsProvider.aiTunnelKey = event.key
                updateKeysState(
                    gigaChatKey = currentState.gigaChatKey,
                    qwenChatKey = currentState.qwenChatKey,
                    aiTunnelKey = event.key,
                    saluteSpeechKey = currentState.saluteSpeechKey,
                )
            }

            is SetupEvent.InputSaluteSpeechKey -> {
                settingsProvider.saluteSpeechKey = event.key
                updateKeysState(
                    gigaChatKey = currentState.gigaChatKey,
                    qwenChatKey = currentState.qwenChatKey,
                    aiTunnelKey = currentState.aiTunnelKey,
                    saluteSpeechKey = event.key,
                )
            }

            SetupEvent.ChooseVoice -> runCatching { say.chooseVoice() }
                .onFailure { l.warn("Failed to open voice settings", it) }

            is SetupEvent.OpenProviderLink -> openProviderLink(event.provider.url)

            SetupEvent.Proceed -> {
                if (currentState.canProceed) {
                    markOnboardingIfNeeded(hasAnyConfiguredKey = true)
                    send(SetupEffect.OpenMain)
                }
            }
        }
    }

    override suspend fun handleSideEffect(effect: SetupEffect) = Unit

    private suspend fun updateKeysState(
        gigaChatKey: String,
        qwenChatKey: String,
        aiTunnelKey: String,
        saluteSpeechKey: String,
    ) {
        val configuredKeysCount = configuredApiKeysCount(
            gigaChatKey = gigaChatKey,
            qwenChatKey = qwenChatKey,
            aiTunnelKey = aiTunnelKey,
            saluteSpeechKey = saluteSpeechKey,
        )
        setState {
            copy(
                gigaChatKey = gigaChatKey,
                qwenChatKey = qwenChatKey,
                aiTunnelKey = aiTunnelKey,
                saluteSpeechKey = saluteSpeechKey,
                configuredKeysCount = configuredKeysCount,
                canProceed = configuredKeysCount > 0
            )
        }
    }

    private fun markOnboardingIfNeeded(hasAnyConfiguredKey: Boolean) {
        if (hasAnyConfiguredKey && !settingsProvider.onboardingCompleted) {
            settingsProvider.needsOnboarding = true
        }
    }

    // TODO: rm duplicate
    private fun openProviderLink(url: String) {
        runCatching {
            if (!Desktop.isDesktopSupported()) error("Desktop browsing is not supported")
            val desktop = Desktop.getDesktop()
            if (!desktop.isSupported(Desktop.Action.BROWSE)) error("Desktop browsing action is not supported")
            desktop.browse(URI(url))
        }.onFailure { l.warn("Failed to open provider link: $url", it) }
    }
}
