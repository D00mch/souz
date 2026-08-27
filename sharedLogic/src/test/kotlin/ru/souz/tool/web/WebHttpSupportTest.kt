package ru.souz.tool.web

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import ru.souz.tool.BadInputException
import ru.souz.tool.web.internal.WebHttpSupport
import ru.souz.tool.web.internal.WebToolSupport
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebHttpSupportTest {
    private val webHttpSupport = WebHttpSupport()

    @Test
    fun `configured user agent is sent by the web client`() = runTest {
        var observedUserAgent: String? = null
        val webToolSupport = WebToolSupport(userAgent = "ProxyApprovedClient/1.0")
        val client = HttpClient(
            MockEngine { request ->
                observedUserAgent = request.headers[HttpHeaders.UserAgent]
                respond("ok")
            },
        ) {
            WebHttpSupport.applyDefaults(this, webToolSupport)
        }

        try {
            WebHttpSupport(webToolSupport, client).getText("https://example.com", timeoutMillis = 1_000L)
            assertEquals("ProxyApprovedClient/1.0", observedUserAgent)
        } finally {
            client.close()
        }
    }

    @Test
    fun `read limited binary body returns bytes within limit`() = runTest {
        val payload = ByteArray(32 * 1024) { (it % 251).toByte() }

        val result = webHttpSupport.readLimitedBinaryBody(
            channel = ByteReadChannel(payload),
            maxBytes = payload.size,
            url = "https://example.com/image.png",
        )

        assertContentEquals(payload, result)
    }

    @Test
    fun `read limited binary body rejects oversized payload before buffering all bytes`() = runTest {
        val error = assertFailsWith<BadInputException> {
            webHttpSupport.readLimitedBinaryBody(
                channel = ByteReadChannel(ByteArray((1024 * 1024) + 1)),
                maxBytes = 1024 * 1024,
                url = "https://example.com/oversized.bin",
            )
        }

        assertTrue(error.message.orEmpty().contains("larger than 1MB"))
    }
}
