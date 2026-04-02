package ru.souz.ui.main.search

import ru.souz.ui.common.MarkdownPart
import ru.souz.ui.common.parseMarkdownContent
import ru.souz.ui.main.ChatMessage

/**
 * Converts chat messages into searchable projections that stay aligned with how the UI renders
 * message content.
 *
 * User messages are treated as plain text. Assistant messages are split into markdown-text and
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
            listOf(
                PlainTextSearchPartProjection(
                    partIndex = 0,
                    text = message.text,
                )
            )
        } else {
            parseMarkdownContent(message.text).mapIndexed { index, part ->
                when (part) {
                    is MarkdownPart.TextContent -> MarkdownTextSearchPartProjection(
                        partIndex = index,
                        markdown = part.content,
                        searchableText = part.content.buildMarkdownSearchableText(),
                    )

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
}
