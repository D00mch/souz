package ru.souz.agent.nodes

import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.souz.agent.AgentStreamChunk
import ru.souz.agent.ActiveRunInput
import ru.souz.agent.graph.GraphRuntime
import ru.souz.agent.graph.RetryPolicy
import ru.souz.agent.graph.buildGraph
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.agent.runtime.AgentToolExecutor
import ru.souz.agent.runtime.ActiveRunInputController
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMException
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LlmProvider
import ru.souz.llms.LLMResponse

class NodesLLMTest {
    @Test
    fun `compaction preserves the active exchange through steering and tool loops`() = runTest {
        val calls = mutableListOf<LLMRequest.Chat>()
        val style = "style ".repeat(3600)
        var instructionReads = 0
        val api = mockk<LLMChatAPI> {
            coEvery { message(capture(calls)) } returnsMany listOf(
                block(0, "compact history".repeat(250)),
                block(0, "answer", tool = true),
                block(0, "compact history"),
                block(0, "answer", tool = true),
            )
        }
        val nodes = NodesLLM(api, mockk { every { useStreaming } returns false }, conversationInstructions = {
            if (instructionReads++ == 0) style else "updated style"
        })
        val system = LLMRequest.Message(LLMMessageRole.system, "system")
        val laterSystem = system.copy(content = "retained instructions")
        val completed = listOf(system) + exchange("old ".repeat(15000)) +
            activeTurn("completed tool output", "previous request") +
            LLMRequest.Message(LLMMessageRole.assistant, "completed answer") + laterSystem
        val active = listOf(
            LLMRequest.Message(LLMMessageRole.user, "fresh memory", name = INJECTED_MEMORY_MESSAGE_NAME),
            LLMRequest.Message(LLMMessageRole.user, "fresh host context"),
        ) + activeTurn()
        val ctx = context(completed + active, activeExchangeStartIndex = completed.size)
        val runtime = GraphRuntime(retryPolicy = RetryPolicy(), maxSteps = 10)
        val controller = ActiveRunInputController()
        val queued = listOf("first follow-up", "second follow-up")
        queued.forEach { input -> controller.submit { ActiveRunInput(input = input) } }
        val chat = SteerableChatNode(nodes, controller)
        val expectedActive = active + queued.map { LLMRequest.Message(LLMMessageRole.user, it) }

        val result = chat.execute(ctx, runtime)

        val summary = calls.dropLast(1).single()
        assertTrue(summary.isSummarization)
        assertEquals(completed, summary.messages.dropLast(1))
        assertEquals(SUMMARIZATION_MAX_OUTPUT_TOKENS, summary.maxTokens)
        assertTrue(summary.functions.isEmpty())
        assertTrue(calls.all { it.contextSize == 24_000 && !it.exceedsContextBudget(24_000) })
        assertEquals("system\n\n$style", calls.last().messages.first().content)
        assertTrue(calls.last().messages.any { "compact history" in it.content })
        assertEquals(CHAT_MAX_OUTPUT_TOKENS, calls.last().maxTokens)
        assertEquals(expectedActive, calls.last().messages.drop(result.activeExchangeStartIndex))
        assertEquals(3, result.activeExchangeStartIndex)
        assertEquals(listOf(system, laterSystem), result.history.filter { it.role == LLMMessageRole.system })
        assertTrue(result.history.none { style in it.content })
        assertEquals(1, instructionReads)

        val executor = mockk<AgentToolExecutor> {
            coEvery { execute(any(), any(), any(), any(), any()) } returns activeTurn("result ".repeat(10550)).last()
        }
        val afterTools = NodesPlain.toolUse(executor).execute(result.map { it as LLMResponse.Chat.Ok }, runtime)
        val next = chat.execute(afterTools, runtime)
        assertEquals(2, instructionReads)
        assertEquals(listOf(true, false, true, false), calls.map { it.isSummarization })
        assertEquals(result.history.take(result.activeExchangeStartIndex), calls[2].messages.dropLast(1))
        assertEquals(afterTools.history.drop(afterTools.activeExchangeStartIndex), calls.last().messages.drop(next.activeExchangeStartIndex))
        assertEquals(3, next.activeExchangeStartIndex)
        assertEquals("system\n\nupdated style", calls.last().messages.first().content)
    }

    @ParameterizedTest
    @ValueSource(strings = ["tool schemas", "completed history"])
    fun `oversized tools or summary input never call the provider`(oversizedPart: String) = runTest {
        val api = mockk<LLMChatAPI>()
        val nodes = NodesLLM(api, mockk { every { useStreaming } returns false })
        val oversized = "x".repeat(100_000)
        val original = if (oversizedPart == "completed history") context(exchange(oversized) + activeTurn(), activeExchangeStartIndex = 2) else
            context(activeTurn()).copy(activeTools = listOf(
                LLMRequest.Function("work", oversized, LLMRequest.Parameters("object")),
            ))

        val rejected = nodes.chat().execute(original, GraphRuntime(retryPolicy = RetryPolicy(), maxSteps = 10))

        assertTrue(rejected.input is LLMResponse.Chat.Error)
        assertEquals(original.history, rejected.history)
        coVerify { api wasNot Called }
    }

    @ParameterizedTest
    @ValueSource(ints = [19_200, 19_201])
    fun `input fits at eighty percent regardless of the output limit`(inputTokens: Int) = runTest {
        val calls = mutableListOf<LLMRequest.Chat>()
        val api = mockk<LLMChatAPI> {
            coEvery { message(capture(calls)) } returns block(0, "answer")
        }
        val seed = context(activeTurn(""))
        val framing = seed.toChatRequest(seed.history, CHAT_MAX_OUTPUT_TOKENS).estimatedInputTokens()
        val ctx = seed.copy(history = activeTurn("x".repeat((inputTokens - framing) * 4)))
        val request = ctx.toChatRequest(ctx.history, maxOutputTokens = 24_000)
        assertEquals(inputTokens > 19_200, request.exceedsContextBudget(24_000))

        val result = NodesLLM(api, mockk { every { useStreaming } returns false }).chat()
            .execute(ctx, GraphRuntime(retryPolicy = RetryPolicy(), maxSteps = 10))

        assertEquals(inputTokens > 19_200, result.input is LLMResponse.Chat.Error)
        assertEquals(if (inputTokens > 19_200) 0 else 1, calls.size)
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
    @ValueSource(strings = ["truncated", "empty", "oversized"])
    fun `invalid summaries never dispatch the conversation`(failure: String) = runTest {
        val calls = mutableListOf<LLMRequest.Chat>()
        val text = when (failure) {
            "empty" -> ""
            "oversized" -> "x".repeat(100_000)
            else -> "incomplete summary"
        }
        val api = mockk<LLMChatAPI> {
            coEvery { message(capture(calls)) } returns block(0, text,
                finishReason = if (failure == "truncated") LLMResponse.FinishReason.length else null)
        }
        val nodes = NodesLLM(api, mockk { every { useStreaming } returns false })
        val ctx = context(exchange("old ".repeat(18000)) + activeTurn("result ".repeat(1000)), activeExchangeStartIndex = 2)
        val runtime = GraphRuntime(retryPolicy = RetryPolicy(), maxSteps = 10)
        if (failure == "oversized") {
            assertTrue(nodes.chat().execute(ctx, runtime).input is LLMResponse.Chat.Error)
        } else {
            assertFailsWith<LLMException> { nodes.chat().execute(ctx, runtime) }
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
        activeExchangeStartIndex: Int = 0,
    ): AgentContext<String> = AgentContext(
        input = "ignored",
        settings = AgentSettings(
            model = "test-model",
            provider = LlmProvider.OPENAI,
            temperature = 0.2f,
            toolsByCategory = emptyMap(),
            contextSize = 24_000,
        ),
        history = history,
        activeTools = emptyList(),
        systemPrompt = "system",
        runtimeEventSink = runtimeEventSink,
        activeExchangeStartIndex = activeExchangeStartIndex,
    )
}
