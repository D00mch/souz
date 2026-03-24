@file:OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package ru.souz.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.localDI
import ru.souz.LocalWindowScope
import ru.souz.ui.common.*
import souz.composeapp.generated.resources.*
import java.awt.datatransfer.Transferable
import java.awt.dnd.*
import java.util.*


private val MacTrafficButtonSize = 12.dp
private val MacTrafficRowSpacing = 8.dp
private val TopActionButtonSize = 32.dp
private val TopActionIconSize = 16.dp
private val ChatUserBubbleBackgroundStart = Color(0x5C3F434A)
private val ChatUserBubbleBackgroundEnd = Color(0x53363A40)
private val ChatUserBubbleBorderStart = Color(0x3DFFFFFF)
private val ChatUserBubbleBorderEnd = Color(0x14FFFFFF)
private val ChatUserTextColor = Color(0xE6FFFFFF)
private val ChatUserTimestampColor = Color(0x40FFFFFF)
private val ChatAssistantTextColor = Color(0xD9FFFFFF)
private val ChatAssistantTimestampColor = Color(0x40FFFFFF)
private val ChatHoverIconColor = Color(0x40FFFFFF)
private val ChatHoverIconHoverColor = Color(0x80FFFFFF)
private val ChatHoverButtonBackground = Color(0x0FFFFFFF)
private val ChatSelectionHandleColor = Color(0xFFFFFFFF)
private val ChatSelectionBackgroundColor = Color(0x66FFFFFF)
private val FinderPathChipBackground = Color(0x2625CAB0)
private val FinderPathChipBorder = Color(0x8812E0B5)
private val FinderPathChipTextColor = Color(0xFF12E0B5)
private val MessageAttachmentPreviewSize = 64.dp
private val MessageAttachmentNameColor = Color(0x99FFFFFF)
private val ToolPermissionDialogMaxWidth = 920.dp
private val ToolPermissionCompactDialogMaxWidth = 360.dp
private const val ToolPermissionDialogMaxHeightFraction = 1f
private val ToolModifyPatchPreviewMinHeight = 220.dp
private val ToolModifyPatchPreviewMaxHeight = 620.dp
private const val ToolModifyPatchParam = "patch"
private const val ToolModifyPatchPreviewMaxLines = 350

private enum class MacTrafficKind {
    Close,
    Minimize,
    Maximize,
}

enum class LiquidGlassPreset {
    Default,
    Hero
}


@Composable
fun MainScreen(
    onOpenSettings: () -> Unit,
    onCloseWindow: () -> Unit,
    onMinimizeWindow: () -> Unit,
    onToggleMaximizeWindow: () -> Unit,
    onShowSnack: (String) -> Unit = {},
    isOnline: Boolean = true,
) {
    val di = localDI()
    val viewModel = viewModel { MainViewModel(di) }
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                MainEffect.Hide -> onCloseWindow()
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
        onToggleMaximize = onToggleMaximizeWindow,
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
        onConsumePendingVoiceInputDraft = { token ->
            viewModel.send(MainEvent.ConsumePendingVoiceInputDraft(token))
        },
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
    onToggleMaximize: () -> Unit = {},
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
    onConsumePendingVoiceInputDraft: (Long) -> Unit = {},
    onApproveToolPermission: () -> Unit = {},
    onRejectToolPermission: () -> Unit = {},
    onSelectApprovalCandidate: (Long) -> Unit = {},
    onCancelSelectionDialog: () -> Unit = {},
    onOpenPath: (String) -> Unit = {},
) {
    val windowInfo = LocalWindowInfo.current
    val isFocused = windowInfo.isWindowFocused

    val stringAppName = stringResource(Res.string.app_title_short)
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
                            .height(56.dp)
                            .zIndex(2f)
                    ) {
                    Text(
                        text = stringAppName,
                        modifier = Modifier.align(Alignment.Center),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = TextStyle(
                            color = Color(0x99FFFFFF),
                            fontSize = 13.sp,
                            letterSpacing = 0.65.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )

                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 20.dp),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MacTrafficLightButton(
                            color = Color(0xFFFF5F57),
                            kind = MacTrafficKind.Close,
                            onClick = onClose
                        )
                        Spacer(Modifier.width(MacTrafficRowSpacing))
                        MacTrafficLightButton(
                            color = Color(0xFFFFBD2E),
                            kind = MacTrafficKind.Minimize,
                            onClick = onMinimize
                        )
                        Spacer(Modifier.width(MacTrafficRowSpacing))
                        MacTrafficLightButton(
                            color = Color(0xFF28C940),
                            kind = MacTrafficKind.Maximize,
                            onClick = onToggleMaximize
                        )
                        Spacer(Modifier.width(12.dp))
                        Box(
                            modifier = Modifier
                                .size(width = 1.dp, height = 16.dp)
                                .background(Color(0x14FFFFFF))
                        )
                        Spacer(Modifier.width(12.dp))
                        Box(
                            modifier = Modifier.size(TopActionButtonSize),
                            contentAlignment = Alignment.TopEnd
                        ) {
                            TopToolbarIconButton(
                                size = TopActionButtonSize,
                                onClick = onToggleThinkingPanel
                            ) { iconTint ->
                                Icon(
                                    Icons.Rounded.AccessTime,
                                    null,
                                    tint = iconTint,
                                    modifier = Modifier.size(TopActionIconSize)
                                )
                            }
                            if (state.isProcessing) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .offset(x = 2.dp, y = (-2).dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF00E5FF))
                                        .border(1.dp, Color(0x80000000), CircleShape)
                                )
                            }
                        }

                        Spacer(Modifier.width(10.dp))
                        TopToolbarIconButton(
                            size = TopActionButtonSize,
                            onClick = onRequestNewConversation
                        ) { iconTint ->
                            Icon(
                                Icons.Rounded.Add,
                                null,
                                tint = iconTint,
                                modifier = Modifier.size(TopActionIconSize)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 20.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TopToolbarIconButton(
                            size = TopActionButtonSize,
                            onClick = onOpenSettings
                        ) { iconTint ->
                            Icon(
                                Icons.Rounded.Settings,
                                null,
                                tint = iconTint,
                                modifier = Modifier.size(TopActionIconSize)
                            )
                        }

                        if (state.lastText != null) {
                            Spacer(Modifier.width(10.dp))
                            TopToolbarIconButton(
                                size = TopActionButtonSize,
                                onClick = onShowLastText
                            ) { iconTint ->
                                Icon(
                                    Icons.Rounded.SkipPrevious,
                                    null,
                                    tint = iconTint,
                                    modifier = Modifier.size(TopActionIconSize)
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color(0x0FFFFFFF))
                    )
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
                    pendingVoiceInputDraft = state.pendingVoiceInputDraft,
                    pendingVoiceInputDraftToken = state.pendingVoiceInputDraftToken,
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
                    onConsumePendingVoiceInputDraft = onConsumePendingVoiceInputDraft,
                    onStartListening = onStartListening,
                    onStopListening = onStopListening,
                    onStopSpeech = onStopSpeech,
                    onShowSnack = onShowSnack,
                    onOpenPath = onOpenPath,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 10.dp, top = 14.dp, bottom = 0.dp)
                )
            }
            
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
                    isOpen = true,
                    variant = DialogVariant.INFO,
                    title = stringNewChatTitle,
                    description = stringNewChatText,
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
                    dialogMaxWidth = if (isToolModifyPermission) {
                        ToolPermissionDialogMaxWidth
                    } else {
                        ToolPermissionCompactDialogMaxWidth
                    },
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
    pendingVoiceInputDraft: String?,
    pendingVoiceInputDraftToken: Long,
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
    onConsumePendingVoiceInputDraft: (Long) -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onStopSpeech: () -> Unit,
    onShowSnack: (String) -> Unit,
    onOpenPath: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val speakingMessageId = messages.lastOrNull()
        ?.takeIf { isSpeaking && !it.isUser && it.isVoice }
        ?.id
    val stringThinking = stringResource(Res.string.status_thinking)
    var inputText by remember(chatSessionId) { mutableStateOf(TextFieldValue("")) }
    var isFileDragActive by remember { mutableStateOf(false) }

    val windowInfo = LocalWindowInfo.current
    val isWindowFocused = windowInfo.isWindowFocused

    LaunchedEffect(isWindowFocused) {
        if (isWindowFocused) {
            focusRequester.requestFocus()
        }
    }

    LaunchedEffect(messages.size, isProcessing) {
        if (messages.isNotEmpty() || isProcessing) {
            val targetIndex = if (isProcessing) messages.size else messages.lastIndex
            listState.animateScrollToItem(targetIndex)
        }
    }

    LaunchedEffect(pendingVoiceInputDraftToken) {
        val recognizedText = pendingVoiceInputDraft?.trim().orEmpty()
        if (recognizedText.isEmpty()) return@LaunchedEffect

        val draftToken = pendingVoiceInputDraftToken
        val mergedText = mergeVoiceDraftIntoInputText(inputText.text, recognizedText)
        inputText = TextFieldValue(
            text = mergedText,
            selection = TextRange(mergedText.length),
        )
        onConsumePendingVoiceInputDraft(draftToken)
        focusRequester.requestFocus()
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
            EmptyChatWelcomeContent(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                onSuggestionClick = { command ->
                    onSendMessage(command)
                    inputText = TextFieldValue("")
                }
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
                        onOpenPath = onOpenPath
                    )
                }

                item(key = "thinking-indicator") {
                    AnimatedVisibility(
                        visible = isProcessing,
                        enter = fadeIn(animationSpec = tween(durationMillis = 180)),
                        exit = fadeOut(animationSpec = tween(durationMillis = 120)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp, horizontal = 8.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            ThinkingIndicator(text = stringThinking)
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
                .padding(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 16.dp)
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
    onOpenPath: (String) -> Unit
) {
    val hoverInteractionSource = remember { MutableInteractionSource() }
    val isHovered by hoverInteractionSource.collectIsHoveredAsState()

    var shouldReveal by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(message.id) { shouldReveal = true }
    val revealAlpha by animateFloatAsState(
        targetValue = if (shouldReveal) 1f else 0f,
        animationSpec = tween(
            durationMillis = 250,
            easing = CubicBezierEasing(0.25f, 0.46f, 0.45f, 0.94f)
        )
    )
    val revealOffset by animateDpAsState(
        targetValue = if (shouldReveal) 0.dp else 8.dp,
        animationSpec = tween(
            durationMillis = 250,
            easing = CubicBezierEasing(0.25f, 0.46f, 0.45f, 0.94f)
        )
    )
    val revealOffsetPx = with(LocalDensity.current) { revealOffset.toPx() }

    val contentModifier = Modifier
        .fillMaxWidth()
        .hoverable(interactionSource = hoverInteractionSource)
        .graphicsLayer {
            alpha = revealAlpha
            translationY = revealOffsetPx
        }

    if (message.isUser) {
        val bubbleShape = RoundedCornerShape(16.dp)
        val customSelectionColors = TextSelectionColors(
            handleColor = ChatSelectionHandleColor,
            backgroundColor = ChatSelectionBackgroundColor
        )

        BoxWithConstraints(modifier = contentModifier) {
            val maxBubbleWidth = maxWidth * 0.7f
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .widthIn(max = maxBubbleWidth),
                horizontalAlignment = Alignment.End
            ) {
                Box(
                    modifier = Modifier
                        .clip(bubbleShape)
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    ChatUserBubbleBackgroundStart,
                                    ChatUserBubbleBackgroundEnd
                                )
                            )
                        )
                        .border(
                            width = 1.dp,
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    ChatUserBubbleBorderStart,
                                    ChatUserBubbleBorderEnd
                                )
                            ),
                            shape = bubbleShape
                        )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (message.attachedFiles.isNotEmpty()) {
                            MessageAttachmentsPreview(
                                files = message.attachedFiles,
                                onOpenPath = onOpenPath,
                            )
                        }

                        if (message.text.isNotBlank()) {
                            CompositionLocalProvider(LocalTextSelectionColors provides customSelectionColors) {
                                SelectionContainer {
                                    Text(
                                        text = message.text,
                                        color = ChatUserTextColor,
                                        fontSize = 14.sp,
                                        lineHeight = 22.4.sp,
                                    )
                                }
                            }
                        }
                    }
                }

                val userTimestampAlpha by animateFloatAsState(
                    targetValue = if (isHovered) 1f else 0f,
                    animationSpec = tween(durationMillis = 200)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Text(
                        text = formatTimestamp(message.timestamp),
                        color = ChatUserTimestampColor,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .alpha(userTimestampAlpha)
                    )
                }
            }
        }
    } else {
        val clipboardManager = LocalClipboardManager.current
        val scope = rememberCoroutineScope()
        var copied by remember(message.id) { mutableStateOf(false) }
        var copyNonce by remember(message.id) { mutableStateOf(0) }

        val attachmentPathKeys = remember(message.attachedFiles) {
            message.attachedFiles.map { it.path.lowercase(Locale.ROOT) }.toSet()
        }
        val clickablePaths = message.finderPaths
            .filterNot { it.path.lowercase(Locale.ROOT) in attachmentPathKeys }

        val copyInteractionSource = remember { MutableInteractionSource() }
        val isCopyHovered by copyInteractionSource.collectIsHoveredAsState()
        val copyIconColor by animateColorAsState(
            targetValue = if (isCopyHovered) ChatHoverIconHoverColor else ChatHoverIconColor,
            animationSpec = tween(durationMillis = 150)
        )
        val copyBackgroundColor by animateColorAsState(
            targetValue = if (isCopyHovered) ChatHoverButtonBackground else Color.Transparent,
            animationSpec = tween(durationMillis = 150)
        )

        Box(modifier = contentModifier) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 17.dp, end = 70.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (message.agentActions.isNotEmpty()) {
                    AgentActionList(actions = message.agentActions)
                }

                if (message.text.isNotBlank()) {
                    val parts = remember(message.text) { parseMarkdownContent(message.text) }
                    val baseFontSize = 14.sp
                    val baseStyle = TextStyle(
                        color = ChatAssistantTextColor,
                        fontSize = baseFontSize,
                        lineHeight = 22.4.sp
                    )
                    val codeStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = baseFontSize * 0.9,
                        color = Color(0xFFE0E0E0)
                    )
                    val typography = chatMarkdownTypography(baseStyle, codeStyle, HeadingScale.SMALL)
                    val colors = chatMarkdownColors(baseStyle.color)
                    val customSelectionColors = TextSelectionColors(
                        handleColor = ChatSelectionHandleColor,
                        backgroundColor = ChatSelectionBackgroundColor
                    )

                    CompositionLocalProvider(LocalTextSelectionColors provides customSelectionColors) {
                        SelectionContainer {
                            Column {
                                parts.forEach { part ->
                                    when (part) {
                                        is MarkdownPart.TextContent -> {
                                            Markdown(
                                                content = part.content,
                                                colors = colors,
                                                typography = typography,
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
                    }
                }

                if (message.attachedFiles.isNotEmpty()) {
                    MessageAttachmentsPreview(
                        files = message.attachedFiles,
                        onOpenPath = onOpenPath,
                    )
                }

                if (clickablePaths.isNotEmpty()) {
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

            AnimatedVisibility(
                visible = isHovered,
                enter = fadeIn(animationSpec = tween(200)),
                exit = fadeOut(animationSpec = tween(200)),
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatTimestamp(message.timestamp),
                        color = ChatAssistantTimestampColor,
                        fontSize = 11.sp
                    )
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(copyBackgroundColor)
                            .hoverable(interactionSource = copyInteractionSource)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable(
                                interactionSource = copyInteractionSource,
                                indication = null,
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(message.text))
                                    copied = true
                                    val clickId = copyNonce + 1
                                    copyNonce = clickId
                                    scope.launch {
                                        delay(2000)
                                        if (copyNonce == clickId) {
                                            copied = false
                                        }
                                    }
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                            contentDescription = null,
                            tint = copyIconColor,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentActionList(actions: List<ChatAgentAction>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        actions.forEach { action ->
            AgentActionRow(action = action)
        }
    }
}

@Composable
private fun AgentActionRow(action: ChatAgentAction) {
    val text = formatToolAction(action.descriptor)
    val tint = when (action.status) {
        ChatAgentActionStatus.IN_PROGRESS -> Color.White.copy(alpha = 0.82f)
        ChatAgentActionStatus.COMPLETED -> Color.White.copy(alpha = 0.66f)
        ChatAgentActionStatus.FAILED -> Color(0xFFFFB4A8)
    }
    val containerColor = when (action.status) {
        ChatAgentActionStatus.IN_PROGRESS -> Color.White.copy(alpha = 0.07f)
        ChatAgentActionStatus.COMPLETED -> Color.White.copy(alpha = 0.05f)
        ChatAgentActionStatus.FAILED -> Color(0x33FF8A80)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(containerColor)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (action.status) {
            ChatAgentActionStatus.IN_PROGRESS -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.8.dp,
                    color = tint
                )
            }

            ChatAgentActionStatus.COMPLETED -> {
                Icon(
                    imageVector = Icons.Rounded.CheckCircleOutline,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(14.dp)
                )
            }

            ChatAgentActionStatus.FAILED -> {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        Text(
            text = text,
            color = tint,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            modifier = Modifier.weight(1f)
        )
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

private val timestampFormatter = java.text.SimpleDateFormat("HH:mm", java.util.Locale("ru", "RU"))

private fun formatTimestamp(timestamp: Long): String = 
    timestampFormatter.format(java.util.Date(timestamp))

@Composable
private fun MacTrafficLightButton(
    color: Color,
    kind: MacTrafficKind,
    onClick: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .size(MacTrafficButtonSize)
            .clip(CircleShape)
            .background(color)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .pointerHoverIcon(PointerIcon.Hand)
    ) {
        if (hovered) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val glyphColor = Color(0xCC1A1A1A)
                val centerX = size.width * 0.5f
                val centerY = size.height * 0.5f
                val half = size.minDimension * 0.22f
                val stroke = size.minDimension * 0.14f

                when (kind) {
                    MacTrafficKind.Close -> {
                        drawLine(
                            color = glyphColor,
                            start = Offset(centerX - half, centerY - half),
                            end = Offset(centerX + half, centerY + half),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            color = glyphColor,
                            start = Offset(centerX + half, centerY - half),
                            end = Offset(centerX - half, centerY + half),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round
                        )
                    }

                    MacTrafficKind.Minimize -> {
                        drawLine(
                            color = glyphColor,
                            start = Offset(centerX - half, centerY),
                            end = Offset(centerX + half, centerY),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round
                        )
                    }

                    MacTrafficKind.Maximize -> {
                        drawLine(
                            color = glyphColor,
                            start = Offset(centerX - half, centerY),
                            end = Offset(centerX + half, centerY),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            color = glyphColor,
                            start = Offset(centerX, centerY - half),
                            end = Offset(centerX, centerY + half),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopToolbarIconButton(
    size: Dp,
    onClick: () -> Unit,
    content: @Composable BoxScope.(Color) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val background by animateColorAsState(
        targetValue = if (hovered) Color(0x0FFFFFFF) else Color.Transparent
    )
    val iconTint by animateColorAsState(
        targetValue = if (hovered) Color(0x99FFFFFF) else Color(0x66FFFFFF)
    )

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .hoverable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        content(iconTint)
    }
}



@Composable
fun RealLiquidGlassCard(
    modifier: Modifier = Modifier,
    isWindowFocused: Boolean,
    cornerRadius: Dp = 24.dp,
    preset: LiquidGlassPreset = LiquidGlassPreset.Hero,
    content: @Composable BoxScope.() -> Unit
) {
    val cardShape = RoundedCornerShape(cornerRadius)
    val borderThickness = if (preset == LiquidGlassPreset.Hero) 1.dp else 1.5.dp

    val backdropAlpha by animateFloatAsState(
        targetValue = when (preset) {
            LiquidGlassPreset.Default -> if (isWindowFocused) 0.95f else 0.0f
            LiquidGlassPreset.Hero -> 1.0f
        },
        animationSpec = tween(400)
    )

    Box(
        modifier = modifier.graphicsLayer {
            shape = cardShape
            clip = true
            compositingStrategy = CompositingStrategy.Offscreen
        }
    ) {
        when (preset) {
            LiquidGlassPreset.Default -> {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = backdropAlpha))
                )
            }

            LiquidGlassPreset.Hero -> {
                Canvas(modifier = Modifier.matchParentSize()) {
                    drawRect(
                        color = Color(0xF21B1C20),
                        alpha = backdropAlpha
                    )

                    drawRect(color = Color(0xB0000000))
                }
            }
        }

        Canvas(modifier = Modifier.matchParentSize()) {
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
                                Color(0x3AFFFFFF),
                                Color(0x18FFFFFF),
                                Color(0x14FFFFFF),
                                Color(0x2AFFFFFF)
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
                        color = Color(0x08FFFFFF),
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
                    .background(Color(0x03FFFFFF))
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
