package ru.souz.backend.llm

import ru.souz.llms.LlmProvider

enum class CredentialSource {
    SERVER_MANAGED,
    USER_MANAGED,
}

data class ResolvedProviderCredential(
    val provider: LlmProvider,
    val apiKey: String,
    val source: CredentialSource,
)

interface ProviderCredentialResolver {
    suspend fun resolve(
        userId: String,
        provider: LlmProvider,
    ): ResolvedProviderCredential?
}
