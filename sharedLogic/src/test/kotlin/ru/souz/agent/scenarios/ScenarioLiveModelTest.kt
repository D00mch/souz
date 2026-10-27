package ru.souz.agent.scenarios

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import org.opentest4j.TestAbortedException
import kotlinx.coroutines.test.runTest
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.llms.restJsonMapper
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScenarioLiveModelTest {
    @Test
    fun `opt out reads no credentials and creates no client`() {
        val reads = mutableListOf<String>()
        assertFailsWith<TestAbortedException> {
            ScenarioLiveModel.fromEnvironment(
                read = { reads += it; null },
                createClient = { error("Must not create an HTTP client before opt in") },
            )
        }
        assertEquals(listOf(SOUZ_AGENT_INTEGRATION_TESTS_ON), reads)
    }

    @Test
    fun `explicit opt in with missing configuration fails before creating a client`() {
        for (missing in listOf("OPENAI_MODEL", "OPENAI_BASE_URL", "OPENAI_API_KEY")) {
            val environment = testEnvironment() - missing
            val failure = assertFailsWith<IllegalArgumentException> {
                ScenarioLiveModel.fromEnvironment(environment::get) { error("Incomplete model configuration") }
            }
            assertContains(failure.message.orEmpty(), "$missing is required")
        }
    }

    @Test
    fun `environment selects arbitrary wire model endpoint and reasoning effort`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("https://model.invalid/v1/chat/completions", request.url.toString())
            respond(
                """{"id":"reply","created":1,"model":"custom/deployment-123","choices":[{"index":0,"message":{"role":"assistant","content":"Done"},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        ScenarioLiveModel.fromEnvironment(testEnvironment()::get) {
            HttpClient(engine) { providerHttpClientDefaults() }
        }.use { model ->
            val result = model.testSupport().runScenario("Say done")
            assertEquals("Done", result.output)
            val request = result.requests.single()
            assertEquals("custom/deployment-123", request.model)
            assertEquals("low", request.reasoningEffort)
            val wire = restJsonMapper.readTree(engine.requestHistory.single().body.toByteArray())
            assertEquals("custom/deployment-123", wire["model"].asText())
            assertEquals("low", wire["reasoning_effort"].asText())
        }
    }
}

private fun testEnvironment(): Map<String, String> = mapOf(
    SOUZ_AGENT_INTEGRATION_TESTS_ON to "true",
    "OPENAI_MODEL" to "custom/deployment-123",
    "OPENAI_BASE_URL" to "https://model.invalid/v1",
    "OPENAI_API_KEY" to "fixture-key",
    SOUZ_AGENT_INTEGRATION_TEST_REASONING_EFFORT to "low",
)
