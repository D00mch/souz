package ru.souz.ui.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DI
import org.kodein.di.compose.localDI
import org.kodein.di.compose.withDI
import ru.souz.llms.LLMModel
import ru.souz.tool.files.ToolModifySelectionAction
import ru.souz.ui.AppTheme
import ru.souz.ui.common.ApiKeyField
import ru.souz.ui.common.ApiKeyProvider
import ru.souz.ui.common.LocalModelDownloadProgressDialog
import ru.souz.ui.common.LocalModelDownloadPromptDialog
import ru.souz.ui.common.ToolModifyPatchPreview
import ru.souz.ui.main.MainEffect
import ru.souz.ui.main.MainEvent
import ru.souz.ui.main.MainState
import ru.souz.ui.main.SelectionDialogCandidateUi
import ru.souz.ui.main.SharedAgentActionList
import ru.souz.ui.main.SharedChatMessageCard
import ru.souz.ui.main.SharedEmptyChatWelcomeContent
import ru.souz.ui.main.ThinkingProcessPanel
import ru.souz.ui.main.createMainViewModel
import ru.souz.ui.main.search.ChatMessageSearchProjection
import ru.souz.ui.settings.CodexOAuthUiState
import ru.souz.ui.settings.SettingsEffect
import ru.souz.ui.settings.SettingsEvent
import ru.souz.ui.settings.SettingsState
import ru.souz.ui.settings.SettingsViewModel
import souz.sharedui.generated.resources.Res
import souz.sharedui.generated.resources.chat_input_placeholder
import souz.sharedui.generated.resources.chat_search_placeholder
import souz.sharedui.generated.resources.dialog_cancel
import souz.sharedui.generated.resources.dialog_new_chat_confirm
import souz.sharedui.generated.resources.dialog_new_chat_text
import souz.sharedui.generated.resources.dialog_new_chat_title
import souz.sharedui.generated.resources.dialog_permission_allow
import souz.sharedui.generated.resources.dialog_permission_deny
import souz.sharedui.generated.resources.dialog_permission_title
import souz.sharedui.generated.resources.label_context
import souz.sharedui.generated.resources.label_codex_cancel
import souz.sharedui.generated.resources.label_codex_connect
import souz.sharedui.generated.resources.label_codex_connected
import souz.sharedui.generated.resources.label_codex_disconnect
import souz.sharedui.generated.resources.label_codex_polling
import souz.sharedui.generated.resources.label_codex_user_code
import souz.sharedui.generated.resources.label_copy
import souz.sharedui.generated.resources.label_model
import souz.sharedui.generated.resources.permission_modify_file
import souz.sharedui.generated.resources.provider_codex_desc
import souz.sharedui.generated.resources.provider_codex_title

@Composable
fun SouzAndroidSharedUiApp(di: DI) {
    withDI(di) {
        AppTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                var route by remember { mutableStateOf(AndroidRoute.Chat) }
                when (route) {
                    AndroidRoute.Chat -> AndroidChatRoute(onOpenSettings = { route = AndroidRoute.Settings })
                    AndroidRoute.Settings -> AndroidSettingsRoute(onBack = { route = AndroidRoute.Chat })
                }
            }
        }
    }
}

private enum class AndroidRoute {
    Chat,
    Settings,
}

@Composable
private fun AndroidChatRoute(
    onOpenSettings: () -> Unit,
) {
    val di = localDI()
    val viewModel = viewModel { createMainViewModel(di) }
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                MainEffect.Hide -> Unit
                is MainEffect.ShowError -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.send(MainEvent.RefreshSettings)
    }

    AndroidChatScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onOpenSettings = onOpenSettings,
        onNewConversation = { viewModel.send(MainEvent.RequestNewConversation) },
        onConfirmNewConversation = { viewModel.send(MainEvent.ConfirmNewConversation) },
        onDismissNewConversation = { viewModel.send(MainEvent.DismissNewConversationDialog) },
        onSendMessage = { viewModel.send(MainEvent.SendChatMessage(it)) },
        onCancel = { viewModel.send(MainEvent.UserPressStop) },
        onModelChange = { viewModel.send(MainEvent.UpdateChatModel(it)) },
        onContextChange = { viewModel.send(MainEvent.UpdateChatContextSize(it)) },
        onOpenPath = { viewModel.send(MainEvent.OpenPath(it)) },
        onToggleThinkingPanel = { viewModel.send(MainEvent.ToggleThinkingPanel) },
        onUpdateSearchQuery = { viewModel.send(MainEvent.UpdateChatSearchQuery(it)) },
        onNextSearchResult = { viewModel.send(MainEvent.SelectNextChatSearchResult) },
        onPreviousSearchResult = { viewModel.send(MainEvent.SelectPreviousChatSearchResult) },
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
        onConfirmLocalModelDownload = { viewModel.send(MainEvent.ConfirmLocalModelDownload) },
        onCancelLocalModelDownload = { viewModel.send(MainEvent.CancelLocalModelDownload) },
        searchProjectionProvider = { viewModel.chatSearchProjectionFor(it) },
    )
}

private const val ToolModifyPatchParam = "patch"

private val AndroidContextOptions = listOf(8_000, 16_000, 32_000, 64_000, 96_000, 128_000)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AndroidChatScreen(
    state: MainState,
    snackbarHostState: SnackbarHostState,
    onOpenSettings: () -> Unit,
    onNewConversation: () -> Unit,
    onConfirmNewConversation: () -> Unit,
    onDismissNewConversation: () -> Unit,
    onSendMessage: (String) -> Unit,
    onCancel: () -> Unit,
    onModelChange: (String) -> Unit,
    onContextChange: (Int) -> Unit,
    onOpenPath: (String) -> Unit,
    onToggleThinkingPanel: () -> Unit,
    onUpdateSearchQuery: (String) -> Unit,
    onNextSearchResult: () -> Unit,
    onPreviousSearchResult: () -> Unit,
    onToggleToolModifyReviewSelection: (String, Long) -> Unit,
    onResolveToolModifyReview: (String, ToolModifySelectionAction) -> Unit,
    onApproveToolPermission: () -> Unit,
    onRejectToolPermission: () -> Unit,
    onSelectApprovalCandidate: (Long) -> Unit,
    onCancelSelectionDialog: () -> Unit,
    onConfirmLocalModelDownload: () -> Unit,
    onCancelLocalModelDownload: () -> Unit,
    searchProjectionProvider: (String) -> ChatMessageSearchProjection?,
) {
    var input by remember(state.chatSessionId) { mutableStateOf("") }
    var searchOpen by remember(state.chatSessionId) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val searchEnabled = searchOpen && state.chatSearch.normalizedQuery.isNotEmpty()
    val canSend = !state.isProcessing &&
        !state.isAwaitingToolReview &&
        input.trim().isNotEmpty()

    LaunchedEffect(state.chatMessages.size, state.isProcessing, searchEnabled) {
        if (searchEnabled) return@LaunchedEffect
        if (state.chatMessages.isNotEmpty() || state.isProcessing) {
            listState.animateScrollToItem(if (state.isProcessing) state.chatMessages.size else state.chatMessages.lastIndex)
        }
    }

    LaunchedEffect(searchOpen, state.chatSearch.activeMatch?.messageId, state.chatSearch.activeMatch?.messageIndex) {
        val activeMatch = state.chatSearch.activeMatch ?: return@LaunchedEffect
        if (searchOpen) {
            listState.animateScrollToItem(activeMatch.messageIndex)
        }
    }

    state.toolPermissionDialog?.let { dialog ->
        AndroidToolPermissionDialog(
            description = dialog.description,
            params = dialog.params,
            onConfirm = onApproveToolPermission,
            onDismiss = onRejectToolPermission,
        )
    }

    if (state.showNewChatDialog) {
        AlertDialog(
            onDismissRequest = onDismissNewConversation,
            title = { Text(stringResource(Res.string.dialog_new_chat_title)) },
            text = { Text(stringResource(Res.string.dialog_new_chat_text)) },
            confirmButton = {
                Button(onClick = onConfirmNewConversation) {
                    Text(stringResource(Res.string.dialog_new_chat_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissNewConversation) {
                    Text(stringResource(Res.string.dialog_cancel))
                }
            },
        )
    }

    state.selectionDialog?.let { dialog ->
        AndroidSelectionDialog(
            requestId = dialog.requestId,
            title = dialog.title,
            message = dialog.message,
            confirmText = dialog.confirmText,
            cancelText = dialog.cancelText,
            candidates = dialog.candidates,
            onConfirmSelection = onSelectApprovalCandidate,
            onDismiss = onCancelSelectionDialog,
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Souz")
                            Text(
                                text = state.selectedModel.ifBlank { "No model selected" },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { searchOpen = !searchOpen }) {
                            Icon(Icons.Rounded.Search, contentDescription = null)
                        }
                        IconButton(onClick = onToggleThinkingPanel) {
                            Icon(Icons.Rounded.Psychology, contentDescription = null)
                        }
                        IconButton(onClick = onNewConversation) {
                            Icon(Icons.Rounded.Add, contentDescription = null)
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Rounded.Settings, contentDescription = null)
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding(),
            ) {
                if (searchOpen) {
                    AndroidChatSearchRow(
                        query = state.chatSearch.query,
                        resultText = androidSearchResultText(state),
                        hasResults = state.chatSearch.matches.isNotEmpty(),
                        onQueryChange = onUpdateSearchQuery,
                        onPrevious = onPreviousSearchResult,
                        onNext = onNextSearchResult,
                        onClose = {
                            searchOpen = false
                            onUpdateSearchQuery("")
                        },
                    )
                }

                AndroidChatMessages(
                    state = state,
                    listState = listState,
                    searchEnabled = searchEnabled,
                    searchProjectionProvider = searchProjectionProvider,
                    onOpenPath = onOpenPath,
                    onToggleToolModifyReviewSelection = onToggleToolModifyReviewSelection,
                    onResolveToolModifyReview = onResolveToolModifyReview,
                    onSendSuggestion = { suggestion ->
                        input = ""
                        onSendMessage(suggestion)
                    },
                    modifier = Modifier.weight(1f),
                )

                AndroidModelAndContextSelector(
                    selectedModelAlias = state.selectedModel,
                    availableModelAliases = state.availableModelAliases,
                    selectedContextSize = state.selectedContextSize,
                    onModelChange = onModelChange,
                    onContextChange = onContextChange,
                )

                AndroidMessageInput(
                    value = input,
                    onValueChange = { input = it },
                    enabled = !state.isProcessing && !state.isAwaitingToolReview,
                    isProcessing = state.isProcessing,
                    canSend = canSend,
                    onCancel = onCancel,
                    onSend = {
                        val text = input.trim()
                        input = ""
                        onSendMessage(text)
                    },
                )
            }
        }

        if (state.isThinkingPanelOpen) {
            AndroidThinkingPanelOverlay(
                state = state,
                onClose = onToggleThinkingPanel,
            )
        }

        state.localModelDownloadPrompt?.let { prompt ->
            LocalModelDownloadPromptDialog(
                prompt = prompt,
                onConfirm = onConfirmLocalModelDownload,
                onDismiss = onCancelLocalModelDownload,
            )
        }

        state.localModelDownloadState?.let { downloadState ->
            LocalModelDownloadProgressDialog(
                state = downloadState,
                onCancel = onCancelLocalModelDownload,
            )
        }
    }
}

@Composable
private fun AndroidChatMessages(
    state: MainState,
    listState: LazyListState,
    searchEnabled: Boolean,
    searchProjectionProvider: (String) -> ChatMessageSearchProjection?,
    onOpenPath: (String) -> Unit,
    onToggleToolModifyReviewSelection: (String, Long) -> Unit,
    onResolveToolModifyReview: (String, ToolModifySelectionAction) -> Unit,
    onSendSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.chatMessages.isEmpty() && !state.isProcessing) {
            item {
                SharedEmptyChatWelcomeContent(
                    onSuggestionClick = onSendSuggestion,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        items(state.chatMessages, key = { it.id }) { message ->
            SharedChatMessageCard(
                message = message,
                searchState = state.chatSearch,
                searchEnabled = searchEnabled,
                searchProjection = searchProjectionProvider(message.id),
                onOpenPath = onOpenPath,
                onToggleToolModifyReviewSelection = onToggleToolModifyReviewSelection,
                onResolveToolModifyReview = onResolveToolModifyReview,
            )
        }

        if (state.isProcessing) {
            item {
                if (state.agentActions.isEmpty()) {
                    Text(
                        text = "Thinking...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                } else {
                    SharedAgentActionList(
                        actions = state.agentActions,
                        inProgress = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun AndroidChatSearchRow(
    query: String,
    resultText: String,
    hasResults: Boolean,
    onQueryChange: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            label = { Text(stringResource(Res.string.chat_search_placeholder)) },
            trailingIcon = {
                Text(
                    text = resultText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        IconButton(enabled = hasResults, onClick = onPrevious) {
            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = null)
        }
        IconButton(enabled = hasResults, onClick = onNext) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Rounded.Close, contentDescription = null)
        }
    }
}

private fun androidSearchResultText(state: MainState): String =
    if (state.chatSearch.normalizedQuery.isEmpty()) {
        ""
    } else if (state.chatSearch.matches.isEmpty()) {
        "0/0"
    } else {
        "${state.chatSearch.currentIndex + 1}/${state.chatSearch.matches.size}"
    }

@Composable
private fun AndroidModelAndContextSelector(
    selectedModelAlias: String,
    availableModelAliases: List<String>,
    selectedContextSize: Int,
    onModelChange: (String) -> Unit,
    onContextChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AndroidDropdownSelector(
            modifier = Modifier.weight(1f),
            label = stringResource(Res.string.label_model),
            value = modelDisplayName(selectedModelAlias),
            options = availableModelAliases,
            optionLabel = ::modelDisplayName,
            onSelect = onModelChange,
        )
        AndroidDropdownSelector(
            modifier = Modifier.width(132.dp),
            label = stringResource(Res.string.label_context),
            value = formatWithSpaces(selectedContextSize),
            options = AndroidContextOptions,
            optionLabel = ::formatWithSpaces,
            onSelect = onContextChange,
        )
    }
}

@Composable
private fun <T> AndroidDropdownSelector(
    label: String,
    value: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun AndroidMessageInput(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    isProcessing: Boolean,
    canSend: Boolean,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(Res.string.chat_input_placeholder)) },
                minLines = 1,
                maxLines = 8,
            )
            IconButton(
                enabled = isProcessing || canSend,
                onClick = {
                    when {
                        isProcessing -> onCancel()
                        canSend -> onSend()
                    }
                },
            ) {
                Icon(
                    imageVector = if (isProcessing) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                    contentDescription = null,
                )
            }
        }
    }
}

@Composable
private fun AndroidToolPermissionDialog(
    description: String,
    params: Map<String, String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val permissionModifyFile = stringResource(Res.string.permission_modify_file)
    val patchText = params[ToolModifyPatchParam]?.takeIf { it.isNotBlank() }
    val visibleParams = params.filterKeys { it != ToolModifyPatchParam }
    val paramsString = visibleParams.entries.joinToString("\n") { "${it.key}: ${it.value}" }
    val isToolModifyPermission = description == permissionModifyFile && patchText != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.dialog_permission_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(description)
                if (paramsString.isNotBlank()) {
                    Text(
                        text = paramsString,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (isToolModifyPermission) {
                    ToolModifyPatchPreview(patch = patchText.orEmpty(), maxHeight = 360.dp)
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(Res.string.dialog_permission_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.dialog_permission_deny))
            }
        },
    )
}

@Composable
private fun AndroidSelectionDialog(
    requestId: Long,
    title: String,
    message: String,
    confirmText: String,
    cancelText: String,
    candidates: List<SelectionDialogCandidateUi>,
    onConfirmSelection: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedId by remember(requestId) { mutableLongStateOf(-1L) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(message)
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    candidates.forEach { candidate ->
                        AndroidSelectionCandidateRow(
                            candidate = candidate,
                            selected = candidate.id == selectedId,
                            onClick = { selectedId = candidate.id },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = selectedId >= 0,
                onClick = { onConfirmSelection(selectedId) },
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(cancelText)
            }
        },
    )
}

@Composable
private fun AndroidSelectionCandidateRow(
    candidate: SelectionDialogCandidateUi,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = candidate.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                candidate.badge?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            candidate.meta?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            candidate.preview?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AndroidThinkingPanelOverlay(
    state: MainState,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.CenterEnd,
    ) {
        ThinkingProcessPanel(
            history = state.agentHistory,
            isProcessing = state.isProcessing,
            onClose = onClose,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun modelDisplayName(alias: String): String =
    LLMModel.entries.firstOrNull { it.alias == alias }?.displayName ?: alias

private fun formatWithSpaces(value: Int): String = value
    .toString()
    .reversed()
    .chunked(3)
    .joinToString(" ")
    .reversed()

@Composable
private fun AndroidSettingsRoute(
    onBack: () -> Unit,
) {
    val di = localDI()
    val viewModel = viewModel { SettingsViewModel(di) }
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                SettingsEffect.CloseScreen -> onBack()
                SettingsEffect.NotifyOnSystemPrompt -> Unit
                is SettingsEffect.ShowSnackbar -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.send(SettingsEvent.RefreshFromProvider)
    }

    AndroidSettingsScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onEvent = viewModel::send,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AndroidSettingsScreen(
    state: SettingsState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onEvent: (SettingsEvent) -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Models", style = MaterialTheme.typography.titleMedium)
            state.availableLlmModels.forEach { model ->
                val selected = model == state.gigaModel
                if (selected) {
                    Button(onClick = { onEvent(SettingsEvent.SelectModel(model)) }) { Text(model.displayName) }
                } else {
                    TextButton(onClick = { onEvent(SettingsEvent.SelectModel(model)) }) { Text(model.displayName) }
                }
            }

            OutlinedTextField(
                value = state.contextSizeInput,
                onValueChange = { onEvent(SettingsEvent.InputContextSize(it)) },
                label = { Text("Context size") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.temperatureInput,
                onValueChange = { onEvent(SettingsEvent.InputTemperature(it)) },
                label = { Text("Temperature") },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            Text("API keys", style = MaterialTheme.typography.titleMedium)
            AndroidKeyField("GigaChat", state.gigaChatKey) { onEvent(SettingsEvent.InputGigaChatKey(it)) }
            AndroidKeyField("Qwen", state.qwenChatKey) { onEvent(SettingsEvent.InputQwenChatKey(it)) }
            AndroidKeyField("AI Tunnel", state.aiTunnelKey) { onEvent(SettingsEvent.InputAiTunnelKey(it)) }
            AndroidKeyField("Anthropic", state.anthropicKey) { onEvent(SettingsEvent.InputAnthropicKey(it)) }
            AndroidKeyField("OpenAI", state.openaiKey) { onEvent(SettingsEvent.InputOpenAiKey(it)) }
            AndroidKeyField("SaluteSpeech", state.saluteSpeechKey) { onEvent(SettingsEvent.InputSaluteSpeechKey(it)) }
            if (ApiKeyField.CODEX in state.availableApiKeyFields) {
                AndroidCodexAuthCard(
                    connected = state.codexConnected,
                    oauthState = state.codexOAuthState,
                    onConnect = { onEvent(SettingsEvent.StartCodexOAuth) },
                    onCancel = { onEvent(SettingsEvent.CancelCodexOAuth) },
                    onDisconnect = { onEvent(SettingsEvent.DisconnectCodex) },
                    onOpenAuthUrl = { onEvent(SettingsEvent.OpenProviderLink(ApiKeyProvider.CODEX)) },
                )
            }

            OutlinedTextField(
                value = state.systemPrompt,
                onValueChange = { onEvent(SettingsEvent.InputSystemPrompt(it)) },
                label = { Text("System prompt") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
            )

            Button(
                onClick = { onEvent(SettingsEvent.GoToMain) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Done")
            }
        }
    }
}

@Composable
private fun AndroidKeyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
    )
}

@Composable
private fun AndroidCodexAuthCard(
    connected: Boolean,
    oauthState: CodexOAuthUiState,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenAuthUrl: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(Res.string.provider_codex_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(Res.string.provider_codex_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (oauthState) {
                is CodexOAuthUiState.AwaitingUserCode -> {
                    Text(
                        text = stringResource(Res.string.label_codex_user_code),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = oauthState.userCode,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        OutlinedButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(oauthState.userCode))
                            },
                        ) {
                            Text(stringResource(Res.string.label_copy))
                        }
                    }
                    Text(
                        text = ApiKeyProvider.CODEX.url,
                        modifier = Modifier.clickable(onClick = onOpenAuthUrl),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = stringResource(Res.string.label_codex_polling),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onCancel,
                    ) {
                        Text(stringResource(Res.string.label_codex_cancel))
                    }
                }

                CodexOAuthUiState.Polling -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(stringResource(Res.string.label_codex_polling))
                    }
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onCancel,
                    ) {
                        Text(stringResource(Res.string.label_codex_cancel))
                    }
                }

                is CodexOAuthUiState.Error -> {
                    Text(
                        text = oauthState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onConnect,
                    ) {
                        Text(stringResource(Res.string.label_codex_connect))
                    }
                }

                CodexOAuthUiState.Done,
                CodexOAuthUiState.Idle -> {
                    if (connected) {
                        Text(
                            text = stringResource(Res.string.label_codex_connected),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onDisconnect,
                        ) {
                            Text(stringResource(Res.string.label_codex_disconnect))
                        }
                    } else {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onConnect,
                        ) {
                            Text(stringResource(Res.string.label_codex_connect))
                        }
                    }
                }
            }
        }
    }
}
