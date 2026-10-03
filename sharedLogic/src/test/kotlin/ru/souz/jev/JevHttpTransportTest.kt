package ru.souz.jev

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttpConfig
import io.ktor.client.engine.cio.CIOEngineConfig
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.takeFrom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Protocol
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import ru.souz.llms.http.JevHttpTransport
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.http.createJevProviderHttpClient
import ru.souz.llms.http.createStandardProviderHttpClient
import ru.souz.llms.restJsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class JevHttpTransportTest {
    private val state = restJsonMapper.readTree("\"private message\"")
    private val questions = mapOf("calendar" to "Does this require calendar access?")
    private val responseBody = """{"answers":{"calendar":{"type":"noul","noul":0.9}}}"""

    @Test
    fun `CIO uses dedicated POST connections even with longer keep alive`() = runBlocking {
        for (transport in listOf(null, JevHttpTransport.CIO)) {
            withTlsClient(transport, listOf(Protocol.HTTP_1_1)) { server, http ->
                repeat(2) { server.enqueue(MockResponse(body = responseBody)) }
                val jev = JevClient(http, "test-token")
                repeat(2) { assertEquals(0.9, jev.evaluate(state, questions).getValue("calendar")) }
                val requests = withContext(Dispatchers.IO) {
                    listOfNotNull(server.takeRequest(5, TimeUnit.SECONDS), server.takeRequest(5, TimeUnit.SECONDS))
                }
                assertEquals(2, requests.size)
                assertNotEquals(requests[0].connectionIndex, requests[1].connectionIndex)
            }
        }
    }

    @Test
    fun `TLS negotiates HTTP2 and reuses its connection through full body receipt`() = runBlocking {
        verifyProtocol(JevHttpTransport.OKHTTP_HTTP2, listOf(Protocol.HTTP_2, Protocol.HTTP_1_1), "HTTP/2.0")
    }

    @Test
    fun `HTTP2 profile falls back to HTTP1 on a TLS server without HTTP2`() = runBlocking {
        verifyProtocol(JevHttpTransport.OKHTTP_HTTP2, listOf(Protocol.HTTP_1_1), "HTTP/1.1")
    }

    @Test
    fun `HTTP1 control stays HTTP1 against an HTTP2 capable TLS server`() = runBlocking {
        verifyProtocol(JevHttpTransport.OKHTTP_HTTP1, listOf(Protocol.HTTP_2, Protocol.HTTP_1_1), "HTTP/1.1")
    }

    private suspend fun verifyProtocol(transport: JevHttpTransport, protocols: List<Protocol>, expected: String) {
        withTlsClient(transport, protocols) { server, http ->
            server.enqueue(MockResponse.Builder().body(responseBody).bodyDelay(150, TimeUnit.MILLISECONDS).build())
            server.enqueue(MockResponse(body = responseBody))
            val diagnostics = mutableListOf<JevHttpDiagnostic>()
            val jev = JevClient(http, "test-token", onHttpRequestCompleted = diagnostics::add)
            repeat(2) { assertEquals(0.9, jev.evaluate(state, questions).getValue("calendar")) }
            assertEquals(listOf(expected, expected), diagnostics.map { it.protocol })
            assertTrue(diagnostics.first().durationMs >= 150, "Duration must include delayed response body")
            val requests = withContext(Dispatchers.IO) {
                listOfNotNull(server.takeRequest(5, TimeUnit.SECONDS), server.takeRequest(5, TimeUnit.SECONDS))
            }
            assertEquals(2, requests.size)
            assertEquals(requests[0].connectionIndex, requests[1].connectionIndex)
            requests.forEach {
                assertEquals("POST", it.method)
                assertEquals("Bearer test-token", it.headers["Authorization"])
                assertEquals("application/json", it.headers["Content-Type"])
                val payload = restJsonMapper.readTree(it.body!!.utf8())
                assertEquals(state, payload["state"])
                assertEquals("noul", payload["questions"]["calendar"]["type"].asText())
            }
            // Jev adapters leave the client open; the existing host resource owns shutdown.
            assertTrue(http.coroutineContext.job.isActive)
            val owner = ProviderHttpClients(http, http, http)
            owner.close()
            owner.close()
            withTimeout(5_000) {
                http.engine.coroutineContext.job.join()
            }
        }
    }

    @Test
    fun `cancellation during body receipt propagates and leaves shared client usable`() = runBlocking {
        for (transport in listOf(JevHttpTransport.OKHTTP_HTTP1, JevHttpTransport.OKHTTP_HTTP2)) {
            withTlsClient(transport) { server, http ->
                server.enqueue(MockResponse.Builder().body(responseBody).bodyDelay(5, TimeUnit.SECONDS).build())
                val diagnostics = mutableListOf<JevHttpDiagnostic>()
                val jev = JevClient(http, "test-token", onHttpRequestCompleted = diagnostics::add)
                val pending = async { jev.evaluate(state, questions) }
                assertTrue(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) } != null)
                pending.cancelAndJoin()
                assertTrue(pending.isCancelled)
                assertEquals("cancelled", diagnostics.single().outcome)
                server.enqueue(MockResponse(body = responseBody))
                assertEquals(0.9, jev.evaluate(state, questions).getValue("calendar"))
            }
        }
    }

    @Test
    fun `two second Jev timeout covers a slow response body on both protocols`() = runBlocking {
        for (transport in listOf(JevHttpTransport.OKHTTP_HTTP1, JevHttpTransport.OKHTTP_HTTP2)) {
            withTlsClient(transport) { server, http ->
                server.enqueue(MockResponse.Builder().body(responseBody).bodyDelay(5, TimeUnit.SECONDS).build())
                val diagnostics = mutableListOf<JevHttpDiagnostic>()
                val jev = JevClient(http, "test-token", onHttpRequestCompleted = diagnostics::add)
                withTimeout(4_000) {
                    assertFailsWith<HttpRequestTimeoutException> { jev.evaluate(state, questions) }
                }
                assertEquals("timeout", diagnostics.single().outcome)
            }
        }
    }

    private suspend fun withTlsClient(
        transport: JevHttpTransport?,
        protocols: List<Protocol> = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1),
        block: suspend (MockWebServer, HttpClient) -> Unit,
    ) {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.protocols = protocols
            server.useHttps(serverTls.sslSocketFactory())
            server.start()
            (transport?.let { createJevProviderHttpClient(it) } ?: createStandardProviderHttpClient()).use { http ->
                when (val config = http.engine.config) {
                    is OkHttpConfig -> config.config {
                        sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                    }
                    is CIOEngineConfig -> config.https { trustManager = clientTls.trustManager }
                }
                http.requestPipeline.intercept(HttpRequestPipeline.Before) {
                    context.url.takeFrom(server.url("/v1/systemone").toString())
                }
                block(server, http)
            }
        }
    }

}
