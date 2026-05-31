package ru.souz.service.speech.ambient

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AmbientTranscriptBufferTest {

    @Test
    fun `volatile event is replaced and final event clears stale volatile`() {
        val buffer = AmbientTranscriptBuffer(maxFinalEvents = 10, ttlMs = 10_000, clock = { 1_000L })
        val firstVolatile = event(id = "v1", text = "hel", isFinal = false, receivedAtMs = 1_000)
        val secondVolatile = event(id = "v2", text = "hello", isFinal = false, receivedAtMs = 1_100)
        val final = event(id = "f1", text = "hello", isFinal = true, receivedAtMs = 1_200)

        buffer.append(firstVolatile)
        assertEquals(firstVolatile, buffer.snapshot().currentVolatile)

        buffer.append(secondVolatile)
        assertEquals(secondVolatile, buffer.snapshot().currentVolatile)
        assertEquals(emptyList(), buffer.snapshot().finalEvents)

        buffer.append(final)

        assertEquals(listOf(final), buffer.snapshot().finalEvents)
        assertNull(buffer.snapshot().currentVolatile)
    }

    @Test
    fun `final events are bounded by max count`() {
        val buffer = AmbientTranscriptBuffer(maxFinalEvents = 2, ttlMs = 10_000, clock = { 2_000L })
        val first = event(id = "f1", text = "one", isFinal = true, receivedAtMs = 1_000)
        val second = event(id = "f2", text = "two", isFinal = true, receivedAtMs = 1_100)
        val third = event(id = "f3", text = "three", isFinal = true, receivedAtMs = 1_200)

        buffer.append(first)
        buffer.append(second)
        buffer.append(third)

        assertEquals(listOf(second, third), buffer.snapshot().finalEvents)
    }

    @Test
    fun `prune removes expired final events but keeps current volatile`() {
        var now = 1_000L
        val buffer = AmbientTranscriptBuffer(maxFinalEvents = 10, ttlMs = 500, clock = { now })
        val oldFinal = event(id = "f1", text = "old", isFinal = true, receivedAtMs = 400)
        val freshFinal = event(id = "f2", text = "fresh", isFinal = true, receivedAtMs = 900)
        val volatile = event(id = "v1", text = "draft", isFinal = false, receivedAtMs = 300)

        buffer.append(oldFinal)
        buffer.append(freshFinal)
        buffer.append(volatile)
        now = 1_301L
        buffer.prune()

        val snapshot = buffer.snapshot()
        assertEquals(listOf(freshFinal), snapshot.finalEvents)
        assertEquals(volatile, snapshot.currentVolatile)
    }

    @Test
    fun `clear removes final and volatile events`() {
        val buffer = AmbientTranscriptBuffer(maxFinalEvents = 10, ttlMs = 10_000, clock = { 1_000L })
        buffer.append(event(id = "f1", text = "final", isFinal = true, receivedAtMs = 1_000))
        buffer.append(event(id = "v1", text = "volatile", isFinal = false, receivedAtMs = 1_100))

        buffer.clear()

        assertEquals(AmbientTranscriptSnapshot(finalEvents = emptyList(), currentVolatile = null), buffer.snapshot())
    }

    private fun event(
        id: String,
        text: String,
        isFinal: Boolean,
        receivedAtMs: Long,
    ): AmbientTranscriptEvent = AmbientTranscriptEvent(
        id = id,
        text = text,
        isFinal = isFinal,
        startedAtMs = null,
        endedAtMs = null,
        receivedAtMs = receivedAtMs,
    )
}
