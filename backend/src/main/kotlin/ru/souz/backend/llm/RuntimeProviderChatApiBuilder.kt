package ru.souz.backend.llm

import ru.souz.backend.app.BackendProviderRetryPolicy
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LlmProvider
import ru.souz.llms.TokenLogging
import ru.souz.llms.anthropic.AnthropicChatAPI
import ru.souz.llms.giga.GigaAuth
import ru.souz.llms.giga.GigaRestChatAPI
import ru.souz.llms.openai.OpenAIChatAPI
import ru.souz.llms.qwen.QwenChatAPI
import ru.souz.llms.tunnel.AiTunnelChatAPI

class RuntimeProviderChatApiBuilder(
    private val tokenLogging: TokenLogging,
    private val retryPolicy: BackendProviderRetryPolicy,
) : ProviderChatApiBuilder {
    override fun build(
        provider: LlmProvider,
        settingsProvider: SettingsProvider,
        sharedTransport: SharedProviderTransport,
        executionContext: BackendLlmExecutionContext,
    ): LLMChatAPI {
        val api = when (provider) {
            LlmProvider.GIGA -> GigaRestChatAPI(GigaAuth(settingsProvider), settingsProvider, tokenLogging)
            LlmProvider.QWEN -> QwenChatAPI(settingsProvider, tokenLogging)
            LlmProvider.AI_TUNNEL -> AiTunnelChatAPI(settingsProvider, tokenLogging)
            LlmProvider.ANTHROPIC -> AnthropicChatAPI(settingsProvider, tokenLogging)
            LlmProvider.OPENAI -> OpenAIChatAPI(settingsProvider, tokenLogging)
            LlmProvider.LOCAL -> error("Local provider is handled separately.")
        }
        return RetryingLlmChatApi(
            delegate = api,
            provider = provider,
            retryPolicy = retryPolicy,
        )
    }
}
