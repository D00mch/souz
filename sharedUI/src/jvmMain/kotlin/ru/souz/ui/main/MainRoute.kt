package ru.souz.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import org.kodein.di.compose.localDI

@Composable
fun MainScreen(
    onOpenSettings: () -> Unit,
    onOpenMemory: () -> Unit,
    onCloseWindow: () -> Unit,
    onMinimizeWindow: () -> Unit,
    onToggleMaximizeWindow: () -> Unit,
    onShowSnack: (String) -> Unit = {},
    isOnline: Boolean = true,
) {
    val di = localDI()
    val viewModel = viewModel { createMainViewModel(di) }
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                MainEffect.Hide -> onCloseWindow()
                is MainEffect.ShowError -> onShowSnack(effect.message)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.send(MainEvent.RefreshSettings)
    }

    MainScreenContent(
        state = state,
        isOnline = isOnline,
        onStartListening = { viewModel.send(MainEvent.StartListening) },
        onStopListening = { viewModel.send(MainEvent.StopListening) },
        onClose = onCloseWindow,
        onMinimize = onMinimizeWindow,
        onToggleMaximize = onToggleMaximizeWindow,
        onRequestNewConversation = { viewModel.send(MainEvent.RequestNewConversation) },
        onConfirmNewConversation = { viewModel.send(MainEvent.ConfirmNewConversation) },
        onDismissNewConversationDialog = { viewModel.send(MainEvent.DismissNewConversationDialog) },
        onOpenSettings = onOpenSettings,
        onOpenMemory = onOpenMemory,
        onStopSpeech = { viewModel.send(MainEvent.StopSpeech) },
        onShowLastText = { viewModel.send(MainEvent.ShowLastText) },
        onToggleThinkingPanel = { viewModel.send(MainEvent.ToggleThinkingPanel) },
        onShowSnack = onShowSnack,
        onChatModelChange = { viewModel.send(MainEvent.UpdateChatModel(it)) },
        onConfirmLocalModelDownload = { viewModel.send(MainEvent.ConfirmLocalModelDownload) },
        onCancelLocalModelDownload = { viewModel.send(MainEvent.CancelLocalModelDownload) },
        onChatContextSizeChange = { viewModel.send(MainEvent.UpdateChatContextSize(it)) },
        onPickChatAttachments = { viewModel.send(MainEvent.PickChatAttachments) },
        onAttachDroppedTransferable = { viewModel.onAttachDroppedPayload(it) },
        onRemoveChatAttachment = { viewModel.send(MainEvent.RemoveChatAttachment(it)) },
        onSendChatMessage = { viewModel.send(MainEvent.SendChatMessage(it)) },
        onClearContext = { viewModel.send(MainEvent.UserPressStop) },
        onConsumePendingVoiceInputDraft = { token ->
            viewModel.send(MainEvent.ConsumePendingVoiceInputDraft(token))
        },
        onToggleToolModifyReviewSelection = { messageId, itemId ->
            viewModel.send(MainEvent.ToggleToolModifyReviewSelection(messageId, itemId))
        },
        onResolveToolModifyReview = { messageId, action ->
            viewModel.send(MainEvent.ResolveToolModifyReview(messageId, action))
        },
        onApproveToolPermission = { viewModel.send(MainEvent.ApproveToolPermission) },
        onRejectToolPermission = { viewModel.send(MainEvent.RejectToolPermission) },
        onSelectApprovalCandidate = { viewModel.send(MainEvent.SelectApprovalCandidate(it)) },
        onCancelSelectionDialog = { viewModel.send(MainEvent.CancelSelectionDialog) },
        onOpenPath = { viewModel.send(MainEvent.OpenPath(it)) },
        onUpdateChatSearchQuery = { viewModel.send(MainEvent.UpdateChatSearchQuery(it)) },
        onSelectNextChatSearchResult = { viewModel.send(MainEvent.SelectNextChatSearchResult) },
        onSelectPreviousChatSearchResult = { viewModel.send(MainEvent.SelectPreviousChatSearchResult) },
        onToggleAmbientMode = { viewModel.send(MainEvent.ToggleAmbientMode) },
        onAcceptAmbientSuggestion = { viewModel.send(MainEvent.AcceptAmbientSuggestion(it)) },
        onRejectAmbientSuggestion = { viewModel.send(MainEvent.RejectAmbientSuggestion(it)) },
        onDismissAmbientSuggestion = { viewModel.send(MainEvent.DismissAmbientSuggestion(it)) },
        searchProjectionProvider = { viewModel.chatSearchProjectionFor(it) },
    )
}
