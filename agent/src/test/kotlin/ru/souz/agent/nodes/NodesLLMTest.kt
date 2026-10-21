package ru.souz.agent.nodes

import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import ru.souz.agent.AgentStreamChunk
import ru.souz.agent.graph.GraphRuntime
import ru.souz.agent.graph.RetryPolicy
import ru.souz.agent.graph.buildGraph
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.agent.runtime.ActiveRunInputController
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMException
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LlmProvider
import ru.souz.llms.LLMResponse
import ru.souz.llms.restJsonMapper

class NodesLLMTest {
    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "personality-triggered compaction, 9000, 2600",
        "recent exchanges without personality, 40000, 0",
    )
    fun `compaction budgets requests and preserves the active tool exchange`(
        scenario: String,
        historyRepetitions: Int,
        instructionRepetitions: Int,
    ) = runTest {
        for (provider in listOf(LlmProvider.LOCAL, LlmProvider.OPENAI)) for (streaming in listOf(false, true)) {
            val calls = mutableListOf<LLMRequest.Chat>()
            val style = "style ".repeat(instructionRepetitions)
            var savedInstructions = style
            var instructionReads = 0
            val api = mockk<LLMChatAPI> {
                coEvery { message(capture(calls)) } answers {
                    if (calls.last().isSummarization) savedInstructions = "updated style"
                    block(0, if (calls.last().isSummarization) "compact history" else "answer")
                }
                coEvery { messageStream(capture(calls)) } returns flowOf(block(0, "answer"))
            }
            val nodes = NodesLLM(api, mockk { every { useStreaming } returns streaming }, conversationInstructions = {
                instructionReads++
                savedInstructions
            })
            val activeTurn = activeTurn()
            val oldest = exchange("old ".repeat(historyRepetitions))
            val recent = exchange("previous request") + activeTurn("completed tool output", "recent request") +
                LLMRequest.Message(LLMMessageRole.assistant, "recent answer")
            val system = LLMRequest.Message(LLMMessageRole.system, "system")
            val laterSystem = LLMRequest.Message(LLMMessageRole.system, "retained instructions")
            val ctx = context(listOf(system) + oldest + laterSystem + recent + activeTurn, provider = provider)
            val runtime = GraphRuntime(retryPolicy = RetryPolicy(), maxSteps = 10)
            assertEquals(style.isEmpty(), ctx.toChatRequest(ctx.history, CHAT_MAX_OUTPUT_TOKENS)
                .exceedsContextBudget(16000), scenario)
            val result = nodes.chat().execute(ctx, runtime)
            val summary = calls.dropLast(1).single()
            assertEquals(1, instructionReads, "Reuse the instructions snapshot when compaction rebuilds the request.")
            assertTrue(summary.isSummarization)
            assertEquals(SUMMARIZATION_MAX_OUTPUT_TOKENS, summary.maxTokens)
            assertTrue(summary.functions.isEmpty())
            assertEquals(restJsonMapper.writeValueAsString(if (style.isEmpty()) recent else oldest + recent),
                summary.messages.last().content)
            assertTrue(summary.messages.none {
                (style.isNotEmpty() && style in it.content) || "current request" in it.content || "result" in it.content
            })
            assertTrue(calls.all { !it.exceedsContextBudget(16000) }, "Every request must fit, including the summary.")
            val request = calls.last()
            assertFalse(request.isSummarization)
            assertEquals(if (style.isEmpty()) "system" else "system\n\n$style", request.messages.first().content)
            assertEquals(activeTurn, request.messages.takeLast(activeTurn.size))
            assertEquals(CHAT_MAX_OUTPUT_TOKENS, request.maxTokens)
            assertEquals(16000, request.contextSize)
            assertTrue(result.history.none { style.isNotEmpty() && style in it.content })
            assertEquals(listOf(system, laterSystem), result.history.filter { it.role == LLMMessageRole.system })
            assertEquals(activeTurn, result.history.dropLast(1).takeLast(activeTurn.size))
            assertTrue(result.history.any { "compact history" in it.content })

            calls.clear()
            nodes.chat().execute(ctx.copy(history = result.history.filter { it.role != LLMMessageRole.system }), runtime)
            assertEquals(2, instructionReads, "The next conversational call reads fresh instructions.")
            assertEquals(LLMRequest.Message(LLMMessageRole.system, "updated style"), calls.single().messages.first())
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["instructions", "tool output", "tool schemas", "completed message", "completed tool exchange"])
    fun `oversized requests preserve history and never call the provider`(oversizedPart: String) = runTest {
        fun content(part: String) = part.repeat(if (part == oversizedPart) 12_000 else 1)
        val api = mockk<LLMChatAPI>()
        val nodes = NodesLLM(api, mockk { every { useStreaming } returns false },
            conversationInstructions = { content("instructions") })
        val completed = when (oversizedPart) {
            "completed message" -> listOf(LLMRequest.Message(LLMMessageRole.user, content(oversizedPart)))
            "completed tool exchange" -> activeTurn(content(oversizedPart))
            else -> emptyList()
        }
        val history = if (completed.isEmpty()) emptyList() else exchange("older small request") +
            completed + LLMRequest.Message(LLMMessageRole.assistant, "completed answer")
        val original = context(history + activeTurn(content("tool output"))).copy(
            activeTools = listOf(LLMRequest.Function("work", content("tool schemas"), LLMRequest.Parameters("object"))),
        )
        val rejected = nodes.chat().execute(original, GraphRuntime(retryPolicy = RetryPolicy(), maxSteps = 10))

        assertTrue(rejected.input is LLMResponse.Chat.Error)
        assertEquals(original.history, rejected.history)
        coVerify { api wasNot Called }
    }

    @Test
    fun `summary budget includes the prompt output reserve and margin without splitting exchanges`() = runTest {
        val calls = mutableListOf<LLMRequest.Chat>()
        val api = mockk<LLMChatAPI> {
            coEvery { message(capture(calls)) } returns block(0, "compact history")
        }
        val completed = activeTurn("whole tool output with quotes \" and emoji 😀") +
            LLMRequest.Message(LLMMessageRole.assistant, "completed answer")
        val active = activeTurn()
        val request = context(completed + active).toChatRequest(completed + active, SUMMARIZATION_MAX_OUTPUT_TOKENS)
        api.compactHistory(request)
        val summary = calls.single()
        val exactBudget = summary.estimatedInputTokens() + SUMMARIZATION_MAX_OUTPUT_TOKENS + CONTEXT_SAFETY_MARGIN_TOKENS
        val history = exchange("older request") + request.messages
        calls.clear()

        api.compactHistory(request.copy(messages = history, contextSize = exactBudget))
        assertEquals(summary.messages, calls.single().messages, "Only the newest whole exchange fits exactly.")
        calls.clear()
        for (budget in listOf(exactBudget - 1, SUMMARIZATION_MAX_OUTPUT_TOKENS + CONTEXT_SAFETY_MARGIN_TOKENS)) {
            assertEquals(history, api.compactHistory(request.copy(messages = history, contextSize = budget)))
            assertTrue(calls.isEmpty(), "Do not send a partial exchange or an oversized summary prompt.")
        }
    }

    @Test
    fun `chat retries only LLM exceptions and respects the attempt limit`() = runTest {
        val failures = listOf(
            LLMException(LLMResponse.Chat.Error(503, "Provider failure")) to 2,
            IllegalStateException("Unexpected failure") to 1,
            CancellationException("Provider cancelled") to 1,
        )
        for (streaming in listOf(false, true)) {
            for ((failure, expectedAttempts) in failures) {
                val events = mutableListOf<AgentRuntimeEvent>()
                var attempts = 0
                val api = mockk<LLMChatAPI> {
                    coEvery { message(any()) } answers { attempts += 1; throw failure }
                    coEvery { messageStream(any()) } returns flow {
                        attempts++
                        emit(block(0, "unfinished", tool = true))
                        throw failure
                    }
                }
                val nodes = NodesLLM(api, mockk { every { useStreaming } returns streaming })
                val graph = buildGraph<String, LLMResponse.Chat> {
                    nodeInput.edgeTo(SteerableChatNode(nodes, ActiveRunInputController())).edgeTo(nodeFinish)
                }

                val thrown =
                    assertFailsWith(failure::class) { graph.start(context(emptyList(), recordingSink(events))) }
                if (failure !is CancellationException) {
                    assertEquals(failure.message, thrown.message)
                }
                assertEquals(expectedAttempts, attempts)
                assertEquals(emptyList(), events.filterIsInstance<AgentRuntimeEvent.AssistantMessage>())
            }
        }
    }

    @Test
    fun `streaming chat emits runtime deltas and keeps side effects batching`() = runTest {
        val runtimeEvents = mutableListOf<AgentRuntimeEvent>()
        val chunks = listOf("Hello", " ", "streaming world")
        val nodes = NodesLLM(
            llmApi = mockk {
                coEvery { messageStream(any()) } returns flow { chunks.forEach { emit(block(0, it)) } }
            },
            settingsProvider = mockk { every { useStreaming } returns true },
        )
        val context = context(
            history = listOf(LLMRequest.Message(role = LLMMessageRole.user, content = "Prompt")),
            runtimeEventSink = recordingSink(runtimeEvents),
        )

        val sideEffect = async(start = CoroutineStart.UNDISPATCHED) { nodes.sideEffects.first() }
        val result = nodes.chat(streamRevision = 7L).execute(
            ctx = context,
            runtime = GraphRuntime(retryPolicy = RetryPolicy(), maxSteps = 10),
        )

        assertEquals<List<AgentRuntimeEvent>>(chunks.map { AgentRuntimeEvent.LlmMessageDelta(it) }, runtimeEvents)
        assertEquals(AgentStreamChunk("Hello streaming world", 7L), sideEffect.await())
        val response = result.input as LLMResponse.Chat.Ok
        assertEquals("Hello streaming world", response.choices.single().message.content)
        assertEquals("Hello streaming world", result.history.last().content)
    }

    @Test
    fun `stream retries publish assembled tool response blocks once in index order`() = runTest {
        val events = mutableListOf<AgentRuntimeEvent>()
        var attempts = 0
        val api = mockk<LLMChatAPI> {
            coEvery { messageStream(any()) } returns flow {
                emit(block(7, "Second"))
                emit(block(2, "Let"))
                emit(block(2, " "))
                emit(block(2, "me check"))
                emit(block(9, "", tool = true))
                assertEquals(emptyList(), events.filterIsInstance<AgentRuntimeEvent.AssistantMessage>())
                if (++attempts == 1) throw LLMException(LLMResponse.Chat.Error(503, "retry"))
            }
        }
        val nodes = NodesLLM(api, mockk { every { useStreaming } returns true })
        val graph = buildGraph<String, LLMResponse.Chat> {
            nodeInput.edgeTo(SteerableChatNode(nodes, ActiveRunInputController())).edgeTo(nodeFinish)
        }
        val result = graph.start(context(emptyList(), recordingSink(events)))
        assertEquals(2, attempts)
        assertEquals(listOf("Let me check", "Second"), events.filterIsInstance<AgentRuntimeEvent.AssistantMessage>().map { it.content })
        assertEquals(listOf("Let me check", "Second", ""), result.history.map { it.content })
    }

    @ParameterizedTest
    @ValueSource(strings = ["provider error", "cancelled", "truncated", "empty", "oversized"])
    fun `summary failures never dispatch an oversized conversation`(failure: String) = runTest {
        val calls = mutableListOf<LLMRequest.Chat>()
        val api = mockk<LLMChatAPI> {
            coEvery { message(capture(calls)) } answers {
                when (failure) {
                    "cancelled" -> throw CancellationException("cancelled")
                    "truncated" -> block(0, "incomplete summary", finishReason = LLMResponse.FinishReason.length)
                    "empty" -> block(0, "")
                    "oversized" -> block(0, "unhelpfully long summary ".repeat(4000))
                    else -> LLMResponse.Chat.Error(503, "summary unavailable")
                }
            }
        }
        val nodes = NodesLLM(api, mockk { every { useStreaming } returns false })
        val active = activeTurn()
        val ctx = context(exchange("old ".repeat(12000)) + active)
        val runtime = GraphRuntime(retryPolicy = RetryPolicy(), maxSteps = 10)
        if (failure == "oversized") {
            val rejected = nodes.chat().execute(ctx, runtime)
            assertTrue(rejected.input is LLMResponse.Chat.Error)
            assertEquals(active, rejected.history.takeLast(active.size))
        } else {
            val errorType = if (failure == "cancelled") CancellationException::class else LLMException::class
            assertFailsWith(errorType) { nodes.chat().execute(ctx, runtime) }
        }
        assertTrue(calls.single().isSummarization)
    }

    private fun exchange(prompt: String) = listOf(
        LLMRequest.Message(LLMMessageRole.user, prompt),
        LLMRequest.Message(LLMMessageRole.assistant, "completed answer"),
    )

    private fun activeTurn(toolOutput: String = "result", prompt: String = "current request") = listOf(
        LLMRequest.Message(LLMMessageRole.user, prompt),
        LLMRequest.Message(LLMMessageRole.assistant, "", functionCall = LLMRequest.FunctionCall("work", "{}"), functionsStateId = "call"),
        LLMRequest.Message(LLMMessageRole.function, toolOutput, name = "work", functionsStateId = "call"),
    )

    private fun recordingSink(events: MutableList<AgentRuntimeEvent>) = object : AgentRuntimeEventSink {
        override suspend fun emit(event: AgentRuntimeEvent) { events += event }
    }

    private fun block(index: Int, content: String, tool: Boolean = false, finishReason: LLMResponse.FinishReason? = null) = LLMResponse.Chat.Ok(
        choices = listOf(LLMResponse.Choice(
            LLMResponse.Message(content, LLMMessageRole.assistant,
                functionCall = if (tool) LLMResponse.FunctionCall("TestTool", emptyMap()) else null,
                functionsStateId = if (tool) "call" else null),
            index, finishReason,
        )),
        created = 1, model = "test-model", usage = LLMResponse.Usage(1, 1, 2, 0),
    )

    private fun context(
        history: List<LLMRequest.Message>,
        runtimeEventSink: AgentRuntimeEventSink = AgentRuntimeEventSink.NONE,
        provider: LlmProvider = LlmProvider.OPENAI,
    ): AgentContext<String> = AgentContext(
        input = "ignored",
        settings = AgentSettings(
            model = "test-model",
            provider = provider,
            temperature = 0.2f,
            toolsByCategory = emptyMap(),
            contextSize = 16_000,
        ),
        history = history,
        activeTools = emptyList(),
        systemPrompt = "system",
        runtimeEventSink = runtimeEventSink,
    )
}
