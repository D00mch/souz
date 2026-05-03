package ru.souz.backend.llm

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
        userProviderKeyService.decrypt(userId, provider)?.let { apiKey ->
            return ResolvedProviderCredential(
                provider = provider,
                apiKey = apiKey,
                source = CredentialSource.USER_MANAGED,
            )
        }
        val serverManaged = when (provider) {
            LlmProvider.GIGA -> baseSettingsProvider.gigaChatKey
            LlmProvider.QWEN -> baseSettingsProvider.qwenChatKey
            LlmProvider.AI_TUNNEL -> baseSettingsProvider.aiTunnelKey
            LlmProvider.ANTHROPIC -> baseSettingsProvider.anthropicKey
            LlmProvider.OPENAI -> baseSettingsProvider.openaiKey
            LlmProvider.LOCAL -> null
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
