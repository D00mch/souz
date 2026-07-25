package ru.souz.agent.knowledge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class KnowledgeEntryTest {
    @Test
    fun `complete entry derives stored length`() {
        val entry = KnowledgeEntry(
            id = KNOWLEDGE_ID,
            sourceTool = "ReadFile",
            originalLength = 5,
            content = KnowledgeContent.Complete("hello"),
        )

        assertEquals(5, entry.storedLength)
        assertEquals("hello", assertIs<KnowledgeContent.Complete>(entry.content).content)
    }

    @Test
    fun `truncated entry derives stored length`() {
        val entry = KnowledgeEntry(
            id = KNOWLEDGE_ID,
            sourceTool = "Search",
            originalLength = 12,
            content = KnowledgeContent.Truncated(head = "head", tail = "tail"),
        )

        assertEquals(8, entry.storedLength)
    }

    @Test
    fun `empty complete entry is valid`() {
        val entry = KnowledgeEntry(
            id = KNOWLEDGE_ID,
            sourceTool = "Empty",
            originalLength = 0,
            content = KnowledgeContent.Complete(""),
        )

        assertEquals(0, entry.storedLength)
    }

    @Test
    fun `entry rejects inconsistent content lengths`() {
        assertFailsWith<IllegalArgumentException> {
            KnowledgeEntry(
                id = KNOWLEDGE_ID,
                sourceTool = "Invalid",
                originalLength = 3,
                content = KnowledgeContent.Complete("four"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            KnowledgeEntry(
                id = KNOWLEDGE_ID,
                sourceTool = "Invalid",
                originalLength = 8,
                content = KnowledgeContent.Truncated(head = "head", tail = "tail"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            KnowledgeEntry(
                id = KNOWLEDGE_ID,
                sourceTool = "Invalid",
                originalLength = 5,
                content = KnowledgeContent.Truncated(head = "", tail = "tail"),
            )
        }
    }

    @Test
    fun `entry requires canonical uuid`() {
        assertFailsWith<IllegalArgumentException> {
            KnowledgeEntry(
                id = KNOWLEDGE_ID.uppercase(),
                sourceTool = "Invalid",
                originalLength = 1,
                content = KnowledgeContent.Complete("x"),
            )
        }
    }

    private companion object {
        const val KNOWLEDGE_ID = "123e4567-e89b-12d3-a456-426614174000"
    }
}
