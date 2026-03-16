package ru.souz.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.PresentToAll
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.*

import androidx.compose.ui.res.painterResource as jvmPainterResource

private val WelcomeEnterEasing = CubicBezierEasing(0.25f, 0.46f, 0.45f, 0.94f)
private val WelcomeCardEasing = CubicBezierEasing(0f, 0f, 0.2f, 1f)
private val QuickActionIconContainerSize = 38.dp
private val QuickActionIconSize = 20.dp
private val QuickActionIconContainerSizeLowDpi = 42.dp
private val QuickActionIconSizeLowDpi = 24.dp
private val WelcomeLogoSize = 64.dp
private val WelcomeLogoSizeLowDpi = 76.dp

private data class QuickActionCardModel(
    val id: String,
    val icon: ImageVector,
    val labelRes: StringResource,
    val descriptionRes: StringResource,
    val gradient: Brush,
    val messageRes: StringResource,
)

private val EmptyChatQuickActions = listOf(
    QuickActionCardModel(
        id = "mail",
        icon = Icons.Filled.Mail,
        labelRes = Res.string.quick_action_mail_label,
        descriptionRes = Res.string.quick_action_mail_description,
        gradient = Brush.linearGradient(colors = listOf(Color(0xFF3B82F6), Color(0xFF22D3EE))),
        messageRes = Res.string.quick_action_mail_message,
    ),
    QuickActionCardModel(
        id = "calendar",
        icon = Icons.Filled.CalendarToday,
        labelRes = Res.string.quick_action_calendar_label,
        descriptionRes = Res.string.quick_action_calendar_description,
        gradient = Brush.linearGradient(colors = listOf(Color(0xFFF97316), Color(0xFFFBBF24))),
        messageRes = Res.string.quick_action_calendar_message,
    ),
    QuickActionCardModel(
        id = "telegram",
        icon = Icons.Filled.Forum,
        labelRes = Res.string.quick_action_telegram_label,
        descriptionRes = Res.string.quick_action_telegram_description,
        gradient = Brush.linearGradient(colors = listOf(Color(0xFF0EA5E9), Color(0xFF60A5FA))),
        messageRes = Res.string.quick_action_telegram_message,
    ),
    QuickActionCardModel(
        id = "documents",
        icon = Icons.Filled.Description,
        labelRes = Res.string.quick_action_documents_label,
        descriptionRes = Res.string.quick_action_documents_description,
        gradient = Brush.linearGradient(colors = listOf(Color(0xFF22C55E), Color(0xFF34D399))),
        messageRes = Res.string.quick_action_documents_message,
    ),
    QuickActionCardModel(
        id = "presentation",
        icon = Icons.Filled.PresentToAll,
        labelRes = Res.string.quick_action_presentation_label,
        descriptionRes = Res.string.quick_action_presentation_description,
        gradient = Brush.linearGradient(colors = listOf(Color(0xFFF43F5E), Color(0xFFF472B6))),
        messageRes = Res.string.quick_action_presentation_message,
    ),
    QuickActionCardModel(
        id = "analytics",
        icon = Icons.Filled.BarChart,
        labelRes = Res.string.quick_action_analytics_label,
        descriptionRes = Res.string.quick_action_analytics_description,
        gradient = Brush.linearGradient(colors = listOf(Color(0xFF8B5CF6), Color(0xFFC084FC))),
        messageRes = Res.string.quick_action_analytics_message,
    ),
    QuickActionCardModel(
        id = "search",
        icon = Icons.Filled.Search,
        labelRes = Res.string.quick_action_search_label,
        descriptionRes = Res.string.quick_action_search_description,
        gradient = Brush.linearGradient(colors = listOf(Color(0xFF14B8A6), Color(0xFF22D3EE))),
        messageRes = Res.string.quick_action_search_message,
    ),
    QuickActionCardModel(
        id = "browser",
        icon = Icons.Filled.Public,
        labelRes = Res.string.quick_action_browser_label,
        descriptionRes = Res.string.quick_action_browser_description,
        gradient = Brush.linearGradient(colors = listOf(Color(0xFF6366F1), Color(0xFF60A5FA))),
        messageRes = Res.string.quick_action_browser_message,
    ),
)

@Composable
internal fun EmptyChatWelcomeContent(
    modifier: Modifier = Modifier,
    onSuggestionClick: (String) -> Unit,
) {
    var show by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val isLowDpi = density.density < 1.5f
    val logoSize = if (isLowDpi) WelcomeLogoSizeLowDpi else WelcomeLogoSize

    LaunchedEffect(Unit) {
        show = true
    }

    val logoAlpha by animateFloatAsState(
        targetValue = if (show) 1f else 0f,
        animationSpec = tween(durationMillis = 500, easing = WelcomeEnterEasing),
        label = "welcome_logo_alpha",
    )
    val logoScale by animateFloatAsState(
        targetValue = if (show) 1f else 0.95f,
        animationSpec = tween(durationMillis = 500, easing = WelcomeEnterEasing),
        label = "welcome_logo_scale",
    )
    val captionAlpha by animateFloatAsState(
        targetValue = if (show) 1f else 0f,
        animationSpec = tween(durationMillis = 500, delayMillis = 100, easing = WelcomeEnterEasing),
        label = "welcome_caption_alpha",
    )
    val captionOffset by animateDpAsState(
        targetValue = if (show) 0.dp else 10.dp,
        animationSpec = tween(durationMillis = 500, delayMillis = 100, easing = WelcomeEnterEasing),
        label = "welcome_caption_offset",
    )
    val gridAlpha by animateFloatAsState(
        targetValue = if (show) 1f else 0f,
        animationSpec = tween(durationMillis = 500, delayMillis = 200, easing = WelcomeEnterEasing),
        label = "welcome_grid_alpha",
    )
    val gridOffset by animateDpAsState(
        targetValue = if (show) 0.dp else 20.dp,
        animationSpec = tween(durationMillis = 500, delayMillis = 200, easing = WelcomeEnterEasing),
        label = "welcome_grid_offset",
    )

    val captionOffsetPx = with(density) { captionOffset.toPx() }
    val gridOffsetPx = with(density) { gridOffset.toPx() }

    Box(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 800.dp)
        ) {
            val columns = if (maxWidth < 560.dp) 2 else 4
            val rows = (EmptyChatQuickActions.size + columns - 1) / columns
            val cardHeight = 124.dp
            val gridHeight = (cardHeight * rows) + (12.dp * (rows - 1))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(logoSize)
                        .graphicsLayer {
                            alpha = logoAlpha
                            scaleX = logoScale
                            scaleY = logoScale
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = jvmPainterResource("icon-light.png"),
                        contentDescription = stringResource(Res.string.welcome_logo_content_desc),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = stringResource(Res.string.welcome_quick_actions_subtitle),
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 14.sp,
                    lineHeight = 19.6.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .widthIn(max = 448.dp)
                        .graphicsLayer {
                            alpha = captionAlpha
                            translationY = captionOffsetPx
                        }
                )

                Spacer(modifier = Modifier.height(32.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            alpha = gridAlpha
                            translationY = gridOffsetPx
                        }
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        userScrollEnabled = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(gridHeight)
                    ) {
                        itemsIndexed(
                            items = EmptyChatQuickActions,
                            key = { _, item -> item.id }
                        ) { index, item ->
                            val message = stringResource(item.messageRes)
                            QuickActionCard(
                                item = item,
                                index = index,
                                height = cardHeight,
                                onClick = { onSuggestionClick(message) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickActionCard(
    item: QuickActionCardModel,
    index: Int,
    height: Dp,
    onClick: () -> Unit,
) {
    var showCard by remember(item.id) { mutableStateOf(false) }
    val density = LocalDensity.current
    val isLowDpi = density.density < 1.5f
    val iconContainerSize = if (isLowDpi) QuickActionIconContainerSizeLowDpi else QuickActionIconContainerSize
    val iconSize = if (isLowDpi) QuickActionIconSizeLowDpi else QuickActionIconSize
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    LaunchedEffect(item.id) {
        delay(300L + index * 50L)
        showCard = true
    }

    val revealAlpha by animateFloatAsState(
        targetValue = if (showCard) 1f else 0f,
        animationSpec = tween(durationMillis = 250, easing = WelcomeCardEasing),
        label = "quick_action_card_alpha_${item.id}",
    )
    val offsetY by animateDpAsState(
        targetValue = if (showCard) 0.dp else 10.dp,
        animationSpec = tween(durationMillis = 250, easing = WelcomeCardEasing),
        label = "quick_action_card_offset_${item.id}",
    )
    val cardBackground by animateColorAsState(
        targetValue = if (isHovered) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.05f),
        animationSpec = tween(durationMillis = 300, easing = WelcomeCardEasing),
        label = "quick_action_card_bg_${item.id}",
    )
    val cardBorder by animateColorAsState(
        targetValue = if (isHovered) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.06f),
        animationSpec = tween(durationMillis = 300, easing = WelcomeCardEasing),
        label = "quick_action_card_border_${item.id}",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (isHovered) 1.1f else 1f,
        animationSpec = tween(durationMillis = 300, easing = WelcomeCardEasing),
        label = "quick_action_icon_scale_${item.id}",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer {
                alpha = revealAlpha
                translationY = with(density) { offsetY.toPx() }
            }
            .clip(RoundedCornerShape(16.dp))
            .background(cardBackground)
            .border(1.dp, cardBorder, RoundedCornerShape(16.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(16.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .size(iconContainerSize)
                .clip(RoundedCornerShape(12.dp))
                .background(item.gradient)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(iconSize)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(item.labelRes),
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 13.sp,
            lineHeight = 15.6.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(item.descriptionRes),
            color = Color.White.copy(alpha = 0.3f),
            fontSize = 11.sp,
            lineHeight = 13.2.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
