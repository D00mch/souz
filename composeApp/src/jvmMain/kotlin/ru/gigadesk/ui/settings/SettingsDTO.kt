package ru.gigadesk.ui.settings

import ru.gigadesk.agent.DEFAULT_SYSTEM_PROMPT
import ru.gigadesk.ui.VMEvent
import ru.gigadesk.ui.VMSideEffect
import ru.gigadesk.ui.VMState
import ru.gigadesk.giga.GigaResponse

data class SettingsState(
    val gigaChatKey: String = "",
    val saluteSpeechKey: String = "",
    val useFewShotExamples: Boolean = false,
    val supportEmail: String = DEFAULT_SUPPORT_EMAIL,
    val isSendingLogs: Boolean = false,
    val sendLogsMessage: String? = null,
    val isBalanceLoading: Boolean = false,
    val balance: List<GigaResponse.BalanceItem> = emptyList(),
    val balanceError: String? = null,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val defaultCalendar: String? = null,
    val availableCalendars: List<String> = emptyList(),
    val isLoadingCalendars: Boolean = false
): VMState

sealed interface SettingsEvent : VMEvent {
    object GoToMain : SettingsEvent
    data class InputGigaChatKey(val key: String): SettingsEvent
    data class InputSaluteSpeechKey(val key: String): SettingsEvent
    data class InputUseFewShotExamples(val enabled: Boolean): SettingsEvent
    data class InputSupportEmail(val email: String): SettingsEvent
    data class InputSystemPrompt(val prompt: String): SettingsEvent
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