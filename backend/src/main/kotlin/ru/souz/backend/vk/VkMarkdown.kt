package ru.souz.backend.vk

import com.fasterxml.jackson.annotation.JsonInclude
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import ru.souz.backend.channels.CHANNEL_TEXT_LIMIT
import ru.souz.backend.channels.channelTextChunks

@JsonInclude(JsonInclude.Include.NON_NULL)
data class VkFormatItem(val type: String, val offset: Int, val length: Int, val url: String? = null)
internal data class VkTextChunk(val text: String, val format: List<VkFormatItem>)

private val parser = Parser.builder().build()

/** CommonMark owns parsing; VK receives plain text and Unicode code-point ranges. */
internal class VkMarkdown(private val markdown: String) {
    private val text = StringBuilder()
    private val ranges = mutableListOf<VkFormatItem>()

    init { render(parser.parse(markdown)) }

    fun chunks(maxLength: Int = CHANNEL_TEXT_LIMIT): List<VkTextChunk> {
        val rendered = text.toString().trimEnd('\n').ifBlank { markdown.ifBlank { "Готово." } }
        var start = 0
        // VK counts @ specially. Reserving a second unit per @ keeps these chunks within its limit.
        val limit = if ('@' in rendered) maxLength / 2 else maxLength
        return channelTextChunks(rendered, limit).map { part ->
            val end = start + part.length
            val items = ranges.mapNotNull { range ->
                val from = maxOf(start, range.offset)
                val to = minOf(end, range.offset + range.length)
                if (from >= to) null else range.copy(
                    offset = rendered.codePointCount(start, from),
                    length = rendered.codePointCount(from, to),
                )
            }.distinct().sortedWith(compareBy({ it.offset }, { -it.length }, { it.type }))
            start = end
            VkTextChunk(part, items)
        }
    }

    private fun render(node: Node) {
        when (node) {
            is Text -> text.append(node.literal)
            is Code -> text.append(node.literal)
            is SoftLineBreak, is HardLineBreak -> text.append('\n')
            is StrongEmphasis -> styled(node, "bold")
            is Emphasis -> styled(node, "italic")
            is Link -> link(node, node.destination)
            is Image -> link(node, node.destination)
            is Heading -> { styled(node, "bold"); newline(2) }
            is Paragraph -> { children(node); newline(if (node.parent is ListItem) 1 else 2) }
            is FencedCodeBlock -> { text.append(node.literal); newline(2) }
            is IndentedCodeBlock -> { text.append(node.literal); newline(2) }
            is HtmlInline -> text.append(node.literal)
            is HtmlBlock -> { text.append(node.literal); newline(2) }
            is ThematicBreak -> { text.append("───"); newline(2) }
            is BulletList, is OrderedList -> { children(node); newline(if (node.parent is ListItem) 1 else 2) }
            is ListItem -> listItem(node)
            is BlockQuote -> { text.append("▎ "); children(node); newline(2) }
            else -> children(node)
        }
    }

    private fun children(node: Node) {
        generateSequence(node.firstChild) { it.next }.forEach(::render)
    }

    private fun styled(node: Node, type: String, url: String? = null) {
        val start = text.length
        children(node)
        if (text.length > start) ranges += VkFormatItem(type, start, text.length - start, url)
    }

    private fun link(node: Node, destination: String) {
        if (destination.startsWith("https://", true) || destination.startsWith("http://", true)) {
            val start = text.length
            styled(node, "url", destination)
            if (text.length == start) text.append(destination)
        } else {
            children(node)
            text.append(" (").append(destination).append(')')
        }
    }

    private fun listItem(node: ListItem) {
        if (text.isNotEmpty()) newline(1)
        val depth = generateSequence(node.parent) { it.parent }.count { it is ListItem }
        text.append("  ".repeat(depth))
        val parent = node.parent
        if (parent is OrderedList) {
            val ordinal = parent.markerStartNumber + generateSequence(node.previous) { it.previous }.count()
            text.append(ordinal).append(". ")
        } else text.append("• ")
        children(node)
        newline(1)
    }

    private fun newline(count: Int) {
        var existing = 0
        while (existing < text.length && text[text.length - existing - 1] == '\n') existing++
        repeat((count - existing).coerceAtLeast(0)) { text.append('\n') }
    }
}
