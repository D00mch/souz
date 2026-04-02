package ru.souz.ui.common

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownUtilsTest {

    @Test
    fun `highlight applies active color only to selected occurrence`() {
        val passive = Color(0x11000000)
        val active = Color(0x22000000)

        val highlighted = buildSearchHighlightedAnnotatedString(
            text = "target and target and target",
            query = "target",
            highlightColor = passive,
            activeHighlightColor = active,
            activeMatchIndex = 1,
        )

        assertEquals(3, highlighted.spanStyles.size)
        assertEquals(listOf(0, 11, 22), highlighted.spanStyles.map { it.start })
        assertEquals(
            listOf(passive, active, passive),
            highlighted.spanStyles.map { it.item.background },
        )
    }
}
