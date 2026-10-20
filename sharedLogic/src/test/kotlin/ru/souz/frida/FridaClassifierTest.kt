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
import ru.souz.tool.ToolCategory
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
    fun `categories are one choice with the top option and options at least 3 above the mean margin`() = runTest {
        val categories = mapOf(
            ToolCategory.CALENDAR to "Calendar events",
            ToolCategory.MAIL to "Email",
            ToolCategory.FILES to "Files",
            ToolCategory.DESKTOP to "Screen capture",
        )
        val engine = MockEngine { httpRequest ->
            assertEquals("https://frida.test/pooling", httpRequest.url.toString())
            assertEquals("Bearer frida-token", httpRequest.headers[HttpHeaders.Authorization])
            assertEquals(2_000L, httpRequest.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis)
            val payload = restJsonMapper.readTree(httpRequest.body.toByteArray())
            assertEquals("frida-decisions", payload["model"].asText())
            assertEquals(jevState(request.messages), payload["data"]["state"].asText())
            val question = payload["data"]["questions"]["choice"]
            assertEquals("choice", question["type"].asText())
            assertEquals(
                "Which tool category is needed to fulfil the latest user request? Consider every step of a combined request. " +
                    "Use history only to resolve missing context. Mentioning a topic alone does not require a tool.",
                question["instructions"].asText(),
            )
            assertEquals(
                restJsonMapper.valueToTree<JsonNode>(categories.mapKeys { it.key.name } + ("DESKTOP" to "Screen capture; " +
                    "understanding the current screen needs both screen capture and image analysis, unless a path to an existing image was supplied")),
                question["criteria"],
            )
            respond("""{"data":{"margins":{"choice":{"CALENDAR":5.0,"MAIL":3.0,"FILES":-2.0,"DESKTOP":-6.0}}}}""")
        }
        HttpClient(engine) { providerHttpClientDefaults() }.use { http ->
            val client = FridaClient(http, "https://frida.test/pooling", "frida-token", "frida-decisions")
            val reply = FridaClassifier(client).classify(request, categories)
            assertEquals(listOf(ToolCategory.CALENDAR, ToolCategory.MAIL), reply.categories)
            assertNull(reply.confidence)
            assertEquals(emptyList(), FridaClassifier(client).classify(request, emptyMap()).categories)
            assertEquals(1, engine.requestHistory.size)
        }
    }

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
                """"paper":4.0,"music":-2.0,"none":6.0""" to emptySet(),
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
            val state = restJsonMapper.valueToTree<JsonNode>("private message")
            val options = mapOf("A" to "First", "B" to "Second")
            val invalid = listOf("", "not json", "{}", """{"data":{"margins":{"choice":{"A":1.0}}}}""",
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
