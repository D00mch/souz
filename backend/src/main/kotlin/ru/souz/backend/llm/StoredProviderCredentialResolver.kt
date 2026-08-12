package ru.souz.backend.llm

import ru.souz.backend.common.BackendLlmSupport
import ru.souz.backend.keys.service.UserProviderKeyService
import ru.souz.db.SettingsProvider
import ru.souz.llms.LlmProvider

class StoredProviderCredentialResolver(
    private val baseSettingsProvider: SettingsProvider,
    private val userProviderKeyService: UserProviderKeyService,
) : ProviderCredentialResolver {
    override suspend fun resolve(
        userId: String,
        provider: LlmProvider,
    ): ResolvedProviderCredential? {
        if (provider == LlmProvider.CODEX) {
            return baseSettingsProvider.codexAccessToken
                .takeIf { baseSettingsProvider.hasCompleteCodexOAuthCredentials() }
                ?.let { accessToken ->
                    ResolvedProviderCredential(
                        provider = provider,
                        apiKey = accessToken,
                        source = CredentialSource.SERVER_MANAGED,
                    )
                }
        }
        if (provider !in BackendLlmSupport.userManagedKeyProviders) {
            return null
        }
        userProviderKeyService.decrypt(userId, provider)?.let { apiKey ->
            return ResolvedProviderCredential(
                provider = provider,
                apiKey = apiKey,
                source = CredentialSource.USER_MANAGED,
            )
        }
        val serverManaged = when (provider) {
            LlmProvider.QWEN -> baseSettingsProvider.qwenChatKey
            LlmProvider.AI_TUNNEL -> baseSettingsProvider.aiTunnelKey
            LlmProvider.ANTHROPIC -> baseSettingsProvider.anthropicKey
            LlmProvider.OPENAI -> baseSettingsProvider.openaiKey
            LlmProvider.GIGA,
            LlmProvider.LOCAL,
            LlmProvider.CODEX,
            -> null
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
