package ru.gigadesk.ui.settings

import ru.gigadesk.agent.DEFAULT_SYSTEM_PROMPT
import ru.gigadesk.giga.GigaModel
import ru.gigadesk.giga.GigaResponse
import ru.gigadesk.tool.config.ToolSoundConfig
import ru.gigadesk.ui.VMEvent
import ru.gigadesk.ui.VMSideEffect
import ru.gigadesk.ui.VMState
import ru.gigadesk.ui.setup.SetupEvent

data class SettingsState(
    val gigaChatKey: String = "",
    val saluteSpeechKey: String = "",
    val useFewShotExamples: Boolean = false,
    val gigaModel: GigaModel = GigaModel.Max,
    val supportEmail: String = DEFAULT_SUPPORT_EMAIL,
    val isSendingLogs: Boolean = false,
    val sendLogsMessage: String? = null,
    val sendLogsPath: String? = null,
    val isBalanceLoading: Boolean = false,
    val balance: List<GigaResponse.BalanceItem> = emptyList(),
    val balanceError: String? = null,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val defaultCalendar: String? = null,
    val availableCalendars: List<String> = emptyList(),
    val isLoadingCalendars: Boolean = false,
    val voiceSpeed: Int = ToolSoundConfig.DEFAULT_SPEED,
    val voiceSpeedInput: String = ToolSoundConfig.DEFAULT_SPEED.toString(),
): VMState

sealed interface SettingsEvent : VMEvent {
    object GoToMain : SettingsEvent
    data class InputGigaChatKey(val key: String): SettingsEvent
    data class InputSaluteSpeechKey(val key: String): SettingsEvent
    data class InputUseFewShotExamples(val enabled: Boolean): SettingsEvent
    data class SelectModel(val model: GigaModel): SettingsEvent
    data class InputSupportEmail(val email: String): SettingsEvent
    data class InputSystemPrompt(val prompt: String): SettingsEvent
    data class InputVoiceSpeed(val speed: String): SettingsEvent
    object ChooseVoice : SettingsEvent
    object ResetSystemPrompt: SettingsEvent
    object SendLogsToSupport: SettingsEvent
    object RefreshBalance: SettingsEvent
    data class SelectDefaultCalendar(val name: String?) : SettingsEvent
    object FetchCalendars : SettingsEvent
}

sealed interface SettingsEffect : VMSideEffect {
    object CloseScreen: SettingsEffect
    object NotifyOnSystemPrompt: SettingsEffect
}

const val DEFAULT_SUPPORT_EMAIL = "arturdumchev@yandex.ru"
