package ru.souz.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.Protocol
import ru.souz.llms.http.providerHttpClientDefaults

/** Host-owned MCP transport. No provider credentials, proxies, redirects or connection-failure retries. */
fun createMcpHttpClient(policy: McpNetworkPolicy = McpNetworkPolicy.fromEnvironment()): HttpClient = HttpClient(OkHttp) {
    providerHttpClientDefaults()
    followRedirects = false
    engine {
        config {
            dns(Dns(policy::lookup))
            proxy(Proxy.NO_PROXY)
            followRedirects(false)
            followSslRedirects(false)
            retryOnConnectionFailure(false)
            protocols(listOf(Protocol.HTTP_1_1))
            connectionPool(ConnectionPool(5, 60, TimeUnit.SECONDS))
            addInterceptor { chain ->
                try {
                    policy.validateUrl(chain.request().url.toString())
                } catch (_: IllegalArgumentException) {
                    throw IOException("MCP destination is not allowed.")
                }
                chain.proceed(chain.request())
            }
            // Network interceptors see wire bytes before OkHttp's transparent gzip decoder.
            addNetworkInterceptor(Interceptor { chain ->
                val response = chain.proceed(chain.request())
                val body = response.body
                val type = body.contentType()
                val charset = type?.parameter("charset")
                val invalidEncoding = charset != null &&
                    !charset.equals("utf-8", ignoreCase = true) && !charset.equals("utf8", ignoreCase = true)
                if (body.contentLength() > McpLimits.RESPONSE_BYTES || invalidEncoding ||
                    response.headers.values("Content-Type").size > 1 ||
                    (response.header("Content-Type") != null && type == null) ||
                    response.headers.values("Content-Encoding").any { !it.equals("identity", ignoreCase = true) }
                ) {
                    response.close()
                    throw IOException("MCP response violates its byte or encoding limits.")
                }
                response.newBuilder().apply {
                    // Pin Ktor's decoder to the UTF-8 bytes inspected by the nesting guard.
                    if (type != null) header("Content-Type", "${type.type}/${type.subtype}; charset=utf-8")
                }.body(McpResponseBody(body)).build()
            })
            // Keep wire and decoded byte budgets identical.
            addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("Accept-Encoding", "identity").build())
            }
        }
    }
}
