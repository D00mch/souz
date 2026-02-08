package ru.gigadesk.ui.settings

import ru.gigadesk.agent.DEFAULT_SYSTEM_PROMPT
import ru.gigadesk.giga.EmbeddingsModel
import ru.gigadesk.giga.GigaModel
import ru.gigadesk.giga.GigaResponse
import ru.gigadesk.tool.config.ToolSoundConfig
import ru.gigadesk.ui.VMEvent
import ru.gigadesk.ui.VMSideEffect
import ru.gigadesk.ui.VMState


enum class SettingsSubScreen {
    MAIN, SESSIONS, VISUALIZATION, FOLDERS
}

data class SettingsState(
    val gigaChatKey: String = "",
    val qwenChatKey: String = "",
    val aiTunnelKey: String = "",
    val saluteSpeechKey: String = "",
    val useFewShotExamples: Boolean = false,
    val useStreaming: Boolean = false,
    val gigaModel: GigaModel = GigaModel.Max,
    val embeddingsModel: EmbeddingsModel = EmbeddingsModel.GigaEmbeddings,
    val systemPrompt: String = "",
    val requestTimeoutMillis: Long = 10_000L,
    val requestTimeoutInput: String = "10000",
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
    
    // Graph Logs
    val currentScreen: SettingsSubScreen = SettingsSubScreen.MAIN,
    val selectedSessionId: String? = null,
): VMState

sealed interface SettingsEvent : VMEvent {
    object GoToMain : SettingsEvent
    data class InputGigaChatKey(val key: String): SettingsEvent
    data class InputQwenChatKey(val key: String): SettingsEvent
    data class InputAiTunnelKey(val key: String): SettingsEvent
    data class InputSaluteSpeechKey(val key: String): SettingsEvent
    data class InputUseFewShotExamples(val enabled: Boolean): SettingsEvent
    data class InputUseStreaming(val enabled: Boolean): SettingsEvent
    data class SelectModel(val model: GigaModel): SettingsEvent
    data class SelectEmbeddingsModel(val model: EmbeddingsModel): SettingsEvent
    data class InputRequestTimeoutMillis(val millis: String) : SettingsEvent
    data class InputTemperature(val temperature: String) : SettingsEvent
    data class InputSupportEmail(val email: String): SettingsEvent
    data class InputSystemPrompt(val prompt: String): SettingsEvent
    data class InputVoiceSpeed(val speed: String): SettingsEvent
    object ChooseVoice : SettingsEvent
    object ResetSystemPrompt: SettingsEvent
    object SendLogsToSupport: SettingsEvent
    object RefreshBalance: SettingsEvent
    data class SelectDefaultCalendar(val name: String?) : SettingsEvent
    object FetchCalendars : SettingsEvent
    
    // Graph Logs
    object OpenGraphSessions : SettingsEvent
    data class OpenGraphVisualization(val sessionId: String) : SettingsEvent
    object BackToSettings : SettingsEvent
    object BackToSessions : SettingsEvent
    object OpenFoldersManagement : SettingsEvent
}

sealed interface SettingsEffect : VMSideEffect {
    object CloseScreen: SettingsEffect
    object NotifyOnSystemPrompt: SettingsEffect
}

const val DEFAULT_SUPPORT_EMAIL = "arturdumchev@yandex.ru"
