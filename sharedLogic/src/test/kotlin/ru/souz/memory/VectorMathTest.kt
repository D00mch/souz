package ru.souz.memory

import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class VectorMathTest {
    @Test
    fun `memory defaults to little endian and desktop can retain big endian`() {
        val vector = floatArrayOf(1f, -2f)
        val littleEndian = byteArrayOf(0, 0, -128, 63, 0, 0, 0, -64)
        val bigEndian = byteArrayOf(63, -128, 0, 0, -64, 0, 0, 0)

        assertContentEquals(littleEndian, vector.toBlob())
        assertContentEquals(vector, littleEndian.toFloatArray())
        assertContentEquals(bigEndian, vector.toBlob(ByteOrder.BIG_ENDIAN))
        assertContentEquals(vector, bigEndian.toFloatArray(ByteOrder.BIG_ENDIAN))
    }

    @Test
    fun `cosine accumulates in double before rounding or desktop score rescaling`() {
        val large = floatArrayOf(Float.MAX_VALUE, 0f)
        val tiny = floatArrayOf(Float.MIN_VALUE, 0f)
        assertEquals(1f, cosineSimilarity(large, large))
        assertEquals(1f, cosineSimilarity(tiny, tiny))
        assertEquals(-1f, cosineSimilarity(large, floatArrayOf(-Float.MAX_VALUE, 0f)))
        assertEquals(0f, cosineSimilarity(large, floatArrayOf(0f, 1f)))
        assertEquals(0f, cosineSimilarity(large, floatArrayOf(0f, 0f)))
        assertEquals(0.0, cosineSimilarityDouble(large, floatArrayOf(1f)))
        assertEquals(0.6, cosineSimilarityDouble(floatArrayOf(1f, 0f), floatArrayOf(3f, 4f)))
        val vector = floatArrayOf(0.1f, 0.2f, 0.3f)
        assertEquals(1.0, cosineSimilarityDouble(vector, vector), 1e-15)
    }
}
