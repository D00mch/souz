package ru.souz.llms

import com.fasterxml.jackson.databind.DeserializationFeature
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.HttpHeaders
import io.ktor.serialization.jackson.jackson
import org.slf4j.LoggerFactory
import ru.souz.llms.openai.openAiTlsDefaults
import ru.souz.llms.tls.trustManagerFromPem
import kotlin.time.Duration.Companion.seconds

/**
 * Creates an application-owned provider client without request-scoped credentials.
 * Callers may safely share the returned client between users as long as credentials,
 * request timeouts, and other user settings are applied to each request.
 */
fun createProviderHttpClient(provider: LlmProvider): HttpClient {
    require(provider != LlmProvider.LOCAL) { "Local models do not use an HTTP client." }
    val log = LoggerFactory.getLogger("ru.souz.llms.http.${provider.name.lowercase()}")

    return HttpClient(CIO) {
        install(HttpTimeout)
        install(ContentNegotiation) {
            jackson {
                disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            }
        }
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) = log.debug(message)
            }
            level = LogLevel.INFO
            sanitizeHeader {
                it.equals(HttpHeaders.Authorization, ignoreCase = true) ||
                    it.equals("x-api-key", ignoreCase = true)
            }
        }
        if (provider == LlmProvider.GIGA || provider == LlmProvider.QWEN || provider == LlmProvider.ANTHROPIC) {
            install(SSE) {
                maxReconnectionAttempts = 0
                reconnectionTime = 3.seconds
            }
        }
        when (provider) {
            LlmProvider.GIGA -> engine {
                https {
                    trustManager = trustManagerFromPem(
                        "certs/russian_trusted_root_ca_gost_2025.cer",
                        "certs/russian_trusted_sub_ca_gost_2025.cer",
                        "certs/russiantrustedca.pem",
                        "certs/russiantrustedca2024.pem",
                    )
                }
            }

            LlmProvider.OPENAI -> openAiTlsDefaults()
            else -> Unit
        }
    }
}
