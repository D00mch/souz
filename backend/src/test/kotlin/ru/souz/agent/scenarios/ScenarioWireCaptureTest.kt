package ru.souz.agent.scenarios

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.llms.openai.OpenAICompatibleChatAPI
import ru.souz.llms.restJsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ScenarioWireCaptureTest {
    @Test
    fun `history check detects a dropped tool result even when the assistant answer remains`() {
        val system = message("system", "Previous system instructions")
        val context = message("user", "<context>\nBackground information. Previous date.</context>")
        val user = message("user", "Find the matching doctor")
        val tool = message("tool", "Returned link from the fixture")
        val previous = ScenarioWireRequest(listOf(system, context, user, tool), tools = null)
        val next = ScenarioWireRequest(listOf(
            message("system", "New system instructions"), user, tool,
            message("assistant", "I found a result."), message("user", "Send it"),
        ), tools = null)

        assertEquals(ScenarioHistory(3, 3, null), previous.checkHistory(next, "I found a result."))
        assertEquals(
            ScenarioHistory(3, 1, "tool"),
            previous.checkHistory(next.copy(messages = next.messages - listOf(tool)), "I found a result."),
        )
        assertEquals(0, previous.compareTo(next, 2).unchangedMessages)
    }

    @Test
    fun `reports zero separately from missing cache usage and compares actual request messages`() = runTest {
        val wire = ScenarioWireCapture()
        var usageDetails = ""","prompt_tokens_details":{"cached_tokens":0}"""
        val engine = MockEngine {
            respond(
                """{"model":"fixture","choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                    "usage":{"prompt_tokens":20,"completion_tokens":2$usageDetails}}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        HttpClient(engine) {
            providerHttpClientDefaults()
            install(wire.plugin)
        }.use { client ->
            val api = OpenAICompatibleChatAPI(
                LlmProvider.OPENAI, scenarioSettings("fixture", LlmProvider.OPENAI), client,
                apiKey = "fixture-key", baseUrl = "https://fixture.example/v1",
            )
            val request = LLMRequest.Chat(
                model = "fixture", provider = LlmProvider.OPENAI, messages = listOf(
                    LLMRequest.Message(LLMMessageRole.system, "Original"),
                    LLMRequest.Message(LLMMessageRole.user, "Question"),
                ),
                functions = listOf(LLMRequest.Function("fixture")),
            )
            assertIs<LLMResponse.Chat.Ok>(api.message(request))
            assertEquals(ScenarioWireUsage(20, 2, 0), wire.usage)
            val original = requireNotNull(wire.request)
            usageDetails = ""
            wire.reset()
            assertIs<LLMResponse.Chat.Ok>(
                api.message(request.copy(messages = request.messages.mapIndexed { i, message ->
                    if (i == 0) message.copy(content = "Rebuilt") else message
                })),
            )
            assertNull(wire.usage?.cached)
            assertEquals(
                ScenarioPrefix(2, 2, 2, 0, "system", "system", true),
                original.compareTo(requireNotNull(wire.request), turn = 2),
            )
        }
    }
}

private fun message(role: String, content: String) =
    restJsonMapper.createObjectNode().put("role", role).put("content", content)
