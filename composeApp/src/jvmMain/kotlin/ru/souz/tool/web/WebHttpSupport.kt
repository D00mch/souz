package ru.souz.tool.web

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
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import ru.souz.tool.BadInputException
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import io.ktor.util.AttributeKey
import io.ktor.util.Attributes

private const val WEB_HTTP_CONNECT_TIMEOUT_MILLIS = 6_000L
private const val WEB_HTTP_INITIAL_RETRY_DELAY_MILLIS = 2_000L
private const val WEB_HTTP_MAX_RETRY_DELAY_MILLIS = 12_000L
private const val WEB_HTTP_MAX_RETRIES = 2
private val WEB_HTTP_RETRY_ENABLED_KEY = AttributeKey<Boolean>("web_http_retry_enabled")

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

private val sharedWebHttpClient by lazy {
    HttpClient(CIO) { webToolDefaults() }
}

internal suspend fun webGetText(
    url: String,
    timeoutMillis: Long,
    accept: String = WEB_TOOLS_ACCEPT_HEADER,
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

private fun HttpClientConfig<*>.webToolDefaults() {
    expectSuccess = false
    followRedirects = true

    defaultRequest {
        header(HttpHeaders.UserAgent, WEB_TOOLS_USER_AGENT)
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

internal fun webDownloadBinary(url: String, timeoutMillis: Long): WebBinaryResponse = runBlocking {
    executeWebRequest(url, timeoutMillis, accept = null, retry = true) { response ->
        WebBinaryResponse(
            statusCode = response.status.value,
            body = response.bodyAsBytes(),
            headers = response.headers.toMap(),
        )
    }
}

private suspend fun <T> executeWebRequest(
    url: String,
    timeoutMillis: Long,
    accept: String?,
    retry: Boolean,
    bodyReader: suspend (HttpResponse) -> T,
): T {
    try {
        val response = sharedWebHttpClient.get(toSafeHttpUrl(url)) {
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

private fun isRetryEnabled(request: HttpRequest): Boolean = isRetryEnabled(request.attributes)

private fun isRetryEnabled(request: HttpRequestBuilder): Boolean = isRetryEnabled(request.attributes)

private fun isRetryEnabled(attributes: Attributes): Boolean {
    return if (attributes.contains(WEB_HTTP_RETRY_ENABLED_KEY)) {
        attributes[WEB_HTTP_RETRY_ENABLED_KEY]
    } else {
        true
    }
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
