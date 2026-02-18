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
import ru.gigadesk.giga.LlmBuildProfile
import ru.gigadesk.giga.LlmProvider
import ru.gigadesk.ui.BaseViewModel
import ru.gigadesk.ui.common.configuredApiKeysCount
import ru.gigadesk.ui.common.hasAnyConfiguredApiKey
import ru.gigadesk.ui.common.openProviderLink

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
        anthropicKey = settingsProvider.anthropicKey.orEmpty(),
        openaiKey = settingsProvider.openaiKey.orEmpty(),
        saluteSpeechKey = settingsProvider.saluteSpeechKey.orEmpty(),
    )

    init {
        viewModelScope.launch {
            val gigaChatKey = settingsProvider.gigaChatKey.orEmpty()
            val qwenChatKey = settingsProvider.qwenChatKey.orEmpty()
            val aiTunnelKey = settingsProvider.aiTunnelKey.orEmpty()
            val anthropicKey = settingsProvider.anthropicKey.orEmpty()
            val openAiKey = settingsProvider.openaiKey.orEmpty()
            val saluteSpeechKey = settingsProvider.saluteSpeechKey.orEmpty()
            updateKeysState(
                gigaChatKey = gigaChatKey,
                qwenChatKey = qwenChatKey,
                aiTunnelKey = aiTunnelKey,
                anthropicKey = anthropicKey,
                openAiKey = openAiKey,
                saluteSpeechKey = saluteSpeechKey,
            )
            val hasAnyConfiguredKey = hasAnyConfiguredApiKey(
                gigaChatKey = gigaChatKey,
                qwenChatKey = qwenChatKey,
                aiTunnelKey = aiTunnelKey,
                anthropicKey = anthropicKey,
                openaiKey = openAiKey,
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
                val anthropicKey = currentState.anthropicKey
                val openAiKey = currentState.openaiKey
                val saluteSpeechKey = currentState.saluteSpeechKey
                updateKeysState(
                    gigaChatKey = event.key,
                    qwenChatKey = qwenChatKey,
                    aiTunnelKey = aiTunnelKey,
                    anthropicKey = anthropicKey,
                    openAiKey = openAiKey,
                    saluteSpeechKey = saluteSpeechKey,
                )
                tryToChooseDefaultMode(
                    gigaChatKey = event.key,
                    qwenChatKey = qwenChatKey,
                    aiTunnelKey = aiTunnelKey,
                    anthropicKey = anthropicKey,
                    openAiKey = openAiKey,
                )
            }

            is SetupEvent.InputQwenChatKey -> {
                settingsProvider.qwenChatKey = event.key
                val gigaChatKey = currentState.gigaChatKey
                val aiTunnelKey = currentState.aiTunnelKey
                val anthropicKey = currentState.anthropicKey
                val openAiKey = currentState.openaiKey
                val saluteSpeechKey = currentState.saluteSpeechKey
                updateKeysState(
                    gigaChatKey = gigaChatKey,
                    qwenChatKey = event.key,
                    aiTunnelKey = aiTunnelKey,
                    anthropicKey = anthropicKey,
                    openAiKey = openAiKey,
                    saluteSpeechKey = saluteSpeechKey,
                )
                tryToChooseDefaultMode(
                    gigaChatKey = gigaChatKey,
                    qwenChatKey = event.key,
                    aiTunnelKey = aiTunnelKey,
                    anthropicKey = anthropicKey,
                    openAiKey = openAiKey,
                )
            }

            is SetupEvent.InputAiTunnelKey -> {
                settingsProvider.aiTunnelKey = event.key
                val gigaChatKey = currentState.gigaChatKey
                val qwenChatKey = currentState.qwenChatKey
                val anthropicKey = currentState.anthropicKey
                val openAiKey = currentState.openaiKey
                val saluteSpeechKey = currentState.saluteSpeechKey
                updateKeysState(
                    gigaChatKey = gigaChatKey,
                    qwenChatKey = qwenChatKey,
                    aiTunnelKey = event.key,
                    anthropicKey = anthropicKey,
                    openAiKey = openAiKey,
                    saluteSpeechKey = saluteSpeechKey,
                )
                tryToChooseDefaultMode(
                    gigaChatKey = gigaChatKey,
                    qwenChatKey = qwenChatKey,
                    aiTunnelKey = event.key,
                    anthropicKey = anthropicKey,
                    openAiKey = openAiKey,
                )
            }

            is SetupEvent.InputAnthropicKey -> {
                settingsProvider.anthropicKey = event.key
                val gigaChatKey = currentState.gigaChatKey
                val qwenChatKey = currentState.qwenChatKey
                val aiTunnelKey = currentState.aiTunnelKey
                val openAiKey = currentState.openaiKey
                val saluteSpeechKey = currentState.saluteSpeechKey
                updateKeysState(
                    gigaChatKey = gigaChatKey,
                    qwenChatKey = qwenChatKey,
                    aiTunnelKey = aiTunnelKey,
                    anthropicKey = event.key,
                    openAiKey = openAiKey,
                    saluteSpeechKey = saluteSpeechKey,
                )
                tryToChooseDefaultMode(
                    gigaChatKey = gigaChatKey,
                    qwenChatKey = qwenChatKey,
                    aiTunnelKey = aiTunnelKey,
                    anthropicKey = event.key,
                    openAiKey = openAiKey,
                )
            }

            is SetupEvent.InputOpenAiKey -> {
                settingsProvider.openaiKey = event.key
                val gigaChatKey = currentState.gigaChatKey
                val qwenChatKey = currentState.qwenChatKey
                val aiTunnelKey = currentState.aiTunnelKey
                val anthropicKey = currentState.anthropicKey
                val saluteSpeechKey = currentState.saluteSpeechKey
                updateKeysState(
                    gigaChatKey = gigaChatKey,
                    qwenChatKey = qwenChatKey,
                    aiTunnelKey = aiTunnelKey,
                    anthropicKey = anthropicKey,
                    openAiKey = event.key,
                    saluteSpeechKey = saluteSpeechKey,
                )
                tryToChooseDefaultMode(
                    gigaChatKey = gigaChatKey,
                    qwenChatKey = qwenChatKey,
                    aiTunnelKey = aiTunnelKey,
                    anthropicKey = anthropicKey,
                    openAiKey = event.key,
                )
            }

            is SetupEvent.InputSaluteSpeechKey -> {
                settingsProvider.saluteSpeechKey = event.key
                updateKeysState(
                    gigaChatKey = currentState.gigaChatKey,
                    qwenChatKey = currentState.qwenChatKey,
                    aiTunnelKey = currentState.aiTunnelKey,
                    anthropicKey = currentState.anthropicKey,
                    openAiKey = currentState.openaiKey,
                    saluteSpeechKey = event.key,
                )
            }

            SetupEvent.ChooseVoice -> runCatching { say.chooseVoice() }
                .onFailure { l.warn("Failed to open voice settings", it) }

            is SetupEvent.OpenProviderLink -> openProviderLink(url = event.provider.url, logger = l)

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
        anthropicKey: String,
        openAiKey: String,
        saluteSpeechKey: String,
    ) {
        val configuredKeysCount = configuredApiKeysCount(
            gigaChatKey = gigaChatKey,
            qwenChatKey = qwenChatKey,
            aiTunnelKey = aiTunnelKey,
            anthropicKey = anthropicKey,
            openaiKey = openAiKey,
            saluteSpeechKey = saluteSpeechKey,
        )
        setState {
            copy(
                gigaChatKey = gigaChatKey,
                qwenChatKey = qwenChatKey,
                aiTunnelKey = aiTunnelKey,
                anthropicKey = anthropicKey,
                openaiKey = openAiKey,
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
        anthropicKey: String,
        openAiKey: String,
    ) {
        if (!startedWithoutAnyApiKeys) return

        val keysByProvider = mapOf(
            LlmProvider.GIGA to gigaChatKey,
            LlmProvider.QWEN to qwenChatKey,
            LlmProvider.AI_TUNNEL to aiTunnelKey,
            LlmProvider.ANTHROPIC to anthropicKey,
            LlmProvider.OPENAI to openAiKey,
        )
        val preferredProvider = LlmBuildProfile.providerPriorities()
            .firstOrNull { provider -> keysByProvider[provider].orEmpty().isNotBlank() }
            ?: return

        settingsProvider.gigaModel = defaultSetupModelForProvider(preferredProvider)
    }

    private fun defaultSetupModelForProvider(provider: LlmProvider): GigaModel =
        LlmBuildProfile.defaultModelForProvider(provider)
            ?: error("Default setup model is not configured for provider: $provider")
}
