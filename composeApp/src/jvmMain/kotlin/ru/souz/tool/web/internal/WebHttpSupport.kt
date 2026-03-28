package ru.souz.tool.web.internal

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import ru.souz.tool.BadInputException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import io.ktor.util.AttributeKey
import io.ktor.util.Attributes

private const val WEB_HTTP_CONNECT_TIMEOUT_MILLIS = 6_000L
private const val WEB_HTTP_INITIAL_RETRY_DELAY_MILLIS = 2_000L
private const val WEB_HTTP_MAX_RETRY_DELAY_MILLIS = 12_000L
private const val WEB_HTTP_MAX_RETRIES = 2
private const val WEB_HTTP_DEFAULT_MAX_BINARY_BYTES = 20 * 1024 * 1024
private const val WEB_HTTP_BINARY_READ_CHUNK_BYTES = 8_192
private val WEB_HTTP_RETRY_ENABLED_KEY = AttributeKey<Boolean>("web_http_retry_enabled")
private val defaultSharedWebHttpClient by lazy {
    val webToolSupport = WebToolSupport()
    HttpClient(CIO) { WebHttpSupport.applyDefaults(this, webToolSupport) }
}

internal data class WebTextResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>>,
)

internal data class WebBinaryResponse(
    val statusCode: Int,
    val body: ByteArray,
    val headers: Map<String, List<String>>,
)

internal fun WebTextResponse.firstHeader(name: String): String? = headers.firstHeader(name)

internal fun WebBinaryResponse.firstHeader(name: String): String? = headers.firstHeader(name)

internal fun Map<String, List<String>>.firstHeader(name: String): String? {
    return entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

internal class WebHttpSupport(
    private val webToolSupport: WebToolSupport = WebToolSupport(),
    private val sharedWebHttpClient: HttpClient = defaultSharedWebHttpClient,
) {
    suspend fun getText(
        url: String,
        timeoutMillis: Long,
        accept: String = webToolSupport.acceptHeader,
        retry: Boolean = true,
    ): WebTextResponse {
        return executeWebRequest(url, timeoutMillis, accept, retry) { response ->
            WebTextResponse(
                statusCode = response.status.value,
                body = response.bodyAsText(),
                headers = response.headers.toMap(),
            )
        }
    }

    suspend fun downloadBinary(
        url: String,
        timeoutMillis: Long,
        maxBytes: Int = WEB_HTTP_DEFAULT_MAX_BINARY_BYTES,
    ): WebBinaryResponse {
        return executeWebRequest(url, timeoutMillis, accept = null, retry = true) { response ->
            val headers = response.headers.toMap()
            WebBinaryResponse(
                statusCode = response.status.value,
                body = readLimitedBinaryBody(
                    channel = response.bodyAsChannel(),
                    maxBytes = maxBytes,
                    declaredLength = headers.firstHeader(HttpHeaders.ContentLength)?.toLongOrNull(),
                    url = url,
                ),
                headers = headers,
            )
        }
    }

    suspend fun readLimitedBinaryBody(
        channel: ByteReadChannel,
        maxBytes: Int,
        declaredLength: Long? = null,
        url: String = "response body",
    ): ByteArray {
        require(maxBytes > 0) { "maxBytes must be positive" }
        if (declaredLength != null && declaredLength > maxBytes) {
            throw oversizeBinaryBodyError(url, maxBytes)
        }

        val output = ByteArrayOutputStream(minOf(maxBytes, WEB_HTTP_BINARY_READ_CHUNK_BYTES))
        val buffer = ByteArray(WEB_HTTP_BINARY_READ_CHUNK_BYTES)
        var totalBytes = 0

        while (!channel.isClosedForRead) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read == -1) break
            if (read == 0) continue
            val nextSize = totalBytes + read
            if (nextSize > maxBytes) {
                throw oversizeBinaryBodyError(url, maxBytes)
            }
            output.write(buffer, 0, read)
            totalBytes = nextSize
        }
        return output.toByteArray()
    }

    private suspend fun <T> executeWebRequest(
        url: String,
        timeoutMillis: Long,
        accept: String?,
        retry: Boolean,
        bodyReader: suspend (HttpResponse) -> T,
    ): T {
        try {
            val response = sharedWebHttpClient.get(webToolSupport.toSafeHttpUrl(url)) {
                attributes.put(WEB_HTTP_RETRY_ENABLED_KEY, retry)
                if (!accept.isNullOrBlank()) {
                    header(HttpHeaders.Accept, accept)
                }
                timeout {
                    requestTimeoutMillis = timeoutMillis
                    socketTimeoutMillis = timeoutMillis
                    connectTimeoutMillis = minOf(timeoutMillis, WEB_HTTP_CONNECT_TIMEOUT_MILLIS)
                }
            }
            return bodyReader(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpRequestTimeoutException) {
            throw BadInputException("HTTP request timed out for $url")
        } catch (e: IOException) {
            val message = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            throw BadInputException("HTTP request failed for $url: $message")
        } catch (e: IllegalArgumentException) {
            val message = e.message?.takeIf { it.isNotBlank() } ?: "invalid URL"
            throw BadInputException("HTTP request failed for $url: $message")
        }
    }

    companion object {
        internal fun applyDefaults(
            config: HttpClientConfig<*>,
            webToolSupport: WebToolSupport,
        ) = with(config) {
            expectSuccess = false
            followRedirects = true

            defaultRequest {
                header(HttpHeaders.UserAgent, webToolSupport.userAgent)
            }

            install(HttpTimeout) {
                connectTimeoutMillis = WEB_HTTP_CONNECT_TIMEOUT_MILLIS
            }

            install(HttpRequestRetry) {
                maxRetries = WEB_HTTP_MAX_RETRIES
                retryIf { request, response ->
                    isRetryEnabled(request) && response.status.value in retryableStatusCodes
                }
                retryOnExceptionIf { request, cause ->
                    isRetryEnabled(request) && (cause is HttpRequestTimeoutException || cause is IOException)
                }
                delayMillis { retry ->
                    val retryAfterDelay = response?.headers?.get(HttpHeaders.RetryAfter)?.let(::parseRetryAfterMillis) ?: 0L
                    maxOf(exponentialRetryDelayMillis(retry), retryAfterDelay)
                }
            }
        }
    }
}

private fun isRetryEnabled(request: HttpRequest): Boolean = isRetryEnabled(request.attributes)

private fun isRetryEnabled(request: HttpRequestBuilder): Boolean = isRetryEnabled(request.attributes)

private fun isRetryEnabled(attributes: Attributes): Boolean {
    return if (attributes.contains(WEB_HTTP_RETRY_ENABLED_KEY)) {
        attributes[WEB_HTTP_RETRY_ENABLED_KEY]
    } else {
        true
    }
}

private fun oversizeBinaryBodyError(url: String, maxBytes: Int): BadInputException {
    val maxMegabytes = maxBytes / (1024 * 1024)
    return BadInputException("HTTP response body is larger than ${maxMegabytes}MB for $url")
}

private fun Headers.toMap(): Map<String, List<String>> = entries().associate { it.key to it.value }

private fun exponentialRetryDelayMillis(retry: Int): Long {
    val factor = 1L shl retry.coerceAtLeast(0)
    return (WEB_HTTP_INITIAL_RETRY_DELAY_MILLIS * factor).coerceAtMost(WEB_HTTP_MAX_RETRY_DELAY_MILLIS)
}

private fun parseRetryAfterMillis(value: String): Long? {
    val normalized = value.trim()
    normalized.toLongOrNull()?.let { seconds ->
        return (seconds.coerceAtLeast(0L) * 1_000L)
    }
    return runCatching {
        val retryAtMillis = ZonedDateTime.parse(normalized, DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant()
            .toEpochMilli()
        (retryAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
    }.getOrNull()
}

private val retryableStatusCodes = setOf(429, 500, 502, 503, 504)
