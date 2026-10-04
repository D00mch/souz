package ru.souz.llms

import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import ru.souz.db.SettingsProvider
import ru.souz.llms.anthropic.AnthropicChatAPI
import ru.souz.llms.http.providerHttpClientDefaults

class AnthropicChatAPICacheTest {
    @Test
    fun `chat request puts message cache marker on penultimate cacheable message block`() = runTest {
        val request = captureRequest(
            body = LLMRequest.Chat(
                model = LLMModel.AnthropicHaiku45.alias,
                maxTokens = 256,
                messages = listOf(
                    LLMRequest.Message(role = LLMMessageRole.system, content = "System prompt"),
                    LLMRequest.Message(role = LLMMessageRole.user, content = "Hello"),
                    LLMRequest.Message(role = LLMMessageRole.assistant, content = "Hi"),
                    LLMRequest.Message(role = LLMMessageRole.user, content = "Tell me more"),
                ),
            ),
        )

        val anthropicMessages = request["messages"].asBlocks()
        val assistantBlocks = anthropicMessages[1]["content"].asBlocks()
        val finalUserBlocks = anthropicMessages.last()["content"].asBlocks()

        assertEquals(EPHEMERAL_CACHE, assistantBlocks.last()["cache_control"])
        assertNull(finalUserBlocks.last()["cache_control"])
    }

    @Test
    fun `chat request keeps tool and system cache breakpoints`() = runTest {
        val request = captureRequest(
            body = LLMRequest.Chat(
                model = LLMModel.AnthropicHaiku45.alias,
                maxTokens = 256,
                messages = listOf(
                    LLMRequest.Message(role = LLMMessageRole.system, content = "System prompt"),
                    LLMRequest.Message(role = LLMMessageRole.user, content = "Use tools"),
                ),
                functions = listOf(function("search"), function("read")),
            ),
        )

        val tools = request["tools"].asBlocks()
        val system = request["system"].asBlocks()

        assertNull(tools.first()["cache_control"])
        assertEquals(EPHEMERAL_CACHE, tools.last()["cache_control"])
        assertEquals(EPHEMERAL_CACHE, system.single()["cache_control"])
        assertEquals(mapOf("type" to "auto"), request["tool_choice"])
    }

    private suspend fun captureRequest(body: LLMRequest.Chat): Map<String, Any> {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.anthropicKey } returns "test-key"
        every { settingsProvider.requestTimeoutMillis } returns 1_000L
        val engine = MockEngine {
            respond(
                content = """{"content":[{"type":"text","text":"Hi"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        HttpClient(engine) { providerHttpClientDefaults() }.use { client ->
            assertIs<LLMResponse.Chat.Ok>(AnthropicChatAPI(settingsProvider, client).message(body))
        }
        return restJsonMapper.readValue(engine.requestHistory.single().body.toByteArray())
    }

    private fun function(name: String): LLMRequest.Function = LLMRequest.Function(
        name = name,
        description = "$name description",
        parameters = LLMRequest.Parameters(
            type = "object",
            properties = mapOf(
                "query" to LLMRequest.Property(type = "string", description = "Query"),
            ),
            required = listOf("query"),
        ),
    )
}

private val EPHEMERAL_CACHE = mapOf("type" to "ephemeral")

@Suppress("UNCHECKED_CAST")
private fun Any?.asBlocks(): List<Map<String, Any>> = this as List<Map<String, Any>>
