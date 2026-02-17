package ru.gigadesk.llms

import io.mockk.every
import io.mockk.mockk
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.giga.GigaMessageRole
import ru.gigadesk.giga.GigaModel
import ru.gigadesk.giga.GigaRequest
import ru.gigadesk.giga.GigaResponse
import ru.gigadesk.giga.TokenLogging
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenAIChatAPIRequestTest {

    @Test
    fun `buildChatRequest resolves OpenAI model by enum name and includes tool choice`() {
        val api = createApi()
        val request = invokeBuildChatRequest(
            api = api,
            body = GigaRequest.Chat(
                model = GigaModel.OpenAIGpt5Mini.name,
                maxTokens = 256,
                messages = listOf(
                    GigaRequest.Message(role = GigaMessageRole.user, content = "Get horoscope"),
                ),
                functions = listOf(function("get_horoscope")),
            ),
            stream = false,
        )

        assertEquals(GigaModel.OpenAIGpt5Mini.alias, request["model"])
        assertEquals("auto", request["tool_choice"])
        val tools = request["tools"] as List<*>
        assertEquals(1, tools.size)
    }

    @Test
    fun `buildChatRequest maps tool response to role tool with call id`() {
        val api = createApi()
        val request = invokeBuildChatRequest(
            api = api,
            body = GigaRequest.Chat(
                model = GigaModel.OpenAIGpt5Nano.alias,
                maxTokens = 256,
                messages = listOf(
                    GigaRequest.Message(
                        role = GigaMessageRole.function,
                        content = """{"sign":"Taurus"}""",
                        functionsStateId = "call_123",
                        name = "get_horoscope",
                    ),
                ),
                functions = listOf(function("get_horoscope")),
            ),
            stream = false,
        )

        @Suppress("UNCHECKED_CAST")
        val messages = request["messages"] as List<Map<String, Any?>>
        assertEquals(1, messages.size)
        assertEquals("tool", messages.first()["role"])
        assertEquals("call_123", messages.first()["tool_call_id"])
        assertNotNull(messages.first()["content"])
    }

    @Test
    fun `buildChatRequest skips null placeholder assistant message between tool call and tool result`() {
        val api = createApi()
        val request = invokeBuildChatRequest(
            api = api,
            body = GigaRequest.Chat(
                model = GigaModel.OpenAIGpt5Nano.alias,
                maxTokens = 256,
                messages = listOf(
                    GigaRequest.Message(
                        role = GigaMessageRole.assistant,
                        content = """{"name":"get_horoscope","arguments":{"sign":"Taurus"}}""",
                        functionsStateId = "call_123",
                    ),
                    GigaRequest.Message(
                        role = GigaMessageRole.assistant,
                        content = "null",
                    ),
                    GigaRequest.Message(
                        role = GigaMessageRole.function,
                        content = """{"sign":"Taurus"}""",
                        functionsStateId = "call_123",
                        name = "get_horoscope",
                    ),
                ),
                functions = listOf(function("get_horoscope")),
            ),
            stream = false,
        )

        @Suppress("UNCHECKED_CAST")
        val messages = request["messages"] as List<Map<String, Any?>>
        assertEquals(2, messages.size)
        assertEquals("assistant", messages[0]["role"])
        assertNotNull(messages[0]["tool_calls"])
        assertEquals("tool", messages[1]["role"])
        assertEquals("call_123", messages[1]["tool_call_id"])
    }

    @Test
    fun `buildChatRequest moves regular assistant text after pending tool result`() {
        val api = createApi()
        val request = invokeBuildChatRequest(
            api = api,
            body = GigaRequest.Chat(
                model = GigaModel.OpenAIGpt5Nano.alias,
                maxTokens = 256,
                messages = listOf(
                    GigaRequest.Message(
                        role = GigaMessageRole.assistant,
                        content = """{"name":"get_horoscope","arguments":{"sign":"Taurus"}}""",
                        functionsStateId = "call_123",
                    ),
                    GigaRequest.Message(
                        role = GigaMessageRole.assistant,
                        content = "Plan: running the tool now",
                    ),
                    GigaRequest.Message(
                        role = GigaMessageRole.function,
                        content = """{"sign":"Taurus"}""",
                        functionsStateId = "call_123",
                        name = "get_horoscope",
                    ),
                ),
                functions = listOf(function("get_horoscope")),
            ),
            stream = false,
        )

        @Suppress("UNCHECKED_CAST")
        val messages = request["messages"] as List<Map<String, Any?>>
        assertEquals(3, messages.size)
        assertEquals("assistant", messages[0]["role"])
        assertNotNull(messages[0]["tool_calls"])
        assertEquals("tool", messages[1]["role"])
        assertEquals("call_123", messages[1]["tool_call_id"])
        assertEquals("assistant", messages[2]["role"])
        assertEquals("Plan: running the tool now", messages[2]["content"])
    }

    @Test
    fun `buildChatRequest merges consecutive assistant tool calls into one OpenAI assistant message`() {
        val api = createApi()
        val request = invokeBuildChatRequest(
            api = api,
            body = GigaRequest.Chat(
                model = GigaModel.OpenAIGpt5Nano.alias,
                maxTokens = 256,
                messages = listOf(
                    GigaRequest.Message(
                        role = GigaMessageRole.assistant,
                        content = """{"name":"tool_a","arguments":{"x":"1"}}""",
                        functionsStateId = "call_a",
                    ),
                    GigaRequest.Message(
                        role = GigaMessageRole.assistant,
                        content = """{"name":"tool_b","arguments":{"y":"2"}}""",
                        functionsStateId = "call_b",
                    ),
                    GigaRequest.Message(
                        role = GigaMessageRole.function,
                        content = """{"ok":true}""",
                        functionsStateId = "call_a",
                        name = "tool_a",
                    ),
                    GigaRequest.Message(
                        role = GigaMessageRole.function,
                        content = """{"ok":true}""",
                        functionsStateId = "call_b",
                        name = "tool_b",
                    ),
                ),
                functions = listOf(function("tool_a"), function("tool_b")),
            ),
            stream = false,
        )

        @Suppress("UNCHECKED_CAST")
        val messages = request["messages"] as List<Map<String, Any?>>
        assertEquals(3, messages.size)
        assertEquals("assistant", messages[0]["role"])
        @Suppress("UNCHECKED_CAST")
        val toolCalls = messages[0]["tool_calls"] as List<Map<String, Any?>>
        assertEquals(2, toolCalls.size)
        assertEquals("tool", messages[1]["role"])
        assertEquals("call_a", messages[1]["tool_call_id"])
        assertEquals("tool", messages[2]["role"])
        assertEquals("call_b", messages[2]["tool_call_id"])
    }

    @Test
    fun `parseCompletionsResponse ignores null content for tool calls`() {
        val api = createApi()
        val response = invokeParseCompletionsResponse(
            api = api,
            text = """
                {
                  "created": 1739900000,
                  "model": "gpt-5-nano",
                  "choices": [
                    {
                      "index": 0,
                      "finish_reason": "tool_calls",
                      "message": {
                        "role": "assistant",
                        "content": null,
                        "tool_calls": [
                          {
                            "id": "call_123",
                            "type": "function",
                            "function": {
                              "name": "get_horoscope",
                              "arguments": "{\"sign\":\"Taurus\"}"
                            }
                          }
                        ]
                      }
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 10,
                    "completion_tokens": 3,
                    "total_tokens": 13
                  }
                }
            """.trimIndent(),
            requestModel = GigaModel.OpenAIGpt5Nano.alias,
        )

        val chat = response as GigaResponse.Chat.Ok
        assertEquals(1, chat.choices.size)
        assertEquals("get_horoscope", chat.choices.first().message.functionCall?.name)
        assertTrue(chat.choices.none { it.message.content == "null" })
    }

    @Test
    fun `buildEmbeddingsRequest includes float encoding format`() {
        val api = createApi()
        val request = invokeBuildEmbeddingsRequest(
            api = api,
            body = GigaRequest.Embeddings(
                model = "Embeddings",
                input = listOf("hello"),
            ),
        )

        assertEquals("float", request["encoding_format"])
        assertEquals("text-embedding-3-small", request["model"])
    }

    private fun createApi(): OpenAIChatAPI {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.openaiKey } returns "test-key"
        every { settingsProvider.requestTimeoutMillis } returns 1_000L
        every { settingsProvider.gigaModel } returns GigaModel.OpenAIGpt5Nano

        val tokenLogging = mockk<TokenLogging>(relaxed = true)
        return OpenAIChatAPI(settingsProvider, tokenLogging)
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeBuildChatRequest(
        api: OpenAIChatAPI,
        body: GigaRequest.Chat,
        stream: Boolean,
    ): Map<String, Any> {
        val method = OpenAIChatAPI::class.java.getDeclaredMethod(
            "buildChatRequest",
            GigaRequest.Chat::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(api, body, stream) as Map<String, Any>
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeBuildEmbeddingsRequest(
        api: OpenAIChatAPI,
        body: GigaRequest.Embeddings,
    ): Map<String, Any> {
        val method = OpenAIChatAPI::class.java.getDeclaredMethod(
            "buildEmbeddingsRequest",
            GigaRequest.Embeddings::class.java,
        )
        method.isAccessible = true
        return method.invoke(api, body) as Map<String, Any>
    }

    private fun invokeParseCompletionsResponse(
        api: OpenAIChatAPI,
        text: String,
        requestModel: String,
    ): GigaResponse.Chat {
        val method = OpenAIChatAPI::class.java.getDeclaredMethod(
            "parseCompletionsResponse",
            String::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(api, text, requestModel) as GigaResponse.Chat
    }

    private fun function(name: String): GigaRequest.Function = GigaRequest.Function(
        name = name,
        description = "$name description",
        parameters = GigaRequest.Parameters(
            type = "object",
            properties = mapOf(
                "sign" to GigaRequest.Property(type = "string", description = "Sign"),
            ),
            required = listOf("sign"),
        ),
    )
}
