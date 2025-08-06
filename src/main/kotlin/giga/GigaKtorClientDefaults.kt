package com.dumch.giga

import com.fasterxml.jackson.databind.DeserializationFeature
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import java.security.cert.X509Certificate
import java.util.*
import javax.net.ssl.X509TrustManager

fun HttpClientConfig<CIOEngineConfig>.gigaDefaults() {
    this.defaultRequest {
        header(HttpHeaders.ContentType, "application/json")
        header(HttpHeaders.Accept, "application/json")
        header("RqUID", UUID.randomUUID().toString())
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 20000
    }
    install(ContentNegotiation) {
        jackson { this.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES) }
    }
    engine {
        https {
            trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
        }
    }
}

