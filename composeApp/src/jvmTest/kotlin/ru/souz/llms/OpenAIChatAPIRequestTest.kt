package ru.souz.llms

import io.mockk.every
import io.mockk.mockk
import ru.souz.db.SettingsProvider
import ru.souz.llms.openai.OpenAIChatAPI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OpenAIChatAPIRequestTest {

    @Test
    fun `buildChatRequest resolves OpenAI model by enum name and includes tool choice`() {
        val api = createApi()
        val request = invokeBuildChatRequest(
            api = api,
            body = LLMRequest.Chat(
                model = LLMModel.OpenAIGpt5Mini.name,
                maxTokens = 256,
                messages = listOf(
                    LLMRequest.Message(role = LLMMessageRole.user, content = "Get horoscope"),
                ),
                functions = listOf(function("get_horoscope")),
            ),
            stream = false,
        )

        assertEquals(LLMModel.OpenAIGpt5Mini.alias, request["model"])
        assertEquals("auto", request["tool_choice"])
        val tools = request["tools"] as List<*>
        assertEquals(1, tools.size)
    }

    @Test
    fun `buildChatRequest maps tool response to role tool with call id`() {
        val api = createApi()
        val request = invokeBuildChatRequest(
            api = api,
            body = LLMRequest.Chat(
                model = LLMModel.OpenAIGpt5Nano.alias,
                maxTokens = 256,
                messages = listOf(
                    LLMRequest.Message(
                        role = LLMMessageRole.function,
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
            body = LLMRequest.Chat(
                model = LLMModel.OpenAIGpt5Nano.alias,
                maxTokens = 256,
                messages = listOf(
                    LLMRequest.Message(
                        role = LLMMessageRole.assistant,
                        content = """{"name":"get_horoscope","arguments":{"sign":"Taurus"}}""",
                        functionsStateId = "call_123",
                    ),
                    LLMRequest.Message(
                        role = LLMMessageRole.assistant,
                        content = "null",
                    ),
                    LLMRequest.Message(
                        role = LLMMessageRole.function,
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
            body = LLMRequest.Chat(
                model = LLMModel.OpenAIGpt5Nano.alias,
                maxTokens = 256,
                messages = listOf(
                    LLMRequest.Message(
                        role = LLMMessageRole.assistant,
                        content = """{"name":"get_horoscope","arguments":{"sign":"Taurus"}}""",
                        functionsStateId = "call_123",
                    ),
                    LLMRequest.Message(
                        role = LLMMessageRole.assistant,
                        content = "Plan: running the tool now",
                    ),
                    LLMRequest.Message(
                        role = LLMMessageRole.function,
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
            body = LLMRequest.Chat(
                model = LLMModel.OpenAIGpt5Nano.alias,
                maxTokens = 256,
                messages = listOf(
                    LLMRequest.Message(
                        role = LLMMessageRole.assistant,
                        content = """{"name":"tool_a","arguments":{"x":"1"}}""",
                        functionsStateId = "call_a",
                    ),
                    LLMRequest.Message(
                        role = LLMMessageRole.assistant,
                        content = """{"name":"tool_b","arguments":{"y":"2"}}""",
                        functionsStateId = "call_b",
                    ),
                    LLMRequest.Message(
                        role = LLMMessageRole.function,
                        content = """{"ok":true}""",
                        functionsStateId = "call_a",
                        name = "tool_a",
                    ),
                    LLMRequest.Message(
                        role = LLMMessageRole.function,
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
    fun `buildChatRequest keeps later user message when assistant tool call cannot be resolved`() {
        val api = createApi()
        val request = invokeBuildChatRequest(
            api = api,
            body = LLMRequest.Chat(
                model = LLMModel.OpenAIGpt5Nano.alias,
                maxTokens = 256,
                messages = listOf(
                    LLMRequest.Message(
                        role = LLMMessageRole.assistant,
                        content = """{"name":"get_horoscope","arguments":{"sign":"Taurus"}}""",
                        functionsStateId = "call_missing",
                    ),
                    LLMRequest.Message(
                        role = LLMMessageRole.user,
                        content = "continue",
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
        assertEquals("""{"name":"get_horoscope","arguments":{"sign":"Taurus"}}""", messages[0]["content"])
        assertEquals(null, messages[0]["tool_calls"])
        assertEquals("user", messages[1]["role"])
        assertEquals("continue", messages[1]["content"])
    }

    @Test
    fun `buildChatRequest maps repeated same-name function results to tool calls in order`() {
        val api = createApi()
        val request = invokeBuildChatRequest(
            api = api,
            body = LLMRequest.Chat(
                model = LLMModel.OpenAIGpt5Nano.alias,
                maxTokens = 256,
                messages = listOf(
                    LLMRequest.Message(
                        role = LLMMessageRole.assistant,
                        content = """{"name":"get_weather","arguments":{"city":"Berlin"}}""",
                        functionsStateId = "call_a",
                    ),
                    LLMRequest.Message(
                        role = LLMMessageRole.assistant,
                        content = """{"name":"get_weather","arguments":{"city":"Paris"}}""",
                        functionsStateId = "call_b",
                    ),
                    LLMRequest.Message(
                        role = LLMMessageRole.function,
                        content = """{"temp":10}""",
                        name = "get_weather",
                    ),
                    LLMRequest.Message(
                        role = LLMMessageRole.function,
                        content = """{"temp":20}""",
                        name = "get_weather",
                    ),
                ),
                functions = listOf(function("get_weather")),
            ),
            stream = false,
        )

        @Suppress("UNCHECKED_CAST")
        val messages = request["messages"] as List<Map<String, Any?>>
        assertEquals(3, messages.size)
        assertEquals("assistant", messages[0]["role"])
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
            requestModel = LLMModel.OpenAIGpt5Nano.alias,
        )

        val chat = response as LLMResponse.Chat.Ok
        assertEquals(1, chat.choices.size)
        assertEquals("get_horoscope", chat.choices.first().message.functionCall?.name)
        assertTrue(chat.choices.none { it.message.content == "null" })
    }

    @Test
    fun `buildEmbeddingsRequest includes float encoding format`() {
        val api = createApi()
        val request = invokeBuildEmbeddingsRequest(
            api = api,
            body = LLMRequest.Embeddings(
                model = "Embeddings",
                input = listOf("hello"),
            ),
        )

        assertEquals("float", request["encoding_format"])
        assertEquals("text-embedding-3-small", request["model"])
    }

    @Test
    fun `buildChatRequest includes items schema for array properties`() {
        val api = createApi()
        val request = invokeBuildChatRequest(
            api = api,
            body = LLMRequest.Chat(
                model = LLMModel.OpenAIGpt5Nano.alias,
                maxTokens = 256,
                messages = listOf(
                    LLMRequest.Message(role = LLMMessageRole.user, content = "run tool"),
                ),
                functions = listOf(
                    LLMRequest.Function(
                        name = "PresentationCreate",
                        description = "Create slides",
                        parameters = LLMRequest.Parameters(
                            type = "object",
                            properties = mapOf(
                                "slides" to LLMRequest.Property(
                                    type = "array",
                                    description = "Array of slide objects",
                                ),
                            ),
                            required = listOf("slides"),
                        ),
                    )
                ),
            ),
            stream = false,
        )

        @Suppress("UNCHECKED_CAST")
        val tools = request["tools"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val function = tools.first()["function"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val parameters = function["parameters"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val properties = parameters["properties"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val slides = properties["slides"] as Map<String, Any?>
        assertEquals("array", slides["type"])
        assertNotNull(slides["items"])
    }

    @Test
    fun `stream accumulator emits distinct indexes for multiple tool calls in one choice`() {
        val classLoader = OpenAIChatAPI::class.java.classLoader
        val clazz = Class.forName("ru.souz.llms.openai.OpenAiStreamAccumulator", true, classLoader)
        val ctor = clazz.getDeclaredConstructor()
        ctor.isAccessible = true
        val accumulator = ctor.newInstance()
        val processChunk = clazz.getDeclaredMethod("processChunk", com.fasterxml.jackson.databind.JsonNode::class.java)
        processChunk.isAccessible = true

        val node = restJsonMapper.readTree(
            """
                {
                  "choices": [
                    {
                      "index": 0,
                      "delta": {
                        "role": "assistant",
                        "tool_calls": [
                          {
                            "index": 0,
                            "id": "call_a",
                            "function": {
                              "name": "tool_a",
                              "arguments": "{\"x\":1}"
                            }
                          },
                          {
                            "index": 1,
                            "id": "call_b",
                            "function": {
                              "name": "tool_b",
                              "arguments": "{\"y\":2}"
                            }
                          }
                        ]
                      },
                      "finish_reason": "tool_calls"
                    }
                  ]
                }
            """.trimIndent()
        )

        @Suppress("UNCHECKED_CAST")
        val choices = processChunk.invoke(accumulator, node) as List<LLMResponse.Choice>
        val toolChoices = choices.filter { it.message.functionCall != null }
        assertEquals(2, toolChoices.size)
        assertNotEquals(toolChoices[0].index, toolChoices[1].index)
        assertEquals(setOf("call_a", "call_b"), toolChoices.mapNotNull { it.message.functionsStateId }.toSet())
    }

    private fun createApi(): OpenAIChatAPI {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.openaiKey } returns "test-key"
        every { settingsProvider.requestTimeoutMillis } returns 1_000L
        every { settingsProvider.gigaModel } returns LLMModel.OpenAIGpt5Nano

        val tokenLogging = mockk<TokenLogging>(relaxed = true)
        return OpenAIChatAPI(settingsProvider, tokenLogging)
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeBuildChatRequest(
        api: OpenAIChatAPI,
        body: LLMRequest.Chat,
        stream: Boolean,
    ): Map<String, Any> {
        val method = OpenAIChatAPI::class.java.getDeclaredMethod(
            "buildChatRequest",
            LLMRequest.Chat::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(api, body, stream) as Map<String, Any>
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeBuildEmbeddingsRequest(
        api: OpenAIChatAPI,
        body: LLMRequest.Embeddings,
    ): Map<String, Any> {
        val method = OpenAIChatAPI::class.java.getDeclaredMethod(
            "buildEmbeddingsRequest",
            LLMRequest.Embeddings::class.java,
        )
        method.isAccessible = true
        return method.invoke(api, body) as Map<String, Any>
    }

    private fun invokeParseCompletionsResponse(
        api: OpenAIChatAPI,
        text: String,
        requestModel: String,
    ): LLMResponse.Chat {
        val method = OpenAIChatAPI::class.java.getDeclaredMethod(
            "parseCompletionsResponse",
            String::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(api, text, requestModel) as LLMResponse.Chat
    }

    private fun function(name: String): LLMRequest.Function = LLMRequest.Function(
        name = name,
        description = "$name description",
        parameters = LLMRequest.Parameters(
            type = "object",
            properties = mapOf(
                "sign" to LLMRequest.Property(type = "string", description = "Sign"),
            ),
            required = listOf("sign"),
        ),
    )
}
