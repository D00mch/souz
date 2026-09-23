package ru.souz.jev

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.llms.restJsonMapper
import ru.souz.llms.runtime.JevClassifier
import ru.souz.tool.ToolCategory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JevClassifierTest {
    private val request = LLMRequest.Chat(
        model = "conversational-model",
        messages = listOf(
            LLMRequest.Message(LLMMessageRole.system, "LLM formatting instructions"),
            LLMRequest.Message(LLMMessageRole.user, "History:\nUSER: Prepare for today's meetings"),
            LLMRequest.Message(LLMMessageRole.user, "New message:\nCheck my calendar and email the agenda"),
        ),
    )
    private val categories = mapOf(
        ToolCategory.CALENDAR to "Read and edit calendar events",
        ToolCategory.MAIL to "Read and send email",
        ToolCategory.FILES to "Read and edit files",
    )

    @Test
    fun `request uses Jev configuration and filtered history with independent category thresholds`() = runTest {
        val engine = MockEngine { httpRequest ->
            assertEquals("https://api.typesafe.ai/v1/systemone", httpRequest.url.toString())
            assertEquals(HttpMethod.Post, httpRequest.method)
            assertEquals("Bearer test-token", httpRequest.headers[HttpHeaders.Authorization])
            assertEquals(30_000L, httpRequest.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis)
            val payload = restJsonMapper.readTree(httpRequest.body.toByteArray())
            assertEquals("jev-configured", payload["model"].asText())
            assertEquals(request.messages.drop(1).map { it.content }, payload["state"].map { it["content"].asText() })
            assertEquals(listOf("user", "user"), payload["state"].map { it["role"].asText() })
            assertEquals(categories.keys.map { it.name }.toSet(), payload["questions"].fieldNames().asSequence().toSet())
            categories.forEach { (category, description) ->
                assertEquals("noul", payload["questions"][category.name]["type"].asText())
                assertContains(payload["questions"][category.name]["instructions"].asText(), description)
            }
            respond("""{"answers":{"CALENDAR":{"type":"noul","noul":0.4},"MAIL":{"type":"noul","noul":0.3},"FILES":{"type":"noul","noul":0}}}""")
        }
        HttpClient(engine) { providerHttpClientDefaults() }.use { http ->
            val client = JevClient(http, "test-token", "jev-configured")
            val cases = mapOf(
                0.0 to listOf(ToolCategory.CALENDAR, ToolCategory.MAIL),
                0.3 to listOf(ToolCategory.CALENDAR),
                0.5 to emptyList(),
            )
            cases.forEach { (threshold, expected) ->
                val result = JevClassifier(client, threshold).classify(request, categories)
                assertEquals(expected, result.categories)
                assertNull(result.confidence)
            }
            assertEquals(emptyList(), JevClassifier(client).classify(request, emptyMap()).categories)
            assertEquals(cases.size, engine.requestHistory.size)
        }
    }

    @Test
    fun `missing malformed and failed responses reject classification`() = runTest {
        var body = ""
        var status = HttpStatusCode.OK
        HttpClient(MockEngine { respond(body, status) }) { providerHttpClientDefaults() }.use { http ->
            val client = JevClient(http, "test-token")
            val state = restJsonMapper.valueToTree<JsonNode>("private message")
            val questions = mapOf("calendar" to "Does this request require calendar access?")
            val invalid = listOf("", "null", "not json", "{}", """{"answers":{"calendar":{"type":"choice","noul":0.9}}}""") +
                listOf("null", "\"0.9\"", "-0.1", "1.1", "1e999").map {
                    """{"answers":{"calendar":{"type":"noul","noul":$it}}}"""
                }
            for (response in invalid) {
                body = response
                assertFailsWith<IllegalStateException>(response) { client.evaluate(state, questions) }
            }
            status = HttpStatusCode.Unauthorized
            body = "test-token and private message"
            val failure = assertFailsWith<IllegalStateException> { client.evaluate(state, questions) }
            assertEquals("Jev request failed (HTTP 401)", failure.message)
        }
    }

    @Test
    fun `invalid configuration fails before IO`() {
        HttpClient(MockEngine { error("HTTP must not be called") }).use { http ->
            assertFailsWith<IllegalArgumentException> { JevClient(http, " ") }
            assertFailsWith<IllegalArgumentException> { JevClient(http, "test-token", " ") }
            val client = JevClient(http, "test-token")
            for (threshold in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1)) {
                assertFailsWith<IllegalArgumentException> { JevClassifier(client, threshold) }
            }
        }
    }
}
