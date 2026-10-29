package ru.souz.llms

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.souz.ToolLoopGraphBasedAgent
import ru.souz.agent.AgentStreamChunk
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.agent.state.AgentTools
import ru.souz.db.SettingsProvider
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.llms.openai.OpenAICompatibleChatAPI
import ru.souz.llms.tunnel.AiTunnelVoiceAPI
import ru.souz.llms.tunnel.retryAiTunnelRequest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AiTunnelRetryTest {
    @Test
    fun `chat embeddings and streams retry request failures with identical payloads`() = runTest {
        for (failure in listOf("connection", "timeout", "400", "401", "429", "500", "503", "malformed", "api-error")) {
            for (operation in listOf("chat", "embeddings", "stream")) {
                var calls = 0
                val payloads = mutableListOf<List<Byte>>()
                val engine = MockEngine { request ->
                    calls++
                    payloads += request.body.toByteArray().toList()
                    if (calls < 3) {
                        when (failure) {
                            "connection" -> throw IOException("connection failed")
                            "timeout" -> throw HttpRequestTimeoutException(request)
                            "malformed" -> respond("{", headers = jsonHeaders)
                            "api-error" -> respond(
                                if (operation == "stream") "data: {\"error\":{\"message\":\"busy\"}}\n\n"
                                else """{"error":{"message":"busy"}}""", headers = jsonHeaders)
                            else -> respond("failure-$calls", HttpStatusCode.fromValue(failure.toInt()), jsonHeaders)
                        }
                    } else respond(reply(operation), headers = jsonHeaders)
                }
                HttpClient(engine) { providerHttpClientDefaults() }.use { client ->
                    val api = api(client)
                    when (operation) {
                        "chat" -> assertIs<LLMResponse.Chat.Ok>(api.message(chat))
                        "embeddings" -> assertIs<LLMResponse.Embeddings.Ok>(api.embeddings(embeddings))
                        else -> {
                            val responses = api.messageStream(chat).toList()
                            assertFalse(responses.any { it is LLMResponse.Chat.Error })
                            assertEquals("accepted", responses.filterIsInstance<LLMResponse.Chat.Ok>()
                                .flatMap { it.choices }.joinToString("") { it.message.content })
                        }
                    }
                }
                assertEquals(3, calls, "$operation/$failure")
                assertEquals(1, payloads.distinct().size, "$operation/$failure changes the request")
            }
        }
    }

    @Test
    fun `attempt limit returns only the last error through the existing response contract`() = runTest {
        for (operation in listOf("chat", "embeddings", "stream")) {
            var calls = 0
            HttpClient(MockEngine {
                calls++
                respond("failure-$calls", HttpStatusCode.TooManyRequests, jsonHeaders)
            }) { providerHttpClientDefaults() }.use { client ->
                val api = api(client)
                if (operation == "embeddings") {
                    assertEquals(LLMResponse.Embeddings.Error(429, "failure-3"), api.embeddings(embeddings))
                } else {
                    val response = if (operation == "stream") api.messageStream(chat).toList()
                        .filterIsInstance<LLMResponse.Chat.Error>().single() else api.message(chat)
                    assertEquals(LLMResponse.Chat.Error(429, "failure-3", retriesExhausted = true), response)
                }
            }
            assertEquals(3, calls)
        }
    }

    @Test
    fun `successful requests and other hosts and providers stay at one attempt`() = runTest {
        for (provider in listOf(LlmProvider.AI_TUNNEL, LlmProvider.OPENAI, LlmProvider.QWEN)) {
            for (enabled in listOf(false, true)) {
                for (success in listOf(false, true)) {
                    for (operation in listOf("chat", "embeddings", "stream")) {
                        var calls = 0
                        HttpClient(MockEngine {
                            calls++
                            respond(if (success) reply(operation) else "failed",
                                if (success) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, jsonHeaders)
                        }) { providerHttpClientDefaults() }.use { client ->
                            val api = api(client, provider, enabled)
                            when (operation) {
                                "chat" -> {
                                    val response = api.message(chat)
                                    if (!success) assertEquals(provider == LlmProvider.AI_TUNNEL && enabled,
                                        assertIs<LLMResponse.Chat.Error>(response).retriesExhausted)
                                }
                                "embeddings" -> api.embeddings(embeddings)
                                else -> api.messageStream(chat).toList()
                            }
                        }
                        assertEquals(if (!success && provider == LlmProvider.AI_TUNNEL && enabled) 3 else 1, calls)
                    }
                }
            }
        }
    }

    @Test
    fun `failed partial stream is discarded before restarting and tools execute once`() = runTest {
        for (failure in listOf("disconnect", "eof", "api-error", "malformed")) {
            var calls = 0
            var toolCalls = 0
            val partial = ByteChannel(autoFlush = true)
            val partialReceived = CompletableDeferred<Unit>()
            val continueStream = CompletableDeferred<Unit>()
            val prefix = "failed partial output longer than a side effect batch"
            // A real tool implementation records execution, not merely emitted provider chunks.
            val executableTool = object : LLMToolSetup {
                override val fn = LLMRequest.Function("lookup", "lookup")
                override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message {
                    toolCalls++
                    return LLMRequest.Message(LLMMessageRole.function, "tool result", functionsStateId = "accepted-call")
                }
            }
            HttpClient(MockEngine {
                calls++
                when (calls) {
                    1 -> respond(partial, headers = streamHeaders)
                    2 -> respond(TOOL_STREAM, headers = streamHeaders)
                    else -> respond(reply("stream"), headers = streamHeaders)
                }
            }) { providerHttpClientDefaults() }.use { client ->
                val settings = settings()
                val agent = ToolLoopGraphBasedAgent(api(client), settings)
                var rendered = ""
                val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    agent.sideEffects.collect { chunk: AgentStreamChunk ->
                        rendered = rendered.dropLast(chunk.retractCharacters) + chunk.text
                        if (rendered == prefix) partialReceived.complete(Unit)
                    }
                }
                val writer = launch {
                    partial.writeStringUtf8("data: ${chunk(prefix)}\n\n")
                    partial.flush()
                    continueStream.await()
                    when (failure) {
                        "disconnect" -> partial.cancel(IOException("stream disconnected"))
                        "eof" -> partial.close()
                        "api-error" -> { partial.writeStringUtf8("data: {\"error\":{\"message\":\"busy\"}}\n\n"); partial.close() }
                        else -> { partial.writeStringUtf8("data: {broken\n\n"); partial.close() }
                    }
                }
                val result = async {
                    agent.execute(AgentContext(
                        input = "hello",
                        settings = AgentSettings("model", LlmProvider.AI_TUNNEL, 0f, AgentTools(listOf(executableTool))),
                        history = emptyList(),
                        activeTools = listOf(executableTool.fn),
                        systemPrompt = "",
                    ))
                }
                partialReceived.await()
                assertFalse(result.isCompleted, "The first attempt must render before the stream finishes")
                assertEquals(prefix, rendered)
                continueStream.complete(Unit)
                val completed = result.await()
                writer.join()
                assertEquals("accepted", completed.output)
                assertFalse(completed.context.history.any { it.content.contains(prefix) })
                assertFalse(rendered.contains(prefix), rendered)
                assertEquals(1, toolCalls)
                assertEquals(3, calls, "two attempts for the tool request, one subsequent final request")
                collector.cancelAndJoin()
            }
        }
    }

    @Test
    fun `cancellation during requests starts no further attempts`() = runTest {
        for (operation in listOf("chat", "embeddings", "stream", "voice")) {
            var calls = 0
            HttpClient(MockEngine {
                calls++
                throw CancellationException("cancelled")
            }) { providerHttpClientDefaults() }.use { client ->
                val request = async {
                    val api = api(client)
                    when (operation) {
                        "chat" -> api.message(chat)
                        "embeddings" -> api.embeddings(embeddings)
                        "voice" -> AiTunnelVoiceAPI(settings(), client).recognize(byteArrayOf(0, 0))
                        else -> api.messageStream(chat).toList()
                    }
                }
                assertFailsWith<CancellationException> { request.await() }
            }
            assertEquals(1, calls, operation)
        }
    }

    @Test
    fun `cancellation during retry backoff prevents another request`() = runTest {
        var calls = 0
        val request = async { retryAiTunnelRequest(isError = { true }) { calls++; "failed" } }
        runCurrent()
        assertEquals(1, calls)
        request.cancelAndJoin()
        assertEquals(1, calls)
    }

    @Test
    fun `transcription retries unchanged audio and returns the last failure after three attempts`() = runTest {
        for (failure in listOf("connection", "timeout", "malformed", "api-error", "401")) {
            for (succeed in listOf(false, true)) {
                var calls = 0
                val bodies = mutableListOf<List<Byte>>()
                HttpClient(MockEngine { request ->
                    calls++
                    bodies += request.body.toByteArray().toList()
                    when {
                        calls == 1 -> when (failure) {
                            "connection" -> throw IOException("connection failed")
                            "timeout" -> throw HttpRequestTimeoutException(request)
                            "malformed" -> respond("{", headers = jsonHeaders)
                            "api-error" -> respond("""{"error":{"message":"busy"}}""", headers = jsonHeaders)
                            else -> respond("unauthorized", HttpStatusCode.Unauthorized, jsonHeaders)
                        }
                        calls == 2 -> respond("busy", HttpStatusCode.TooManyRequests, jsonHeaders)
                        succeed -> respond("""{"text":" recognized "}""", headers = jsonHeaders)
                        else -> respond("last failure", HttpStatusCode.ServiceUnavailable, jsonHeaders)
                    }
                }) { providerHttpClientDefaults() }.use { client ->
                    val voice = AiTunnelVoiceAPI(settings(), client)
                    if (succeed) assertEquals("recognized", voice.recognize(byteArrayOf(1, 2)))
                    else assertEquals("AiTunnel transcription failed: 503",
                        assertFailsWith<IllegalStateException> { voice.recognize(byteArrayOf(1, 2)) }.message)
                }
                assertEquals(3, calls)
                assertEquals(1, bodies.distinct().size)
            }
        }
    }

    private fun api(client: HttpClient, provider: LlmProvider = LlmProvider.AI_TUNNEL, enabled: Boolean = true) =
        OpenAICompatibleChatAPI(provider, settings(), client, "test-key", retryAiTunnelRequests = enabled)

    private fun settings(): SettingsProvider = mockk(relaxed = true) {
        every { requestTimeoutMillis } returns 10_000L
        every { useStreaming } returns true
        every { aiTunnelKey } returns "test-key"
        every { regionProfile } returns "ru"
        every { voiceRecognitionModel } returns VoiceRecognitionModel.AiTunnelGpt4oTranscribe
    }

    private fun reply(operation: String) = when (operation) {
        "embeddings" -> """{"data":[{"embedding":[0.1,0.2]}],"model":"embedding","usage":{"total_tokens":1}}"""
        "stream" -> "data: $CHAT_REPLY\n\ndata: [DONE]\n\n"
        else -> CHAT_REPLY
    }

    private fun chunk(content: String) = """{"choices":[{"index":0,"delta":{"content":"$content"}}]}"""

    private companion object {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        val streamHeaders = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
        val chat = LLMRequest.Chat("model", listOf(LLMRequest.Message(LLMMessageRole.user, "hello")))
        val embeddings = LLMRequest.Embeddings("embedding", listOf("hello"))
        const val CHAT_REPLY = """{"choices":[{"index":0,"message":{"role":"assistant","content":"accepted"},"delta":{"role":"assistant","content":"accepted"},"finish_reason":"stop"}],"created":1}"""
        const val TOOL_STREAM = "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"accepted-call\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"
    }
}
