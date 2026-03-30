package ru.souz.ui.settings

import ru.souz.agent.AgentId
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.DEFAULT_MAX_TOKENS
import ru.souz.llms.GigaModel
import ru.souz.llms.GigaResponse
import ru.souz.llms.VoiceRecognitionModel
import ru.souz.tool.config.ToolSoundConfig
import ru.souz.ui.VMEvent
import ru.souz.ui.VMSideEffect
import ru.souz.ui.VMState
import ru.souz.ui.common.ApiKeyField
import ru.souz.ui.common.ApiKeyProvider
import ru.souz.llms.local.LocalModelDownloadPrompt
import ru.souz.llms.local.LocalModelDownloadState
import org.jetbrains.compose.resources.StringResource
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.*



enum class SettingsSubScreen {
    MAIN, SESSIONS, VISUALIZATION, FOLDERS, TELEGRAM
}

enum class TelegramAuthStepUi {
    INITIALIZING,
    PHONE,
    CODE,
    PASSWORD,
    CONNECTED,
    LOGGING_OUT,
    ERROR,
}



enum class SettingsSection(val title: StringResource, val icon: String? = null) {
    MODELS(Res.string.settings_section_models),
    GENERAL(Res.string.settings_section_general),
    KEYS(Res.string.settings_section_keys),
    FUNCTIONS(Res.string.settings_section_functions),
    SECURITY(Res.string.settings_section_security),
    SUPPORT(Res.string.settings_section_support)
}

data class SettingsState(
    val gigaChatKey: String = "",
    val qwenChatKey: String = "",
    val aiTunnelKey: String = "",
    val anthropicKey: String = "",
    val openaiKey: String = "",
    val saluteSpeechKey: String = "",
    val availableApiKeyFields: Set<ApiKeyField> = emptySet(),
    val availableApiKeyProviders: List<ApiKeyProvider> = emptyList(),
    val supportsVoiceRecognitionApiKeys: Boolean = false,
    val configuredKeysCount: Int = 0,
    val mcpServersJson: String = "",
    val useFewShotExamples: Boolean = false,
    val useStreaming: Boolean = false,
    val notificationSoundEnabled: Boolean = true,
    val voiceInputReviewEnabled: Boolean = false,
    val useEnglishVersion: Boolean = false,
    val safeModeEnabled: Boolean = false,
    val activeAgentId: AgentId = AgentId.default,
    val availableAgents: List<AgentId> = AgentId.entries,
    val gigaModel: GigaModel = GigaModel.Max,
    val embeddingsModel: EmbeddingsModel = EmbeddingsModel.GigaEmbeddings,
    val voiceRecognitionModel: VoiceRecognitionModel = VoiceRecognitionModel.SaluteSpeech,
    val availableLlmModels: List<GigaModel> = emptyList(),
    val availableEmbeddingsModels: List<EmbeddingsModel> = emptyList(),
    val availableVoiceRecognitionModels: List<VoiceRecognitionModel> = emptyList(),
    val systemPrompt: String = "",
    val requestTimeoutMillis: Long = 10_000L,
    val requestTimeoutInput: String = "10000",
    val contextSize: Int = DEFAULT_MAX_TOKENS,
    val contextSizeInput: String = DEFAULT_MAX_TOKENS.toString(),
    val temperature: Float = 0.7f,
    val temperatureInput: String = "0.7",
    val supportEmail: String = DEFAULT_SUPPORT_EMAIL,
    val isSendingLogs: Boolean = false,
    val sendLogsMessage: String? = null,
    val sendLogsPath: String? = null,
    val isBalanceLoading: Boolean = false,
    val balance: List<GigaResponse.BalanceItem> = emptyList(),
    val balanceError: String? = null,
    val defaultCalendar: String? = null,
    val availableCalendars: List<String> = emptyList(),
    val isLoadingCalendars: Boolean = false,
    val voiceSpeed: Int = ToolSoundConfig.DEFAULT_SPEED,
    val voiceSpeedInput: String = ToolSoundConfig.DEFAULT_SPEED.toString(),

    // Telegram auth wizard
    val telegramPhoneInput: String = "",
    val telegramCodeInput: String = "",
    val telegramPasswordInput: String = "",
    val telegramAuthStep: TelegramAuthStepUi = TelegramAuthStepUi.INITIALIZING,
    val telegramActiveSessionPhone: String? = null,
    val telegramCodeHint: String? = null,
    val telegramPasswordHint: String? = null,
    val telegramAuthBusy: Boolean = false,
    val telegramAuthError: String? = null,
    val isTelegramSupported: Boolean = true,
    val isTelegramBotActive: Boolean = false,
    val showBotDeleteConfirmation: Boolean = false,
    val botNameToDelete: String? = null,
    val showAgentSwitchConfirmation: Boolean = false,
    val pendingAgentId: AgentId? = null,
    val localModelDownloadPrompt: LocalModelDownloadPrompt? = null,
    val localModelDownloadState: LocalModelDownloadState? = null,
    
    // Graph Logs
    val currentScreen: SettingsSubScreen = SettingsSubScreen.MAIN,
    val selectedSessionId: String? = null,
    
    val activeSection: SettingsSection = SettingsSection.MODELS,
): VMState

sealed interface SettingsEvent : VMEvent {
    object GoToMain : SettingsEvent
    object RefreshFromProvider : SettingsEvent
    data class InputGigaChatKey(val key: String): SettingsEvent
    data class InputQwenChatKey(val key: String): SettingsEvent
    data class InputAiTunnelKey(val key: String): SettingsEvent
    data class InputAnthropicKey(val key: String): SettingsEvent
    data class InputOpenAiKey(val key: String): SettingsEvent
    data class InputSaluteSpeechKey(val key: String): SettingsEvent
    data class OpenProviderLink(val provider: ApiKeyProvider): SettingsEvent
    data class InputMcpServersJson(val json: String): SettingsEvent
    data class InputUseFewShotExamples(val enabled: Boolean): SettingsEvent
    data class InputUseStreaming(val enabled: Boolean): SettingsEvent
    data class InputNotificationSoundEnabled(val enabled: Boolean): SettingsEvent
    data class InputVoiceInputReviewEnabled(val enabled: Boolean): SettingsEvent
    data class InputUseEnglishVersion(val enabled: Boolean): SettingsEvent
    data class InputSafeModeEnabled(val enabled: Boolean): SettingsEvent
    data class SelectAgent(val agentId: AgentId): SettingsEvent
    object ConfirmAgentSwitch : SettingsEvent
    object CancelAgentSwitch : SettingsEvent
    data class SelectModel(val model: GigaModel): SettingsEvent
    object ConfirmLocalModelDownload : SettingsEvent
    object CancelLocalModelDownload : SettingsEvent
    data class SelectEmbeddingsModel(val model: EmbeddingsModel): SettingsEvent
    data class SelectVoiceRecognitionModel(val model: VoiceRecognitionModel): SettingsEvent
    data class InputRequestTimeoutMillis(val millis: String) : SettingsEvent
    data class InputContextSize(val size: String) : SettingsEvent
    data class InputTemperature(val temperature: String) : SettingsEvent
    data class InputSupportEmail(val email: String): SettingsEvent
    data class InputSystemPrompt(val prompt: String): SettingsEvent
    data class InputVoiceSpeed(val speed: String): SettingsEvent
    data class InputTelegramPhone(val value: String): SettingsEvent
    data class InputTelegramCode(val value: String): SettingsEvent
    data class InputTelegramPassword(val value: String): SettingsEvent
    object SubmitTelegramPhone : SettingsEvent
    object SubmitTelegramCode : SettingsEvent
    object SubmitTelegramPassword : SettingsEvent
    object TelegramLogout : SettingsEvent
    object ChooseVoice : SettingsEvent
    object ResetSystemPrompt: SettingsEvent
    object SendLogsToSupport: SettingsEvent
    object OpenPrivacyPolicy: SettingsEvent
    object RefreshBalance: SettingsEvent
    data class SelectDefaultCalendar(val name: String?) : SettingsEvent
    object FetchCalendars : SettingsEvent
    
    // Graph Logs
    object OpenGraphSessions : SettingsEvent
    data class OpenGraphVisualization(val sessionId: String) : SettingsEvent
    object BackToSettings : SettingsEvent
    object BackToSessions : SettingsEvent
    object OpenFoldersManagement : SettingsEvent
    object OpenTelegramSettings : SettingsEvent
    object CreateControlBot : SettingsEvent
    object DisconnectTelegramBot : SettingsEvent
    object ConfirmDisconnectTelegramBot : SettingsEvent
    object CancelDisconnectTelegramBot : SettingsEvent
    
    data class SelectSettingsSection(val section: SettingsSection): SettingsEvent
}

sealed interface SettingsEffect : VMSideEffect {
    object CloseScreen: SettingsEffect
    object NotifyOnSystemPrompt: SettingsEffect
    data class ShowSnackbar(val message: String): SettingsEffect
}

const val DEFAULT_SUPPORT_EMAIL = "arturdumchev@yandex.ru"
