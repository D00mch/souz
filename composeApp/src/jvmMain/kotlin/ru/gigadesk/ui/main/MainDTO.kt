package ru.gigadesk.ui.main

import ru.gigadesk.agent.engine.AgentContext
import ru.gigadesk.giga.DEFAULT_MAX_TOKENS
import ru.gigadesk.ui.VMEvent
import ru.gigadesk.ui.VMSideEffect
import ru.gigadesk.ui.VMState
import androidx.compose.ui.text.input.TextFieldValue
import ru.gigadesk.giga.GigaRequest

/**
 * Chat message for the chat mode.
 */
data class FinderPathItem(
    val path: String,
    val displayName: String,
    val isDirectory: Boolean,
)

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val isVoice: Boolean = false,
    val finderPaths: List<FinderPathItem> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val id: String = java.util.UUID.randomUUID().toString()
)

data class ToolPermissionDialogData(
    val requestId: Long,
    val description: String,
    val params: Map<String, String>,
)

/**
 * State for the main screen that mirrors the floating glass panel experience.
 */
data class MainState(
    val displayedText: String = "",
    val isListening: Boolean = false,
    val statusMessage: String = "",
    val lastText: String? = null,
    val lastKnownAgentContext: AgentContext<String>? = null,
    val userExpectCloseOnX: Boolean = false,
    val isProcessing: Boolean = false,
    val agentHistory: List<GigaRequest.Message> = emptyList(),
    val isThinkingPanelOpen: Boolean = false,
    val chatMessages: List<ChatMessage> = emptyList(),
    val chatStartTip: String = "",
    val chatInputText: TextFieldValue = TextFieldValue(""),
    val selectedModel: String = ru.gigadesk.giga.GigaModel.Max.alias,
    val selectedContextSize: Int = DEFAULT_MAX_TOKENS,
    val isSpeaking: Boolean = false,
    val showNewChatDialog: Boolean = false,
    val toolPermissionDialog: ToolPermissionDialogData? = null,
) : VMState {

    companion object {
        fun randomStatusTip(): String = ""
    }
}

sealed interface MainEvent : VMEvent {
    data object StartListening : MainEvent
    data object StopListening : MainEvent
    data object RequestNewConversation : MainEvent
    data object ConfirmNewConversation : MainEvent
    data object DismissNewConversationDialog : MainEvent
    data object ClearContext : MainEvent
    data object StopSpeech : MainEvent
    data object StopAgentJob : MainEvent
    data object ShowLastText : MainEvent
    data object ToggleThinkingPanel : MainEvent
    data class UpdateChatInput(val text: TextFieldValue) : MainEvent
    data class UpdateChatModel(val model: String) : MainEvent
    data class UpdateChatContextSize(val size: Int) : MainEvent
    data object SendChatMessage : MainEvent
    data class OpenPath(val path: String) : MainEvent
    data object RefreshSettings : MainEvent
    data object ApproveToolPermission : MainEvent
    data object RejectToolPermission : MainEvent
}

sealed interface MainEffect : VMSideEffect {
    data class ShowError(val message: String) : MainEffect
    object Hide : MainEffect
}
