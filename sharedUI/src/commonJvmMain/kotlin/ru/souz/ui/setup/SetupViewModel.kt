package ru.souz.ui.setup

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.souz.db.DesktopModelCatalog
import ru.souz.db.SettingsProvider
import ru.souz.llms.LlmProvider
import ru.souz.llms.codex.CodexOAuthService
import ru.souz.llms.codex.CodexOAuthState
import ru.souz.ui.BaseViewModel
import ru.souz.ui.common.usecases.ApiKeyAvailabilityUseCase
import ru.souz.ui.common.usecases.ApiKeyValues
import ru.souz.ui.host.ExternalLinkOpener
import ru.souz.ui.host.UiSpeechPlayer
import ru.souz.ui.settings.CodexOAuthUiState

class SetupViewModel(
    override val di: DI,
) : BaseViewModel<SetupState, SetupEvent, SetupEffect>(), DIAware {

    private val l = LoggerFactory.getLogger(SetupViewModel::class.java)
    private val settingsProvider: SettingsProvider by di.instance()
    private val modelCatalog: DesktopModelCatalog by di.instance()
    private val apiKeyAvailabilityUseCase: ApiKeyAvailabilityUseCase by di.instance()
    private val speechPlayer: UiSpeechPlayer by di.instance()
    private val codexOAuthService: CodexOAuthService by di.instance()
    private val externalLinkOpener: ExternalLinkOpener by di.instance()
    private var codexOAuthJob: Job? = null
    private val startedWithoutAnyApiKeys: Boolean = !apiKeyAvailabilityUseCase.hasAnyConfiguredKey(
        values = ApiKeyValues(
            gigaChatKey = settingsProvider.gigaChatKey.orEmpty(),
            qwenChatKey = settingsProvider.qwenChatKey.orEmpty(),
            aiTunnelKey = settingsProvider.aiTunnelKey.orEmpty(),
            anthropicKey = settingsProvider.anthropicKey.orEmpty(),
            openaiKey = settingsProvider.openaiKey.orEmpty(),
            polzaApiKey = settingsProvider.polzaApiKey.orEmpty(),
            codexConnected = !settingsProvider.codexAccessToken.isNullOrBlank(),
        ),
    )

    init {
        viewModelScope.launch {
            val gigaChatKey = settingsProvider.gigaChatKey.orEmpty()
            val qwenChatKey = settingsProvider.qwenChatKey.orEmpty()
            val aiTunnelKey = settingsProvider.aiTunnelKey.orEmpty()
            val anthropicKey = settingsProvider.anthropicKey.orEmpty()
            val openAiKey = settingsProvider.openaiKey.orEmpty()
            val polzaApiKey = settingsProvider.polzaApiKey.orEmpty()
            updateKeysState(
                gigaChatKey = gigaChatKey,
                qwenChatKey = qwenChatKey,
                aiTunnelKey = aiTunnelKey,
                anthropicKey = anthropicKey,
                openAiKey = openAiKey,
                polzaApiKey = polzaApiKey,
            )
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
                val polzaApiKey = currentState.polzaApiKey
                updateKeysState(
                    gigaChatKey = event.key,
                    qwenChatKey = qwenChatKey,
                    aiTunnelKey = aiTunnelKey,
                    anthropicKey = anthropicKey,
                    openAiKey = openAiKey,
                    polzaApiKey = polzaApiKey,
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
                val polzaApiKey = currentState.polzaApiKey
                updateKeysState(
                    gigaChatKey = gigaChatKey,
                    qwenChatKey = event.key,
                    aiTunnelKey = aiTunnelKey,
                    anthropicKey = anthropicKey,
                    openAiKey = openAiKey,
                    polzaApiKey = polzaApiKey,
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
                val polzaApiKey = currentState.polzaApiKey
                updateKeysState(
                    gigaChatKey = gigaChatKey,
                    qwenChatKey = qwenChatKey,
                    aiTunnelKey = event.key,
                    anthropicKey = anthropicKey,
                    openAiKey = openAiKey,
                    polzaApiKey = polzaApiKey,
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
                val polzaApiKey = currentState.polzaApiKey
                updateKeysState(
                    gigaChatKey = gigaChatKey,
                    qwenChatKey = qwenChatKey,
                    aiTunnelKey = aiTunnelKey,
                    anthropicKey = event.key,
                    openAiKey = openAiKey,
                    polzaApiKey = polzaApiKey,
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
                val polzaApiKey = currentState.polzaApiKey
                updateKeysState(
                    gigaChatKey = gigaChatKey,
                    qwenChatKey = qwenChatKey,
                    aiTunnelKey = aiTunnelKey,
                    anthropicKey = anthropicKey,
                    openAiKey = event.key,
                    polzaApiKey = polzaApiKey,
                )
                tryToChooseDefaultMode(
                    gigaChatKey = gigaChatKey,
                    qwenChatKey = qwenChatKey,
                    aiTunnelKey = aiTunnelKey,
                    anthropicKey = anthropicKey,
                    openAiKey = event.key,
                )
            }

            is SetupEvent.InputPolzaApiKey -> {
                settingsProvider.polzaApiKey = event.key
                updateKeysState(
                    gigaChatKey = currentState.gigaChatKey,
                    qwenChatKey = currentState.qwenChatKey,
                    aiTunnelKey = currentState.aiTunnelKey,
                    anthropicKey = currentState.anthropicKey,
                    openAiKey = currentState.openaiKey,
                    polzaApiKey = event.key,
                )
            }

            SetupEvent.StartCodexOAuth -> {
                codexOAuthJob?.cancel()
                codexOAuthJob = viewModelScope.launch {
                    codexOAuthService.oauthState.collect { oauthState ->
                        val uiState: CodexOAuthUiState = when (oauthState) {
                            is CodexOAuthState.Idle -> CodexOAuthUiState.Idle
                            is CodexOAuthState.AwaitingUserCode -> CodexOAuthUiState.AwaitingUserCode(oauthState.userCode)
                            is CodexOAuthState.Polling -> CodexOAuthUiState.Polling
                            is CodexOAuthState.Success -> CodexOAuthUiState.Done
                            is CodexOAuthState.Error -> CodexOAuthUiState.Error(oauthState.message)
                        }
                        setState { copy(codexOAuthState = uiState) }
                        if (oauthState is CodexOAuthState.Success) {
                            updateKeysState(
                                gigaChatKey = currentState.gigaChatKey,
                                qwenChatKey = currentState.qwenChatKey,
                                aiTunnelKey = currentState.aiTunnelKey,
                                anthropicKey = currentState.anthropicKey,
                                openAiKey = currentState.openaiKey,
                                polzaApiKey = currentState.polzaApiKey,
                            )
                            tryToChooseDefaultMode(
                                gigaChatKey = currentState.gigaChatKey,
                                qwenChatKey = currentState.qwenChatKey,
                                aiTunnelKey = currentState.aiTunnelKey,
                                anthropicKey = currentState.anthropicKey,
                                openAiKey = currentState.openaiKey,
                                codexConnected = true,
                            )
                        }
                    }
                }
                codexOAuthService.launchFlow(viewModelScope)
            }

            SetupEvent.CancelCodexOAuth -> {
                codexOAuthService.cancelFlow()
                codexOAuthJob?.cancel()
                codexOAuthJob = null
                setState { copy(codexOAuthState = CodexOAuthUiState.Idle) }
            }

            SetupEvent.ChooseVoice -> {
                setState {
                    copy(
                        hasOpenedVoiceSelection = true,
                        showVoiceReminderDialog = false,
                    )
                }
                runCatching { speechPlayer.chooseVoice() }
                    .onFailure { l.warn("Failed to open voice settings", it) }
            }

            SetupEvent.DismissVoiceReminderDialog -> setState {
                copy(showVoiceReminderDialog = false)
            }

            is SetupEvent.OpenProviderLink -> externalLinkOpener
                .open(event.provider.url)
                .onFailure { l.warn("Failed to open provider link: ${event.provider.url}", it) }

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
        polzaApiKey: String,
    ) {
        val codexConnected = !settingsProvider.codexAccessToken.isNullOrBlank()
        val availability = apiKeyAvailabilityUseCase.availability()
        val configuredKeysCount = apiKeyAvailabilityUseCase.configuredKeysCount(
            values = ApiKeyValues(
                gigaChatKey = gigaChatKey,
                qwenChatKey = qwenChatKey,
                aiTunnelKey = aiTunnelKey,
                anthropicKey = anthropicKey,
                openaiKey = openAiKey,
                polzaApiKey = polzaApiKey,
                codexConnected = codexConnected,
            ),
        )
        setState {
            copy(
                gigaChatKey = gigaChatKey,
                qwenChatKey = qwenChatKey,
                aiTunnelKey = aiTunnelKey,
                anthropicKey = anthropicKey,
                openaiKey = openAiKey,
                polzaApiKey = polzaApiKey,
                codexConnected = codexConnected,
                availableApiKeyFields = availability.fields,
                availableApiKeyProviders = availability.providers,
                supportsVoiceRecognitionApiKeys = availability.supportsVoiceRecognitionApiKeys,
                supportsLocalInference = availability.supportsLocalInference,
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
        codexConnected: Boolean = !settingsProvider.codexAccessToken.isNullOrBlank(),
    ) {
        if (!startedWithoutAnyApiKeys) return

        val keysByProvider = mapOf(
            LlmProvider.CODEX to if (codexConnected) "connected" else "",
            LlmProvider.GIGA to gigaChatKey,
            LlmProvider.QWEN to qwenChatKey,
            LlmProvider.AI_TUNNEL to aiTunnelKey,
            LlmProvider.ANTHROPIC to anthropicKey,
            LlmProvider.OPENAI to openAiKey,
        )
        modelCatalog.defaultChatModel(settingsProvider.openaiModel) { !keysByProvider[it].isNullOrBlank() }
            ?.let { settingsProvider.gigaModel = it }
    }
}
