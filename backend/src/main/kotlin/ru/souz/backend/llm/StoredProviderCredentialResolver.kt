package ru.souz.backend.llm

import ru.souz.backend.config.BackendSettingsConfig
import ru.souz.backend.keys.service.UserProviderKeyService
import ru.souz.llms.LlmProvider
import ru.souz.llms.codex.CodexOAuthCredentialStore

class StoredProviderCredentialResolver(
    private val settingsConfig: BackendSettingsConfig,
    private val userProviderKeyService: UserProviderKeyService,
    private val codexOAuthCredentialStore: CodexOAuthCredentialStore,
) : ProviderCredentialResolver {
    override suspend fun resolve(
        userId: String,
        provider: LlmProvider,
    ): ResolvedProviderCredential? {
        if (provider != LlmProvider.CODEX) {
            userProviderKeyService.decrypt(userId, provider)?.let { apiKey ->
                return ResolvedProviderCredential(
                    provider = provider,
                    apiKey = apiKey,
                    source = CredentialSource.USER_MANAGED,
                )
            }
        }
        val serverManaged = when (provider) {
            LlmProvider.GIGA -> settingsConfig.gigaChatKey
            LlmProvider.QWEN -> settingsConfig.qwenChatKey
            LlmProvider.AI_TUNNEL -> settingsConfig.aiTunnelKey
            LlmProvider.ANTHROPIC -> settingsConfig.anthropicKey
            LlmProvider.OPENAI -> settingsConfig.openaiKey
            LlmProvider.LOCAL -> null
            LlmProvider.CODEX -> codexOAuthCredentialStore.load()
                ?.takeIf { credentials -> credentials.isCompleteRotatingSet }
                ?.accessToken
        }
        return serverManaged
            ?.takeIf { it.isNotBlank() }
            ?.let {
                ResolvedProviderCredential(
                    provider = provider,
                    apiKey = it,
                    source = CredentialSource.SERVER_MANAGED,
                )
            }
    }
}
