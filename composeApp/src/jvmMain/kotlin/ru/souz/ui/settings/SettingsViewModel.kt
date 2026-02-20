package ru.souz.ui.settings

import androidx.lifecycle.viewModelScope
import io.ktor.util.logging.debug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.souz.agent.DEFAULT_SYSTEM_PROMPT
import ru.souz.agent.GraphBasedAgent
import ru.souz.audio.Say
import ru.souz.db.ConfigStore
import ru.souz.db.SettingsProvider
import ru.souz.db.VectorDB
import ru.souz.giga.GigaChatAPI
import ru.souz.giga.GigaResponse
import ru.souz.giga.LlmProvider
import ru.souz.service.telegram.TelegramAuthStep
import ru.souz.service.telegram.TelegramService
import ru.souz.tool.config.ToolSoundConfig
import ru.souz.tool.ToolRunBashCommand
import ru.souz.tool.calendar.CalendarAppleScriptCommands
import ru.souz.ui.BaseViewModel
import ru.souz.ui.common.openProviderLink
import ru.souz.ui.settings.SettingsEvent.*
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

class SettingsViewModel(
    override val di: DI,
) : BaseViewModel<SettingsState, SettingsEvent, SettingsEffect>(), DIAware {

    private val l = LoggerFactory.getLogger(SettingsViewModel::class.java)
    private val keysProvider: SettingsProvider by di.instance()
    private val chatApi: GigaChatAPI by di.instance()
    private val graphBasedAgent: GraphBasedAgent by di.instance()
    private val telegramService: TelegramService by di.instance()
    private val telegramBotController: ru.souz.service.telegram.TelegramBotController by di.instance()
    private val supportLogSender = SupportLogSender()
    private val say: Say by di.instance()

    init {
        viewModelScope.launch {
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
                keysProvider.gigaChatKey = event.key
                refreshFromProvider()
                fetchBalance()
            }
            is InputQwenChatKey -> {
                keysProvider.qwenChatKey = event.key
                refreshFromProvider()
                fetchBalance()
            }
            is InputSaluteSpeechKey -> {
                keysProvider.saluteSpeechKey = event.key
                setState { copy(saluteSpeechKey = event.key) }
            }
            is OpenProviderLink -> openProviderLink(url = event.provider.url, logger = l)
            is InputMcpServersJson -> {
                keysProvider.mcpServersJson = event.json
                setState { copy(mcpServersJson = event.json) }
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
            is InputAiTunnelKey -> {
                keysProvider.aiTunnelKey = event.key
                refreshFromProvider()
            }
            is InputAnthropicKey -> {
                keysProvider.anthropicKey = event.key
                refreshFromProvider()
            }
            is InputOpenAiKey -> {
                keysProvider.openaiKey = event.key
                refreshFromProvider()
            }
            is InputSafeModeEnabled -> {
                keysProvider.safeModeEnabled = event.enabled
                setState { copy(safeModeEnabled = event.enabled) }
            }
            is SelectModel -> {
                if (event.model !in currentState.availableLlmModels) return
                val newPrompt = graphBasedAgent.updateModel(event.model)
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
                    graphBasedAgent.updateContextSize(newContextSize)
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
                    graphBasedAgent.updateTemperature(newTemperature)
                }
                setState {
                    copy(
                        temperatureInput = normalized,
                        temperature = newTemperature ?: temperature
                    )
                }
            }
            is InputSupportEmail -> {
                keysProvider.supportEmail = event.email
                setState { copy(supportEmail = event.email, sendLogsMessage = null, sendLogsPath = null) }
            }
            is InputSystemPrompt -> {
                graphBasedAgent.updateSystemPrompt(event.prompt)
                setState { copy(systemPrompt = event.prompt) }
                send(SettingsEffect.NotifyOnSystemPrompt)
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
            RefreshFromProvider -> refreshFromProvider()
            ChooseVoice -> {
                runCatching { say.chooseVoice() }
                    .onFailure { l.warn("Failed to open voice settings", it) }
            }
            ResetSystemPrompt -> {
                graphBasedAgent.resetSystemPrompt()
                send(SettingsEffect.NotifyOnSystemPrompt)
                setState { copy(systemPrompt = DEFAULT_SYSTEM_PROMPT) }
            }
            is SendLogsToSupport -> sendLogs()
            RefreshBalance -> fetchBalance()
            GoToMain -> {
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
            DisconnectTelegramBot -> disconnectBot()
            is SelectSettingsSection -> setState { copy(activeSection = event.section) }
        }
    }

    private fun createTelegramBot() = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            setState { copy(telegramAuthBusy = true, telegramAuthError = null) }
            telegramService.createControlBot()
        }.onSuccess {
            setState { copy(telegramAuthBusy = false, isTelegramBotActive = true) }
            telegramBotController.restartPolling()
            send(SettingsEffect.ShowSnackbar(getString(Res.string.bot_created_success_message))) // need to add this resource
        }.onFailure { error ->
            val errorMsg = error.message ?: getString(Res.string.error_failed_to_create_bot)
            setState { copy(telegramAuthError = errorMsg, telegramAuthBusy = false) }
        }
    }

    private fun disconnectBot() = viewModelScope.launch(Dispatchers.IO) {
        ConfigStore.rm(ConfigStore.TG_BOT_TOKEN)
        ConfigStore.rm(ConfigStore.TG_BOT_OWNER_ID)
        telegramBotController.stopPolling()
        setState { copy(isTelegramBotActive = false) }
        send(SettingsEffect.ShowSnackbar("PC Control Bot disabled and credentials cleared"))
    }

    override suspend fun handleSideEffect(effect: SettingsEffect) = when (effect) {
        SettingsEffect.CloseScreen,
        SettingsEffect.NotifyOnSystemPrompt,
        is SettingsEffect.ShowSnackbar -> l.debug { "ignore effect: $effect" }
    }

    private suspend fun refreshFromProvider() {
        val voiceSpeed = ConfigStore.get(ToolSoundConfig.SPEED_KEY, ToolSoundConfig.DEFAULT_SPEED)
        val gigaChatKey = keysProvider.gigaChatKey.orEmpty()
        val qwenChatKey = keysProvider.qwenChatKey.orEmpty()
        val aiTunnelKey = keysProvider.aiTunnelKey.orEmpty()
        val anthropicKey = keysProvider.anthropicKey.orEmpty()
        val openAiKey = keysProvider.openaiKey.orEmpty()

        val availableLlmModels = keysProvider.availableLlmModels()
        val configuredLlmModel = keysProvider.gigaModel
        val selectedLlmModel = pickConfiguredOrDefault(
            configured = configuredLlmModel,
            available = availableLlmModels,
        ) { keysProvider.defaultLlmModel() }
        val selectedPrompt = if (selectedLlmModel != configuredLlmModel) {
            graphBasedAgent.updateModel(selectedLlmModel)
        } else {
            keysProvider.getSystemPromptForModel(selectedLlmModel) ?: DEFAULT_SYSTEM_PROMPT
        }

        val availableEmbeddingsModels = keysProvider.availableEmbeddingsModels()
        val configuredEmbeddingsModel = keysProvider.embeddingsModel
        val selectedEmbeddingsModel = pickConfiguredOrDefault(
            configured = configuredEmbeddingsModel,
            available = availableEmbeddingsModels,
        ) { keysProvider.defaultEmbeddingsModel() }
        if (selectedEmbeddingsModel != configuredEmbeddingsModel) {
            keysProvider.embeddingsModel = selectedEmbeddingsModel
            VectorDB.clearAllData()
        }

        setState {
            copy(
                gigaChatKey = gigaChatKey,
                qwenChatKey = qwenChatKey,
                aiTunnelKey = aiTunnelKey,
                anthropicKey = anthropicKey,
                openaiKey = openAiKey,
                saluteSpeechKey = keysProvider.saluteSpeechKey ?: "",
                mcpServersJson = keysProvider.mcpServersJson ?: "",
                useFewShotExamples = keysProvider.useFewShotExamples,
                useStreaming = keysProvider.useStreaming,
                safeModeEnabled = keysProvider.safeModeEnabled,
                gigaModel = selectedLlmModel,
                embeddingsModel = selectedEmbeddingsModel,
                availableLlmModels = availableLlmModels,
                availableEmbeddingsModels = availableEmbeddingsModels,
                requestTimeoutMillis = keysProvider.requestTimeoutMillis,
                requestTimeoutInput = keysProvider.requestTimeoutMillis.toString(),
                contextSize = keysProvider.contextSize,
                contextSizeInput = keysProvider.contextSize.toString(),
                temperature = keysProvider.temperature,
                temperatureInput = keysProvider.temperature.toString(),
                supportEmail = keysProvider.supportEmail ?: DEFAULT_SUPPORT_EMAIL,
                systemPrompt = selectedPrompt,
                defaultCalendar = keysProvider.defaultCalendar,
                voiceSpeed = voiceSpeed,
                voiceSpeedInput = voiceSpeed.toString(),
            )
        }
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
}
