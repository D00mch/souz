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
import ru.souz.llms.openai.OpenAIChatAPI
import ru.souz.llms.qwen.QwenChatAPI
import ru.souz.llms.tunnel.AiTunnelChatAPI
import kotlin.test.Test
import kotlin.test.assertEquals

class ProviderStreamingFlowTest {
    @Test
    fun `OpenAI-shaped flows emit their terminal usage-only chunk`() = runTest {
        val cases: List<Pair<String, (HttpClient) -> LLMChatAPI>> = listOf(
            LLMModel.OpenAIGpt5Nano.alias to { client ->
                OpenAIChatAPI(settings(), client, apiKey = "openai-key")
            },
            LLMModel.AiTunnelGpt5Nano.alias to { client ->
                AiTunnelChatAPI(settings(), client, apiKey = "tunnel-key")
            },
        )

        cases.forEach { (model, apiFactory) ->
            val client = streamClient(OPEN_AI_STREAM)
            val chunks = apiFactory(client).messageStream(chatRequest(model))
                .filterIsInstance<LLMResponse.Chat.Ok>()
                .toList()

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

    @Test
    fun `Qwen flow emits its terminal usage-only event`() = runTest {
        val client = streamClient(QWEN_STREAM)
        val api = QwenChatAPI(settings(), client, apiKey = "qwen-key")

        val chunks = api.messageStream(chatRequest("qwen-test"))
            .filterIsInstance<LLMResponse.Chat.Ok>()
            .toList()

        assertEquals(LLMResponse.Usage(7, 3, 10, 0), chunks.last().usage)
        assertEquals(emptyList(), chunks.last().choices)
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
        val OPEN_AI_STREAM = """
            data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"Hi"},"finish_reason":null}],"created":1,"model":"gpt-test","usage":null}

            data: {"choices":[],"created":1,"model":"gpt-test","usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}

            data: [DONE]

        """.trimIndent() + "\n\n"

        val ANTHROPIC_STREAM = """
            data: {"type":"message_start","message":{"model":"claude-test","usage":{"input_tokens":7,"cache_creation_input_tokens":2,"cache_read_input_tokens":4,"output_tokens":0}}}

            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}

            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}

        """.trimIndent() + "\n\n"

        val QWEN_STREAM = """
            data: {"output":{"choices":[{"index":0,"message":{"role":"assistant","content":"Hi"},"finish_reason":null}]},"usage":{"input_tokens":7,"output_tokens":1,"total_tokens":8}}

            data: {"output":{"choices":[]},"usage":{"input_tokens":7,"output_tokens":3,"total_tokens":10}}

        """.trimIndent() + "\n\n"
    }
}
