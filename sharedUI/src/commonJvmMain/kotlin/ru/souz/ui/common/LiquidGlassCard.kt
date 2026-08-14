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
import androidx.compose.ui.platform.LocalWindowInfo
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
    val borderColor = if (LocalWindowInfo.current.isWindowFocused) {
        glassColors.heroBorder
    } else {
        glassColors.heroBorder.copy(alpha = glassColors.heroBorder.alpha * 0.55f)
    }

    Box(
        modifier = modifier.graphicsLayer {
            shape = cardShape
            clip = true
            compositingStrategy = CompositingStrategy.Offscreen
        },
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            drawRect(brush = Brush.verticalGradient(glassColors.heroBackground))

            val strokeWidth = borderThickness.toPx()
            val strokeInset = strokeWidth / 2f
            drawRoundRect(
                color = borderColor,
                topLeft = Offset(strokeInset, strokeInset),
                size = Size(
                    width = (size.width - strokeWidth).coerceAtLeast(0f),
                    height = (size.height - strokeWidth).coerceAtLeast(0f),
                ),
                cornerRadius = CornerRadius(
                    (cornerRadius.toPx() - strokeInset).coerceAtLeast(0f),
                ),
                style = Stroke(width = strokeWidth),
            )
        }

        val innerShape = RoundedCornerShape(cornerRadius - borderThickness)
        Box(modifier = Modifier.padding(borderThickness).clip(innerShape)) {
            content()
        }
    }
}
