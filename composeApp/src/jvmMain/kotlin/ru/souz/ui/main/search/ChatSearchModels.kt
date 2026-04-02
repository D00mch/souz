package ru.souz.ui.main.search

/**
 * Half-open text range `[start, endExclusive)` used by search indexing and highlighting.
 *
 * Ranges are always relative to a single searchable part, not to the whole message.
 */
data class SearchTextRange(
    val start: Int,
    val endExclusive: Int,
) {
    init {
        require(start >= 0) { "start must be >= 0" }
        require(endExclusive >= start) { "endExclusive must be >= start" }
    }

    val length: Int
        get() = endExclusive - start

    fun overlaps(other: SearchTextRange): Boolean =
        start < other.endExclusive && endExclusive > other.start
}

/**
 * Describes a single search hit inside the projected message content.
 *
 * [occurrenceIndexInMessage] is stable within a message and is used to preserve the active match
 * when search results are recalculated after message updates.
 */
data class ChatSearchMatch(
    val messageId: String,
    val messageIndex: Int,
    val occurrenceIndexInMessage: Int,
    val partIndex: Int,
    val rangeInPart: SearchTextRange,
)

/**
 * UI-facing search state for the current chat session.
 *
 * The raw [query] is preserved for the text field, while indexing uses [normalizedQuery].
 */
data class ChatSearchState(
    val query: String = "",
    val matches: List<ChatSearchMatch> = emptyList(),
    val currentIndex: Int = 0,
) {
    val normalizedQuery: String
        get() = query.trim()

    val activeMatch: ChatSearchMatch?
        get() = matches.getOrNull(currentIndex)
}

/**
 * Searchable projection of one rendered chat-message part.
 *
 * [partIndex] must stay aligned with the rendered part order used by `MainScreen`, because match
 * ranges and active-result highlighting are resolved against this index.
 */
sealed interface ChatMessageSearchPartProjection {
    val partIndex: Int
    val searchableText: String
}

/**
 * Plain text projection used for user messages and any assistant content rendered without
 * markdown/code block structure.
 */
data class PlainTextSearchPartProjection(
    override val partIndex: Int,
    val text: String,
) : ChatMessageSearchPartProjection {
    override val searchableText: String = text
}

/**
 * Markdown text projection for assistant text blocks.
 *
 * [markdown] keeps the original source for rendering, while [searchableText] stores the flattened
 * visible text used during indexing so match offsets line up with rendered markdown content.
 */
data class MarkdownTextSearchPartProjection(
    override val partIndex: Int,
    val markdown: String,
    override val searchableText: String,
) : ChatMessageSearchPartProjection

/**
 * Code-block projection for assistant markdown fences.
 */
data class CodeBlockSearchPartProjection(
    override val partIndex: Int,
    val language: String,
    val code: String,
) : ChatMessageSearchPartProjection {
    override val searchableText: String = code
}

/**
 * Search projection for a single chat message.
 */
data class ChatMessageSearchProjection(
    val messageId: String,
    val parts: List<ChatMessageSearchPartProjection>,
)

/**
 * Returns all match ranges for one rendered part of a message.
 */
fun ChatSearchState.matchRangesForPart(
    messageId: String,
    partIndex: Int,
): List<SearchTextRange> = matches.asSequence()
    .filter { it.messageId == messageId && it.partIndex == partIndex }
    .map { it.rangeInPart }
    .toList()

/**
 * Returns the active match range for one rendered part, if the current selection points to it.
 */
fun ChatSearchState.activeRangeForPart(
    messageId: String,
    partIndex: Int,
): SearchTextRange? = activeMatch
    ?.takeIf { it.messageId == messageId && it.partIndex == partIndex }
    ?.rangeInPart
