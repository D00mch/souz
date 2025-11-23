package ru.abledo.ui.settings

import ru.abledo.ui.VMEvent
import ru.abledo.ui.VMSideEffect
import ru.abledo.ui.VMState

data class SettingsState(
    val gigaChatKey: String,
    val saluteSpeechKey: String,
): VMState

sealed interface SettingsEvent : VMEvent {
    object GoToMain : SettingsEvent
    data class InputGigaChatKey(val key: String): SettingsEvent
    data class InputSaluteSpeechKey(val key: String): SettingsEvent
}

sealed interface SettingsEffect : VMSideEffect {
    object CloseScreen: SettingsEffect
}