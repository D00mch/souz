package ru.souz.llms

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import ru.souz.db.SettingsProvider
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.llms.openai.OpenAIChatAPI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProviderRequestLifecycleTest {
    @Test
    fun `shared client keeps concurrent adapter credentials and timeouts isolated`() = runTest {
        val requests = mutableListOf<Pair<String?, Long?>>()
        val requestsMutex = Mutex()
        val client = HttpClient(
            MockEngine { request ->
                requestsMutex.withLock {
                    requests += request.headers[HttpHeaders.Authorization] to
                        request.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis
                }
                respond(
                    content = OPEN_AI_RESPONSE,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        ) {
            providerHttpClientDefaults()
        }
        val first = OpenAIChatAPI(
            openAiSettings(apiKey = { "key-a" }, timeoutMillis = { 1_000L }),
            client,
        )
        val second = OpenAIChatAPI(
            openAiSettings(apiKey = { "key-b" }, timeoutMillis = { 2_000L }),
            client,
        )

        listOf(
            async { first.message(chatRequest()) },
            async { second.message(chatRequest()) },
        ).awaitAll()

        assertEquals(
            setOf("Bearer key-a" to 1_000L, "Bearer key-b" to 2_000L),
            requests.toSet(),
        )
        client.close()
    }

    @Test
    fun `provider reads credentials and timeout for each request`() = runTest {
        var apiKey = "key-a"
        var requestTimeoutMillis = 1_000L
        val authorizations = mutableListOf<String?>()
        val timeouts = mutableListOf<Long?>()
        val settings = openAiSettings(
            apiKey = { apiKey },
            timeoutMillis = { requestTimeoutMillis },
        )
        val client = HttpClient(
            MockEngine { request ->
                authorizations += request.headers[HttpHeaders.Authorization]
                timeouts += request.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis
                respond(
                    content = OPEN_AI_RESPONSE,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        ) {
            providerHttpClientDefaults()
        }
        val api = OpenAIChatAPI(settings, client)

        api.message(chatRequest())
        apiKey = "key-b"
        requestTimeoutMillis = 2_000L
        api.message(chatRequest())

        assertEquals(listOf<String?>("Bearer key-a", "Bearer key-b"), authorizations)
        assertEquals(listOf<Long?>(1_000L, 2_000L), timeouts)
        client.close()
    }

    @Test
    fun `provider message propagates cancellation`() = runTest {
        val cancellation = CancellationException("cancelled")
        val client = cancellingClient(cancellation)
        val api = OpenAIChatAPI(openAiSettings(), client)

        assertFailsWith<CancellationException> { api.message(chatRequest()) }
        client.close()
    }

    @Test
    fun `provider stream propagates cancellation`() = runTest {
        val cancellation = CancellationException("cancelled")
        val client = cancellingClient(cancellation)
        val api = OpenAIChatAPI(openAiSettings(), client)

        assertFailsWith<CancellationException> {
            api.messageStream(chatRequest()).toList()
        }
        client.close()
    }

    private fun cancellingClient(cancellation: CancellationException): HttpClient =
        HttpClient(MockEngine { throw cancellation }) {
            providerHttpClientDefaults()
        }

    private fun openAiSettings(
        apiKey: () -> String = { "test-key" },
        timeoutMillis: () -> Long = { 1_000L },
    ): SettingsProvider = mockk<SettingsProvider>(relaxed = true) {
        every { openaiKey } answers { apiKey() }
        every { openaiBaseUrl } returns "https://openai.test/v1"
        every { requestTimeoutMillis } answers { timeoutMillis() }
    }

    private fun chatRequest() = LLMRequest.Chat(
        model = LLMModel.OpenAIGpt5Nano.alias,
        messages = listOf(LLMRequest.Message(LLMMessageRole.user, "hello")),
    )

    private companion object {
        const val OPEN_AI_RESPONSE =
            """{"choices":[],"created":1,"model":"gpt-5-nano","usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3,"precached_prompt_tokens":0}}"""
    }
}
