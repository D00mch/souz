package ru.souz.ui.main.search

import ru.souz.ui.common.MarkdownPart
import ru.souz.ui.common.parseMarkdownContent
import ru.souz.ui.main.ChatMessage
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * Converts chat messages into searchable projections that stay aligned with how the UI renders
 * message content.
 *
 * User messages retain plain text outside GFM tables. Assistant messages are split into markdown-text and
 * code-block parts so indexing, navigation, and highlighting can work per rendered block.
 */
class ChatSearchProjector {

    /**
     * Builds projections for the current message list keyed by message id.
     */
    fun buildProjections(messages: List<ChatMessage>): Map<String, ChatMessageSearchProjection> =
        messages.associate { message -> message.id to project(message) }

    /**
     * Projects one message into searchable parts.
     *
     * For markdown assistant content, `searchableText` is derived from visible markdown text rather
     * than raw source so match ranges line up with rendered content.
     */
    fun project(message: ChatMessage): ChatMessageSearchProjection {
        val parts = if (message.isUser) {
            projectUserText(message.text)
        } else {
            parseMarkdownContent(message.text).mapIndexed { index, part ->
                when (part) {
                    is MarkdownPart.TextContent -> projectMarkdown(index, part.content)

                    is MarkdownPart.CodeContent -> CodeBlockSearchPartProjection(
                        partIndex = index,
                        language = part.language,
                        code = part.code,
                    )
                }
            }
        }

        return ChatMessageSearchProjection(
            messageId = message.id,
            parts = parts,
        )
    }

    private fun projectUserText(text: String): List<ChatMessageSearchPartProjection> {
        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(text)
        val tables = tree.children.filter { it.type == GFMElementTypes.TABLE }
        if (tables.isEmpty()) return listOf(PlainTextSearchPartProjection(0, text))
        return buildList {
            var offset = 0
            tables.forEach { table ->
                if (offset < table.startOffset) {
                    add(PlainTextSearchPartProjection(size, text.substring(offset, table.startOffset)))
                }
                add(projectMarkdown(size, text.substring(table.startOffset, table.endOffset)))
                offset = table.endOffset
            }
            if (offset < text.length) add(PlainTextSearchPartProjection(size, text.substring(offset)))
        }
    }

    private fun projectMarkdown(index: Int, markdown: String): MarkdownTextSearchPartProjection {
        val projection = markdown.buildMarkdownSearchProjection()
        return MarkdownTextSearchPartProjection(index, markdown, projection.text, projection.leafRanges)
    }
}
