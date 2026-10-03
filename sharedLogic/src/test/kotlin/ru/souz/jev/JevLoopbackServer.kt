package ru.souz.jev

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.takeFrom
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/** Loopback fixture with no request logging and a delayed final body fragment. */
internal class JevLoopbackServer : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val connections = ConcurrentLinkedQueue<InetSocketAddress>()

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            exchange.use {
                connections.add(it.remoteAddress)
                it.requestBody.use { body -> body.readAllBytes() }
                val body = """{"answers":{"calendar":{"type":"noul","noul":0.9}}}""".toByteArray()
                it.responseHeaders.set("Content-Type", "application/json")
                it.sendResponseHeaders(200, body.size.toLong())
                it.responseBody.write(body, 0, body.size - 1)
                it.responseBody.flush()
                Thread.sleep(30)
                it.responseBody.write(body, body.size - 1, 1)
            }
        }
        server.start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}/v1/systemone"

    fun redirect(client: HttpClient) {
        client.plugin(HttpSend).intercept { request ->
            request.url.takeFrom(url)
            execute(request)
        }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}
