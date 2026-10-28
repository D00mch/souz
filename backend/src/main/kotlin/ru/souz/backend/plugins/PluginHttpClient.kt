package ru.souz.backend.plugins

import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import ru.souz.plugins.HttpRequest
import ru.souz.plugins.HttpResponse
import ru.souz.plugins.PLUGIN_MAX_BYTES
import ru.souz.plugins.PluginHttp

internal class PluginHttpClient(private val client: OkHttpClient = pluginHttpClient()) : PluginHttp, AutoCloseable {
    override suspend fun execute(request: HttpRequest): HttpResponse {
        val url = request.url.toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty()) { "URL credentials are unsupported" }
        val literal = url.host
        if (':' in literal || literal.all { it.isDigit() || it == '.' }) {
            require(publicAddress(InetAddress.getByName(literal))) { "Non-public HTTP address" }
        }
        require(request.method in METHODS)
        require(request.body.size <= PLUGIN_MAX_BYTES)
        val body = if (request.method == "GET" || request.method == "HEAD") {
            require(request.body.isEmpty()); null
        } else request.body.toRequestBody()
        val builder = Request.Builder().url(url).method(request.method, body)
        request.headers.forEach { (name, values) ->
            require(name.lowercase() !in RESERVED_HEADERS)
            values.forEach { builder.addHeader(name, it) }
        }
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(builder.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            val bytes = it.body.byteStream().readNBytes(PLUGIN_MAX_BYTES + 1)
                            require(bytes.size <= PLUGIN_MAX_BYTES) { "HTTP response exceeds 1 MiB" }
                            HttpResponse(it.code, it.headers.toMultimap(), bytes)
                        }
                        continuation.resumeWith(Result.success(result))
                    } catch (e: Exception) { continuation.resumeWithException(e) }
                }
            })
        }
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}

internal fun pluginHttpClient(dns: Dns = Dns.SYSTEM): OkHttpClient = OkHttpClient.Builder()
    .dns { host -> dns.lookup(host).also { addresses ->
        if (addresses.isEmpty() || addresses.any { !publicAddress(it) }) throw java.net.UnknownHostException("Non-public HTTP address")
    } }
    .proxy(Proxy.NO_PROXY)
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .callTimeout(30, TimeUnit.SECONDS)
    // OkHttp retries 421/503 despite retryOnConnectionFailure=false. Keep them out of its
    // follow-up interceptor, then restore the original response before exposing it to plugins.
    .addNetworkInterceptor { chain ->
        val response = chain.proceed(chain.request())
        if (response.code == 421 || response.code == 503) response.newBuilder().code(response.code + 1000).build() else response
    }
    .addInterceptor { chain ->
        val response = chain.proceed(chain.request())
        if (response.code == 1421 || response.code == 1503) response.newBuilder().code(response.code - 1000).build() else response
    }
    .build()

internal fun publicAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress) return false
    val bytes = address.address.map { it.toInt() and 255 }
    if (bytes.size == 16) return bytes[0] in 0x20..0x3f &&
        !(bytes[0] == 0x20 && bytes[1] == 0x01 && (bytes[2] < 2 || bytes[2] == 0x0d && bytes[3] == 0xb8)) &&
        !(bytes[0] == 0x20 && bytes[1] == 0x02) && !(bytes[0] == 0x3f && bytes[1] == 0xff)
    if (bytes.size != 4) return false
    val (a, b, c) = bytes
    return a != 0 && a < 224 && !(a == 100 && b in 64..127) &&
        !(a == 192 && (b == 0 && (c == 0 || c == 2) || b == 88 && c == 99)) &&
        !(a == 198 && (b in 18..19 || b == 51 && c == 100)) && !(a == 203 && b == 0 && c == 113)
}

private val METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
private val RESERVED_HEADERS = setOf("host", "connection", "content-length", "transfer-encoding", "proxy-authorization")
