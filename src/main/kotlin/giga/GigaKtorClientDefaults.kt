package com.dumch.giga

import com.fasterxml.jackson.databind.DeserializationFeature
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import java.io.IOException
import java.io.InputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.*
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

fun HttpClientConfig<CIOEngineConfig>.gigaDefaults() {
    this.defaultRequest {
        header(HttpHeaders.ContentType, "application/json")
        header(HttpHeaders.Accept, "application/json")
        header("RqUID", UUID.randomUUID().toString())
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 10000
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

private fun resourceStream(path: String) =
    Thread.currentThread().contextClassLoader.getResourceAsStream(path)
        ?: throw IOException("Certificate not found on classpath: $path")

fun trustManagerFromPem(vararg resourcePaths: String): X509TrustManager {
    val cf = CertificateFactory.getInstance("X.509")
    val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }

    resourcePaths.forEachIndexed { i, path ->
        val ins: InputStream = resourceStream(path)
        ins.use {
            val cert = cf.generateCertificate(it)
            ks.setCertificateEntry("extra-ca-$i", cert)
        }
    }

    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
        init(ks)
    }
    return tmf.trustManagers.filterIsInstance<X509TrustManager>().single()
}