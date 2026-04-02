package ru.souz.ui.main

import ru.souz.agent.state.AgentContext
import ru.souz.llms.DEFAULT_MAX_TOKENS
import ru.souz.ui.VMEvent
import ru.souz.ui.VMSideEffect
import ru.souz.ui.VMState
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMModel
import ru.souz.llms.local.LocalModelDownloadPrompt
import ru.souz.llms.local.LocalModelDownloadState
import ru.souz.tool.files.ToolModifyApplyStatus
import ru.souz.tool.files.ToolModifySelectionAction
import ru.souz.ui.main.search.ChatSearchState

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
    val agentActions: List<String> = emptyList(),
    val toolModifyReview: ToolModifyReviewUi? = null,
    val attachedFiles: List<ChatAttachedFile> = emptyList(),
    val finderPaths: List<FinderPathItem> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val id: String = java.util.UUID.randomUUID().toString()
)

data class ToolModifyReviewUi(
    val items: List<ToolModifyReviewItemUi>,
    val isResolved: Boolean = false,
    val summary: String? = null,
)

data class ToolModifyReviewItemUi(
    val id: Long,
    val path: String,
    val patchPreview: String,
    val selected: Boolean = true,
    val status: ToolModifyApplyStatus? = null,
    val warning: String? = null,
)

data class ToolPermissionDialogData(
    val requestId: Long,
    val description: String,
    val params: Map<String, String>,
)

data class SelectionDialogData(
    val sourceId: String,
    val requestId: Long,
    val title: String,
    val message: String,
    val confirmText: String,
    val cancelText: String,
    val candidates: List<SelectionDialogCandidateUi>,
)

data class SelectionDialogCandidateUi(
    val id: Long,
    val title: String,
    val badge: String?,
    val meta: String?,
    val preview: String?,
)

/**
 * State for the main screen that mirrors the floating glass panel experience.
 */
data class MainState(
    val displayedText: String = "",
    val isListening: Boolean = false,
    val statusMessage: String = "",
    val agentActions: List<String> = emptyList(),
    val lastText: String? = null,
    val lastKnownAgentContext: AgentContext<String>? = null,
    val userExpectCloseOnX: Boolean = false,
    val isProcessing: Boolean = false,
    val agentHistory: List<LLMRequest.Message> = emptyList(),
    val isThinkingPanelOpen: Boolean = false,
    val chatMessages: List<ChatMessage> = emptyList(),
    val chatStartTip: String = "",
    val chatSessionId: Long = 0L, // need this so local draft resets reliably when conversation resets.
    val selectedModel: String = LLMModel.Max.alias,
    val availableModelAliases: List<String> = listOf(LLMModel.Max.alias),
    val selectedContextSize: Int = DEFAULT_MAX_TOKENS,
    val isSpeaking: Boolean = false,
    val isAwaitingToolReview: Boolean = false,
    val showNewChatDialog: Boolean = false,
    val toolPermissionDialog: ToolPermissionDialogData? = null,
    val selectionDialog: SelectionDialogData? = null,
    val localModelDownloadPrompt: LocalModelDownloadPrompt? = null,
    val localModelDownloadState: LocalModelDownloadState? = null,
    val attachedFiles: List<ChatAttachedFile> = emptyList(),
    val pendingVoiceInputDraft: String? = null,
    val pendingVoiceInputDraftToken: Long = 0L,
    val chatSearch: ChatSearchState = ChatSearchState(),
) : VMState {

    companion object {
        fun randomStatusTip(): String = ""
    }
}

sealed interface MainEvent : VMEvent {
    data object StartListening : MainEvent
    data object StopListening : MainEvent
    data class ConsumePendingVoiceInputDraft(val token: Long) : MainEvent
    data object RequestNewConversation : MainEvent
    data object ConfirmNewConversation : MainEvent
    data object DismissNewConversationDialog : MainEvent
    data object ClearContext : MainEvent
    data object StopSpeech : MainEvent
    data object UserPressStop : MainEvent
    data object ShowLastText : MainEvent
    data object ToggleThinkingPanel : MainEvent
    data class UpdateChatModel(val model: String) : MainEvent
    data object ConfirmLocalModelDownload : MainEvent
    data object CancelLocalModelDownload : MainEvent
    data class UpdateChatContextSize(val size: Int) : MainEvent
    data object PickChatAttachments : MainEvent
    data class AttachDroppedFiles(val paths: List<String>) : MainEvent
    data class RemoveChatAttachment(val path: String) : MainEvent
    data class SendChatMessage(val text: String) : MainEvent
    data class OpenPath(val path: String) : MainEvent
    data class UpdateChatSearchQuery(val query: String) : MainEvent
    data object SelectNextChatSearchResult : MainEvent
    data object SelectPreviousChatSearchResult : MainEvent
    data object RefreshSettings : MainEvent
    data class ToggleToolModifyReviewSelection(val messageId: String, val itemId: Long) : MainEvent
    data class ResolveToolModifyReview(val messageId: String, val action: ToolModifySelectionAction) : MainEvent
    data object ApproveToolPermission : MainEvent
    data object RejectToolPermission : MainEvent
    data class SelectApprovalCandidate(val candidateId: Long) : MainEvent
    data object CancelSelectionDialog : MainEvent
}

sealed interface MainEffect : VMSideEffect {
    data class ShowError(val message: String) : MainEffect
    object Hide : MainEffect
}
