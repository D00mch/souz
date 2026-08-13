package ru.souz.llms.giga

import com.fasterxml.jackson.databind.DeserializationFeature
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import ru.souz.llms.ProviderSettings
import ru.souz.llms.tls.trustManagerFromPem
import java.util.*

fun HttpClientConfig<CIOEngineConfig>.gigaDefaults(settingsProvider: ProviderSettings) {
    this.defaultRequest {
        header(HttpHeaders.ContentType, "application/json")
        header(HttpHeaders.Accept, "application/json")
        header(HttpHeaders.UserAgent, "Souz")
        header("RqUID", UUID.randomUUID().toString())
    }
    install(HttpTimeout) {
        requestTimeoutMillis = settingsProvider.requestTimeoutMillis
    }
    install(ContentNegotiation) {
        jackson { this.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES) }
    }
    engine {
        https {
            trustManager = trustManagerFromPem(
                "certs/russian_trusted_root_ca_gost_2025.cer",
                "certs/russian_trusted_sub_ca_gost_2025.cer",
                "certs/russiantrustedca.pem",
                "certs/russiantrustedca2024.pem",
            )
        }
    }
}
