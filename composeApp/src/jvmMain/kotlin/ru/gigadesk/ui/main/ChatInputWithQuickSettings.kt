package ru.gigadesk.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerMoveFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import kotlin.math.max
import ru.gigadesk.giga.GigaModel

private val AccentTurquoise = Color(0xFF12E0B5)
private val AccentTurquoiseDark = Color(0xFF0EA889)

private val GlassBackground = Color(0x4D000000)
private val GlassBackgroundDark = Color(0xFA111827)
private val GlassBorder = Color(0x26FFFFFF)
private val GlassBorderLight = Color(0x1AFFFFFF)
private val GlassDivider = Color(0x0FFFFFFF)

private val TextPrimary = Color(0xE6FFFFFF)
private val TextSecondary = Color(0xB3FFFFFF)
private val TextTertiary = Color(0x80FFFFFF)
private val TextDisabled = Color(0x66FFFFFF)

private val HoverBackground = Color(0x0DFFFFFF)
private val ActiveBackground = Color(0x1A12E0B5)
private val ControlTextMuted = Color(0x59FFFFFF)
private val ControlTextHover = Color(0x99FFFFFF)
private val ControlButtonSize = 36.dp
private val ControlIconSize = 18.dp
private val SendButtonInactiveBackground = Color(0x14FFFFFF)
private val SendButtonInactiveBorder = Color(0x1AFFFFFF)
private val SendButtonInactiveIcon = Color(0x66FFFFFF)
private val SendButtonActiveBorder = Color(0x6612E0B5)
private val SendButtonActiveGradient = Brush.linearGradient(
    colors = listOf(
        Color(0x4D12E0B5),
        Color(0x3312E0B5)
    )
)

private val ContextOptions = listOf(8_000, 16_000, 32_000, 64_000, 96_000, 128_000)

@Composable
internal fun ChatInputWithQuickSettings(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    isListening: Boolean,
    onToggleListening: () -> Unit,
    enabled: Boolean,
    focusRequester: FocusRequester,
    selectedModel: String,
    selectedContextSize: Int,
    onModelChange: (String) -> Unit,
    onContextChange: (Int) -> Unit,
    scrollCloseSignal: Pair<Int, Int>,
    placeholder: String = "Введите сообщение...",
    modifier: Modifier = Modifier,
) {
    val hasText = value.text.isNotBlank() && enabled
    val canToggleMic = enabled || isListening
    val containerShape = RoundedCornerShape(24.dp)
    var isModelDropdownOpen by remember { mutableStateOf(false) }
    var isContextDropdownOpen by remember { mutableStateOf(false) }
    val windowInfo = LocalWindowInfo.current

    LaunchedEffect(scrollCloseSignal, windowInfo.containerSize) {
        isModelDropdownOpen = false
        isContextDropdownOpen = false
    }

    val modelOptions = remember {
        GigaModel.entries.map { QuickOption(value = it.alias, label = it.displayName) }
    }
    val contextOptions = remember {
        ContextOptions.map { QuickOption(value = it, label = formatWithSpaces(it)) }
    }

    Box(
        modifier = modifier
            .clip(containerShape)
            .border(1.dp, GlassBorder, containerShape)
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(GlassBackground)
                .blur(14.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(GlassBackground)
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .heightIn(min = 44.dp, max = 120.dp)
                    .onPreviewKeyEvent { event ->
                        when {
                            event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed -> {
                                if (hasText) onSend()
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
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.weight(1f),
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
                        maxLines = 5,
                        cursorBrush = SolidColor(AccentTurquoise),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )
                }

                Spacer(Modifier.width(8.dp))

                VoiceToggleButton(
                    isListening = isListening,
                    enabled = canToggleMic,
                    onClick = onToggleListening
                )

                Spacer(Modifier.width(8.dp))

                SendMessageButton(
                    isActive = hasText,
                    onClick = onSend
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun VoiceToggleButton(
    isListening: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    var showTooltip by remember { mutableStateOf(false) }
    var tooltipHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val tooltipOffset = with(density) { tooltipHeightPx.toDp() + 8.dp }
    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.95f
            isHovered -> 1.05f
            else -> 1f
        },
        animationSpec = tween(150)
    )

    val iconColor = if (isListening) AccentTurquoise else Color(0x80FFFFFF)
    val background = if (isListening) Color(0x3312E0B5) else Color.Transparent
    val hoverBorderColor = if (isHovered) Color(0x669CA3AF) else Color.Transparent

    Box(
        modifier = Modifier
            .size(ControlButtonSize)
            .pointerMoveFilter(
                onEnter = {
                    showTooltip = true
                    false
                },
                onExit = {
                    showTooltip = false
                    false
                }
            )
    ) {
        AnimatedVisibility(
            visible = showTooltip,
            enter = fadeIn(animationSpec = tween(150)) + slideInVertically(
                animationSpec = tween(150),
                initialOffsetY = { 5 }
            ),
            exit = fadeOut(animationSpec = tween(150)) + slideOutVertically(
                animationSpec = tween(150),
                targetOffsetY = { 5 }
            ),
            modifier = Modifier
                .wrapContentSize(unbounded = true)
                .align(Alignment.TopCenter)
                .offset(y = -tooltipOffset)
                .zIndex(2f)
        ) {
            Box(
                modifier = Modifier
                    .onSizeChanged { tooltipHeightPx = it.height }
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
                        .background(Color(0xE6000000))
                        .blur(20.dp)
                )
                Box(
                    modifier = Modifier
                        .widthIn(min = 156.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xE6000000))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Зажать правый Option",
                        color = Color(0xE6FFFFFF),
                        fontSize = 12.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .size(ControlButtonSize)
                .scale(scale)
                .clip(CircleShape)
                .background(background)
                .border(1.dp, hoverBorderColor, CircleShape)
                .hoverable(interactionSource = interactionSource)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Mic,
                contentDescription = "Голосовой ввод",
                tint = iconColor,
                modifier = Modifier.size(ControlIconSize)
            )
        }
    }
}

@Composable
private fun SendMessageButton(
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = when {
            !isActive -> 1f
            isPressed -> 0.95f
            isHovered -> 1.05f
            else -> 1f
        },
        animationSpec = tween(150)
    )

    val backgroundBrush = if (isActive) SendButtonActiveGradient else Brush.linearGradient(
        colors = listOf(
            SendButtonInactiveBackground,
            SendButtonInactiveBackground
        )
    )
    val borderColor = if (isActive) SendButtonActiveBorder else SendButtonInactiveBorder
    val iconColor = if (isActive) AccentTurquoise else SendButtonInactiveIcon

    Box(
        modifier = Modifier
            .size(ControlButtonSize)
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
        Icon(
            imageVector = Icons.Rounded.ArrowUpward,
            contentDescription = "Отправить",
            tint = iconColor,
            modifier = Modifier.size(ControlIconSize)
        )
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
    val selectedModelLabel = modelOptions.firstOrNull { it.value == selectedModel }?.label ?: "Модель"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        QuickDropdown(
            label = selectedModelLabel,
            width = 144.dp,
            expanded = isModelDropdownOpen,
            options = modelOptions,
            selectedValue = selectedModel,
            onExpandedChange = onModelDropdownChange,
            onSelect = onModelSelect,
            menuLabel = { option -> option.label }
        )

        QuickDropdown(
            label = "Контекст",
            width = 160.dp,
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
                .clip(RoundedCornerShape(4.dp))
                .background(if (isHovered || expanded) HoverBackground else Color.Transparent)
                .hoverable(interactionSource = interactionSource)
                .clickable { onExpandedChange(!expanded) }
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                color = if (isHovered || expanded) ControlTextHover else ControlTextMuted
            )
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = "Открыть $label",
                tint = if (isHovered || expanded) ControlTextHover else ControlTextMuted,
                modifier = Modifier.size(10.dp)
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
                                        selected -> ActiveBackground
                                        itemHovered -> HoverBackground
                                        else -> Color.Transparent
                                    }
                                    val textColor = if (selected) AccentTurquoise else TextSecondary

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
