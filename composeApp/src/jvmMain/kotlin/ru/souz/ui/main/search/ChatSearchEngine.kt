package ru.souz.ui.main.search

import ru.souz.ui.main.ChatMessage

class ChatSearchEngine(
    private val projector: ChatSearchProjector = ChatSearchProjector(),
) {

    fun buildProjections(messages: List<ChatMessage>): Map<String, ChatMessageSearchProjection> =
        projector.buildProjections(messages)

    fun ensureProjections(
        messages: List<ChatMessage>,
        cached: Map<String, ChatMessageSearchProjection>,
    ): Map<String, ChatMessageSearchProjection> = if (
        cached.size == messages.size &&
        messages.all { cached.containsKey(it.id) }
    ) {
        cached
    } else {
        buildProjections(messages)
    }

    fun updateQuery(
        search: ChatSearchState,
        query: String,
    ): ChatSearchState = search.copy(
        query = query,
        currentIndex = 0,
    )

    fun next(search: ChatSearchState): ChatSearchState {
        val size = search.matches.size
        if (size == 0) return search
        return search.copy(currentIndex = (search.currentIndex + 1) % size)
    }

    fun previous(search: ChatSearchState): ChatSearchState {
        val size = search.matches.size
        if (size == 0) return search
        return search.copy(currentIndex = if (search.currentIndex == 0) size - 1 else search.currentIndex - 1)
    }

    fun reindex(
        messages: List<ChatMessage>,
        projections: Map<String, ChatMessageSearchProjection>,
        search: ChatSearchState,
    ): ChatSearchState {
        val normalizedQuery = search.normalizedQuery
        if (normalizedQuery.isEmpty()) {
            return search.copy(
                matches = emptyList(),
                currentIndex = 0,
            )
        }

        val matches = buildList {
            messages.forEachIndexed { messageIndex, message ->
                val projection = projections[message.id] ?: projector.project(message)
                var occurrenceIndexInMessage = 0
                projection.parts.forEach { part ->
                    part.searchableText.findSearchMatchRanges(normalizedQuery).forEach { range ->
                        add(
                            ChatSearchMatch(
                                messageId = message.id,
                                messageIndex = messageIndex,
                                occurrenceIndexInMessage = occurrenceIndexInMessage,
                                partIndex = part.partIndex,
                                rangeInPart = range,
                            )
                        )
                        occurrenceIndexInMessage += 1
                    }
                }
            }
        }

        val activeMatch = search.activeMatch
        val currentIndex = when {
            matches.isEmpty() -> 0
            activeMatch == null -> search.currentIndex.coerceIn(0, matches.lastIndex)
            else -> matches.indexOfFirst {
                it.messageId == activeMatch.messageId &&
                    it.occurrenceIndexInMessage == activeMatch.occurrenceIndexInMessage
            }.takeIf { it >= 0 } ?: search.currentIndex.coerceIn(0, matches.lastIndex)
        }

        return search.copy(
            matches = matches,
            currentIndex = currentIndex,
        )
    }
}

fun String.findSearchMatchRanges(query: String): List<SearchTextRange> {
    if (query.isBlank() || isEmpty()) return emptyList()

    val ranges = mutableListOf<SearchTextRange>()
    var currentIndex = 0
    while (currentIndex < length) {
        val matchIndex = indexOf(query, startIndex = currentIndex, ignoreCase = true)
        if (matchIndex < 0) break
        ranges += SearchTextRange(
            start = matchIndex,
            endExclusive = matchIndex + query.length,
        )
        currentIndex = matchIndex + query.length
    }
    return ranges
}
