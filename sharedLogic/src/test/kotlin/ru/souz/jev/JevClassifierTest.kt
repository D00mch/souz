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
import ru.souz.agent.skills.SkillId
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.llms.restJsonMapper
import ru.souz.llms.runtime.JevClassifier
import ru.souz.llms.runtime.jevState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JevClassifierTest {
    private val request = LLMRequest.Chat(
        model = "conversational-model",
        messages = listOf(
            LLMRequest.Message(LLMMessageRole.system, "LLM formatting instructions"),
            LLMRequest.Message(LLMMessageRole.user, "Prepare for today's meetings"),
            LLMRequest.Message(LLMMessageRole.user, "Summarize them"),
            LLMRequest.Message(LLMMessageRole.assistant, "", functionCall = LLMRequest.FunctionCall("ListEvents", "{}")),
            LLMRequest.Message(LLMMessageRole.function, "private tool result"),
            LLMRequest.Message(LLMMessageRole.assistant, " You have two meetings. " + "x".repeat(400)),
            LLMRequest.Message(LLMMessageRole.user, "Check my calendar and email the agenda"),
        ),
    )
    private val sides = mapOf(
        "true" to "the request or at least one of its steps needs it",
        "false" to "no step of the request needs this; a mentioned topic alone does not count",
    )

    @Test
    fun `state puts the new message first and earlier non-empty ones newest first, cut to 300 characters`() {
        val assistant = ("You have two meetings. " + "x".repeat(400)).take(299)
        assertEquals(
            "New user message:\nCheck my calendar and email the agenda\n\nPrevious messages, newest first:\n" +
                "Assistant: $assistant\nUser: Summarize them\nUser: Prepare for today's meetings",
            jevState(request.messages),
        )
        assertEquals(
            "New user message:\nCheck my calendar and email the agenda\n\nPrevious messages, newest first:\n" +
                "Assistant: $assistant\nUser: Summarize them",
            jevState(request.messages, historyLimit = 2),
        )
        assertEquals("Check my calendar", jevState(listOf(request.messages[0], LLMRequest.Message(LLMMessageRole.user, "Check my calendar"))))
    }

    @Test
    fun `Skills are Noul questions over the last two messages`() = runTest {
        val paper = SkillId("Paper.S17")
        val music = SkillId("music")
        val descriptions = mapOf(paper to "Summarize papers.", music to "Compose music.")
        val engine = MockEngine { httpRequest ->
            val payload = restJsonMapper.readTree(httpRequest.body.toByteArray())
            assertEquals(jevState(request.messages, historyLimit = 2), payload["state"].asText())
            assertEquals(
                restJsonMapper.valueToTree<JsonNode>(descriptions.entries.associate { (id, description) ->
                    id.value to mapOf(
                        "instructions" to "Does fulfilling the latest user request require this Skill: ${id.value} ($description)?",
                        "type" to "noul",
                        "criteria" to sides,
                    )
                }),
                payload["questions"],
            )
            respond("""{"answers":{"Paper.S17":{"type":"noul","noul":0.9},"music":{"type":"noul","noul":0.5}}}""")
        }
        HttpClient(engine) { providerHttpClientDefaults() }.use { http ->
            val client = JevClient(http, "test-token")
            assertEquals(setOf(paper), JevClassifier(client).selectSkills(request, descriptions))
            assertEquals(emptySet(), JevClassifier(client).selectSkills(request, emptyMap()))
            assertEquals(1, engine.requestHistory.size)
        }
    }

    @Test
    fun `complexity requires confident text only answer and includes supplied tool facts`() = runTest {
        var simple = 0.0
        val engine = MockEngine { httpRequest ->
            val payload = restJsonMapper.readTree(httpRequest.body.toByteArray())
            kotlin.test.assertTrue(payload["state"]["previous_dialogue"].asText().contains("private tool result"))
            kotlin.test.assertFalse(payload["state"]["previous_dialogue"].asText().contains("LLM formatting instructions"))
            assertEquals(mapOf("application.action" to "Read or change application state"),
                restJsonMapper.convertValue(payload["state"]["available_capabilities"], Map::class.java))
            assertEquals("user: Check my calendar and email the agenda", payload["state"]["current_request"].asText())
            kotlin.test.assertFalse(payload["state"]["previous_dialogue"].asText().contains("email the agenda"))
            val question = payload["questions"]["simple"]
            assertEquals("noul", question["type"].asText())
            kotlin.test.assertTrue(question["instructions"].asText().contains("without tools"))
            respond("""{"answers":{"simple":{"type":"noul","noul":$simple}}}""")
        }
        HttpClient(engine) { providerHttpClientDefaults() }.use { http ->
            val classifier = JevClassifier(JevClient(http, "test-token"))
            assertEquals(listOf(true, true, false, false), listOf(0.0, 0.89, 0.9, 1.0).map { simple = it; classifier.isComplex(request, mapOf("application.action" to "Read or change application state")) })
        }
    }

    @Test
    fun `current request stays separate from bounded action history and keeps speaker identity`() = runTest {
        val engine = MockEngine { httpRequest ->
            val state = restJsonMapper.readTree(httpRequest.body.toByteArray())["state"]
            assertEquals("user [souz_speaker_unknown]: Are you there?", state["current_request"].asText())
            val previous = state["previous_dialogue"].asText()
            assertEquals(9, previous.lines().size)
            kotlin.test.assertFalse(previous.contains("old user"))
            kotlin.test.assertFalse(previous.contains("Are you there?"))
            kotlin.test.assertTrue(previous.endsWith("assistant: I will check the device."))
            respond("""{"answers":{"simple":{"type":"noul","noul":0.95}}}""")
        }
        HttpClient(engine) { providerHttpClientDefaults() }.use { http ->
            val messages = listOf(LLMRequest.Message(LLMMessageRole.user, "old user")) +
                List(9) { LLMRequest.Message(LLMMessageRole.assistant, "I will check the device.") } +
                LLMRequest.Message(LLMMessageRole.user, "Are you there?", name = "souz_speaker_unknown")
            assertEquals(false, JevClassifier(JevClient(http, "test-token")).isComplex(request.copy(messages = messages), emptyMap()))
        }
    }

    @Test
    fun `attachments force complex without classification IO`() = runTest {
        HttpClient(MockEngine { error("Attachments must not reach classification") }).use { http ->
            val attached = request.copy(messages = listOf(LLMRequest.Message(
                LLMMessageRole.user, "What is in this file?", attachments = listOf("file-id"))))
            assertEquals(true, JevClassifier(JevClient(http, "test-token")).isComplex(attached, emptyMap()))
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
            // Probability values share the Noul validation above.
            val invalidChoice = listOf("""{"type":"noul","probabilities":{"A":0.5,"B":0.5}}""", """{"type":"choice","probabilities":{"A":1.0}}""")
            for (response in invalidChoice) {
                body = """{"answers":{"choice":$response}}"""
                assertFailsWith<IllegalStateException>(response) { client.choose(state, "Which one?", mapOf("A" to "A", "B" to "B")) }
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
