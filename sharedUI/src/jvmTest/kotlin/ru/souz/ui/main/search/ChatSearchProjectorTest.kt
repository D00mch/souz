package ru.souz.ui.main.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.souz.ui.main.ChatMessage

class ChatSearchProjectorTest {

    private val projector = ChatSearchProjector()

    @Test
    fun `projector uses visible markdown text and keeps code blocks separate`() {
        val message = ChatMessage(
            text = "[Visible label](https://example.com/private-destination)\n\n```kotlin\nprintln(\"target\")\n```",
            isUser = false,
        )

        val projection = projector.project(message)

        assertEquals(2, projection.parts.size)

        val markdownPart = projection.parts[0] as MarkdownTextSearchPartProjection
        assertEquals("Visible label", markdownPart.searchableText.trim())
        assertFalse(markdownPart.searchableText.contains("private-destination"))

        val codePart = projection.parts[1] as CodeBlockSearchPartProjection
        assertEquals("kotlin", codePart.language)
        assertTrue(codePart.searchableText.contains("println(\"target\")"))
    }

    @Test
    fun `projector keeps user text as a single plain-text part`() {
        val message = ChatMessage(
            text = "alpha target beta",
            isUser = true,
        )

        val projection = projector.project(message)

        assertEquals(
            listOf(
                PlainTextSearchPartProjection(
                    partIndex = 0,
                    text = "alpha target beta",
                )
            ),
            projection.parts,
        )
    }
}
