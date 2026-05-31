package ru.souz.ui.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MarkdownUtilsTest {
    @Test
    fun `parseMarkdownContent splits text and fenced code blocks`() {
        val parts = parseMarkdownContent(
            """
            Before

            ```kotlin
            val answer = 42
            ```

            After
            """.trimIndent(),
        )

        assertEquals(3, parts.size)
        assertEquals("Before", assertIs<MarkdownPart.TextContent>(parts[0]).content.trim())
        val code = assertIs<MarkdownPart.CodeContent>(parts[1])
        assertEquals("kotlin", code.language)
        assertEquals("val answer = 42", code.code)
        assertEquals("After", assertIs<MarkdownPart.TextContent>(parts[2]).content.trim())
    }

    @Test
    fun `parseMarkdownContent preserves glued fence language prefixes`() {
        val parts = parseMarkdownContent(
            """
            ```kotlin title=Example.kt
            fun main() = Unit
            ```
            """.trimIndent(),
        )

        val code = assertIs<MarkdownPart.CodeContent>(parts.single())
        assertEquals("kotlin", code.language)
        assertEquals("title=Example.kt\nfun main() = Unit", code.code)
    }
}
