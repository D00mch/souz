package ru.souz.llms

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
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
import ru.souz.llms.runtime.SettingsRoutingLlmChatApi
import ru.souz.ToolLoopGraphBasedAgent
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.state.AgentSettings
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentTools
import ru.souz.llms.giga.toGiga
import ru.souz.tool.math.ToolCalculator
import ru.souz.tool.RuntimePassThroughToolsFilter
import ru.souz.tool.subagent.SubagentToolFactory
import ru.souz.tool.subagent.spawnAndWait
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class ProviderStreamingFlowTest {
    @Test
    fun `routers and adapters forward exact model IDs in both modes`() = runTest {
        val cases = listOf(
            LLMModel.OpenAIGpt52 to "Deployment/ID",
            LLMModel.AnthropicOpus45 to "ClAuDe-Custom/Case",
            LLMModel.AiTunnelGpt54Mini to "GigaChat-Custom/Deployment",
            LLMModel.QwenMax to " Raw/ID ",
            LLMModel.Qwen38_27B to "qwen3.8-27b",
        )
        cases.forEach { (selected, input) ->
            val settings = settings().also { every { it.gigaModel } returns selected }
            val anthropic = selected.provider == LlmProvider.ANTHROPIC
            listOf(false, true).forEach { streaming ->
                val content = when {
                    !streaming -> if (anthropic) ANTHROPIC_REPLY else COMPATIBLE_REPLY
                    anthropic -> ANTHROPIC_STREAM
                    else -> "data: $COMPATIBLE_REPLY\n\ndata: [DONE]\n\n"
                }
                listOf(false, true).forEach { routed ->
                    var requests = 0
                    streamClient(content, if (streaming) ContentType.Text.EventStream else ContentType.Application.Json) {
                        requests++
                        val payload = restJsonMapper.readTree(it.body.toByteArray())
                        assertEquals(input, payload["model"].asText())
                        assertEquals(streaming, payload["stream"].asBoolean())
                        assertFalse(payload.has("provider"))
                    }.use { client ->
                        val adapter = if (anthropic) AnthropicChatAPI(settings, client, "test-key")
                        else OpenAICompatibleChatAPI(selected.provider, settings, client, "test-key")
                        val api = if (routed) SettingsRoutingLlmChatApi(settings, mapOf(selected.provider to adapter)) else adapter
                        val request = chatRequest(input)
                        val response = if (streaming) api.messageStream(request).toList().last() else api.message(request)
                        assertIs<LLMResponse.Chat.Ok>(response)
                        assertEquals(1, requests)
                    }
                }
            }
        }
    }

    @Test
    fun `Qwen 3_8 routes two dependent tool calls through the desktop API in both modes`() = runTest {
        for (streaming in listOf(false, true)) {
            val settings = settings().also {
                every { it.useStreaming } returns streaming
                every { it.gigaModel } returns LLMModel.Qwen38_27B
                every { it.qwenChatKey } returns "qwen-fixture-key"
                every { it.openaiKey } returns "wrong-provider-key"
            }
            var requests = 0
            val engine = MockEngine { request ->
                val payload = restJsonMapper.readTree(request.body.toByteArray())
                assertEquals("https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions", request.url.toString())
                assertEquals("Bearer qwen-fixture-key", request.headers[HttpHeaders.Authorization])
                assertEquals("qwen3.8-27b", payload["model"].asText())
                assertEquals(false, payload["enable_thinking"]?.asBoolean())
                assertEquals(true, payload["parallel_tool_calls"].asBoolean())
                assertEquals(streaming, payload["stream"].asBoolean())
                assertFalse(payload.has("provider"))
                val messages = payload["messages"].toList()
                val results = messages.filter { it["role"].asText() == "tool" }
                assertEquals(requests, results.size)
                results.forEachIndexed { index, result ->
                    assertEquals("call_${index + 1}", result["tool_call_id"].asText())
                    assertEquals(if (index == 0) 3 else 12, restJsonMapper.readTree(result["content"].asText())["result"].asInt())
                    val assistant = messages.single { it["tool_calls"]?.first()?.get("id")?.asText() == "call_${index + 1}" }
                    assertEquals("Calculator", assistant["tool_calls"][0]["function"]["name"].asText())
                }
                requests++
                val message = if (requests <= 2) {
                    val expression = if (requests == 1) "1+2" else "3*4"
                    """{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_$requests","type":"function","function":{"name":"Calculator","arguments":"{\"expression\":\"$expression\"}"}}]}"""
                } else """{"role":"assistant","content":"12"}"""
                val finish = if (requests <= 2) "tool_calls" else "stop"
                val response = """{"created":1,"model":"qwen3.8-27b","choices":[{"index":0,"${if (streaming) "delta" else "message"}":$message,"finish_reason":"$finish"}],"usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}"""
                respond(
                    if (streaming) "data: $response\n\ndata: [DONE]\n\n" else response,
                    headers = headersOf(HttpHeaders.ContentType, if (streaming) "text/event-stream" else "application/json"),
                )
            }
            HttpClient(engine) { providerHttpClientDefaults() }.use { client ->
                val adapter = OpenAICompatibleChatAPI(LlmProvider.QWEN, settings, client)
                val router = SettingsRoutingLlmChatApi(settings, mapOf(LlmProvider.QWEN to adapter))
                val calculator = ToolCalculator().toGiga()
                val context = AgentContext(
                    input = "Compute 1+2, then multiply the result by 4.",
                    settings = AgentSettings("qwen3.8-27b", LlmProvider.QWEN, 0.7f, AgentTools(listOf(calculator))),
                    history = emptyList(), activeTools = listOf(calculator.fn), systemPrompt = "Use Calculator.",
                )
                val result = ToolLoopGraphBasedAgent(router, settings, maxTurns = 3).execute(context)
                assertEquals("12", result.output)
                assertEquals(listOf(3, 12), result.context.history.filter { it.role == LLMMessageRole.function }
                    .map { restJsonMapper.readTree(it.content)["result"].asInt() })
                assertEquals(3, requests)
            }
        }
    }

    @Test
    fun `desktop children reach configured providers with exact model IDs in both modes`() = runTest {
        listOf(false, true).forEach { streaming ->
            listOf(LlmProvider.OPENAI, LlmProvider.ANTHROPIC, LlmProvider.QWEN, LlmProvider.AI_TUNNEL).forEach { provider ->
                val settings = settings().also {
                    every { it.useStreaming } returns streaming
                    every { it.gigaModel } returns LLMModel.Max
                    every { it.openaiKey } returns "child-key"
                    every { it.anthropicKey } returns "child-key"
                    every { it.qwenChatKey } returns "child-key"
                    every { it.aiTunnelKey } returns "child-key"
                }
                val model = "GigaChat-Custom/Deployment"
                val anthropic = provider == LlmProvider.ANTHROPIC
                val content = if (streaming) {
                    if (anthropic) ANTHROPIC_STREAM else "data: $COMPATIBLE_REPLY\n\ndata: [DONE]\n\n"
                } else if (anthropic) ANTHROPIC_REPLY
                else COMPATIBLE_REPLY
                var requests = 0
                streamClient(content, if (streaming) ContentType.Text.EventStream else ContentType.Application.Json) { request ->
                    requests++
                    val payload = restJsonMapper.readTree(request.body.toByteArray())
                    assertEquals(model, payload["model"].asText())
                    assertFalse(payload.has("provider"))
                    assertEquals(streaming, payload["stream"].asBoolean())
                    assertEquals(if (anthropic) "child-key" else "Bearer child-key", request.headers[if (anthropic) "x-api-key" else HttpHeaders.Authorization])
                }.use { client ->
                    val providerApi = if (anthropic) AnthropicChatAPI(settings, client)
                    else OpenAICompatibleChatAPI(provider, settings, client)
                    val router = SettingsRoutingLlmChatApi(settings, mapOf(provider to providerApi))
                    val api = TokenLoggingChatApi(router, mockk(relaxed = true))
                    val spawn = SubagentToolFactory(
                        createAgent = { ToolLoopGraphBasedAgent(api, settings, it) },
                        catalogProvider = { AgentToolCatalog() },
                        toolsFilter = RuntimePassThroughToolsFilter,
                        skillBundleProvider = mockk(), commandExecutor = mockk(),
                        configuredModels = mapOf(model to provider),
                    ).create(AgentSettings(LLMModel.Max.alias, LlmProvider.GIGA, 0.5f, AgentTools(emptyMap())))
                    val result = spawn.spawnAndWait(LLMResponse.FunctionCall(spawn.fn.name, mapOf("task" to "Say Hi", "model" to model)))
                    assertEquals("Hi", restJsonMapper.readTree(result.content)["result"]?.asText(), result.content)
                    assertEquals(1, requests)
                }
            }
        }
    }

    @Test
    fun `compatible providers share text tool and terminal usage streaming`() = runTest {
        val cases = listOf(
            Triple(LlmProvider.OPENAI, LLMModel.OpenAIGpt5Mini.alias, "openai-key"),
            Triple(LlmProvider.AI_TUNNEL, LLMModel.AiTunnelGpt54Mini.alias, "tunnel-key"),
            Triple(LlmProvider.QWEN, LLMModel.QwenFlash.alias, "qwen-key"),
            Triple(LlmProvider.QWEN, LLMModel.Qwen38_27B.alias, "qwen-key"),
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

    private fun streamClient(
        stream: String,
        contentType: ContentType = ContentType.Text.EventStream,
        onRequest: suspend (HttpRequestData) -> Unit = {},
    ): HttpClient {
        val engineConfig = MockEngineConfig().apply {
            addHandler { request ->
                onRequest(request)
                respond(
                    content = stream,
                    headers = headersOf(HttpHeaders.ContentType, contentType.toString()),
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
        every { subagentTaskTimeout } returns ru.souz.agent.runtime.DEFAULT_SUBAGENT_TASK_TIMEOUT
        every { requestTimeoutMillis } returns 1_000L
        every { gigaModel } returns LLMModel.AnthropicHaiku45
        every { openaiBaseUrl } returns "https://openai.test/v1"
    }

    private fun chatRequest(model: String) = LLMRequest.Chat(
        model = model,
        messages = listOf(LLMRequest.Message(LLMMessageRole.user, "hello")),
    )

    private companion object {
        const val ANTHROPIC_REPLY = """{"content":[{"type":"text","text":"Hi"}],"stop_reason":"end_turn","usage":{"input_tokens":7,"output_tokens":3}}"""
        const val COMPATIBLE_REPLY = """{"choices":[{"index":0,"message":{"role":"assistant","content":"Hi"},"delta":{"role":"assistant","content":"Hi"},"finish_reason":"stop"}],"created":1,"usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}"""
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
