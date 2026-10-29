package ru.souz.llms

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import ru.souz.db.SettingsProvider
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.llms.openai.OpenAICompatibleChatAPI
import ru.souz.llms.tunnel.AiTunnelVoiceAPI
import ru.souz.memory.LlmMemoryWriter
import ru.souz.memory.MemoryCaptureInput
import ru.souz.memory.MemoryCaptureService
import ru.souz.memory.MemoryService
import ru.souz.memory.MemoryWriterException
import ru.souz.memory.globalMemoryScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class AiTunnelRetryTest {
    @Test
    fun `unary operations recover on their third attempt`() = runTest {
        for (operation in listOf("chat", "embeddings", "voice")) {
            var calls = 0
            HttpClient(MockEngine {
                when (++calls) {
                    1 -> throw IOException("offline")
                    2 -> respond("busy", HttpStatusCode.ServiceUnavailable)
                    else -> respond(REPLY, headers = JSON_HEADERS)
                }
            }) { providerHttpClientDefaults() }.use { client ->
                when (operation) {
                    "chat" -> assertIs<LLMResponse.Chat.Ok>(api(client).message(CHAT))
                    "embeddings" -> assertIs<LLMResponse.Embeddings.Ok>(api(client).embeddings(LLMRequest.Embeddings("model", listOf("hello"))))
                    else -> assertEquals("accepted", AiTunnelVoiceAPI(settings(), client).recognize(byteArrayOf(0, 0)))
                }
                assertEquals(3, calls, operation)
            }
        }
    }

    @Test
    fun `streams retry before output but never replay text tools or usage`() = runTest {
        for (partial in listOf(null, TEXT, TOOL, """{"usage":{"total_tokens":1}}""")) {
            var calls = 0
            HttpClient(MockEngine {
                calls++
                respond(when {
                    partial != null -> "data: $partial\n\n" // EOF after a successful chunk is terminal.
                    calls == 1 -> "data: {broken\n\n"
                    calls == 2 -> "data: {\"error\":{\"message\":\"busy\"}}\n\n"
                    else -> "data: $TEXT\n\ndata: [DONE]\n\n"
                }, headers = JSON_HEADERS)
            }) { providerHttpClientDefaults() }.use { client ->
                val responses = api(client).messageStream(CHAT).toList()
                assertIs<LLMResponse.Chat.Ok>(responses.first())
                if (partial != null) assertFalse(assertIs<LLMResponse.Chat.Error>(responses.last()).retryable)
                else assertEquals(1, responses.size)
                assertEquals(if (partial == null) 3 else 1, calls)
            }
        }
    }

    @Test
    fun `memory capture cannot multiply exhausted provider attempts`() = runTest {
        var calls = 0
        HttpClient(MockEngine { calls++; respond("busy", HttpStatusCode.TooManyRequests) }) {
            providerHttpClientDefaults()
        }.use { client ->
            val capture = MemoryCaptureService(mockk<MemoryService>(), LlmMemoryWriter(api(client), settings()))
            val error = assertFailsWith<MemoryWriterException> {
                capture.captureAfterTurn(MemoryCaptureInput(
                    scopes = listOf(globalMemoryScope()), userMessage = "hello", assistantMessage = "answer",
                    conversationId = "chat", userMessageId = "user", assistantMessageId = "assistant",
                ))
            }
            assertFalse(error.retryable)
            assertEquals(3, calls)
        }
    }

    @Test
    fun `request and backoff cancellation start no further attempts`() = runTest {
        for (duringRequest in listOf(true, false)) {
            var calls = 0
            HttpClient(MockEngine) {
                providerHttpClientDefaults()
                engine {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler {
                        calls++
                        if (duringRequest) throw CancellationException("cancelled")
                        respond("busy", HttpStatusCode.ServiceUnavailable)
                    }
                }
            }.use { client ->
                val request = async { api(client).messageStream(CHAT).toList() }
                runCurrent()
                if (duringRequest) assertFailsWith<CancellationException> { request.await() }
                else request.cancelAndJoin()
                assertEquals(1, calls)
            }
        }
    }

    @Test
    fun `collector failure propagates without replay`() = runTest {
        var calls = 0
        val failure = IllegalStateException("consumer failed")
        HttpClient(MockEngine { calls++; respond("data: $TEXT\n\ndata: [DONE]\n\n", headers = JSON_HEADERS) }) {
            providerHttpClientDefaults()
        }.use { client ->
            assertEquals(failure.message, assertFailsWith<IllegalStateException> {
                api(client).messageStream(CHAT).collect { throw failure }
            }.message)
            assertEquals(1, calls)
        }
    }

    private fun api(client: HttpClient) = OpenAICompatibleChatAPI(LlmProvider.AI_TUNNEL, settings(), client, "test-key")

    private fun settings(): SettingsProvider = mockk(relaxed = true) {
        every { gigaModel } returns LLMModel.AiTunnelGpt54Mini
        every { executionModelId(any()) } answers { firstArg<LLMModel>().alias }
        every { requestTimeoutMillis } returns 10_000L
        every { aiTunnelKey } returns "test-key"
        every { regionProfile } returns "ru"
        every { voiceRecognitionModel } returns VoiceRecognitionModel.AiTunnelGpt4oTranscribe
    }

    private companion object {
        val JSON_HEADERS = headersOf("Content-Type", "application/json")
        val CHAT = LLMRequest.Chat("model", listOf(LLMRequest.Message(LLMMessageRole.user, "hello")))
        const val REPLY = """{"choices":[],"data":[{"embedding":[0.1]}],"text":"accepted"}"""
        const val TEXT = """{"choices":[{"index":0,"delta":{"content":"partial"}}]}"""
        const val TOOL = """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call","function":{"name":"lookup","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
    }
}
