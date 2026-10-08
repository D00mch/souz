package ru.souz.backend.e2e

import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.souz.backend.http.BackendHttpRoutes

class BackendMetricsE2eTest {
    @Test
    fun `trusted network scraper sees bounded metrics without changing work or counting probes`() = backendE2eTest("e2e_metrics") {
        val metrics = backend.dependencies.metrics
        val first = client.get("/metrics")
        assertEquals(HttpStatusCode.OK, first.status)
        assertTrue(first.headers["Content-Type"].orEmpty().startsWith("text/plain; version=0.0.4"))
        val text = first.bodyAsText()
        for (name in listOf("souz_executions_total", "souz_execution_duration_seconds_bucket",
            "souz_pending_tool_calls", "souz_ws_connections_active",
            "jvm_memory_used_bytes", "jvm_gc_memory_allocated_bytes_total", "process_uptime_seconds", "hikaricp_connections_active")) {
            assertTrue(text.contains(name), "Missing $name")
        }
        assertTrue(text.contains("hikaricp_connections_acquire_seconds_bucket{pool=\"souz-backend-postgres\",le=\"900.0\"}"))
        val requestTimerCount = metrics.registry.find("ktor.http.server.requests").timers().sumOf { it.count() }
        repeat(3) { client.get("/health"); client.get("/metrics") }
        assertEquals(requestTimerCount, metrics.registry.find("ktor.http.server.requests").timers().sumOf { it.count() })
        assertTrue(llm.requests.isEmpty())

        val userId = UUID.randomUUID().toString()
        val chatId = createPublicChat(userId)
        val body = """{"content":"metrics turn","clientMessageId":"idempotent","options":{"model":"${E2E_LOCAL_MODEL.alias}"}}"""
        val accepted = client.post(BackendHttpRoutes.chatMessages(chatId)) { trusted(userId); jsonBody(body) }.jsonBody()
        val executionId = UUID.fromString(accepted["execution"]["id"].asText())
        backend.awaitExecution(executionId)
        repeat(3) { client.post(BackendHttpRoutes.chatMessages(chatId)) { trusted(userId); jsonBody(body) } }
        val started = checkNotNull(backend.executionRepository.get(userId, executionId))
        repeat(2) { backend.dependencies.executionService.propagateCancellation(started) }
        assertExecutionMeters(backend, "completed")
        val requests = llm.requests.size
        client.get("/unregistered/first")
        // Unmatched routes share one bounded n/a series.
        val registeredAfterUnknown = metrics.registry.meters.count { it.id.name.startsWith("ktor.http.server.requests") }
        repeat(5) { client.get("/unregistered/${UUID.randomUUID()}") { header("Host", "dynamic-${UUID.randomUUID()}.example") } }
        assertEquals(registeredAfterUnknown, metrics.registry.meters.count { it.id.name.startsWith("ktor.http.server.requests") })
        assertEquals(requests, llm.requests.size)
        val scrape = client.get("/metrics").bodyAsText()
        assertFalse(scrape.contains(userId))
        assertFalse(scrape.contains(chatId))
        assertFalse(scrape.contains(executionId.toString()))
    }
}
