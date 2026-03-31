package ru.souz.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.delay
import kotlin.math.max
import ru.souz.llms.LLMModel
import ru.souz.service.permissions.MacAppEnvironment
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

private val AccentTurquoise = Color(0xFF12E0B5)
private val AccentTurquoiseDark = Color(0xFF0EA889)

private val GlassBackground = Color(0xD9202226)
private val GlassBackgroundDark = Color(0xE61F2026)
private val GlassBorder = Color(0x1AFFFFFF)
private val GlassBorderLight = Color(0x1AFFFFFF)
private val GlassDivider = Color(0x14FFFFFF)

private val TextPrimary = Color(0xE6FFFFFF)
private val TextSecondary = Color(0x99FFFFFF)
private val TextTertiary = Color(0x66FFFFFF)
private val TextDisabled = Color(0x40FFFFFF)

private val HoverBackground = Color(0x0DFFFFFF)
private val ActiveBackground = Color(0x1A12E0B5)
private val SelectMenuSelectedBackground = Color(0x2E3F434A)
private val SelectMenuSelectedText = Color(0xE6FFFFFF)
private val ControlTextMuted = Color(0x80FFFFFF)
private val ControlTextHover = Color(0xB3FFFFFF)
private val ControlButtonSize = 32.dp
private val ControlIconSize = 16.dp
private val VoiceButtonSize = 32.dp
private val VoiceIconSize = 16.dp
private val StopIconSize = 10.dp
private val SendButtonInactiveBackground = Color(0x0FFFFFFF)
private val SendButtonInactiveBorder = Color(0x00000000)
private val SendButtonInactiveIcon = Color(0x33FFFFFF)
private val SendButtonActiveBorder = Color(0x26FFFFFF)
private val SendButtonActiveIcon = Color(0xFF1E2228)
private val SendButtonActiveGlow = Color(0x4DFFFFFF)
private val StopButtonBackground = Color(0xE61E1E28)
private val StopButtonBorder = Color(0x26FFFFFF)
private val StopButtonIcon = Color(0xE6FFFFFF)
private val StopButtonPulseRing = Color(0x33FFFFFF)
private val ControlTooltipBackground = Color(0xE6000000)
private val ControlTooltipBorder = Color(0x33FFFFFF)
private val VoiceStopColor = Color(0xFFEF4444)
private val VoiceStopBackground = Color(0x33EF4444)
private val VoiceStopBorder = Color(0x66EF4444)
private val VoiceHoverBorder = Color.Transparent
private val VoiceListeningBackground = Color(0x0FFFFFFF)
private val VoiceIdleIcon = Color(0x4DFFFFFF)
private val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private val BounceEasing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
private val SendButtonActiveGradient = Brush.linearGradient(
    colors = listOf(
        Color(0xFFFFFFFF),
        Color(0xFFF0F2F6)
    )
)

private val ContextOptions = listOf(8_000, 16_000, 32_000, 64_000, 96_000, 128_000)

@Composable
internal fun ChatInputWithQuickSettings(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    attachedFiles: List<ChatAttachedFile>,
    onAttachClick: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    isFileDragActive: Boolean,
    isProcessing: Boolean,
    isListening: Boolean,
    speakingMessageId: String?,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onStopSpeaking: () -> Unit,
    enabled: Boolean,
    focusRequester: FocusRequester,
    selectedModel: String,
    availableModelAliases: List<String>,
    selectedContextSize: Int,
    onModelChange: (String) -> Unit,
    onContextChange: (Int) -> Unit,
    scrollCloseSignal: Pair<Int, Int>,
    placeholder: String = stringResource(Res.string.chat_input_placeholder),
    modifier: Modifier = Modifier,
) {
    val hasText = value.text.isNotBlank()
    val hasAttachments = attachedFiles.isNotEmpty()
    val hasSendPayload = (hasText || hasAttachments) && enabled
    val canSendOrCancel = hasSendPayload || isProcessing
    val canToggleMic = enabled || isListening || speakingMessageId != null
    val containerShape = RoundedCornerShape(16.dp)
    var isModelDropdownOpen by remember { mutableStateOf(false) }
    var isContextDropdownOpen by remember { mutableStateOf(false) }
    val windowInfo = LocalWindowInfo.current

    LaunchedEffect(scrollCloseSignal, windowInfo.containerSize) {
        isModelDropdownOpen = false
        isContextDropdownOpen = false
    }

    val modelOptions = remember(availableModelAliases) {
        availableModelAliases.mapNotNull { alias ->
            LLMModel.entries.firstOrNull { it.alias == alias }?.let { model ->
                QuickOption(value = model.alias, label = model.displayName)
            }
        }
    }
    val contextOptions = remember {
        ContextOptions.map { QuickOption(value = it, label = formatWithSpaces(it)) }
    }

    Box(
        modifier = modifier
            .clip(containerShape)
            .border(
                1.dp,
                if (isFileDragActive) Color(0x6612E0B5) else GlassBorder,
                containerShape
            )
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(GlassBackground)
                .blur(40.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Transparent)
        ) {
            QuickSettingsPanel(
                modelOptions = modelOptions,
                contextOptions = contextOptions,
                selectedModel = selectedModel,
                selectedContextSize = selectedContextSize,
                isModelDropdownOpen = isModelDropdownOpen,
                isContextDropdownOpen = isContextDropdownOpen,
                onModelDropdownChange = { expanded ->
                    isModelDropdownOpen = expanded
                    if (expanded) isContextDropdownOpen = false
                },
                onContextDropdownChange = { expanded ->
                    isContextDropdownOpen = expanded
                    if (expanded) isModelDropdownOpen = false
                },
                onModelSelect = {
                    onModelChange(it.value)
                    isModelDropdownOpen = false
                },
                onContextSelect = {
                    onContextChange(it.value)
                    isContextDropdownOpen = false
                }
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(1.dp)
                    .background(GlassDivider)
            )

            AttachedFilesPreview(
                files = attachedFiles,
                onRemove = onRemoveAttachment,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)
                    .heightIn(min = 44.dp, max = 160.dp)
                    .onPreviewKeyEvent { event ->
                        when {
                            event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed -> {
                                if (hasSendPayload) onSend()
                                true
                            }

                            event.type == KeyEventType.KeyDown && event.key == Key.Enter && event.isShiftPressed -> {
                                val cursorPos = value.selection.start
                                val newText = value.text.substring(0, cursorPos) + "\n" + value.text.substring(cursorPos)
                                onValueChange(TextFieldValue(newText, TextRange(cursorPos + 1)))
                                true
                            }

                            else -> false
                        }
                    },
                verticalAlignment = Alignment.Bottom
            ) {
                Box(modifier = Modifier.padding(bottom = 2.dp)) {
                    AttachFilesButton(
                        enabled = enabled,
                        filesCount = attachedFiles.size,
                        isDragActive = isFileDragActive,
                        onClick = onAttachClick
                    )
                }

                Spacer(Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.text.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = TextDisabled,
                            fontSize = 14.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        enabled = enabled,
                        textStyle = TextStyle(
                            color = TextPrimary,
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        ),
                        singleLine = false,
                        maxLines = 8,
                        cursorBrush = SolidColor(AccentTurquoise),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )
                }

                Spacer(Modifier.width(8.dp))

                Row(
                    modifier = Modifier.padding(bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    VoiceToggleButton(
                        isListening = isListening,
                        speakingMessageId = speakingMessageId,
                        enabled = canToggleMic,
                        onPressStart = onStartListening,
                        onPressEnd = onStopListening,
                        onStopSpeaking = onStopSpeaking,
                    )

                    SendMessageButton(
                        isActive = canSendOrCancel,
                        isProcessing = isProcessing,
                        onClick = {
                            if (isProcessing) onCancel()
                            else onSend()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachFilesButton(
    enabled: Boolean,
    filesCount: Int,
    isDragActive: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val hasFiles = filesCount > 0 || isDragActive
    val scale by animateFloatAsState(
        targetValue = when {
            !enabled -> 1f
            isPressed -> 0.95f
            isHovered -> 1.05f
            else -> 1f
        },
        animationSpec = tween(200, easing = EaseInOut)
    )

    val backgroundColor = when {
        !enabled -> Color.Transparent
        isPressed -> Color(0x14FFFFFF)
        isHovered || hasFiles -> Color(0x0FFFFFFF)
        else -> Color.Transparent
    }
    val borderColor = when {
        !enabled -> Color.Transparent
        else -> Color.Transparent
    }
    val iconColor = when {
        !enabled -> Color(0x2EFFFFFF)
        isHovered -> Color(0x80FFFFFF)
        else -> Color(0x4DFFFFFF)
    }

    Box(
        modifier = Modifier.size(ControlButtonSize)
    ) {
        ControlTooltip(visible = isHovered, text = stringResource(Res.string.tooltip_attach_file))

        Box(
            modifier = Modifier
                .size(ControlButtonSize)
                .scale(scale)
                .clip(CircleShape)
                .background(backgroundColor)
                .border(1.dp, borderColor, CircleShape)
                .hoverable(interactionSource = interactionSource)
                .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.AttachFile,
                contentDescription = stringResource(Res.string.content_desc_attach_file),
                tint = iconColor,
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer(rotationZ = -45f)
            )
        }

        AnimatedVisibility(
            visible = filesCount > 0,
            enter = fadeIn(animationSpec = tween(200)) + scaleIn(
                animationSpec = tween(200, easing = BounceEasing),
                initialScale = 0f
            ),
            exit = fadeOut(animationSpec = tween(150)) + scaleOut(
                animationSpec = tween(150),
                targetScale = 0f
            ),
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Box(
                modifier = Modifier
                    .offset(x = 4.dp, y = (-4).dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(AccentTurquoise)
                    .border(2.dp, Color(0xFF141820), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                val badgeText = if (filesCount > 9) "9+" else filesCount.toString()
                Text(
                    text = badgeText,
                    color = Color(0xFF141820),
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AttachedFilesPreview(
    files: List<ChatAttachedFile>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (files.isEmpty()) return

    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color(0x14141820))
            .border(1.dp, Color(0x1AFFFFFF), shape)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            files.forEachIndexed { index, file ->
                AnimatedAttachmentItem(index = index, key = file.path) {
                    if (file.type == ChatAttachmentType.IMAGE && file.thumbnailBytes != null) {
                        ImageAttachmentItem(file = file, onRemove = onRemove)
                    } else {
                        FileAttachmentItem(file = file, onRemove = onRemove)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimatedAttachmentItem(
    index: Int,
    key: String,
    content: @Composable () -> Unit,
) {
    var visible by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        delay(index * 50L)
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(250)) + slideInVertically(
            animationSpec = tween(250),
            initialOffsetY = { -8 }
        ),
        exit = fadeOut(animationSpec = tween(200)) + slideOutHorizontally(
            animationSpec = tween(200),
            targetOffsetX = { 20 }
        ) + scaleOut(animationSpec = tween(200), targetScale = 0.8f)
    ) {
        content()
    }
}

@Composable
private fun FileAttachmentItem(
    file: ChatAttachedFile,
    onRemove: (String) -> Unit,
) {
    val style = chatAttachmentUiStyle(file.type)
    val shape = RoundedCornerShape(8.dp)

    Row(
        modifier = Modifier
            .widthIn(max = 280.dp)
            .clip(shape)
            .background(Color(0x0AFFFFFF))
            .border(1.dp, Color(0x1AFFFFFF), shape)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(style.background),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = style.icon,
                contentDescription = null,
                tint = style.iconTint,
                modifier = Modifier.size(16.dp)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .widthIn(min = 0.dp)
        ) {
            Text(
                text = file.displayName,
                fontSize = 12.sp,
                color = Color(0xCCFFFFFF),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatAttachmentFileSize(file.sizeBytes),
                fontSize = 10.sp,
                color = Color(0x66FFFFFF),
                maxLines = 1
            )
        }

        AttachmentRemoveButton(
            size = 20.dp,
            corner = RoundedCornerShape(4.dp),
            iconSize = 12.dp,
            onClick = { onRemove(file.path) }
        )
    }
}

@Composable
private fun ImageAttachmentItem(
    file: ChatAttachedFile,
    onRemove: (String) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val overlayAlpha by animateFloatAsState(
        targetValue = if (isHovered) 1f else 0f,
        animationSpec = tween(200)
    )
    val bitmap = remember(file.thumbnailBytes) { decodeAttachmentThumbnail(file.thumbnailBytes) }
    val imageStyle = chatAttachmentUiStyle(ChatAttachmentType.IMAGE)

    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x0AFFFFFF))
            .hoverable(interactionSource = interactionSource)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = file.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(imageStyle.background),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = imageStyle.icon,
                    contentDescription = null,
                    tint = imageStyle.iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Color.Black.copy(alpha = 0.4f * overlayAlpha))
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .alpha(overlayAlpha)
        ) {
            AttachmentRemoveButton(
                size = 20.dp,
                corner = CircleShape,
                iconSize = 10.dp,
                hoveredBackground = Color(0xFFFF4444),
                defaultBackground = Color(0xCC000000),
                defaultBorder = Color(0x26FFFFFF),
                hoveredIconColor = Color.White,
                defaultIconColor = Color.White,
                onClick = { onRemove(file.path) }
            )
        }
    }
}

@Composable
private fun AttachmentRemoveButton(
    size: Dp,
    corner: Shape,
    iconSize: Dp,
    hoveredBackground: Color = Color(0x33FF4444),
    defaultBackground: Color = Color.Transparent,
    defaultBorder: Color = Color.Transparent,
    hoveredIconColor: Color = Color(0xFFFF4444),
    defaultIconColor: Color = Color(0x66FFFFFF),
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.9f
            isHovered -> 1.1f
            else -> 1f
        },
        animationSpec = tween(200)
    )

    Box(
        modifier = Modifier
            .size(size)
            .scale(scale)
            .clip(corner)
            .background(if (isHovered) hoveredBackground else defaultBackground)
            .border(1.dp, defaultBorder, corner)
            .hoverable(interactionSource = interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = stringResource(Res.string.content_desc_remove_attachment),
            tint = if (isHovered) hoveredIconColor else defaultIconColor,
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun VoiceToggleButton(
    isListening: Boolean,
    speakingMessageId: String?,
    enabled: Boolean,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    onStopSpeaking: () -> Unit,
) {
    val isSpeaking = speakingMessageId != null
    val canInteract = enabled || isSpeaking
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    var isPressing by remember { mutableStateOf(false) }
    LaunchedEffect(isSpeaking) {
        if (isSpeaking) isPressing = false
    }
    val isButtonPressed = isPressing || isPressed
    val scale by animateFloatAsState(
        targetValue = when {
            isButtonPressed -> 0.95f
            isHovered -> 1.05f
            else -> 1f
        },
        animationSpec = tween(150),
        label = "voiceScale"
    )
    val iconColor by animateColorAsState(
        targetValue = when {
            isSpeaking -> VoiceStopColor
            isListening -> Color(0xB3FFFFFF)
            isHovered -> Color(0x80FFFFFF)
            else -> VoiceIdleIcon
        },
        animationSpec = tween(220),
        label = "voiceIconColor"
    )
    val background by animateColorAsState(
        targetValue = when {
            isSpeaking -> VoiceStopBackground
            isListening -> VoiceListeningBackground
            isHovered -> Color(0x0FFFFFFF)
            else -> Color.Transparent
        },
        animationSpec = tween(220),
        label = "voiceBackground"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isSpeaking -> VoiceStopBorder
            else -> Color.Transparent
        },
        animationSpec = tween(220),
        label = "voiceBorder"
    )
    val pulseTransition = rememberInfiniteTransition(label = "voicePulse")
    val pulseProgress by pulseTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_500, easing = EaseInOut),
            repeatMode = RepeatMode.Restart
        ),
        label = "voicePulseProgress"
    )
    val tooltipText = if (isSpeaking) {
        stringResource(Res.string.tooltip_stop_speech)
    } else if (MacAppEnvironment.isSandboxed) {
        stringResource(Res.string.tooltip_hold_mic_button)
    } else {
        stringResource(Res.string.tooltip_hold_option)
    }

    Box(modifier = Modifier.size(VoiceButtonSize)) {
        ControlTooltip(visible = isHovered, text = tooltipText)

        if (isSpeaking) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val spread = 0.22f * pulseProgress
                        scaleX = 1f + spread
                        scaleY = 1f + spread
                        alpha = 1f - pulseProgress
                    }
                    .border(2.dp, VoiceStopBorder, CircleShape)
            )
        }

        Box(
            modifier = Modifier
                .size(VoiceButtonSize)
                .scale(scale)
                .clip(CircleShape)
                .background(background)
                .border(1.dp, borderColor, CircleShape)
                .hoverable(interactionSource = interactionSource)
                .pointerHoverIcon(if (canInteract) PointerIcon.Hand else PointerIcon.Default)
                .then(
                    if (isSpeaking) {
                        Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onStopSpeaking
                        )
                    } else {
                        Modifier.pointerInput(enabled) {
                            detectTapGestures(
                                onPress = {
                                    if (!enabled) return@detectTapGestures
                                    isPressing = true
                                    onPressStart()
                                    try {
                                        tryAwaitRelease()
                                    } finally {
                                        isPressing = false
                                        onPressEnd()
                                    }
                                }
                            )
                        }
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = isSpeaking,
                transitionSpec = {
                    (
                        fadeIn(animationSpec = tween(220)) + scaleIn(
                            animationSpec = tween(220),
                            initialScale = 0.85f
                        )
                        ) togetherWith (
                        fadeOut(animationSpec = tween(220)) + scaleOut(
                            animationSpec = tween(220),
                            targetScale = 0.85f
                        )
                    )
                },
                label = "voiceIconSwap"
            ) { speaking ->
                Icon(
                    imageVector = if (speaking) {
                        Icons.AutoMirrored.Rounded.VolumeOff
                    } else {
                        Icons.Rounded.Mic
                    },
                    contentDescription = stringResource(Res.string.content_desc_voice_input),
                    tint = iconColor,
                    modifier = Modifier.size(VoiceIconSize)
                )
            }
        }
    }
}

@Composable
private fun SendMessageButton(
    isActive: Boolean,
    isProcessing: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val showTooltip = isHovered
    val tooltipText = if (isProcessing) stringResource(Res.string.tooltip_stop_request) else stringResource(Res.string.tooltip_send_enter)
    val scale by animateFloatAsState(
        targetValue = when {
            !isActive -> 1f
            isPressed -> 0.95f
            isHovered -> 1.05f
            else -> 1f
        },
        animationSpec = tween(150)
    )
    val stopRotation by animateFloatAsState(
        targetValue = if (isProcessing) 0f else -90f,
        animationSpec = tween(220)
    )
    val pulseTransition = rememberInfiniteTransition()
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 2_000
                1f at 0 using EaseInOut
                1.15f at 1_000 using EaseInOut
                1f at 2_000
            },
            repeatMode = RepeatMode.Restart
        )
    )
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 2_000
                0.3f at 0 using EaseInOut
                0f at 1_000 using EaseInOut
                0.3f at 2_000
            },
            repeatMode = RepeatMode.Restart
        )
    )

    val backgroundBrush = when {
        isProcessing -> Brush.linearGradient(colors = listOf(StopButtonBackground, StopButtonBackground))
        isActive -> SendButtonActiveGradient
        else -> Brush.linearGradient(
            colors = listOf(
                SendButtonInactiveBackground,
                SendButtonInactiveBackground
            )
        )
    }
    val borderColor = when {
        isProcessing -> StopButtonBorder
        isActive -> SendButtonActiveBorder
        else -> SendButtonInactiveBorder
    }
    val iconColor = when {
        isProcessing -> StopButtonIcon
        isActive -> SendButtonActiveIcon
        else -> SendButtonInactiveIcon
    }

    Box(
        modifier = Modifier.size(ControlButtonSize)
    ) {
        ControlTooltip(visible = showTooltip, text = tooltipText)

        if (isProcessing) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                        alpha = pulseAlpha
                    }
                    .border(2.dp, StopButtonPulseRing, CircleShape)
            )
        }

        Box(
            modifier = Modifier
                .size(ControlButtonSize)
                .shadow(
                    elevation = if (isActive && !isProcessing) 10.dp else 0.dp,
                    shape = CircleShape,
                    ambientColor = SendButtonActiveGlow,
                    spotColor = SendButtonActiveGlow,
                    clip = false
                )
                .scale(scale)
                .clip(CircleShape)
                .background(backgroundBrush)
                .border(1.dp, borderColor, CircleShape)
                .hoverable(interactionSource = interactionSource)
                .pointerHoverIcon(if (isActive) PointerIcon.Hand else PointerIcon.Default)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = isActive,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = !isProcessing,
                enter = fadeIn(animationSpec = tween(150)) + scaleIn(
                    animationSpec = tween(150),
                    initialScale = 0.85f
                ),
                exit = fadeOut(animationSpec = tween(150)) + scaleOut(
                    animationSpec = tween(150),
                    targetScale = 0.85f
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.ArrowUpward,
                    contentDescription = stringResource(Res.string.content_desc_send),
                    tint = iconColor,
                    modifier = Modifier.size(ControlIconSize)
                )
            }

            AnimatedVisibility(
                visible = isProcessing,
                enter = fadeIn(animationSpec = tween(200)) + scaleIn(
                    animationSpec = tween(200),
                    initialScale = 0.5f
                ),
                exit = fadeOut(animationSpec = tween(150)) + scaleOut(
                    animationSpec = tween(150),
                    targetScale = 0.5f
                )
            ) {
                Box(
                    modifier = Modifier
                        .size(StopIconSize)
                        .graphicsLayer { rotationZ = stopRotation }
                        .background(iconColor, RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

@Composable
private fun ControlTooltip(
    visible: Boolean,
    text: String,
) {
    val popupTransitionState = remember { MutableTransitionState(false) }
    val density = LocalDensity.current
    val popupPositionProvider = remember(density) {
        CenteredAboveAnchorPopupPositionProvider(gapPx = with(density) { 8.dp.roundToPx() })
    }

    LaunchedEffect(visible) {
        popupTransitionState.targetState = visible
    }

    if (popupTransitionState.currentState || popupTransitionState.targetState) {
        Popup(
            popupPositionProvider = popupPositionProvider,
            onDismissRequest = {},
            properties = PopupProperties(
                focusable = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        ) {
            AnimatedVisibility(
                visibleState = popupTransitionState,
                enter = fadeIn(animationSpec = tween(150)) + slideInVertically(
                    animationSpec = tween(150),
                    initialOffsetY = { 5 }
                ),
                exit = fadeOut(animationSpec = tween(150)) + slideOutVertically(
                    animationSpec = tween(150),
                    targetOffsetY = { 5 }
                )
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .shadow(
                            elevation = 12.dp,
                            shape = RoundedCornerShape(12.dp),
                            ambientColor = Color(0x4D000000),
                            spotColor = Color(0x4D000000)
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(ControlTooltipBackground)
                            .blur(20.dp)
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(ControlTooltipBackground)
                            .border(1.dp, ControlTooltipBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = text,
                            color = Color(0xE6FFFFFF),
                            fontSize = 12.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickSettingsPanel(
    modelOptions: List<QuickOption<String>>,
    contextOptions: List<QuickOption<Int>>,
    selectedModel: String,
    selectedContextSize: Int,
    isModelDropdownOpen: Boolean,
    isContextDropdownOpen: Boolean,
    onModelDropdownChange: (Boolean) -> Unit,
    onContextDropdownChange: (Boolean) -> Unit,
    onModelSelect: (QuickOption<String>) -> Unit,
    onContextSelect: (QuickOption<Int>) -> Unit,
) {
    val selectedModelLabel = modelOptions.firstOrNull { it.value == selectedModel }?.label ?: stringResource(Res.string.label_model_default)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        QuickDropdown(
            label = selectedModelLabel,
            width = 155.dp,
            expanded = isModelDropdownOpen,
            options = modelOptions,
            selectedValue = selectedModel,
            onExpandedChange = onModelDropdownChange,
            onSelect = onModelSelect,
            menuLabel = { option -> option.label },
            leadingIcon = Icons.Rounded.AutoAwesome,
        )

        QuickDropdown(
            label = stringResource(Res.string.label_context),
            width = 73.dp,
            expanded = isContextDropdownOpen,
            options = contextOptions,
            selectedValue = selectedContextSize,
            onExpandedChange = onContextDropdownChange,
            onSelect = onContextSelect,
        )
    }
}

@Composable
private fun <T> QuickDropdown(
    label: String,
    width: Dp,
    expanded: Boolean,
    options: List<QuickOption<T>>,
    selectedValue: T,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (QuickOption<T>) -> Unit,
    menuMaxHeight: Dp = 240.dp,
    menuLabel: (QuickOption<T>) -> String = { it.label },
    leadingIcon: ImageVector? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val popupTransitionState = remember { MutableTransitionState(false) }
    val selectedIndex = remember(options, selectedValue) {
        options.indexOfFirst { it.value == selectedValue }
    }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val popupPositionProvider = remember(density) {
        AboveAnchorPopupPositionProvider(gapPx = with(density) { 8.dp.roundToPx() })
    }

    LaunchedEffect(expanded, selectedIndex) {
        popupTransitionState.targetState = expanded
        if (expanded && selectedIndex >= 0) {
            listState.scrollToItem(selectedIndex)
        }
    }

    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (isHovered || expanded) Color(0x1AFFFFFF) else Color(0x0FFFFFFF))
                .hoverable(interactionSource = interactionSource)
                .clickable { onExpandedChange(!expanded) }
                .padding(horizontal = 7.dp, vertical = 1.4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = if (isHovered || expanded) ControlTextHover else ControlTextMuted,
                    modifier = Modifier.size(8.dp)
                )
            }
            Text(
                text = label,
                fontSize = 11.sp,
                color = if (isHovered || expanded) ControlTextHover else ControlTextMuted
            )
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = stringResource(Res.string.content_desc_open_label).format(label),
                tint = if (isHovered || expanded) ControlTextHover else ControlTextMuted,
                modifier = Modifier.size(8.dp)
            )
        }

        if (popupTransitionState.currentState || popupTransitionState.targetState) {
            Popup(
                popupPositionProvider = popupPositionProvider,
                onDismissRequest = { onExpandedChange(false) },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true
                )
            ) {
                AnimatedVisibility(
                    visibleState = popupTransitionState,
                    enter = fadeIn(animationSpec = tween(120)) + slideInVertically(
                        animationSpec = tween(120),
                        initialOffsetY = { 8 }
                    ),
                    exit = fadeOut(animationSpec = tween(120)) + slideOutVertically(
                        animationSpec = tween(120),
                        targetOffsetY = { 8 }
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .zIndex(1000f)
                            .widthIn(min = width, max = width)
                            .shadow(32.dp, RoundedCornerShape(8.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clip(RoundedCornerShape(8.dp))
                                .background(GlassBackgroundDark)
                                .blur(10.dp)
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(GlassBackgroundDark)
                                .border(1.dp, GlassBorderLight, RoundedCornerShape(8.dp))
                        ) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = menuMaxHeight)
                                    .padding(vertical = 4.dp),
                                state = listState,
                            ) {
                                itemsIndexed(options) { _, option ->
                                    val selected = option.value == selectedValue
                                    val itemInteractionSource = remember { MutableInteractionSource() }
                                    val itemHovered by itemInteractionSource.collectIsHoveredAsState()
                                    val backgroundColor = when {
                                        selected -> SelectMenuSelectedBackground
                                        itemHovered -> HoverBackground
                                        else -> Color.Transparent
                                    }
                                    val textColor = if (selected) SelectMenuSelectedText else TextSecondary

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(backgroundColor)
                                            .hoverable(interactionSource = itemInteractionSource)
                                            .clickable { onSelect(option) }
                                            .padding(horizontal = 8.dp, vertical = 12.dp)
                                    ) {
                                        Text(
                                            text = menuLabel(option),
                                            color = textColor,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private class AboveAnchorPopupPositionProvider(
    private val gapPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val popupX = when (layoutDirection) {
            LayoutDirection.Ltr -> anchorBounds.left
            LayoutDirection.Rtl -> anchorBounds.right - popupContentSize.width
        }
        val maxAllowedX = max(0, windowSize.width - popupContentSize.width)
        val clampedX = popupX.coerceIn(0, maxAllowedX)
        val popupY = (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0)
        return IntOffset(clampedX, popupY)
    }
}

private class CenteredAboveAnchorPopupPositionProvider(
    private val gapPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val anchorWidth = anchorBounds.right - anchorBounds.left
        val centeredX = anchorBounds.left + (anchorWidth - popupContentSize.width) / 2
        val maxAllowedX = max(0, windowSize.width - popupContentSize.width)
        val clampedX = centeredX.coerceIn(0, maxAllowedX)
        val popupY = (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0)
        return IntOffset(clampedX, popupY)
    }
}

private data class QuickOption<T>(
    val value: T,
    val label: String,
)

private fun formatWithSpaces(value: Int): String = value
    .toString()
    .reversed()
    .chunked(3)
    .joinToString(" ")
    .reversed()
