package ru.souz.tool.presentation

import io.mockk.every
import io.mockk.mockk
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebResearchClientTest {
    @Test
    fun `spaces sequential requests by minimum interval`() {
        var nowMillis = 0L
        val sleeps = mutableListOf<Long>()
        val client = WebResearchClient(
            currentTimeMillis = { nowMillis },
            sleepMillis = { delayMillis ->
                sleeps += delayMillis
                nowMillis += delayMillis
            },
            requestSender = {
                response(
                    status = 200,
                    responseBody = "<html><body>Page body</body></html>",
                )
            },
        )

        client.extractPageText("https://example.com/one", maxChars = 500)
        client.extractPageText("https://example.com/two", maxChars = 500)

        assertEquals(listOf(1_200L), sleeps)
    }

    @Test
    fun `retries retryable responses with retry after delay`() {
        var nowMillis = 0L
        val sleeps = mutableListOf<Long>()
        val responses = ArrayDeque(
            listOf(
                response(
                    status = 429,
                    responseBody = "Too many requests",
                    headers = mapOf("Retry-After" to listOf("3")),
                ),
                response(
                    status = 200,
                    responseBody = "<html><body>Recovered page</body></html>",
                ),
            )
        )
        val client = WebResearchClient(
            currentTimeMillis = { nowMillis },
            sleepMillis = { delayMillis ->
                sleeps += delayMillis
                nowMillis += delayMillis
            },
            requestSender = { _: HttpRequest ->
                responses.removeFirst()
            },
        )

        val text = client.extractPageText("https://example.com/retry", maxChars = 500)

        assertEquals("Recovered page", text)
        assertEquals(listOf(3_000L), sleeps)
        assertTrue(responses.isEmpty())
    }

    private fun response(
        status: Int,
        responseBody: String,
        headers: Map<String, List<String>> = emptyMap(),
    ): HttpResponse<String> {
        val httpHeaders = HttpHeaders.of(headers) { _, _ -> true }
        return mockk {
            every { statusCode() } returns status
            every { body() } returns responseBody
            every { headers() } returns httpHeaders
        }
    }
}
