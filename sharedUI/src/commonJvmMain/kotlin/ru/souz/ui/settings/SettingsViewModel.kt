package ru.souz.ui.settings

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.souz.agent.AgentFacade
import ru.souz.db.REGION_EN
import ru.souz.db.REGION_RU
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmBuildProfile
import ru.souz.llms.LlmProvider
import ru.souz.llms.VoiceRecognitionProvider
import ru.souz.llms.codex.CodexOAuthService
import ru.souz.llms.codex.CodexOAuthState
import ru.souz.ui.BaseViewModel
import ru.souz.ui.common.ApiKeyField
import ru.souz.ui.common.usecases.ApiKeyAvailabilityUseCase
import ru.souz.ui.host.CalendarListProvider
import ru.souz.ui.host.ExternalLinkOpener
import ru.souz.ui.host.LocalModelUiHost
import ru.souz.ui.host.PrivacyPolicyOpener
import ru.souz.ui.host.SettingsHostPreferences
import ru.souz.ui.host.SupportLogService
import ru.souz.ui.host.TelegramHostAuthStep
import ru.souz.ui.host.TelegramSettingsHost
import ru.souz.ui.host.UiSpeechPlayer
import ru.souz.ui.settings.SettingsEvent.*
import souz.sharedui.generated.resources.Res
import souz.sharedui.generated.resources.*
import org.jetbrains.compose.resources.getString

class SettingsViewModel(
    override val di: DI,
    private val settingsDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) : BaseViewModel<SettingsState, SettingsEvent, SettingsEffect>(), DIAware {

    private val l = LoggerFactory.getLogger(SettingsViewModel::class.java)
    private val keysProvider: SettingsProvider by di.instance()
    private val llmBuildProfile: LlmBuildProfile by di.instance()
    private val localModelUiHost: LocalModelUiHost by di.instance()
    private val apiKeyAvailabilityUseCase: ApiKeyAvailabilityUseCase by di.instance()
    private val chatApi: LLMChatAPI by di.instance()
    private val agentFacade: AgentFacade by di.instance()
    private val telegramSettingsHost: TelegramSettingsHost by di.instance()
    private val calendarListProvider: CalendarListProvider by di.instance()
    private val supportLogService: SupportLogService by di.instance()
    private val privacyPolicyOpener: PrivacyPolicyOpener by di.instance()
    private val settingsHostPreferences: SettingsHostPreferences by di.instance()
    private val externalLinkOpener: ExternalLinkOpener by di.instance()
    private val speechPlayer: UiSpeechPlayer by di.instance()
    private val pendingKeyDrafts = mutableMapOf<ApiKeyField, String>()
    private val pendingKeySaveJobs = mutableMapOf<ApiKeyField, Job>()
    private val pendingTextSettingDrafts = mutableMapOf<DeferredTextSetting, String>()
    private val pendingTextSettingSaveJobs = mutableMapOf<DeferredTextSetting, Job>()
    private val codexOAuthService: CodexOAuthService by di.instance()
    private var codexOAuthJob: Job? = null
    private var localModelDownloadJob: Job? = null
    private var localModelPreloadJob: Job? = null
    private var pendingLocalModelSelectionTarget = LocalModelSelectionTarget.AGENT

    init {
        viewModelScope.launch(settingsDispatcher) {
            val isSupported = telegramSettingsHost.isSupported()
            val isBotActive = telegramSettingsHost.isControlBotActive()
            withContext(Dispatchers.Main) {
                setState {
                    copy(
                        isTelegramSupported = isSupported,
                        isTelegramBotActive = isBotActive,
                    )
                }
            }
        }

        viewModelScope.launch {
            telegramSettingsHost.authState.collectLatest { auth ->
                setState {
                    copy(
                        telegramAuthStep = when (auth.step) {
                            TelegramHostAuthStep.INITIALIZING -> TelegramAuthStepUi.INITIALIZING
                            TelegramHostAuthStep.PHONE -> TelegramAuthStepUi.PHONE
                            TelegramHostAuthStep.CODE -> TelegramAuthStepUi.CODE
                            TelegramHostAuthStep.PASSWORD -> TelegramAuthStepUi.PASSWORD
                            TelegramHostAuthStep.CONNECTED -> TelegramAuthStepUi.CONNECTED
                            TelegramHostAuthStep.LOGGING_OUT -> TelegramAuthStepUi.LOGGING_OUT
                            TelegramHostAuthStep.ERROR -> TelegramAuthStepUi.ERROR
                        },
                        telegramActiveSessionPhone = auth.activePhoneMasked,
                        telegramCodeHint = auth.codeHint,
                        telegramPasswordHint = auth.passwordHint,
                        telegramAuthBusy = auth.isBusy,
                        telegramAuthError = auth.errorMessage,
                        telegramCodeInput = if (auth.step == TelegramHostAuthStep.CONNECTED) "" else telegramCodeInput,
                        telegramPasswordInput = if (auth.step == TelegramHostAuthStep.CONNECTED) "" else telegramPasswordInput,
                    )
                }
            }
        }
    }

    override fun initialState(): SettingsState = SettingsState()

    override suspend fun handleEvent(event: SettingsEvent) {
        l.debug("handleEvent: {}", event)
        when(event) {
            is InputApiKey -> handleDeferredKeyInput(event.field, event.value)
            is OpenProviderLink -> {
                externalLinkOpener.open(event.provider.url)
                    .onFailure { error ->
                        viewModelScope.launch {
                            send(SettingsEffect.ShowSnackbar(error.message ?: event.provider.url))
                        }
                    }
            }
            is InputMcpServersJson -> {
                handleDeferredTextSettingInput(DeferredTextSetting.MCP_SERVERS_JSON, event.json)
            }
            is InputUseFewShotExamples -> {
                keysProvider.useFewShotExamples = event.enabled
                setState { copy(useFewShotExamples = event.enabled) }
            }
            is InputUseStreaming -> {
                keysProvider.useStreaming = event.enabled
                setState { copy(useStreaming = event.enabled) }
                fetchBalance()
            }
            is InputNotificationSoundEnabled -> {
                keysProvider.notificationSoundEnabled = event.enabled
                setState { copy(notificationSoundEnabled = event.enabled) }
            }
            is InputVoiceInputReviewEnabled -> {
                keysProvider.voiceInputReviewEnabled = event.enabled
                setState { copy(voiceInputReviewEnabled = event.enabled) }
            }
            is InputUseEnglishVersion -> {
                val newLanguage = if (event.enabled) REGION_EN else REGION_RU
                if (keysProvider.regionProfile != newLanguage) {
                    keysProvider.regionProfile = newLanguage
                    flushPendingSystemPromptSave()
                    refreshFromProvider()
                    fetchBalance()
                }
            }
            is ToggleApiKeyVisibility -> toggleApiKeyVisibility(event.field)
            StartCodexOAuth -> {
                codexOAuthJob?.cancel()
                codexOAuthJob = viewModelScope.launch {
                    codexOAuthService.oauthState.collect { oauthState ->
                        val uiState = oauthState.toUiState()
                        setState { copy(codexOAuthState = uiState) }
                        if (oauthState is CodexOAuthState.Success || oauthState is CodexOAuthState.Error) {
                            if (oauthState is CodexOAuthState.Success) refreshFromProvider()
                        }
                    }
                }
                codexOAuthService.launchFlow(viewModelScope)
            }
            CancelCodexOAuth -> {
                codexOAuthService.cancelFlow()
                codexOAuthJob?.cancel()
                codexOAuthJob = null
                setState { copy(codexOAuthState = CodexOAuthUiState.Idle) }
            }
            DisconnectCodex -> {
                keysProvider.codexAccessToken = null
                keysProvider.codexRefreshToken = null
                keysProvider.codexAccountId = null
                keysProvider.codexExpiresAt = null
                viewModelScope.launch { refreshFromProvider() }
                setState { copy(codexConnected = false, codexOAuthState = CodexOAuthUiState.Idle) }
            }
            is InputSafeModeEnabled -> {
                keysProvider.safeModeEnabled = event.enabled
                setState { copy(safeModeEnabled = event.enabled) }
            }
            is SelectAgent -> {
                if (event.agentId == currentState.activeAgentId) return
                setState { copy(showAgentSwitchConfirmation = true, pendingAgentId = event.agentId) }
            }
            ConfirmAgentSwitch -> {
                flushPendingSystemPromptSave()
                val targetAgent = currentState.pendingAgentId ?: return
                agentFacade.setActiveAgent(targetAgent)
                setState {
                    copy(
                        showAgentSwitchConfirmation = false,
                        pendingAgentId = null,
                        activeAgentId = targetAgent,
                        systemPrompt = agentFacade.currentContext.value.systemPrompt,
                    )
                }
            }
            CancelAgentSwitch -> {
                setState { copy(showAgentSwitchConfirmation = false, pendingAgentId = null) }
            }
            is SelectModel -> {
                if (event.model !in currentState.availableLlmModels) return
                val downloadPrompt = localModelUiHost.downloadPromptFor(event.model)
                if (downloadPrompt != null) {
                    pendingLocalModelSelectionTarget = LocalModelSelectionTarget.AGENT
                    setState {
                        copy(
                            localModelDownloadPrompt = downloadPrompt,
                            localModelDownloadState = null,
                        )
                    }
                    return
                }
                applySelectedModel(event.model)
            }
            is SelectAmbientAnalysisModel -> {
                if (event.model !in currentState.availableAmbientAnalysisModels) return
                val downloadPrompt = localModelUiHost.downloadPromptFor(event.model)
                if (downloadPrompt != null) {
                    pendingLocalModelSelectionTarget = LocalModelSelectionTarget.AMBIENT_ANALYSIS
                    setState {
                        copy(
                            localModelDownloadPrompt = downloadPrompt,
                            localModelDownloadState = null,
                        )
                    }
                    return
                }
                applySelectedAmbientAnalysisModel(event.model)
            }
            ConfirmLocalModelDownload -> confirmLocalModelDownload()
            CancelLocalModelDownload -> cancelLocalModelDownload()
            is SelectEmbeddingsModel -> {
                if (event.model !in currentState.availableEmbeddingsModels) return
                val currentModel = keysProvider.embeddingsModel
                keysProvider.embeddingsModel = event.model
                val effectiveModel = keysProvider.embeddingsModel
                if (currentModel != effectiveModel) {
                    localModelUiHost.rebuildIndex(viewModelScope, ioDispatchers)
                }
                setState { copy(embeddingsModel = effectiveModel) }
            }
            is SelectVoiceRecognitionModel -> {
                if (event.model !in currentState.availableVoiceRecognitionModels) return
                keysProvider.voiceRecognitionModel = event.model
                setState { copy(voiceRecognitionModel = event.model) }
            }
            is InputRequestTimeoutMillis -> {
                val normalized = event.millis.filter { it.isDigit() }
                val newTimeout = normalized.toLongOrNull()
                if (newTimeout != null) {
                    keysProvider.requestTimeoutMillis = newTimeout
                }
                setState {
                    copy(
                        requestTimeoutInput = normalized,
                        requestTimeoutMillis = newTimeout ?: requestTimeoutMillis
                    )
                }
            }
            is InputContextSize -> {
                val normalized = event.size.filter { it.isDigit() }
                val newContextSize = normalized.toIntOrNull()?.takeIf { it > 0 }
                if (newContextSize != null) {
                    keysProvider.contextSize = newContextSize
                    agentFacade.setContextSize(newContextSize)
                }
                setState {
                    copy(
                        contextSizeInput = normalized,
                        contextSize = newContextSize ?: contextSize
                    )
                }
            }
            is InputTemperature -> {
                val normalized = event.temperature.replace(',', '.')
                    .filter { it.isDigit() || it == '.' }
                val newTemperature = normalized.toFloatOrNull()
                if (newTemperature != null) {
                    keysProvider.temperature = newTemperature
                    agentFacade.setTemperature(newTemperature)
                }
                setState {
                    copy(
                        temperatureInput = normalized,
                        temperature = newTemperature ?: temperature
                    )
                }
            }
            is InputSupportEmail -> {
                handleDeferredTextSettingInput(DeferredTextSetting.SUPPORT_EMAIL, event.email)
            }
            is InputSystemPrompt -> {
                handleDeferredTextSettingInput(DeferredTextSetting.SYSTEM_PROMPT, event.prompt)
            }
            is InputVoiceSpeed -> {
                val normalized = event.speed.filter { it.isDigit() }
                val newSpeed = normalized.toIntOrNull()
                if (newSpeed != null) {
                    settingsHostPreferences.voiceSpeed = newSpeed
                }
                setState { copy(voiceSpeedInput = normalized, voiceSpeed = newSpeed ?: voiceSpeed) }
            }
            is InputTelegramPhone -> setState { copy(telegramPhoneInput = event.value) }
            is InputTelegramCode -> setState { copy(telegramCodeInput = event.value) }
            is InputTelegramPassword -> setState { copy(telegramPasswordInput = event.value) }
            SubmitTelegramPhone -> submitTelegramPhone()
            SubmitTelegramCode -> submitTelegramCode()
            SubmitTelegramPassword -> submitTelegramPassword()
            TelegramLogout -> telegramLogout()
            RefreshFromProvider -> {
                flushPendingTextSettingSaves()
                flushPendingKeySaves()
                refreshFromProvider()
                fetchBalance()
                fetchCalendars()
            }
            ChooseVoice -> {
                runCatching { speechPlayer.chooseVoice() }
                    .onFailure { l.warn("Failed to open voice settings", it) }
            }
            ResetSystemPrompt -> {
                cancelPendingSystemPromptSave()
                agentFacade.resetSystemPrompt()
                send(SettingsEffect.NotifyOnSystemPrompt)
                setState { copy(systemPrompt = agentFacade.currentContext.value.systemPrompt) }
            }
            is SendLogsToSupport -> sendLogs()
            OpenPrivacyPolicy -> openPrivacyPolicy()
            RefreshBalance -> fetchBalance()
            GoToMain -> {
                leaveSettings(SettingsEffect.CloseScreen)
            }
            OpenTools -> leaveSettings(SettingsEffect.OpenTools)
            is SelectDefaultCalendar -> {
                keysProvider.defaultCalendar = event.name
                setState { copy(defaultCalendar = event.name) }
            }
            FetchCalendars -> fetchCalendars()
            
            // Graph Logs
            OpenGraphSessions -> setState { copy(currentScreen = SettingsSubScreen.SESSIONS) }
            is OpenGraphVisualization -> setState { 
                copy(currentScreen = SettingsSubScreen.VISUALIZATION, selectedSessionId = event.sessionId) 
            }
            BackToSettings -> setState { copy(currentScreen = SettingsSubScreen.MAIN) }
            BackToSessions -> setState { copy(currentScreen = SettingsSubScreen.SESSIONS, selectedSessionId = null) }
            OpenFoldersManagement -> setState { copy(currentScreen = SettingsSubScreen.FOLDERS) }
            OpenTelegramSettings -> setState { copy(currentScreen = SettingsSubScreen.TELEGRAM) }
            CreateControlBot -> createTelegramBot()
            DisconnectTelegramBot -> checkBotBeforeDisconnect()
            ConfirmDisconnectTelegramBot -> disconnectBot()
            CancelDisconnectTelegramBot -> setState { copy(showBotDeleteConfirmation = false) }
            is SelectSettingsSection -> setState { copy(activeSection = event.section) }
        }
    }

    private fun createTelegramBot() = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            setState { copy(telegramAuthBusy = true, telegramAuthError = null) }
            telegramSettingsHost.createControlBot(forceNew = true)
        }.onSuccess {
            setState { copy(telegramAuthBusy = false, isTelegramBotActive = true) }
            telegramSettingsHost.restartControlBotPolling()
            send(SettingsEffect.ShowSnackbar(getString(Res.string.bot_created_success_message)))
        }.onFailure { error ->
            val errorMsg = error.message ?: getString(Res.string.error_failed_to_create_bot)
            setState { copy(telegramAuthError = errorMsg, telegramAuthBusy = false) }
        }
    }

    private fun checkBotBeforeDisconnect() = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            setState { copy(telegramAuthBusy = true, telegramAuthError = null) }
            telegramSettingsHost.fetchActiveBotUsernameFromBotFather()
        }.onSuccess { activeUsername ->
            if (activeUsername != null) {
                setState { 
                    copy(
                        telegramAuthBusy = false,
                        showBotDeleteConfirmation = true,
                        botNameToDelete = activeUsername
                    )
                }
            } else {
                disconnectBot()
            }
        }.onFailure { error ->
            val errorMsg = error.message ?: getString(Res.string.error_failed_to_delete_bot)
            setState { copy(telegramAuthError = errorMsg, telegramAuthBusy = false) }
        }
    }

    private fun disconnectBot() = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            setState { copy(telegramAuthBusy = true, telegramAuthError = null, showBotDeleteConfirmation = false) }
            telegramSettingsHost.deleteControlBot(forceNew = true)
        }.onSuccess {
            telegramSettingsHost.stopControlBotPolling()
            setState { copy(isTelegramBotActive = false, telegramAuthBusy = false) }
            send(SettingsEffect.ShowSnackbar(getString(Res.string.bot_deleted_success_message)))
        }.onFailure { error ->
            val errorMsg = error.message ?: getString(Res.string.error_failed_to_delete_bot)
            setState { copy(telegramAuthError = errorMsg, telegramAuthBusy = false) }
        }
    }

    override suspend fun handleSideEffect(effect: SettingsEffect) = when (effect) {
        SettingsEffect.CloseScreen,
        SettingsEffect.OpenTools,
        SettingsEffect.NotifyOnSystemPrompt,
        is SettingsEffect.ShowSnackbar -> l.debug("ignore effect: {}", effect)
    }

    private suspend fun applySelectedModel(model: LLMModel) {
        flushPendingSystemPromptSave()
        val previousEmbeddingsModel = keysProvider.embeddingsModel
        val newPrompt = agentFacade.setModel(model)
        val effectiveEmbeddingsModel = keysProvider.embeddingsModel
        if (previousEmbeddingsModel != effectiveEmbeddingsModel) {
            localModelUiHost.rebuildIndex(viewModelScope, ioDispatchers)
        }
        setState {
            copy(
                gigaModel = model,
                systemPrompt = newPrompt,
                embeddingsModel = effectiveEmbeddingsModel,
                availableEmbeddingsModels = keysProvider.availableEmbeddingsModels(llmBuildProfile),
                localModelDownloadPrompt = null,
            )
        }
        fetchBalance()
        localModelPreloadJob = localModelUiHost.schedulePreload(
            scope = viewModelScope,
            dispatcher = ioDispatchers,
            currentJob = localModelPreloadJob,
            model = model,
        )
    }

    private suspend fun applySelectedAmbientAnalysisModel(model: LLMModel) {
        keysProvider.ambientAnalysisModel = model
        val effectiveModel = keysProvider.ambientAnalysisModel
        setState {
            copy(
                ambientAnalysisModel = effectiveModel,
                localModelDownloadPrompt = null,
            )
        }
        localModelPreloadJob = localModelUiHost.schedulePreload(
            scope = viewModelScope,
            dispatcher = ioDispatchers,
            currentJob = localModelPreloadJob,
            model = effectiveModel,
        )
    }

    private suspend fun confirmLocalModelDownload() {
        localModelDownloadJob = localModelUiHost.startDownload(
            scope = viewModelScope,
            dispatcher = ioDispatchers,
            currentJob = localModelDownloadJob,
            prompt = currentState.localModelDownloadPrompt,
            updateDownloadState = { state ->
                setState {
                    copy(
                        localModelDownloadPrompt = null,
                        localModelDownloadState = state,
                    )
                }
            },
            onSuccess = { prompt ->
                when (pendingLocalModelSelectionTarget) {
                    LocalModelSelectionTarget.AGENT -> applySelectedModel(prompt.model)
                    LocalModelSelectionTarget.AMBIENT_ANALYSIS -> applySelectedAmbientAnalysisModel(prompt.model)
                }
                pendingLocalModelSelectionTarget = LocalModelSelectionTarget.AGENT
                setState { copy(localModelDownloadState = null) }
            },
            onError = { error ->
                send(SettingsEffect.ShowSnackbar(error.message ?: getString(Res.string.local_model_download_failed)))
            }
        )?.also { job ->
            job.invokeOnCompletion {
                if (localModelDownloadJob === job) {
                    localModelDownloadJob = null
                }
            }
        }
    }

    private suspend fun cancelLocalModelDownload() {
        localModelDownloadJob = localModelUiHost.cancelDownload(
            currentJob = localModelDownloadJob,
            hasActiveDownload = currentState.localModelDownloadState != null,
            clearDownloadState = {
                setState {
                    copy(
                        localModelDownloadPrompt = null,
                        localModelDownloadState = null,
                    )
                }
            },
            onCancelled = {
                send(SettingsEffect.ShowSnackbar(getString(Res.string.local_model_download_cancelled)))
            },
        )
    }

    private suspend fun refreshFromProvider() = withContext(settingsDispatcher) {
        val voiceSpeed = settingsHostPreferences.voiceSpeed
        val mcpServersJson = pendingTextSettingDrafts[DeferredTextSetting.MCP_SERVERS_JSON] ?: (keysProvider.mcpServersJson ?: "")
        val supportEmail = pendingTextSettingDrafts[DeferredTextSetting.SUPPORT_EMAIL]
            ?: (keysProvider.supportEmail ?: DEFAULT_SUPPORT_EMAIL)
        val apiKeyAvailability = apiKeyAvailabilityUseCase.availability()
        val apiKeyFields = apiKeyAvailability.fields
            .asSequence()
            .filterNot { it == ApiKeyField.CODEX }
            .associateWith { field ->
                when (val current = currentState.apiKeyFields[field]) {
                    is ApiKeyFieldState.Editable,
                    ApiKeyFieldState.Revealing -> current
                    else -> initialApiKeyState(field)
                }
            }

        val availableLlmModels = keysProvider.availableLlmModels(llmBuildProfile)
        val configuredLlmModel = keysProvider.gigaModel
        val selectedLlmModel = pickConfiguredOrDefault(
            configured = configuredLlmModel,
            available = availableLlmModels,
        ) { keysProvider.defaultLlmModel(llmBuildProfile) }
        val availableAmbientAnalysisModels = keysProvider.availableAmbientAnalysisModels(llmBuildProfile)
        val configuredAmbientAnalysisModel = keysProvider.ambientAnalysisModel
        val selectedAmbientAnalysisModel = pickConfiguredOrDefault(
            configured = configuredAmbientAnalysisModel,
            available = availableAmbientAnalysisModels,
        ) { keysProvider.defaultAmbientAnalysisModel(llmBuildProfile) }
        if (selectedAmbientAnalysisModel != configuredAmbientAnalysisModel) {
            keysProvider.ambientAnalysisModel = selectedAmbientAnalysisModel
        }
        val downloadPrompt = localModelUiHost.downloadPromptFor(selectedLlmModel)
        val selectedPrompt = if (downloadPrompt == null) {
            agentFacade.setModel(selectedLlmModel)
        } else {
            agentFacade.currentContext.value.systemPrompt
        }
        val activeAgentId = agentFacade.activeAgentId.value
        val availableAgents = agentFacade.availableAgents

        val configuredEmbeddingsModel = keysProvider.embeddingsModel
        val availableEmbeddingsModels = keysProvider.availableEmbeddingsModels(llmBuildProfile)
        val selectedEmbeddingsModel = pickConfiguredOrDefault(
            configured = configuredEmbeddingsModel,
            available = availableEmbeddingsModels,
        ) { keysProvider.defaultEmbeddingsModel(llmBuildProfile) }
        if (selectedEmbeddingsModel != configuredEmbeddingsModel) {
            keysProvider.embeddingsModel = selectedEmbeddingsModel
            localModelUiHost.rebuildIndex(viewModelScope, ioDispatchers)
        }

        val localMacOsSpeechAvailable = settingsHostPreferences.isLocalMacOsSpeechAvailable()
        val availableVoiceRecognitionModels = keysProvider.availableVoiceRecognitionModels(
            llmBuildProfile = llmBuildProfile,
            localMacOsSpeechAvailable = localMacOsSpeechAvailable,
        )
        val configuredVoiceRecognitionModel = keysProvider.voiceRecognitionModel
        val selectedVoiceRecognitionModel = pickConfiguredOrDefault(
            configured = configuredVoiceRecognitionModel,
            available = availableVoiceRecognitionModels,
        ) {
            keysProvider.defaultVoiceRecognitionModel(
                llmBuildProfile = llmBuildProfile,
                localMacOsSpeechAvailable = localMacOsSpeechAvailable,
            )
        }
        if (selectedVoiceRecognitionModel != configuredVoiceRecognitionModel) {
            keysProvider.voiceRecognitionModel = selectedVoiceRecognitionModel
        }

        val codexConnected = keysProvider.hasKey(LlmProvider.CODEX)
        val configuredKeysCount = configuredKeysCount(
            apiKeyFields,
            codexConnected,
            apiKeyAvailability.fields,
        )

        setState {
            copy(
                apiKeyFields = apiKeyFields,
                codexConnected = codexConnected,
                availableApiKeyFields = apiKeyAvailability.fields,
                availableApiKeyProviders = apiKeyAvailability.providers,
                supportsVoiceRecognitionApiKeys = apiKeyAvailability.supportsVoiceRecognitionApiKeys,
                configuredKeysCount = configuredKeysCount,
                mcpServersJson = mcpServersJson,
                useFewShotExamples = keysProvider.useFewShotExamples,
                useStreaming = keysProvider.useStreaming,
                notificationSoundEnabled = keysProvider.notificationSoundEnabled,
                voiceInputReviewEnabled = keysProvider.voiceInputReviewEnabled,
                useEnglishVersion = keysProvider.regionProfile == REGION_EN,
                safeModeEnabled = keysProvider.safeModeEnabled,
                activeAgentId = activeAgentId,
                availableAgents = availableAgents,
                gigaModel = selectedLlmModel,
                ambientAnalysisModel = selectedAmbientAnalysisModel,
                embeddingsModel = selectedEmbeddingsModel,
                voiceRecognitionModel = selectedVoiceRecognitionModel,
                localModelDownloadPrompt = downloadPrompt,
                availableLlmModels = availableLlmModels,
                availableAmbientAnalysisModels = availableAmbientAnalysisModels,
                availableEmbeddingsModels = availableEmbeddingsModels,
                availableVoiceRecognitionModels = availableVoiceRecognitionModels,
                requestTimeoutMillis = keysProvider.requestTimeoutMillis,
                requestTimeoutInput = keysProvider.requestTimeoutMillis.toString(),
                contextSize = keysProvider.contextSize,
                contextSizeInput = keysProvider.contextSize.toString(),
                temperature = keysProvider.temperature,
                temperatureInput = keysProvider.temperature.toString(),
                supportEmail = supportEmail,
                systemPrompt = pendingTextSettingDrafts[DeferredTextSetting.SYSTEM_PROMPT] ?: selectedPrompt,
                defaultCalendar = keysProvider.defaultCalendar,
                voiceSpeed = voiceSpeed,
                voiceSpeedInput = voiceSpeed.toString(),
            )
        }
    }

    private suspend fun handleDeferredKeyInput(field: ApiKeyField, value: String) {
        if (currentState.apiKeyFields[field] !is ApiKeyFieldState.Editable) return
        pendingKeyDrafts[field] = value
        setDeferredKeyState(field, value)
        pendingKeySaveJobs.remove(field)?.cancel()
        pendingKeySaveJobs[field] = viewModelScope.launch {
            delay(KEY_INPUT_SAVE_DEBOUNCE_MS)
            pendingKeySaveJobs.remove(field)
            persistDeferredKeySave(field)
        }
    }

    private suspend fun setDeferredKeyState(field: ApiKeyField, value: String) {
        setState {
            val current = apiKeyFields[field] as? ApiKeyFieldState.Editable
                ?: return@setState this
            val updatedFields = apiKeyFields + (
                field to current.copy(value = value)
            )
            copy(
                apiKeyFields = updatedFields,
                configuredKeysCount = configuredKeysCount(updatedFields, codexConnected),
            )
        }
    }

    private suspend fun persistDeferredKeySave(field: ApiKeyField) {
        val value = pendingKeyDrafts[field] ?: return
        if (!persistApiKey(field, value)) return
        if (pendingKeyDrafts[field] == value) {
            pendingKeyDrafts.remove(field)
        }
        if (field.requiresRefreshAfterSave) {
            flushPendingSystemPromptSave()
            refreshFromProvider()
        }
        if (field.requiresBalanceRefreshAfterSave) {
            fetchBalance()
        }
    }

    private suspend fun flushPendingKeySaves() {
        if (pendingKeyDrafts.isEmpty() && pendingKeySaveJobs.isEmpty()) return

        val jobs = pendingKeySaveJobs.values.toList()
        pendingKeySaveJobs.clear()
        jobs.forEach { it.cancelAndJoin() }

        val draftsToPersist = pendingKeyDrafts.toMap()
        if (draftsToPersist.isEmpty()) return

        val persistedFields = draftsToPersist
            .filter { (field, value) -> persistApiKey(field, value) }

        persistedFields.forEach { (field, value) ->
            if (pendingKeyDrafts[field] == value) {
                pendingKeyDrafts.remove(field)
            }
        }

        if (persistedFields.keys.any { it.requiresRefreshAfterSave }) {
            flushPendingSystemPromptSave()
            refreshFromProvider()
        }
        if (persistedFields.keys.any { it.requiresBalanceRefreshAfterSave }) {
            fetchBalance()
        }
    }

    private suspend fun toggleApiKeyVisibility(field: ApiKeyField) {
        if (field == ApiKeyField.CODEX || field !in currentState.availableApiKeyFields) return
        when (val state = currentState.apiKeyFields[field] ?: initialApiKeyState(field)) {
            ApiKeyFieldState.StoredHidden -> revealApiKey(field)

            ApiKeyFieldState.Revealing -> Unit

            is ApiKeyFieldState.Editable -> {
                if (state.revealed) {
                    flushPendingKeySave(field)
                    updateApiKeyField(field, initialApiKeyState(field))
                } else {
                    updateApiKeyField(field, state.copy(revealed = true))
                }
            }
        }
    }

    private suspend fun revealApiKey(field: ApiKeyField) {
        updateApiKeyField(field, ApiKeyFieldState.Revealing)
        try {
            updateApiKeyField(
                field,
                ApiKeyFieldState.Editable(value = readApiKey(field), revealed = true),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            l.warn("Failed to reveal API key for {}", field, error)
            updateApiKeyField(field, ApiKeyFieldState.StoredHidden)
            send(SettingsEffect.ShowSnackbar(getString(Res.string.error_failed_reveal_api_key)))
        }
    }

    private suspend fun flushPendingKeySave(field: ApiKeyField) {
        pendingKeySaveJobs.remove(field)?.cancelAndJoin()
        persistDeferredKeySave(field)
    }

    private suspend fun persistApiKey(field: ApiKeyField, value: String): Boolean = try {
        withContext(settingsDispatcher) {
            val storedValue = value.takeUnless(String::isBlank)
            when (field) {
                ApiKeyField.GIGA_CHAT -> keysProvider.gigaChatKey = storedValue
                ApiKeyField.QWEN_CHAT -> keysProvider.qwenChatKey = storedValue
                ApiKeyField.AI_TUNNEL -> keysProvider.aiTunnelKey = storedValue
                ApiKeyField.ANTHROPIC -> keysProvider.anthropicKey = storedValue
                ApiKeyField.OPENAI -> keysProvider.openaiKey = storedValue
                ApiKeyField.SALUTE_SPEECH -> keysProvider.saluteSpeechKey = storedValue
                ApiKeyField.CODEX -> error("Codex credentials are OAuth-controlled")
            }
        }
        true
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        l.warn("Failed to persist API key for {}", field, error)
        send(SettingsEffect.ShowSnackbar(getString(Res.string.error_failed_save_api_key)))
        false
    }

    private suspend fun readApiKey(field: ApiKeyField): String = withContext(settingsDispatcher) {
        when (field) {
            ApiKeyField.GIGA_CHAT -> keysProvider.gigaChatKey
            ApiKeyField.QWEN_CHAT -> keysProvider.qwenChatKey
            ApiKeyField.AI_TUNNEL -> keysProvider.aiTunnelKey
            ApiKeyField.ANTHROPIC -> keysProvider.anthropicKey
            ApiKeyField.OPENAI -> keysProvider.openaiKey
            ApiKeyField.SALUTE_SPEECH -> keysProvider.saluteSpeechKey
            ApiKeyField.CODEX -> error("Codex credentials are OAuth-controlled")
        } ?: error("Configured API key could not be read")
    }

    private fun initialApiKeyState(field: ApiKeyField): ApiKeyFieldState =
        if (hasApiKey(field)) ApiKeyFieldState.StoredHidden else ApiKeyFieldState.Editable("", false)

    private fun hasApiKey(field: ApiKeyField): Boolean = when (field) {
        ApiKeyField.GIGA_CHAT -> keysProvider.hasKey(LlmProvider.GIGA)
        ApiKeyField.QWEN_CHAT -> keysProvider.hasKey(LlmProvider.QWEN)
        ApiKeyField.AI_TUNNEL -> keysProvider.hasKey(LlmProvider.AI_TUNNEL)
        ApiKeyField.ANTHROPIC -> keysProvider.hasKey(LlmProvider.ANTHROPIC)
        ApiKeyField.OPENAI -> keysProvider.hasKey(LlmProvider.OPENAI)
        ApiKeyField.SALUTE_SPEECH -> keysProvider.hasKey(VoiceRecognitionProvider.SALUTE_SPEECH)
        ApiKeyField.CODEX -> keysProvider.hasKey(LlmProvider.CODEX)
    }

    private suspend fun leaveSettings(effect: SettingsEffect) {
        setState { copy(apiKeyFields = apiKeyFields.mapValues { (_, state) -> state.concealed() }) }
        flushPendingTextSettingSaves()
        flushPendingKeySaves()
        send(effect)
    }

    private suspend fun updateApiKeyField(field: ApiKeyField, state: ApiKeyFieldState) {
        setState {
            val updatedFields = apiKeyFields + (field to state)
            copy(
                apiKeyFields = updatedFields,
                configuredKeysCount = configuredKeysCount(updatedFields, codexConnected),
            )
        }
    }

    private fun configuredKeysCount(
        fields: Map<ApiKeyField, ApiKeyFieldState>,
        codexConnected: Boolean,
        availableFields: Set<ApiKeyField> = currentState.availableApiKeyFields,
    ): Int = fields.values.count(ApiKeyFieldState::isConfigured) +
        if (codexConnected && ApiKeyField.CODEX in availableFields) 1 else 0

    private suspend fun handleDeferredTextSettingInput(field: DeferredTextSetting, value: String) {
        pendingTextSettingDrafts[field] = value
        setDeferredTextSettingState(field, value)
        pendingTextSettingSaveJobs.remove(field)?.cancel()
        pendingTextSettingSaveJobs[field] = viewModelScope.launch {
            delay(TEXT_INPUT_SAVE_DEBOUNCE_MS)
            pendingTextSettingSaveJobs.remove(field)
            persistDeferredTextSettingSave(field)
        }
    }

    private suspend fun setDeferredTextSettingState(field: DeferredTextSetting, value: String) {
        when (field) {
            DeferredTextSetting.MCP_SERVERS_JSON -> setState { copy(mcpServersJson = value) }
            DeferredTextSetting.SUPPORT_EMAIL -> setState {
                copy(supportEmail = value, sendLogsMessage = null, sendLogsPath = null)
            }
            DeferredTextSetting.SYSTEM_PROMPT -> setState { copy(systemPrompt = value) }
        }
    }

    private suspend fun persistDeferredTextSettingSave(field: DeferredTextSetting) {
        val value = pendingTextSettingDrafts[field] ?: return
        when (field) {
            DeferredTextSetting.MCP_SERVERS_JSON -> withContext(Dispatchers.IO) {
                keysProvider.mcpServersJson = value
            }
            DeferredTextSetting.SUPPORT_EMAIL -> withContext(Dispatchers.IO) {
                keysProvider.supportEmail = value
            }
            DeferredTextSetting.SYSTEM_PROMPT -> {
                agentFacade.updateSystemPrompt(value)
                send(SettingsEffect.NotifyOnSystemPrompt)
            }
        }
        if (pendingTextSettingDrafts[field] == value) {
            pendingTextSettingDrafts.remove(field)
        }
    }

    private suspend fun flushPendingTextSettingSaves() {
        if (pendingTextSettingDrafts.isEmpty() && pendingTextSettingSaveJobs.isEmpty()) return

        val jobs = pendingTextSettingSaveJobs.values.toList()
        pendingTextSettingSaveJobs.clear()
        jobs.forEach { it.cancelAndJoin() }

        val fieldsToPersist = pendingTextSettingDrafts.keys.toList()
        fieldsToPersist.forEach { field -> persistDeferredTextSettingSave(field) }
    }

    private suspend fun flushPendingSystemPromptSave() {
        if (DeferredTextSetting.SYSTEM_PROMPT !in pendingTextSettingDrafts &&
            DeferredTextSetting.SYSTEM_PROMPT !in pendingTextSettingSaveJobs
        ) return

        pendingTextSettingSaveJobs.remove(DeferredTextSetting.SYSTEM_PROMPT)?.cancelAndJoin()
        persistDeferredTextSettingSave(DeferredTextSetting.SYSTEM_PROMPT)
    }

    private suspend fun cancelPendingSystemPromptSave() {
        pendingTextSettingSaveJobs.remove(DeferredTextSetting.SYSTEM_PROMPT)?.cancelAndJoin()
        pendingTextSettingDrafts.remove(DeferredTextSetting.SYSTEM_PROMPT)
    }

    private fun <T> pickConfiguredOrDefault(
        configured: T,
        available: List<T>,
        default: () -> T?,
    ): T = when {
        available.isEmpty() -> configured
        configured in available -> configured
        else -> default() ?: available.first()
    }

    private fun fetchCalendars() = viewModelScope.launch(Dispatchers.IO) {
        setState { copy(isLoadingCalendars = true) }

        val result = runCatching {
            calendarListProvider()
        }.getOrElse {
            l.error("Error fetching calendars", it)
            emptyList()
        }

        val calendars = result
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toList()

        setState {
            copy(
                availableCalendars = calendars,
                isLoadingCalendars = false
            )
        }
    }

    private fun sendLogs() = viewModelScope.launch(Dispatchers.IO) {
        val email = currentState.supportEmail.ifBlank { DEFAULT_SUPPORT_EMAIL }
        val resolvedLogDir = supportLogService.logDirectoryPath()
        setState {
            copy(
                isSendingLogs = true,
                sendLogsMessage = null,
                supportEmail = email,
                sendLogsPath = resolvedLogDir
            )
        }

        val result = runCatching { supportLogService.sendLatestLogs(email) }
        result
            .onSuccess { sendResult ->
                setState {
                    copy(
                        isSendingLogs = false,
                        sendLogsMessage = sendResult.message,
                        supportEmail = sendResult.recipient,
                        sendLogsPath = sendResult.logArchivePath,
                    )
                }
            }
            .onFailure { error ->
                val errorMsg = error.message ?: getString(Res.string.error_failed_send_logs)
                setState {
                    copy(
                        isSendingLogs = false,
                        sendLogsMessage = errorMsg,
                        sendLogsPath = resolvedLogDir,
                    )
                }
            }
    }

    private fun openPrivacyPolicy() = viewModelScope.launch(Dispatchers.IO) {
        privacyPolicyOpener.openPrivacyPolicy(keysProvider.regionProfile).onFailure { error ->
            l.warn("Failed to open privacy policy", error)
            val fallbackMessage = getString(Res.string.error_failed_open_privacy_policy)
            send(SettingsEffect.ShowSnackbar(error.message ?: fallbackMessage))
        }
    }

    private fun fetchBalance() = viewModelScope.launch(Dispatchers.IO) {
        if (currentState.isBalanceLoading) return@launch

        when (currentState.gigaModel.provider) {
            LlmProvider.GIGA -> {
                if (!keysProvider.hasKey(LlmProvider.GIGA)) {
                    val errorMsg = getString(Res.string.error_gigachat_key_missing)
                    setState {
                        copy(
                            balance = emptyList(),
                            balanceError = errorMsg,
                            isBalanceLoading = false
                        )
                    }
                    return@launch
                }
            }
            LlmProvider.QWEN -> {
                val errorMsg = getString(Res.string.error_balance_unavailable_qwen)
                setState {
                    copy(
                        balance = emptyList(),
                        balanceError = errorMsg,
                        isBalanceLoading = false
                    )
                }
                return@launch
            }
            LlmProvider.AI_TUNNEL -> {
                val errorMsg = getString(Res.string.error_balance_unavailable_aitunnel)
                setState {
                    copy(
                        balance = emptyList(),
                        balanceError = errorMsg,
                        isBalanceLoading = false
                    )
                }
                return@launch
            }
            LlmProvider.ANTHROPIC -> {
                val errorMsg = getString(Res.string.error_balance_unavailable_anthropic)
                setState {
                    copy(
                        balance = emptyList(),
                        balanceError = errorMsg,
                        isBalanceLoading = false
                    )
                }
                return@launch
            }
            LlmProvider.OPENAI -> {
                val errorMsg = getString(Res.string.error_balance_unavailable_openai)
                setState {
                    copy(
                        balance = emptyList(),
                        balanceError = errorMsg,
                        isBalanceLoading = false
                    )
                }
                return@launch
            }
            LlmProvider.LOCAL -> {
                setState {
                    copy(
                        balance = emptyList(),
                        balanceError = "Balance is not available for the local provider.",
                        isBalanceLoading = false
                    )
                }
                return@launch
            }
            LlmProvider.CODEX -> {
                setState {
                    copy(
                        balance = emptyList(),
                        balanceError = "Balance is not available for Codex.",
                        isBalanceLoading = false
                    )
                }
                return@launch
            }

        }

        setState { copy(isBalanceLoading = true, balanceError = null) }

        when (val result = chatApi.balance()) {
            is LLMResponse.Balance.Ok -> setState {
                copy(
                    balance = result.balance,
                    isBalanceLoading = false,
                    balanceError = null,
                )
            }

            is LLMResponse.Balance.Error -> setState {
                copy(
                    balance = emptyList(),
                    isBalanceLoading = false,
                    balanceError = result.message,
                )
            }
        }
    }

    private fun submitTelegramPhone() = viewModelScope.launch(Dispatchers.IO) {
        val phone = currentState.telegramPhoneInput.trim()
        if (phone.isBlank()) {
            val errorMsg = getString(Res.string.error_enter_phone)
            setState { copy(telegramAuthError = errorMsg) }
            return@launch
        }

        runCatching { telegramSettingsHost.submitPhoneNumber(phone) }
            .onFailure { error ->
                val errorMsg = error.message ?: getString(Res.string.error_failed_request_code)
                setState { copy(telegramAuthError = errorMsg) }
            }
    }

    private fun submitTelegramCode() = viewModelScope.launch(Dispatchers.IO) {
        val code = currentState.telegramCodeInput.trim()
        if (code.isBlank()) {
            val errorMsg = getString(Res.string.error_enter_code)
            setState { copy(telegramAuthError = errorMsg) }
            return@launch
        }

        runCatching { telegramSettingsHost.submitLoginCode(code) }
            .onFailure { error ->
                val errorMsg = error.message ?: getString(Res.string.error_failed_verify_code)
                setState { copy(telegramAuthError = errorMsg) }
            }
    }

    private fun submitTelegramPassword() = viewModelScope.launch(Dispatchers.IO) {
        val password = currentState.telegramPasswordInput
        if (password.isBlank()) {
            val errorMsg = getString(Res.string.error_enter_password)
            setState { copy(telegramAuthError = errorMsg) }
            return@launch
        }

        runCatching { telegramSettingsHost.submitTwoFaPassword(password) }
            .onFailure { error ->
                val errorMsg = error.message ?: getString(Res.string.error_failed_verify_password)
                setState { copy(telegramAuthError = errorMsg) }
            }
    }

    private fun telegramLogout() = viewModelScope.launch(Dispatchers.IO) {
        runCatching { telegramSettingsHost.logout() }
            .onFailure { error ->
                val errorMsg = error.message ?: getString(Res.string.error_failed_logout)
                setState { copy(telegramAuthError = errorMsg) }
            }
    }

    override fun onCleared() {
        super.onCleared()
        localModelPreloadJob?.cancel()
    }

    private enum class DeferredTextSetting {
        MCP_SERVERS_JSON,
        SUPPORT_EMAIL,
        SYSTEM_PROMPT,
    }

    private enum class LocalModelSelectionTarget {
        AGENT,
        AMBIENT_ANALYSIS,
    }

    companion object {
        private const val KEY_INPUT_SAVE_DEBOUNCE_MS = 400L
        private const val TEXT_INPUT_SAVE_DEBOUNCE_MS = 400L
    }
}

private fun CodexOAuthState.toUiState(): CodexOAuthUiState = when (this) {
    is CodexOAuthState.Idle -> CodexOAuthUiState.Idle
    is CodexOAuthState.AwaitingUserCode -> CodexOAuthUiState.AwaitingUserCode(userCode)
    is CodexOAuthState.Polling -> CodexOAuthUiState.Polling
    is CodexOAuthState.Success -> CodexOAuthUiState.Done
    is CodexOAuthState.Error -> CodexOAuthUiState.Error(message)
}

private fun ApiKeyFieldState.isConfigured(): Boolean = when (this) {
    ApiKeyFieldState.StoredHidden,
    ApiKeyFieldState.Revealing -> true
    is ApiKeyFieldState.Editable -> value.isNotBlank()
}

private fun ApiKeyFieldState.concealed(): ApiKeyFieldState = when (this) {
    ApiKeyFieldState.StoredHidden -> this
    ApiKeyFieldState.Revealing -> ApiKeyFieldState.StoredHidden
    is ApiKeyFieldState.Editable -> if (value.isBlank()) {
        copy(revealed = false)
    } else {
        ApiKeyFieldState.StoredHidden
    }
}

private val ApiKeyField.requiresRefreshAfterSave: Boolean
    get() = this != ApiKeyField.SALUTE_SPEECH && this != ApiKeyField.CODEX

private val ApiKeyField.requiresBalanceRefreshAfterSave: Boolean
    get() = this == ApiKeyField.GIGA_CHAT || this == ApiKeyField.QWEN_CHAT
