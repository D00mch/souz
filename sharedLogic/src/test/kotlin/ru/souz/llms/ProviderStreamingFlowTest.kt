package ru.souz.llms

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.sse.DefaultClientSSESession
import io.ktor.client.plugins.sse.SSECapability
import io.ktor.client.plugins.sse.SSEClientContent
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import ru.souz.db.SettingsProvider
import ru.souz.llms.anthropic.AnthropicChatAPI
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.llms.openai.OpenAICompatibleChatAPI
import kotlin.test.Test
import kotlin.test.assertEquals

class ProviderStreamingFlowTest {
    @Test
    fun `compatible providers share text tool and terminal usage streaming`() = runTest {
        val cases = listOf(
            Triple(LlmProvider.OPENAI, LLMModel.OpenAIGpt5Mini.alias, "openai-key"),
            Triple(LlmProvider.AI_TUNNEL, LLMModel.AiTunnelGpt54Mini.alias, "tunnel-key"),
            Triple(LlmProvider.QWEN, LLMModel.QwenFlash.alias, "qwen-key"),
        )

        cases.forEach { (provider, model, apiKey) ->
            val client = streamClient(COMPATIBLE_STREAM)
            val api = OpenAICompatibleChatAPI(provider, settings(), client, apiKey)
            val chunks = api.messageStream(chatRequest(model))
                .filterIsInstance<LLMResponse.Chat.Ok>()
                .toList()

            assertEquals("Hi", chunks.flatMap { it.choices }.first().message.content)
            val toolChoice = chunks.flatMap { it.choices }.single { it.message.functionCall != null }
            assertEquals("lookup", toolChoice.message.functionCall?.name)
            assertEquals(mapOf("city" to "Paris"), toolChoice.message.functionCall?.arguments)
            assertEquals(LLMResponse.FinishReason.function_call, toolChoice.finishReason)
            assertEquals(LLMResponse.Usage(7, 3, 10, 0), chunks.last().usage)
            assertEquals(emptyList(), chunks.last().choices)
            client.close()
        }
    }

    @Test
    fun `Anthropic flow emits cumulative terminal usage`() = runTest {
        val client = streamClient(ANTHROPIC_STREAM)
        val api = AnthropicChatAPI(settings(), client, apiKey = "anthropic-key")

        val chunks = api.messageStream(chatRequest(LLMModel.AnthropicHaiku45.alias))
            .filterIsInstance<LLMResponse.Chat.Ok>()
            .toList()

        assertEquals(LLMResponse.Usage(9, 5, 14, 4), chunks.last().usage)
        assertEquals(LLMResponse.FinishReason.stop, chunks.last().choices.single().finishReason)
        client.close()
    }

    private fun streamClient(stream: String): HttpClient {
        val engineConfig = MockEngineConfig().apply {
            addHandler {
                respond(
                    content = stream,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
                )
            }
        }
        @OptIn(InternalAPI::class)
        @Suppress("DEPRECATION")
        val engine = object : MockEngine(engineConfig) {
            override val supportedCapabilities = super.supportedCapabilities + SSECapability

            override suspend fun execute(data: HttpRequestData): HttpResponseData {
                val response = super.execute(data)
                val content = data.body as? SSEClientContent ?: return response
                val session = DefaultClientSSESession(content, response.body as ByteReadChannel)
                return HttpResponseData(
                    statusCode = response.statusCode,
                    requestTime = response.requestTime,
                    headers = response.headers,
                    version = response.version,
                    body = session,
                    callContext = response.callContext,
                )
            }
        }
        return HttpClient(engine) {
            providerHttpClientDefaults()
        }
    }

    private fun settings(): SettingsProvider = mockk<SettingsProvider>(relaxed = true) {
        every { requestTimeoutMillis } returns 1_000L
        every { gigaModel } returns LLMModel.AnthropicHaiku45
        every { openaiBaseUrl } returns "https://openai.test/v1"
    }

    private fun chatRequest(model: String) = LLMRequest.Chat(
        model = model,
        messages = listOf(LLMRequest.Message(LLMMessageRole.user, "hello")),
    )

    private companion object {
        val COMPATIBLE_STREAM = """
            data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"Hi"},"finish_reason":null}],"created":1,"model":"gpt-test","usage":null}

            data:{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"lookup","arguments":"{\"city\":"}}]},"finish_reason":null}],"created":1,"model":"gpt-test","usage":null}

            data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"Paris\"}"}}]},"finish_reason":"tool_calls"}],"created":1,"model":"gpt-test","usage":null}

            data: {"choices":[],"created":1,"model":"gpt-test","usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}

            data: [DONE]

        """.trimIndent() + "\n\n"

        val ANTHROPIC_STREAM = """
            data: {"type":"message_start","message":{"model":"claude-test","usage":{"input_tokens":7,"cache_creation_input_tokens":2,"cache_read_input_tokens":4,"output_tokens":0}}}

            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}

            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}

        """.trimIndent() + "\n\n"
    }
}
