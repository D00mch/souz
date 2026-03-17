package ru.souz.ui.main

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ThinkingTextColor = Color(0xFFE5E7EB)
private val ThinkingPulseEasing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private const val ThinkingLetterDelayMillis = 140
private const val ThinkingPulseCycleMillis = 2200
private const val ThinkingPulseHalfCycleMillis = ThinkingPulseCycleMillis / 2
private const val ThinkingTextMinAlpha = 0.3f
private const val ThinkingTextMaxAlpha = 1f

@Composable
fun ThinkingIndicator(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        text.forEachIndexed { index, char ->
            AnimatedThinkingLetter(
                char = char.toString(),
                delayMillis = index * ThinkingLetterDelayMillis,
            )
        }
    }
}

@Composable
private fun AnimatedThinkingLetter(
    char: String,
    delayMillis: Int,
) {
    val transition = rememberInfiniteTransition()
    val alpha by transition.animateFloat(
        initialValue = ThinkingTextMinAlpha,
        targetValue = ThinkingTextMaxAlpha,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = ThinkingPulseHalfCycleMillis,
                easing = ThinkingPulseEasing,
            ),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(delayMillis),
        ),
    )

    Text(
        text = char,
        fontSize = 14.sp,
        color = ThinkingTextColor,
        modifier = Modifier.graphicsLayer { this.alpha = alpha },
    )
}
