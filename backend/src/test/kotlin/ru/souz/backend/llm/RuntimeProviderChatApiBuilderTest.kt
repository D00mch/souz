package ru.souz.backend.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import ru.souz.backend.TestSettingsProvider
import ru.souz.backend.app.BackendProviderRetryPolicy
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
import ru.souz.llms.TokenLogging
import ru.souz.llms.codex.CodexOAuthService

class RuntimeProviderChatApiBuilderTest {
    @Test
    fun `builder creates backend Codex chat API`() = runTest {
        val settingsProvider = TestSettingsProvider().apply {
            codexAccessToken = "server-codex-token"
            codexRefreshToken = "server-codex-refresh-token"
            codexAccountId = "account-id"
            codexExpiresAt = 1_800_000_000L
        }
        val api = RuntimeProviderChatApiBuilder(
            tokenLogging = NoopTokenLogging,
            retryPolicy = BackendProviderRetryPolicy(max429Retries = 0),
            codexOAuthService = CodexOAuthService(settingsProvider),
        ).build(
            provider = LlmProvider.CODEX,
            settingsProvider = settingsProvider,
            sharedTransport = SharedProviderTransport("codex"),
            executionContext = BackendLlmExecutionContext("user-a", "exec-a", settingsProvider),
        )

        val response = assertIs<LLMResponse.Embeddings.Error>(
            api.embeddings(LLMRequest.Embeddings(input = listOf("hello")))
        )

        assertEquals("Codex provider does not support embeddings", response.message)
    }
}

private object NoopTokenLogging : TokenLogging {
    override suspend fun logTokenUsage(result: LLMResponse.Chat.Ok, body: LLMRequest.Chat) = Unit
}
