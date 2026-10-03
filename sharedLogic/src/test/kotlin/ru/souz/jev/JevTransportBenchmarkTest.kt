package ru.souz.jev

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import ru.souz.llms.http.JevHttpTransport
import ru.souz.llms.http.createJevProviderHttpClient
import ru.souz.llms.http.createStandardProviderHttpClient
import ru.souz.llms.restJsonMapper
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.TimeSource

/** Live experiment: fixed inputs, rotated profile order, fresh pools for each cold-request sample. */
class JevTransportBenchmarkTest {
    @Test
    fun `compare full Jev latency across engines protocols and idle intervals`() = runBlocking {
        assumeTrue(System.getenv("SOUZ_BENCHMARK_JEV") == "1", "Enable with SOUZ_BENCHMARK_JEV=1 and JEV_TOKEN")
        val rounds = System.getenv("JEV_BENCHMARK_ROUNDS")?.toInt() ?: 20
        val idleMillis = System.getenv("JEV_BENCHMARK_IDLE_MS")?.toLong() ?: 20_000L
        val retentionMillis = System.getenv("JEV_IDLE_RETENTION_MS")?.toLong() ?: 60_000L
        require(rounds in 1..100 && idleMillis in 10_000..30_000 && retentionMillis > 0)
        val profiles = linkedMapOf("CIO_BASELINE" to null) + JevHttpTransport.entries.associateBy { it.name }
        val state = restJsonMapper.readTree("\"Check my calendar for today's meetings and email everyone the agenda\"")
        val questions = linkedMapOf(
            "calendar" to "Does this request require reading or editing calendar events?",
            "mail" to "Does this request require reading or sending email?",
            "files" to "Does this request require reading or editing local files?",
        )
        val samples = mutableListOf<Sample>()
        println("Jev benchmark rounds=$rounds repeats=3 idleMs=$idleMillis retentionMs=$retentionMillis timeoutMs=2000")
        repeat(rounds) { round ->
            val clients = mutableMapOf<String, HttpClient>()
            try {
                profiles.forEach { (name, transport) ->
                    clients[name] = transport?.let { createJevProviderHttpClient(it, retentionMillis) }
                        ?: createStandardProviderHttpClient()
                }
                val order = profiles.keys.toList().let { it.drop(round % it.size) + it.take(round % it.size) }
                val lastCompleted = mutableMapOf<String, TimeSource.Monotonic.ValueTimeMark>()
                val evaluators = clients.mapValues { (name, http) ->
                    JevClient(http, onHttpRequestCompleted = { diagnostic ->
                        samples.add(Sample(name, phase, round, actualIdleMillis, diagnostic))
                    })
                }
                for (name in order) {
                    phase = "first"
                    actualIdleMillis = 0.0
                    evaluate(evaluators.getValue(name), state, questions)
                    phase = "immediate"
                    repeat(3) { evaluate(evaluators.getValue(name), state, questions) }
                    lastCompleted[name] = TimeSource.Monotonic.markNow()
                }
                delay(idleMillis)
                phase = "after_idle"
                for (name in order) {
                    actualIdleMillis = lastCompleted.getValue(name).elapsedNow().inWholeNanoseconds / 1_000_000.0
                    evaluate(evaluators.getValue(name), state, questions)
                }
            } finally {
                clients.values.forEach(HttpClient::close)
            }
        }
        samples.forEach { sample -> println(restJsonMapper.writeValueAsString(sample)) }
        samples.groupBy { it.profile to it.phase }.forEach { (group, values) ->
            val successful = values.filter { it.http.outcome == "success" }.map { it.http.durationMs }.sorted()
            val median = if (successful.isEmpty()) null else {
                (successful[(successful.size - 1) / 2] + successful[successful.size / 2]) / 2
            }
            val p95 = successful.takeIf { it.isNotEmpty() }?.get(ceil(successful.size * 0.95).toInt() - 1)
            println(restJsonMapper.writeValueAsString(mapOf(
                "profile" to group.first, "phase" to group.second,
                "samples" to values.size, "successes" to successful.size,
                "medianMs" to median, "p95Ms" to p95,
                "protocols" to values.groupingBy { it.http.protocol ?: "no_response" }.eachCount(),
                "outcomes" to values.groupingBy { it.http.outcome }.eachCount(),
            )))
        }
        assertEquals(rounds * profiles.size * 5, samples.size)
        assertEquals(0, samples.count { it.http.outcome != "success" }, "Review reported failures before comparing latency")
        assertEquals(0, evaluationFailures, "Every response must also pass Jev answer validation")
    }

    private var phase = "first"
    private var actualIdleMillis = 0.0
    private var evaluationFailures = 0

    private suspend fun evaluate(
        jev: JevClient,
        state: com.fasterxml.jackson.databind.JsonNode,
        questions: Map<String, String>,
    ) {
        try {
            jev.evaluate(state, questions)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            evaluationFailures++
            // The diagnostic records HTTP failure/timeout; exception messages can contain private data.
        }
    }

    private data class Sample(
        val profile: String, val phase: String, val round: Int, val idleMs: Double, val http: JevHttpDiagnostic,
    )
}
