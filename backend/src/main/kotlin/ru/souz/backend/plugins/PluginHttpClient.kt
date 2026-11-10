package ru.souz.backend.plugins

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.http.URLParserException
import io.ktor.http.Url
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import ru.souz.plugins.api.PluginHttp
import ru.souz.plugins.api.PluginHttpRequest
import ru.souz.plugins.api.PluginHttpResponse

/** Dedicated host client, without provider credentials, cookies, redirects or retry plugins. */
internal class PluginHttpClient(private val client: HttpClient = HttpClient(CIO) {
    followRedirects = false
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 5_000
        socketTimeoutMillis = 15_000
    }
}) : PluginHttp, AutoCloseable {
    override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse {
        require(request.url.length <= 8192 && request.method in METHODS && request.body.size <= MAX_BYTES)
        val target = try { Url(request.url) }
        catch (_: URLParserException) { throw IllegalArgumentException("Invalid HTTP URL.") }
        require(target.protocol.name == "http" || target.protocol.name == "https") { "HTTP(S) URL required." }
        require(target.user.isNullOrEmpty() && target.password.isNullOrEmpty()) { "URL credentials are forbidden." }
        require(request.headers.size <= 64 && request.headers.entries.sumOf { (key, values) ->
            key.length + values.sumOf(String::length)
        } <= 16_384) { "HTTP request metadata is too large." }
        require(request.headers.keys.none { it.lowercase() in RESERVED_HEADERS }) { "Reserved transport header." }
        require((request.method != "GET" && request.method != "HEAD") || request.body.isEmpty()) { "Unexpected HTTP body." }
        return client.prepareRequest(target) {
            method = HttpMethod.parse(request.method)
            request.headers.forEach { (key, values) -> headers.appendAll(key, values) }
            if (request.body.isNotEmpty()) setBody(request.body.copyOf())
        }.execute { response ->
            val headers = response.headers.entries()
            require(headers.size <= 64 && headers.sumOf { (key, values) -> key.length + values.sumOf(String::length) } <= 16_384) {
                "HTTP response metadata is too large."
            }
            val buffer = ByteArray(8192)
            val output = ByteArrayOutputStream()
            val channel = response.bodyAsChannel()
            while (true) {
                val read = channel.readAvailable(buffer)
                if (read < 0) break
                require(output.size() + read <= MAX_BYTES) { "HTTP response exceeds 1 MiB." }
                output.write(buffer, 0, read)
            }
            PluginHttpResponse(response.status.value, headers.associate { it.key to it.value.toList() }, output.toByteArray())
        }
    }

    override fun close() = client.close()

    private companion object {
        const val MAX_BYTES = 1_048_576
        val METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        val RESERVED_HEADERS = setOf("host", "connection", "content-length", "transfer-encoding", "proxy-authorization")
    }
}
