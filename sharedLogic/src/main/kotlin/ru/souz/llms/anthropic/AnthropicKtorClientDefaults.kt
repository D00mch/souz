package ru.souz.llms.anthropic

import com.fasterxml.jackson.databind.DeserializationFeature
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIOEngineConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.jackson.jackson

fun HttpClientConfig<CIOEngineConfig>.anthropicDefaults(
    apiKey: String?,
    version: String = "2023-06-01",
    requestTimeoutMillis: Long = 60_000,
) {
    defaultRequest {
        header(HttpHeaders.ContentType, ContentType.Application.Json)
        header(HttpHeaders.Accept, ContentType.Application.Json)
        if (!apiKey.isNullOrBlank()) {
            header("x-api-key", apiKey)
        }
        header("anthropic-version", version)
    }
    install(HttpTimeout) {
        this.requestTimeoutMillis = requestTimeoutMillis
    }
    install(ContentNegotiation) {
        jackson {
            disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        }
    }
}
