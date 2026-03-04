package ru.souz.ui.main

import ru.souz.agent.engine.AgentContext
import ru.souz.giga.DEFAULT_MAX_TOKENS
import ru.souz.ui.VMEvent
import ru.souz.ui.VMSideEffect
import ru.souz.ui.VMState
import ru.souz.giga.GigaRequest
import ru.souz.giga.LlmBuildProfile

/**
 * Chat message for the chat mode.
 */
data class FinderPathItem(
    val path: String,
    val displayName: String,
    val isDirectory: Boolean,
)

enum class ChatAttachmentType {
    DOCUMENT,
    IMAGE,
    PDF,
    SPREADSHEET,
    VIDEO,
    AUDIO,
    ARCHIVE,
    OTHER,
}

data class ChatAttachedFile(
    val path: String,
    val displayName: String,
    val sizeBytes: Long,
    val type: ChatAttachmentType,
    val thumbnailBytes: ByteArray? = null,
)

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val isVoice: Boolean = false,
    val attachedFiles: List<ChatAttachedFile> = emptyList(),
    val finderPaths: List<FinderPathItem> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val id: String = java.util.UUID.randomUUID().toString()
)

data class ToolPermissionDialogData(
    val requestId: Long,
    val description: String,
    val params: Map<String, String>,
)

data class TelegramContactSelectionDialogData(
    val requestId: Long,
    val query: String,
    val candidates: List<TelegramContactCandidateUi>,
)

data class TelegramChatSelectionDialogData(
    val requestId: Long,
    val query: String,
    val candidates: List<TelegramChatCandidateUi>,
)

data class TelegramContactCandidateUi(
    val userId: Long,
    val displayName: String,
    val username: String?,
    val phoneMasked: String?,
    val isContact: Boolean,
    val lastMessageText: String?,
)

data class TelegramChatCandidateUi(
    val chatId: Long,
    val title: String,
    val unreadCount: Int,
    val isPrivateChat: Boolean,
    val lastMessageText: String?,
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
    val chatSessionId: Long = 0L, // need this so local draft resets reliably when conversation resets.
    val selectedModel: String = LlmBuildProfile.defaultModel.alias,
    val availableModelAliases: List<String> = LlmBuildProfile.availableModels.map { it.alias },
    val selectedContextSize: Int = DEFAULT_MAX_TOKENS,
    val isSpeaking: Boolean = false,
    val showNewChatDialog: Boolean = false,
    val toolPermissionDialog: ToolPermissionDialogData? = null,
    val telegramContactSelectionDialog: TelegramContactSelectionDialogData? = null,
    val telegramChatSelectionDialog: TelegramChatSelectionDialogData? = null,
    val attachedFiles: List<ChatAttachedFile> = emptyList(),
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
    data object UserPressStop : MainEvent
    data object ShowLastText : MainEvent
    data object ToggleThinkingPanel : MainEvent
    data class UpdateChatModel(val model: String) : MainEvent
    data class UpdateChatContextSize(val size: Int) : MainEvent
    data object PickChatAttachments : MainEvent
    data class AttachDroppedFiles(val paths: List<String>) : MainEvent
    data class RemoveChatAttachment(val path: String) : MainEvent
    data class SendChatMessage(val text: String) : MainEvent
    data class OpenPath(val path: String) : MainEvent
    data object RefreshSettings : MainEvent
    data object ApproveToolPermission : MainEvent
    data object RejectToolPermission : MainEvent
    data class SelectTelegramContact(val userId: Long) : MainEvent
    data object CancelTelegramContactSelection : MainEvent
    data class SelectTelegramChat(val chatId: Long) : MainEvent
    data object CancelTelegramChatSelection : MainEvent
}

sealed interface MainEffect : VMSideEffect {
    data class ShowError(val message: String) : MainEffect
    object Hide : MainEffect
}
