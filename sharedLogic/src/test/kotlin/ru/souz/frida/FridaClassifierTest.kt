package ru.souz.frida

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import ru.souz.agent.skills.SkillId
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.llms.restJsonMapper
import ru.souz.llms.runtime.FridaClassifier
import ru.souz.llms.runtime.jevState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FridaClassifierTest {
    private val request = LLMRequest.Chat(
        model = "conversational-model",
        messages = listOf(
            LLMRequest.Message(LLMMessageRole.system, "LLM formatting instructions"),
            LLMRequest.Message(LLMMessageRole.user, "Prepare for today's meetings"),
            LLMRequest.Message(LLMMessageRole.assistant, "You have two meetings."),
            LLMRequest.Message(LLMMessageRole.user, "Check my calendar and email the agenda"),
        ),
    )

    @Test
    fun `Skills are one choice over the last two messages with none`() = runTest {
        val paper = SkillId("paper")
        val music = SkillId("music")
        val descriptions = mapOf(paper to "Summarize papers.", music to "Compose music.", SkillId("none") to "Ambiguous ID.")
        var margins = ""
        val engine = MockEngine { httpRequest ->
            assertNull(httpRequest.headers[HttpHeaders.Authorization])
            val payload = restJsonMapper.readTree(httpRequest.body.toByteArray())
            assertNull(payload["model"])
            assertEquals(jevState(request.messages, historyLimit = 2), payload["data"]["state"].asText())
            assertEquals(listOf("paper", "music", "none"), payload["data"]["questions"]["choice"]["criteria"].fieldNames().asSequence().toList())
            respond("""{"data":{"margins":{"choice":{$margins}}}}""")
        }
        HttpClient(engine) { providerHttpClientDefaults() }.use { http ->
            val classifier = FridaClassifier(FridaClient(http, "https://frida.test/pooling", token = null, model = null))
            val cases = mapOf(
                """"paper":5.0,"music":3.0,"none":-8.0""" to setOf(paper, music),
                """"paper":5.0,"music":-8.0,"none":6.0""" to emptySet(),
            )
            cases.forEach { (answer, expected) ->
                margins = answer
                assertEquals(expected, classifier.selectSkills(request, descriptions), answer)
            }
            assertEquals(emptySet(), classifier.selectSkills(request, mapOf(SkillId("none") to "Ambiguous ID.")))
            assertEquals(cases.size, engine.requestHistory.size)
        }
    }

    @Test
    fun `failed and malformed responses are rejected`() = runTest {
        var body = ""
        var status = HttpStatusCode.OK
        HttpClient(MockEngine { respond(body, status) }) { providerHttpClientDefaults() }.use { http ->
            val client = FridaClient(http, "https://frida.test/pooling")
            val state = "private message"
            val options = mapOf("A" to "First", "B" to "Second")
            val invalid = listOf("not json", """{"data":{"margins":{"choice":{"A":1.0}}}}""",
                """{"data":{"margins":{"choice":{"A":1.0,"B":"2"}}}}""", """{"data":{"margins":{"choice":{"A":1.0,"B":1e999}}}}""")
            for (response in invalid) {
                body = response
                assertFailsWith<IllegalStateException>(response) { client.choose(state, "Which?", options) }
            }
            status = HttpStatusCode.ServiceUnavailable
            body = """{"data":{"margins":{"choice":{"A":1.0,"B":2.0}}}}"""
            assertEquals("FRIDA request failed (HTTP 503)", assertFailsWith<IllegalStateException> { client.choose(state, "Which?", options) }.message)
            assertFailsWith<IllegalArgumentException> { FridaClient(http, " ") }
        }
    }
}
