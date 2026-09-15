package ru.souz.backend.vk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VkTextChunkingTest {
    @Test
    fun `text exactly at the limit is not split`() {
        val text = "a".repeat(50)

        assertEquals(listOf(text), vkTextChunks(text, maxLength = 50))
    }

    @Test
    fun `text over the limit is split into multiple chunks that reassemble exactly`() {
        val text = "a".repeat(105)

        val chunks = vkTextChunks(text, maxLength = 50)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 50 })
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun `prefers splitting on a newline near the limit`() {
        val text = "a".repeat(40) + "\n" + "b".repeat(40)

        val chunks = vkTextChunks(text, maxLength = 50)

        assertEquals(listOf("a".repeat(40) + "\n", "b".repeat(40)), chunks)
    }
}
