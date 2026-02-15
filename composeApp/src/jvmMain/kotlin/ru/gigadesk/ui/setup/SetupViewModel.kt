package ru.gigadesk.ui.setup

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.gigadesk.audio.Say
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.giga.GigaModel
import ru.gigadesk.giga.LlmProvider
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
    private val startedWithoutAnyApiKeys: Boolean = !hasAnyConfiguredApiKey(
        gigaChatKey = settingsProvider.gigaChatKey.orEmpty(),
        qwenChatKey = settingsProvider.qwenChatKey.orEmpty(),
        aiTunnelKey = settingsProvider.aiTunnelKey.orEmpty(),
        saluteSpeechKey = settingsProvider.saluteSpeechKey.orEmpty(),
    )

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
                val qwenChatKey = currentState.qwenChatKey
                val aiTunnelKey = currentState.aiTunnelKey
                val saluteSpeechKey = currentState.saluteSpeechKey
                updateKeysState(
                    gigaChatKey = event.key,
                    qwenChatKey = qwenChatKey,
                    aiTunnelKey = aiTunnelKey,
                    saluteSpeechKey = saluteSpeechKey,
                )
                tryToChooseDefaultMode(
                    gigaChatKey = event.key,
                    qwenChatKey = qwenChatKey,
                    aiTunnelKey = aiTunnelKey,
                )
            }

            is SetupEvent.InputQwenChatKey -> {
                settingsProvider.qwenChatKey = event.key
                val gigaChatKey = currentState.gigaChatKey
                val aiTunnelKey = currentState.aiTunnelKey
                val saluteSpeechKey = currentState.saluteSpeechKey
                updateKeysState(
                    gigaChatKey = gigaChatKey,
                    qwenChatKey = event.key,
                    aiTunnelKey = aiTunnelKey,
                    saluteSpeechKey = saluteSpeechKey,
                )
                tryToChooseDefaultMode(
                    gigaChatKey = gigaChatKey,
                    qwenChatKey = event.key,
                    aiTunnelKey = aiTunnelKey,
                )
            }

            is SetupEvent.InputAiTunnelKey -> {
                settingsProvider.aiTunnelKey = event.key
                val gigaChatKey = currentState.gigaChatKey
                val qwenChatKey = currentState.qwenChatKey
                val saluteSpeechKey = currentState.saluteSpeechKey
                updateKeysState(
                    gigaChatKey = gigaChatKey,
                    qwenChatKey = qwenChatKey,
                    aiTunnelKey = event.key,
                    saluteSpeechKey = saluteSpeechKey,
                )
                tryToChooseDefaultMode(
                    gigaChatKey = gigaChatKey,
                    qwenChatKey = qwenChatKey,
                    aiTunnelKey = event.key,
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

    /** Choose default LLM base on the keys provided */
    private fun tryToChooseDefaultMode(
        gigaChatKey: String,
        qwenChatKey: String,
        aiTunnelKey: String,
    ) {
        if (!startedWithoutAnyApiKeys) return

        val preferredProvider = when {
            gigaChatKey.isNotBlank() -> LlmProvider.GIGA
            qwenChatKey.isNotBlank() -> LlmProvider.QWEN
            aiTunnelKey.isNotBlank() -> LlmProvider.AI_TUNNEL
            else -> return
        }
        settingsProvider.gigaModel = defaultSetupModelForProvider(preferredProvider)
    }

    private fun defaultSetupModelForProvider(provider: LlmProvider): GigaModel =
        GigaModel.entries.singleOrNull { model ->
            model.provider == provider && model.isDefaultSetupModel()
        } ?: error("Default setup model is not configured for provider: $provider")

    private fun GigaModel.isDefaultSetupModel(): Boolean = when (this) {
        GigaModel.Max -> true
        GigaModel.QwenMax -> true
        GigaModel.AiTunnelClaudeHaiku -> true
        GigaModel.Lite, GigaModel.Pro, GigaModel.QwenFlash, GigaModel.QwenPlus, GigaModel.Qwen3OpenSource,
        GigaModel.AiTunnelGpt52Codex, GigaModel.AiTunnelGpt5Nano, GigaModel.AiTunnelGemini3Flash,
        GigaModel.AiTunnelClaudeOpus, GigaModel.AiTunnelGpt4oMini, GigaModel.AiTunnelGrok -> false
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
