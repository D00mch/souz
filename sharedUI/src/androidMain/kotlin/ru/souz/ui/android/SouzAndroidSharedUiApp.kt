package ru.souz.ui.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
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
import ru.souz.ui.common.RegionProfileToggle
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
import souz.sharedui.generated.resources.action_open_graph_sessions
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
import souz.sharedui.generated.resources.label_context_size
import souz.sharedui.generated.resources.label_copy
import souz.sharedui.generated.resources.label_key_aitunnel
import souz.sharedui.generated.resources.label_key_anthropic
import souz.sharedui.generated.resources.label_key_gigachat
import souz.sharedui.generated.resources.label_key_openai
import souz.sharedui.generated.resources.label_key_qwen
import souz.sharedui.generated.resources.label_key_salutespeech
import souz.sharedui.generated.resources.label_model
import souz.sharedui.generated.resources.label_system_prompt
import souz.sharedui.generated.resources.label_temperature
import souz.sharedui.generated.resources.model_picker_no_models
import souz.sharedui.generated.resources.permission_modify_file
import souz.sharedui.generated.resources.provider_codex_desc
import souz.sharedui.generated.resources.provider_codex_title
import souz.sharedui.generated.resources.setting_language_profile_desc
import souz.sharedui.generated.resources.setting_language_profile_title
import souz.sharedui.generated.resources.settings_action_clear
import souz.sharedui.generated.resources.settings_action_done
import souz.sharedui.generated.resources.settings_action_edit
import souz.sharedui.generated.resources.settings_action_hide_secret
import souz.sharedui.generated.resources.settings_action_save
import souz.sharedui.generated.resources.settings_action_show_secret
import souz.sharedui.generated.resources.settings_edit_field_title
import souz.sharedui.generated.resources.settings_section_keys
import souz.sharedui.generated.resources.settings_section_models
import souz.sharedui.generated.resources.settings_models_configure
import souz.sharedui.generated.resources.settings_models_empty
import souz.sharedui.generated.resources.settings_title
import souz.sharedui.generated.resources.settings_value_not_set

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
                    AndroidRoute.Chat -> AndroidChatRoute(
                        onOpenSettings = { route = AndroidRoute.Settings },
                        onOpenGraphSessions = { route = AndroidRoute.GraphSessions },
                    )
                    AndroidRoute.Settings -> AndroidSettingsRoute(onBack = { route = AndroidRoute.Chat })
                    AndroidRoute.GraphSessions -> AndroidGraphSessionsRoute(onBack = { route = AndroidRoute.Chat })
                }
            }
        }
    }
}

private enum class AndroidRoute {
    Chat,
    Settings,
    GraphSessions,
}

@Composable
private fun AndroidChatRoute(
    onOpenSettings: () -> Unit,
    onOpenGraphSessions: () -> Unit,
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
        onOpenGraphSessions = onOpenGraphSessions,
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
    onOpenGraphSessions: () -> Unit,
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
    val hasUsableChatModel = state.selectedModel.isNotBlank() &&
        state.selectedModel in state.availableModelAliases
    val canSend = !state.isProcessing &&
        !state.isAwaitingToolReview &&
        hasUsableChatModel &&
        input.trim().isNotEmpty()
    val currentModelLabel = if (state.availableModelAliases.isEmpty()) {
        stringResource(Res.string.model_picker_no_models)
    } else {
        state.selectedModel
    }

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
                                text = currentModelLabel,
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
                        IconButton(onClick = onOpenGraphSessions) {
                            Icon(
                                imageVector = Icons.Rounded.AccountTree,
                                contentDescription = stringResource(Res.string.action_open_graph_sessions),
                            )
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
                    onOpenSettings = onOpenSettings,
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
    val scope = rememberCoroutineScope()
    val scrollStepPx = with(LocalDensity.current) { 320.dp.toPx() }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    return@onPreviewKeyEvent false
                }
                val scrollDelta = when (event.key) {
                    Key.DirectionUp -> -scrollStepPx
                    Key.DirectionDown -> scrollStepPx
                    Key.PageUp -> -scrollStepPx * 2
                    Key.PageDown -> scrollStepPx * 2
                    else -> return@onPreviewKeyEvent false
                }
                val canScroll = if (scrollDelta < 0) {
                    listState.canScrollBackward
                } else {
                    listState.canScrollForward
                }
                if (!canScroll) {
                    return@onPreviewKeyEvent false
                }
                scope.launch { listState.animateScrollBy(scrollDelta) }
                true
            },
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
    onOpenSettings: () -> Unit,
) {
    val hasModels = availableModelAliases.isNotEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AndroidDropdownSelector(
            modifier = Modifier.weight(1f),
            label = stringResource(Res.string.label_model),
            value = if (hasModels) modelDisplayName(selectedModelAlias) else stringResource(Res.string.model_picker_no_models),
            options = availableModelAliases,
            optionLabel = ::modelDisplayName,
            onSelect = onModelChange,
        )
        if (!hasModels) {
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = stringResource(Res.string.settings_models_configure),
                )
            }
        }
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
    val hasOptions = options.isNotEmpty()

    LaunchedEffect(hasOptions) {
        if (!hasOptions) expanded = false
    }

    Box(modifier = modifier) {
        OutlinedButton(
            enabled = hasOptions,
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
            expanded = expanded && hasOptions,
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
    val submit = {
        if (canSend) {
            onSend()
        }
        Unit
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
                    canSend -> submit()
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

    val closeSettings = {
        viewModel.send(SettingsEvent.GoToMain)
        Unit
    }
    BackHandler(onBack = closeSettings)

    AndroidSettingsScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onBack = closeSettings,
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
                title = { Text(stringResource(Res.string.settings_title)) },
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
            Text(
                text = stringResource(Res.string.setting_language_profile_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(Res.string.setting_language_profile_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RegionProfileToggle(
                useEnglishProfile = state.useEnglishVersion,
                onProfileChange = { enabled -> onEvent(SettingsEvent.InputUseEnglishVersion(enabled)) },
            )

            Spacer(Modifier.height(6.dp))
            Text(stringResource(Res.string.settings_section_models), style = MaterialTheme.typography.titleMedium)
            if (state.availableLlmModels.isEmpty()) {
                Text(
                    text = stringResource(Res.string.settings_models_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.availableLlmModels.forEach { model ->
                    val selected = model == state.gigaModel
                    if (selected) {
                        Button(onClick = { onEvent(SettingsEvent.SelectModel(model)) }) { Text(model.displayName) }
                    } else {
                        TextButton(onClick = { onEvent(SettingsEvent.SelectModel(model)) }) { Text(model.displayName) }
                    }
                }
            }

            AndroidTextSettingRow(
                label = stringResource(Res.string.label_context_size),
                value = state.contextSizeInput,
                onValueChange = { onEvent(SettingsEvent.InputContextSize(it)) },
            )
            AndroidTextSettingRow(
                label = stringResource(Res.string.label_temperature),
                value = state.temperatureInput,
                onValueChange = { onEvent(SettingsEvent.InputTemperature(it)) },
            )

            Spacer(Modifier.height(8.dp))
            Text(stringResource(Res.string.settings_section_keys), style = MaterialTheme.typography.titleMedium)
            AndroidSecretSettingRow(
                label = stringResource(Res.string.label_key_gigachat),
                value = state.gigaChatKey,
                onValueChange = { onEvent(SettingsEvent.InputGigaChatKey(it)) },
            )
            AndroidSecretSettingRow(
                label = stringResource(Res.string.label_key_qwen),
                value = state.qwenChatKey,
                onValueChange = { onEvent(SettingsEvent.InputQwenChatKey(it)) },
            )
            AndroidSecretSettingRow(
                label = stringResource(Res.string.label_key_aitunnel),
                value = state.aiTunnelKey,
                onValueChange = { onEvent(SettingsEvent.InputAiTunnelKey(it)) },
            )
            AndroidSecretSettingRow(
                label = stringResource(Res.string.label_key_anthropic),
                value = state.anthropicKey,
                onValueChange = { onEvent(SettingsEvent.InputAnthropicKey(it)) },
            )
            AndroidSecretSettingRow(
                label = stringResource(Res.string.label_key_openai),
                value = state.openaiKey,
                onValueChange = { onEvent(SettingsEvent.InputOpenAiKey(it)) },
            )
            AndroidSecretSettingRow(
                label = stringResource(Res.string.label_key_salutespeech),
                value = state.saluteSpeechKey,
                onValueChange = { onEvent(SettingsEvent.InputSaluteSpeechKey(it)) },
            )
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

            AndroidTextSettingRow(
                label = stringResource(Res.string.label_system_prompt),
                value = state.systemPrompt,
                onValueChange = { onEvent(SettingsEvent.InputSystemPrompt(it)) },
                singleLine = false,
                minLines = 4,
                previewMaxLines = 2,
            )

            Button(
                onClick = { onEvent(SettingsEvent.GoToMain) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.settings_action_done))
            }
        }
    }
}

@Composable
private fun AndroidTextSettingRow(
    label: String,
    value: String,
    singleLine: Boolean = true,
    minLines: Int = 1,
    previewMaxLines: Int = 1,
    onValueChange: (String) -> Unit,
) {
    var showEditor by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(TextFieldValue()) }

    if (showEditor) {
        AlertDialog(
            onDismissRequest = { showEditor = false },
            title = { Text(stringResource(Res.string.settings_edit_field_title, label)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text(label) },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = VisualTransformation.None,
                        singleLine = singleLine,
                        minLines = minLines,
                    )
                    TextButton(
                        onClick = {
                            draft = TextFieldValue(
                                text = "",
                                selection = TextRange.Zero,
                            )
                        },
                    ) {
                        Text(stringResource(Res.string.settings_action_clear))
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onValueChange(draft.text)
                        showEditor = false
                    },
                ) {
                    Text(stringResource(Res.string.settings_action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditor = false }) {
                    Text(stringResource(Res.string.dialog_cancel))
                }
            },
        )
    }

    AndroidSettingRow(
        label = label,
        value = value,
        previewMaxLines = previewMaxLines,
        onEdit = {
            draft = TextFieldValue(
                text = value,
                selection = TextRange(0, value.length),
            )
            showEditor = true
        },
    )
}

@Composable
private fun AndroidSecretSettingRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    var showEditor by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(TextFieldValue()) }

    if (showEditor) {
        AndroidSecretSettingEditorDialog(
            label = label,
            value = draft,
            onValueChange = { updated -> draft = updated },
            onDismiss = { showEditor = false },
            onSave = {
                onValueChange(draft.text)
                showEditor = false
            },
        )
    }

    AndroidSettingRow(
        label = label,
        value = value,
        secret = true,
        onEdit = {
            draft = TextFieldValue(
                text = value,
                selection = TextRange(0, value.length),
            )
            showEditor = true
        },
    )
}

@Composable
private fun AndroidSettingRow(
    label: String,
    value: String,
    secret: Boolean = false,
    previewMaxLines: Int = 1,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onEdit,
            modifier = Modifier
                .width(210.dp)
                .heightIn(min = 52.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Edit,
                contentDescription = stringResource(Res.string.settings_action_edit),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AndroidValuePreview(
            value = value,
            secret = secret,
            maxLines = previewMaxLines,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AndroidSecretSettingEditorDialog(
    label: String,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    var reveal by remember { mutableStateOf(false) }

    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.settings_edit_field_title, label)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text(label) },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSave() }),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { reveal = !reveal }) {
                        Text(
                            if (reveal) {
                                stringResource(Res.string.settings_action_hide_secret)
                            } else {
                                stringResource(Res.string.settings_action_show_secret)
                            },
                        )
                    }
                    TextButton(
                        onClick = {
                            onValueChange(
                                TextFieldValue(
                                    text = "",
                                    selection = TextRange.Zero,
                                ),
                            )
                        },
                    ) {
                        Text(stringResource(Res.string.settings_action_clear))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onSave) {
                Text(stringResource(Res.string.settings_action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.dialog_cancel))
            }
        },
    )
}

@Composable
private fun AndroidValuePreview(
    value: String,
    secret: Boolean,
    maxLines: Int,
    modifier: Modifier = Modifier,
    revealSecret: Boolean = false,
    fixedSecretMask: Boolean = true,
    useMonospace: Boolean = false,
) {
    val displayValue = when {
        value.isBlank() -> stringResource(Res.string.settings_value_not_set)
        secret && !revealSecret -> if (fixedSecretMask) "********" else "*".repeat(value.length)
        else -> value
    }
    val shape = RoundedCornerShape(8.dp)

    Surface(
        modifier = modifier
            .heightIn(min = 52.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = shape,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = displayValue,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (useMonospace) FontFamily.Monospace else null,
                color = if (value.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
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
