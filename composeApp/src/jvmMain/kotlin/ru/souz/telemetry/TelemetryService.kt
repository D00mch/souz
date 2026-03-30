package ru.souz.telemetry

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.jackson.jackson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import ru.souz.db.ConfigStore
import ru.souz.db.SettingsProvider
import ru.souz.db.SettingsProviderImpl.Companion.REGION_EN
import ru.souz.db.SettingsProviderImpl.Companion.REGION_RU
import ru.souz.llms.GigaResponse
import ru.souz.llms.plus
import ru.souz.tool.ToolCategory
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

class TelemetryService(
    private val outboxRepository: TelemetryOutboxRepository,
    private val cryptoService: TelemetryCryptoService,
    private val settingsProvider: SettingsProvider,
    private val runtimeConfig: TelemetryRuntimeConfig = TelemetryRuntimeConfig.production(),
) {
    private val l = LoggerFactory.getLogger(TelemetryService::class.java)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val senderStarted = AtomicBoolean(false)
    private val senderJob = AtomicReference<Job?>(null)
    private val registrationMutex = Mutex()
    private val lastSuccessfulFlushAtMs = AtomicLong(0L)
    private val lastErrorMessage = AtomicReference<String?>(null)
    private val appStartedAtMs = System.currentTimeMillis()
    private val appSessionId = UUID.randomUUID().toString()
    private val installationId = AtomicReference<String?>(loadInstallationId())
    private val identity = resolveInstallationIdentity()
    private val httpObjectMapper = jacksonObjectMapper().apply {
        setSerializationInclusion(JsonInclude.Include.NON_NULL)
        disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    }
    private val httpClient = HttpClient(CIO) {
        expectSuccess = false
        install(ContentNegotiation) {
            jackson {
                setSerializationInclusion(JsonInclude.Include.NON_NULL)
                disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            }
        }
    }

    private val currentRequestContext = ThreadLocal<TelemetryRequestContext?>()
    private val activeRequests = ConcurrentHashMap<String, ActiveTelemetryRequest>()
    private val conversations = ConcurrentHashMap<String, ConversationMetrics>()

    fun start() {
        if (!senderStarted.compareAndSet(false, true)) return
        enqueueAppOpened()
        senderJob.set(
            serviceScope.launch {
                while (isActive) {
                    runCatching { drainReadyBatches(maxBatches = runtimeConfig.autoFlushMaxBatches) }
                        .onFailure { error -> l.warn("Telemetry auto-flush failed: {}", error.message) }
                    delay(runtimeConfig.autoFlushIntervalMs)
                }
            }
        )
    }

    fun close() {
        if (senderStarted.getAndSet(false)) {
            enqueueAppClosed()
        }
        senderJob.getAndSet(null)?.cancel()
        serviceScope.cancel()
        runCatching { httpClient.close() }
            .onFailure { error -> l.warn("Failed to close telemetry HTTP client: {}", error.message) }
    }

    fun startConversation(reason: TelemetryConversationStartReason): String {
        val conversationId = UUID.randomUUID().toString()
        conversations[conversationId] = ConversationMetrics(
            startedAtMs = System.currentTimeMillis(),
            startReason = reason,
        )
        enqueueEvent(
            eventType = TelemetryEventType.CONVERSATION_STARTED,
            conversationId = conversationId,
            payload = mapOf("reason" to reason.wireName),
        )
        return conversationId
    }

    fun finishConversation(conversationId: String?, reason: TelemetryConversationEndReason) {
        if (conversationId.isNullOrBlank()) return
        val snapshot = conversations.remove(conversationId) ?: return
        enqueueEvent(
            eventType = TelemetryEventType.CONVERSATION_FINISHED,
            conversationId = conversationId,
            payload = mapOf(
                "reason" to reason.wireName,
                "startReason" to snapshot.startReason.wireName,
                "durationMs" to (System.currentTimeMillis() - snapshot.startedAtMs),
                "requestCount" to snapshot.requestCount,
                "toolCallCount" to snapshot.toolCallCount,
                "tokenUsage" to usagePayload(snapshot.tokenUsage),
            ),
        )
    }

    fun requestContextElement(context: TelemetryRequestContext): CoroutineContext =
        currentRequestContext.asContextElement(context)

    fun beginRequest(
        conversationId: String,
        source: TelemetryRequestSource,
        model: String,
        provider: String,
        inputLengthChars: Int,
        attachedFilesCount: Int,
    ): TelemetryRequestContext {
        val context = TelemetryRequestContext(
            requestId = UUID.randomUUID().toString(),
            conversationId = conversationId,
            source = source,
            model = model,
            provider = provider,
            inputLengthChars = inputLengthChars,
            attachedFilesCount = attachedFilesCount,
            startedAtMs = System.currentTimeMillis(),
        )
        activeRequests[context.requestId] = ActiveTelemetryRequest(context = context)
        return context
    }

    fun finishRequest(
        context: TelemetryRequestContext,
        status: TelemetryRequestStatus,
        responseLengthChars: Int?,
        errorMessage: String?,
        requestTokenUsage: GigaResponse.Usage,
        sessionTokenUsage: GigaResponse.Usage,
    ) {
        val durationMs = System.currentTimeMillis() - context.startedAtMs
        val toolCallCount = activeRequests.remove(context.requestId)?.toolCallCount ?: 0
        conversations.computeIfPresent(context.conversationId) { _, metrics ->
            metrics.copy(
                requestCount = metrics.requestCount + 1,
                toolCallCount = metrics.toolCallCount + toolCallCount,
                tokenUsage = metrics.tokenUsage + requestTokenUsage,
            )
        }

        enqueueEvent(
            eventType = TelemetryEventType.REQUEST_FINISHED,
            conversationId = context.conversationId,
            requestId = context.requestId,
            payload = mapOf(
                "status" to status.wireName,
                "source" to context.source.wireName,
                "model" to context.model,
                "provider" to context.provider,
                "durationMs" to durationMs,
                "inputLengthChars" to context.inputLengthChars,
                "responseLengthChars" to responseLengthChars,
                "attachedFilesCount" to context.attachedFilesCount,
                "toolCallCount" to toolCallCount,
                "requestTokenUsage" to usagePayload(requestTokenUsage),
                "sessionTokenUsage" to usagePayload(sessionTokenUsage),
                "errorMessage" to sanitizeErrorMessage(errorMessage),
            ),
        )
    }

    fun recordToolExecution(
        functionName: String,
        functionArguments: Map<String, Any>,
        toolCategory: ToolCategory?,
        durationMs: Long,
        success: Boolean,
        errorMessage: String?,
    ) {
        val currentContext = currentRequestContext.get() ?: return
        val requestContext = activeRequests.computeIfPresent(currentContext.requestId) { _, current ->
            current.copy(toolCallCount = current.toolCallCount + 1)
        }?.context ?: return
        enqueueEvent(
            eventType = TelemetryEventType.TOOL_EXECUTED,
            conversationId = requestContext.conversationId,
            requestId = requestContext.requestId,
            payload = mapOf(
                "toolName" to functionName,
                "toolCategory" to toolCategory?.name,
                "durationMs" to durationMs,
                "success" to success,
                "errorMessage" to sanitizeErrorMessage(errorMessage),
                "argumentKeys" to functionArguments.keys.sorted(),
                "argumentCount" to functionArguments.size,
            ),
        )
    }

    suspend fun flushNow(): TelemetryFlushResult =
        drainReadyBatches(maxBatches = runtimeConfig.manualFlushMaxBatches)

    fun diagnostics(): TelemetryDiagnostics = TelemetryDiagnostics(
        captureEnabled = true,
        sendConfigured = currentSendConfig() != null,
        queuedEvents = outboxRepository.pendingCount(),
        lastSuccessfulFlushAtMs = lastSuccessfulFlushAtMs.get().takeIf { it > 0L },
        lastErrorMessage = lastErrorMessage.get(),
        userId = identity.userId,
        deviceId = identity.deviceId,
        installationId = installationId.get(),
        appSessionId = appSessionId,
    )

    private suspend fun drainReadyBatches(
        maxBatches: Int,
        config: TelemetrySendConfig? = currentSendConfig(),
    ): TelemetryFlushResult {
        val resolvedConfig = config ?: return TelemetryFlushResult(
            success = false,
            acceptedEvents = 0,
            queuedEventsAfter = outboxRepository.pendingCount(),
            message = FLUSH_MESSAGE_NOT_CONFIGURED,
        )
        var totalAccepted = 0
        repeat(maxBatches) {
            val result = flushSingleBatch(resolvedConfig)
            totalAccepted += result.acceptedEvents
            if (!result.success || result.acceptedEvents == 0 || result.queuedEventsAfter == 0) {
                return if (totalAccepted > 0 && result.success) {
                    result.copy(acceptedEvents = totalAccepted)
                } else {
                    result
                }
            }
        }
        return TelemetryFlushResult(
            success = true,
            acceptedEvents = totalAccepted,
            queuedEventsAfter = outboxRepository.pendingCount(),
            message = if (totalAccepted > 0) FLUSH_MESSAGE_SUCCESS else FLUSH_MESSAGE_EMPTY,
        )
    }

    private suspend fun flushSingleBatch(config: TelemetrySendConfig): TelemetryFlushResult {
        val batch = outboxRepository.loadReadyBatch(limit = runtimeConfig.batchSize)
        if (batch.isEmpty()) {
            return TelemetryFlushResult(
                success = true,
                acceptedEvents = 0,
                queuedEventsAfter = outboxRepository.pendingCount(),
                message = FLUSH_MESSAGE_EMPTY,
            )
        }

        val ensuredInstallationId = runCatching { ensureInstallationRegistered(config) }
            .getOrElse { error ->
                return handleFlushFailure(batch, error.message ?: REGISTRATION_FAILED_MESSAGE)
            }

        val rowIds = batch.map { it.rowId }
        val payload = TelemetryBatchRequest(
            schemaVersion = TELEMETRY_SCHEMA_VERSION,
            client = clientMetadata(installationId = ensuredInstallationId),
            events = batch.map { it.event },
        )
        val payloadJson = httpObjectMapper.writeValueAsString(payload)
        val signedRequest = signedBatchRequest(
            path = runtimeConfig.batchPath,
            installationId = ensuredInstallationId,
            payloadJson = payloadJson,
        )

        return try {
            val response = httpClient.post(config.batchUrl) {
                contentType(ContentType.Application.Json)
                header(HEADER_INSTALLATION_ID, ensuredInstallationId)
                header(HEADER_TIMESTAMP, signedRequest.timestampMs.toString())
                header(HEADER_NONCE, signedRequest.nonce)
                header(HEADER_SIGNATURE, signedRequest.signature)
                header(HEADER_KEY_ALGORITHM, runtimeConfig.keyAlgorithm)
                setBody(payloadJson)
            }
            if (response.status.value in 200..299) {
                outboxRepository.delete(rowIds)
                lastSuccessfulFlushAtMs.set(System.currentTimeMillis())
                lastErrorMessage.set(null)
                TelemetryFlushResult(
                    success = true,
                    acceptedEvents = batch.size,
                    queuedEventsAfter = outboxRepository.pendingCount(),
                    message = FLUSH_MESSAGE_SUCCESS,
                )
            } else {
                maybeResetRegistration(response.status.value)
                handleFlushFailure(batch, "HTTP ${response.status.value}")
            }
        } catch (e: Exception) {
            handleFlushFailure(batch, e.message ?: e::class.java.simpleName)
        }
    }

    private fun handleFlushFailure(
        batch: List<QueuedTelemetryEvent>,
        errorMessage: String,
    ): TelemetryFlushResult {
        val maxAttempt = batch.maxOfOrNull { it.attemptCount } ?: 0
        val nextAttemptAtMs = System.currentTimeMillis() + retryDelayMs(maxAttempt + 1)
        outboxRepository.markFailed(
            rowIds = batch.map { it.rowId },
            nextAttemptAtMs = nextAttemptAtMs,
            errorMessage = errorMessage,
        )
        lastErrorMessage.set(errorMessage)
        return TelemetryFlushResult(
            success = false,
            acceptedEvents = 0,
            queuedEventsAfter = outboxRepository.pendingCount(),
            message = "$FLUSH_MESSAGE_FAILED_PREFIX$errorMessage",
        )
    }

    private suspend fun ensureInstallationRegistered(config: TelemetrySendConfig): String =
        installationId.get()?.takeIf { it.isNotBlank() } ?: registrationMutex.withLock {
            installationId.get()?.takeIf { it.isNotBlank() } ?: registerInstallation(config)
        }

    private suspend fun registerInstallation(config: TelemetrySendConfig): String {
        val payload = TelemetryInstallationRegistrationRequest(
            schemaVersion = TELEMETRY_SCHEMA_VERSION,
            userId = identity.userId,
            deviceId = identity.deviceId,
            publicKey = identity.keyPair.encodedPublicKey,
            keyAlgorithm = runtimeConfig.keyAlgorithm,
            client = clientMetadata(installationId = null),
        )
        val payloadJson = httpObjectMapper.writeValueAsString(payload)
        val signedRequest = signedRegistrationRequest(
            path = runtimeConfig.registrationPath,
            payloadJson = payloadJson,
        )

        val response = httpClient.post(config.registrationUrl) {
            contentType(ContentType.Application.Json)
            header(HEADER_TIMESTAMP, signedRequest.timestampMs.toString())
            header(HEADER_NONCE, signedRequest.nonce)
            header(HEADER_SIGNATURE, signedRequest.signature)
            header(HEADER_KEY_ALGORITHM, runtimeConfig.keyAlgorithm)
            setBody(payloadJson)
        }
        if (response.status.value !in 200..299) {
            val responseText = response.bodyAsText().trim()
            error(
                buildString {
                    append("Registration HTTP ${response.status.value}")
                    if (responseText.isNotEmpty()) append(": ${responseText.take(MAX_ERROR_BODY_CHARS)}")
                }
            )
        }

        val responseBody = response.bodyAsText().trim()
        val registrationResponse = httpObjectMapper.readValue(
            responseBody,
            TelemetryInstallationRegistrationResponse::class.java,
        )
        val resolvedInstallationId = registrationResponse.installationId.trim()
        require(resolvedInstallationId.isNotEmpty()) { "Empty installationId in registration response" }
        persistInstallationId(resolvedInstallationId)
        lastErrorMessage.set(null)
        return resolvedInstallationId
    }

    private fun enqueueAppOpened() {
        enqueueEvent(
            eventType = TelemetryEventType.APP_OPENED,
            payload = mapOf(
                "edition" to editionWireValue(),
                "osName" to System.getProperty("os.name").orEmpty(),
                "osVersion" to System.getProperty("os.version").orEmpty(),
                "osArch" to System.getProperty("os.arch").orEmpty(),
            ),
        )
    }

    private fun enqueueAppClosed() {
        enqueueEvent(
            eventType = TelemetryEventType.APP_CLOSED,
            payload = mapOf("durationMs" to (System.currentTimeMillis() - appStartedAtMs)),
        )
    }

    private fun enqueueEvent(
        eventType: TelemetryEventType,
        conversationId: String? = null,
        requestId: String? = null,
        payload: Map<String, Any?> = emptyMap(),
    ) {
        val event = buildEvent(
            eventType = eventType,
            conversationId = conversationId,
            requestId = requestId,
            payload = payload,
        )
        outboxRepository.enqueue(listOf(event))
    }

    private fun buildEvent(
        eventType: TelemetryEventType,
        conversationId: String? = null,
        requestId: String? = null,
        payload: Map<String, Any?> = emptyMap(),
    ): TelemetryEvent = TelemetryEvent(
        eventId = UUID.randomUUID().toString(),
        type = eventType.wireName,
        occurredAtMs = System.currentTimeMillis(),
        userId = identity.userId,
        deviceId = identity.deviceId,
        appSessionId = appSessionId,
        conversationId = conversationId,
        requestId = requestId,
        payload = payload.withoutNullValues(),
    )

    private fun currentSendConfig(): TelemetrySendConfig? {
        val baseUrl = runtimeConfig.baseUrl.trim()
        if (baseUrl.isBlank()) return null
        return runCatching {
            TelemetrySendConfig(
                registrationUrl = resolvedUrl(baseUrl, runtimeConfig.registrationPath),
                batchUrl = resolvedUrl(baseUrl, runtimeConfig.batchPath),
            )
        }.getOrNull()
    }

    private fun clientMetadata(installationId: String?): TelemetryClientMetadata = TelemetryClientMetadata(
        appName = runtimeConfig.appName,
        appVersion = appVersion(),
        edition = editionWireValue(),
        userId = identity.userId,
        deviceId = identity.deviceId,
        installationId = installationId,
        appSessionId = appSessionId,
        osName = System.getProperty("os.name").orEmpty(),
        osVersion = System.getProperty("os.version").orEmpty(),
        osArch = System.getProperty("os.arch").orEmpty(),
        sentAtMs = System.currentTimeMillis(),
    )

    private fun editionWireValue(): String =
        if (settingsProvider.regionProfile == REGION_EN) REGION_EN else REGION_RU

    private fun resolvedUrl(baseUrl: String, path: String): String =
        URI(baseUrl.trim().ifEmpty { error("Empty telemetry base URL") })
            .resolve(path.removePrefix("/"))
            .toString()

    private fun appVersion(): String =
        TelemetryService::class.java.`package`?.implementationVersion?.trim().orEmpty().ifBlank { "dev" }

    private fun loadInstallationId(): String? =
        ConfigStore.get<String>(TelemetryStorageKeys.INSTALLATION_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun persistInstallationId(value: String) {
        installationId.set(value)
        ConfigStore.put(TelemetryStorageKeys.INSTALLATION_ID, value)
    }

    private fun clearInstallationId() {
        installationId.set(null)
        ConfigStore.rm(TelemetryStorageKeys.INSTALLATION_ID)
    }

    private fun maybeResetRegistration(statusCode: Int) {
        if (statusCode in REGISTRATION_RESET_STATUSES) {
            clearInstallationId()
        }
    }

    private fun resolveInstallationIdentity(): TelemetryInstallationIdentity {
        val storedUserId = ConfigStore.get<String>(TelemetryStorageKeys.USER_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val storedDeviceId = ConfigStore.get<String>(TelemetryStorageKeys.DEVICE_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val storedPrivateKey = ConfigStore.get<String>(TelemetryStorageKeys.PRIVATE_KEY)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val storedPublicKey = ConfigStore.get<String>(TelemetryStorageKeys.PUBLIC_KEY)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        val keyPair = if (storedPrivateKey != null && storedPublicKey != null) {
            runCatching {
                cryptoService.loadSigningKeyPair(runtimeConfig.keyAlgorithm, storedPrivateKey, storedPublicKey)
            }
                .onFailure {
                    l.warn("Failed to load telemetry signing key, generating a new one: {}", it.message)
                    clearInstallationId()
                }
                .getOrNull()
        } else {
            null
        } ?: cryptoService.generateSigningKeyPair(runtimeConfig.keyAlgorithm).also {
            ConfigStore.put(TelemetryStorageKeys.PRIVATE_KEY, it.encodedPrivateKey)
            ConfigStore.put(TelemetryStorageKeys.PUBLIC_KEY, it.encodedPublicKey)
            clearInstallationId()
        }

        val resolvedUserId = storedUserId ?: UUID.randomUUID().toString().also {
            ConfigStore.put(TelemetryStorageKeys.USER_ID, it)
        }
        val resolvedDeviceId = storedDeviceId ?: UUID.randomUUID().toString().also {
            ConfigStore.put(TelemetryStorageKeys.DEVICE_ID, it)
        }

        if (storedUserId == null) ConfigStore.put(TelemetryStorageKeys.USER_ID, resolvedUserId)
        if (storedDeviceId == null) ConfigStore.put(TelemetryStorageKeys.DEVICE_ID, resolvedDeviceId)

        return TelemetryInstallationIdentity(
            userId = resolvedUserId,
            deviceId = resolvedDeviceId,
            keyPair = keyPair,
        )
    }

    private fun signedRegistrationRequest(path: String, payloadJson: String): SignedTelemetryRequest {
        val timestampMs = System.currentTimeMillis()
        val nonce = UUID.randomUUID().toString()
        val signature = cryptoService.signPayload(
            keyAlgorithm = runtimeConfig.keyAlgorithm,
            privateKey = identity.keyPair.privateKey,
            payload = registrationSignaturePayload(
                path = path,
                timestampMs = timestampMs,
                nonce = nonce,
                payloadJson = payloadJson,
            ),
        )
        return SignedTelemetryRequest(timestampMs, nonce, signature)
    }

    private fun signedBatchRequest(
        path: String,
        installationId: String,
        payloadJson: String,
    ): SignedTelemetryRequest {
        val timestampMs = System.currentTimeMillis()
        val nonce = UUID.randomUUID().toString()
        val signature = cryptoService.signPayload(
            keyAlgorithm = runtimeConfig.keyAlgorithm,
            privateKey = identity.keyPair.privateKey,
            payload = batchSignaturePayload(
                path = path,
                installationId = installationId,
                timestampMs = timestampMs,
                nonce = nonce,
                payloadJson = payloadJson,
            ),
        )
        return SignedTelemetryRequest(timestampMs, nonce, signature)
    }

    private fun registrationSignaturePayload(
        path: String,
        timestampMs: Long,
        nonce: String,
        payloadJson: String,
    ): String = listOf(
        "POST",
        path,
        timestampMs.toString(),
        nonce,
        cryptoService.sha256Base64(payloadJson),
    ).joinToString("\n")

    private fun batchSignaturePayload(
        path: String,
        installationId: String,
        timestampMs: Long,
        nonce: String,
        payloadJson: String,
    ): String = listOf(
        "POST",
        path,
        installationId,
        timestampMs.toString(),
        nonce,
        cryptoService.sha256Base64(payloadJson),
    ).joinToString("\n")

    private fun retryDelayMs(attempt: Int): Long {
        val boundedAttempt = attempt.coerceIn(1, 6)
        return runtimeConfig.baseRetryDelayMs * (1L shl (boundedAttempt - 1))
    }

    private fun sanitizeErrorMessage(errorMessage: String?): String? =
        errorMessage
            ?.trim()
            ?.lineSequence()
            ?.firstOrNull()
            ?.substringBefore(':')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.split(Regex("\\s+"))
            ?.joinToString("_")
            ?.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
            ?.takeIf { it.isNotEmpty() }
            ?.take(MAX_ERROR_MESSAGE_CHARS)

    private fun usagePayload(usage: GigaResponse.Usage): Map<String, Int> = mapOf(
        "promptTokens" to usage.promptTokens,
        "completionTokens" to usage.completionTokens,
        "totalTokens" to usage.totalTokens,
        "precachedTokens" to usage.precachedTokens,
    )

    private fun Map<String, Any?>.withoutNullValues(): Map<String, Any?> =
        entries.associateNotNull { (key, value) -> value?.let { key to it } }

    private data class TelemetrySendConfig(
        val registrationUrl: String,
        val batchUrl: String,
    )

    private data class SignedTelemetryRequest(
        val timestampMs: Long,
        val nonce: String,
        val signature: String,
    )

    companion object {
        private const val HEADER_TIMESTAMP = "X-Telemetry-Timestamp"
        private const val HEADER_NONCE = "X-Telemetry-Nonce"
        private const val HEADER_SIGNATURE = "X-Telemetry-Signature"
        private const val HEADER_KEY_ALGORITHM = "X-Telemetry-Key-Algorithm"
        private const val HEADER_INSTALLATION_ID = "X-Telemetry-Installation-Id"
        private const val MAX_ERROR_BODY_CHARS = 256
        private const val REGISTRATION_FAILED_MESSAGE = "Telemetry installation registration failed"
        private val REGISTRATION_RESET_STATUSES = setOf(401, 403, 404, 409)
        private const val MAX_ERROR_MESSAGE_CHARS = 80

        const val FLUSH_MESSAGE_NOT_CONFIGURED = "Telemetry transport is not configured"
        const val FLUSH_MESSAGE_EMPTY = "No pending telemetry events"
        const val FLUSH_MESSAGE_SUCCESS = "Pending telemetry events sent"
        const val FLUSH_MESSAGE_FAILED_PREFIX = "Telemetry send failed: "
    }
}

private inline fun <K, V, R : Any> Iterable<Map.Entry<K, V>>.associateNotNull(
    transform: (Map.Entry<K, V>) -> Pair<K, R>?,
): Map<K, R> {
    val destination = LinkedHashMap<K, R>()
    for (entry in this) {
        val transformed = transform(entry) ?: continue
        destination[transformed.first] = transformed.second
    }
    return destination
}
