package ru.souz.tool.web

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import ru.souz.tool.BadInputException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebResearchClientTest {
    @Test
    fun `spaces sequential requests by minimum interval`() = runTest {
        var nowMillis = 0L
        val sleeps = mutableListOf<Long>()
        val client = WebResearchClient(
            httpGet = responsesOf(
                WebTextResponse(200, "<html><body>Page body</body></html>", emptyMap()),
                WebTextResponse(200, "<html><body>Page body</body></html>", emptyMap()),
            ),
            currentTimeMillis = { nowMillis },
            sleepMillis = { delayMillis ->
                sleeps += delayMillis
                nowMillis += delayMillis
            },
        )

        client.extractPageText("https://example.com/one", maxChars = 500)
        client.extractPageText("https://example.com/two", maxChars = 500)

        assertEquals(listOf(1_200L), sleeps)
    }

    @Test
    fun `does not rate limit different hosts together`() = runTest {
        var nowMillis = 0L
        val sleeps = mutableListOf<Long>()
        val client = WebResearchClient(
            httpGet = responsesOf(
                WebTextResponse(200, "<html><body>Page body</body></html>", emptyMap()),
                WebTextResponse(200, "<html><body>Page body</body></html>", emptyMap()),
            ),
            currentTimeMillis = { nowMillis },
            sleepMillis = { delayMillis ->
                sleeps += delayMillis
                nowMillis += delayMillis
            },
        )

        client.extractPageText("https://example.com/one", maxChars = 500)
        client.extractPageText("https://other.example/two", maxChars = 500)

        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `parses duckduckgo redirects into search results`() = runTest {
        val responses = ArrayDeque(
            listOf(
                WebTextResponse(
                    statusCode = 200,
                    body = """
                        <html>
                          <body>
                            <div class="result">
                              <a class="result__a" href="/l/?uddg=https%3A%2F%2Fexample.com%2Freport%3Fref%3Dabc%26lang%3Den">Example report</a>
                              <div class="result__snippet">Useful snippet</div>
                            </div>
                          </body>
                        </html>
                    """.trimIndent(),
                    headers = emptyMap(),
                ),
            )
        )
        val client = WebResearchClient(
            httpGet = { _, _, _ -> responses.removeFirst() },
        )

        val results = client.searchWeb("example report", limit = 1)

        assertEquals(1, results.size)
        assertEquals("Example report", results.single().title)
        assertEquals("https://example.com/report?ref=abc&lang=en", results.single().url)
        assertEquals("Useful snippet", results.single().snippet)
        assertTrue(responses.isEmpty())
    }

    @Test
    fun `falls back to html duckduckgo endpoint when primary one fails`() = runTest {
        val responses = ArrayDeque(
            listOf(
                Result.failure<WebTextResponse>(BadInputException("primary endpoint failed")),
                Result.success(
                    WebTextResponse(
                        statusCode = 200,
                        body = """
                            <html>
                              <body>
                                <article data-testid="result">
                                  <h2><a data-testid="result-title-a" href="/l/?uddg=https%3A%2F%2Fexample.com%2Ffallback">Fallback result</a></h2>
                                  <div class="result-snippet">Fallback snippet</div>
                                </article>
                              </body>
                            </html>
                        """.trimIndent(),
                        headers = emptyMap(),
                    )
                ),
            )
        )
        val requestedUrls = mutableListOf<String>()
        val requestedRetries = mutableListOf<Boolean>()
        val client = WebResearchClient(
            httpGet = { url, _, retry ->
                requestedUrls += url
                requestedRetries += retry
                responses.removeFirst().getOrThrow()
            },
        )

        val results = client.searchWeb("fallback result", limit = 1)

        assertEquals(listOf("https://example.com/fallback"), results.map { it.url })
        assertEquals(2, requestedUrls.size)
        assertEquals(listOf(false, false), requestedRetries)
        assertTrue(requestedUrls.first().startsWith("https://duckduckgo.com/html/"))
        assertTrue(requestedUrls.last().startsWith("https://html.duckduckgo.com/html/"))
    }

    @Test
    fun `propagates cancellation instead of returning empty results`() = runTest {
        val client = WebResearchClient(
            httpGet = { _, _, _ -> throw CancellationException("cancel search") },
        )

        assertFailsWith<CancellationException> {
            client.searchWeb("cancelled", limit = 1)
        }
    }

    @Test
    fun `challenge page aborts immediately with provider blocked`() = runTest {
        val requestedUrls = mutableListOf<String>()
        val client = WebResearchClient(
            httpGet = { url, _, _ ->
                requestedUrls += url
                WebTextResponse(
                    statusCode = 200,
                    body = """
                        <html>
                          <head><title>DuckDuckGo</title></head>
                          <body>
                            Unfortunately, bots use DuckDuckGo too.
                            Please complete the following challenge to confirm this search was made by a human.
                            Select all squares containing a duck.
                          </body>
                        </html>
                    """.trimIndent(),
                    headers = emptyMap(),
                )
            },
        )

        val error = assertFailsWith<WebSearchProviderException> {
            client.searchWeb("blocked", limit = 1)
        }

        assertEquals(WebSearchProviderFailureKind.BLOCKED, error.kind)
        assertEquals(1, requestedUrls.size)
    }

    @Test
    fun `unavailable provider aborts after short timeout series`() = runTest {
        val requestedUrls = mutableListOf<String>()
        val client = WebResearchClient(
            httpGet = { url, _, _ ->
                requestedUrls += url
                throw BadInputException("HTTP request timed out for $url")
            },
        )

        val error = assertFailsWith<WebSearchProviderException> {
            client.searchWeb("timeout burst", limit = 1)
        }

        assertEquals(WebSearchProviderFailureKind.UNAVAILABLE, error.kind)
        assertEquals(4, requestedUrls.size)
    }

    private fun responsesOf(vararg items: WebTextResponse): suspend (String, Long, Boolean) -> WebTextResponse {
        val queue = ArrayDeque(items.toList())
        return { _, _, _ ->
            queue.removeFirst()
        }
    }
}
