package ru.souz.llms

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.jackson.jackson
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import ru.souz.db.SettingsProvider
import ru.souz.llms.giga.GigaAuth
import ru.souz.llms.giga.GigaRestChatAPI
import kotlin.test.Test
import kotlin.test.assertEquals

class GigaRestChatAPIAccessTokenTest {
    @Test
    fun `distinct API keys keep distinct access tokens on a shared HTTP client`() = runTest {
        val tokenRequests = mutableListOf<String>()
        val apiAccessTokens = mutableListOf<String>()
        val engine = MockEngine { request ->
            val authorization = requireNotNull(request.headers[HttpHeaders.Authorization])
            if (request.url.host == "ngw.devices.sberbank.ru") {
                val apiKey = authorization.removePrefix("Basic ")
                tokenRequests += apiKey
                respondJson("""{"access_token":"token-$apiKey","expires_at":4102444800000}""")
            } else {
                apiAccessTokens += authorization.removePrefix("Bearer ")
                respondJson("""{"balance":[]}""")
            }
        }
        val httpClient = HttpClient(engine) {
            install(HttpTimeout)
            install(ContentNegotiation) { jackson() }
        }
        val settings = mockk<SettingsProvider>(relaxed = true) {
            every { requestTimeoutMillis } returns 1_000L
        }

        httpClient.use {
            val firstApi = gigaApi(settings, apiKey = "first-key", httpClient)
            val secondApi = gigaApi(settings, apiKey = "second-key", httpClient)

            firstApi.balance()
            secondApi.balance()
            firstApi.balance()
        }

        assertEquals(listOf("first-key", "second-key"), tokenRequests)
        assertEquals(listOf("token-first-key", "token-second-key", "token-first-key"), apiAccessTokens)
    }

    @Test
    fun `settings backed API refreshes token after API key changes`() = runTest {
        val tokenRequests = mutableListOf<String>()
        val apiAccessTokens = mutableListOf<String>()
        val engine = MockEngine { request ->
            val authorization = requireNotNull(request.headers[HttpHeaders.Authorization])
            if (request.url.host == "ngw.devices.sberbank.ru") {
                val apiKey = authorization.removePrefix("Basic ")
                tokenRequests += apiKey
                respondJson("""{"access_token":"token-$apiKey","expires_at":4102444800000}""")
            } else {
                apiAccessTokens += authorization.removePrefix("Bearer ")
                respondJson("""{"balance":[]}""")
            }
        }
        val httpClient = HttpClient(engine) {
            install(HttpTimeout)
            install(ContentNegotiation) { jackson() }
        }
        val settings = mockk<SettingsProvider>(relaxed = true) {
            every { requestTimeoutMillis } returns 1_000L
            every { gigaChatKey } returnsMany listOf("first-key", "second-key")
        }

        httpClient.use {
            val api = GigaRestChatAPI(
                auth = GigaAuth(settings, httpClient),
                settingsProvider = settings,
                tokenLogging = NoopTokenLogging,
                httpClient = httpClient,
            )
            api.balance()
            api.balance()
        }

        assertEquals(listOf("first-key", "second-key"), tokenRequests)
        assertEquals(listOf("token-first-key", "token-second-key"), apiAccessTokens)
    }

    private fun gigaApi(
        settings: SettingsProvider,
        apiKey: String,
        httpClient: HttpClient,
    ): GigaRestChatAPI = GigaRestChatAPI(
        auth = GigaAuth(settings, httpClient),
        settingsProvider = settings,
        tokenLogging = NoopTokenLogging,
        apiKey = apiKey,
        httpClient = httpClient,
    )

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(body: String) =
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
}

private object NoopTokenLogging : TokenLogging {
    override suspend fun logTokenUsage(result: LLMResponse.Chat.Ok, body: LLMRequest.Chat) = Unit
}
