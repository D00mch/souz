@file:OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package ru.souz.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.model.DefaultMarkdownColors
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.localDI
import ru.souz.LocalWindowScope
import ru.souz.ui.common.*
import ru.souz.ui.glassColors
import souz.composeapp.generated.resources.*
import java.awt.datatransfer.Transferable
import java.awt.dnd.*
import java.util.*


private val TopButtonSize = 24.dp
private val TopIconSize = 14.dp
private val ChatUserBubbleGradientStart = Color(0x3312E0B5)
private val ChatUserBubbleGradientEnd = Color(0x1912E0B5)
private val ChatUserBubbleBorderColor = Color(0x6612E0B5)
private val ChatUserTextColor = Color(0xF2FFFFFF)
private val ChatUserTimestampColor = Color(0xB212E0B5)
private val ChatAssistantBubbleBackground = Color(0x4C000000)
private val ChatAssistantBubbleBorderColor = Color(0x33FFFFFF)
private val ChatAssistantTextColor = Color(0xE5FFFFFF)
private val ChatAssistantTimestampColor = Color(0x7FFFFFFF)
private val FinderPathChipBackground = Color(0x2625CAB0)
private val FinderPathChipBorder = Color(0x8812E0B5)
private val FinderPathChipTextColor = Color(0xFF12E0B5)
private val MessageAttachmentPreviewSize = 64.dp
private val MessageAttachmentNameColor = Color(0x99FFFFFF)
private val ToolPermissionDialogMaxWidth = 920.dp
private const val ToolPermissionDialogMaxHeightFraction = 1f
private val ToolModifyPatchPreviewMinHeight = 220.dp
private val ToolModifyPatchPreviewMaxHeight = 620.dp
private const val ToolModifyPatchParam = "patch"
private const val ToolModifyPatchPreviewMaxLines = 350

enum class LiquidGlassPreset {
    Default,
    Hero
}


@Composable
fun MainScreen(
    onOpenSettings: () -> Unit,
    onCloseWindow: () -> Unit,
    onHideWindow: () -> Unit,
    onMinimizeWindow: () -> Unit,
    onShowSnack: (String) -> Unit = {},
    isOnline: Boolean = true,
) {
    val di = localDI()
    val viewModel = viewModel { MainViewModel(di) }
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                MainEffect.Hide -> onHideWindow()
                is MainEffect.ShowError -> Unit
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
        onRequestNewConversation = { viewModel.send(MainEvent.RequestNewConversation) },
        onConfirmNewConversation = { viewModel.send(MainEvent.ConfirmNewConversation) },
        onDismissNewConversationDialog = { viewModel.send(MainEvent.DismissNewConversationDialog) },
        onOpenSettings = onOpenSettings,
        onStopSpeech = { viewModel.send(MainEvent.StopSpeech) },
        onShowLastText = { viewModel.send(MainEvent.ShowLastText) },
        onToggleThinkingPanel = { viewModel.send(MainEvent.ToggleThinkingPanel) },
        onShowSnack = onShowSnack,
        onChatModelChange = { viewModel.send(MainEvent.UpdateChatModel(it)) },
        onChatContextSizeChange = { viewModel.send(MainEvent.UpdateChatContextSize(it)) },
        onPickChatAttachments = { viewModel.send(MainEvent.PickChatAttachments) },
        onAttachDroppedTransferable = { viewModel.onAttachDroppedTransferable(it) },
        onRemoveChatAttachment = { viewModel.send(MainEvent.RemoveChatAttachment(it)) },
        onSendChatMessage = { viewModel.send(MainEvent.SendChatMessage(it)) },
        onClearContext = { viewModel.send(MainEvent.UserPressStop) },
        onApproveToolPermission = { viewModel.send(MainEvent.ApproveToolPermission) },
        onRejectToolPermission = { viewModel.send(MainEvent.RejectToolPermission) },
        onSelectApprovalCandidate = { viewModel.send(MainEvent.SelectApprovalCandidate(it)) },
        onCancelSelectionDialog = { viewModel.send(MainEvent.CancelSelectionDialog) },
        onOpenPath = { viewModel.send(MainEvent.OpenPath(it)) },
    )
}

@Composable
fun MainScreenContent(
    state: MainState,
    isOnline: Boolean,
    onStartListening: () -> Unit = {},
    onStopListening: () -> Unit = {},
    onRequestNewConversation: () -> Unit = {},
    onConfirmNewConversation: () -> Unit = {},
    onDismissNewConversationDialog: () -> Unit = {},
    onClose: () -> Unit = {},
    onMinimize: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onStopSpeech: () -> Unit = {},
    onShowLastText: () -> Unit = {},
    onToggleThinkingPanel: () -> Unit = {},
    onShowSnack: (String) -> Unit = {},
    onChatModelChange: (String) -> Unit = {},
    onChatContextSizeChange: (Int) -> Unit = {},
    onPickChatAttachments: () -> Unit = {},
    onAttachDroppedTransferable: (Transferable) -> Unit = {},
    onRemoveChatAttachment: (String) -> Unit = {},
    onSendChatMessage: (String) -> Unit = {},
    onClearContext: () -> Unit = {},
    onApproveToolPermission: () -> Unit = {},
    onRejectToolPermission: () -> Unit = {},
    onSelectApprovalCandidate: (Long) -> Unit = {},
    onCancelSelectionDialog: () -> Unit = {},
    onOpenPath: (String) -> Unit = {},
) {
    val windowInfo = LocalWindowInfo.current
    val isFocused = windowInfo.isWindowFocused

    val stringAppName = stringResource(Res.string.app_title_short)
    val stringProcessing = stringResource(Res.string.status_processing)
    val stringNewChatTitle = stringResource(Res.string.dialog_new_chat_title)
    val stringNewChatText = stringResource(Res.string.dialog_new_chat_text)
    val stringNewChatConfirm = stringResource(Res.string.dialog_new_chat_confirm)
    val stringPermissionTitle = stringResource(Res.string.dialog_permission_title)
    val stringPermissionAllow = stringResource(Res.string.dialog_permission_allow)
    val stringPermissionDeny = stringResource(Res.string.dialog_permission_deny)
    val stringPermissionModifyFile = stringResource(Res.string.permission_modify_file)

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        RealLiquidGlassCard(
            modifier = Modifier.fillMaxSize(),
            isWindowFocused = isFocused,
            preset = LiquidGlassPreset.Hero
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                DraggableWindowArea {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .zIndex(2f)
                    ) {
                    Text(
                        text = stringAppName,
                        modifier = Modifier.align(Alignment.Center),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            color = Color(0x33FFFFFF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )

                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 8.dp),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val iconTint = Color(0xCCFFFFFF)

                        Box(
                            modifier = Modifier.size(TopButtonSize),
                            contentAlignment = Alignment.TopEnd
                        ) {
                            MinimalGlassButton(onClick = onToggleThinkingPanel) {
                                Icon(Icons.Rounded.Psychology, null, tint = iconTint, modifier = Modifier.size(TopIconSize))
                            }
                            if (state.isProcessing) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .offset(x = 2.dp, y = (-2).dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF00E5FF))
                                        .border(1.dp, Color(0x80000000), CircleShape)
                                )
                            }
                        }

                        Spacer(Modifier.width(8.dp))

                        Box(
                            modifier = Modifier.size(TopButtonSize),
                            contentAlignment = Alignment.TopEnd
                        ) {
                            MinimalGlassButton(onClick = onRequestNewConversation) {
                                Icon(
                                    Icons.Rounded.Replay,
                                    null,
                                    tint = iconTint,
                                    modifier = Modifier.size(TopIconSize)
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val iconTint = Color(0xCCFFFFFF)

                        if (state.lastText != null) {
                            MinimalGlassButton(onClick = onShowLastText) {
                                Icon(
                                    Icons.Rounded.SkipPrevious,
                                    null,
                                    tint = iconTint,
                                    modifier = Modifier.size(TopIconSize)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        MinimalGlassButton(onClick = onOpenSettings) {
                            Icon(Icons.Rounded.Settings, null, tint = iconTint, modifier = Modifier.size(TopIconSize))
                        }
                        Spacer(Modifier.width(8.dp))
                        MinimalGlassButton(onClick = onClose) {
                            Icon(Icons.Rounded.Close, null, tint = iconTint, modifier = Modifier.size(TopIconSize))
                        }
                    }
                    }
                }

                ChatModeContent(
                    messages = state.chatMessages,
                    chatPlaceholder = state.chatStartTip,
                    chatSessionId = state.chatSessionId,
                    selectedModel = state.selectedModel,
                    availableModelAliases = state.availableModelAliases,
                    selectedContextSize = state.selectedContextSize,
                    attachedFiles = state.attachedFiles,
                    isProcessing = state.isProcessing,
                    isListening = state.isListening,
                    isOnline = isOnline,
                    isSpeaking = state.isSpeaking,
                    onModelChange = onChatModelChange,
                    onContextChange = onChatContextSizeChange,
                    onPickAttachments = onPickChatAttachments,
                    onDropTransferable = onAttachDroppedTransferable,
                    onRemoveAttachment = onRemoveChatAttachment,
                    onSendMessage = onSendChatMessage,
                    onCancelProcessing = onClearContext,
                    onStartListening = onStartListening,
                    onStopListening = onStopListening,
                    onStopSpeech = onStopSpeech,
                    onShowSnack = onShowSnack,
                    onOpenPath = onOpenPath,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = 43.dp)
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            0.0f to Color.Transparent,
                            0.04f to Color(0x18FFFFFF),
                            0.96f to Color(0x18FFFFFF),
                            1.0f to Color.Transparent
                        )
                    )
            )
            
            // Thinking Panel Overlay
            AnimatedVisibility(
                visible = state.isThinkingPanelOpen,
                enter = slideInHorizontally { it },
                exit = slideOutHorizontally { it },
                modifier = Modifier.align(Alignment.CenterEnd).zIndex(10f)
            ) {
                 ThinkingProcessPanel(
                     history = state.agentHistory,
                     isProcessing = state.isProcessing,
                     onClose = onToggleThinkingPanel,
                     modifier = Modifier.fillMaxHeight().width(400.dp)
                 )
            }

            if (state.showNewChatDialog) {
                ConfirmDialog(
                    type = ConfirmDialogType.INFO,
                    title = stringNewChatTitle,
                    message = stringNewChatText,
                    confirmText = stringNewChatConfirm,
                    onConfirm = onConfirmNewConversation,
                    onDismiss = onDismissNewConversationDialog
                )
            }

            state.toolPermissionDialog?.let { dialog ->
                val patchText = dialog.params[ToolModifyPatchParam]?.takeIf { it.isNotBlank() }
                val isToolModifyPermission = dialog.description == stringPermissionModifyFile && patchText != null
                val visibleParams = dialog.params.filterKeys { it != ToolModifyPatchParam }
                val paramsString = if (visibleParams.isNotEmpty()) {
                    visibleParams.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                } else null

                ConfirmDialog(
                    type = ConfirmDialogType.WARNING,
                    title = stringPermissionTitle,
                    message = dialog.description,
                    details = paramsString,
                    dialogMaxWidth = ToolPermissionDialogMaxWidth,
                    dialogMaxHeightFraction = ToolPermissionDialogMaxHeightFraction,
                    detailsContent = if (isToolModifyPermission) {
                        {
                            if (!paramsString.isNullOrBlank()) {
                                Text(
                                    text = paramsString,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0x80FFFFFF),
                                    lineHeight = 18.sp,
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            ToolModifyPatchPreview(patch = patchText.orEmpty())
                        }
                    } else {
                        null
                    },
                    confirmText = stringPermissionAllow,
                    cancelText = stringPermissionDeny,
                    onConfirm = onApproveToolPermission,
                    onDismiss = onRejectToolPermission
                )
            }

            state.selectionDialog?.let { dialog ->
                SelectionDialog(
                    requestId = dialog.requestId,
                    title = dialog.title,
                    message = dialog.message,
                    candidates = dialog.candidates,
                    confirmText = dialog.confirmText,
                    cancelText = dialog.cancelText,
                    onConfirmSelection = onSelectApprovalCandidate,
                    onDismiss = onCancelSelectionDialog,
                )
            }
        }
    }
}

@Composable
private fun SelectionDialog(
    requestId: Long,
    title: String,
    message: String,
    candidates: List<SelectionDialogCandidateUi>,
    confirmText: String,
    cancelText: String,
    onConfirmSelection: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedId by remember(requestId) { mutableStateOf<Long?>(null) }

    ConfirmDialog(
        type = ConfirmDialogType.WARNING,
        title = title,
        message = message,
        dialogMaxWidth = ToolPermissionDialogMaxWidth,
        dialogMaxHeightFraction = ToolPermissionDialogMaxHeightFraction,
        detailsContent = {
            SelectionCandidatesList(
                candidates = candidates,
                selectedId = selectedId,
                onSelect = { selectedId = it },
            )
        },
        confirmText = confirmText,
        cancelText = cancelText,
        confirmEnabled = selectedId != null,
        onConfirm = { selectedId?.let(onConfirmSelection) },
        onDismiss = onDismiss,
    )
}

@Composable
private fun SelectionCandidatesList(
    candidates: List<SelectionDialogCandidateUi>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        candidates.forEach { candidate ->
            val candidateId = candidate.id
            SelectionCandidateRow(
                title = candidate.title,
                selected = candidateId == selectedId,
                badge = candidate.badge,
                meta = candidate.meta,
                preview = candidate.preview,
                onClick = { onSelect(candidateId) },
            )
        }
    }
}

@Composable
private fun SelectionCandidateRow(
    title: String,
    selected: Boolean,
    badge: String?,
    meta: String?,
    preview: String?,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val borderColor by animateColorAsState(
        targetValue = when {
            selected -> Color(0xFFF59E0B)
            isHovered -> Color(0x66FFFFFF)
            else -> Color(0x1AFFFFFF)
        }
    )
    val backgroundColor by animateColorAsState(
        targetValue = when {
            selected -> Color(0x26F59E0B)
            isHovered -> Color(0x14FFFFFF)
            else -> Color(0x0DFFFFFF)
        }
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                color = Color(0xF2FFFFFF),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!badge.isNullOrBlank()) {
                Text(
                    text = badge,
                    color = Color(0xFFF59E0B),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        meta?.takeIf { it.isNotBlank() }?.let { metaText ->
            Text(
                text = metaText,
                color = Color(0x99FFFFFF),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        preview?.takeIf { it.isNotBlank() }?.let { previewText ->
            Text(
                text = previewText,
                color = Color(0x80FFFFFF),
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun chatMarkdownColors(textColor: Color) = DefaultMarkdownColors(
    text = textColor,
    codeText = Color(0xFFE0E0E0),
    codeBackground = Color(0x66000000),
    inlineCodeText = Color(0xFF81D4FA),
    inlineCodeBackground = Color(0x1AFFFFFF),
    dividerColor = textColor.copy(alpha = 0.2f),
    linkText = Color(0xFF82B1FF)
)

@Composable
private fun ToolModifyPatchPreview(patch: String) {
    val (lines, isTruncated) = remember(patch) {
        buildPatchPreviewLines(patch, ToolModifyPatchPreviewMaxLines)
    }
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(
                min = ToolModifyPatchPreviewMinHeight,
                max = ToolModifyPatchPreviewMaxHeight
            )
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x33000000))
            .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(6.dp))
            .padding(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(verticalScroll)
                .horizontalScroll(horizontalScroll),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            lines.forEach { line ->
                Text(
                    text = line.text,
                    color = line.color,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
            if (isTruncated) {
                Text(
                    text = "... (preview truncated)",
                    color = Color(0x99FFFFFF),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

private fun buildPatchPreviewLines(
    patch: String,
    maxLines: Int,
): Pair<List<PatchPreviewLine>, Boolean> {
    if (patch.isBlank()) {
        return listOf(PatchPreviewLine("(empty patch)", Color(0x99FFFFFF))) to false
    }

    val allLines = patch.lines()
    val preview = allLines
        .take(maxLines)
        .map { line ->
            val color = when {
                line.startsWith("+++") || line.startsWith("---") -> Color(0xFF90CAF9)
                line.startsWith("@@") -> Color(0xFFFFCC80)
                line.startsWith("+") -> Color(0xFFB9F6CA)
                line.startsWith("-") -> Color(0xFFFF8A80)
                line.startsWith("diff ") || line.startsWith("index ") -> Color(0xFFB0BEC5)
                else -> Color(0xCCFFFFFF)
            }
            PatchPreviewLine(text = line, color = color)
        }

    return preview to (allLines.size > maxLines)
}

private data class PatchPreviewLine(
    val text: String,
    val color: Color,
)

@Composable
private fun chatMarkdownTypography(
    baseStyle: TextStyle,
    codeStyle: TextStyle,
    headingScale: HeadingScale = HeadingScale.LARGE,
): DefaultMarkdownTypography {
    val headings = when (headingScale) {
        HeadingScale.LARGE -> listOf(
            MaterialTheme.typography.headlineMedium,
            MaterialTheme.typography.titleLarge,
            MaterialTheme.typography.titleMedium,
            MaterialTheme.typography.titleSmall,
            MaterialTheme.typography.bodyLarge,
            MaterialTheme.typography.bodyMedium,
        )
        HeadingScale.SMALL -> listOf(
            MaterialTheme.typography.titleMedium,
            MaterialTheme.typography.titleSmall,
            MaterialTheme.typography.titleSmall,
            MaterialTheme.typography.bodyLarge,
            MaterialTheme.typography.bodyMedium,
            MaterialTheme.typography.bodyMedium,
        )
    }
    return DefaultMarkdownTypography(
        h1 = headings[0].copy(color = baseStyle.color, fontWeight = FontWeight.Bold),
        h2 = headings[1].copy(color = baseStyle.color, fontWeight = FontWeight.Bold),
        h3 = headings[2].copy(color = baseStyle.color, fontWeight = FontWeight.Bold),
        h4 = headings[3].copy(color = baseStyle.color, fontWeight = FontWeight.Bold),
        h5 = headings[4].copy(color = baseStyle.color, fontWeight = FontWeight.Bold),
        h6 = headings[5].copy(color = baseStyle.color, fontWeight = FontWeight.Bold),
        text = baseStyle,
        paragraph = baseStyle,
        code = codeStyle,
        inlineCode = codeStyle.copy(color = Color(0xFF81D4FA), background = Color(0x1AFFFFFF)),
        quote = baseStyle.copy(color = Color.Gray, fontStyle = FontStyle.Italic),
        bullet = baseStyle.copy(fontWeight = FontWeight.Bold),
        list = baseStyle,
        ordered = baseStyle,
        link = baseStyle.copy(color = Color(0xFF82B1FF), textDecoration = TextDecoration.Underline)
    )
}

private enum class HeadingScale { LARGE, SMALL }

@Composable
fun ChatModeContent(
    messages: List<ChatMessage>,
    chatPlaceholder: String,
    chatSessionId: Long,
    selectedModel: String,
    availableModelAliases: List<String>,
    selectedContextSize: Int,
    attachedFiles: List<ChatAttachedFile>,
    isProcessing: Boolean,
    isListening: Boolean,
    isOnline: Boolean,
    isSpeaking: Boolean,
    onModelChange: (String) -> Unit,
    onContextChange: (Int) -> Unit,
    onPickAttachments: () -> Unit,
    onDropTransferable: (Transferable) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onCancelProcessing: () -> Unit = {},
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onStopSpeech: () -> Unit,
    onShowSnack: (String) -> Unit,
    onOpenPath: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val textColor = MaterialTheme.glassColors.textPrimary
    val speakingMessageId = messages.lastOrNull()
        ?.takeIf { isSpeaking && !it.isUser && it.isVoice }
        ?.id
    val stringProcessing = stringResource(Res.string.status_processing)
    var inputText by remember(chatSessionId) { mutableStateOf(TextFieldValue("")) }
    var isFileDragActive by remember { mutableStateOf(false) }

    val windowInfo = LocalWindowInfo.current
    val isWindowFocused = windowInfo.isWindowFocused

    LaunchedEffect(isWindowFocused) {
        if (isWindowFocused) {
            focusRequester.requestFocus()
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }


    ChatFileDropTarget(
        enabled = true,
        onDropTransferable = onDropTransferable,
        onDragStateChanged = { isActive -> isFileDragActive = isActive }
    )

    Column(
        modifier = modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown &&
                !event.isMetaPressed &&
                event.key != Key.Enter &&
                event.key != Key.NumPadEnter
            ) {
                focusRequester.requestFocus()
            }
            false
        }
    ) {
        if (messages.isEmpty() && !isProcessing) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 0.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    ChatBubble(
                        message = message,
                        onShowSnack = onShowSnack,
                        onOpenPath = onOpenPath
                    )
                }

                if (isProcessing) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp, horizontal = 8.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = textColor.copy(0.5f),
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = stringProcessing,
                                    color = textColor.copy(0.5f),
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        ConnectionStatusNotification(
            isOnline = isOnline,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 8.dp)
        )

        ChatInputWithQuickSettings(
            value = inputText,
            onValueChange = { inputText = it },
            onSend = {
                val currentText = inputText.text
                onSendMessage(currentText)
                inputText = TextFieldValue("")
            },
            onCancel = onCancelProcessing,
            attachedFiles = attachedFiles,
            onAttachClick = onPickAttachments,
            onRemoveAttachment = onRemoveAttachment,
            isFileDragActive = isFileDragActive,
            isProcessing = isProcessing,
            isListening = isListening,
            speakingMessageId = speakingMessageId,
            onStartListening = onStartListening,
            onStopListening = onStopListening,
            onStopSpeaking = onStopSpeech,
            enabled = !isProcessing,
            focusRequester = focusRequester,
            selectedModel = selectedModel,
            availableModelAliases = availableModelAliases,
            selectedContextSize = selectedContextSize,
            onModelChange = onModelChange,
            onContextChange = onContextChange,
            scrollCloseSignal = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset,
            placeholder = if (messages.isEmpty()) chatPlaceholder else "",
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
        )
    }
}




@Composable
private fun ChatFileDropTarget(
    enabled: Boolean,
    onDropTransferable: (Transferable) -> Unit,
    onDragStateChanged: (Boolean) -> Unit,
) {
    val windowScope = LocalWindowScope.current
    val window = windowScope?.window
    val currentOnDropTransferable by rememberUpdatedState(onDropTransferable)
    val currentOnDragState by rememberUpdatedState(onDragStateChanged)

    DisposableEffect(window, enabled) {
        if (window == null || !enabled) return@DisposableEffect onDispose {}

        val listener = object : DropTargetAdapter() {
            override fun dragEnter(dtde: DropTargetDragEvent) {
                currentOnDragState(true)
            }

            override fun dragExit(dte: DropTargetEvent) {
                currentOnDragState(false)
            }

            override fun drop(dtde: DropTargetDropEvent) {
                currentOnDragState(false)
                try {
                    dtde.acceptDrop(DnDConstants.ACTION_COPY)
                    currentOnDropTransferable(dtde.transferable)
                    dtde.dropComplete(true)
                } catch (e: Exception) {
                    dtde.dropComplete(false)
                }
            }
        }

        val target = DropTarget(window, DnDConstants.ACTION_COPY, listener)
        
        onDispose {
            if (window.dropTarget == target) {
                window.dropTarget = null
            }
        }
    }
}

@Composable
private fun ChatBubble(
    message: ChatMessage,
    onShowSnack: (String) -> Unit,
    onOpenPath: (String) -> Unit
) {
    val bubbleShape = if (message.isUser) {
        RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = 16.dp,
            bottomEnd = 4.dp
        )
    } else {
        RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = 4.dp,
            bottomEnd = 16.dp
        )
    }

    val bubbleBorderColor = if (message.isUser) {
        ChatUserBubbleBorderColor
    } else {
        ChatAssistantBubbleBorderColor
    }

    val alignment = if (message.isUser) Alignment.CenterEnd else Alignment.CenterStart
    
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 800.dp)
                .clip(bubbleShape)
                .background(
                    brush = if (message.isUser) {
                        Brush.linearGradient(
                            colors = listOf(
                                ChatUserBubbleGradientStart,
                                ChatUserBubbleGradientEnd
                            )
                        )
                    } else {
                        Brush.linearGradient(
                            colors = listOf(
                                ChatAssistantBubbleBackground,
                                ChatAssistantBubbleBackground
                            )
                        )
                    }
                )
                .border(1.dp, bubbleBorderColor, bubbleShape)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (message.isUser) {
                if (message.attachedFiles.isNotEmpty()) {
                    MessageAttachmentsPreview(
                        files = message.attachedFiles,
                        onOpenPath = onOpenPath,
                    )
                    if (message.text.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                val customSelectionColors = TextSelectionColors(
                    handleColor = Color(0xFFFFFFFF),
                    backgroundColor = Color(0x66FFFFFF)
                )

                if (message.text.isNotBlank()) {
                    CompositionLocalProvider(LocalTextSelectionColors provides customSelectionColors) {
                        SelectionContainer {
                            Text(
                                text = message.text,
                                color = ChatUserTextColor,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                modifier = Modifier.padding(bottom = 3.dp)
                            )
                        }
                    }
                }
            } else {
                val parts = remember(message.text) { parseMarkdownContent(message.text) }
                val baseFontSize = 14.sp
                val baseStyle = TextStyle(
                    color = ChatAssistantTextColor,
                    fontSize = baseFontSize,
                    lineHeight = 20.sp
                )
                val codeStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = baseFontSize * 0.9,
                    color = Color(0xFFE0E0E0)
                )
                val attachmentPathKeys = remember(message.attachedFiles) {
                    message.attachedFiles.map { it.path.lowercase(Locale.ROOT) }.toSet()
                }
                val clickablePaths = message.finderPaths
                    .filterNot { it.path.lowercase(Locale.ROOT) in attachmentPathKeys }
                
                val bubbleTypography = chatMarkdownTypography(baseStyle, codeStyle, HeadingScale.SMALL)
                val bubbleColors = chatMarkdownColors(baseStyle.color)

                SelectionContainer {
                    Column {
                        parts.forEach { part ->
                            when (part) {
                                is MarkdownPart.TextContent -> {
                                    Markdown(
                                        content = part.content,
                                        colors = bubbleColors,
                                        typography = bubbleTypography,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                is MarkdownPart.CodeContent -> {
                                    CodeBlockWithCopy(
                                        code = part.code,
                                        language = part.language,
                                        style = codeStyle
                                    )
                                }
                            }
                        }
                    }
                }

                if (message.attachedFiles.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    MessageAttachmentsPreview(
                        files = message.attachedFiles,
                        onOpenPath = onOpenPath,
                    )
                }

                if (clickablePaths.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        clickablePaths.forEach { item ->
                            FinderPathChip(
                                path = item.path,
                                displayName = item.displayName,
                                isDirectory = item.isDirectory,
                                onClick = {
                                    onOpenPath(item.path)
                                }
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.padding(top = 0.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatTimestamp(message.timestamp),
                    color = if (message.isUser) ChatUserTimestampColor else ChatAssistantTimestampColor,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun MessageAttachmentsPreview(
    files: List<ChatAttachedFile>,
    onOpenPath: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        files.forEach { file ->
            MessageAttachmentTile(
                file = file,
                onOpenPath = onOpenPath,
            )
        }
    }
}

@Composable
private fun MessageAttachmentTile(
    file: ChatAttachedFile,
    onOpenPath: (String) -> Unit,
) {
    val previewStyle = chatAttachmentUiStyle(file.type)
    val bitmap = remember(file.thumbnailBytes) { decodeAttachmentThumbnail(file.thumbnailBytes) }

    Column(
        modifier = Modifier
            .width(MessageAttachmentPreviewSize)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable { onOpenPath(file.path) },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(MessageAttachmentPreviewSize)
                .clip(RoundedCornerShape(8.dp))
                .background(previewStyle.background)
                .border(1.dp, previewStyle.border, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (file.type == ChatAttachmentType.IMAGE && bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = file.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = previewStyle.icon,
                    contentDescription = null,
                    tint = previewStyle.iconTint,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = file.displayName,
            color = MessageAttachmentNameColor,
            fontSize = 10.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 12.sp
        )
    }
}

@Composable
private fun FinderPathChip(
    path: String,
    displayName: String,
    isDirectory: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    TooltipArea(
        delayMillis = 250,
        tooltip = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xE6000000))
                    .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = path,
                    color = Color(0xF2FFFFFF),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    ) {
        Row(
            modifier = Modifier
                .clip(shape)
                .background(FinderPathChipBackground)
                .border(1.dp, FinderPathChipBorder, shape)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (isDirectory) Icons.Rounded.Folder else Icons.Rounded.Description,
                contentDescription = null,
                tint = FinderPathChipTextColor,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = displayName,
                color = FinderPathChipTextColor,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private val timestampFormatter = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())

private fun formatTimestamp(timestamp: Long): String = 
    timestampFormatter.format(java.util.Date(timestamp))



@Composable
fun MinimalGlassButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val backgroundColor = Color(0x0DFFFFFF)
    val borderColor = Color(0x33FFFFFF)

    Box(
        modifier = Modifier
            .size(TopButtonSize)
            .clip(CircleShape)
            .background(backgroundColor)
            .border(0.5.dp, borderColor, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}



@Composable
fun RealLiquidGlassCard(
    modifier: Modifier = Modifier,
    isWindowFocused: Boolean,
    cornerRadius: Dp = 23.dp,
    preset: LiquidGlassPreset = LiquidGlassPreset.Hero,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val borderThickness = if (preset == LiquidGlassPreset.Hero) 1.dp else 1.5.dp

    val backdropAlpha by animateFloatAsState(
        targetValue = when (preset) {
            LiquidGlassPreset.Default -> if (isWindowFocused) 0.95f else 0.0f
            LiquidGlassPreset.Hero -> 1.0f
        },
        animationSpec = tween(400)
    )

    val accentAlpha by animateFloatAsState(
        targetValue = when (preset) {
            LiquidGlassPreset.Default -> if (isWindowFocused) 1f else 0f
            LiquidGlassPreset.Hero -> 0.62f
        },
        animationSpec = tween(400)
    )

    Box(modifier = modifier) {
        when (preset) {
            LiquidGlassPreset.Default -> {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(shape)
                        .background(Color.Black.copy(alpha = backdropAlpha))
                )
            }

            LiquidGlassPreset.Hero -> {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(shape)
                        .background(Color(0xFC151820))
                        .alpha(backdropAlpha)
                )

                Canvas(modifier = Modifier.matchParentSize().clip(shape).alpha(accentAlpha)) {
                    drawRect(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0.0f to Color(0x14D5B284),
                                0.34f to Color(0x0CB08D5F),
                                0.72f to Color(0x047A603B),
                                1.0f to Color.Transparent
                            ),
                            center = Offset(size.width * 0.16f, size.height * 0.06f),
                            radius = size.maxDimension * 1.05f
                        )
                    )

                    drawRect(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Transparent,
                                0.60f to Color.Transparent,
                                0.82f to Color(0x3005070E),
                                1.0f to Color(0x7205070E)
                            ),
                            center = Offset(size.width * 0.50f, size.height * 1.08f),
                            radius = size.maxDimension * 1.02f
                        )
                    )

                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.Transparent, Color(0x22000000)),
                            center = Offset(size.width * 0.50f, size.height * 0.50f),
                            radius = size.maxDimension * 0.95f
                        )
                    )
                }

                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(shape)
                        .background(Color(0x59000000))
                )
            }
        }

        Canvas(modifier = Modifier.matchParentSize().clip(shape)) {
            val strokeWidth = borderThickness.toPx()
            when (preset) {
                LiquidGlassPreset.Default -> {
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            0.0f to Color(0xCCFFFFFF),
                            0.5f to Color(0x00FFFFFF),
                            1.0f to Color(0x4DFFFFFF),
                            start = Offset(0f, 0f),
                            end = Offset(size.width, size.height)
                        ),
                        cornerRadius = CornerRadius(cornerRadius.toPx()),
                        style = Stroke(width = strokeWidth)
                    )

                    drawPath(
                        path = Path().apply {
                            moveTo(0f, size.height * 0.2f)
                            lineTo(size.width * 0.4f, 0f)
                            lineTo(size.width * 0.65f, 0f)
                            lineTo(0f, size.height * 0.6f)
                            close()
                        },
                        brush = Brush.linearGradient(
                            colors = listOf(Color(0x0DFFFFFF), Color.Transparent),
                            start = Offset(0f, 0f),
                            end = Offset(size.width / 2, size.height / 2)
                        )
                    )
                }

                LiquidGlassPreset.Hero -> {
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color(0x66FFFFFF),
                                Color(0x22FFFFFF),
                                Color(0x18FFFFFF),
                                Color(0x40FFFFFF)
                            ),
                            start = Offset(0f, 0f),
                            end = Offset(size.width, size.height)
                        ),
                        cornerRadius = CornerRadius(cornerRadius.toPx()),
                        style = Stroke(width = strokeWidth)
                    )

                    val inset = strokeWidth * 1.4f
                    val innerWidth = (size.width - inset * 2f).coerceAtLeast(0f)
                    val innerHeight = (size.height - inset * 2f).coerceAtLeast(0f)
                    drawRoundRect(
                        color = Color(0x0DFFFFFF),
                        topLeft = Offset(inset, inset),
                        size = Size(innerWidth, innerHeight),
                        cornerRadius = CornerRadius((cornerRadius.toPx() - inset).coerceAtLeast(0f)),
                        style = Stroke(width = strokeWidth * 0.7f)
                    )
                }
            }
        }

        if (preset == LiquidGlassPreset.Default) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(shape)
                    .background(Color(0x03FFFFFF))
            )
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(shape)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0x0AFFFFFF),
                                Color(0x05FFFFFF),
                                Color(0x01FFFFFF)
                            )
                        )
                    )
                    .alpha(accentAlpha)
            )
        }

        val innerShape = RoundedCornerShape(cornerRadius - borderThickness)
        Box(modifier = Modifier.padding(borderThickness).clip(innerShape)) {
            content()
        }
    }
}

@Preview
@Composable
fun PreviewSmartFocusGlass() {
    MaterialTheme {
        Box(Modifier.fillMaxSize().background(Color.Gray)) {
            MainScreenContent(
                state = MainState(
                    displayedText = "### Заголовок\nВот пример кода:\n```python\ndef hello():\n    print('Hello')\n```\n* Пункт 1\n* Пункт 2",
                    statusMessage = "Готов",
                    isListening = false
                ),
                isOnline = true
            )
        }
    }
}

@Preview
@Composable
fun PreviewChatMode() {
    MaterialTheme {
        Box(Modifier.fillMaxSize().background(Color.Gray)) {
            MainScreenContent(
                state = MainState(
                    chatMessages = listOf(
                        ChatMessage("Привет! Как дела?", isUser = true, timestamp = System.currentTimeMillis() - 60000),
                        ChatMessage("Привет! Все отлично, спасибо за вопрос. Чем могу помочь?", isUser = false, timestamp = System.currentTimeMillis() - 30000),
                        ChatMessage("Покажи погоду в Москве", isUser = true, timestamp = System.currentTimeMillis())
                    ),
                    displayedText = "",
                    statusMessage = "Чат режим",
                    isListening = false
                ),
                isOnline = true
            )
        }
    }
}

@Preview
@Composable
fun PreviewChatModeEmpty() {
    MaterialTheme {
        Box(Modifier.fillMaxSize().background(Color.Gray)) {
            MainScreenContent(
                state = MainState(
                    chatMessages = emptyList(),
                    displayedText = "",
                    statusMessage = "Чат режим",
                    isListening = false
                ),
                isOnline = true
            )
        }
    }
}
