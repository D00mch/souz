package ru.souz.llms

import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.souz.db.SettingsProvider
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.llms.openai.OpenAICompatibleChatAPI
import ru.souz.llms.openai.OpenAIEndpoint
import ru.souz.llms.tunnel.AiTunnelVoiceAPI

data class CompatibleProviderCase(
    val provider: LlmProvider,
    val expectedBaseUrl: String,
    val expectedMaxTokensField: String,
    val sendsTemperature: Boolean,
    val sendsStreamUsage: Boolean,
    val embeddingEncodingFormat: String?,
)

@OptIn(ExperimentalCoroutinesApi::class)
class OpenAICompatibleChatAPIRequestTest {

    @Test
    fun `AiTunnel unary operations recover on their third attempt`() = runTest {
        for (operation in listOf("chat", "embeddings", "voice")) {
            var calls = 0
            retryClient {
                when (++calls) {
                    1 -> throw IOException("offline")
                    2 -> respond("busy", HttpStatusCode.ServiceUnavailable)
                    else -> respond(when (operation) {
                        "chat" -> CHAT_REPLY
                        "embeddings" -> EMBEDDINGS_REPLY
                        else -> """{"text":"accepted"}"""
                    }, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }.use { client ->
                val api = createApi(client, LlmProvider.AI_TUNNEL)
                when (operation) {
                    "chat" -> assertIs<LLMResponse.Chat.Ok>(api.message(RETRY_CHAT), operation)
                    "embeddings" -> assertIs<LLMResponse.Embeddings.Ok>(api.embeddings(LLMRequest.Embeddings("model", listOf("hello"))), operation)
                    else -> assertEquals("accepted", AiTunnelVoiceAPI(mockk(relaxed = true) {
                        every { aiTunnelKey } returns "test-key"
                        every { regionProfile } returns "ru"
                        every { requestTimeoutMillis } returns 1_000L
                        every { voiceRecognitionModel } returns VoiceRecognitionModel.AiTunnelGpt4oTranscribe
                    }, client).recognize(byteArrayOf(0, 0)), operation)
                }
                assertEquals(3, calls, operation)
            }
        }
    }

    @Test
    fun `AiTunnel streams retry before output but never replay text tools or usage`() = runTest {
        val chunks = mapOf(
            "before output" to null,
            "text" to """{"choices":[{"index":0,"delta":{"content":"partial"}}]}""",
            "tool" to TOOL_CHUNK,
            "usage" to """{"usage":{"total_tokens":1}}""",
        )
        for ((case, chunk) in chunks) {
            var calls = 0
            retryClient {
                calls++
                respond(when {
                    chunk != null -> "data: $chunk\n\n" // Premature EOF after output is terminal.
                    calls == 1 -> "data: {broken\n\n"
                    calls == 2 -> "data: {\"error\":{\"message\":\"busy\"}}\n\n"
                    else -> "data: $CHAT_REPLY\n\ndata: [DONE]\n\n"
                })
            }.use { client ->
                val responses = createApi(client, LlmProvider.AI_TUNNEL).messageStream(RETRY_CHAT).toList()
                assertIs<LLMResponse.Chat.Ok>(responses.first(), case)
                if (chunk != null) assertFalse(assertIs<LLMResponse.Chat.Error>(responses.last(), case).retryable, case)
                assertEquals(if (chunk == null) 1 else 2, responses.size, case)
                assertEquals(if (chunk == null) 3 else 1, calls, case)
            }
        }
    }

    @Test
    fun `AiTunnel request and backoff cancellation start no further attempts`() = runTest {
        for (duringRequest in listOf(true, false)) {
            var calls = 0
            retryClient {
                calls++
                if (duringRequest) throw CancellationException("cancelled")
                respond("busy", HttpStatusCode.ServiceUnavailable)
            }.use { client ->
                val request = async { createApi(client, LlmProvider.AI_TUNNEL).messageStream(RETRY_CHAT).toList() }
                runCurrent()
                if (duringRequest) assertFailsWith<CancellationException> { request.await() } else request.cancelAndJoin()
                assertEquals(1, calls, "duringRequest=$duringRequest")
            }
        }
    }

    @Test
    fun `AiTunnel collector failure propagates without replay`() = runTest {
        var calls = 0
        retryClient { calls++; respond("data: $CHAT_REPLY\n\ndata: [DONE]\n\n") }.use { client ->
            assertEquals("consumer failed", assertFailsWith<IllegalStateException> {
                createApi(client, LlmProvider.AI_TUNNEL).messageStream(RETRY_CHAT).collect { error("consumer failed") }
            }.message)
            assertEquals(1, calls)
        }
    }

    private fun TestScope.retryClient(handler: MockRequestHandler) = HttpClient(MockEngine) {
        providerHttpClientDefaults()
        engine { dispatcher = StandardTestDispatcher(testScheduler); addHandler(handler) }
    }

    @Test
    fun `provider matrix applies compatible protocol differences`() = runTest {
        compatibleProviderCases.forEach { case ->
            val engine = MockEngine { request ->
                val embeddings = request.url.encodedPath.endsWith("/embeddings")
                respond(
                    content = if (embeddings) EMBEDDINGS_REPLY else "data: $CHAT_REPLY\n\ndata: [DONE]\n\n",
                    headers = headersOf(
                        HttpHeaders.ContentType,
                        (if (embeddings) ContentType.Application.Json else ContentType.Text.EventStream).toString(),
                    ),
                )
            }
            HttpClient(engine) { providerHttpClientDefaults() }.use { client ->
                val api = createApi(client, provider = case.provider)
                val responses = api.messageStream(
                    LLMRequest.Chat(
                        model = modelFor(case.provider),
                        messages = listOf(LLMRequest.Message(LLMMessageRole.user, "hello")),
                        functions = listOf(function("get_horoscope")),
                        temperature = 0.4f,
                        maxTokens = 256,
                        reasoningEffort = "low",
                    ),
                ).toList()
                assertIs<LLMResponse.Chat.Ok>(responses.single())
                assertIs<LLMResponse.Embeddings.Ok>(
                    api.embeddings(LLMRequest.Embeddings(model = "Embeddings", input = listOf("hello"))),
                )
            }
            val chatRequest = restJsonMapper.readValue<Map<String, Any>>(engine.requestHistory[0].body.toByteArray())
            val embeddingsRequest = restJsonMapper.readValue<Map<String, Any>>(engine.requestHistory[1].body.toByteArray())

            assertEquals("${case.expectedBaseUrl}/chat/completions", engine.requestHistory[0].url.toString())
            assertEquals("${case.expectedBaseUrl}/embeddings", engine.requestHistory[1].url.toString())
            assertEquals(modelFor(case.provider), chatRequest["model"])
            assertEquals(256, chatRequest[case.expectedMaxTokensField])
            assertEquals(case.sendsTemperature, "temperature" in chatRequest)
            assertEquals(case.sendsStreamUsage, "stream_options" in chatRequest)
            assertEquals(case.embeddingEncodingFormat, embeddingsRequest["encoding_format"])
            assertEquals(case.provider == LlmProvider.QWEN, chatRequest["parallel_tool_calls"] == true)
            assertEquals(if (case.provider == LlmProvider.OPENAI) "low" else null, chatRequest["reasoning_effort"])
        }
    }

    @Test
    fun `request overrides preserve configured output limit and structural fields`() = runTest {
        val request = captureChatRequest(
            requestParameters =
                """{"model":"ignored","messages":[],"stream":true,"max_completion_tokens":512,"reasoning_effort":"low"}""",
            body = LLMRequest.Chat(
                model = "provider-summary-model",
                messages = listOf(LLMRequest.Message(LLMMessageRole.user, "hello")),
                maxTokens = 0,
            ),
        )

        assertEquals("provider-summary-model", request["model"])
        assertEquals(false, request["stream"])
        assertEquals(1, (request["messages"] as List<*>).size)
        assertEquals(512, request["max_completion_tokens"])
        assertEquals("low", request["reasoning_effort"])
    }

    @Test
    fun `zero max tokens omits generated output limit`() = runTest {
        val request = captureChatRequest(
            body = LLMRequest.Chat(
                model = LLMModel.OpenAIGpt5Mini.alias,
                messages = listOf(LLMRequest.Message(LLMMessageRole.user, "hello")),
                maxTokens = 0,
            ),
        )

        assertTrue("max_completion_tokens" !in request)
    }

    @Test
    fun `constructor rejects providers outside the compatible set`() {
        assertFailsWith<IllegalArgumentException> {
            createApi(mockk<HttpClient>(), provider = LlmProvider.ANTHROPIC)
        }
    }

    @Test
    fun `stream request omits usage option for custom compatible endpoints`() = runTest {
        val request = captureChatRequest(
            openaiBaseUrl = "https://example.test/openai/v1/",
            body = LLMRequest.Chat(
                model = LLMModel.OpenAICompatibleCustom.alias,
                messages = listOf(LLMRequest.Message(LLMMessageRole.user, "hello")),
            ),
            stream = true,
        )

        assertEquals(null, request["stream_options"])
    }

    @Test
    fun `chat request includes tool choice`() = runTest {
        val request = captureChatRequest(
            body = LLMRequest.Chat(
                model = LLMModel.OpenAIGpt5Mini.alias,
                maxTokens = 256,
                messages = listOf(
                    LLMRequest.Message(role = LLMMessageRole.user, content = "Get horoscope"),
                ),
                functions = listOf(function("get_horoscope")),
            ),
        )

        assertEquals(LLMModel.OpenAIGpt5Mini.alias, request["model"])
        assertEquals(256, request["max_completion_tokens"])
        assertEquals("auto", request["tool_choice"])
        val tools = request["tools"] as List<*>
        assertEquals(1, tools.size)
    }

    @Test
    fun `OpenAI endpoint normalizes custom and equivalent official urls`() {
        assertEquals(
            "https://example.test/openai/v1/chat/completions",
            OpenAIEndpoint.from(" https://example.test/openai/v1/// ").endpoint("chat/completions"),
        )
        listOf(
            null,
            "https://api.openai.com/v1",
            " HTTPS://API.OPENAI.COM/v1/ ",
            "https://api.openai.com:443/v1///",
        ).forEach { baseUrl ->
            val endpoint = OpenAIEndpoint.from(baseUrl)
            assertTrue(endpoint.isOfficial)
            assertEquals("https://api.openai.com/v1/chat/completions", endpoint.endpoint("chat/completions"))
        }
        assertTrue(!OpenAIEndpoint.from("http://api.openai.com/v1").isOfficial)
        assertTrue(!OpenAIEndpoint.from("https://api.openai.com:444/v1").isOfficial)
        assertTrue(!OpenAIEndpoint.from("https://example.test/v1").isOfficial)
    }

    @Test
    fun `chat request maps tool response to role tool with call id`() = runTest {
        val request = captureChatRequest(
            body = LLMRequest.Chat(
                model = LLMModel.OpenAIGpt5Mini.alias,
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
        )

        @Suppress("UNCHECKED_CAST")
        val messages = request["messages"] as List<Map<String, Any?>>
        assertEquals(1, messages.size)
        assertEquals("tool", messages.first()["role"])
        assertEquals("call_123", messages.first()["tool_call_id"])
        assertNotNull(messages.first()["content"])
    }

    @Test
    fun `chat request skips null placeholder assistant message between tool call and tool result`() = runTest {
        val request = captureChatRequest(
            body = LLMRequest.Chat(
                model = LLMModel.OpenAIGpt5Mini.alias,
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
    fun `chat request moves regular assistant text after pending tool result`() = runTest {
        val request = captureChatRequest(
            body = LLMRequest.Chat(
                model = LLMModel.OpenAIGpt5Mini.alias,
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
    fun `chat request merges consecutive assistant tool calls into one OpenAI assistant message`() = runTest {
        val request = captureChatRequest(
            body = LLMRequest.Chat(
                model = LLMModel.OpenAIGpt5Mini.alias,
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
    fun `chat request serializes image attachments as multimodal content parts`() = runTest {
        val imageDataUrl = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB"
        val request = captureChatRequest(
            body = LLMRequest.Chat(
                model = LLMModel.OpenAIGpt5Mini.alias,
                maxTokens = 256,
                messages = listOf(
                    LLMRequest.Message(
                        role = LLMMessageRole.user,
                        content = "Describe the image",
                        attachments = listOf(imageDataUrl),
                    ),
                ),
            ),
        )

        @Suppress("UNCHECKED_CAST")
        val messages = request["messages"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val content = messages.single()["content"] as List<Map<String, Any?>>

        assertEquals("user", messages.single()["role"])
        assertEquals("text", content[0]["type"])
        assertEquals("Describe the image", content[0]["text"])
        assertEquals("image_url", content[1]["type"])
        @Suppress("UNCHECKED_CAST")
        val imageUrl = content[1]["image_url"] as Map<String, String>
        assertEquals(imageDataUrl, imageUrl["url"])
    }

    @Test
    fun `chat request preserves https image attachments as multimodal content parts`() = runTest {
        val imageUrl = "https://example.com/image.png"
        val request = captureChatRequest(
            body = LLMRequest.Chat(
                model = LLMModel.OpenAIGpt5Mini.alias,
                messages = listOf(
                    LLMRequest.Message(
                        role = LLMMessageRole.user,
                        content = "Describe the image",
                        attachments = listOf(imageUrl),
                    ),
                ),
            ),
        )

        @Suppress("UNCHECKED_CAST")
        val messages = request["messages"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val content = messages.single()["content"] as List<Map<String, Any?>>

        assertEquals("image_url", content[1]["type"])
        @Suppress("UNCHECKED_CAST")
        val encodedImageUrl = content[1]["image_url"] as Map<String, String>
        assertEquals(imageUrl, encodedImageUrl["url"])
    }

    @Test
    fun `chat request keeps later user message when assistant tool call cannot be resolved`() = runTest {
        val request = captureChatRequest(
            body = LLMRequest.Chat(
                model = LLMModel.OpenAIGpt5Mini.alias,
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
    fun `chat request maps repeated same-name function results to tool calls in order`() = runTest {
        val request = captureChatRequest(
            body = LLMRequest.Chat(
                model = LLMModel.OpenAIGpt5Mini.alias,
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
    fun `chat response ignores null content for tool calls`() = runTest {
        val response = completionResponse(
            text = """
                {
                  "created": 1739900000,
                  "model": "gpt-5-mini",
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
            requestModel = LLMModel.OpenAIGpt5Mini.alias,
        )

        val chat = response as LLMResponse.Chat.Ok
        assertEquals(1, chat.choices.size)
        assertEquals("get_horoscope", chat.choices.first().message.functionCall?.name)
        assertTrue(chat.choices.none { it.message.content == "null" })
    }

    @Test
    fun `chat response accepts compatible usage names and finish reasons`() = runTest {
        val response = completionResponse(
            provider = LlmProvider.QWEN,
            text = """
                {
                  "created": 1739900000,
                  "model": "qwen-flash",
                  "choices": [
                    {
                      "index": 0,
                      "finish_reason": "max_tokens",
                      "message": {"role": "assistant", "content": "partial"}
                    }
                  ],
                  "usage": {
                    "input_tokens": 9,
                    "output_tokens": 4,
                    "prompt_tokens_details": {"cached_tokens": 3}
                  }
                }
            """.trimIndent(),
            requestModel = LLMModel.QwenFlash.alias,
        ) as LLMResponse.Chat.Ok

        assertEquals(LLMResponse.FinishReason.length, response.choices.single().finishReason)
        assertEquals(LLMResponse.Usage(9, 4, 13, 3), response.usage)
    }

    @Test
    fun `embeddings request includes float encoding format`() = runTest {
        val engine = MockEngine {
            respond(EMBEDDINGS_REPLY, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        HttpClient(engine) { providerHttpClientDefaults() }.use { client ->
            assertIs<LLMResponse.Embeddings.Ok>(
                createApi(client).embeddings(LLMRequest.Embeddings(model = "Embeddings", input = listOf("hello"))),
            )
        }
        val request = restJsonMapper.readValue<Map<String, Any>>(engine.requestHistory.single().body.toByteArray())

        assertEquals("float", request["encoding_format"])
        assertEquals("text-embedding-3-small", request["model"])
    }

    @Test
    fun `chat request includes items schema for array properties`() = runTest {
        val request = captureChatRequest(
            body = LLMRequest.Chat(
                model = LLMModel.OpenAIGpt5Mini.alias,
                maxTokens = 256,
                messages = listOf(
                    LLMRequest.Message(role = LLMMessageRole.user, content = "run tool"),
                ),
                functions = listOf(
                    LLMRequest.Function(
                        name = "BatchProcess",
                        description = "Process records",
                        parameters = LLMRequest.Parameters(
                            type = "object",
                            properties = mapOf(
                                "records" to LLMRequest.Property(
                                    type = "array",
                                    description = "Array of record objects",
                                ),
                            ),
                            required = listOf("records"),
                        ),
                    )
                ),
            ),
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
        val records = properties["records"] as Map<String, Any?>
        assertEquals("array", records["type"])
        assertNotNull(records["items"])
    }

    @Test
    fun `stream emits distinct indexes for multiple tool calls in one choice`() = runTest {
        val chunk =
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
        val engine = MockEngine {
            respond(
                content = "data: ${restJsonMapper.readTree(chunk)}\n\ndata: [DONE]\n\n",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
            )
        }
        HttpClient(engine) { providerHttpClientDefaults() }.use { client ->
            val responses = createApi(client).messageStream(
                LLMRequest.Chat(
                    model = LLMModel.OpenAIGpt5Mini.alias,
                    messages = listOf(LLMRequest.Message(LLMMessageRole.user, "run tools")),
                ),
            ).toList()
            val choices = assertIs<LLMResponse.Chat.Ok>(responses.single()).choices
            val toolChoices = choices.filter { it.message.functionCall != null }
            assertEquals(2, toolChoices.size)
            assertNotEquals(toolChoices[0].index, toolChoices[1].index)
            assertEquals(setOf("call_a", "call_b"), toolChoices.mapNotNull { it.message.functionsStateId }.toSet())
        }
    }

    private fun createApi(
        client: HttpClient,
        provider: LlmProvider = LlmProvider.OPENAI,
        openaiBaseUrl: String? = null,
        requestParameters: String? = null,
    ): OpenAICompatibleChatAPI {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.openaiKey } returns "test-key"
        every { settingsProvider.aiTunnelKey } returns "test-key"
        every { settingsProvider.qwenChatKey } returns "test-key"
        every { settingsProvider.openaiBaseUrl } returns openaiBaseUrl
        every { settingsProvider.requestTimeoutMillis } returns 1_000L

        return OpenAICompatibleChatAPI(
            provider = provider,
            settingsProvider = settingsProvider,
            client = client,
            requestParameters = requestParameters,
        )
    }

    private suspend fun captureChatRequest(
        body: LLMRequest.Chat,
        stream: Boolean = false,
        openaiBaseUrl: String? = null,
        requestParameters: String? = null,
    ): Map<String, Any> {
        val engine = MockEngine {
            respond(
                content = if (stream) "data: $CHAT_REPLY\n\ndata: [DONE]\n\n" else CHAT_REPLY,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    (if (stream) ContentType.Text.EventStream else ContentType.Application.Json).toString(),
                ),
            )
        }
        HttpClient(engine) { providerHttpClientDefaults() }.use { client ->
            val api = createApi(client, openaiBaseUrl = openaiBaseUrl, requestParameters = requestParameters)
            val response = if (stream) api.messageStream(body).toList().single() else api.message(body)
            assertIs<LLMResponse.Chat.Ok>(response)
        }
        return restJsonMapper.readValue(engine.requestHistory.single().body.toByteArray())
    }

    private suspend fun completionResponse(
        text: String,
        requestModel: String,
        provider: LlmProvider = LlmProvider.OPENAI,
    ): LLMResponse.Chat {
        val engine = MockEngine {
            respond(text, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        return HttpClient(engine) { providerHttpClientDefaults() }.use { client ->
            createApi(client, provider).message(
                LLMRequest.Chat(requestModel, listOf(LLMRequest.Message(LLMMessageRole.user, "hello"))),
            )
        }
    }

    private fun modelFor(provider: LlmProvider): String = when (provider) {
        LlmProvider.OPENAI -> LLMModel.OpenAIGpt5Mini.alias
        LlmProvider.AI_TUNNEL -> LLMModel.AiTunnelGpt54Mini.alias
        LlmProvider.QWEN -> LLMModel.QwenFlash.alias
        else -> error("Unsupported provider: $provider")
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

    private companion object {
        val RETRY_CHAT = LLMRequest.Chat("model", listOf(LLMRequest.Message(LLMMessageRole.user, "hello")))
        const val TOOL_CHUNK = """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call","function":{"name":"lookup","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
        const val CHAT_REPLY =
            """{"choices":[{"index":0,"message":{"role":"assistant","content":"Hi"},"delta":{"content":"Hi"},"finish_reason":"stop"}],"created":1,"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
        const val EMBEDDINGS_REPLY = """{"data":[{"embedding":[0.1],"index":0}],"model":"embeddings","object":"list"}"""

        val compatibleProviderCases = listOf(
            CompatibleProviderCase(
                provider = LlmProvider.OPENAI,
                expectedBaseUrl = OpenAIEndpoint.DEFAULT_BASE_URL,
                expectedMaxTokensField = "max_completion_tokens",
                sendsTemperature = false,
                sendsStreamUsage = true,
                embeddingEncodingFormat = "float",
            ),
            CompatibleProviderCase(
                provider = LlmProvider.AI_TUNNEL,
                expectedBaseUrl = "https://api.aitunnel.ru/v1",
                expectedMaxTokensField = "max_tokens",
                sendsTemperature = true,
                sendsStreamUsage = true,
                embeddingEncodingFormat = null,
            ),
            CompatibleProviderCase(
                provider = LlmProvider.QWEN,
                expectedBaseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
                expectedMaxTokensField = "max_tokens",
                sendsTemperature = true,
                sendsStreamUsage = true,
                embeddingEncodingFormat = null,
            ),
        )
    }
}
