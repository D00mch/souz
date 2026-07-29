package ru.souz.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.souz.ui.glassColors

@Composable
fun RealLiquidGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val cardShape = RoundedCornerShape(cornerRadius)
    val borderThickness = 1.dp
    val glassColors = MaterialTheme.glassColors

    Box(
        modifier = modifier.graphicsLayer {
            shape = cardShape
            clip = true
            compositingStrategy = CompositingStrategy.Offscreen
        },
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            drawRect(brush = Brush.verticalGradient(glassColors.heroBackground))
            drawRect(color = glassColors.heroOverlay)

            val strokeWidth = borderThickness.toPx()
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = glassColors.heroBorder,
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
                cornerRadius = CornerRadius(cornerRadius.toPx()),
                style = Stroke(width = strokeWidth),
            )

            val inset = strokeWidth * 1.4f
            drawRoundRect(
                color = glassColors.innerBorder,
                topLeft = Offset(inset, inset),
                size = Size(
                    width = (size.width - inset * 2f).coerceAtLeast(0f),
                    height = (size.height - inset * 2f).coerceAtLeast(0f),
                ),
                cornerRadius = CornerRadius((cornerRadius.toPx() - inset).coerceAtLeast(0f)),
                style = Stroke(width = strokeWidth * 0.7f),
            )
        }

        val innerShape = RoundedCornerShape(cornerRadius - borderThickness)
        Box(modifier = Modifier.padding(borderThickness).clip(innerShape)) {
            content()
        }
    }
}
