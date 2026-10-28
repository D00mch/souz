package ru.souz.backend.plugins

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.Dns
import ru.souz.plugins.HttpRequest
import ru.souz.plugins.PLUGIN_MAX_BYTES

class PluginHttpClientTest {
    @Test fun `addresses and literal URLs reject private reserved and mapped ranges`() = runTest {
        PluginHttpClient().use { http ->
            for (host in listOf("127.1", "10.0.0.1", "100.64.0.1", "169.254.169.254", "192.0.2.1", "198.18.0.1", "224.0.0.1", "::1", "fc00::1", "2001:db8::1", "::ffff:127.0.0.1")) {
                assertFalse(publicAddress(InetAddress.getByName(host)), host)
                val literal = if (':' in host) "[$host]" else host
                assertFailsWith<IllegalArgumentException>(host) { http.execute(HttpRequest("http://$literal/")) }
            }
            for (host in listOf("8.8.8.8", "2606:4700:4700::1111")) assertTrue(publicAddress(InetAddress.getByName(host)))
            assertFailsWith<IllegalArgumentException> { http.execute(HttpRequest("https://user:secret@example.com")) }
        }
        val client = pluginHttpClient(Dns { listOf(InetAddress.getByName("127.0.0.1")) })
        try { assertFailsWith<java.net.UnknownHostException> { client.dns.lookup("example.test") } }
        finally { client.dispatcher.executorService.shutdown() }
    }

    @Test fun `real transport preserves retryable statuses without retries cookies or redirects and bounds bodies`() = runTest {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            assertNull(exchange.requestHeaders.getFirst("Cookie"))
            val bytes = if (exchange.requestURI.path == "/large") ByteArray(PLUGIN_MAX_BYTES + 1) else "busy".toByteArray()
            val status = if (exchange.requestURI.path == "/redirect") 302 else 503
            exchange.responseHeaders.add("Retry-After", "0")
            exchange.responseHeaders.add("Set-Cookie", "private=value")
            exchange.responseHeaders.add("Location", "/followed")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        // Only this test routes a public-looking hostname to its loopback fixture. Production
        // DNS rejects that mapping; the real HTTP transport policy remains installed.
        val client = pluginHttpClient().newBuilder().dns { listOf(InetAddress.getLoopbackAddress()) }.build()
        try {
            PluginHttpClient(client).use { http ->
                val url = "http://fixture.test:${server.address.port}"
                repeat(2) { assertEquals(503, http.execute(HttpRequest(url)).status) }
                assertEquals(302, http.execute(HttpRequest("$url/redirect")).status)
                assertEquals(3, calls.get())
                assertFailsWith<IllegalArgumentException> { http.execute(HttpRequest("$url/large")) }
                assertFailsWith<IllegalArgumentException> { http.execute(HttpRequest(url, "POST", body = ByteArray(PLUGIN_MAX_BYTES + 1))) }
                assertEquals(4, calls.get())
            }
        } finally { server.stop(0) }
    }

    @Test fun `cancellation interrupts a stalled response and deadline is enforced`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { entered.complete(Unit) }
        server.start()
        val client = pluginHttpClient().newBuilder().dns { listOf(InetAddress.getLoopbackAddress()) }
            .callTimeout(200, TimeUnit.MILLISECONDS).build()
        try {
            PluginHttpClient(client).use { http ->
                val url = "http://fixture.test:${server.address.port}"
                val call = async { http.execute(HttpRequest(url)) }
                entered.await()
                call.cancel()
                assertFailsWith<CancellationException> { call.await() }
                assertFailsWith<java.io.IOException> { http.execute(HttpRequest(url)) }
            }
        } finally { server.stop(0) }
    }
}
