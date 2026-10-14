package ru.souz.backend.plugins

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.OkHttpClient
import ru.souz.plugins.PLUGIN_MAX_BYTES
import ru.souz.plugins.PluginException
import ru.souz.plugins.PluginHttp
import ru.souz.plugins.PluginHttpRequest
import ru.souz.plugins.PluginHttpResponse

/** DNS validation happens in the engine's resolver, against the addresses actually used to connect. */
internal class BackendPluginHttp(
    private val client: HttpClient = pluginHttpClient(),
) : PluginHttp, AutoCloseable {
    override suspend fun execute(request: PluginHttpRequest): PluginHttpResponse {
        validatePluginUrl(request.url)
        require(request.body.size <= PLUGIN_MAX_BYTES) { "HTTP request body exceeds 1 MiB" }
        require(request.method in setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")) { "Unsupported HTTP method" }
        require(request.headers.keys.none { it.lowercase() in setOf("host", "connection", "content-length", "transfer-encoding", "proxy-authorization") }) {
            "Reserved HTTP header"
        }
        return client.prepareRequest(request.url) {
            method = HttpMethod.parse(request.method)
            request.headers.forEach { (name, values) -> values.forEach { headers.append(name, it) } }
            setBody(request.body)
        }.execute { response ->
            val bytes = response.bodyAsChannel().readRemaining(PLUGIN_MAX_BYTES + 1L).use { it.readByteArray() }
            if (bytes.size > PLUGIN_MAX_BYTES) throw PluginException("plugin_http_body_too_large", "HTTP response exceeds 1 MiB")
            PluginHttpResponse(response.status.value, response.headers.entries().associate { it.key to it.value.toList() }, bytes)
        }
    }

    override fun close() = client.close()
}

internal fun pluginHttpClient(engine: HttpClientEngine = OkHttp.create { config { pluginHttpPolicy() } }): HttpClient =
    HttpClient(engine) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    }

internal fun OkHttpClient.Builder.pluginHttpPolicy() {
    dns(pluginDns())
    proxy(Proxy.NO_PROXY)
    followRedirects(false)
    followSslRedirects(false)
    retryOnConnectionFailure(false)
    // OkHttp 5 retries 503/421 independently of retryOnConnectionFailure. Hide those
    // codes only across its follow-up interceptor, restoring the exact public response.
    // Wire status codes are three digits; these internal values cannot arrive from a server.
    addNetworkInterceptor { chain ->
        val response = chain.proceed(chain.request())
        if (response.code == 503 || response.code == 421) response.newBuilder().code(response.code + 1000).build() else response
    }
    addInterceptor { chain ->
        val response = chain.proceed(chain.request())
        if (response.code == 1503 || response.code == 1421) response.newBuilder().code(response.code - 1000).build() else response
    }
}

internal fun validatePluginUrl(raw: String) {
    val uri = URI(raw)
    require(uri.scheme?.lowercase() in setOf("http", "https") && uri.host != null && uri.rawUserInfo == null) { "Expected an HTTP(S) URL without credentials" }
    require(uri.port in -1..65535 && uri.port != 0) { "Invalid HTTP port" }
    val host = uri.host.removeSurrounding("[", "]")
    // OkHttp bypasses custom DNS for numeric IP literals.
    if (':' in host || host.all { it.isDigit() || it == '.' }) {
        require(isPublicPluginAddress(InetAddress.getByName(host))) { "Non-public HTTP destination" }
    }
}

internal fun isPublicPluginAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress) return false
    val bytes = address.address.map { it.toInt() and 255 }
    if (bytes.size == 4) {
        val (a, b, c) = bytes
        return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
            (a == 100 && b in 64..127) || (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) || (a == 192 && (b == 168 || (b == 0 && c in setOf(0, 2)) || (b == 88 && c == 99))) ||
            (a == 198 && (b in 18..19 || (b == 51 && c == 100))) ||
            (a == 203 && b == 0 && c == 113))
    }
    // Public IPv6 unicast only; exclude special-use ranges within 2000::/3.
    return bytes.size == 16 && bytes[0] in 0x20..0x3f &&
        !(bytes[0] == 0x20 && bytes[1] == 0x01 && (bytes[2] < 2 || (bytes[2] == 0x0d && bytes[3] == 0xb8))) &&
        !(bytes[0] == 0x20 && bytes[1] == 0x02) && !(bytes[0] == 0x3f && bytes[1] == 0xff)
}

internal fun pluginDns(delegate: Dns = Dns.SYSTEM): Dns = Dns { host ->
    delegate.lookup(host).also { addresses ->
        if (addresses.isEmpty() || addresses.any { !isPublicPluginAddress(it) }) throw UnknownHostException("Non-public HTTP destination")
    }
}
