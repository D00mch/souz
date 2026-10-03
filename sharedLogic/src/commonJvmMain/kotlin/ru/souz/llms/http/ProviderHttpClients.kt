package ru.souz.llms.http

import com.fasterxml.jackson.databind.DeserializationFeature
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.HttpHeaders
import io.ktor.serialization.jackson.jackson
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import org.slf4j.LoggerFactory
import ru.souz.llms.openai.openAiTlsDefaults

/** Process-owned HTTP clients shared by provider adapters. */
class ProviderHttpClients(
    val standard: HttpClient,
    val openAi: HttpClient,
    jev: HttpClient? = standard,
) : AutoCloseable {
    constructor() : this(createProviderHttpClientPair())

    private constructor(pair: ProviderHttpClientPair) : this(pair.standard, pair.openAi, jev = null)

    private val jevResource = if (jev == null) lazy { createJevProviderHttpClient() } else lazyOf(jev)

    /** Separate, lazily created CIO profile; the host owns its shutdown too. */
    val jev: HttpClient
        get() {
            check(!closed.get()) { "Provider HTTP clients are closed" }
            return jevResource.value
        }

    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        var failure: Throwable? = null
        val clients = listOfNotNull(standard, openAi, jevResource.takeIf { it.isInitialized() }?.value)
        for (client in clients.distinct()) {
            try {
                client.close()
            } catch (closeFailure: Throwable) {
                if (failure == null) {
                    failure = closeFailure
                } else {
                    failure.addSuppressed(closeFailure)
                }
            }
        }
        failure?.let { throw it }
    }
}

private data class ProviderHttpClientPair(
    val standard: HttpClient,
    val openAi: HttpClient,
)

private fun createProviderHttpClientPair(): ProviderHttpClientPair {
    val standard = createStandardProviderHttpClient()
    return try {
        ProviderHttpClientPair(
            standard = standard,
            openAi = createOpenAiProviderHttpClient(),
        )
    } catch (failure: Throwable) {
        runCatching { standard.close() }
            .exceptionOrNull()
            ?.let(failure::addSuppressed)
        throw failure
    }
}

fun createStandardProviderHttpClient(): HttpClient =
    HttpClient(CIO) {
        providerHttpClientDefaults()
    }

/** CIO's idle setting is configurable for experiments; POST requests still use dedicated connections. */
fun createJevProviderHttpClient(
    keepAliveTimeMillis: Long = jevKeepAliveTimeMillis(System.getenv("JEV_KEEP_ALIVE_TIME_MS")),
): HttpClient {
    require(keepAliveTimeMillis > 0) { "JEV_KEEP_ALIVE_TIME_MS must be a positive integer in milliseconds" }
    return HttpClient(CIO) {
        providerHttpClientDefaults()
        engine {
            endpoint { keepAliveTime = keepAliveTimeMillis }
        }
    }
}

internal fun jevKeepAliveTimeMillis(value: String?): Long {
    val configured = value?.trim()?.takeIf(String::isNotEmpty) ?: return 5_000L
    return requireNotNull(configured.toLongOrNull()?.takeIf { it > 0 }) {
        "JEV_KEEP_ALIVE_TIME_MS must be a positive integer in milliseconds"
    }
}

fun createOpenAiProviderHttpClient(): HttpClient =
    HttpClient(CIO) {
        providerHttpClientDefaults()
        openAiTlsDefaults()
    }

/** Shared plugin contract for production clients and MockEngine-based tests. */
fun <T : HttpClientEngineConfig> HttpClientConfig<T>.providerHttpClientDefaults() {
    install(HttpTimeout)
    install(ContentNegotiation) {
        jackson {
            disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        }
    }
    install(SSE) {
        maxReconnectionAttempts = 0
        reconnectionTime = 3.seconds
    }
    install(Logging) {
        logger = object : Logger {
            private val delegate = LoggerFactory.getLogger("ProviderHttpClient")

            override fun log(message: String) {
                delegate.debug(message)
            }
        }
        level = LogLevel.INFO
        sanitizeHeader { header ->
            header.equals(HttpHeaders.Authorization, ignoreCase = true) ||
                header.equals("x-api-key", ignoreCase = true)
        }
    }
}
