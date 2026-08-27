package ru.souz.backend.llm

import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.app.BackendProviderRetryPolicy
import ru.souz.backend.common.BackendLlmSupport
import ru.souz.db.SettingsProvider
import ru.souz.llms.EmbeddingsModelSelection
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
import ru.souz.llms.ModelResolution
import ru.souz.llms.anthropic.AnthropicChatAPI
import ru.souz.llms.codex.CodexChatAPI
import ru.souz.llms.codex.CodexOAuthService
import ru.souz.llms.resolveChatModel
import ru.souz.llms.resolveEmbeddingsModel
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.local.LocalChatAPI
import ru.souz.llms.openai.OpenAICompatibleChatAPI

/** Execution-scoped LLM routing, credentials, retries, and usage over process-owned transports. */
internal class BackendExecutionLlmChatApi(
    private val userId: String,
    private val settingsProvider: SettingsProvider,
    private val credentialResolver: ProviderCredentialResolver,
    private val retryPolicy: BackendProviderRetryPolicy,
    private val httpClients: ProviderHttpClients,
    private val localChatApi: LocalChatAPI,
    private val codexOAuthService: CodexOAuthService,
    initialUsage: LLMResponse.Usage = ZERO_USAGE,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
    private val providerApiOverride: ((LlmProvider) -> LLMChatAPI)? = null,
) : LLMChatAPI {
    private val providerStateMutex = Mutex()
    private val credentials = mutableMapOf<LlmProvider, String?>()
    private val providerApis = mutableMapOf<LlmProvider, LLMChatAPI>()

    private val usageMutex = Mutex()
    private var usage = initialUsage

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
        val model = when (val resolution = chatModel(body.model)) {
            is ModelResolution.Resolved -> resolution.value
            else -> return unsupportedChatModel(resolution)
        }
        val response = retryChat(retryPolicy, delayMillis) {
            apiFor(model.provider).message(body.copy(model = model.alias))
        }
        recordUsage(response)
        return response
    }

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> {
        val model = when (val resolution = chatModel(body.model)) {
            is ModelResolution.Resolved -> resolution.value
            else -> return flow { emit(unsupportedChatModel(resolution)) }
        }
        val api = apiFor(model.provider)
        return retryingStream(api, body.copy(model = model.alias))
    }

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings {
        val configuredModel = settingsProvider.embeddingsModel
        val model = when (
            val resolution = resolveEmbeddingsModel(
                rawModel = body.model,
                configuredModel = configuredModel,
                supportedProviders = BackendLlmSupport.embeddingProviders,
            )
        ) {
            is ModelResolution.Resolved -> when (val selection = resolution.value) {
                EmbeddingsModelSelection.Default -> configuredModel
                is EmbeddingsModelSelection.Explicit -> selection.model
            }
            else -> return unsupportedEmbeddingModel(resolution)
        }
        return apiFor(model.provider).embeddings(body.copy(model = model.alias))
    }

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile =
        apiFor(currentProvider()).uploadFile(file)

    override suspend fun downloadFile(fileId: String): String? =
        apiFor(currentProvider()).downloadFile(fileId)

    override suspend fun balance(): LLMResponse.Balance =
        apiFor(currentProvider()).balance()

    suspend fun cumulativeUsage(): LLMResponse.Usage = usageMutex.withLock { usage }

    internal suspend fun credentialFor(provider: LlmProvider): String {
        require(provider in BackendLlmSupport.userManagedKeyProviders) {
            "Provider $provider does not use execution-scoped API-key credentials."
        }
        return providerStateMutex.withLock { resolveCredentialLocked(provider) }
    }

    private fun currentProvider(): LlmProvider = settingsProvider.gigaModel.provider

    private fun chatModel(model: String): ModelResolution<LLMModel> =
        resolveChatModel(
            rawModel = model,
            supportedProviders = BackendLlmSupport.chatProviders,
            preferredModel = settingsProvider.gigaModel,
        )

    private suspend fun apiFor(provider: LlmProvider): LLMChatAPI {
        if (provider == LlmProvider.GIGA) error(BackendLlmSupport.GIGA_UNSUPPORTED_MESSAGE)
        require(provider in BackendLlmSupport.chatProviders) {
            "Provider $provider is not supported by the backend."
        }
        if (provider == LlmProvider.LOCAL && providerApiOverride == null) return localChatApi
        return providerStateMutex.withLock {
            providerApis[provider]?.let { return@withLock it }
            val api = providerApiOverride?.invoke(provider) ?: when (provider) {
                LlmProvider.OPENAI,
                LlmProvider.AI_TUNNEL,
                LlmProvider.QWEN,
                -> OpenAICompatibleChatAPI(
                    provider = provider,
                    settingsProvider = settingsProvider,
                    client = when (provider) {
                        LlmProvider.OPENAI -> httpClients.openAi
                        else -> httpClients.standard
                    },
                    apiKey = resolveCredentialLocked(provider),
                )
                LlmProvider.ANTHROPIC -> AnthropicChatAPI(
                    settingsProvider,
                    httpClients.standard,
                    apiKey = resolveCredentialLocked(provider),
                )
                LlmProvider.CODEX -> CodexChatAPI(
                    settingsProvider,
                    codexOAuthService,
                    httpClients.standard,
                )
                LlmProvider.LOCAL -> localChatApi
                LlmProvider.GIGA -> error(BackendLlmSupport.GIGA_UNSUPPORTED_MESSAGE)
            }
            providerApis[provider] = api
            api
        }
    }

    private suspend fun resolveCredentialLocked(provider: LlmProvider): String {
        if (credentials.containsKey(provider)) {
            return credentials.getValue(provider)
                ?: error("Missing configured credential for provider $provider.")
        }
        val credential = credentialResolver.resolve(userId, provider)
        credentials[provider] = credential?.apiKey
        return credentials.getValue(provider)
            ?: error("Missing configured credential for provider $provider.")
    }

    private fun retryingStream(api: LLMChatAPI, body: LLMRequest.Chat): Flow<LLMResponse.Chat> = flow {
        var attempt = 0
        while (true) {
            try {
                var emitted = false
                var previousUsage = ZERO_USAGE
                api.messageStream(body).collect { response ->
                    if (
                        !emitted &&
                        response is LLMResponse.Chat.Error &&
                        response.status == HTTP_TOO_MANY_REQUESTS &&
                        attempt < retryPolicy.max429Retries
                    ) {
                        throw RetryFirstStreaming429(response)
                    }
                    previousUsage = emitAndRecordStreamingUsage(response, previousUsage)
                    emitted = true
                }
                return@flow
            } catch (retry: RetryFirstStreaming429) {
                delayMillis(backoffForAttempt(retryPolicy, attempt, retry.error.message))
                attempt += 1
            }
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<LLMResponse.Chat>.emitAndRecordStreamingUsage(
        response: LLMResponse.Chat,
        previousUsage: LLMResponse.Usage,
    ): LLMResponse.Usage {
        if (response is LLMResponse.Chat.Ok) {
            val delta = response.usage.deltaFrom(previousUsage)
            usageMutex.withLock { usage = usage.plus(delta) }
            emit(response)
            return response.usage
        }
        emit(response)
        return previousUsage
    }

    private suspend fun recordUsage(response: LLMResponse.Chat) {
        if (response !is LLMResponse.Chat.Ok) return
        usageMutex.withLock { usage = usage.plus(response.usage) }
    }

    private fun unsupportedChatModel(resolution: ModelResolution<*>): LLMResponse.Chat.Error =
        LLMResponse.Chat.Error(-1, "Unsupported backend chat model: ${resolution.description()}.")

    private fun unsupportedEmbeddingModel(resolution: ModelResolution<*>): LLMResponse.Embeddings.Error =
        LLMResponse.Embeddings.Error(-1, "Unsupported backend embeddings model: ${resolution.description()}.")

    private companion object {
        val ZERO_USAGE = LLMResponse.Usage(0, 0, 0, 0)
    }
}

private class RetryFirstStreaming429(val error: LLMResponse.Chat.Error) : Exception("retry", null, false, false)

private fun ModelResolution<*>.description(): String = when (this) {
    is ModelResolution.Resolved -> value.toString()
    is ModelResolution.Unknown -> normalizedInput
    is ModelResolution.Ambiguous -> "$normalizedInput is ambiguous"
    is ModelResolution.UnsupportedProvider -> "provider $provider is unsupported"
}

private fun LLMResponse.Usage.plus(other: LLMResponse.Usage): LLMResponse.Usage =
    LLMResponse.Usage(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        totalTokens = totalTokens + other.totalTokens,
        precachedTokens = precachedTokens + other.precachedTokens,
    )

private fun LLMResponse.Usage.deltaFrom(previous: LLMResponse.Usage): LLMResponse.Usage =
    LLMResponse.Usage(
        promptTokens = (promptTokens - previous.promptTokens).coerceAtLeast(0),
        completionTokens = (completionTokens - previous.completionTokens).coerceAtLeast(0),
        totalTokens = (totalTokens - previous.totalTokens).coerceAtLeast(0),
        precachedTokens = (precachedTokens - previous.precachedTokens).coerceAtLeast(0),
    )
