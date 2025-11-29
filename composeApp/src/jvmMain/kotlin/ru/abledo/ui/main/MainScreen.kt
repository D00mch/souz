package ru.abledo.ui.main

import androidx.compose.animation.core.*
import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import org.kodein.di.compose.localDI

// --- ЦВЕТА ---
private val GlassBackgroundTop = Color(0x509CAAB8)
private val GlassBackgroundBottom = Color(0x667D8C9B)
private val BorderGlowTop = Color(0x40FFFFFF)
private val BorderGlowBottom = Color(0x05FFFFFF)
private val TextPrimary = Color(0xD9FFFFFF)

private val WindowShape = RoundedCornerShape(22.dp)

// --- РАЗМЕРЫ (СДЕЛАЛИ КОМПАКТНЕЕ) ---
private val TopButtonSize = 22.dp
private val TopIconSize = 16.dp
private val CardMinWidth = 500.dp // Чуть уже (было 600)
private val CardMinHeight = 260.dp // Значительно меньше по высоте (было 350)
private val CardMaxHeight = 800.dp

// --- ШРИФТЫ ---
private val SfDisplay = FontFamily(
    Font("SF Pro Display", FontWeight.Medium),
    Font("SF Pro Display", FontWeight.Normal),
    Font("San Francisco", FontWeight.Medium),
    Font("Helvetica Neue", FontWeight.Medium)
)

@Composable
fun MainScreen(onOpenSettings: () -> Unit) {
    val di = localDI()
    val viewModel = viewModel { MainViewModel(di) }
    val state by viewModel.uiState.collectAsState()

    MainScreen(
        state = state,
        onToggleListening = {
            if (state.isListening) viewModel.send(MainEvent.StopListening)
            else viewModel.send(MainEvent.StartListening)
        },
        onClear = { viewModel.send(MainEvent.ClearContext) },
        onOpenSettings = onOpenSettings
    )
}

@Composable
fun MainScreen(
    state: MainState,
    onToggleListening: () -> Unit,
    onClear: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        GlassCard(
            modifier = Modifier
                .width(CardMinWidth)
                .heightIn(min = CardMinHeight, max = CardMaxHeight)
                .wrapContentHeight()
                .padding(20.dp)
        ) {
            Column(
                modifier = Modifier.wrapContentHeight()
            ) {
                // 1. ВЕРХНЯЯ ЧАСТЬ (Кнопки + Текст + Скролл)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false) // fill=false позволяет сжиматься
                ) {
                    // Кнопки (Сделали отступы компактнее)
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 16.dp, end = 16.dp) // Было 20.dp
                            .zIndex(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(onClick = onOpenSettings, modifier = Modifier.size(TopButtonSize)) {
                            Icon(Icons.Rounded.Settings, null, tint = TextPrimary.copy(alpha = 0.6f), modifier = Modifier.size(TopIconSize))
                        }
                        IconButton(onClick = onClear, modifier = Modifier.size(TopButtonSize)) {
                            Icon(Icons.Rounded.Close, null, tint = TextPrimary.copy(alpha = 0.8f), modifier = Modifier.size(TopIconSize))
                        }
                    }

                    // --- ТЕКСТОВАЯ ОБЛАСТЬ ---
                    val scrollState = rememberScrollState()
                    val textContent = state.displayedText.ifEmpty { state.statusMessage }

                    val dynamicFontSize = remember(textContent) {
                        when (textContent.length) {
                            in 0..60 -> 30.sp
                            in 61..150 -> 26.sp
                            in 151..300 -> 20.sp
                            else -> 20.sp
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 32.dp, bottom = 0.dp) // Уменьшили верхний отступ (было 50)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(max = CardMaxHeight - 150.dp)
                                .verticalScroll(scrollState)
                                .padding(horizontal = 32.dp), // Чуть меньше боковые отступы
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = textContent,
                                style = TextStyle(
                                    fontFamily = SfDisplay,
                                    fontSize = dynamicFontSize,
                                    lineHeight = dynamicFontSize * 1.3,
                                    fontWeight = FontWeight.Medium,
                                    color = TextPrimary,
                                    textAlign = TextAlign.Center,
                                    shadow = Shadow(
                                        color = Color.Black.copy(alpha = 0.2f),
                                        offset = Offset(0f, 1f),
                                        blurRadius = 5f
                                    )
                                )
                            )
                        }

                        if (scrollState.maxValue > 0) {
                            LiquidScrollbar(
                                scrollState = scrollState,
                                modifier = Modifier
                                    .padding(end = 6.dp, top = 10.dp, bottom = 10.dp)
                                    .width(4.dp)
                                    .fillMaxHeight()
                            )
                        }
                    }
                }

                // 2. НИЖНЯЯ ЧАСТЬ (Orb)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(85.dp)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { onToggleListening() }
                    ) {
                        MagicOrb(isActive = state.isListening)
                    }
                }
            }
        }
    }
}

// --- LIQUID SCROLLBAR ---
@Composable
fun LiquidScrollbar(
    scrollState: ScrollState,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val totalHeight = size.height
        val viewportHeight = totalHeight
        val contentHeight = scrollState.maxValue + viewportHeight
        val scrollbarHeight = (viewportHeight / contentHeight) * viewportHeight
        val safeScrollbarHeight = scrollbarHeight.coerceAtLeast(40f)
        val scrollProgress = scrollState.value.toFloat() / scrollState.maxValue.toFloat()
        val scrollbarY = scrollProgress * (viewportHeight - safeScrollbarHeight)

        drawRoundRect(Color(0x10FFFFFF), cornerRadius = CornerRadius(4.dp.toPx()), size = size)
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(Color(0xA0FFFFFF), Color(0x40FFFFFF))),
            topLeft = Offset(0f, scrollbarY),
            size = Size(size.width, safeScrollbarHeight),
            cornerRadius = CornerRadius(4.dp.toPx())
        )
    }
}

// --- СТЕКЛО ---
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .shadow(elevation = 0.dp, shape = WindowShape)
            .clip(WindowShape)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(GlassBackgroundTop, GlassBackgroundBottom)
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(BorderGlowTop, BorderGlowBottom)
                ),
                shape = WindowShape
            ),
        content = content
    )
}

// --- MAGIC ORB ---
@Composable
fun MagicOrb(isActive: Boolean) {
    val infiniteTransition = rememberInfiniteTransition()
    val spinDuration = if (isActive) 1500 else 8000
    val angle by infiniteTransition.animateFloat(0f, 360f, infiniteRepeatable(tween(spinDuration, easing = LinearEasing)))
    val pulseScale by infiniteTransition.animateFloat(1f, if (isActive) 1.15f else 1.05f, infiniteRepeatable(tween(if (isActive) 600 else 2500, easing = FastOutSlowInEasing), RepeatMode.Reverse))
    val color1 = if (isActive) Color(0xFF00FFFF) else Color(0xFF6366F1)
    val color2 = if (isActive) Color(0xFFFFFFFF) else Color(0xFF818CF8)

    Box(contentAlignment = Alignment.Center, modifier = Modifier.scale(pulseScale)) {
        Canvas(Modifier.size(70.dp)) { drawCircle(Brush.radialGradient(listOf(color1.copy(alpha = 0.3f), Color.Transparent), center, size.minDimension/1.5f)) }
        Canvas(Modifier.size(56.dp).rotate(angle)) { drawCircle(Brush.sweepGradient(listOf(Color.Transparent, color1.copy(0.1f), color1)), style = Stroke(3.dp.toPx())) }
        Canvas(Modifier.size(46.dp).rotate(-angle * 1.5f)) { drawCircle(Brush.sweepGradient(listOf(color2, color2.copy(0.1f), Color.Transparent)), style = Stroke(2.dp.toPx())) }
        Box(Modifier.size(35.dp).background(Brush.radialGradient(listOf(Color.White.copy(0.9f), color1), radius=100f), CircleShape))
    }
}

@Preview
@Composable
fun MainScreenPreview() {
    Box(Modifier.fillMaxSize().background(Color(0xFF336699))) {
        MainScreen(
            state = MainState(displayedText = "Compact Mode", statusMessage = "", isListening = false),
            onToggleListening = {}, onClear = {}, onOpenSettings = {}
        )
    }
}