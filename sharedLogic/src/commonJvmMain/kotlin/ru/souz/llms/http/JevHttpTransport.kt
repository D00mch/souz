package ru.souz.llms.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Protocol

/** Opt-in profiles; HTTP/2 uses TLS ALPN and permits HTTP/1.1 fallback. */
enum class JevHttpTransport { CIO, OKHTTP_HTTP1, OKHTTP_HTTP2 }

const val JEV_IDLE_RETENTION_MILLIS = 60_000L

/** The caller owns this client. Retention is a ceiling; the server may close earlier. */
fun createJevProviderHttpClient(
    transport: JevHttpTransport,
    idleRetentionMillis: Long = JEV_IDLE_RETENTION_MILLIS,
): HttpClient {
    require(idleRetentionMillis > 0) { "Jev idle retention must be positive" }
    return when (transport) {
        JevHttpTransport.CIO -> HttpClient(CIO) {
            providerHttpClientDefaults()
            engine { endpoint.keepAliveTime = idleRetentionMillis }
        }
        JevHttpTransport.OKHTTP_HTTP1, JevHttpTransport.OKHTTP_HTTP2 -> HttpClient(OkHttp) {
            providerHttpClientDefaults()
            engine {
                // A dedicated pool avoids sharing OkHttp's process-wide prototype pool.
                val pool = ConnectionPool(5, idleRetentionMillis, TimeUnit.MILLISECONDS)
                config {
                    connectionPool(pool)
                    protocols(
                        if (transport == JevHttpTransport.OKHTTP_HTTP2) {
                            listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
                        } else {
                            listOf(Protocol.HTTP_1_1)
                        }
                    )
                    retryOnConnectionFailure(false)
                }
            }
        }
    }
}
