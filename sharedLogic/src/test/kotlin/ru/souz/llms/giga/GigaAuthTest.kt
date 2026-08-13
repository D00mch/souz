package ru.souz.llms.giga

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import ru.souz.db.SettingsProvider
import ru.souz.llms.http.providerHttpClientDefaults
import kotlin.test.Test
import kotlin.test.assertEquals

class GigaAuthTest {
    @Test
    fun `token cache is scoped by key and scope`() = runTest {
        val authorizations = mutableListOf<String?>()
        val client = tokenClient(authorizations)
        val auth = GigaAuth(settingsProvider(), client)

        assertEquals("token-1", auth.requestToken("key-a", "scope-a"))
        assertEquals("token-1", auth.requestToken("key-a", "scope-a"))
        assertEquals("token-2", auth.requestToken("key-b", "scope-a"))
        assertEquals("token-3", auth.requestToken("key-a", "scope-a"))
        assertEquals(listOf<String?>("Basic key-a", "Basic key-b", "Basic key-a"), authorizations)
        client.close()
    }

    private fun tokenClient(authorizations: MutableList<String?>): HttpClient {
        var responseIndex = 0
        return HttpClient(
            MockEngine { request ->
                authorizations += request.headers[HttpHeaders.Authorization]
                responseIndex += 1
                respond(
                    content = """{"access_token":"token-$responseIndex","expires_at":4102444800000}""",
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        ) {
            providerHttpClientDefaults()
        }
    }

    private fun settingsProvider(): SettingsProvider = mockk<SettingsProvider> {
        every { requestTimeoutMillis } returns 1_000L
    }
}
