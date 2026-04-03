package ru.souz.ui.setup

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.souz.service.audio.Say
import ru.souz.db.SettingsProvider
import ru.souz.db.SettingsProviderImpl.Companion.REGION_EN
import ru.souz.db.SettingsProviderImpl.Companion.REGION_RU
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmBuildProfile
import ru.souz.llms.LlmProvider
import ru.souz.ui.BaseViewModel
import ru.souz.ui.common.openProviderLink
import ru.souz.ui.common.usecases.ApiKeyAvailabilityUseCase
import ru.souz.ui.common.usecases.ApiKeyValues

class SetupViewModel(
    override val di: DI,
) : BaseViewModel<SetupState, SetupEvent, SetupEffect>(), DIAware {

    private val l = LoggerFactory.getLogger(SetupViewModel::class.java)
    private val settingsProvider: SettingsProvider by di.instance()
    private val llmBuildProfile: LlmBuildProfile by di.instance()
    private val apiKeyAvailabilityUseCase: ApiKeyAvailabilityUseCase by di.instance()
    private val say: Say by di.instance()
    private val startedWithoutAnyApiKeys: Boolean = !apiKeyAvailabilityUseCase.hasAnyConfiguredKey(
        values = ApiKeyValues(
        gigaChatKey = settingsProvider.gigaChatKey.orEmpty(),
        qwenChatKey = settingsProvider.qwenChatKey.orEmpty(),
        aiTunnelKey = settingsProvider.aiTunnelKey.orEmpty(),
        anthropicKey = settingsProvider.anthropicKey.orEmpty(),
        openaiKey = settingsProvider.openaiKey.orEmpty(),
        saluteSpeechKey = settingsProvider.saluteSpeechKey.orEmpty(),
        ),
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
            val hasAnyConfiguredKey = apiKeyAvailabilityUseCase.hasAnyConfiguredKey(
                values = ApiKeyValues(
                gigaChatKey = gigaChatKey,
                qwenChatKey = qwenChatKey,
                aiTunnelKey = aiTunnelKey,
                anthropicKey = anthropicKey,
                openaiKey = openAiKey,
                saluteSpeechKey = saluteSpeechKey,
                ),
            )
            val supportsLocalInference = apiKeyAvailabilityUseCase.availability().supportsLocalInference
            setState {
                copy(shouldProceed = hasAnyConfiguredKey)
            }
            markOnboardingIfNeeded(hasAnyConfiguredKey || supportsLocalInference)
        }
    }

    override fun initialState(): SetupState = SetupState()

    override suspend fun handleEvent(event: SetupEvent) {
        when (event) {
            is SetupEvent.InputUseEnglishVersion -> {
                val newLanguage = if (event.enabled) REGION_EN else REGION_RU
                if (settingsProvider.regionProfile != newLanguage) {
                    settingsProvider.regionProfile = newLanguage
                    updateKeysState(
                        gigaChatKey = currentState.gigaChatKey,
                        qwenChatKey = currentState.qwenChatKey,
                        aiTunnelKey = currentState.aiTunnelKey,
                        anthropicKey = currentState.anthropicKey,
                        openAiKey = currentState.openaiKey,
                        saluteSpeechKey = currentState.saluteSpeechKey,
                    )
                }
            }

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

            SetupEvent.ChooseVoice -> {
                setState {
                    copy(
                        hasOpenedVoiceSelection = true,
                        showVoiceReminderDialog = false,
                    )
                }
                runCatching { say.chooseVoice() }
                    .onFailure { l.warn("Failed to open voice settings", it) }
            }

            SetupEvent.DismissVoiceReminderDialog -> setState {
                copy(showVoiceReminderDialog = false)
            }

            is SetupEvent.OpenProviderLink -> openProviderLink(url = event.provider.url, logger = l)

            SetupEvent.Proceed -> {
                if (currentState.canProceed) {
                    if (!currentState.hasOpenedVoiceSelection && !currentState.hasShownVoiceReminderOnProceed) {
                        setState {
                            copy(
                                hasShownVoiceReminderOnProceed = true,
                                showVoiceReminderDialog = true,
                            )
                        }
                        return
                    }
                    markOnboardingIfNeeded(canProceed = true)
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
        val availability = apiKeyAvailabilityUseCase.availability()
        val configuredKeysCount = apiKeyAvailabilityUseCase.configuredKeysCount(
            values = ApiKeyValues(
            gigaChatKey = gigaChatKey,
            qwenChatKey = qwenChatKey,
            aiTunnelKey = aiTunnelKey,
            anthropicKey = anthropicKey,
            openaiKey = openAiKey,
            saluteSpeechKey = saluteSpeechKey,
            ),
        )
        setState {
            copy(
                gigaChatKey = gigaChatKey,
                qwenChatKey = qwenChatKey,
                aiTunnelKey = aiTunnelKey,
                anthropicKey = anthropicKey,
                openaiKey = openAiKey,
                saluteSpeechKey = saluteSpeechKey,
                availableApiKeyFields = availability.fields,
                availableApiKeyProviders = availability.providers,
                supportsVoiceRecognitionApiKeys = availability.supportsVoiceRecognitionApiKeys,
                supportsLocalInference = availability.supportsLocalInference,
                useEnglishVersion = settingsProvider.regionProfile == REGION_EN,
                configuredKeysCount = configuredKeysCount,
                canProceed = configuredKeysCount > 0 || availability.supportsLocalInference
            )
        }
    }

    private fun markOnboardingIfNeeded(canProceed: Boolean) {
        if (canProceed && !settingsProvider.onboardingCompleted) {
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
        val preferredProvider = llmBuildProfile.providerPriorities()
            .firstOrNull { provider -> keysByProvider[provider].orEmpty().isNotBlank() }
            ?: return

        settingsProvider.gigaModel = defaultSetupModelForProvider(preferredProvider)
    }

    private fun defaultSetupModelForProvider(provider: LlmProvider): LLMModel =
        llmBuildProfile.defaultModelForProvider(provider)
            ?: error("Default setup model is not configured for provider: $provider")
}
