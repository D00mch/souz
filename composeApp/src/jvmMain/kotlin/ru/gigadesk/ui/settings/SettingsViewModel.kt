package ru.gigadesk.ui.settings

import androidx.lifecycle.viewModelScope
import io.ktor.util.logging.debug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.gigadesk.agent.DEFAULT_SYSTEM_PROMPT
import ru.gigadesk.agent.GraphBasedAgent
import ru.gigadesk.audio.Say
import ru.gigadesk.db.ConfigStore
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.giga.GigaChatAPI
import ru.gigadesk.giga.GigaResponse
import ru.gigadesk.giga.LlmProvider
import ru.gigadesk.service.telegram.TelegramAuthStep
import ru.gigadesk.service.telegram.TelegramService
import ru.gigadesk.tool.config.ToolSoundConfig
import ru.gigadesk.tool.ToolRunBashCommand
import ru.gigadesk.tool.calendar.CalendarAppleScriptCommands
import ru.gigadesk.ui.BaseViewModel
import ru.gigadesk.ui.common.openProviderLink
import ru.gigadesk.ui.settings.SettingsEvent.*

class SettingsViewModel(
    override val di: DI,
) : BaseViewModel<SettingsState, SettingsEvent, SettingsEffect>(), DIAware {

    private val l = LoggerFactory.getLogger(SettingsViewModel::class.java)
    private val keysProvider: SettingsProvider by di.instance()
    private val chatApi: GigaChatAPI by di.instance()
    private val graphBasedAgent: GraphBasedAgent by di.instance()
    private val telegramService: TelegramService by di.instance()
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

    override fun initialState(): SettingsState = SettingsState()

    override suspend fun handleEvent(event: SettingsEvent) {
        l.debug { "handleEvent: $event" }
        when(event) {
            is InputGigaChatKey -> {
                keysProvider.gigaChatKey = event.key
                setState { copy(gigaChatKey = event.key) }
                fetchBalance()
            }
            is InputQwenChatKey -> {
                keysProvider.qwenChatKey = event.key
                setState { copy(qwenChatKey = event.key) }
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
                setState { copy(aiTunnelKey = event.key) }
            }
            is InputSafeModeEnabled -> {
                keysProvider.safeModeEnabled = event.enabled
                setState { copy(safeModeEnabled = event.enabled) }
            }
            is SelectModel -> {
                val newPrompt = graphBasedAgent.updateModel(event.model)
                setState { copy(gigaModel = event.model, systemPrompt = newPrompt) }
                fetchBalance()
            }
            is SelectEmbeddingsModel -> {
                keysProvider.embeddingsModel = event.model
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

            is SelectSettingsSection -> setState { copy(activeSection = event.section) }
        }
    }

    override suspend fun handleSideEffect(effect: SettingsEffect) = when (effect) {
        SettingsEffect.CloseScreen,
        SettingsEffect.NotifyOnSystemPrompt -> l.debug { "ignore effect: $effect" }
    }

    private suspend fun refreshFromProvider() {
        val voiceSpeed = ConfigStore.get(ToolSoundConfig.SPEED_KEY, ToolSoundConfig.DEFAULT_SPEED)
        val currentModel = keysProvider.gigaModel
        val currentPrompt = keysProvider.getSystemPromptForModel(currentModel) ?: DEFAULT_SYSTEM_PROMPT
        setState {
            copy(
                gigaChatKey = keysProvider.gigaChatKey ?: "",
                qwenChatKey = keysProvider.qwenChatKey ?: "",
                aiTunnelKey = keysProvider.aiTunnelKey ?: "",
                saluteSpeechKey = keysProvider.saluteSpeechKey ?: "",
                mcpServersJson = keysProvider.mcpServersJson ?: "",
                useFewShotExamples = keysProvider.useFewShotExamples,
                useStreaming = keysProvider.useStreaming,
                safeModeEnabled = keysProvider.safeModeEnabled,
                gigaModel = currentModel,
                embeddingsModel = keysProvider.embeddingsModel,
                requestTimeoutMillis = keysProvider.requestTimeoutMillis,
                requestTimeoutInput = keysProvider.requestTimeoutMillis.toString(),
                contextSize = keysProvider.contextSize,
                contextSizeInput = keysProvider.contextSize.toString(),
                temperature = keysProvider.temperature,
                temperatureInput = keysProvider.temperature.toString(),
                supportEmail = keysProvider.supportEmail ?: DEFAULT_SUPPORT_EMAIL,
                systemPrompt = currentPrompt,
                defaultCalendar = keysProvider.defaultCalendar,
                voiceSpeed = voiceSpeed,
                voiceSpeedInput = voiceSpeed.toString(),
            )
        }
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
                setState {
                    copy(
                        isSendingLogs = false,
                        sendLogsMessage = error.message ?: "Не удалось отправить логи",
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
                    setState {
                        copy(
                            balance = emptyList(),
                            balanceError = "Укажите GigaChat ключ",
                            isBalanceLoading = false
                        )
                    }
                    return@launch
                }
            }
            LlmProvider.QWEN -> {
                setState {
                    copy(
                        balance = emptyList(),
                        balanceError = "Баланс для Qwen недоступен",
                        isBalanceLoading = false
                    )
                }
                return@launch
            }
            LlmProvider.AI_TUNNEL -> {
                setState {
                    copy(
                        balance = emptyList(),
                        balanceError = "Баланс для AI Tunnel недоступен",
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
            setState { copy(telegramAuthError = "Введите номер телефона") }
            return@launch
        }

        runCatching { telegramService.submitPhoneNumber(phone) }
            .onFailure { error ->
                setState { copy(telegramAuthError = error.message ?: "Не удалось запросить код Telegram") }
            }
    }

    private fun submitTelegramCode() = viewModelScope.launch(Dispatchers.IO) {
        val code = currentState.telegramCodeInput.trim()
        if (code.isBlank()) {
            setState { copy(telegramAuthError = "Введите код входа") }
            return@launch
        }

        runCatching { telegramService.submitLoginCode(code) }
            .onFailure { error ->
                setState { copy(telegramAuthError = error.message ?: "Не удалось подтвердить код") }
            }
    }

    private fun submitTelegramPassword() = viewModelScope.launch(Dispatchers.IO) {
        val password = currentState.telegramPasswordInput
        if (password.isBlank()) {
            setState { copy(telegramAuthError = "Введите пароль 2FA") }
            return@launch
        }

        runCatching { telegramService.submitTwoFaPassword(password) }
            .onFailure { error ->
                setState { copy(telegramAuthError = error.message ?: "Не удалось подтвердить пароль 2FA") }
            }
    }

    private fun telegramLogout() = viewModelScope.launch(Dispatchers.IO) {
        runCatching { telegramService.logout() }
            .onFailure { error ->
                setState { copy(telegramAuthError = error.message ?: "Не удалось завершить Telegram-сессию") }
            }
    }
}
