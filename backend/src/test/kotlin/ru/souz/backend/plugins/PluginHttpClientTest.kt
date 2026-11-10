package ru.souz.backend.plugins

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ru.souz.plugins.api.PluginHttpRequest

class PluginHttpClientTest {
    @Test
    fun `dedicated transport does not follow redirects retain cookies inherit credentials or retry`() = runBlocking {
        val requests = ConcurrentLinkedQueue<Pair<String, Map<String, List<String>>>>()
        pluginHttpServer { server, origin ->
            server.createContext("/") { exchange ->
                requests += exchange.requestURI.path to exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.toList() }
                val body = exchange.requestBody.readBytes()
                val status = when (exchange.requestURI.path) {
                    "/redirect" -> { exchange.responseHeaders.add("Location", "$origin/followed"); 302 }
                    "/cookie" -> { exchange.responseHeaders.add("Set-Cookie", "secret=retained; Path=/"); 200 }
                    "/unavailable" -> 503
                    else -> 200
                }
                val response = body.takeIf { it.isNotEmpty() } ?: "response".toByteArray()
                exchange.sendResponseHeaders(status, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            server.start()
            PluginHttpClient().use { http ->
                assertEquals(302, http.execute(PluginHttpRequest("$origin/redirect")).status)
                http.execute(PluginHttpRequest("$origin/cookie"))
                http.execute(PluginHttpRequest("$origin/after-cookie"))
                assertEquals(503, http.execute(PluginHttpRequest("$origin/unavailable")).status)
                val body = "Привет".toByteArray()
                assertContentEquals(body, http.execute(PluginHttpRequest("$origin/echo", "POST",
                    headers = mapOf("X-Plugin-Test" to listOf("one", "two")), body = body)).body)
            }
            assertEquals(listOf("/redirect", "/cookie", "/after-cookie", "/unavailable", "/echo"), requests.map { it.first })
            requests.forEach { (_, headers) ->
                assertNull(headers["authorization"])
                assertNull(headers["proxy-authorization"])
                assertNull(headers["cookie"])
            }
            assertEquals(listOf("one", "two"), requests.last().second["x-plugin-test"]?.flatMap { it.split(',').map(String::trim) })
        }
    }

    @Test
    fun `request and chunked response bounds reject before excess bytes reach plugin code`() = runBlocking {
        val received = AtomicInteger()
        pluginHttpServer { server, origin ->
            server.createContext("/") { exchange ->
                received.incrementAndGet()
                val size = if (exchange.requestURI.path == "/oversized") LIMIT + 1 else LIMIT
                val request = exchange.requestBody.readBytes()
                if (exchange.requestMethod == "POST") assertEquals(LIMIT, request.size)
                exchange.sendResponseHeaders(200, 0) // Deliberately omit Content-Length.
                try { exchange.responseBody.use { it.write(ByteArray(size) { 7 }) } }
                catch (_: java.io.IOException) { exchange.close() }
            }
            server.start()
            PluginHttpClient().use { http ->
                assertEquals(LIMIT, http.execute(PluginHttpRequest("$origin/exact", "POST", body = ByteArray(LIMIT))).body.size)
                assertFailsWith<IllegalArgumentException> { http.execute(PluginHttpRequest("$origin/oversized")) }
                val before = received.get()
                for (request in listOf(
                    PluginHttpRequest("$origin/excess", "POST", body = ByteArray(LIMIT + 1)),
                    PluginHttpRequest("$origin/get-body", body = byteArrayOf(1)),
                    PluginHttpRequest("$origin/header", headers = mapOf("Host" to listOf("spoofed"))),
                    PluginHttpRequest("$origin/header", headers = mapOf("Proxy-Authorization" to listOf("secret"))),
                    PluginHttpRequest("ftp://127.0.0.1/file"),
                    PluginHttpRequest("http://host:invalid-port/"),
                    PluginHttpRequest(origin.replace("://", "://user:secret@")),
                )) {
                    assertFailsWith<IllegalArgumentException> { http.execute(request) }
                }
                assertEquals(before, received.get())
            }
        }
    }

    @Test
    fun `cancellation interrupts a streaming response without waiting for its body`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        pluginHttpServer { server, origin ->
            server.createContext("/") { exchange ->
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write(byteArrayOf(1))
                exchange.responseBody.flush()
                entered.complete(Unit)
                try { release.await() }
                finally { exchange.close() }
            }
            server.start()
            PluginHttpClient().use { http ->
                val call = async { http.execute(PluginHttpRequest(origin)) }
                try {
                    withTimeout(5_000) { entered.await(); call.cancelAndJoin() }
                    assertFalse(call.isActive)
                } finally { release.countDown() }
            }
        }
    }

    private suspend fun pluginHttpServer(block: suspend (HttpServer, String) -> Unit) {
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { this.executor = executor }
        try { block(server, "http://127.0.0.1:${server.address.port}") }
        finally { server.stop(0); executor.shutdownNow() }
    }

    private companion object { const val LIMIT = 1_048_576 }
}
