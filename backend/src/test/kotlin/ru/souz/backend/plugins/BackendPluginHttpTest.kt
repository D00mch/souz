package ru.souz.backend.plugins

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.net.InetAddress
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.souz.plugins.PLUGIN_MAX_BYTES
import ru.souz.plugins.PluginException
import ru.souz.plugins.PluginHttpRequest

class BackendPluginHttpTest {
    @Test fun `reserved literals and DNS addresses are rejected including mapped IPv6`() {
        listOf("127.0.0.1", "127.1", "10.1.2.3", "169.254.169.254", "100.64.0.1", "192.0.2.1",
            "192.168.0.1", "198.18.0.1", "224.0.0.1", "0.0.0.0", "::1", "::", "fc00::1",
            "fe80::1", "2001:db8::1", "2002:7f00:1::1", "::ffff:127.0.0.1").forEach { address ->
            assertFalse(isPublicPluginAddress(InetAddress.getByName(address)), address)
            val host = if (':' in address) "[$address]" else address
            assertFailsWith<IllegalArgumentException>(address) { validatePluginUrl("https://$host/") }
        }
        listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111").forEach {
            assertTrue(isPublicPluginAddress(InetAddress.getByName(it)))
        }
        listOf("file:///tmp/x", "http://user:password@example.com", "https://example.com:0/").forEach {
            assertFailsWith<IllegalArgumentException> { validatePluginUrl(it) }
        }
        var lookups = 0
        val dns = pluginDns(Dns {
            listOf(InetAddress.getByName(if (lookups++ == 0) "8.8.8.8" else "127.0.0.1"))
        })
        assertEquals("8.8.8.8", dns.lookup("changing.test").single().hostAddress)
        assertFailsWith<UnknownHostException> { dns.lookup("changing.test") }
    }

    @Test fun `gateway bounds bodies and returns redirects without following them`() = runTest {
        var calls = 0
        BackendPluginHttp(pluginHttpClient(MockEngine { request ->
            calls++
            assertEquals(listOf("a", "b"), request.headers.getAll("X-Repeat"))
            respond("redirect", HttpStatusCode.Found, headersOf("Location", "http://127.0.0.1/private"))
        })).use { http ->
            val response = http.execute(PluginHttpRequest("https://example.com", headers = mapOf("X-Repeat" to listOf("a", "b"))))
            assertEquals(302, response.status)
            assertEquals(1, calls)
            assertFailsWith<IllegalArgumentException> { http.execute(PluginHttpRequest("https://example.com", body = ByteArray(PLUGIN_MAX_BYTES + 1))) }
            assertEquals("plugin_http_destination_forbidden", assertFailsWith<PluginException> { http.execute(PluginHttpRequest("http://127.0.0.1")) }.code)
            assertEquals(1, calls)
        }
        BackendPluginHttp(HttpClient(MockEngine { respond("x".repeat(PLUGIN_MAX_BYTES + 1)) })).use { oversized ->
            assertFailsWith<PluginException> { oversized.execute(PluginHttpRequest("https://example.com")) }
        }
    }

    @Test fun `caller cancellation and gateway deadline cancel suspended network work`() = runTest {
        BackendPluginHttp(pluginHttpClient(MockEngine { awaitCancellation() })).use { http ->
            assertFailsWith<CancellationException> {
                withTimeout(100) { http.execute(PluginHttpRequest("https://example.com")) }
            }
            val started = TimeSource.Monotonic.markNow()
            assertEquals("plugin_http_timeout", assertFailsWith<PluginException> { http.execute(PluginHttpRequest("https://example.com")) }.code)
            assertTrue(started.elapsedNow() >= 29.seconds)
        }
    }

    @Test fun `engine returns retryable statuses and cookies without repeating or sharing them`() {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            assertEquals(null, exchange.requestHeaders.getFirst("Cookie"))
            exchange.responseHeaders.add("Retry-After", "0")
            exchange.responseHeaders.add("Set-Cookie", "secret=first")
            exchange.responseHeaders.add("Set-Cookie", "other=second")
            exchange.sendResponseHeaders(503, 4)
            exchange.responseBody.use { it.write("busy".toByteArray()) }
        }
        server.start()
        // A loopback server exercises the actual transport policy; URL ownership checks
        // are covered separately and deliberately reject this address in the gateway.
        val client = OkHttpClient.Builder().apply { pluginHttpPolicy() }.build()
        try {
            repeat(2) {
                client.newCall(Request.Builder().url("http://127.0.0.1:${server.address.port}/").build()).execute().use { response ->
                    assertEquals(503, response.code)
                    assertEquals("busy", response.body.string())
                    assertEquals("0", response.header("Retry-After"))
                    assertEquals(2, response.headers("Set-Cookie").size)
                }
            }
            assertEquals(2, calls.get())
        } finally {
            server.stop(0)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
