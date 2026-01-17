package ru.gigadesk.ui.setup

import ru.gigadesk.ui.VMEvent
import ru.gigadesk.ui.VMSideEffect
import ru.gigadesk.ui.VMState

data class SetupState(
    val gigaChatKey: String = "",
    val saluteSpeechKey: String = "",
    val missingMessages: List<String> = emptyList(),
    val isInputMonitoringPermissionGranted: Boolean = false,
    val isAccessibilityPermissionGranted: Boolean = false,
    val isCheckingPermissions: Boolean = true,
    val canProceed: Boolean = false,
) : VMState

sealed interface SetupEvent : VMEvent {
    data class InputGigaChatKey(val key: String) : SetupEvent
    data class InputSaluteSpeechKey(val key: String) : SetupEvent
    data object CheckPermissions : SetupEvent
    data object OpenInputMonitoringSettings : SetupEvent
    data object OpenAccessibilitySettings : SetupEvent
    object ChooseVoice : SetupEvent
    object Proceed : SetupEvent
}

sealed interface SetupEffect : VMSideEffect {
    object OpenMain : SetupEffect
}
