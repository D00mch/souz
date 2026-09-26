package ru.souz.backend.vk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VkMarkdownTest {
    @Test
    fun `nested styles and reference links use code point offsets after emoji`() {
        val chunk = VkMarkdown("😀 **Привет, *мир*!** [**сайт**][ref]\n\n[ref]: https://example.org/a_(b)?x=1&y=2").chunks().single()
        assertEquals("😀 Привет, мир! сайт", chunk.text)
        assertEquals(setOf(
            VkFormatItem("bold", 2, 12), VkFormatItem("italic", 10, 3),
            VkFormatItem("bold", 15, 4), VkFormatItem("url", 15, 4, "https://example.org/a_(b)?x=1&y=2"),
        ), chunk.format.toSet())
    }

    @Test
    fun `code escaped syntax unsupported links and incomplete Markdown remain readable`() {
        for ((source, expected) in listOf(
            "`**literal**` \\*escaped\\*\n\n```kotlin\nval x = \"[a](b)\"\n```" to "**literal** *escaped*\n\nval x = \"[a](b)\"",
            "[почта](mailto:a@example.com)" to "почта (mailto:a@example.com)",
            "**незакрыто <b>HTML</b>" to "**незакрыто <b>HTML</b>",
            " " to "Готово.",
        )) {
            assertEquals(VkTextChunk(expected, emptyList()), VkMarkdown(source).chunks().single())
        }
    }

    @Test
    fun `headings nested lists and quotes retain block layout`() {
        val chunk = VkMarkdown("# Заголовок\n\n3. первый\n4. второй\n   - внутри\n5. третий\n\n> цитата").chunks().single()
        assertEquals("Заголовок\n\n3. первый\n4. второй\n  • внутри\n5. третий\n\n▎ цитата", chunk.text)
        assertEquals(listOf(VkFormatItem("bold", 0, 9)), chunk.format)
    }

    @Test
    fun `splits rendered text and clips overlapping ranges without breaking emoji`() {
        val chunks = VkMarkdown("x [**${"😀".repeat(12)}**](https://example.org) y").chunks(maxLength = 10)
        assertEquals(listOf("x " + "😀".repeat(4), "😀".repeat(5), "😀".repeat(3) + " y"), chunks.map { it.text })
        for ((chunk, range) in chunks.zip(listOf(2 to 4, 0 to 5, 0 to 3))) {
            assertEquals(setOf(
                VkFormatItem("bold", range.first, range.second),
                VkFormatItem("url", range.first, range.second, "https://example.org"),
            ), chunk.format.toSet())
        }
        val mentions = VkMarkdown("@".repeat(20)).chunks(maxLength = 10)
        assertEquals("@".repeat(20), mentions.joinToString("") { it.text })
        assertTrue(mentions.all { it.text.length * 2 <= 10 })
    }
}
