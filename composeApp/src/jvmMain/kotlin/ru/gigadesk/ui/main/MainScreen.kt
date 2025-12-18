package ru.gigadesk.ui.main

import androidx.compose.animation.core.*
import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import org.kodein.di.compose.localDI

// --- КОНСТАНТЫ ---
private val TopButtonSize = 28.dp
private val TopIconSize = 16.dp
private val BaseWidth = 500.dp
private val BaseHeight = 260.dp
private val MaxHeight = 900.dp
private val MaxWidth = 650.dp

@Composable
fun MainScreen(
    onOpenSettings: () -> Unit,
    onResizeRequest: (DpSize) -> Unit,
    onCloseWindow: () -> Unit,
    onShowSnack: (String) -> Unit = {},
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

    MainScreen(
        state = state,
        onToggleListening = {
            if (state.isListening) viewModel.send(MainEvent.StopListening)
            else viewModel.send(MainEvent.StartListening)
        },
        onClear = { viewModel.send(MainEvent.ClearContext) },
        onOpenSettings = onOpenSettings,
        onResizeRequest = onResizeRequest,
        onStopSpeech = { viewModel.send(MainEvent.StopSpeech) },
        onShowLastText = { viewModel.send(MainEvent.ShowLastText) },
        onShowSnack = onShowSnack
    )
}

@Composable
fun MainScreen(
    state: MainState,
    onToggleListening: () -> Unit = {},
    onClear: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onResizeRequest: (DpSize) -> Unit = {},
    onStopSpeech: () -> Unit = {},
    onShowLastText: () -> Unit = {},
    onShowSnack: (String) -> Unit = {},
) {
    val clipboardManager = LocalClipboardManager.current
    val textContent = state.displayedText.ifEmpty { state.statusMessage }

    LaunchedEffect(textContent) {
        val textLen = textContent.length
        val calculatedHeight = (280 + (textLen * 0.8)).dp
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
        RealLiquidGlassCard(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {

                // --- Верхний бар ---
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, end = 12.dp)
                        .zIndex(2f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val iconTint = Color.White.copy(0.8f)

                    if (state.lastText != null) {
                        MinimalGlassButton(onClick = onShowLastText) {
                            Icon(Icons.Rounded.SkipPrevious, null, tint = iconTint, modifier = Modifier.size(TopIconSize))
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    MinimalGlassButton(onClick = onStopSpeech) {
                        Icon(Icons.AutoMirrored.Rounded.VolumeOff, null, tint = iconTint, modifier = Modifier.size(TopIconSize))
                    }
                    Spacer(Modifier.width(8.dp))
                    MinimalGlassButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.Settings, null, tint = iconTint, modifier = Modifier.size(TopIconSize))
                    }
                    Spacer(Modifier.width(8.dp))
                    // Кнопка закрытия
                    MinimalGlassButton(onClick = onClear, isDestructive = true) {
                        Icon(Icons.Rounded.Close, null, tint = iconTint, modifier = Modifier.size(TopIconSize))
                    }
                }

                // --- Текст ---
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val scrollState = rememberScrollState()
                    val dynamicFontSize = remember(textContent) {
                        when (textContent.length) {
                            in 0..30 -> 32.sp
                            in 31..60 -> 28.sp
                            in 61..150 -> 24.sp
                            else -> 20.sp
                        }
                    }

                    Row(modifier = Modifier.fillMaxSize().padding(top = 5.dp)) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(scrollState)
                                .padding(horizontal = 24.dp)
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) {
                                    clipboardManager.setText(AnnotatedString(textContent))
                                    onShowSnack("Скопировано")
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = textContent,
                                style = TextStyle(
                                    fontSize = dynamicFontSize,
                                    lineHeight = dynamicFontSize * 1.4,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                    textAlign = TextAlign.Center,
                                    shadow = Shadow(
                                        color = Color.Black,
                                        offset = Offset(0f, 2f),
                                        blurRadius = 4f
                                    )
                                )
                            )
                        }

                        if (scrollState.maxValue > 0) {
                            Box(modifier = Modifier.width(8.dp).fillMaxHeight().padding(vertical = 20.dp, horizontal = 2.dp)) {
                                GlassScrollbar(scrollState)
                            }
                        }
                    }
                }

                // --- Orb ---
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp, top = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    LiquidOrb(
                        isActive = state.isListening,
                        onClick = onToggleListening
                    )
                }
            }
        }
    }
}

/**
 * Максимально минималистичная кнопка.
 * - Убрал красный цвет, теперь она нейтральная.
 */
@Composable
fun MinimalGlassButton(
    onClick: () -> Unit,
    isDestructive: Boolean = false,
    content: @Composable () -> Unit
) {
    // Единый стиль фона для всех кнопок
    val backgroundColor = Color.White.copy(0.05f)
    val borderColor = Color.White.copy(0.2f)

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

/**
 * Переименовано обратно в RealLiquidGlassCard
 */
@Composable
fun RealLiquidGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 32.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val borderThickness = 1.5.dp

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.matchParentSize().clip(shape)) {
            val strokeWidth = borderThickness.toPx()

            // 1. Основной контур
            drawRoundRect(
                brush = Brush.linearGradient(
                    0.0f to Color.White.copy(alpha = 0.8f),
                    0.5f to Color.White.copy(alpha = 0.0f),
                    1.0f to Color.White.copy(alpha = 0.3f),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height)
                ),
                cornerRadius = CornerRadius(cornerRadius.toPx()),
                style = Stroke(width = strokeWidth)
            )

            // 2. Блик (Sheen)
            drawPath(
                path = Path().apply {
                    moveTo(0f, size.height * 0.2f)
                    lineTo(size.width * 0.4f, 0f)
                    lineTo(size.width * 0.65f, 0f)
                    lineTo(0f, size.height * 0.6f)
                    close()
                },
                brush = Brush.linearGradient(
                    colors = listOf(Color.White.copy(0.05f), Color.Transparent),
                    start = Offset(0f, 0f),
                    end = Offset(size.width / 2, size.height / 2)
                )
            )
        }

        // Хитбокс (невидимый фон для перехвата кликов)
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .background(Color.White.copy(alpha = 0.01f))
        )

        Box(modifier = Modifier.padding(borderThickness)) {
            content()
        }
    }
}

@Composable
fun LiquidOrb(isActive: Boolean, onClick: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition()

    // --- Динамика (Ускоренная) ---
    // Основное вращение (быстрое)
    val mainRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if(isActive) 1000 else 3000, easing = LinearEasing))
    )

    // Встречное вращение "смеси" (еще быстрее для эффекта турбулентности)
    val turbulenceRotation by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(if(isActive) 1500 else 6000, easing = LinearEasing))
    )

    // Резкий "дышащий" пульс
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive) 1.25f else 1.08f,
        animationSpec = infiniteRepeatable(
            tween(if(isActive) 500 else 2500, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        )
    )

    // Цвета Siri (Фиолетовый доминант)
    val cPurple = Color(0xFF7C4DFF)
    val cMagenta = Color(0xFFD500F9)
    val cCyan = Color(0xFF00E5FF)
    val cDeep = Color(0xFF651FFF)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(52.dp) // Размер уменьшен еще на 20%
            .scale(pulseScale)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick
            )
    ) {
        // 1. Аура (Glow)
        Canvas(modifier = Modifier.fillMaxSize().scale(1.5f)) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        cPurple.copy(alpha = if(isActive) 0.6f else 0.3f),
                        Color.Transparent
                    )
                )
            )
        }

        // 2. Основное тело (Вращающийся градиент)
        Canvas(modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = mainRotation }) {
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(cPurple, cMagenta, cDeep, cCyan, cPurple)
                )
            )
        }

        // 3. Турбулентность (Внутренний слой со смещением)
        // Смещаем центр отрисовки на пару пикселей, чтобы при вращении создавался эффект "болтанки"
        Canvas(modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = turbulenceRotation }) {
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(Color.Transparent, cCyan.copy(0.7f), cPurple.copy(0.5f), Color.Transparent)
                ),
                radius = size.minDimension / 2.1f,
                center = center + Offset(3f, -3f) // Смещение для эффекта жидкости
            )
        }

        // 4. Глянцевая линза (Блики)
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Жесткий белый блик
            drawOval(
                brush = Brush.linearGradient(
                    colors = listOf(Color.White.copy(0.95f), Color.Transparent)
                ),
                topLeft = Offset(size.width * 0.25f, size.height * 0.15f),
                size = Size(size.width * 0.3f, size.height * 0.2f)
            )

            // Нижний контур
            drawArc(
                color = Color.White.copy(0.4f),
                startAngle = 20f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(2f, 2f),
                size = Size(size.width - 4f, size.height - 4f),
                style = Stroke(width = 1.5.dp.toPx())
            )
        }
    }
}

@Composable
fun GlassScrollbar(scrollState: ScrollState) {
    Canvas(modifier = Modifier.fillMaxSize().alpha(if(scrollState.maxValue > 0) 1f else 0f)) {
        val indicatorHeight = (size.height / (scrollState.maxValue + size.height)) * size.height
        val safeHeight = indicatorHeight.coerceAtLeast(30f)
        val indicatorY = (scrollState.value.toFloat() / scrollState.maxValue.toFloat()) * (size.height - safeHeight)

        drawRoundRect(
            brush = Brush.verticalGradient(listOf(Color.White, Color.White.copy(0.5f))),
            topLeft = Offset(0f, indicatorY),
            size = Size(4.dp.toPx(), safeHeight),
            cornerRadius = CornerRadius(10f)
        )
    }
}

@Preview
@Composable
fun PreviewRealLiquidGlass() {
    MaterialTheme {
        Box(Modifier.fillMaxSize().background(Color.Gray)) {
            MainScreen(
                state = MainState(
                    displayedText = "Real Liquid Glass восстановлен. Кнопки минималистичны и чисты.",
                    statusMessage = "",
                    isListening = false
                )
            )
        }
    }
}