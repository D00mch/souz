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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import org.jetbrains.skia.FilterTileMode
import org.kodein.di.compose.localDI
import kotlin.random.Random

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
    val windowInfo = LocalWindowInfo.current
    val isFocused = windowInfo.isWindowFocused

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
        RealLiquidGlassCard(
            modifier = Modifier.fillMaxSize(),
            isWindowFocused = isFocused
        ) {
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

@Composable
fun MinimalGlassButton(
    onClick: () -> Unit,
    isDestructive: Boolean = false,
    content: @Composable () -> Unit
) {
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
 * Хелпер для генерации "Шума" (Зерна).
 * Создает маленькую текстуру с рандомными пикселями один раз при запуске.
 */
@Composable
fun rememberNoiseBrush(): ShaderBrush {
    return remember {
        val size = 256 // Увеличили размер паттерна, чтобы тайлинг был незаметен
        val imageBitmap = ImageBitmap(size, size)
        val canvas = Canvas(imageBitmap)
        val paint = Paint().apply {
            color = Color.White
            // Чем выше alpha здесь, тем "злее" шум.
            // 0.2f - оптимально для заметного, но не перекрывающего текст зерна.
            alpha = 0.2f
        }
        val rnd = Random(System.currentTimeMillis())

        // Рисуем шум попиксельно
        for (x in 0 until size) {
            for (y in 0 until size) {
                // Частота заполнения: 20% пикселей будут белыми точками
                if (rnd.nextFloat() > 0.8f) {
                    canvas.drawRect(Rect(x.toFloat(), y.toFloat(), x + 1f, y + 1f), paint)
                }
            }
        }

        // Создаем шейдер, который будет повторять этот паттерн
        val shader = ImageShader(imageBitmap, TileMode.Repeated, TileMode.Repeated)
        ShaderBrush(shader)
    }
}

/**
 * RealLiquidGlassCard с High-Frequency Noise (Мелкое зерно)
 */
@Composable
fun RealLiquidGlassCard(
    modifier: Modifier = Modifier,
    isWindowFocused: Boolean,
    cornerRadius: Dp = 32.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val borderThickness = 1.5.dp

    // Получаем кисть с мелким шумом
    val noiseBrush = rememberNoiseBrush()

    // Анимация подложки
    val backdropAlpha by animateFloatAsState(
        targetValue = if (isWindowFocused) 0.85f else 0.0f,
        animationSpec = tween(400)
    )

    // Анимация видимости шума
    val noiseAlpha by animateFloatAsState(
        targetValue = if (isWindowFocused) 0.25f else 0.0f, // 0.25f достаточно для четкого зерна
        animationSpec = tween(400)
    )

    Box(modifier = modifier) {
        // 1. Темная подложка
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .background(Color.Black.copy(alpha = backdropAlpha))
        )

        // 2. Слой "Шума" (High Frequency Grain)
        if (noiseAlpha > 0f) {
            Canvas(modifier = Modifier.matchParentSize().clip(shape).alpha(noiseAlpha)) {
                // Заливаем прямоугольник кистью с тайлингом.
                // Это гарантирует, что 1 пиксель шума занимает ровно 1 пиксель экрана.
                drawRect(brush = noiseBrush)

                // Виньетка поверх шума для глубины
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(0.5f)),
                        radius = size.maxDimension / 1.0f
                    )
                )
            }
        }

        // 3. Блики и Границы
        Canvas(modifier = Modifier.matchParentSize().clip(shape)) {
            val strokeWidth = borderThickness.toPx()

            // Контур
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

            // Диагональный блик (Sheen)
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

        // Хитбокс
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

    val mainRotation by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if(isActive) 1000 else 3000, easing = LinearEasing))
    )

    val turbulenceRotation by infiniteTransition.animateFloat(
        initialValue = 360f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(if(isActive) 1500 else 6000, easing = LinearEasing))
    )

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = if (isActive) 1.25f else 1.08f,
        animationSpec = infiniteRepeatable(tween(if(isActive) 500 else 2500, easing = FastOutSlowInEasing), RepeatMode.Reverse)
    )

    val cPurple = Color(0xFF7C4DFF)
    val cMagenta = Color(0xFFD500F9)
    val cCyan = Color(0xFF00E5FF)
    val cDeep = Color(0xFF651FFF)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(52.dp)
            .scale(pulseScale)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClick)
    ) {
        Canvas(modifier = Modifier.fillMaxSize().scale(1.5f)) {
            drawCircle(brush = Brush.radialGradient(listOf(cPurple.copy(alpha = if(isActive) 0.6f else 0.3f), Color.Transparent)))
        }
        Canvas(modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = mainRotation }) {
            drawCircle(brush = Brush.sweepGradient(listOf(cPurple, cMagenta, cDeep, cCyan, cPurple)))
        }
        Canvas(modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = turbulenceRotation }) {
            drawCircle(
                brush = Brush.sweepGradient(listOf(Color.Transparent, cCyan.copy(0.7f), cPurple.copy(0.5f), Color.Transparent)),
                radius = size.minDimension / 2.1f, center = center + Offset(3f, -3f)
            )
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawOval(
                brush = Brush.linearGradient(listOf(Color.White.copy(0.95f), Color.Transparent)),
                topLeft = Offset(size.width * 0.25f, size.height * 0.15f),
                size = Size(size.width * 0.3f, size.height * 0.2f)
            )
            drawArc(
                color = Color.White.copy(0.4f), startAngle = 20f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(2f, 2f), size = Size(size.width - 4f, size.height - 4f),
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
fun PreviewSmartFocusGlass() {
    MaterialTheme {
        Box(Modifier.fillMaxSize().background(Color.Gray)) {
            MainScreen(
                state = MainState(
                    displayedText = "Кликни по окну, чтобы сделать его темнее и увидеть 'шум' (эмуляция матового стекла).",
                    statusMessage = "",
                    isListening = false
                )
            )
        }
    }
}