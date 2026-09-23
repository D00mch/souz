package ru.souz.jev

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.llms.restJsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JevClientTest {
    private val state = restJsonMapper.readTree("""{"message":"Check my calendar and email the agenda"}""")
    private val questions = mapOf("CALENDAR" to JevNoulQuestion("Does this require calendar tools?"))

    @Test
    fun `structured Noul evaluation preserves model usage and request local configuration`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("https://api.typesafe.ai/v1/systemone", request.url.toString())
            assertEquals(HttpMethod.Post, request.method)
            assertTrue(request.body.contentType.toString().startsWith("application/json"))
            assertEquals(30_000L, request.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis)
            val payload = restJsonMapper.readTree(request.body.toByteArray())
            assertEquals(state, payload["state"])
            assertEquals("noul", payload["questions"]["CALENDAR"]["type"].asText())
            assertEquals(questions.getValue("CALENDAR").instructions, payload["questions"]["CALENDAR"]["instructions"].asText())
            when (request.headers[HttpHeaders.Authorization]) {
                "Bearer token-a" -> assertEquals("custom-jev", payload["model"].asText())
                "Bearer token-b" -> assertEquals("jev-latest", payload["model"].asText())
                "Bearer token-c" -> assertEquals("explicit-jev", payload["model"].asText())
                else -> error("Unexpected credential")
            }
            respond(jevResponse(mapOf("CALENDAR" to 0.94)))
        }
        jevHttpClient(engine).use { http ->
            var token = "token-a"
            val first = JevClient(http, { token }, { "custom-jev" })
            val second = JevClient(http, { "token-b" }, { null })
            val results = listOf(
                async { first.evaluate(state, questions) },
                async { second.evaluate(state, questions) },
            ).awaitAll()
            results.forEach {
                assertEquals("jev-test-version", it.model)
                assertEquals(mapOf("CALENDAR" to 0.94), it.probabilities)
                assertEquals(JevUsage(372, 70), it.usage)
            }
            token = "token-c"
            first.evaluate(state, questions, model = "explicit-jev")
            // Both adapters keep the host transport usable after evaluation.
            assertEquals(3, engine.requestHistory.size)
        }
    }

    @Test
    fun `missing or malformed answers cannot silently select categories`() = runTest {
        val valid = jevResponse(mapOf("CALENDAR" to 0.9))
        val invalid = listOf(
            "", "null", "not json", "{}",
            valid.replace("\"CALENDAR\"", "\"OTHER\""),
            valid.replace("\"noul\"", "\"choice\""),
            valid.replace("0.9", "null"),
            valid.replace("0.9", "\"0.9\""),
            valid.replace("0.9", "1.1"),
            valid.replace("0.9", "-0.1"),
            valid.replace("0.9", "1e999"),
            valid.replace("\"model\"", "\"missing_model\""),
            valid.replace("\"usage\"", "\"missing_usage\""),
        )
        for (body in invalid) {
            jevHttpClient(MockEngine { respond(body) }).use { http ->
                assertFailsWith<IllegalStateException>(body) {
                    JevClient(http, { "test-token" }).evaluate(state, questions)
                }
            }
        }
    }

    @Test
    fun `HTTP errors omit response bodies and credentials`() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable)) {
            jevHttpClient(MockEngine { respond("secret-token and private state", status) }).use { http ->
                val failure = assertFailsWith<IllegalStateException> {
                    JevClient(http, { "secret-token" }).evaluate(state, questions)
                }
                assertTrue(failure.message.orEmpty().contains(status.value.toString()))
                assertFalse(failure.message.orEmpty().contains("secret-token"))
                assertFalse(failure.message.orEmpty().contains("private state"))
            }
        }
    }

    @Test
    fun `timeout and cancellation propagate without client retries`() = runTest {
        val failures = listOf(CancellationException("Cancelled"), HttpRequestTimeoutException("https://api.typesafe.ai", 30_000))
        for (failure in failures) {
            var calls = 0
            jevHttpClient(MockEngine { calls++; throw failure }).use { http ->
                val thrown = assertFailsWith<Exception> {
                    JevClient(http, { "test-token" }).evaluate(state, questions)
                }
                assertEquals(failure::class, thrown::class)
                assertEquals(1, calls)
            }
        }
    }

    @Test
    fun `invalid configuration and empty questions fail before IO`() = runTest {
        jevHttpClient(MockEngine { error("HTTP must not be called") }).use { http ->
            for (token in listOf(null, "", "  ")) {
                val client = JevClient(http, { token })
                assertFailsWith<IllegalStateException> { client.requireConfigured() }
                assertFailsWith<IllegalStateException> { client.evaluate(state, questions) }
            }
            val client = JevClient(http, { "test-token" })
            assertFailsWith<IllegalArgumentException> { client.evaluate(state, emptyMap()) }
            assertFailsWith<IllegalArgumentException> { client.evaluate(state, questions, " ") }
            assertFailsWith<IllegalArgumentException> { client.evaluate(restJsonMapper.readTree("42"), questions) }
        }
    }
}

internal fun jevHttpClient(engine: MockEngine): HttpClient = HttpClient(engine) { providerHttpClientDefaults() }

internal fun jevResponse(probabilities: Map<String, Double>): String = restJsonMapper.writeValueAsString(
    mapOf(
        "model" to "jev-test-version",
        "answers" to probabilities.mapValues { (_, probability) -> mapOf("type" to "noul", "noul" to probability) },
        "usage" to mapOf("input_tokens" to 372, "output_tokens" to 70),
        "future_field" to "ignored",
    ),
)
