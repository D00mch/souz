package ru.abledo.ui.main

import androidx.compose.animation.core.*
import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import org.kodein.di.compose.localDI
import ru.abledo.ui.GlassTheme // <--- Импорт нашей темы

// --- ЛЕЙАУТ КОНСТАНТЫ (Их можно оставить здесь, это размеры элементов) ---
private val TopButtonSize = 22.dp
private val TopIconSize = 16.dp
private val BaseWidth = 500.dp
private val BaseHeight = 260.dp
private val MaxHeight = 900.dp
private val MaxWidth = 650.dp

@Composable
fun MainScreen(
    onOpenSettings: () -> Unit,
    onResizeRequest: (DpSize) -> Unit
) {
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
        onOpenSettings = onOpenSettings,
        onResizeRequest = onResizeRequest
    )
}

@Composable
fun MainScreen(
    state: MainState,
    onToggleListening: () -> Unit,
    onClear: () -> Unit,
    onOpenSettings: () -> Unit,
    onResizeRequest: (DpSize) -> Unit
) {
    val textContent = state.displayedText.ifEmpty { state.statusMessage }

    // --- ЛОГИКА РЕСАЙЗА ---
    LaunchedEffect(textContent) {
        val textLen = textContent.length
        val calculatedHeight = (260 + (textLen * 0.8)).dp
        var targetWidth = BaseWidth
        var targetHeight = calculatedHeight

        if (targetHeight > MaxHeight) {
            targetHeight = MaxHeight
            targetWidth = MaxWidth
        } else if (targetHeight < BaseHeight) {
            targetHeight = BaseHeight
            targetWidth = BaseWidth
        }
        onResizeRequest(DpSize(targetWidth, targetHeight))
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        GlassCard(
            modifier = Modifier.fillMaxSize().padding(20.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // ВЕРХНЯЯ ЧАСТЬ
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    // Кнопки
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 16.dp, end = 16.dp)
                            .zIndex(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(onClick = onOpenSettings, modifier = Modifier.size(TopButtonSize)) {
                            Icon(Icons.Rounded.Settings, null, tint = GlassTheme.colors.textPrimary.copy(alpha = 0.6f), modifier = Modifier.size(TopIconSize))
                        }
                        IconButton(onClick = onClear, modifier = Modifier.size(TopButtonSize)) {
                            Icon(Icons.Rounded.Close, null, tint = GlassTheme.colors.textPrimary.copy(alpha = 0.8f), modifier = Modifier.size(TopIconSize))
                        }
                    }

                    // Текст + Скролл
                    val scrollState = rememberScrollState()
                    val dynamicFontSize = remember(textContent) {
                        when (textContent.length) {
                            in 0..60 -> 32.sp
                            in 61..150 -> 28.sp
                            in 151..400 -> 24.sp
                            else -> 20.sp
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth().padding(top = 32.dp)) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(scrollState)
                                .padding(horizontal = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = textContent,
                                style = TextStyle(
                                    fontFamily = GlassTheme.typography, // Шрифт из темы
                                    fontSize = dynamicFontSize,
                                    lineHeight = dynamicFontSize * 1.3,
                                    fontWeight = FontWeight.Medium,
                                    color = GlassTheme.colors.textPrimary, // Цвет из темы
                                    textAlign = TextAlign.Center,
                                    shadow = Shadow(Color.Black.copy(0.2f), Offset(0f, 1f), 5f)
                                )
                            )
                        }
                        if (scrollState.maxValue > 0) {
                            LiquidScrollbar(scrollState, Modifier.padding(end=6.dp, top=10.dp, bottom=10.dp).width(4.dp).fillMaxHeight())
                        }
                    }
                }

                // НИЖНЯЯ ЧАСТЬ
                Box(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(85.dp)
                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onToggleListening() }
                    ) {
                        MagicOrb(isActive = state.isListening)
                    }
                }
            }
        }
    }
}

// --- КОМПОНЕНТЫ ---

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    // Используем цвета и форму из GlassTheme
    Box(
        modifier = modifier
            .shadow(elevation = 0.dp, shape = GlassTheme.shape)
            .clip(GlassTheme.shape)
            .background(Brush.verticalGradient(listOf(GlassTheme.colors.backgroundTop, GlassTheme.colors.backgroundBottom)))
            .border(1.dp, Brush.verticalGradient(listOf(GlassTheme.colors.borderGlowTop, GlassTheme.colors.borderGlowBottom)), GlassTheme.shape),
        content = content
    )
}

@Composable
fun MagicOrb(isActive: Boolean) {
    val infiniteTransition = rememberInfiniteTransition()
    val spinDuration = if (isActive) 1500 else 8000
    val angle by infiniteTransition.animateFloat(0f, 360f, infiniteRepeatable(tween(spinDuration, easing = LinearEasing)))
    val pulseScale by infiniteTransition.animateFloat(1f, if (isActive) 1.15f else 1.05f, infiniteRepeatable(tween(if (isActive) 600 else 2500, easing = FastOutSlowInEasing), RepeatMode.Reverse))

    // Используем цвета из темы
    val color1 = if (isActive) GlassTheme.colors.orbCyan else GlassTheme.colors.orbIndigo
    val color2 = if (isActive) GlassTheme.colors.orbWhite else Color(0xFF818CF8) // Светлый индиго можно оставить хардкодом или добавить в тему

    Box(contentAlignment = Alignment.Center, modifier = Modifier.scale(pulseScale)) {
        Canvas(Modifier.size(70.dp)) { drawCircle(Brush.radialGradient(listOf(color1.copy(alpha = 0.3f), Color.Transparent), center, size.minDimension/1.5f)) }
        Canvas(Modifier.size(56.dp).rotate(angle)) { drawCircle(Brush.sweepGradient(listOf(Color.Transparent, color1.copy(0.1f), color1)), style = Stroke(3.dp.toPx())) }
        Canvas(Modifier.size(46.dp).rotate(-angle * 1.5f)) { drawCircle(Brush.sweepGradient(listOf(color2, color2.copy(0.1f), Color.Transparent)), style = Stroke(2.dp.toPx())) }
        Box(Modifier.size(35.dp).background(Brush.radialGradient(listOf(GlassTheme.colors.orbWhite.copy(0.9f), color1), radius=100f), CircleShape))
    }
}

@Composable
fun LiquidScrollbar(scrollState: ScrollState, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val totalHeight = size.height; val viewportHeight = totalHeight; val contentHeight = scrollState.maxValue + viewportHeight
        val scrollbarHeight = (viewportHeight / contentHeight) * viewportHeight
        val safeScrollbarHeight = scrollbarHeight.coerceAtLeast(40f)
        val scrollProgress = scrollState.value.toFloat() / scrollState.maxValue.toFloat()
        val scrollbarY = scrollProgress * (viewportHeight - safeScrollbarHeight)
        drawRoundRect(Color(0x10FFFFFF), cornerRadius = CornerRadius(4.dp.toPx()), size = size)
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xA0FFFFFF), Color(0x40FFFFFF))), topLeft = Offset(0f, scrollbarY), size = Size(size.width, safeScrollbarHeight), cornerRadius = CornerRadius(4.dp.toPx()))
    }
}

@Preview
@Composable
fun MainScreenPreview() {
    // Оборачиваем в AppTheme, чтобы подтянулись цвета из DefaultGlassColors
    ru.abledo.ui.AppTheme {
        Box(Modifier.fillMaxSize().background(Color(0xFF336699))) {
            MainScreen(
                state = MainState(displayedText = "Theme extracted!", statusMessage = "", isListening = false),
                onToggleListening = {}, onClear = {}, onOpenSettings = {}, onResizeRequest = {}
            )
        }
    }
}