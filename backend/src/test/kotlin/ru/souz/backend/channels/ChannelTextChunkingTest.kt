package ru.souz.backend.channels

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChannelTextChunkingTest {
    @Test
    fun `text exactly at the limit is not split`() {
        val text = "a".repeat(50)

        assertEquals(listOf(text), channelTextChunks(text, maxLength = 50))
    }

    @Test
    fun `text over the limit is split into multiple chunks that reassemble exactly`() {
        val text = "a".repeat(105)

        val chunks = channelTextChunks(text, maxLength = 50)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 50 })
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun `keeps emoji surrogate pairs together`() {
        val text = "a".repeat(4095) + "😀" + "b".repeat(4100)
        val chunks = channelTextChunks(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= CHANNEL_TEXT_LIMIT && !it.last().isHighSurrogate() })
    }

    @Test
    fun `prefers splitting on a newline near the limit`() {
        val text = "a".repeat(40) + "\n" + "b".repeat(40)

        val chunks = channelTextChunks(text, maxLength = 50)

        assertEquals(listOf("a".repeat(40) + "\n", "b".repeat(40)), chunks)
    }
}
