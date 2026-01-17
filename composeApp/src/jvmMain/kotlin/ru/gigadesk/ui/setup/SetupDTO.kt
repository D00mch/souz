package ru.gigadesk.ui.setup

import ru.gigadesk.ui.VMEvent
import ru.gigadesk.ui.VMSideEffect
import ru.gigadesk.ui.VMState

data class SetupState(
    val gigaChatKey: String = "",
    val saluteSpeechKey: String = "",
    val missingMessages: List<String> = emptyList(),
    val canProceed: Boolean = false,
    val shouldProceed: Boolean = false,
) : VMState

sealed interface SetupEvent : VMEvent {
    data class InputGigaChatKey(val key: String) : SetupEvent
    data class InputSaluteSpeechKey(val key: String) : SetupEvent
    object ChooseVoice : SetupEvent
    object Proceed : SetupEvent
}

sealed interface SetupEffect : VMSideEffect {
    object OpenMain : SetupEffect
}
