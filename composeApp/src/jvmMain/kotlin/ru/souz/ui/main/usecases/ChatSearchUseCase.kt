package ru.souz.ui.main.usecases

import ru.souz.ui.main.ChatMessage
import ru.souz.ui.main.ChatSearchResult
import ru.souz.ui.main.ChatSearchState
import ru.souz.ui.common.buildSearchableMessageText
import ru.souz.ui.common.findSearchMatchStartIndexes

class ChatSearchUseCase {

    fun open(search: ChatSearchState): ChatSearchState = search.copy(
        isOpen = true,
        activationToken = search.activationToken + 1,
        selectAllOnActivate = search.normalizedQuery.isNotEmpty(),
    )

    fun close(search: ChatSearchState): ChatSearchState = search.copy(
        isOpen = false,
        selectAllOnActivate = false,
    )

    fun clear(): ChatSearchState = ChatSearchState()

    fun updateQuery(
        messages: List<ChatMessage>,
        search: ChatSearchState,
        query: String,
    ): ChatSearchState = sync(
        messages = messages,
        search = search.copy(
            query = query,
            currentIndex = 0,
            selectAllOnActivate = false,
        ),
    )

    fun next(search: ChatSearchState): ChatSearchState {
        val size = search.results.size
        if (size == 0) return search
        return search.copy(currentIndex = (search.currentIndex + 1) % size)
    }

    fun previous(search: ChatSearchState): ChatSearchState {
        val size = search.results.size
        if (size == 0) return search
        return search.copy(currentIndex = if (search.currentIndex == 0) size - 1 else search.currentIndex - 1)
    }

    fun sync(
        messages: List<ChatMessage>,
        search: ChatSearchState,
    ): ChatSearchState {
        val normalizedQuery = search.normalizedQuery
        if (normalizedQuery.isEmpty()) {
            return search.copy(
                results = emptyList(),
                currentIndex = 0,
            )
        }

        val results: List<ChatSearchResult> = messages.flatMapIndexed { messageIndex, message ->
            val searchableText = buildSearchableMessageText(
                text = message.text,
                isUserMessage = message.isUser,
            )
            List(searchableText.findSearchMatchStartIndexes(query = normalizedQuery).size) { matchIndexInMessage ->
                ChatSearchResult(
                    messageId = message.id,
                    messageIndex = messageIndex,
                    matchIndexInMessage = matchIndexInMessage,
                )
            }
        }
        val activeResult = search.activeResult
        val syncedIndex = when {
            results.isEmpty() -> 0
            activeResult == null -> search.currentIndex.coerceIn(0, results.lastIndex)
            else -> results.indexOfFirst {
                it.messageId == activeResult.messageId && it.matchIndexInMessage == activeResult.matchIndexInMessage
            }
                .takeIf { it >= 0 }
                ?: search.currentIndex.coerceIn(0, results.lastIndex)
        }

        return search.copy(
            results = results,
            currentIndex = syncedIndex,
        )
    }
}
