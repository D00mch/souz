package ru.souz.backend.llm

import ru.souz.backend.app.BackendProviderRetryPolicy
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LlmProvider
import ru.souz.llms.ProviderSettings
import ru.souz.llms.TokenLogging
import ru.souz.llms.anthropic.AnthropicChatAPI
import ru.souz.llms.codex.CodexChatAPI
import ru.souz.llms.codex.CodexOAuthService
import ru.souz.llms.giga.GigaAuth
import ru.souz.llms.giga.GigaRestChatAPI
import ru.souz.llms.openai.OpenAIChatAPI
import ru.souz.llms.qwen.QwenChatAPI
import ru.souz.llms.tunnel.AiTunnelChatAPI

class RuntimeProviderChatApiBuilder(
    private val tokenLogging: TokenLogging,
    private val retryPolicy: BackendProviderRetryPolicy,
    private val codexOAuthService: CodexOAuthService,
    private val providerHttpClients: BackendProviderHttpClients,
) : ProviderChatApiBuilder {
    override fun build(
        provider: LlmProvider,
        settingsProvider: ProviderSettings,
        apiKey: String,
    ): LLMChatAPI {
        check(provider != LlmProvider.LOCAL) { "Local provider is handled separately." }
        val httpClient = providerHttpClients.clientFor(provider)
        val api = when (provider) {
            LlmProvider.GIGA -> GigaRestChatAPI(
                auth = GigaAuth(settingsProvider, httpClient),
                settingsProvider = settingsProvider,
                tokenLogging = tokenLogging,
                apiKey = apiKey,
                httpClient = httpClient,
            )
            LlmProvider.QWEN -> QwenChatAPI(settingsProvider, tokenLogging, apiKey, httpClient)
            LlmProvider.AI_TUNNEL -> AiTunnelChatAPI(settingsProvider, tokenLogging, apiKey, httpClient)
            LlmProvider.ANTHROPIC -> AnthropicChatAPI(settingsProvider, tokenLogging, apiKey, httpClient)
            LlmProvider.OPENAI -> OpenAIChatAPI(settingsProvider, tokenLogging, apiKey, httpClient)
            LlmProvider.LOCAL -> error("Unreachable after the local provider check.")
            LlmProvider.CODEX -> CodexChatAPI(
                settingsProvider = settingsProvider,
                tokenLogging = tokenLogging,
                oauthService = codexOAuthService,
                providedHttpClient = httpClient,
            )
        }
        return RetryingLlmChatApi(
            delegate = api,
            provider = provider,
            retryPolicy = retryPolicy,
        )
    }
}
