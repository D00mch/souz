package ru.souz.ui.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DI
import org.kodein.di.compose.localDI
import org.kodein.di.compose.withDI
import ru.souz.llms.LLMModel
import ru.souz.tool.files.ToolModifyApplyStatus
import ru.souz.tool.files.ToolModifySelectionAction
import ru.souz.ui.AppTheme
import ru.souz.ui.common.ApiKeyField
import ru.souz.ui.common.ApiKeyProvider
import ru.souz.ui.main.ChatMessage
import ru.souz.ui.main.MainEffect
import ru.souz.ui.main.MainEvent
import ru.souz.ui.main.MainState
import ru.souz.ui.main.ToolModifyReviewItemUi
import ru.souz.ui.main.ToolModifyReviewUi
import ru.souz.ui.main.createMainViewModel
import ru.souz.ui.settings.CodexOAuthUiState
import ru.souz.ui.settings.SettingsEffect
import ru.souz.ui.settings.SettingsEvent
import ru.souz.ui.settings.SettingsState
import ru.souz.ui.settings.SettingsViewModel
import souz.sharedui.generated.resources.Res
import souz.sharedui.generated.resources.label_codex_cancel
import souz.sharedui.generated.resources.label_codex_connect
import souz.sharedui.generated.resources.label_codex_connected
import souz.sharedui.generated.resources.label_codex_disconnect
import souz.sharedui.generated.resources.label_codex_polling
import souz.sharedui.generated.resources.label_codex_user_code
import souz.sharedui.generated.resources.label_copy
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
    val snackbarScope = rememberCoroutineScope()

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
        onModelChange = { viewModel.send(MainEvent.UpdateChatModel(it.alias)) },
        onToggleToolModifyReviewSelection = { messageId, itemId ->
            viewModel.send(MainEvent.ToggleToolModifyReviewSelection(messageId, itemId))
        },
        onResolveToolModifyReview = { messageId, action ->
            viewModel.send(MainEvent.ResolveToolModifyReview(messageId, action))
        },
        onApproveToolPermission = { viewModel.send(MainEvent.ApproveToolPermission) },
        onRejectToolPermission = { viewModel.send(MainEvent.RejectToolPermission) },
        onShowMessage = { snackbarScope.launch { snackbarHostState.showSnackbar(it) } },
    )
}

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
    onModelChange: (LLMModel) -> Unit,
    onToggleToolModifyReviewSelection: (String, Long) -> Unit,
    onResolveToolModifyReview: (String, ToolModifySelectionAction) -> Unit,
    onApproveToolPermission: () -> Unit,
    onRejectToolPermission: () -> Unit,
    onShowMessage: (String) -> Unit,
) {
    var input by remember(state.chatSessionId) { mutableStateOf("") }
    val canSend = !state.isProcessing && !state.isAwaitingToolReview && input.trim().isNotEmpty()

    state.toolPermissionDialog?.let { dialog ->
        val paramsText = dialog.params.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        AlertDialog(
            onDismissRequest = onRejectToolPermission,
            title = { Text("Tool permission") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(dialog.description)
                    if (paramsText.isNotBlank()) {
                        Text(
                            text = paramsText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = onApproveToolPermission) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = onRejectToolPermission) { Text("Deny") }
            },
        )
    }

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
            if (state.showNewChatDialog) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Start a new chat?", fontWeight = FontWeight.SemiBold)
                        Text("This clears the current conversation context.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onConfirmNewConversation) { Text("New chat") }
                            TextButton(onClick = onDismissNewConversation) { Text("Cancel") }
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.chatMessages.isEmpty() && !state.isProcessing) {
                    item {
                        Text(
                            text = state.chatStartTip.ifBlank { "Send a message to start." },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                items(state.chatMessages, key = { it.id }) { message ->
                    AndroidChatMessage(
                        message = message,
                        onToggleToolModifyReviewSelection = onToggleToolModifyReviewSelection,
                        onResolveToolModifyReview = onResolveToolModifyReview,
                    )
                }

                if (state.isProcessing) {
                    item {
                        Text(
                            text = state.agentActions.lastOrNull() ?: "Thinking...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            AndroidModelSelector(
                selectedModelAlias = state.selectedModel,
                availableModelAliases = state.availableModelAliases,
                onModelChange = { alias ->
                    LLMModel.entries.firstOrNull { it.alias == alias }?.let(onModelChange)
                        ?: onShowMessage("Unknown model: $alias")
                },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    enabled = !state.isProcessing && !state.isAwaitingToolReview,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                    minLines = 1,
                    maxLines = 5,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    enabled = state.isProcessing || canSend,
                    onClick = {
                        when {
                            state.isProcessing -> onCancel()
                            state.isAwaitingToolReview -> Unit
                            else -> {
                                val text = input.trim()
                                if (text.isNotEmpty()) {
                                    input = ""
                                    onSendMessage(text)
                                }
                            }
                        }
                    },
                ) {
                    Icon(
                        imageVector = if (state.isProcessing) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                        contentDescription = null,
                    )
                }
            }
        }
    }
}

@Composable
private fun AndroidChatMessage(
    message: ChatMessage,
    onToggleToolModifyReviewSelection: (String, Long) -> Unit,
    onResolveToolModifyReview: (String, ToolModifySelectionAction) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (message.isUser) "You" else "Assistant",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (message.text.isNotBlank()) {
                Text(message.text)
            } else if (message.agentActions.isEmpty() && message.toolModifyReview == null) {
                Text("...")
            }
            if (message.agentActions.isNotEmpty()) {
                Text(
                    text = message.agentActions.joinToString("\n"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            message.toolModifyReview?.let { review ->
                AndroidToolModifyReview(
                    messageId = message.id,
                    review = review,
                    onToggleSelection = onToggleToolModifyReviewSelection,
                    onResolve = onResolveToolModifyReview,
                )
            }
        }
    }
}

@Composable
private fun AndroidToolModifyReview(
    messageId: String,
    review: ToolModifyReviewUi,
    onToggleSelection: (String, Long) -> Unit,
    onResolve: (String, ToolModifySelectionAction) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = if (review.isResolved) "Edit review result" else "Review staged edits",
            fontWeight = FontWeight.SemiBold,
        )
        review.summary?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        review.items.forEach { item ->
            AndroidToolModifyReviewItem(
                messageId = messageId,
                item = item,
                reviewResolved = review.isResolved,
                onToggleSelection = onToggleSelection,
            )
        }
        if (!review.isResolved) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onResolve(messageId, ToolModifySelectionAction.APPLY_SELECTED) },
                ) {
                    Text("Apply selected")
                }
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onResolve(messageId, ToolModifySelectionAction.DISCARD_SELECTED) },
                ) {
                    Text("Discard selected")
                }
            }
        }
    }
}

@Composable
private fun AndroidToolModifyReviewItem(
    messageId: String,
    item: ToolModifyReviewItemUi,
    reviewResolved: Boolean,
    onToggleSelection: (String, Long) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!reviewResolved) {
                Checkbox(
                    checked = item.selected,
                    onCheckedChange = { onToggleSelection(messageId, item.id) },
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = androidToolModifyStatusText(item, reviewResolved),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item.warning?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = item.patchPreview.ifBlank { "(empty patch)" },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(6.dp),
                )
                .padding(8.dp)
                .verticalScroll(rememberScrollState()),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun androidToolModifyStatusText(
    item: ToolModifyReviewItemUi,
    reviewResolved: Boolean,
): String =
    if (!reviewResolved) {
        if (item.selected) "Selected" else "Not selected"
    } else {
        when (item.status) {
            ToolModifyApplyStatus.APPLIED -> "Applied"
            ToolModifyApplyStatus.DISCARDED -> "Discarded"
            ToolModifyApplyStatus.SKIPPED_CONFLICT -> "Skipped: dependency conflict"
            ToolModifyApplyStatus.SKIPPED_EXTERNAL_CONFLICT -> "Skipped: file changed"
            null -> "Pending"
        }
    }

@Composable
private fun AndroidModelSelector(
    selectedModelAlias: String,
    availableModelAliases: List<String>,
    onModelChange: (String) -> Unit,
) {
    if (availableModelAliases.isEmpty()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        availableModelAliases.take(4).forEach { alias ->
            val selected = alias == selectedModelAlias
            if (selected) {
                Button(onClick = { onModelChange(alias) }) { Text(alias) }
            } else {
                TextButton(onClick = { onModelChange(alias) }) { Text(alias) }
            }
        }
    }
}

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
