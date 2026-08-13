package ru.souz.backend.llm

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import ru.souz.backend.TestSettingsProvider
import ru.souz.backend.app.BackendProviderRetryPolicy
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
import ru.souz.llms.TokenLogging
import ru.souz.llms.codex.CodexOAuthService

class RuntimeProviderChatApiBuilderTest {
    @Test
    fun `builder uses shared HTTP client with API scoped credentials`() = runTest {
        val authorizationHeaders = mutableListOf<String>()
        val settingsProvider = TestSettingsProvider()
        val providerHttpClients = BackendProviderHttpClients { provider ->
            HttpClient(
                MockEngine { request ->
                    if (provider == LlmProvider.OPENAI) {
                        authorizationHeaders += requireNotNull(request.headers[HttpHeaders.Authorization])
                    }
                    respond(
                        content = openAiResponse,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            ) {
                install(HttpTimeout)
                install(ContentNegotiation) { jackson() }
            }
        }

        providerHttpClients.use {
            val builder = RuntimeProviderChatApiBuilder(
                tokenLogging = NoopTokenLogging,
                retryPolicy = BackendProviderRetryPolicy(max429Retries = 0),
                codexOAuthService = CodexOAuthService(settingsProvider),
                providerHttpClients = providerHttpClients,
            )
            assertIs<LLMResponse.Chat.Ok>(
                builder.build(LlmProvider.OPENAI, settingsProvider, "first-key").message(openAiRequest)
            )
            assertIs<LLMResponse.Chat.Ok>(
                builder.build(LlmProvider.OPENAI, settingsProvider, "second-key").message(openAiRequest)
            )
        }

        assertEquals(listOf("Bearer first-key", "Bearer second-key"), authorizationHeaders)
    }

    @Test
    fun `builder creates backend Codex chat API`() = runTest {
        val settingsProvider = TestSettingsProvider().apply {
            codexAccessToken = "server-codex-token"
            codexRefreshToken = "server-codex-refresh-token"
            codexAccountId = "account-id"
            codexExpiresAt = 1_800_000_000L
        }
        BackendProviderHttpClients().use { providerHttpClients ->
            val api = RuntimeProviderChatApiBuilder(
                tokenLogging = NoopTokenLogging,
                retryPolicy = BackendProviderRetryPolicy(max429Retries = 0),
                codexOAuthService = CodexOAuthService(settingsProvider),
                providerHttpClients = providerHttpClients,
            ).build(
                provider = LlmProvider.CODEX,
                settingsProvider = settingsProvider,
                apiKey = "server-codex-token",
            )
            val response = assertIs<LLMResponse.Embeddings.Error>(
                api.embeddings(LLMRequest.Embeddings(input = listOf("hello")))
            )

            assertEquals("Codex provider does not support embeddings", response.message)
        }
    }
}

private val openAiRequest = LLMRequest.Chat(
    model = LLMModel.OpenAIGpt52.alias,
    messages = listOf(LLMRequest.Message(LLMMessageRole.user, "hello")),
)

private val openAiResponse = """
    {
      "choices": [
        {
          "message": {"role": "assistant", "content": "hello"},
          "index": 0,
          "finish_reason": "stop"
        }
      ],
      "created": 1,
      "model": "gpt-5.2",
      "usage": {
        "prompt_tokens": 1,
        "completion_tokens": 1,
        "total_tokens": 2,
        "precached_prompt_tokens": 0
      }
    }
""".trimIndent()

private object NoopTokenLogging : TokenLogging {
    override suspend fun logTokenUsage(result: LLMResponse.Chat.Ok, body: LLMRequest.Chat) = Unit
}
