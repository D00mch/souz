@file:OptIn(ExperimentalFoundationApi::class)

package ru.gigadesk.ui.main

import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.model.DefaultMarkdownColors
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import org.kodein.di.compose.localDI
import ru.gigadesk.ui.common.ConnectionStatusNotification
import ru.gigadesk.ui.common.DraggableWindowArea
import ru.gigadesk.ui.common.ConfirmDialog
import ru.gigadesk.ui.common.ConfirmDialogType
import ru.gigadesk.ui.common.parseMarkdownContent
import ru.gigadesk.ui.common.CodeBlockWithCopy
import ru.gigadesk.ui.common.MarkdownPart
import ru.gigadesk.ui.glassColors
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.*

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


@Composable
fun MainScreen(
    onOpenSettings: () -> Unit,
    onCloseWindow: () -> Unit,
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
        onClear = onCloseWindow,
        onMinimize = onMinimizeWindow,
        onRequestNewConversation = { viewModel.send(MainEvent.RequestNewConversation) },
        onConfirmNewConversation = { viewModel.send(MainEvent.ConfirmNewConversation) },
        onDismissNewConversationDialog = { viewModel.send(MainEvent.DismissNewConversationDialog) },
        onOpenSettings = onOpenSettings,
        onStopSpeech = { viewModel.send(MainEvent.StopSpeech) },
        onShowLastText = { viewModel.send(MainEvent.ShowLastText) },
        onToggleThinkingPanel = { viewModel.send(MainEvent.ToggleThinkingPanel) },
        onShowSnack = onShowSnack,
        onUpdateChatInput = { viewModel.send(MainEvent.UpdateChatInput(it)) },
        onChatModelChange = { viewModel.send(MainEvent.UpdateChatModel(it)) },
        onChatContextSizeChange = { viewModel.send(MainEvent.UpdateChatContextSize(it)) },
        onSendChatMessage = { viewModel.send(MainEvent.SendChatMessage) },
        onClearContext = { viewModel.send(MainEvent.StopAgentJob) },
        onApproveToolPermission = { viewModel.send(MainEvent.ApproveToolPermission) },
        onRejectToolPermission = { viewModel.send(MainEvent.RejectToolPermission) },
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
    onClear: () -> Unit = {},
    onMinimize: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onStopSpeech: () -> Unit = {},
    onShowLastText: () -> Unit = {},
    onToggleThinkingPanel: () -> Unit = {},
    onShowSnack: (String) -> Unit = {},
    onUpdateChatInput: (TextFieldValue) -> Unit = {},
    onChatModelChange: (String) -> Unit = {},
    onChatContextSizeChange: (Int) -> Unit = {},
    onSendChatMessage: () -> Unit = {},
    onClearContext: () -> Unit = {},
    onApproveToolPermission: () -> Unit = {},
    onRejectToolPermission: () -> Unit = {},
    onOpenPath: (String) -> Unit = {},
) {
    val windowInfo = LocalWindowInfo.current
    val isFocused = windowInfo.isWindowFocused

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        RealLiquidGlassCard(
            modifier = Modifier.fillMaxSize(),
            isWindowFocused = isFocused
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
                        text = "Союз",
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
                        MinimalGlassButton(onClick = onMinimize) {
                            Icon(Icons.Rounded.Close, null, tint = iconTint, modifier = Modifier.size(TopIconSize))
                        }
                    }
                }
                }

                ChatModeContent(
                    messages = state.chatMessages,
                    chatPlaceholder = state.chatStartTip,
                    inputText = state.chatInputText,
                    selectedModel = state.selectedModel,
                    selectedContextSize = state.selectedContextSize,
                    isProcessing = state.isProcessing,
                    isListening = state.isListening,
                    isOnline = isOnline,
                    isSpeaking = state.isSpeaking,
                    onInputChange = onUpdateChatInput,
                    onModelChange = onChatModelChange,
                    onContextChange = onChatContextSizeChange,
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
                    title = "Начать новую беседу?",
                    message = "Текущая история сообщений будет утеряна",
                    confirmText = "Начать",
                    onConfirm = onConfirmNewConversation,
                    onDismiss = onDismissNewConversationDialog
                )
            }

            state.toolPermissionDialog?.let { dialog ->
                val paramsString = if (dialog.params.isNotEmpty()) {
                    dialog.params.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                } else null

                ConfirmDialog(
                    type = ConfirmDialogType.WARNING,
                    title = "Подтверждение действия",
                    message = dialog.description,
                    details = paramsString,
                    confirmText = "Разрешить",
                    cancelText = "Запретить",
                    onConfirm = onApproveToolPermission,
                    onDismiss = onRejectToolPermission
                )
            }
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
    inputText: TextFieldValue,
    selectedModel: String,
    selectedContextSize: Int,
    isProcessing: Boolean,
    isListening: Boolean,
    isOnline: Boolean,
    isSpeaking: Boolean,
    onInputChange: (TextFieldValue) -> Unit,
    onModelChange: (String) -> Unit,
    onContextChange: (Int) -> Unit,
    onSendMessage: () -> Unit,
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
    val speakingAssistantMessageId = remember(messages) { messages.lastOrNull { !it.isUser }?.id }

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
                        isSpeaking = isSpeaking && speakingAssistantMessageId == message.id,
                        onStopSpeech = onStopSpeech,
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
                                    text = "Обработка...",
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
            onValueChange = onInputChange,
            onSend = onSendMessage,
            onCancel = onCancelProcessing,
            isProcessing = isProcessing,
            isListening = isListening,
            onStartListening = onStartListening,
            onStopListening = onStopListening,
            enabled = !isProcessing,
            focusRequester = focusRequester,
            selectedModel = selectedModel,
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
private fun ChatBubble(
    message: ChatMessage,
    isSpeaking: Boolean,
    onStopSpeech: () -> Unit,
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
                .widthIn(max = 600.dp)
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
                val customSelectionColors = TextSelectionColors(
                    handleColor = Color(0xFFFFFFFF),
                    backgroundColor = Color(0x66FFFFFF)
                )

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
                val clickablePaths = message.finderPaths
                
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
                if (!message.isUser && isSpeaking) {
                    SpeakingWaves(
                        onClick = onStopSpeech
                    )
                }
            }
        }
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

@Composable
private fun SpeakingWaves(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.98f
            isHovered -> 1.05f
            else -> 1f
        },
        animationSpec = tween(150)
    )
    val wavesColor = Color(0xFF12E0B5)
    val transition = rememberInfiniteTransition()
    val barHeights = listOf(
        transition.animateFloat(
            initialValue = 4f,
            targetValue = 12f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800, delayMillis = 0, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            )
        ),
        transition.animateFloat(
            initialValue = 6f,
            targetValue = 16f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800, delayMillis = 100, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            )
        ),
        transition.animateFloat(
            initialValue = 4f,
            targetValue = 10f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800, delayMillis = 200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            )
        ),
        transition.animateFloat(
            initialValue = 8f,
            targetValue = 14f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800, delayMillis = 150, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            )
        ),
    )

    TooltipArea(
        delayMillis = 300,
        tooltip = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xE6000000))
                    .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Нажмите, чтобы остановить озвучку",
                    color = Color(0xF2FFFFFF),
                    fontSize = 12.sp
                )
            }
        }
    ) {
        Row(
            modifier = modifier
                .height(16.dp)
                .scale(scale)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                ),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            barHeights.forEach { animatedHeight ->
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(animatedHeight.value.dp)
                        .clip(RoundedCornerShape(percent = 100))
                        .background(wavesColor)
                )
            }
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
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val borderThickness = 1.5.dp
    val noiseBrush = rememberNoiseBrush()

    val backdropAlpha by animateFloatAsState(
        targetValue = if (isWindowFocused) 0.95f else 0.0f,
        animationSpec = tween(400)
    )

    val noiseAlpha by animateFloatAsState(
        targetValue = if (isWindowFocused) 0.25f else 0.0f,
        animationSpec = tween(400)
    )

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .background(Color.Black.copy(alpha = backdropAlpha))
        )

        if (noiseAlpha > 0f) {
            Canvas(modifier = Modifier.matchParentSize().clip(shape).alpha(noiseAlpha)) {
                drawRect(brush = noiseBrush)
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.Transparent, Color(0x80000000)),
                        radius = size.maxDimension / 1.0f
                    )
                )
            }
        }

        Canvas(modifier = Modifier.matchParentSize().clip(shape)) {
            val strokeWidth = borderThickness.toPx()
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

        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .background(Color(0x03FFFFFF))
        )

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
                    chatInputText = TextFieldValue(""),
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
                    chatInputText = TextFieldValue(""),
                    displayedText = "",
                    statusMessage = "Чат режим",
                    isListening = false
                ),
                isOnline = true
            )
        }
    }
}
