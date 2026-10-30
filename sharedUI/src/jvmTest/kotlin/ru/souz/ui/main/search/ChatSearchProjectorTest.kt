package ru.souz.ui.main.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertIs
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

    @Test
    fun `user tables render markdown while surrounding text stays literal`() {
        val table = "| Name | Value |\n| :--- | ---: |\n| **Bold** | `code` |"
        val before = "**literal** [link](unchanged)\n\n"
        val after = "\n\n*literal too*"
        val parts = projector.project(ChatMessage(text = before + table + after, isUser = true)).parts

        assertEquals(listOf(0, 1, 2), parts.map { it.partIndex })
        assertEquals(before, assertIs<PlainTextSearchPartProjection>(parts[0]).text)
        assertEquals(table, assertIs<MarkdownTextSearchPartProjection>(parts[1]).markdown)
        assertEquals("Name\tValue\nBold\tcode", parts[1].searchableText)
        assertEquals(after, assertIs<PlainTextSearchPartProjection>(parts[2]).text)
    }

    @Test
    fun `pipes invalid tables and fenced examples retain literal user text`() {
        val text = "a | b\nnot a delimiter\n\n```md\n| A | B |\n| --- | --- |\n| C | D |\n```"
        assertEquals(
            listOf(PlainTextSearchPartProjection(0, text)),
            projector.project(ChatMessage(text = text, isUser = true)).parts,
        )
    }

    @Test
    fun `table search includes inline labels and excludes delimiters destinations and excess cells`() {
        val text = "| A | B |\n| --- | --- |\n| **Bold** and *em* | [label](https://example.com/hidden) | ignored |\n| a\\|b | `code` |"
        for (isUser in listOf(true, false)) {
            val part = projector.project(ChatMessage(text = text, isUser = isUser)).parts.single()
            assertEquals("A\tB\nBold and em\tlabel\na|b\tcode", part.searchableText)
        }
    }
}
