package ru.souz.ui.main.search

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

data class ChatSearchMatch(
    val messageId: String,
    val messageIndex: Int,
    val occurrenceIndexInMessage: Int,
    val partIndex: Int,
    val rangeInPart: SearchTextRange,
)

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

sealed interface ChatMessageSearchPartProjection {
    val partIndex: Int
    val searchableText: String
}

data class PlainTextSearchPartProjection(
    override val partIndex: Int,
    val text: String,
) : ChatMessageSearchPartProjection {
    override val searchableText: String = text
}

data class MarkdownTextSearchPartProjection(
    override val partIndex: Int,
    val markdown: String,
    override val searchableText: String,
) : ChatMessageSearchPartProjection

data class CodeBlockSearchPartProjection(
    override val partIndex: Int,
    val language: String,
    val code: String,
) : ChatMessageSearchPartProjection {
    override val searchableText: String = code
}

data class ChatMessageSearchProjection(
    val messageId: String,
    val parts: List<ChatMessageSearchPartProjection>,
)

fun ChatSearchState.matchesForMessage(messageId: String): List<ChatSearchMatch> =
    matches.filter { it.messageId == messageId }

fun ChatSearchState.matchRangesForPart(
    messageId: String,
    partIndex: Int,
): List<SearchTextRange> = matches.asSequence()
    .filter { it.messageId == messageId && it.partIndex == partIndex }
    .map { it.rangeInPart }
    .toList()

fun ChatSearchState.activeRangeForPart(
    messageId: String,
    partIndex: Int,
): SearchTextRange? = activeMatch
    ?.takeIf { it.messageId == messageId && it.partIndex == partIndex }
    ?.rangeInPart
