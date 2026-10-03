package ru.souz.jev

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import kotlinx.coroutines.runBlocking
import ru.souz.llms.http.createJevProviderHttpClient
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.llms.restJsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals

class JevHttpTransportTest {
    @Test
    fun `CIO POST creates new connections with both idle timeout settings`() = runBlocking {
        JevLoopbackServer().use { server ->
            for (keepAliveTime in listOf(5_000L, 60_000L)) {
                createJevProviderHttpClient(keepAliveTime).use { http ->
                    server.redirect(http)
                    val client = JevClient(http, "loopback-token", "jev-latest")
                    repeat(3) {
                        assertEquals(
                            0.9,
                            client.evaluate(
                                restJsonMapper.readTree("\"Check my calendar\""),
                                mapOf("calendar" to "Does this request require calendar access?"),
                            ).getValue("calendar"),
                        )
                    }
                }
            }
            assertEquals(6, server.connections.size)
            assertEquals(6, server.connections.toSet().size)
        }
    }

    @Test
    fun `loopback server permits reuse but CIO dedicates POST even with pipelining enabled`() = runBlocking {
        JevLoopbackServer().use { server ->
            java.net.http.HttpClient.newHttpClient().use { control ->
                val request = HttpRequest.newBuilder(URI(server.url)).GET().build()
                repeat(2) { control.send(request, BodyHandlers.ofString()) }
                assertEquals(1, server.connections.toSet().size)
            }
            HttpClient(CIO) {
                providerHttpClientDefaults()
                engine { pipelining = true }
            }.use { http ->
                server.redirect(http)
                val client = JevClient(http, "loopback-token")
                repeat(2) {
                    client.evaluate(
                        restJsonMapper.readTree("\"Check my calendar\""),
                        mapOf("calendar" to "Does this request require calendar access?"),
                    )
                }
                assertEquals(3, server.connections.toSet().size)
            }
        }
    }
}
