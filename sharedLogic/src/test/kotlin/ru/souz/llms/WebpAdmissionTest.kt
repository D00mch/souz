package ru.souz.llms

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import ru.souz.llms.runtime.ImageSource
import ru.souz.llms.runtime.InvalidImageException

class WebpAdmissionTest {
    @Test
    fun `still WebP accepts matching canvas and rejects malformed chunks and animation`() = runTest {
        val frame = lossless.copyOfRange(12, lossless.size)
        val extended = riff(canvas(), frame)
        for (bytes in listOf(lossless, extended)) {
            val source = ImageSource.read(bytes)
            assertEquals("image/webp", source.mimeType)
            assertContentEquals(bytes, source.bytes)
        }
        val invalid = listOf(
            extended.copyOf(extended.size - 1),
            extended.copyOf().apply { this[lastIndex] = 1 },
            extended.copyOf().apply { this[34] = 0xff.toByte() },
            riff(canvas(), frame, frame),
            riff(canvas(flags = 2), frame),
            riff(canvas(), "ANMF".encodeToByteArray() + le32(0), frame),
        )
        for (bytes in invalid) {
            assertEquals("image_decode_failed", assertFailsWith<InvalidImageException> { ImageSource.read(bytes) }.code)
        }
    }

    @Test
    fun `WebP mismatch rejection and large lossy decode fit in a constrained JVM`() {
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx128m", "-Djava.awt.headless=true",
            "-cp", checkNotNull(System.getProperty("souz.test.runtimeClasspath")), javaClass.name,
        ).redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "WebP validation subprocess timed out")
            assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    companion object {
        private val lossless = Base64.getDecoder().decode("UklGRh4AAABXRUJQVlA4TBEAAAAvAAAAAAfQ//73v/+BiOh/AAA=")

        @JvmStatic
        fun main(args: Array<String>): Unit = runBlocking {
            val lossyMismatch = Base64.getDecoder().decode("UklGRigAAABXRUJQVlA4WAoAAAAAAAAAAAAAAAAAVlA4IAoAAAAQAACdASr/P/8/")
            val losslessMismatch = riff(canvas(width = 2), lossless.copyOfRange(12, lossless.size))
            for (bytes in listOf(lossyMismatch, losslessMismatch)) {
                assertEquals("image_decode_failed", assertFailsWith<InvalidImageException> { ImageSource.read(bytes) }.code)
            }
            val bytes = checkNotNull(WebpAdmissionTest::class.java.getResourceAsStream("/images/large-lossy.webp"))
                .use { it.readBytes() }
            val source = ImageSource.read(bytes)
            assertEquals("image/webp", source.mimeType)
            assertContentEquals(bytes, source.bytes)
            assertTrue(source.forVision().startsWith("data:image/png;base64,"))
        }

        private fun le32(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

        private fun canvas(width: Int = 1, flags: Int = 0): ByteArray =
            "VP8X".encodeToByteArray() + le32(10) + ByteArray(10).apply {
                this[0] = flags.toByte()
                this[4] = (width - 1).toByte()
            }

        private fun riff(vararg chunks: ByteArray): ByteArray =
            "RIFF".encodeToByteArray() + le32(4 + chunks.sumOf { it.size }) +
                "WEBP".encodeToByteArray() + chunks.reduce(ByteArray::plus)
    }
}
