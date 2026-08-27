package ru.souz.llms.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.llms.restJsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAICompatibleMessageAPITest {

    @Test
    fun `trailing slash in base url does not produce a double slash path`() = runTest {
        var requestedUrl: String? = null
        val client = HttpClient(
            MockEngine { request ->
                requestedUrl = request.url.toString()
                respond(OK_RESPONSE, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            },
        ) {
            providerHttpClientDefaults()
        }
        val api = OpenAICompatibleMessageAPI(
            client = client,
            apiKey = "test-key",
            baseUrl = "https://openrouter.ai/api/v1/",
            model = "google/gemini-3.7-flash",
        )

        api.message(chatRequest())

        assertEquals("https://openrouter.ai/api/v1/chat/completions", requestedUrl)
        client.close()
    }

    @Test
    fun `reasoning field is omitted by default`() = runTest {
        var requestBody: String? = null
        val client = HttpClient(
            MockEngine { request ->
                requestBody = String(request.body.toByteArray())
                respond(OK_RESPONSE, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            },
        ) {
            providerHttpClientDefaults()
        }
        val api = OpenAICompatibleMessageAPI(
            client = client,
            apiKey = "test-key",
            baseUrl = "https://example.test/v1",
            model = "some-model",
        )

        api.message(chatRequest())

        val node = restJsonMapper.readTree(requestBody)
        assertFalse(node.has("reasoning"))
        client.close()
    }

    @Test
    fun `reasoning field is included when configured`() = runTest {
        var requestBody: String? = null
        val client = HttpClient(
            MockEngine { request ->
                requestBody = String(request.body.toByteArray())
                respond(OK_RESPONSE, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            },
        ) {
            providerHttpClientDefaults()
        }
        val api = OpenAICompatibleMessageAPI(
            client = client,
            apiKey = "test-key",
            baseUrl = "https://example.test/v1",
            model = "some-model",
            reasoningEffort = "low",
        )

        api.message(chatRequest())

        val node = restJsonMapper.readTree(requestBody)
        assertTrue(node.has("reasoning"))
        assertEquals("low", node.path("reasoning").path("effort").asText())
        client.close()
    }

    @Test
    fun `message propagates cancellation instead of returning an error`() = runTest {
        val client = HttpClient(MockEngine { throw CancellationException("cancelled") }) {
            providerHttpClientDefaults()
        }
        val api = OpenAICompatibleMessageAPI(
            client = client,
            apiKey = "test-key",
            baseUrl = "https://example.test/v1",
            model = "some-model",
        )

        assertFailsWith<CancellationException> { api.message(chatRequest()) }
        client.close()
    }

    private fun chatRequest() = LLMRequest.Chat(
        model = "unused",
        messages = listOf(LLMRequest.Message(LLMMessageRole.user, "hello")),
    )

    private companion object {
        const val OK_RESPONSE =
            """{"choices":[{"message":{"content":"hi"}}],"created":1,"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
    }
}
