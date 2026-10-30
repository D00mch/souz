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
import ru.souz.agent.spi.TaskClassification
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
import kotlin.test.assertTrue
import kotlin.test.assertFalse

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
    fun `complexity requires confident tool free answers with bounded reference history`() = runTest {
        var simple = 0.0
        val capabilities = mapOf("application.action" to "Read or change application state")
        val bounded = request.copy(messages = listOf(LLMRequest.Message(LLMMessageRole.user, "too old")) +
            List(4) { LLMRequest.Message(LLMMessageRole.assistant, "x".repeat(5000)) } + request.messages)
        val engine = MockEngine { httpRequest ->
            val payload = restJsonMapper.readTree(httpRequest.body.toByteArray())
            val state = payload["state"]
            assertEquals("user: Check my calendar and email the agenda", state["current_request"].asText())
            assertEquals(restJsonMapper.valueToTree<JsonNode>(capabilities), state["available_capabilities"])
            val previous = state["previous_dialogue"].asText()
            assertEquals(9, previous.lines().size)
            assertTrue(previous.contains("ListEvents {}"))
            assertTrue(previous.contains("private tool result"))
            for (excluded in listOf("too old", "LLM formatting instructions", "email the agenda", "x".repeat(4001))) {
                assertFalse(previous.contains(excluded))
            }
            assertEquals("noul", payload["questions"]["simple"]["type"].asText())
            respond("""{"answers":{"simple":{"type":"noul","noul":$simple}}}""")
        }
        HttpClient(engine) { providerHttpClientDefaults() }.use { http ->
            val classifier = JevClassifier(JevClient(http, "test-token"))
            assertEquals(listOf(true, false, false), listOf(0.89, 0.9, 1.0).map {
                simple = it; classifier.classify(bounded, capabilities).complex
            })
            val attached = request.copy(messages = listOf(request.messages.last().copy(attachments = listOf("file-id"))))
            assertTrue(classifier.classify(attached, capabilities).complex)
            assertEquals(3, engine.requestHistory.size)
        }
    }

    @Test
    fun `routing preserves its verdict while waiting categories require a valid confident choice`() = runTest {
        var simple = 0.2
        var choice = ""
        val categories = mapOf("travel" to "Trips.", "weather" to "Weather.")
        val engine = MockEngine { httpRequest ->
            val questions = restJsonMapper.readTree(httpRequest.body.toByteArray())["questions"]
            assertEquals(2, questions.size())
            assertEquals("noul", questions["simple"]["type"].asText())
            assertEquals("choice", questions["choice"]["type"].asText())
            assertEquals(
                restJsonMapper.valueToTree<JsonNode>(categories + ("other" to "None of the other options fits.")),
                questions["choice"]["criteria"],
            )
            respond("""{"answers":{"simple":{"type":"noul","noul":$simple},"choice":$choice}}""")
        }
        HttpClient(engine) { providerHttpClientDefaults() }.use { http ->
            val client = JevClient(http, "test-token")
            val cases = listOf(
                Triple("""{"type":"choice","probabilities":{"travel":0.6,"weather":0.3,"other":0.1}}""", 0.5, "travel"),
                Triple("""{"type":"choice","probabilities":{"travel":0.5,"weather":0.3,"other":0.2}}""", 0.5, "travel"),
                Triple("""{"type":"choice","probabilities":{"travel":0.49,"weather":0.3,"other":0.21}}""", 0.5, null),
                Triple("""{"type":"choice","probabilities":{"travel":0.2,"weather":0.3,"other":0.5}}""", 0.5, null),
                Triple("""{"type":"choice","probabilities":{"travel":0.6,"weather":0.3,"other":0.1}}""", 0.7, null),
                Triple("""{"type":"choice","probabilities":{"travel":0.6}}""", 0.5, null),
                Triple("""{"type":"noul","probabilities":{"travel":1,"weather":0,"other":0}}""", 0.5, null),
                Triple("null", 0.5, null),
            )
            for ((answer, threshold, expected) in cases) for (probability in listOf(0.2, 0.95)) {
                choice = answer
                simple = probability
                val classifier = JevClassifier(client, waitCategories = categories, waitCategoryThreshold = threshold)
                assertEquals(TaskClassification(simple < 0.9, expected), classifier.classify(request, emptyMap()), answer)
            }
            simple = -1.0
            assertFailsWith<IllegalStateException> {
                JevClassifier(client, waitCategories = categories).classify(request, emptyMap())
            }
        }
    }

    @Test
    fun `oversized current requests bypass classification including actions beyond the limit`() = runTest {
        val boundary = "x".repeat(4000)
        val engine = MockEngine { httpRequest ->
            val state = restJsonMapper.readTree(httpRequest.body.toByteArray())["state"]
            assertEquals("user: $boundary", state["current_request"].asText())
            respond("""{"answers":{"simple":{"type":"noul","noul":1.0}}}""")
        }
        HttpClient(engine) { providerHttpClientDefaults() }.use { http ->
            val classifier = JevClassifier(JevClient(http, "test-token"))
            for (text in listOf(boundary + "x", boundary + "\nEmail this to my manager.")) {
                val longRequest = request.copy(messages = listOf(LLMRequest.Message(LLMMessageRole.user, text)))
                assertTrue(classifier.classify(longRequest, emptyMap()).complex)
                assertTrue(engine.requestHistory.isEmpty())
            }
            val exactRequest = request.copy(messages = listOf(LLMRequest.Message(LLMMessageRole.user, boundary)))
            assertFalse(classifier.classify(exactRequest, emptyMap()).complex)
            assertEquals(1, engine.requestHistory.size)
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
