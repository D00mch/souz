package ru.souz.ui.main.search

import ru.souz.ui.common.MarkdownPart
import ru.souz.ui.common.parseMarkdownContent
import ru.souz.ui.main.ChatMessage

class ChatSearchProjector {

    fun buildProjections(messages: List<ChatMessage>): Map<String, ChatMessageSearchProjection> =
        messages.associate { message -> message.id to project(message) }

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
