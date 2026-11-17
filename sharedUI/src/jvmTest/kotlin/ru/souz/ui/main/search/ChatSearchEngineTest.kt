package ru.souz.ui.main.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import ru.souz.ui.main.ChatMessage

class ChatSearchEngineTest {

    private val engine = ChatSearchEngine()

    @Test
    fun `projection refresh reuses unchanged messages and drops removed entries`() {
        val first = ChatMessage(text = "**target**", isUser = false)
        val second = ChatMessage(text = "target\n\n```kotlin\ntarget\n```", isUser = false)
        val removed = ChatMessage(text = "removed", isUser = true)
        val cached = engine.buildProjections(listOf(first, second, removed))
        val metadataOnly = first.copy(timestamp = first.timestamp + 1, agentActions = listOf("done"))
        val updated = second.copy(text = "new **target**\n\n```kotlin\nupdated target\n```")
        val added = ChatMessage(text = "target", isUser = true)
        val messages = listOf(updated, added, metadataOnly)

        val projections = engine.ensureProjections(messages, cached)

        assertEquals(messages.map { it.id }, projections.keys.toList())
        assertSame(cached[first.id], projections[first.id])
        assertNotSame(cached[second.id], projections[second.id])
        assertEquals(engine.buildProjections(messages), projections)
        assertSame(projections, engine.ensureProjections(messages, projections))
        assertEquals(emptyMap(), engine.ensureProjections(emptyList(), projections))
    }

    @Test
    fun `same message ids still invalidate edited text and role`() {
        val original = ChatMessage(text = "**target**", isUser = false)
        val cached = engine.buildProjections(listOf(original))
        for (updated in listOf(original.copy(text = "new target"), original.copy(isUser = true))) {
            val projections = engine.ensureProjections(listOf(updated), cached)
            assertNotSame(cached[original.id], projections[original.id])
            assertEquals(engine.buildProjections(listOf(updated)), projections)
            assertEquals(
                engine.reindex(listOf(updated), projections, ChatSearchState(query = "target")),
                engine.reindex(listOf(updated), cached, ChatSearchState(query = "target")),
            )
        }
    }

    @Test
    fun `reindex without projections preserves markdown code and table matches`() {
        val messages = listOf(
            ChatMessage(text = "[target](https://example.com/hidden)\n\n```kotlin\ntarget\n```", isUser = false),
            ChatMessage(text = "| Label |\n| --- |\n| **target** |", isUser = true),
        )
        val search = ChatSearchState(query = "target")
        val indexed = engine.reindex(messages, engine.buildProjections(messages), search)

        assertEquals(3, indexed.matches.size)
        assertEquals(indexed, engine.reindex(messages, emptyMap(), search))
    }

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
