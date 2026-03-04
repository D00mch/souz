package ru.souz.ui.setup

import ru.souz.ui.common.ApiKeyField
import ru.souz.ui.common.ApiKeyProvider
import ru.souz.ui.VMEvent
import ru.souz.ui.VMSideEffect
import ru.souz.ui.VMState

data class SetupState(
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
    val canProceed: Boolean = false,
    val shouldProceed: Boolean = false,
    val hasOpenedVoiceSelection: Boolean = false,
    val hasShownVoiceReminderOnProceed: Boolean = false,
    val showVoiceReminderDialog: Boolean = false,
) : VMState

sealed interface SetupEvent : VMEvent {
    data class InputGigaChatKey(val key: String) : SetupEvent
    data class InputQwenChatKey(val key: String) : SetupEvent
    data class InputAiTunnelKey(val key: String) : SetupEvent
    data class InputAnthropicKey(val key: String) : SetupEvent
    data class InputOpenAiKey(val key: String) : SetupEvent
    data class InputSaluteSpeechKey(val key: String) : SetupEvent
    data class OpenProviderLink(val provider: ApiKeyProvider) : SetupEvent
    object ChooseVoice : SetupEvent
    object DismissVoiceReminderDialog : SetupEvent
    object Proceed : SetupEvent
}

sealed interface SetupEffect : VMSideEffect {
    object OpenMain : SetupEffect
}
