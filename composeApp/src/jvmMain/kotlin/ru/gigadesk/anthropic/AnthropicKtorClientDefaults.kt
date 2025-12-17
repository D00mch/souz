package ru.gigadesk.anthropic

import com.fasterxml.jackson.databind.DeserializationFeature
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*

fun HttpClientConfig<CIOEngineConfig>.anthropicDefaults(
    apiKey: String = System.getenv("ANTHROPIC_API_KEY")
        ?: System.getProperty("ANTHROPIC_API_KEY")
        ?: error("ANTHROPIC_API_KEY is not set"),
    version: String = "2023-06-01",
) {
    defaultRequest {
        header(HttpHeaders.ContentType, ContentType.Application.Json)
        header("x-api-key", apiKey)
        header("anthropic-version", version)
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 60_000
    }
    install(ContentNegotiation) {
        jackson {
            disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        }
    }
}
