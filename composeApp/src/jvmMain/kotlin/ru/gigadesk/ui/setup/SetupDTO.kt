package ru.gigadesk.ui.setup

import ru.gigadesk.ui.common.ApiKeyProvider
import ru.gigadesk.ui.VMEvent
import ru.gigadesk.ui.VMSideEffect
import ru.gigadesk.ui.VMState

data class SetupState(
    val gigaChatKey: String = "",
    val qwenChatKey: String = "",
    val aiTunnelKey: String = "",
    val saluteSpeechKey: String = "",
    val configuredKeysCount: Int = 0,
    val canProceed: Boolean = false,
    val shouldProceed: Boolean = false,
) : VMState

sealed interface SetupEvent : VMEvent {
    data class InputGigaChatKey(val key: String) : SetupEvent
    data class InputQwenChatKey(val key: String) : SetupEvent
    data class InputAiTunnelKey(val key: String) : SetupEvent
    data class InputSaluteSpeechKey(val key: String) : SetupEvent
    data class OpenProviderLink(val provider: ApiKeyProvider) : SetupEvent
    object ChooseVoice : SetupEvent
    object Proceed : SetupEvent
}

sealed interface SetupEffect : VMSideEffect {
    object OpenMain : SetupEffect
}
