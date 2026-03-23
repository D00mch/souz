package ru.souz.tool.web

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import ru.souz.tool.BadInputException
import ru.souz.tool.web.internal.readLimitedBinaryBody
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebHttpSupportTest {
    @Test
    fun `read limited binary body returns bytes within limit`() = runTest {
        val payload = ByteArray(32 * 1024) { (it % 251).toByte() }

        val result = readLimitedBinaryBody(
            channel = ByteReadChannel(payload),
            maxBytes = payload.size,
            url = "https://example.com/image.png",
        )

        assertContentEquals(payload, result)
    }

    @Test
    fun `read limited binary body rejects oversized payload before buffering all bytes`() = runTest {
        val error = assertFailsWith<BadInputException> {
            readLimitedBinaryBody(
                channel = ByteReadChannel(ByteArray((1024 * 1024) + 1)),
                maxBytes = 1024 * 1024,
                url = "https://example.com/oversized.bin",
            )
        }

        assertTrue(error.message.orEmpty().contains("larger than 1MB"))
    }
}
