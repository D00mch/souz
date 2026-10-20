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
import ru.souz.tool.ToolCategory
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
            LLMRequest.Message(LLMMessageRole.function, "private tool result"),
            LLMRequest.Message(LLMMessageRole.user, "Summarize them"),
            LLMRequest.Message(LLMMessageRole.assistant, " You have two meetings. " + "x".repeat(400)),
            LLMRequest.Message(LLMMessageRole.user, "Check my calendar and email the agenda"),
        ),
    )
    private val categories = mapOf(
        ToolCategory.CALENDAR to "Read and edit calendar events",
        ToolCategory.MAIL to "Read and send email",
        ToolCategory.FILES to "Read and edit files",
        ToolCategory.DESKTOP to "Capture the screen",
        ToolCategory.IMAGE to "Analyze images",
    )
    private val sides = mapOf(
        "true" to "the request or at least one of its steps needs it",
        "false" to "no step of the request needs this; a mentioned topic alone does not count",
    )

    @Test
    fun `state puts the new message first and earlier ones newest first, cut to 300 characters`() {
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
    fun `categories are Noul questions selected strictly above the threshold`() = runTest {
        val screen = "; understanding the current screen needs both screen capture and image analysis, " +
            "unless a path to an existing image was supplied"
        val expectedQuestions = categories.entries.associate { (category, description) ->
            val rule = if (category in setOf(ToolCategory.DESKTOP, ToolCategory.IMAGE)) screen else ""
            category.name to mapOf(
                "instructions" to "Does fulfilling the latest user request require this tool category: ${category.name} ($description$rule)?",
                "type" to "noul",
                "criteria" to sides,
            )
        }
        val engine = MockEngine { httpRequest ->
            assertEquals("https://api.typesafe.ai/v1/systemone", httpRequest.url.toString())
            assertEquals(HttpMethod.Post, httpRequest.method)
            assertEquals("Bearer test-token", httpRequest.headers[HttpHeaders.Authorization])
            assertEquals(2_000L, httpRequest.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis)
            val payload = restJsonMapper.readTree(httpRequest.body.toByteArray())
            assertEquals("jev-configured", payload["model"].asText())
            assertEquals(jevState(request.messages), payload["state"].asText())
            assertEquals(restJsonMapper.valueToTree<JsonNode>(expectedQuestions), payload["questions"])
            respond(
                """{"answers":{"CALENDAR":{"type":"noul","noul":0.9},"MAIL":{"type":"noul","noul":0.5},""" +
                    """"FILES":{"type":"noul","noul":0.51},"DESKTOP":{"type":"noul","noul":0.2},"IMAGE":{"type":"noul","noul":0}}}""",
            )
        }
        HttpClient(engine) { providerHttpClientDefaults() }.use { http ->
            val client = JevClient(http, "test-token", "jev-configured")
            val cases = mapOf(
                0.0 to listOf(ToolCategory.CALENDAR, ToolCategory.MAIL, ToolCategory.FILES, ToolCategory.DESKTOP),
                0.5 to listOf(ToolCategory.CALENDAR, ToolCategory.FILES),
                0.9 to emptyList(),
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
