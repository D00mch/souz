package ru.souz.backend.vk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VkMarkdownTest {
    @Test
    fun `renders nested emphasis with code point offsets after emoji`() {
        val chunk = VkMarkdown.chunks("😀 **Привет, *мир*!**").single()
        assertEquals("😀 Привет, мир!", chunk.text)
        assertEquals(listOf(VkFormatItem("bold", 2, 12), VkFormatItem("italic", 10, 3)), chunk.format?.items)
    }

    @Test
    fun `parses links with parentheses references and formatted labels`() {
        val chunk = VkMarkdown.chunks("[**Билет**](https://example.com/a_(b)?x=1&y=2) и [сайт][ref]\n\n[ref]: https://example.org").single()
        assertEquals("Билет и сайт", chunk.text)
        val links = chunk.format!!.items.filter { it.type == "url" }
        assertEquals(listOf("https://example.com/a_(b)?x=1&y=2", "https://example.org"), links.map { it.url })
        assertEquals(listOf(0, 8), links.map { it.offset })
        assertTrue(VkFormatItem("bold", 0, 5) in chunk.format.items)
    }

    @Test
    fun `code and escaped punctuation are literal`() {
        val chunk = VkMarkdown.chunks("`**literal**` \\*escaped\\*\n\n```kotlin\nval x = \"[a](b)\"\n``` ").single()
        assertEquals("**literal** *escaped*\n\nval x = \"[a](b)\"", chunk.text)
        assertNull(chunk.format)
    }

    @Test
    fun `keeps block layout headings ordered and nested lists readable`() {
        val chunk = VkMarkdown.chunks("# Заголовок\n\n3. первый\n4. второй\n   - внутри\n\n> цитата").single()
        assertTrue(chunk.text.startsWith("Заголовок\n\n3. первый\n4. второй\n  • внутри"), chunk.text)
        assertTrue(chunk.text.endsWith("▎ цитата"))
        assertEquals(VkFormatItem("bold", 0, 9), chunk.format?.items?.single())
    }

    @Test
    fun `splits rendered text and clips formatting instead of splitting Markdown`() {
        val chunks = VkMarkdown.chunks("**" + "😀".repeat(12) + "**", maxLength = 10)
        assertEquals("😀".repeat(12), chunks.joinToString("") { it.text })
        assertEquals(listOf(5, 5, 2), chunks.map { it.format!!.items.single().length })
        assertTrue(chunks.all { it.format!!.items.single().offset == 0 && it.text.length <= 10 })
    }

    @Test
    fun `link spanning chunks keeps its destination and rebased ranges`() {
        val chunks = VkMarkdown.chunks("[abcdefghijklmnop](https://example.com)", maxLength = 6)
        assertEquals(listOf("abcdef", "ghijkl", "mnop"), chunks.map { it.text })
        assertTrue(chunks.all {
            it.format!!.items.single() == VkFormatItem("url", 0, it.text.length, "https://example.com")
        })
    }

    @Test
    fun `preserves plain URLs malformed Markdown and raw HTML without interpreting it`() {
        val source = "**незакрыто [текст https://example.com?a=1&b=2 <b>HTML</b>"
        assertEquals(source, VkMarkdown.chunks(source).single().text)
        assertNull(VkMarkdown.chunks(source).single().format)
    }

    @Test
    fun `unsupported link schemes retain the address as text`() {
        val chunk = VkMarkdown.chunks("[почта](mailto:a@example.com)").single()
        assertEquals("почта (mailto:a@example.com)", chunk.text)
        assertNull(chunk.format)
    }

    @Test
    fun `at symbols and empty input respect delivery constraints`() {
        val chunks = VkMarkdown.chunks("@".repeat(20), maxLength = 10)
        assertTrue(chunks.all { it.text.length * 2 <= 10 })
        assertEquals("@".repeat(20), chunks.joinToString("") { it.text })
        assertEquals("Готово.", VkMarkdown.chunks("  ").single().text)
    }
}
