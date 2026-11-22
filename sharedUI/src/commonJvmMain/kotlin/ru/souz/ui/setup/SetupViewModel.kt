package ru.souz.ui.setup

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.souz.db.SettingsProvider
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
    private val apiKeyAvailabilityUseCase: ApiKeyAvailabilityUseCase by di.instance()
    private val speechPlayer: UiSpeechPlayer by di.instance()
    private val codexOAuthService: CodexOAuthService by di.instance()
    private val externalLinkOpener: ExternalLinkOpener by di.instance()
    private var codexOAuthJob: Job? = null
    init {
        viewModelScope.launch { refreshKeys() }
    }

    override fun initialState(): SetupState = SetupState()

    override suspend fun handleEvent(event: SetupEvent) {
        when (event) {
            is SetupEvent.InputGigaChatKey -> {
                settingsProvider.gigaChatKey = event.key
                refreshKeys()
            }

            is SetupEvent.InputQwenChatKey -> {
                settingsProvider.qwenChatKey = event.key
                refreshKeys()
            }

            is SetupEvent.InputAiTunnelKey -> {
                settingsProvider.aiTunnelKey = event.key
                refreshKeys()
            }

            is SetupEvent.InputAnthropicKey -> {
                settingsProvider.anthropicKey = event.key
                refreshKeys()
            }

            is SetupEvent.InputOpenAiKey -> {
                settingsProvider.openaiKey = event.key
                refreshKeys()
            }

            is SetupEvent.InputPolzaApiKey -> {
                settingsProvider.polzaApiKey = event.key
                refreshKeys()
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
                        if (oauthState is CodexOAuthState.Success) refreshKeys()
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

    private suspend fun refreshKeys() {
        val availability = apiKeyAvailabilityUseCase.availability()
        val keys = ApiKeyValues(
            gigaChatKey = settingsProvider.gigaChatKey.orEmpty(),
            qwenChatKey = settingsProvider.qwenChatKey.orEmpty(),
            aiTunnelKey = settingsProvider.aiTunnelKey.orEmpty(),
            anthropicKey = settingsProvider.anthropicKey.orEmpty(),
            openaiKey = settingsProvider.openaiKey.orEmpty(),
            polzaApiKey = settingsProvider.polzaApiKey.orEmpty(),
            codexConnected = !settingsProvider.codexAccessToken.isNullOrBlank(),
        )
        val configuredKeysCount = apiKeyAvailabilityUseCase.configuredKeysCount(keys, availability.fields)
        setState {
            copy(
                gigaChatKey = keys.gigaChatKey,
                qwenChatKey = keys.qwenChatKey,
                aiTunnelKey = keys.aiTunnelKey,
                anthropicKey = keys.anthropicKey,
                openaiKey = keys.openaiKey,
                polzaApiKey = keys.polzaApiKey,
                codexConnected = keys.codexConnected,
                availableApiKeyFields = availability.fields,
                availableApiKeyProviders = availability.providers,
                supportsVoiceRecognitionApiKeys = availability.supportsVoiceRecognitionApiKeys,
                supportsLocalInference = availability.supportsLocalInference,
                configuredKeysCount = configuredKeysCount,
                canProceed = configuredKeysCount > 0 || availability.supportsLocalInference,
            )
        }
    }

    private fun markOnboardingIfNeeded(canProceed: Boolean) {
        if (canProceed && !settingsProvider.onboardingCompleted) {
            settingsProvider.needsOnboarding = true
        }
    }
}
