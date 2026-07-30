package ru.souz.agent.knowledge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KnowledgeEntryTest {
    @Test
    fun `valid entries derive stored length`() {
        assertEquals(5, entry(originalLength = 5, content = KnowledgeContent.Complete("hello")).storedLength)
        assertEquals(
            8,
            entry(
                originalLength = 12,
                content = KnowledgeContent.Truncated(head = "head", tail = "tail"),
            ).storedLength,
        )
        assertEquals(0, entry(originalLength = 0, content = KnowledgeContent.Complete("")).storedLength)
    }

    @Test
    fun `invalid entries are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            entry(
                originalLength = 3,
                content = KnowledgeContent.Complete("four"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            entry(
                originalLength = 8,
                content = KnowledgeContent.Truncated(head = "head", tail = "tail"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            entry(
                originalLength = 5,
                content = KnowledgeContent.Truncated(head = "", tail = "tail"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            entry(
                id = KNOWLEDGE_ID.uppercase(),
                originalLength = 1,
                content = KnowledgeContent.Complete("x"),
            )
        }
    }

    private fun entry(
        originalLength: Int,
        content: KnowledgeContent,
        id: String = KNOWLEDGE_ID,
    ): KnowledgeEntry = KnowledgeEntry(
        id = id,
        sourceTool = "Tool",
        originalLength = originalLength,
        content = content,
    )

    private companion object {
        const val KNOWLEDGE_ID = "123e4567-e89b-12d3-a456-426614174000"
    }
}
