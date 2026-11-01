package ru.souz.backend.plugins

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import ru.souz.plugins.api.PluginHttp
import ru.souz.plugins.api.PluginHttpResponse

/** One dedicated process-owned client: no provider auth, logging, cookie jar or automatic retries. */
internal class PluginHttpClient(private val client: HttpClient = HttpClient(CIO) {
    followRedirects = false
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 5_000
        socketTimeoutMillis = 15_000
    }
}) : PluginHttp, AutoCloseable {
    override suspend fun request(method: String, url: String, headers: Map<String, String>, body: String?): PluginHttpResponse {
        val target = Url(url)
        require(target.protocol.name == "http" || target.protocol.name == "https") { "HTTP(S) URL required." }
        require(target.user.isNullOrEmpty() && target.password.isNullOrEmpty()) { "URL credentials are forbidden." }
        require(url.length <= 8192 && headers.size <= 64 && headers.entries.sumOf { it.key.length + it.value.length } <= 16_384) {
            "HTTP request metadata is too large."
        }
        require((body?.toByteArray()?.size ?: 0) <= MAX_BYTES) { "HTTP request body is too large." }
        return client.prepareRequest(target) {
            this.method = HttpMethod.parse(method)
            headers.forEach { (key, value) -> this.headers.append(key, value) }
            body?.let { setBody(it) }
        }.execute { response ->
            val buffer = ByteArray(8192)
            val output = ByteArrayOutputStream()
            val channel = response.bodyAsChannel()
            while (true) {
                val read = channel.readAvailable(buffer)
                if (read < 0) break
                require(output.size() + read <= MAX_BYTES) { "HTTP response body is too large." }
                output.write(buffer, 0, read)
            }
            PluginHttpResponse(response.status.value, output.toString(Charsets.UTF_8))
        }
    }

    override fun close() = client.close()

    private companion object {
        const val MAX_BYTES = 1_048_576
    }
}
