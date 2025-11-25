package ru.abledo.ui.main

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.kodein.di.compose.localDI
import ru.abledo.ui.AppTheme

@Composable
fun MainScreen(onOpenSettings: () -> Unit) {
    val di = localDI()
    val viewModel = viewModel { MainViewModel(di) }
    val state by viewModel.uiState.collectAsState()

    MainScreen(
        state = state,
        onStart = { viewModel.send(MainEvent.StartListening) },
        onStop = { viewModel.send(MainEvent.StopListening) },
        onClear = { viewModel.send(MainEvent.ClearContext) },
        onOpenSettings = onOpenSettings
    )
}

@Composable
fun MainScreen(
    state: MainState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val glassShape = RoundedCornerShape(20.dp)
    val scrollState = rememberScrollState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF0F1729),
                            Color(0xFF111827),
                            Color(0xFF0B1020)
                        ),
                        start = Offset.Zero,
                        end = Offset(800f, 1400f)
                    )
                )
        ) {
            GlowLayer(
                color = Color(0xFF6FE8FF),
                blur = 220.dp,
                size = 320.dp,
                offset = Offset(-120f, 140f)
            )
            GlowLayer(
                color = Color(0xFFB48CFF),
                blur = 260.dp,
                size = 280.dp,
                offset = Offset(480f, -60f)
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "AbleDo · Голосовой компаньон",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White.copy(alpha = 0.72f)
                        )
                        Text(
                            text = state.statusMessage,
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White
                        )
                    }

                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .border(
                                width = 1.dp,
                                color = Color.White.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(12.dp)
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Открыть настройки",
                            tint = Color.White
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(glassShape)
                        .drawGlassSurface(glassShape)
                        .border(
                            width = 1.dp,
                            brush = Brush.linearGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.5f),
                                    Color.White.copy(alpha = 0.18f)
                                )
                            ),
                            shape = glassShape
                        )
                        .padding(24.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = state.displayedText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.92f),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val primaryBrush = Brush.horizontalGradient(
                            listOf(
                                Color(0xFF7CE9FF),
                                Color(0xFF7C93FF)
                            )
                        )
                        Button(
                            onClick = onStart,
                            enabled = !state.isListening,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(primaryBrush)
                        ) {
                            Icon(imageVector = Icons.Rounded.Mic, contentDescription = null, tint = Color(0xFF0B1020))
                            Spacer(Modifier.width(8.dp))
                            Text("Начать", color = Color(0xFF0B1020))
                        }

                        Button(
                            onClick = onStop,
                            enabled = state.isListening,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.12f)),
                            modifier = Modifier.clip(RoundedCornerShape(14.dp))
                        ) {
                            Icon(imageVector = Icons.Rounded.Stop, contentDescription = null, tint = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text("Остановить", color = Color.White)
                        }
                    }

                    IconButton(
                        onClick = onClear,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .border(
                                width = 1.dp,
                                color = Color.White.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(12.dp)
                            )
                    ) {
                        Icon(imageVector = Icons.Rounded.Clear, contentDescription = null, tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun GlowLayer(color: Color, blur: Dp, size: Dp, offset: Offset) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithCache {
                onDrawBehind {
                    withTransform({ translate(offset.x, offset.y) }) {
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(color.copy(alpha = 0.35f), Color.Transparent),
                                radius = size.toPx()
                            )
                        )
                    }
                }
            }
            .blur(blur)
    )
}

private fun Modifier.drawGlassSurface(shape: RoundedCornerShape): Modifier = this
    .background(
        brush = Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = 0.14f),
                Color.White.copy(alpha = 0.06f)
            ),
            start = Offset.Zero,
            end = Offset(800f, 900f)
        ),
        shape = shape
    )
    .drawWithCache {
        val highlightBrush = Brush.radialGradient(
            listOf(Color.White.copy(alpha = 0.24f), Color.Transparent),
            radius = size.minDimension / 1.5f,
            center = Offset(size.width * 0.25f, size.height * 0.2f)
        )
        onDrawWithContent {
            drawContent()
            drawRect(highlightBrush)
        }
    }

@Preview
@Composable
fun MainScreenPreview() {
    AppTheme {
        MainScreen(
            state = MainState(),
            onStart = {},
            onStop = {},
            onClear = {},
            onOpenSettings = {},
        )
    }
}
