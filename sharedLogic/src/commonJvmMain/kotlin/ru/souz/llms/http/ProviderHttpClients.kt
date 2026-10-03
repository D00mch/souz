package ru.souz.llms.http

import com.fasterxml.jackson.databind.DeserializationFeature
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.cio.CIO
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
    val jev: HttpClient = standard,
) : AutoCloseable {
    constructor(
        jevTransport: JevHttpTransport? = null,
        jevIdleRetentionMillis: Long = JEV_IDLE_RETENTION_MILLIS,
    ) : this(createProviderHttpClientSet(jevTransport, jevIdleRetentionMillis))

    private constructor(clients: ProviderHttpClientSet) : this(clients.standard, clients.openAi, clients.jev)

    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        var failure: Throwable? = null
        val distinctClients = listOf(standard, openAi, jev).fold(mutableListOf<HttpClient>()) { clients, client ->
            if (clients.none { it === client }) clients.add(client)
            clients
        }
        for (client in distinctClients) {
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

private data class ProviderHttpClientSet(
    val standard: HttpClient,
    val openAi: HttpClient,
    val jev: HttpClient,
)

private fun createProviderHttpClientSet(
    jevTransport: JevHttpTransport?,
    jevIdleRetentionMillis: Long,
): ProviderHttpClientSet {
    val standard = createStandardProviderHttpClient()
    var openAi: HttpClient? = null
    return try {
        openAi = createOpenAiProviderHttpClient()
        ProviderHttpClientSet(
            standard = standard,
            openAi = openAi,
            jev = jevTransport?.let { createJevProviderHttpClient(it, jevIdleRetentionMillis) } ?: standard,
        )
    } catch (failure: Throwable) {
        runCatching { standard.close() }
            .exceptionOrNull()
            ?.let(failure::addSuppressed)
        runCatching { openAi?.close() }
            .exceptionOrNull()
            ?.let(failure::addSuppressed)
        throw failure
    }
}

fun createStandardProviderHttpClient(): HttpClient =
    HttpClient(CIO) {
        providerHttpClientDefaults()
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
