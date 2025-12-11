package ru.abledo.ui.settings

import ru.abledo.ui.VMEvent
import ru.abledo.ui.VMSideEffect
import ru.abledo.ui.VMState

data class SettingsState(
    val gigaChatKey: String = "",
    val saluteSpeechKey: String = "",
    val useFewShotExamples: Boolean = false,
    val supportEmail: String = DEFAULT_SUPPORT_EMAIL,
    val isSendingLogs: Boolean = false,
    val sendLogsMessage: String? = null,
): VMState

sealed interface SettingsEvent : VMEvent {
    object GoToMain : SettingsEvent
    data class InputGigaChatKey(val key: String): SettingsEvent
    data class InputSaluteSpeechKey(val key: String): SettingsEvent
    data class InputUseFewShotExamples(val enabled: Boolean): SettingsEvent
    data class InputSupportEmail(val email: String): SettingsEvent
    object SendLogsToSupport: SettingsEvent
}

sealed interface SettingsEffect : VMSideEffect {
    object CloseScreen: SettingsEffect
}

const val DEFAULT_SUPPORT_EMAIL = "arturdumchev@yandex.ru"