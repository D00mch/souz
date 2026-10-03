package ru.souz.jev

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.slf4j.LoggerFactory
import ru.souz.llms.http.createJevProviderHttpClient
import ru.souz.llms.restJsonMapper
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals

/** Opt-in experiment; emits timings and connection counts, never credentials or payloads. */
class JevLatencyBenchmarkTest {
    @Test
    fun `compare first repeats and idle requests with identical Jev inputs`() = runBlocking<Unit> {
        val mode = System.getenv("SOUZ_TEST_JEV_LATENCY")
        assumeTrue(mode in listOf("local", "hosted"), "Set SOUZ_TEST_JEV_LATENCY=local or hosted")
        val samples = System.getenv("JEV_LATENCY_SAMPLES")?.toInt() ?: 20
        require(samples > 0)
        val model = System.getenv("JEV_MODEL")?.trim()?.takeIf(String::isNotEmpty) ?: "jev-latest"
        val state = restJsonMapper.readTree("\"Check my calendar for today's meetings\"")
        val questions = mapOf("calendar" to "Does this request require calendar access?")
        val server = if (mode == "local") JevLoopbackServer() else null
        val logger = LoggerFactory.getLogger(JevClient::class.java) as Logger
        val previousLevel = logger.level
        val timings = ListAppender<ILoggingEvent>().apply { start() }
        val measurements = mutableMapOf<Pair<Long, String>, MutableList<Long>>()
        logger.level = Level.INFO
        logger.addAppender(timings)
        try {
            repeat(samples) { trial ->
                // Alternate order to reduce systematic time-of-run bias.
                val profiles = if (trial % 2 == 0) listOf(5_000L, 60_000L) else listOf(60_000L, 5_000L)
                for (keepAliveTime in profiles) {
                    createJevProviderHttpClient(keepAliveTime).use { http ->
                        server?.redirect(http)
                        val client = if (server == null) JevClient(http, model = model)
                            else JevClient(http, "loopback-token", model)
                        for ((scenario, idleMillis) in listOf("first" to 0L, "repeat" to 0L, "idle10s" to 10_000L, "idle30s" to 30_000L)) {
                            delay(idleMillis)
                            timings.list.clear()
                            val connectionsBefore = server?.connections?.toSet().orEmpty()
                            val startedAtMs = System.currentTimeMillis()
                            client.evaluate(state, questions)
                            // Use JevClient's boundary, excluding response JSON parsing.
                            val event = timings.list.single { it.message.startsWith("Jev HTTP request durationMs=") }
                            assertEquals("success", event.argumentArray[2])
                            val durationMs = event.argumentArray[0] as Long
                            measurements.getOrPut(keepAliveTime to scenario) { mutableListOf() }.add(durationMs)
                            val newConnection = server?.let { it.connections.last() !in connectionsBefore }
                            println("JEV_SAMPLE mode=$mode keepAliveMs=$keepAliveTime scenario=$scenario trial=$trial startedAtMs=$startedAtMs durationMs=$durationMs newConnection=${newConnection ?: "external-trace"}")
                        }
                    }
                }
            }
            for ((profile, values) in measurements) {
                val sorted = values.sorted()
                val median = (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2.0
                val p95 = sorted[ceil(sorted.size * 0.95).toInt() - 1]
                println("JEV_SUMMARY mode=$mode keepAliveMs=${profile.first} scenario=${profile.second} n=${sorted.size} medianMs=$median p95Ms=$p95")
            }
            server?.let {
                println("JEV_CONNECTIONS requests=${it.connections.size} distinct=${it.connections.toSet().size}")
                assertEquals(it.connections.size, it.connections.toSet().size)
            }
        } finally {
            logger.detachAppender(timings)
            logger.level = previousLevel
            timings.stop()
            server?.close()
        }
    }
}
