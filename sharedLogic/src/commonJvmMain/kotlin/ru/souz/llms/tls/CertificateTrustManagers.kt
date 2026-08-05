package ru.souz.llms.tls

import java.io.File
import java.io.InputStream
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

fun trustManagerFromPem(vararg resourcePaths: String): X509TrustManager =
    trustManagerFromStreams(
        resourcePaths.map { path ->
            CertificateInput { resourceStream(path) }
        },
        includeDefaultTrustManager = false,
        emptyInputMessage = "No certificate resources provided.",
        emptyCertificateMessage = "No loadable X.509 certificates found in resources.",
    )

fun trustManagerFromPemFiles(
    files: List<File>,
    includeDefaultTrustManager: Boolean,
    emptyInputMessage: String,
    emptyCertificateMessage: String,
): X509TrustManager =
    trustManagerFromStreams(
        files.map { file ->
            CertificateInput { file.inputStream() }
        },
        includeDefaultTrustManager = includeDefaultTrustManager,
        emptyInputMessage = emptyInputMessage,
        emptyCertificateMessage = emptyCertificateMessage,
    )

private fun trustManagerFromStreams(
    inputs: List<CertificateInput>,
    includeDefaultTrustManager: Boolean,
    emptyInputMessage: String,
    emptyCertificateMessage: String,
): X509TrustManager {
    require(inputs.isNotEmpty()) { emptyInputMessage }

    val cf = CertificateFactory.getInstance("X.509")
    val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
    var index = 0
    inputs.forEach { input ->
        input.open().use { stream ->
            cf.generateCertificates(stream).forEach { cert ->
                ks.setCertificateEntry("extra-ca-${index++}", cert)
            }
        }
    }
    require(index > 0) { emptyCertificateMessage }

    val extraTrustManager = trustManagerFromKeyStore(ks)
    return if (includeDefaultTrustManager) {
        CompositeX509TrustManager(defaultX509TrustManager(), extraTrustManager)
    } else {
        extraTrustManager
    }
}

private fun defaultX509TrustManager(): X509TrustManager {
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
        init(null as KeyStore?)
    }
    return tmf.trustManagers.filterIsInstance<X509TrustManager>().single()
}

private fun trustManagerFromKeyStore(keyStore: KeyStore): X509TrustManager {
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
        init(keyStore)
    }
    return tmf.trustManagers.filterIsInstance<X509TrustManager>().single()
}

private class CompositeX509TrustManager(
    private val defaultTrustManager: X509TrustManager,
    private val extraTrustManager: X509TrustManager,
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        checkTrusted(chain, authType, defaultTrustManager::checkClientTrusted, extraTrustManager::checkClientTrusted)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        checkTrusted(chain, authType, defaultTrustManager::checkServerTrusted, extraTrustManager::checkServerTrusted)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        defaultTrustManager.acceptedIssuers + extraTrustManager.acceptedIssuers

    private fun checkTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        defaultCheck: (Array<X509Certificate>, String) -> Unit,
        extraCheck: (Array<X509Certificate>, String) -> Unit,
    ) {
        try {
            defaultCheck(chain, authType)
        } catch (defaultError: CertificateException) {
            try {
                extraCheck(chain, authType)
            } catch (_: CertificateException) {
                throw defaultError
            }
        }
    }
}

private data class CertificateInput(
    val open: () -> InputStream,
)

private fun resourceStream(path: String): InputStream =
    Thread.currentThread().contextClassLoader?.getResourceAsStream(path)
        ?: CertificateTrustManagerResourceAnchor::class.java.classLoader?.getResourceAsStream(path)
        ?: error("Resource not found: $path")

private object CertificateTrustManagerResourceAnchor
