package ru.souz.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class LiquidGlassPreset {
    Default,
    Hero
}

@Composable
fun RealLiquidGlassCard(
    modifier: Modifier = Modifier,
    isWindowFocused: Boolean,
    cornerRadius: Dp = 24.dp,
    preset: LiquidGlassPreset = LiquidGlassPreset.Hero,
    content: @Composable BoxScope.() -> Unit
) {
    val cardShape = RoundedCornerShape(cornerRadius)
    val borderThickness = if (preset == LiquidGlassPreset.Hero) 1.dp else 1.5.dp

    val backdropAlpha by animateFloatAsState(
        targetValue = when (preset) {
            LiquidGlassPreset.Default -> if (isWindowFocused) 0.95f else 0.0f
            LiquidGlassPreset.Hero -> 1.0f
        },
        animationSpec = tween(400)
    )

    Box(
        modifier = modifier.graphicsLayer {
            shape = cardShape
            clip = true
            compositingStrategy = CompositingStrategy.Offscreen
        }
    ) {
        when (preset) {
            LiquidGlassPreset.Default -> {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = backdropAlpha))
                )
            }

            LiquidGlassPreset.Hero -> {
                Canvas(modifier = Modifier.matchParentSize()) {
                    drawRect(
                        color = Color(0xF21B1C20),
                        alpha = backdropAlpha
                    )

                    drawRect(color = Color(0xB0000000))
                }
            }
        }

        Canvas(modifier = Modifier.matchParentSize()) {
            val strokeWidth = borderThickness.toPx()
            when (preset) {
                LiquidGlassPreset.Default -> {
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            0.0f to Color(0xCCFFFFFF),
                            0.5f to Color(0x00FFFFFF),
                            1.0f to Color(0x4DFFFFFF),
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
                            colors = listOf(Color(0x0DFFFFFF), Color.Transparent),
                            start = Offset(0f, 0f),
                            end = Offset(size.width / 2, size.height / 2)
                        )
                    )
                }

                LiquidGlassPreset.Hero -> {
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color(0x3AFFFFFF),
                                Color(0x18FFFFFF),
                                Color(0x14FFFFFF),
                                Color(0x2AFFFFFF)
                            ),
                            start = Offset(0f, 0f),
                            end = Offset(size.width, size.height)
                        ),
                        cornerRadius = CornerRadius(cornerRadius.toPx()),
                        style = Stroke(width = strokeWidth)
                    )

                    val inset = strokeWidth * 1.4f
                    val innerWidth = (size.width - inset * 2f).coerceAtLeast(0f)
                    val innerHeight = (size.height - inset * 2f).coerceAtLeast(0f)
                    drawRoundRect(
                        color = Color(0x08FFFFFF),
                        topLeft = Offset(inset, inset),
                        size = Size(innerWidth, innerHeight),
                        cornerRadius = CornerRadius((cornerRadius.toPx() - inset).coerceAtLeast(0f)),
                        style = Stroke(width = strokeWidth * 0.7f)
                    )
                }
            }
        }

        if (preset == LiquidGlassPreset.Default) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color(0x03FFFFFF))
            )
        }

        val innerShape = RoundedCornerShape(cornerRadius - borderThickness)
        Box(modifier = Modifier.padding(borderThickness).clip(innerShape)) {
            content()
        }
    }
}
