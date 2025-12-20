package ru.gigadesk.ui.main

import androidx.compose.animation.core.*
import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.model.DefaultMarkdownColors
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import kotlinx.coroutines.launch
import org.kodein.di.compose.localDI
import kotlin.random.Random

// --- КОНСТАНТЫ ---
private val TopButtonSize = 28.dp
private val TopIconSize = 16.dp
private val BaseWidth = 500.dp
private val BaseHeight = 260.dp
private val MaxHeight = 900.dp
private val MaxWidth = 700.dp

// --- МОДЕЛЬ ДЛЯ РУЧНОГО ПАРСИНГА ---
sealed class MarkdownPart {
    data class TextContent(val content: String) : MarkdownPart()
    data class CodeContent(val language: String, val code: String) : MarkdownPart()
}

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

    MainScreenContent(
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
fun MainScreenContent(
    state: MainState,
    onToggleListening: () -> Unit = {},
    onClear: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onResizeRequest: (DpSize) -> Unit = {},
    onStopSpeech: () -> Unit = {},
    onShowLastText: () -> Unit = {},
    onShowSnack: (String) -> Unit = {},
) {
    val textContent = state.displayedText.ifEmpty { state.statusMessage }
    val windowInfo = LocalWindowInfo.current
    val isFocused = windowInfo.isWindowFocused

    LaunchedEffect(textContent) {
        val textLen = textContent.length
        val hasCode = textContent.contains("```")
        val multiplier = if (hasCode) 1.2 else 0.8
        val calculatedHeight = (280 + (textLen * multiplier)).dp

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
                    MinimalGlassButton(onClick = onClear) {
                        Icon(Icons.Rounded.Close, null, tint = iconTint, modifier = Modifier.size(TopIconSize))
                    }
                }

                // --- Контент с Markdown ---
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {

                    val dynamicFontSize = remember(textContent) {
                        when (textContent.length) {
                            in 0..50 -> 22.sp
                            in 51..200 -> 18.sp
                            else -> 15.sp
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 5.dp, start = 24.dp, end = 24.dp),
                        contentAlignment = Alignment.TopStart
                    ) {
                        // ЕДИНСТВЕННЫЙ SelectionContainer на верхнем уровне
                        SelectionContainer {
                            MarkdownViewer(
                                text = textContent,
                                baseFontSize = dynamicFontSize,
                                isWindowFocused = isFocused,
                                onShowSnack = onShowSnack
                            )
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

// --- ЛОГИКА РЕНДЕРИНГА ---

@Composable
fun MarkdownViewer(
    text: String,
    baseFontSize: androidx.compose.ui.unit.TextUnit,
    isWindowFocused: Boolean,
    onShowSnack: (String) -> Unit
) {
    val scrollState = rememberScrollState()
    val alphaAnim = remember { Animatable(0f) }
    val blurAnim = remember { Animatable(10f) }

    // Парсим текст на блоки (Текст / Код) вручную
    val parts = remember(text) { parseMarkdownContent(text) }

    LaunchedEffect(text) {
        alphaAnim.snapTo(0f)
        blurAnim.snapTo(10f)
        launch { alphaAnim.animateTo(1f, tween(500)) }
        launch { blurAnim.animateTo(0f, tween(500)) }
        scrollState.animateScrollTo(0)
    }

    val containerAlpha by animateFloatAsState(
        targetValue = if (isWindowFocused) 1f else 0.5f,
        animationSpec = tween(600)
    )

    // Стили для текста
    val baseStyle = TextStyle(
        color = Color.White,
        fontSize = baseFontSize,
        lineHeight = baseFontSize * 1.4
    )
    val codeStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = baseFontSize * 0.9,
        color = Color(0xFFE0E0E0)
    )
    val customTypography = DefaultMarkdownTypography(
        h1 = MaterialTheme.typography.headlineMedium.copy(color = Color.White, fontWeight = FontWeight.Bold),
        h2 = MaterialTheme.typography.titleLarge.copy(color = Color.White, fontWeight = FontWeight.Bold),
        h3 = MaterialTheme.typography.titleMedium.copy(color = Color.White, fontWeight = FontWeight.Bold),
        h4 = MaterialTheme.typography.titleSmall.copy(color = Color.White, fontWeight = FontWeight.Bold),
        h5 = MaterialTheme.typography.bodyLarge.copy(color = Color.White, fontWeight = FontWeight.Bold),
        h6 = MaterialTheme.typography.bodyMedium.copy(color = Color.White, fontWeight = FontWeight.Bold),
        text = baseStyle,
        paragraph = baseStyle,
        code = codeStyle,
        inlineCode = codeStyle.copy(color = Color(0xFF81D4FA), background = Color.White.copy(0.1f)),
        quote = baseStyle.copy(color = Color.Gray, fontStyle = FontStyle.Italic),
        bullet = baseStyle.copy(fontWeight = FontWeight.Bold),
        list = baseStyle,
        ordered = baseStyle,
        link = baseStyle.copy(color = Color(0xFF82B1FF), textDecoration = TextDecoration.Underline)
    )
    val customColors = DefaultMarkdownColors(
        text = Color.White,
        codeText = Color(0xFFE0E0E0),
        codeBackground = Color.Black.copy(alpha = 0.4f),
        inlineCodeText = Color(0xFF81D4FA),
        inlineCodeBackground = Color.White.copy(alpha = 0.1f),
        dividerColor = Color.White.copy(alpha = 0.2f),
        linkText = Color(0xFF82B1FF)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .alpha(containerAlpha * alphaAnim.value)
            .blur(blurAnim.value.dp)
            .verticalScroll(scrollState)
    ) {
        // Проходимся по нашим вручную распаршенным блокам
        parts.forEach { part ->
            when (part) {
                is MarkdownPart.TextContent -> {
                    Markdown(
                        content = part.content,
                        colors = customColors,
                        typography = customTypography,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                is MarkdownPart.CodeContent -> {
                    CodeBlockWithCopy(
                        code = part.code,
                        language = part.language,
                        style = codeStyle,
                        onShowSnack = onShowSnack
                    )
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

// --- ПАРСЕР СТРОГОГО РЕЖИМА ---
fun parseMarkdownContent(input: String): List<MarkdownPart> {
    val parts = mutableListOf<MarkdownPart>()

    // Новая регулярка:
    // 1. ``` - начало
    // 2. ([\w\+\-\.\s]*) - группа языка. Берет буквы, цифры, +, -, точки и пробелы.
    // 3. \n - ОБЯЗАТЕЛЬНЫЙ перенос строки. Это отсекает "python" от тела кода.
    // 4. ([\s\S]*?) - ленивый захват тела кода до закрывающих кавычек
    val regex = Regex("```([\\w\\+\\-\\.\\s]*)\\n([\\s\\S]*?)```")

    var lastIndex = 0
    regex.findAll(input).forEach { match ->
        val textBefore = input.substring(lastIndex, match.range.first)
        if (textBefore.isNotBlank()) {
            parts.add(MarkdownPart.TextContent(textBefore))
        }

        val rawLang = match.groupValues[1].trim()
        val code = match.groupValues[2].trimEnd() // Убираем перенос строки в конце, если есть

        parts.add(MarkdownPart.CodeContent(rawLang, code))
        lastIndex = match.range.last + 1
    }

    if (lastIndex < input.length) {
        val textAfter = input.substring(lastIndex)
        if (textAfter.isNotBlank()) {
            parts.add(MarkdownPart.TextContent(textAfter))
        }
    }

    return parts
}

// --- КОМПОНЕНТ ДЛЯ КОДА ---
@Composable
fun CodeBlockWithCopy(
    code: String,
    language: String?,
    style: TextStyle,
    onShowSnack: (String) -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val displayLang = if (!language.isNullOrBlank()) language.uppercase() else "CODE"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.4f))
            .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(8.dp))
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(0.05f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayLang,
                    style = TextStyle(
                        color = Color.White.copy(0.4f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                )

                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable {
                            clipboardManager.setText(AnnotatedString(code))
                            onShowSnack("Код скопирован")
                        }
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = "Copy code",
                        tint = Color.White.copy(0.6f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Text(
                text = code,
                style = style,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

// --- UI КОМПОНЕНТЫ (LiquidOrb, GlassCard, etc - без изменений) ---
@Composable
fun MinimalGlassButton(
    onClick: () -> Unit,
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

@Composable
fun rememberNoiseBrush(): ShaderBrush {
    return remember {
        val size = 256
        val imageBitmap = ImageBitmap(size, size)
        val canvas = Canvas(imageBitmap)
        val paint = Paint().apply {
            color = Color.White
            alpha = 0.2f
        }
        val rnd = Random(System.currentTimeMillis())

        for (x in 0 until size) {
            for (y in 0 until size) {
                if (rnd.nextFloat() > 0.8f) {
                    canvas.drawRect(Rect(x.toFloat(), y.toFloat(), x + 1f, y + 1f), paint)
                }
            }
        }
        val shader = ImageShader(imageBitmap, TileMode.Repeated, TileMode.Repeated)
        ShaderBrush(shader)
    }
}

@Composable
fun RealLiquidGlassCard(
    modifier: Modifier = Modifier,
    isWindowFocused: Boolean,
    cornerRadius: Dp = 32.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val borderThickness = 1.5.dp
    val noiseBrush = rememberNoiseBrush()

    val backdropAlpha by animateFloatAsState(
        targetValue = if (isWindowFocused) 0.75f else 0.0f,
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
                        colors = listOf(Color.Transparent, Color.Black.copy(0.5f)),
                        radius = size.maxDimension / 1.0f
                    )
                )
            }
        }

        Canvas(modifier = Modifier.matchParentSize().clip(shape)) {
            val strokeWidth = borderThickness.toPx()
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
                )
            )
        }
    }
}