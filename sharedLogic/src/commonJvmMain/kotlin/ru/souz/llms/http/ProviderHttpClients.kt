package ru.souz.llms.http

import com.fasterxml.jackson.databind.DeserializationFeature
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.HttpHeaders
import io.ktor.serialization.jackson.jackson
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Protocol
import kotlin.time.Duration.Companion.seconds
import org.slf4j.LoggerFactory
import ru.souz.llms.openai.openAiTlsDefaults

fun createStandardProviderHttpClient(): HttpClient =
    HttpClient(CIO) {
        providerHttpClientDefaults()
    }

/** Fixed OkHttp HTTP/1.1 transport; the host owns its pool and lifecycle. */
fun createOkHttpProviderHttpClient(): HttpClient = HttpClient(OkHttp) {
    providerHttpClientDefaults()
    engine {
        val pool = ConnectionPool(5, 60, TimeUnit.SECONDS)
        config {
            connectionPool(pool)
            protocols(listOf(Protocol.HTTP_1_1))
            retryOnConnectionFailure(false)
        }
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
