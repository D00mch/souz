package ru.gigadesk.llms

import com.fasterxml.jackson.databind.JsonNode
import io.mockk.every
import io.mockk.mockk
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.giga.GigaMessageRole
import ru.gigadesk.giga.GigaModel
import ru.gigadesk.giga.GigaRequest
import ru.gigadesk.giga.GigaResponse
import ru.gigadesk.giga.TokenLogging
import ru.gigadesk.giga.gigaJsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnthropicChatAPICacheTest {
    @Test
    fun `buildChatRequest puts message cache marker on final message block`() {
        val api = createApi()
        val request = invokeBuildChatRequest(
            api = api,
            body = GigaRequest.Chat(
                model = GigaModel.AnthropicHaiku45.alias,
                maxTokens = 256,
                messages = listOf(
                    GigaRequest.Message(role = GigaMessageRole.system, content = "System prompt"),
                    GigaRequest.Message(role = GigaMessageRole.user, content = "Hello"),
                    GigaRequest.Message(role = GigaMessageRole.assistant, content = "Hi"),
                    GigaRequest.Message(role = GigaMessageRole.user, content = "Tell me more"),
                ),
            ),
        )

        val anthropicMessages = request["messages"].asBlocks()
        val assistantBlocks = anthropicMessages[1]["content"].asBlocks()
        val finalUserBlocks = anthropicMessages.last()["content"].asBlocks()

        assertNull(assistantBlocks.last()["cache_control"])
        assertEquals(EPHEMERAL_CACHE, finalUserBlocks.last()["cache_control"])
    }

    @Test
    fun `buildChatRequest keeps tool and system cache breakpoints`() {
        val api = createApi()
        val request = invokeBuildChatRequest(
            api = api,
            body = GigaRequest.Chat(
                model = GigaModel.AnthropicHaiku45.alias,
                maxTokens = 256,
                messages = listOf(
                    GigaRequest.Message(role = GigaMessageRole.system, content = "System prompt"),
                    GigaRequest.Message(role = GigaMessageRole.user, content = "Use tools"),
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

    @Test
    fun `parseUsage includes cache reads in prompt tokens`() {
        val api = createApi()
        val usageNode: JsonNode = gigaJsonMapper.readTree(
            """
            {
              "input_tokens": 21,
              "cache_creation_input_tokens": 188086,
              "cache_read_input_tokens": 17,
              "output_tokens": 393
            }
            """.trimIndent()
        )

        val usage = invokeParseUsage(api, usageNode)

        assertEquals(188124, usage.promptTokens)
        assertEquals(393, usage.completionTokens)
        assertEquals(188517, usage.totalTokens)
        assertEquals(17, usage.precachedTokens)
    }

    private fun createApi(): AnthropicChatAPI {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.anthropicKey } returns "test-key"
        every { settingsProvider.requestTimeoutMillis } returns 1_000L
        every { settingsProvider.gigaModel } returns GigaModel.AnthropicHaiku45

        val tokenLogging = mockk<TokenLogging>(relaxed = true)
        return AnthropicChatAPI(settingsProvider, tokenLogging)
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeBuildChatRequest(
        api: AnthropicChatAPI,
        body: GigaRequest.Chat,
    ): Map<String, Any> {
        val method = AnthropicChatAPI::class.java.getDeclaredMethod(
            "buildChatRequest",
            GigaRequest.Chat::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(api, body, GigaModel.AnthropicHaiku45.alias, false) as Map<String, Any>
    }

    private fun invokeParseUsage(api: AnthropicChatAPI, node: JsonNode): GigaResponse.Usage {
        val method = AnthropicChatAPI::class.java.getDeclaredMethod("parseUsage", JsonNode::class.java)
        method.isAccessible = true
        return method.invoke(api, node) as GigaResponse.Usage
    }

    private fun function(name: String): GigaRequest.Function = GigaRequest.Function(
        name = name,
        description = "$name description",
        parameters = GigaRequest.Parameters(
            type = "object",
            properties = mapOf(
                "query" to GigaRequest.Property(type = "string", description = "Query"),
            ),
            required = listOf("query"),
        ),
    )
}

private val EPHEMERAL_CACHE = mapOf("type" to "ephemeral")

@Suppress("UNCHECKED_CAST")
private fun Any?.asBlocks(): List<Map<String, Any>> = this as List<Map<String, Any>>
