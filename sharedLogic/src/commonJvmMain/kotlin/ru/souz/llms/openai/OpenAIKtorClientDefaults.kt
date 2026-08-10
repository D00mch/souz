package ru.souz.llms.openai

import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIOEngineConfig
import ru.souz.llms.tls.trustManagerFromPemFiles
import java.io.File
import javax.net.ssl.X509TrustManager

internal const val OPENAI_CA_CERTS_DIR_ENV = "OPENAI_CA_CERTS_DIR"

internal fun HttpClientConfig<CIOEngineConfig>.openAiTlsDefaults() {
    val extraTrustManager = openAiTrustManagerFromPemDirectoryEnv() ?: return
    engine {
        https {
            trustManager = extraTrustManager
        }
    }
}

internal fun openAiTrustManagerFromPemDirectoryEnv(): X509TrustManager? {
    val directory = System.getenv(OPENAI_CA_CERTS_DIR_ENV)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return trustManagerFromPemDirectory(File(directory))
}

internal fun trustManagerFromPemDirectory(directory: File): X509TrustManager? {
    if (!directory.isDirectory) {
        return null
    }

    val certificateFiles = directory.listFiles()
        ?.filter { it.isFile && it.extension.lowercase() in certificateExtensions }
        ?.sortedBy { it.name }
        .orEmpty()

    if (certificateFiles.isEmpty()) {
        return null
    }

    return trustManagerFromPemFiles(
        files = certificateFiles,
        includeDefaultTrustManager = true,
        emptyInputMessage = "$OPENAI_CA_CERTS_DIR_ENV contains no .pem, .crt, or .cer files: ${directory.absolutePath}",
        emptyCertificateMessage = "$OPENAI_CA_CERTS_DIR_ENV contains no loadable X.509 certificates: ${directory.absolutePath}",
    )
}

private val certificateExtensions = setOf("pem", "crt", "cer")
