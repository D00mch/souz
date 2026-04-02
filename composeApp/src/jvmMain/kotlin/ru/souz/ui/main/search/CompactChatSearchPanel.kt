package ru.souz.ui.main.search

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.chat_search_placeholder

private const val ChatSearchAnimationDurationMillis = 200

private val ChatSearchPanelBackground = Color(0x0FFFFFFF)
private val ChatSearchPanelBorder = Color(0x14FFFFFF)
private val ChatSearchInputText = Color(0xE6FFFFFF)
private val ChatSearchPlaceholder = Color(0x4DFFFFFF)
private val ChatSearchBadgeBackground = Color(0x14FFFFFF)
private val ChatSearchBadgeBorder = Color(0x0FFFFFFF)
private val ChatSearchBadgeText = Color(0x99FFFFFF)
private val ChatSearchButton = Color(0x66FFFFFF)
private val ChatSearchButtonHoverBackground = Color(0x14FFFFFF)
private val ChatSearchButtonHover = Color(0xB3FFFFFF)

@Stable
class ChatSearchPanelState {
    var isOpen by mutableStateOf(false)
        private set

    var activationToken by mutableLongStateOf(0L)
        private set

    var selectAllOnActivate by mutableStateOf(false)
        private set

    fun open(query: String) {
        isOpen = true
        activationToken += 1
        selectAllOnActivate = query.isNotBlank()
    }

    fun close() {
        isOpen = false
        selectAllOnActivate = false
    }
}

@Composable
fun rememberChatSearchPanelState(
    resetKey: Any? = Unit,
): ChatSearchPanelState {
    val state = remember { ChatSearchPanelState() }
    LaunchedEffect(resetKey) {
        state.close()
    }
    return state
}

@Composable
internal fun CompactChatSearchPanel(
    panelState: ChatSearchPanelState,
    searchState: ChatSearchState,
    onQueryChange: (String) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isRendered by remember { mutableStateOf(panelState.isOpen) }
    var fieldValue by rememberSaveable { mutableStateOf(TextFieldValue(searchState.query)) }
    val focusRequester = remember { FocusRequester() }
    val panelWidth by animateDpAsState(
        targetValue = if (panelState.isOpen) 320.dp else 200.dp,
        animationSpec = tween(ChatSearchAnimationDurationMillis, easing = FastOutSlowInEasing),
        label = "chatSearchWidth",
    )
    val panelAlpha by animateFloatAsState(
        targetValue = if (panelState.isOpen) 1f else 0f,
        animationSpec = tween(ChatSearchAnimationDurationMillis, easing = FastOutSlowInEasing),
        label = "chatSearchAlpha",
    )
    val hasQuery = searchState.normalizedQuery.isNotEmpty()
    val hasResults = searchState.matches.isNotEmpty()
    val placeholderText = stringResource(Res.string.chat_search_placeholder)

    if (fieldValue.text != searchState.query) {
        fieldValue = TextFieldValue(searchState.query, TextRange(searchState.query.length))
    }

    LaunchedEffect(panelState.isOpen) {
        if (panelState.isOpen) {
            isRendered = true
        } else {
            delay(ChatSearchAnimationDurationMillis.toLong())
            isRendered = false
        }
    }

    LaunchedEffect(panelState.activationToken) {
        if (!panelState.isOpen) return@LaunchedEffect
        delay(60)
        focusRequester.requestFocus()
        val query = searchState.query
        fieldValue = TextFieldValue(
            text = query,
            selection = if (panelState.selectAllOnActivate && query.isNotEmpty()) {
                TextRange(0, query.length)
            } else {
                TextRange(query.length)
            },
        )
    }

    if (!isRendered) return

    Row(
        modifier = modifier
            .width(panelWidth)
            .height(32.dp)
            .alpha(panelAlpha)
            .clip(RoundedCornerShape(8.dp))
            .background(ChatSearchPanelBackground)
            .border(
                width = 1.dp,
                color = ChatSearchPanelBorder,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicTextField(
            value = fieldValue,
            onValueChange = { updated ->
                fieldValue = updated
                onQueryChange(updated.text)
            },
            singleLine = true,
            cursorBrush = SolidColor(ChatSearchInputText),
            textStyle = TextStyle(
                color = ChatSearchInputText,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    when {
                        event.type == KeyEventType.KeyDown &&
                            (event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
                            if (event.isShiftPressed) {
                                onPrevious()
                            } else {
                                onNext()
                            }
                            true
                        }

                        event.type == KeyEventType.KeyDown && event.key == Key.Escape -> {
                            onClose()
                            true
                        }

                        else -> false
                    }
                },
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxHeight(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (fieldValue.text.isEmpty()) {
                        Text(
                            text = placeholderText,
                            color = ChatSearchPlaceholder,
                            fontSize = 12.sp,
                        )
                    }
                    innerTextField()
                }
            },
        )

        if (hasQuery) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(ChatSearchBadgeBackground)
                    .border(
                        width = 1.dp,
                        color = ChatSearchBadgeBorder,
                        shape = RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = if (hasResults) {
                        "${searchState.currentIndex + 1}/${searchState.matches.size}"
                    } else {
                        "0/0"
                    },
                    color = ChatSearchBadgeText,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            SearchPanelIconButton(
                icon = Icons.Rounded.KeyboardArrowUp,
                enabled = hasResults,
                onClick = onPrevious,
            )
            SearchPanelIconButton(
                icon = Icons.Rounded.KeyboardArrowDown,
                enabled = hasResults,
                onClick = onNext,
            )
        }

        SearchPanelIconButton(
            icon = Icons.Rounded.Close,
            enabled = true,
            onClick = onClose,
        )
    }
}

@Composable
private fun SearchPanelIconButton(
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val background = if (isHovered && enabled) ChatSearchButtonHoverBackground else Color.Transparent
    val iconTint = if (isHovered && enabled) ChatSearchButtonHover else ChatSearchButton

    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(4.dp))
            .alpha(if (enabled) 1f else 0.3f)
            .background(background)
            .hoverable(interactionSource = interactionSource)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(14.dp),
        )
    }
}
