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
) : AutoCloseable {
    constructor() : this(createProviderHttpClientPair())

    private constructor(pair: ProviderHttpClientPair) : this(pair.standard, pair.openAi)

    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        var failure: Throwable? = null
        try {
            standard.close()
        } catch (standardFailure: Throwable) {
            failure = standardFailure
        }
        if (openAi !== standard) {
            try {
                openAi.close()
            } catch (openAiFailure: Throwable) {
                if (failure == null) {
                    failure = openAiFailure
                } else {
                    failure.addSuppressed(openAiFailure)
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
