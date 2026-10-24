package ru.souz.backend.llm

import java.io.File
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.souz.backend.app.BackendProviderRetryPolicy
import ru.souz.backend.common.BackendLlmSupport
import ru.souz.backend.hooks.HookLlmBudget
import ru.souz.backend.hooks.HookLlmBudget.Companion.withProviderCall
import ru.souz.backend.metrics.BackendMetrics
import ru.souz.backend.metrics.metricOutcome
import ru.souz.backend.metrics.responseMetricOutcome
import ru.souz.backend.config.NamedLlmModel
import ru.souz.db.SettingsProvider
import ru.souz.llms.EmbeddingsModelSelection
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
import ru.souz.llms.ModelResolution
import ru.souz.llms.anthropic.AnthropicChatAPI
import ru.souz.llms.codex.CodexChatAPI
import ru.souz.llms.codex.CodexOAuthService
import ru.souz.llms.findLLMModel
import ru.souz.llms.resolveChatModel
import ru.souz.llms.resolveEmbeddingsModel
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.openai.OpenAICompatibleChatAPI

/** Execution-scoped LLM routing, credentials, retries, and usage over process-owned transports. */
internal class BackendExecutionLlmChatApi(
    private val userId: String,
    private val settingsProvider: SettingsProvider,
    private val credentialResolver: ProviderCredentialResolver,
    private val retryPolicy: BackendProviderRetryPolicy,
    private val httpClients: ProviderHttpClients,
    private val codexOAuthService: CodexOAuthService,
    initialUsage: LLMResponse.Usage = ZERO_USAGE,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
    private val providerApiOverride: ((LlmProvider) -> LLMChatAPI?)? = null,
    private val hookBudget: HookLlmBudget? = null,
    private val metrics: BackendMetrics? = null,
    private val namedModels: Map<String, NamedLlmModel> = emptyMap(),
    private val fallbackModels: List<String> = emptyList(),
    private val failureMemory: ModelFailureMemory = ModelFailureMemory(),
) : LLMChatAPI {
    private val providerStateMutex = Mutex()
    private val credentials = mutableMapOf<LlmProvider, ResolvedProviderCredential?>()
    private val providerApis = mutableMapOf<Pair<LlmProvider, NamedLlmModel?>, LLMChatAPI>()

    private val usageMutex = Mutex()
    private var usage = initialUsage

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
        val summarizationModel = settingsProvider.openaiSummarizationModel
        if (body.isSummarization && summarizationModel != null) {
            val api = providerApiOverride?.invoke(LlmProvider.OPENAI) ?: OpenAICompatibleChatAPI(
                provider = LlmProvider.OPENAI,
                settingsProvider = settingsProvider,
                client = httpClients.openAi,
                apiKey = settingsProvider.openaiSummarizationApiKey ?: credentialFor(LlmProvider.OPENAI),
                baseUrl = settingsProvider.openaiSummarizationBaseUrl,
                requestParameters = settingsProvider.openaiSummarizationParameters,
            )
            val request = body.copy(model = summarizationModel, provider = LlmProvider.OPENAI)
            return retryChat { callProvider(LlmProvider.OPENAI, request, api) }
        }
        val primary = when (val route = chatRoute(body)) {
            is ChatRoute.Ready -> route
            is ChatRoute.Rejected -> return route.error
        }
        val fallbacks = fallbackModels.map { chatRoute(body.copy(model = it, provider = findLLMModel(it)?.provider ?: LlmProvider.OPENAI)) }
            .filterIsInstance<ChatRoute.Ready>()
        val routes = (listOf(primary) + fallbacks).distinctBy { it.target }
        if (routes.size == 1) return chat(primary)
        var lastFailure: LLMResponse.Chat.Error? = null
        for (route in routes) {
            val failureKey = route.target to userId.takeIf { usesUserKey(route) }
            if (!failureMemory.shouldTry(failureKey)) continue
            val response = chat(route)
            val failed = response is LLMResponse.Chat.Error && response.isProviderFailure()
            failureMemory.record(failureKey, failed)
            if (!failed) return response
            log.warn("Model {} failed with status {}; trying the next fallback model", route.request.model, response.status)
            lastFailure = response
        }
        // Every model is cooling down: the requested one is still the best attempt.
        return lastFailure ?: chat(primary)
    }

    private suspend fun chat(route: ChatRoute.Ready): LLMResponse.Chat =
        retryChat { callProvider(route.provider, route.request, apiFor(route.provider, route.namedModel)) }

    // A user's own key fails for that user only; every other credential is shared by the whole server.
    private suspend fun usesUserKey(route: ChatRoute.Ready): Boolean =
        route.namedModel == null && route.provider in BackendLlmSupport.userManagedKeyProviders &&
            providerStateMutex.withLock { resolveCredentialLocked(route.provider) }.source == CredentialSource.USER_MANAGED

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> {
        val (provider, request, namedModel) = when (val route = chatRoute(body)) {
            is ChatRoute.Ready -> route
            is ChatRoute.Rejected -> return flow { emit(route.error) }
        }
        val api = apiFor(provider, namedModel)
        return retryingStream(provider, api, request)
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
        val api = apiFor(model.provider)
        val request = body.copy(model = model.alias)
        return withProviderCall(hookBudget, model.provider) {
            measureAttempt(model.provider, request.model) { outcome ->
                api.embeddings(request).also {
                    outcome(responseMetricOutcome((it as? LLMResponse.Embeddings.Error)?.status))
                }
            }
        }
    }

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile {
        val provider = currentProvider()
        val api = apiFor(provider, currentNamedModel())
        return withProviderCall(hookBudget, provider) { api.uploadFile(file) }
    }

    override suspend fun downloadFile(fileId: String): String? =
        apiFor(currentProvider(), currentNamedModel()).downloadFile(fileId)

    override suspend fun balance(): LLMResponse.Balance =
        apiFor(currentProvider(), currentNamedModel()).balance()

    suspend fun cumulativeUsage(): LLMResponse.Usage = usageMutex.withLock { usage }

    internal suspend fun credentialFor(provider: LlmProvider): String {
        require(provider in BackendLlmSupport.userManagedKeyProviders) {
            "Provider $provider does not use execution-scoped API-key credentials."
        }
        return providerStateMutex.withLock { resolveCredentialLocked(provider) }.apiKey
    }

    private fun currentProvider(): LlmProvider = settingsProvider.gigaModel.provider

    private fun currentNamedModel(): NamedLlmModel? = namedModels[
        settingsProvider.executionModelId(settingsProvider.gigaModel)
    ]

    private fun chatRoute(body: LLMRequest.Chat): ChatRoute {
        if (body.provider == null || body.provider == LlmProvider.OPENAI) {
            namedModels[body.model]?.let { model ->
                return ChatRoute.Ready(LlmProvider.OPENAI, body.copy(model = model.modelId, provider = LlmProvider.OPENAI), model)
            }
        }
        body.provider?.let { provider ->
            return if (provider in BackendLlmSupport.chatProviders) ChatRoute.Ready(provider, body)
            else rejectChatRoute(ModelResolution.UnsupportedProvider(body, provider))
        }
        return when (val resolution = resolveChatModel(
            rawModel = body.model,
            supportedProviders = BackendLlmSupport.chatProviders,
            preferredModel = settingsProvider.gigaModel,
        )) {
            is ModelResolution.Resolved -> chatRoute(
                body.copy(model = settingsProvider.executionModelId(resolution.value), provider = resolution.value.provider),
            )
            else -> rejectChatRoute(resolution)
        }
    }

    private suspend fun apiFor(provider: LlmProvider, namedModel: NamedLlmModel? = null): LLMChatAPI {
        if (provider == LlmProvider.GIGA) error(BackendLlmSupport.GIGA_UNSUPPORTED_MESSAGE)
        require(provider in BackendLlmSupport.chatProviders) {
            "Provider $provider is not supported by the backend."
        }
        return providerStateMutex.withLock {
            val cacheKey = provider to namedModel
            providerApis[cacheKey]?.let { return@withLock it }
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
                    apiKey = namedModel?.apiKey ?: resolveCredentialLocked(provider).apiKey,
                    baseUrl = namedModel?.baseUrl,
                )
                LlmProvider.ANTHROPIC -> AnthropicChatAPI(
                    settingsProvider,
                    httpClients.standard,
                    apiKey = resolveCredentialLocked(provider).apiKey,
                )
                LlmProvider.CODEX -> CodexChatAPI(
                    settingsProvider,
                    codexOAuthService,
                    httpClients.standard,
                )
                LlmProvider.GIGA -> error(BackendLlmSupport.GIGA_UNSUPPORTED_MESSAGE)
            }
            providerApis[cacheKey] = api
            api
        }
    }

    private suspend fun resolveCredentialLocked(provider: LlmProvider): ResolvedProviderCredential {
        if (!credentials.containsKey(provider)) credentials[provider] = credentialResolver.resolve(userId, provider)
        return credentials.getValue(provider) ?: error("Missing configured credential for provider $provider.")
    }

    private suspend fun retryChat(request: suspend () -> LLMResponse.Chat): LLMResponse.Chat {
        var attempt = 0
        while (true) {
            val response = request()
            if (response !is LLMResponse.Chat.Error || response.status != TOO_MANY_REQUESTS) {
                return response
            }
            if (attempt == retryPolicy.max429Retries) return response
            delayMillis(backoffForAttempt(attempt, response.message))
            attempt += 1
        }
    }

    private suspend fun callProvider(provider: LlmProvider, body: LLMRequest.Chat, api: LLMChatAPI): LLMResponse.Chat {
        val request = hookBudget?.limitRequest(body) ?: body
        return withProviderCall(hookBudget, provider) {
            measureAttempt(provider, request.model) { outcome ->
                api.message(request).also { response ->
                    outcome(responseMetricOutcome((response as? LLMResponse.Chat.Error)?.status))
                    if (response is LLMResponse.Chat.Ok) recordUsage(provider, request.model, response.usage)
                }
            }
        }
    }

    private fun retryingStream(provider: LlmProvider, api: LLMChatAPI, body: LLMRequest.Chat): Flow<LLMResponse.Chat> = flow {
        val request = hookBudget?.limitRequest(body) ?: body
        var attempt = 0
        while (true) {
            try {
                var emitted = false
                var previousUsage = ZERO_USAGE
                withProviderCall(hookBudget, provider) {
                    measureAttempt(provider, request.model) { outcome ->
                        api.messageStream(request).collect { response ->
                            if (response is LLMResponse.Chat.Error) outcome(responseMetricOutcome(response.status))
                            if (!emitted && response is LLMResponse.Chat.Error && response.status == TOO_MANY_REQUESTS &&
                                attempt < retryPolicy.max429Retries) throw RetryFirstStreaming429(response)
                            if (response is LLMResponse.Chat.Ok) {
                                val delta = response.usage.deltaFrom(previousUsage)
                                recordUsage(provider, request.model, delta)
                                previousUsage = previousUsage.plus(delta)
                            }
                            emit(response)
                            emitted = true
                        }
                    }
                }
                return@flow
            } catch (retry: RetryFirstStreaming429) {
                delayMillis(backoffForAttempt(attempt, retry.error.message))
                attempt += 1
            }
        }
    }

    private suspend fun recordUsage(provider: LlmProvider, model: String, delta: LLMResponse.Usage) {
        usageMutex.withLock { usage = usage.plus(delta) }
        metrics?.llmUsage(provider, model, delta)
        hookBudget?.recordUsage(delta.totalTokens)
    }

    // Accounting must run before cancellation propagates from a provider or collector.
    @Suppress("SuspendFunSwallowedCancellation")
    private suspend fun <T> measureAttempt(
        provider: LlmProvider,
        model: String,
        call: suspend ((String) -> Unit) -> T,
    ): T {
        val started = System.nanoTime()
        var outcome = "success"
        try {
            return call { outcome = it }
        } catch (failure: Throwable) {
            outcome = failure.metricOutcome()
            throw failure
        } finally {
            metrics?.llmAttempt(provider, model, outcome, System.nanoTime() - started)
        }
    }

    private fun backoffForAttempt(attempt: Int, message: String): Long {
        val retryAfter = RETRY_AFTER.find(message)?.groupValues?.getOrNull(1)?.toLongOrNull()
        if (retryAfter != null) return min(retryAfter, retryPolicy.backoffMaxMs)
        return min(retryPolicy.backoffBaseMs * (attempt + 1), retryPolicy.backoffMaxMs)
    }

    private fun rejectChatRoute(resolution: ModelResolution<*>): ChatRoute.Rejected =
        ChatRoute.Rejected(LLMResponse.Chat.Error(-1, "Unsupported backend chat model: ${resolution.description()}."))

    private fun unsupportedEmbeddingModel(resolution: ModelResolution<*>): LLMResponse.Embeddings.Error =
        LLMResponse.Embeddings.Error(-1, "Unsupported backend embeddings model: ${resolution.description()}.")

    private companion object {
        const val TOO_MANY_REQUESTS = 429
        val RETRY_AFTER = Regex("""retry-after=(\d+)""", RegexOption.IGNORE_CASE)
        val ZERO_USAGE = LLMResponse.Usage(0, 0, 0, 0)
        val log: Logger = LoggerFactory.getLogger(BackendExecutionLlmChatApi::class.java)
    }
}

private sealed interface ChatRoute {
    data class Ready(val provider: LlmProvider, val request: LLMRequest.Chat, val namedModel: NamedLlmModel? = null) : ChatRoute {
        /** The upstream model and connection this route reaches; named connections are shared, process-wide instances. */
        val target = Triple(provider, namedModel, request.model)
    }
    data class Rejected(val error: LLMResponse.Chat.Error) : ChatRoute
}

/** The provider failed rather than the request: no HTTP status, 5xx, rejected credentials, timeout or rate limit. */
private fun LLMResponse.Chat.Error.isProviderFailure(): Boolean =
    status !in 400..499 ||
        status in 401..403 ||
        status == 408 ||
        status == 429

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
