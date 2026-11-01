package ru.souz.backend.plugins

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta
import ru.souz.plugins.api.PluginInvocation
import ru.souz.plugins.api.PluginLog
import ru.souz.plugins.api.PluginState

class PluginHttpTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `Java awaits and cancels a real bounded host HTTP call without leaking backend headers`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newFixedThreadPool(2)
        server.executor = executor
        val entered = CompletableFuture<Unit>()
        val release = CompletableFuture<Unit>()
        server.createContext("/") { exchange ->
            try {
                assertNull(exchange.requestHeaders.getFirst("Authorization"))
                assertNull(exchange.requestHeaders.getFirst("Cookie"))
                if (exchange.requestURI.path == "/slow") {
                    entered.complete(Unit)
                    release.get(5, TimeUnit.SECONDS)
                }
                val body = "java-completed".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            PluginHttpClient().use { http ->
                PluginLoader(directory).open(Files.readAllBytes(Path.of(System.getProperty("plugin.fixture.java")))).use { opened ->
                    val context = PluginInvocation(ToolInvocationMeta("alice"), object : PluginState {
                        override suspend fun get(key: String) = error("unused")
                        override suspend fun put(key: String, json: String, dataSchemaVersion: Int, expectedRevision: Long?) = error("unused")
                    }, http, PluginLog {})
                    suspend fun call(path: String) = withContext(PluginClassLoaderContext(opened.loader)) {
                        opened.plugin.invoke(LLMResponse.FunctionCall("plugin.java_reference.fetch",
                            mapOf("url" to "http://127.0.0.1:${server.address.port}$path")), context)
                    }
                    assertEquals("java-completed", call("/").content)
                    val invocation = async { call("/slow") }
                    withContext(Dispatchers.IO) { entered.get(5, TimeUnit.SECONDS) }
                    withTimeout(2_000) { invocation.cancelAndJoin() }
                    assertTrue(invocation.isCancelled)
                    release.complete(Unit)
                }
            }
        } finally {
            release.complete(Unit)
            server.stop(0)
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
