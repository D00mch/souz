package ru.souz.ui.settings

import androidx.lifecycle.viewModelScope
import io.ktor.util.logging.debug
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
import ru.souz.agent.defaultSystemPromptForRegion
import ru.souz.agent.GraphBasedAgent
import ru.souz.audio.Say
import ru.souz.db.ConfigStore
import ru.souz.db.SettingsProvider
import ru.souz.db.SettingsProviderImpl.Companion.REGION_EN
import ru.souz.db.SettingsProviderImpl.Companion.REGION_RU
import ru.souz.db.VectorDB
import ru.souz.giga.GigaChatAPI
import ru.souz.giga.GigaResponse
import ru.souz.giga.LlmBuildProfile
import ru.souz.giga.LlmProvider
import ru.souz.service.telegram.TelegramAuthStep
import ru.souz.service.telegram.TelegramPlatformSupport
import ru.souz.service.telegram.TelegramService
import ru.souz.tool.config.ToolSoundConfig
import ru.souz.tool.ToolRunBashCommand
import ru.souz.tool.calendar.CalendarAppleScriptCommands
import ru.souz.ui.BaseViewModel
import ru.souz.ui.common.openProviderLink
import ru.souz.ui.common.usecases.ApiKeyAvailabilityUseCase
import ru.souz.ui.common.usecases.ApiKeyValues
import ru.souz.ui.settings.SettingsEvent.*
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class SettingsViewModel(
    override val di: DI,
) : BaseViewModel<SettingsState, SettingsEvent, SettingsEffect>(), DIAware {

    private val l = LoggerFactory.getLogger(SettingsViewModel::class.java)
    private val keysProvider: SettingsProvider by di.instance()
    private val llmBuildProfile: LlmBuildProfile by di.instance()
    private val apiKeyAvailabilityUseCase: ApiKeyAvailabilityUseCase by di.instance()
    private val chatApi: GigaChatAPI by di.instance()
    private val graphBasedAgent: GraphBasedAgent by di.instance()
    private val telegramPlatformSupport: TelegramPlatformSupport by di.instance()
    private val telegramService: TelegramService by di.instance()
    private val telegramBotController: ru.souz.service.telegram.TelegramBotController by di.instance()
    private val supportLogSender = SupportLogSender()
    private val say: Say by di.instance()
    private val pendingKeyDrafts = mutableMapOf<DeferredSettingsKey, String>()
    private val pendingKeySaveJobs = mutableMapOf<DeferredSettingsKey, Job>()
    private val pendingTextSettingDrafts = mutableMapOf<DeferredTextSetting, String>()
    private val pendingTextSettingSaveJobs = mutableMapOf<DeferredTextSetting, Job>()

    init {
        viewModelScope.launch {
            setState { copy(isTelegramSupported = telegramPlatformSupport.isSupported()) }
            refreshFromProvider()
            fetchBalance()
            fetchCalendars()
        }

        viewModelScope.launch(Dispatchers.IO) {
            telegramService.authState.collectLatest { auth ->
                setState {
                    copy(
                        telegramAuthStep = when (auth.step) {
                            TelegramAuthStep.INITIALIZING -> TelegramAuthStepUi.INITIALIZING
                            TelegramAuthStep.WAIT_PHONE -> TelegramAuthStepUi.PHONE
                            TelegramAuthStep.WAIT_CODE -> TelegramAuthStepUi.CODE
                            TelegramAuthStep.WAIT_PASSWORD -> TelegramAuthStepUi.PASSWORD
                            TelegramAuthStep.READY -> TelegramAuthStepUi.CONNECTED
                            TelegramAuthStep.LOGGING_OUT -> TelegramAuthStepUi.LOGGING_OUT
                            TelegramAuthStep.CLOSED -> TelegramAuthStepUi.INITIALIZING
                            TelegramAuthStep.ERROR -> TelegramAuthStepUi.ERROR
                        },
                        telegramActiveSessionPhone = auth.activePhoneMasked,
                        telegramCodeHint = auth.codeHint,
                        telegramPasswordHint = auth.passwordHint,
                        telegramAuthBusy = auth.isBusy,
                        telegramAuthError = auth.errorMessage,
                        telegramCodeInput = if (auth.step == TelegramAuthStep.READY) "" else telegramCodeInput,
                        telegramPasswordInput = if (auth.step == TelegramAuthStep.READY) "" else telegramPasswordInput,
                    )
                }
            }
        }
    }

    override fun initialState(): SettingsState = SettingsState(
        isTelegramBotActive = ConfigStore.get<String>(ConfigStore.TG_BOT_TOKEN) != null
    )

    override suspend fun handleEvent(event: SettingsEvent) {
        l.debug { "handleEvent: $event" }
        when(event) {
            is InputGigaChatKey -> {
                handleDeferredKeyInput(DeferredSettingsKey.GIGA_CHAT, event.key)
            }
            is InputQwenChatKey -> {
                handleDeferredKeyInput(DeferredSettingsKey.QWEN_CHAT, event.key)
            }
            is InputSaluteSpeechKey -> {
                handleDeferredKeyInput(DeferredSettingsKey.SALUTE_SPEECH, event.key)
            }
            is OpenProviderLink -> openProviderLink(url = event.provider.url, logger = l)
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
            is InputUseEnglishVersion -> {
                val newLanguage = if (event.enabled) REGION_EN else REGION_RU
                if (keysProvider.regionProfile != newLanguage) {
                    keysProvider.regionProfile = newLanguage
                    flushPendingSystemPromptSave()
                    refreshFromProvider()
                    fetchBalance()
                }
            }
            is InputAiTunnelKey -> {
                handleDeferredKeyInput(DeferredSettingsKey.AI_TUNNEL, event.key)
            }
            is InputAnthropicKey -> {
                handleDeferredKeyInput(DeferredSettingsKey.ANTHROPIC, event.key)
            }
            is InputOpenAiKey -> {
                handleDeferredKeyInput(DeferredSettingsKey.OPENAI, event.key)
            }
            is InputSafeModeEnabled -> {
                keysProvider.safeModeEnabled = event.enabled
                setState { copy(safeModeEnabled = event.enabled) }
            }
            is SelectModel -> {
                if (event.model !in currentState.availableLlmModels) return
                flushPendingSystemPromptSave()
                val newPrompt = graphBasedAgent.setModel(event.model)
                setState { copy(gigaModel = event.model, systemPrompt = newPrompt) }
                fetchBalance()
            }
            is SelectEmbeddingsModel -> {
                if (event.model !in currentState.availableEmbeddingsModels) return
                val currentModel = keysProvider.embeddingsModel
                keysProvider.embeddingsModel = event.model
                if (currentModel != event.model) {
                    VectorDB.clearAllData()
                }
                setState { copy(embeddingsModel = event.model) }
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
                    graphBasedAgent.setContextSize(newContextSize)
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
                    graphBasedAgent.setTemperature(newTemperature)
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
                    ConfigStore.put(ToolSoundConfig.SPEED_KEY, newSpeed)
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
            }
            ChooseVoice -> {
                runCatching { say.chooseVoice() }
                    .onFailure { l.warn("Failed to open voice settings", it) }
            }
            ResetSystemPrompt -> {
                cancelPendingSystemPromptSave()
                graphBasedAgent.resetSystemPrompt()
                send(SettingsEffect.NotifyOnSystemPrompt)
                setState { copy(systemPrompt = defaultSystemPromptForRegion(keysProvider.regionProfile)) }
            }
            is SendLogsToSupport -> sendLogs()
            OpenPrivacyPolicy -> openPrivacyPolicy()
            RefreshBalance -> fetchBalance()
            GoToMain -> {
                flushPendingTextSettingSaves()
                flushPendingKeySaves()
                send(SettingsEffect.CloseScreen)
            }
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
            telegramService.createControlBot(forceNew = true)
        }.onSuccess {
            setState { copy(telegramAuthBusy = false, isTelegramBotActive = true) }
            telegramBotController.restartPolling()
            send(SettingsEffect.ShowSnackbar(getString(Res.string.bot_created_success_message)))
        }.onFailure { error ->
            val errorMsg = error.message ?: getString(Res.string.error_failed_to_create_bot)
            setState { copy(telegramAuthError = errorMsg, telegramAuthBusy = false) }
        }
    }

    private fun checkBotBeforeDisconnect() = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            setState { copy(telegramAuthBusy = true, telegramAuthError = null) }
            telegramService.fetchActiveBotUsernameFromBotFather()
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
            telegramService.deleteControlBot(forceNew = true)
        }.onSuccess {
            telegramBotController.stopPolling()
            setState { copy(isTelegramBotActive = false, telegramAuthBusy = false) }
            send(SettingsEffect.ShowSnackbar(getString(Res.string.bot_deleted_success_message)))
        }.onFailure { error ->
            val errorMsg = error.message ?: getString(Res.string.error_failed_to_delete_bot)
            setState { copy(telegramAuthError = errorMsg, telegramAuthBusy = false) }
        }
    }

    override suspend fun handleSideEffect(effect: SettingsEffect) = when (effect) {
        SettingsEffect.CloseScreen,
        SettingsEffect.NotifyOnSystemPrompt,
        is SettingsEffect.ShowSnackbar -> l.debug { "ignore effect: $effect" }
    }

    private suspend fun refreshFromProvider() {
        val voiceSpeed = ConfigStore.get(ToolSoundConfig.SPEED_KEY, ToolSoundConfig.DEFAULT_SPEED)
        val gigaChatKey = pendingKeyDrafts[DeferredSettingsKey.GIGA_CHAT] ?: keysProvider.gigaChatKey.orEmpty()
        val qwenChatKey = pendingKeyDrafts[DeferredSettingsKey.QWEN_CHAT] ?: keysProvider.qwenChatKey.orEmpty()
        val aiTunnelKey = pendingKeyDrafts[DeferredSettingsKey.AI_TUNNEL] ?: keysProvider.aiTunnelKey.orEmpty()
        val anthropicKey = pendingKeyDrafts[DeferredSettingsKey.ANTHROPIC] ?: keysProvider.anthropicKey.orEmpty()
        val openAiKey = pendingKeyDrafts[DeferredSettingsKey.OPENAI] ?: keysProvider.openaiKey.orEmpty()
        val saluteSpeechKey = pendingKeyDrafts[DeferredSettingsKey.SALUTE_SPEECH] ?: (keysProvider.saluteSpeechKey ?: "")
        val mcpServersJson = pendingTextSettingDrafts[DeferredTextSetting.MCP_SERVERS_JSON] ?: (keysProvider.mcpServersJson ?: "")
        val supportEmail = pendingTextSettingDrafts[DeferredTextSetting.SUPPORT_EMAIL]
            ?: (keysProvider.supportEmail ?: DEFAULT_SUPPORT_EMAIL)
        val apiKeyAvailability = apiKeyAvailabilityUseCase.availability()

        val availableLlmModels = keysProvider.availableLlmModels(llmBuildProfile)
        val configuredLlmModel = keysProvider.gigaModel
        val selectedLlmModel = pickConfiguredOrDefault(
            configured = configuredLlmModel,
            available = availableLlmModels,
        ) { keysProvider.defaultLlmModel(llmBuildProfile) }
        val selectedPrompt = if (selectedLlmModel != currentState.gigaModel) {
            graphBasedAgent.setModel(selectedLlmModel)
        } else {
            keysProvider.getSystemPromptForModel(selectedLlmModel)
                ?: defaultSystemPromptForRegion(keysProvider.regionProfile)
        }

        val availableEmbeddingsModels = keysProvider.availableEmbeddingsModels(llmBuildProfile)
        val configuredEmbeddingsModel = keysProvider.embeddingsModel
        val selectedEmbeddingsModel = pickConfiguredOrDefault(
            configured = configuredEmbeddingsModel,
            available = availableEmbeddingsModels,
        ) { keysProvider.defaultEmbeddingsModel(llmBuildProfile) }
        if (selectedEmbeddingsModel != configuredEmbeddingsModel) {
            keysProvider.embeddingsModel = selectedEmbeddingsModel
            VectorDB.clearAllData()
        }

        val availableVoiceRecognitionModels = keysProvider.availableVoiceRecognitionModels(llmBuildProfile)
        val configuredVoiceRecognitionModel = keysProvider.voiceRecognitionModel
        val selectedVoiceRecognitionModel = pickConfiguredOrDefault(
            configured = configuredVoiceRecognitionModel,
            available = availableVoiceRecognitionModels,
        ) { keysProvider.defaultVoiceRecognitionModel(llmBuildProfile) }
        if (selectedVoiceRecognitionModel != configuredVoiceRecognitionModel) {
            keysProvider.voiceRecognitionModel = selectedVoiceRecognitionModel
        }

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
                availableApiKeyFields = apiKeyAvailability.fields,
                availableApiKeyProviders = apiKeyAvailability.providers,
                supportsVoiceRecognitionApiKeys = apiKeyAvailability.supportsVoiceRecognitionApiKeys,
                configuredKeysCount = configuredKeysCount,
                mcpServersJson = mcpServersJson,
                useFewShotExamples = keysProvider.useFewShotExamples,
                useStreaming = keysProvider.useStreaming,
                notificationSoundEnabled = keysProvider.notificationSoundEnabled,
                useEnglishVersion = keysProvider.regionProfile == REGION_EN,
                safeModeEnabled = keysProvider.safeModeEnabled,
                gigaModel = selectedLlmModel,
                embeddingsModel = selectedEmbeddingsModel,
                voiceRecognitionModel = selectedVoiceRecognitionModel,
                availableLlmModels = availableLlmModels,
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

    private suspend fun handleDeferredKeyInput(field: DeferredSettingsKey, value: String) {
        pendingKeyDrafts[field] = value
        setDeferredKeyState(field, value)
        pendingKeySaveJobs.remove(field)?.cancel()
        pendingKeySaveJobs[field] = viewModelScope.launch {
            delay(KEY_INPUT_SAVE_DEBOUNCE_MS)
            pendingKeySaveJobs.remove(field)
            persistDeferredKeySave(field)
        }
    }

    private suspend fun setDeferredKeyState(field: DeferredSettingsKey, value: String) {
        setState {
            val updated = when (field) {
                DeferredSettingsKey.GIGA_CHAT -> copy(gigaChatKey = value)
                DeferredSettingsKey.QWEN_CHAT -> copy(qwenChatKey = value)
                DeferredSettingsKey.AI_TUNNEL -> copy(aiTunnelKey = value)
                DeferredSettingsKey.ANTHROPIC -> copy(anthropicKey = value)
                DeferredSettingsKey.OPENAI -> copy(openaiKey = value)
                DeferredSettingsKey.SALUTE_SPEECH -> copy(saluteSpeechKey = value)
            }
            updated.copy(
                configuredKeysCount = apiKeyAvailabilityUseCase.configuredKeysCount(updated.toApiKeyValues()),
            )
        }
    }

    private suspend fun persistDeferredKeySave(field: DeferredSettingsKey) {
        val value = pendingKeyDrafts[field] ?: return
        withContext(Dispatchers.IO) {
            applyDeferredKeyToProvider(field, value)
        }
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

        withContext(Dispatchers.IO) {
            draftsToPersist.forEach { (field, value) ->
                applyDeferredKeyToProvider(field, value)
            }
        }

        draftsToPersist.forEach { (field, value) ->
            if (pendingKeyDrafts[field] == value) {
                pendingKeyDrafts.remove(field)
            }
        }

        if (draftsToPersist.keys.any { it.requiresRefreshAfterSave }) {
            flushPendingSystemPromptSave()
            refreshFromProvider()
        }
        if (draftsToPersist.keys.any { it.requiresBalanceRefreshAfterSave }) {
            fetchBalance()
        }
    }

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
                graphBasedAgent.updateSystemPrompt(value)
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

    private fun applyDeferredKeyToProvider(field: DeferredSettingsKey, value: String) {
        when (field) {
            DeferredSettingsKey.GIGA_CHAT -> keysProvider.gigaChatKey = value
            DeferredSettingsKey.QWEN_CHAT -> keysProvider.qwenChatKey = value
            DeferredSettingsKey.AI_TUNNEL -> keysProvider.aiTunnelKey = value
            DeferredSettingsKey.ANTHROPIC -> keysProvider.anthropicKey = value
            DeferredSettingsKey.OPENAI -> keysProvider.openaiKey = value
            DeferredSettingsKey.SALUTE_SPEECH -> keysProvider.saluteSpeechKey = value
        }
    }

    private fun SettingsState.toApiKeyValues(): ApiKeyValues = ApiKeyValues(
        gigaChatKey = gigaChatKey,
        qwenChatKey = qwenChatKey,
        aiTunnelKey = aiTunnelKey,
        anthropicKey = anthropicKey,
        openaiKey = openaiKey,
        saluteSpeechKey = saluteSpeechKey,
    )

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

        val cmd = CalendarAppleScriptCommands.listCalendarsCommand("")

        val result = runCatching {
            ToolRunBashCommand.sh(cmd)
        }.getOrElse {
            l.error("Error fetching calendars", it)
            ""
        }

        val calendars = result
            .lines()
            .asSequence()
            .map { it.trim() }
            .filter { it.startsWith("- ") }
            .map { it.removePrefix("- ").trim() }
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
        val resolvedLogDir = runCatching { supportLogSender.logDirectory().toAbsolutePath().toString() }.getOrNull()
        setState {
            copy(
                isSendingLogs = true,
                sendLogsMessage = null,
                supportEmail = email,
                sendLogsPath = resolvedLogDir
            )
        }

        val result = runCatching { supportLogSender.sendLatestLogs(email) }
        result
            .onSuccess { sendResult ->
                setState {
                    copy(
                        isSendingLogs = false,
                        sendLogsMessage = sendResult.message,
                        supportEmail = sendResult.recipient,
                        sendLogsPath = sendResult.logArchive.toAbsolutePath().toString(),
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
        runCatching {
            val resourcePath = if (keysProvider.regionProfile == REGION_EN) {
                "support/privacy-policy.html"
            } else {
                "support/privacy-policy-ru.html"
            }
            val targetPath = extractClasspathResourceToTemp(resourcePath)
            if (!Desktop.isDesktopSupported()) error("Desktop browsing is not supported")
            val desktop = Desktop.getDesktop()
            if (!desktop.isSupported(Desktop.Action.BROWSE)) error("Desktop browsing action is not supported")
            desktop.browse(targetPath.toUri())
        }.onFailure { error ->
            l.warn("Failed to open privacy policy", error)
            val fallbackMessage = getString(Res.string.error_failed_open_privacy_policy)
            send(SettingsEffect.ShowSnackbar(error.message ?: fallbackMessage))
        }
    }

    private fun extractClasspathResourceToTemp(resourcePath: String): Path {
        val input = javaClass.classLoader.getResourceAsStream(resourcePath)
            ?: error("Resource not found: $resourcePath")
        val tempDir = Path.of(System.getProperty("java.io.tmpdir"), "souz-support")
        Files.createDirectories(tempDir)
        val target = tempDir.resolve(resourcePath.substringAfterLast('/'))
        input.use { stream ->
            Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING)
        }
        return target
    }

    private fun fetchBalance() = viewModelScope.launch(Dispatchers.IO) {
        if (currentState.isBalanceLoading) return@launch

        when (currentState.gigaModel.provider) {
            LlmProvider.GIGA -> {
                if (currentState.gigaChatKey.isBlank()) {
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

        }

        setState { copy(isBalanceLoading = true, balanceError = null) }

        when (val result = chatApi.balance()) {
            is GigaResponse.Balance.Ok -> setState {
                copy(
                    balance = result.balance,
                    isBalanceLoading = false,
                    balanceError = null,
                )
            }

            is GigaResponse.Balance.Error -> setState {
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

        runCatching { telegramService.submitPhoneNumber(phone) }
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

        runCatching { telegramService.submitLoginCode(code) }
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

        runCatching { telegramService.submitTwoFaPassword(password) }
            .onFailure { error ->
                val errorMsg = error.message ?: getString(Res.string.error_failed_verify_password)
                setState { copy(telegramAuthError = errorMsg) }
            }
    }

    private fun telegramLogout() = viewModelScope.launch(Dispatchers.IO) {
        runCatching { telegramService.logout() }
            .onFailure { error ->
                val errorMsg = error.message ?: getString(Res.string.error_failed_logout)
                setState { copy(telegramAuthError = errorMsg) }
            }
    }

    private enum class DeferredSettingsKey {
        GIGA_CHAT,
        QWEN_CHAT,
        AI_TUNNEL,
        ANTHROPIC,
        OPENAI,
        SALUTE_SPEECH;

        val requiresRefreshAfterSave: Boolean
            get() = this != SALUTE_SPEECH

        val requiresBalanceRefreshAfterSave: Boolean
            get() = this == GIGA_CHAT || this == QWEN_CHAT
    }

    private enum class DeferredTextSetting {
        MCP_SERVERS_JSON,
        SUPPORT_EMAIL,
        SYSTEM_PROMPT,
    }

    companion object {
        private const val KEY_INPUT_SAVE_DEBOUNCE_MS = 400L
        private const val TEXT_INPUT_SAVE_DEBOUNCE_MS = 400L
    }
}
