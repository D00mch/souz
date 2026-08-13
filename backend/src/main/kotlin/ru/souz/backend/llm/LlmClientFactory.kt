package ru.souz.backend.llm

import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
import ru.souz.llms.ProviderSettings
import ru.souz.llms.findLLMModel

data class BackendLlmExecutionContext(
    val userId: String,
    val executionId: String,
    val settingsProvider: ProviderSettings,
)

data class SharedProviderTransport(
    val id: String,
)

interface ProviderChatApiBuilder {
    fun build(
        provider: LlmProvider,
        settingsProvider: ProviderSettings,
        apiKey: String,
        sharedTransport: SharedProviderTransport,
        executionContext: BackendLlmExecutionContext,
    ): LLMChatAPI
}

interface LlmClientFactory {
    suspend fun create(context: BackendLlmExecutionContext): LLMChatAPI
}

class BackendLlmClientFactory(
    private val credentialResolver: ProviderCredentialResolver,
    private val providerClientFactory: ProviderChatApiBuilder,
) : LlmClientFactory {
    private val transports = LlmProvider.entries.associateWith { provider ->
        SharedProviderTransport(id = provider.name.lowercase())
    }

    override suspend fun create(context: BackendLlmExecutionContext): LLMChatAPI =
        RoutingLlmChatApi(
            context = context,
            credentialResolver = credentialResolver,
            providerClientFactory = providerClientFactory,
            transports = transports,
        )
}

private class RoutingLlmChatApi(
    private val context: BackendLlmExecutionContext,
    private val credentialResolver: ProviderCredentialResolver,
    private val providerClientFactory: ProviderChatApiBuilder,
    private val transports: Map<LlmProvider, SharedProviderTransport>,
) : LLMChatAPI {
    private val mutex = Mutex()
    private val apis = LinkedHashMap<LlmProvider, LLMChatAPI>()

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat =
        apiFor(providerFor(body.model)).message(body)

    override suspend fun messageStream(body: LLMRequest.Chat) =
        apiFor(providerFor(body.model)).messageStream(body)

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings =
        apiFor(context.settingsProvider.embeddingsModel.provider).embeddings(body)

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile =
        apiFor(context.settingsProvider.gigaModel.provider).uploadFile(file)

    override suspend fun downloadFile(fileId: String): String? =
        apiFor(context.settingsProvider.gigaModel.provider).downloadFile(fileId)

    override suspend fun balance(): LLMResponse.Balance =
        apiFor(context.settingsProvider.gigaModel.provider).balance()

    private suspend fun apiFor(provider: LlmProvider): LLMChatAPI {
        check(provider != LlmProvider.LOCAL) { "Local models are unavailable in the backend." }
        apis[provider]?.let { return it }
        return mutex.withLock {
            apis[provider]?.let { return@withLock it }
            val credential = credentialResolver.resolve(context.userId, provider)
                ?: error("Missing configured credential for provider $provider.")
            providerClientFactory.build(
                provider = provider,
                settingsProvider = context.settingsProvider,
                apiKey = credential.apiKey,
                sharedTransport = transports.getValue(provider),
                executionContext = context,
            ).also { api -> apis[provider] = api }
        }
    }

    private fun providerFor(model: String): LlmProvider =
        findLLMModel(model)?.provider ?: context.settingsProvider.gigaModel.provider
}
