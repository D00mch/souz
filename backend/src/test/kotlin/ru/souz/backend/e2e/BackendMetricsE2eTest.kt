package ru.souz.backend.e2e

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpMethod
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.souz.backend.http.BackendHttpRoutes

class BackendMetricsE2eTest {
    @Test
    fun `scrape exports bounded HTTP JVM pool and shared execution load`() = backendE2eTest("e2e_metrics") {
        val userId = UUID.randomUUID().toString()
        val chatId = createPublicChat(userId)
        llm.hangUntilCancelled()
        val submitted = client.post(BackendHttpRoutes.chatMessages(chatId)) {
            trusted(userId)
            jsonBody("""{"content":"wait","options":{"model":"${E2E_LOCAL_MODEL.alias}"}}""")
        }
        eventually("running LLM") { llm.requests.singleOrNull() }
        val running = client.get("/metrics")
        assertEquals(HttpStatusCode.OK, running.status)
        assertTrue(running.headers[HttpHeaders.ContentType]!!.startsWith("text/plain; version=0.0.4"))
        val exposition = running.bodyAsText()
        assertTrue(exposition.contains("souz_executions_active{state=\"running\"} 1.0"))
        withPeerBackend { peer ->
            assertTrue(peer.client.get("/metrics").bodyAsText().contains("souz_executions_active{state=\"running\"} 1.0"))
        }
        repeat(2) { index ->
            client.request("/unknown-$index") {
                method = HttpMethod("METHOD-$index")
                header(HttpHeaders.Host, "arbitrary-$index.example")
            }
            client.get("/health")
        }
        val after = client.get("/metrics").bodyAsText()
        listOf("jvm_memory_used_bytes", "process_uptime_seconds", "hikaricp_connections_active",
            "hikaricp_connections_acquire_seconds_bucket", "ktor_http_server_requests_seconds_bucket",
            "souz_execution_duration_seconds_bucket").forEach { assertTrue(after.contains(it), it) }
        assertTrue(after.contains("le=\"900.0\""))
        listOf(userId, chatId, "arbitrary-", "unknown-", "METHOD-", "route=\"/health", "route=\"/metrics")
            .forEach { assertFalse(after.contains(it), it) }
        client.post(BackendHttpRoutes.cancelActive(chatId)) { trusted(userId) }
        backend.awaitExecution(UUID.fromString(submitted.jsonBody()["execution"]["id"].asText()))
        val finished = client.get("/metrics").bodyAsText()
        assertTrue(finished.contains("souz_executions_total{outcome=\"cancelled\"} 1.0"))
        listOf("queued", "running", "waiting_option", "cancelling").forEach {
            assertTrue(finished.contains("souz_executions_active{state=\"$it\"} 0.0"))
        }
    }
}
