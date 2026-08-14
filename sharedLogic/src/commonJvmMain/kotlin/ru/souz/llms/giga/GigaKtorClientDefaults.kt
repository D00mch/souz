package ru.souz.llms.giga

import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIOEngineConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import ru.souz.db.SettingsProvider
import ru.souz.llms.tls.trustManagerFromPem
import java.util.UUID

fun HttpRequestBuilder.gigaRequestDefaults(settingsProvider: SettingsProvider) {
    header(HttpHeaders.ContentType, "application/json")
    header(HttpHeaders.Accept, "application/json")
    header(HttpHeaders.UserAgent, "Souz")
    header("RqUID", UUID.randomUUID().toString())
    timeout {
        requestTimeoutMillis = settingsProvider.requestTimeoutMillis
    }
}

fun HttpClientConfig<CIOEngineConfig>.gigaTlsDefaults() {
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
