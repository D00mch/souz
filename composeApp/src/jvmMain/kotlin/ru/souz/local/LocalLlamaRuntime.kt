package ru.souz.local

import com.fasterxml.jackson.annotation.JsonProperty
import com.sun.jna.Pointer
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.giga.GigaMessageRole
import ru.souz.giga.GigaRequest
import ru.souz.giga.GigaResponse
import ru.souz.giga.gigaJsonMapper

class LocalLlamaRuntime(
    private val availability: LocalProviderAvailability,
    private val modelStore: LocalModelStore,
    private val promptRenderer: LocalPromptRenderer,
    private val strictJsonParser: LocalStrictJsonParser,
    private val bridge: LocalNativeBridge,
) : AutoCloseable {
    private val l = LoggerFactory.getLogger(LocalLlamaRuntime::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadMutex = Mutex()
    private val runtimeOperationMutex = Mutex()

    private val runtimeHandle = AtomicReference<Pointer?>(null)
    private val loadedModel = AtomicReference<LoadedModel?>(null)
    private val warmedModelId = AtomicReference<String?>(null)

    suspend fun chat(body: GigaRequest.Chat): GigaResponse.Chat {
        val nativeResult = runCatching { generate(body, stream = false) }
            .getOrElse { error ->
                NativeGenerationResult.error("Local inference failed: ${error.message ?: error::class.simpleName.orEmpty()}")
            }
        nativeResult.error?.let { message ->
            return GigaResponse.Chat.Error(-1, message)
        }
        return strictJsonParser.parse(
            rawText = nativeResult.text,
            requestModel = body.model,
            usage = nativeResult.toUsage(),
        )
    }

    fun chatStream(body: GigaRequest.Chat): Flow<GigaResponse.Chat> = flow {
        val response = withContext(Dispatchers.IO) {
            runCatching {
                val result = generate(body, stream = true)
                result.error?.let { message ->
                    return@runCatching GigaResponse.Chat.Error(-1, message)
                }
                strictJsonParser.parse(
                    rawText = result.text,
                    requestModel = body.model,
                    usage = result.toUsage(),
                )
            }.getOrElse { error ->
                GigaResponse.Chat.Error(
                    -1,
                    "Local inference failed: ${error.message ?: error::class.simpleName.orEmpty()}",
                )
            }
        }

        emit(response)
    }

    fun cancelActiveRequest() {
        runtimeHandle.get()?.let { runtime ->
            runCatching { bridge.cancel(runtime) }
                .onFailure { error -> l.debug("Local cancel failed: {}", error.message) }
        }
    }

    suspend fun preload(modelAlias: String) {
        val profile = LocalModelProfiles.forAlias(modelAlias) ?: return
        preload(profile)
    }

    suspend fun preload(profile: LocalModelProfile) {
        val availabilityStatus = availability.status()
        if (!availabilityStatus.available || profile.gigaModel !in availabilityStatus.availableModels) {
            return
        }
        if (!modelStore.isPresent(profile)) {
            return
        }

        val runtime = ensureRuntime()
        val modelHandle = ensureModel(profile)
        if (warmedModelId.get() == profile.id) {
            return
        }

        runCatching {
            executeGeneration(
                runtime = runtime,
                modelHandle = modelHandle,
                generationRequest = buildWarmupRequest(profile),
                stream = false,
            )
        }.onSuccess {
            warmedModelId.set(profile.id)
        }.onFailure { error ->
            if (error !is CancellationException) {
                l.warn("Local model preload warmup failed for {}: {}", profile.id, error.message)
            }
        }
    }

    private suspend fun generate(body: GigaRequest.Chat, stream: Boolean): NativeGenerationResult {
        val availabilityStatus = availability.status()
        if (!availabilityStatus.available) {
            return NativeGenerationResult.error(availabilityStatus.message)
        }

        val profile = LocalModelProfiles.forAlias(body.model)
            ?: availabilityStatus.selectedProfile
            ?: return NativeGenerationResult.error("No local model profile is available for ${body.model}.")

        val modelHandle = ensureModel(profile)
        val runtime = ensureRuntime()
        val prompt = promptRenderer.render(body, profile)
        val completionBudget = resolveCompletionBudget(body)
        val contextSize = resolveContextSize(body, profile, prompt, completionBudget)
        val useStructuredOutput = !body.prefersPlainTextLocalOutput()
        val generationRequest = LocalGenerationRequest(
            prompt = prompt,
            contextSize = contextSize,
            maxTokens = completionBudget,
            temperature = body.temperature ?: DEFAULT_TEMPERATURE,
            topP = DEFAULT_TOP_P,
            topK = DEFAULT_TOP_K,
            seed = DEFAULT_SEED,
            stop = emptyList(),
            grammar = if (profile.useNativeGrammar && useStructuredOutput) LocalStrictJsonContract.grammar else "",
        )

        val requestVariants = buildRequestVariants(generationRequest, profile)
        var lastError: Throwable? = null
        for ((index, candidate) in requestVariants.withIndex()) {
            val result = runCatching {
                executeGeneration(runtime, modelHandle, candidate, stream)
            }.recoverCatching { error ->
                if (!shouldRetryWithoutGrammar(error) || candidate.grammar.isBlank()) {
                    throw error
                }
                l.warn("Retrying local generation without native grammar guidance: {}", error.message)
                executeGeneration(runtime, modelHandle, candidate.copy(grammar = ""), stream)
            }

            result.getOrNull()?.let { nativeResult ->
                warmedModelId.set(profile.id)
                return nativeResult
            }

            val error = result.exceptionOrNull() ?: continue
            lastError = error
            if (!shouldRetryWithExpandedContext(error, candidate, profile) || index == requestVariants.lastIndex) {
                break
            }
            l.warn(
                "Retrying local generation with maximum context window ({} -> {}): {}",
                candidate.contextSize,
                profile.maxContextSize,
                error.message,
            )
        }

        throw (lastError ?: IllegalStateException("Local generation failed without a specific error."))
    }

    private suspend fun executeGeneration(
        runtime: Pointer,
        modelHandle: Pointer,
        generationRequest: LocalGenerationRequest,
        stream: Boolean,
    ): NativeGenerationResult {
        val requestJson = gigaJsonMapper.writeValueAsString(generationRequest)
        return suspendCancellableCoroutine { cont ->
            val job = scope.launch {
                runCatching {
                    runtimeOperationMutex.withLock {
                        if (stream) {
                            bridge.generateStream(runtime, modelHandle, requestJson) { event ->
                                l.debug("Local native stream event: {}", event)
                            }
                        } else {
                            bridge.generate(runtime, modelHandle, requestJson)
                        }
                    }
                }.onSuccess { json ->
                    val parsed = gigaJsonMapper.readValue(json, NativeGenerationResult::class.java)
                    if (cont.isActive) {
                        cont.resume(parsed)
                    }
                }.onFailure { error ->
                    if (cont.isActive) {
                        cont.resumeWithException(error)
                    }
                }
            }

            cont.invokeOnCancellation {
                cancelActiveRequest()
                job.cancel()
            }
        }
    }

    private fun shouldRetryWithoutGrammar(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        return message.contains("Unexpected empty grammar stack after accepting piece") ||
            message.contains("Failed to initialize strict JSON grammar")
    }

    private fun shouldRetryWithExpandedContext(
        error: Throwable,
        request: LocalGenerationRequest,
        profile: LocalModelProfile,
    ): Boolean {
        if (request.contextSize >= profile.maxContextSize) {
            return false
        }
        val message = error.message.orEmpty()
        return message.contains("Prompt does not fit into the configured local context window.") ||
            message.contains("Prompt does not leave room for any completion tokens.") ||
            message.contains("Prompt and reserved completion do not fit into the configured local context window.")
    }

    private fun buildRequestVariants(
        request: LocalGenerationRequest,
        profile: LocalModelProfile,
    ): List<LocalGenerationRequest> {
        val variants = mutableListOf(request)
        if (request.contextSize < profile.maxContextSize) {
            variants += request.copy(contextSize = profile.maxContextSize)
        }
        return variants
    }

    internal fun buildWarmupRequest(profile: LocalModelProfile): LocalGenerationRequest {
        val prompt = promptRenderer.render(
            body = GigaRequest.Chat(
                model = profile.gigaModel.alias,
                messages = listOf(
                    GigaRequest.Message(
                        role = GigaMessageRole.user,
                        content = WARMUP_PROMPT,
                    )
                ),
                maxTokens = MIN_CONTEXT_SIZE,
            ),
            profile = profile,
        )
        return LocalGenerationRequest(
            prompt = prompt,
            contextSize = MIN_CONTEXT_SIZE.coerceAtMost(profile.maxContextSize),
            maxTokens = WARMUP_COMPLETION_TOKENS,
            temperature = 0f,
            topP = 1f,
            topK = 1,
            seed = DEFAULT_SEED,
            stop = emptyList(),
            grammar = "",
        )
    }

    internal fun resolveCompletionBudget(body: GigaRequest.Chat): Int =
        body.maxTokens
            .takeIf { it > 0 }
            ?.let { minOf(MAX_COMPLETION_TOKENS, it) }
            ?: MAX_COMPLETION_TOKENS

    internal fun resolveContextSize(
        body: GigaRequest.Chat,
        profile: LocalModelProfile,
        prompt: String,
        completionBudget: Int = resolveCompletionBudget(body),
    ): Int {
        val promptEstimate = prompt.estimateTokenCount()
        val desired = promptEstimate + completionBudget + CONTEXT_SAFETY_MARGIN_TOKENS
        return nextContextBucket(desired)
            .coerceAtLeast(MIN_CONTEXT_SIZE)
            .coerceAtMost(profile.maxContextSize)
    }

    private fun nextContextBucket(tokens: Int): Int {
        val normalized = tokens.coerceAtLeast(MIN_CONTEXT_SIZE)
        return CONTEXT_BUCKETS.firstOrNull { normalized <= it } ?: CONTEXT_BUCKETS.last()
    }

    private suspend fun ensureRuntime(): Pointer = loadMutex.withLock {
        runtimeHandle.get() ?: runtimeOperationMutex.withLock {
            runtimeHandle.get() ?: bridge.createRuntime().also(runtimeHandle::set)
        }
    }

    private suspend fun ensureModel(profile: LocalModelProfile): Pointer = loadMutex.withLock {
        val runtime = runtimeHandle.get() ?: runtimeOperationMutex.withLock {
            runtimeHandle.get() ?: bridge.createRuntime().also(runtimeHandle::set)
        }
        loadedModel.get()?.takeIf { it.profile.id == profile.id }?.pointer?.let { return@withLock it }

        runtimeOperationMutex.withLock {
            loadedModel.get()?.let { current ->
                bridge.unloadModel(runtime, current.pointer)
                loadedModel.set(null)
                warmedModelId.set(null)
            }

            val modelPath = modelStore.requireAvailable(profile)
            val requestJson = gigaJsonMapper.writeValueAsString(
                LocalModelLoadRequest(
                    modelPath = modelPath.toAbsolutePath().toString(),
                    gpuLayers = profile.defaultGpuLayers,
                    useMmap = true,
                    useMlock = false,
                )
            )
            val pointer = bridge.loadModel(runtime, requestJson)
            loadedModel.set(LoadedModel(profile = profile, pointer = pointer))
            return@withLock pointer
        }
    }

    suspend fun shutdown() {
        cancelActiveRequest()
        loadMutex.withLock {
            runtimeOperationMutex.withLock {
                val runtime = runtimeHandle.get()
                val currentModel = loadedModel.get()

                if (runtime != null && currentModel != null) {
                    runCatching { bridge.unloadModel(runtime, currentModel.pointer) }
                        .onFailure { error ->
                            l.warn("Failed to unload local model during shutdown: {}", error.message)
                        }
                }

                loadedModel.set(null)
                warmedModelId.set(null)

                if (runtime != null) {
                    runCatching { bridge.destroyRuntime(runtime) }
                        .onFailure { error ->
                            l.warn("Failed to destroy local runtime during shutdown: {}", error.message)
                        }
                }

                runtimeHandle.set(null)
            }
        }
        scope.cancel()
    }

    override fun close() {
        runBlocking { shutdown() }
    }

    private data class LoadedModel(
        val profile: LocalModelProfile,
        val pointer: Pointer,
    )

    data class LocalModelLoadRequest(
        @field:JsonProperty("model_path") val modelPath: String,
        @field:JsonProperty("gpu_layers") val gpuLayers: Int,
        @field:JsonProperty("use_mmap") val useMmap: Boolean,
        @field:JsonProperty("use_mlock") val useMlock: Boolean,
    )

    data class LocalGenerationRequest(
        val prompt: String,
        @field:JsonProperty("context_size") val contextSize: Int,
        @field:JsonProperty("max_tokens") val maxTokens: Int,
        val temperature: Float,
        @field:JsonProperty("top_p") val topP: Float,
        @field:JsonProperty("top_k") val topK: Int,
        val seed: Int,
        val stop: List<String>,
        val grammar: String,
    )

    data class NativeGenerationResult(
        val text: String,
        @field:JsonProperty("finish_reason") val finishReason: String = "stop",
        @field:JsonProperty("prompt_tokens") val promptTokens: Int = 0,
        @field:JsonProperty("completion_tokens") val completionTokens: Int = 0,
        @field:JsonProperty("total_tokens") val totalTokens: Int = promptTokens + completionTokens,
        @field:JsonProperty("precached_prompt_tokens") val precachedTokens: Int = 0,
        val error: String? = null,
    ) {
        fun toUsage(): GigaResponse.Usage = GigaResponse.Usage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens,
            precachedTokens = precachedTokens,
        )

        companion object {
            fun error(message: String): NativeGenerationResult = NativeGenerationResult(
                text = "",
                finishReason = "error",
                error = message,
            )
        }
    }

    private companion object {
        const val DEFAULT_TOP_K = 40
        const val DEFAULT_TOP_P = 0.9f
        const val DEFAULT_TEMPERATURE = 0.2f
        const val DEFAULT_SEED = 42
        const val MAX_COMPLETION_TOKENS = 1024
        const val CONTEXT_SAFETY_MARGIN_TOKENS = 512
        const val MIN_CONTEXT_SIZE = 2048
        const val WARMUP_COMPLETION_TOKENS = 1
        const val WARMUP_PROMPT = "Warm up."
        val CONTEXT_BUCKETS = intArrayOf(2048, 4096, 6144, 8192, 12288, 16384)
    }
}

private fun String.estimateTokenCount(): Int = ceil(length / 4.0).toInt()
