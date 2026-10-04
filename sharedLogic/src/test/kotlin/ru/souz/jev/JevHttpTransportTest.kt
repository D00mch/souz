package ru.souz.jev

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttpConfig
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.client.statement.HttpReceivePipeline
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.takeFrom
import java.util.concurrent.TimeUnit
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.Protocol
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.slf4j.LoggerFactory
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.restJsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class JevHttpTransportTest {
    private val state = restJsonMapper.readTree("\"private message\"")
    private val questions = mapOf("calendar" to "Does this require calendar access?")
    private val body = """{"answers":{"calendar":{"type":"noul","noul":0.9}}}"""

    @Test
    fun `different adapters reuse HTTP1 connection and timing includes body receipt`() = runBlocking {
        withClient { server, http, logs ->
            server.enqueue(MockResponse.Builder().body(body).bodyDelay(150, TimeUnit.MILLISECONDS).build())
            server.enqueue(MockResponse(body = body))
            val first = JevClient(http, "token-a", "model-a")
            val second = JevClient(http, "token-b", "model-b")
            assertEquals(0.9, first.evaluate(state, questions).getValue("calendar"))
            delay(150)
            assertEquals(0.9, second.evaluate(state, questions).getValue("calendar"))

            val requests = withContext(Dispatchers.IO) {
                listOf(assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)), assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)))
            }
            assertEquals(requests[0].connectionIndex, requests[1].connectionIndex)
            assertEquals(listOf("Bearer token-a", "Bearer token-b"), requests.map { it.headers["Authorization"] })
            assertEquals(listOf("model-a", "model-b"), requests.map { restJsonMapper.readTree(it.body!!.utf8())["model"].asText() })
            requests.forEach {
                assertEquals("POST", it.method)
                assertEquals("application/json", it.headers["Content-Type"])
                val payload = restJsonMapper.readTree(it.body!!.utf8())
                assertEquals(state, payload["state"])
                assertEquals("noul", payload["questions"]["calendar"]["type"].asText())
                assertEquals(questions.getValue("calendar"), payload["questions"]["calendar"]["instructions"].asText())
            }
            val duration = Regex("durationMs=(\\d+)").find(logs.first().formattedMessage)!!.groupValues[1].toLong()
            assertTrue(duration >= 150, "HTTP timing must include the delayed body")
            assertTrue(logs.all { it.formattedMessage.endsWith("status=200 outcome=success") })
            assertTrue(http.coroutineContext.job.isActive)
        }
    }

    @Test
    fun `cancellation during body receipt leaves shared client usable`() = runBlocking {
        withClient { server, http, logs ->
            server.enqueue(MockResponse.Builder().body(body).bodyDelay(3, TimeUnit.SECONDS).build())
            val jev = JevClient(http, "test-token")
            val pending = async { jev.evaluate(state, questions) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            withTimeout(1_000) { pending.cancelAndJoin() }
            assertTrue(pending.isCancelled)
            assertTrue(logs.single().formattedMessage.endsWith("outcome=cancelled"))
            server.enqueue(MockResponse(body = body))
            assertEquals(0.9, jev.evaluate(state, questions).getValue("calendar"))
        }
    }

    @Test
    fun `two second timeout covers slow body receipt`() = runBlocking {
        withClient { server, http, logs ->
            server.enqueue(MockResponse.Builder().body(body).bodyDelay(3, TimeUnit.SECONDS).build())
            withTimeout(4_000) {
                assertFailsWith<HttpRequestTimeoutException> { JevClient(http, "test-token").evaluate(state, questions) }
            }
            assertTrue(logs.single().formattedMessage.endsWith("outcome=timeout"))
        }
    }

    @Test
    fun `connection failure does not retry the Jev POST`() = runBlocking {
        withClient { server, http, logs ->
            val jev = JevClient(http, "test-token")
            server.enqueue(MockResponse(body = body))
            jev.evaluate(state, questions)
            server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            server.enqueue(MockResponse(body = body))
            assertFailsWith<IOException> { jev.evaluate(state, questions) }
            assertEquals(2, server.requestCount)
            assertTrue(logs.last().formattedMessage.endsWith("outcome=failure"))
            assertEquals(0.9, jev.evaluate(state, questions).getValue("calendar"))
        }
    }

    private suspend fun withClient(block: suspend (MockWebServer, HttpClient, List<ILoggingEvent>) -> Unit) {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val logger = LoggerFactory.getLogger(JevClient::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            MockWebServer().use { server ->
                server.protocols = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
                server.useHttps(serverTls.sslSocketFactory())
                server.start()
                val owner = ProviderHttpClients()
                val http = owner.jev
                assertNotSame(owner.standard, http)
                (http.engine.config as OkHttpConfig).config {
                    sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                }
                http.requestPipeline.intercept(HttpRequestPipeline.Before) {
                    context.url.takeFrom(server.url("/v1/systemone").toString())
                }
                http.receivePipeline.intercept(HttpReceivePipeline.Before) { response ->
                    assertEquals(HttpProtocolVersion.HTTP_1_1, response.version)
                }
                try {
                    block(server, http, appender.list)
                } finally {
                    owner.close()
                    owner.close()
                    withTimeout(5_000) { http.engine.coroutineContext.job.join() }
                }
            }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
