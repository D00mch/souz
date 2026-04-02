package ru.souz.ui.main.search

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.souz.ui.main.ChatMessage

class ChatSearchEngineTest {

    private val engine = ChatSearchEngine()

    @Test
    fun `reindex tracks every occurrence across message parts`() {
        val userMessage = ChatMessage(
            text = "target and target",
            isUser = true,
        )
        val assistantMessage = ChatMessage(
            text = "prefix target\n```kotlin\ntarget\n```",
            isUser = false,
        )
        val messages = listOf(userMessage, assistantMessage)

        val search = engine.reindex(
            messages = messages,
            projections = engine.buildProjections(messages),
            search = ChatSearchState(query = "target"),
        )

        assertEquals(4, search.matches.size)
        assertEquals(
            listOf(
                Triple(userMessage.id, 0, 0),
                Triple(userMessage.id, 0, 1),
                Triple(assistantMessage.id, 0, 0),
                Triple(assistantMessage.id, 1, 1),
            ),
            search.matches.map { Triple(it.messageId, it.partIndex, it.occurrenceIndexInMessage) },
        )
        assertEquals(
            listOf(
                SearchTextRange(0, 6),
                SearchTextRange(11, 17),
                SearchTextRange(7, 13),
                SearchTextRange(0, 6),
            ),
            search.matches.map { it.rangeInPart },
        )
    }

    @Test
    fun `reindex keeps active occurrence by message and ordinal`() {
        val original = ChatMessage(
            text = "alpha target and target",
            isUser = true,
        )
        val updated = original.copy(text = "target and alpha target")

        val initialSearch = engine.reindex(
            messages = listOf(original),
            projections = engine.buildProjections(listOf(original)),
            search = ChatSearchState(query = "target"),
        ).copy(currentIndex = 1)

        val reindexed = engine.reindex(
            messages = listOf(updated),
            projections = engine.buildProjections(listOf(updated)),
            search = initialSearch,
        )

        assertEquals(1, reindexed.currentIndex)
        assertEquals(SearchTextRange(17, 23), reindexed.activeMatch?.rangeInPart)
    }
}
