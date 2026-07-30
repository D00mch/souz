package ru.souz.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppThemeTest {

    @Test
    fun `semantic palette stays readable and light surfaces stay neutral`() {
        listOf(false, true).forEach { isDark ->
            val colors = souzColors(isDark)
            val scheme = if (isDark) DarkColors else LightColors
            val base = scheme.background
            val hero = colors.glass.heroBackground.first().compositeOver(base)
            assertEquals(scheme.onErrorContainer, colors.memory.danger.content)
            assertEquals(scheme.errorContainer, colors.memory.danger.container)
            (
                listOf(
                    Triple(scheme.onSurface, colors.glass.heroBackground.first(), base),
                    Triple(colors.graph.primaryText, colors.graph.nodeBackground, base),
                    Triple(colors.graph.primaryText, colors.graph.panelBackground, base),
                    Triple(colors.settings.primaryActionContent, colors.settings.primaryActionContainer, base),
                    Triple(colors.toolReview.content, colors.toolReview.container, base),
                    Triple(colors.toolReview.content, colors.toolReview.itemContainer, base),
                    Triple(colors.toolReview.secondaryContent, colors.toolReview.container, base),
                    Triple(colors.toolReview.accentContent, colors.toolReview.accent, base),
                    Triple(colors.toolReview.content, colors.toolReview.patchBackground, base),
                    Triple(colors.toolReview.info, colors.toolReview.patchBackground, base),
                    Triple(colors.toolReview.positive, colors.toolReview.patchBackground, base),
                    Triple(colors.toolReview.negative, colors.toolReview.patchBackground, base),
                    Triple(colors.toolReview.warning, colors.toolReview.patchBackground, base),
                    Triple(colors.tooltip.content, colors.tooltip.background, hero),
                    Triple(
                        colors.ambientSuggestion.content,
                        colors.ambientSuggestion.background,
                        base,
                    ),
                    Triple(
                        colors.ambientSuggestion.secondaryContent,
                        colors.ambientSuggestion.background,
                        base,
                    ),
                    Triple(
                        colors.ambientSuggestion.accentContent,
                        colors.ambientSuggestion.accent,
                        base,
                    ),
                ) + colors.memory.allKinds().map { Triple(it.content, it.container, base) }
            ).forEach { (content, container, underlay) ->
                assertTrue(contrast(content, container, underlay) >= 4.5f)
            }
            if (!isDark) {
                assertTrue(chroma(colors.chat.userBubbleBackground) < 0.04f)
                assertTrue(chroma(colors.chat.pathChipBackground) < 0.04f)
                assertTrue(colors.graph.nodeBackground.luminance() > 0.7f)
                assertTrue(colors.memory.warning.container.luminance() > 0.7f)
                assertTrue(colors.memory.danger.container.luminance() > 0.7f)
                assertTrue(colors.ambientSuggestion.background.luminance() > 0.7f)
            }
        }
    }
}

private fun MemoryColors.allKinds(): List<MemoryKindColors> = listOf(
    semantic, preference, procedure, projectRule, episodeNote, projectDecision, warning, danger,
)

private fun chroma(color: Color) =
    max(color.red, max(color.green, color.blue)) - min(color.red, min(color.green, color.blue))

private fun contrast(foreground: Color, background: Color, underlay: Color): Float {
    val renderedBackground = background.compositeOver(underlay)
    val renderedForeground = foreground.compositeOver(renderedBackground)
    val lighter = max(renderedForeground.luminance(), renderedBackground.luminance())
    val darker = min(renderedForeground.luminance(), renderedBackground.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}
