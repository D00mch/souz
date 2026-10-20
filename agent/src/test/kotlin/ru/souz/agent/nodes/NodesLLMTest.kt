package ru.souz.agent.nodes

import io.mockk.coEvery
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

class NodesLLMTest {
    @Test
    fun `host instructions budget the request without entering history or summaries`() = runTest {
        for (provider in listOf(LlmProvider.LOCAL, LlmProvider.OPENAI)) for (streaming in listOf(false, true)) {
            val calls = mutableListOf<LLMRequest.Chat>()
            val style = "style ".repeat(2600)
            var savedInstructions = style
            var instructionReads = 0
            val api = mockk<LLMChatAPI> {
                coEvery { message(any()) } answers {
                    calls += firstArg<LLMRequest.Chat>()
                    if (calls.last().isSummarization) savedInstructions = "updated style"
                    block(0, if (calls.last().isSummarization) "compact history" else "answer")
                }
                coEvery { messageStream(any()) } answers {
                    calls += firstArg<LLMRequest.Chat>()
                    flow { emit(block(0, "answer")) }
                }
            }
            val nodes = NodesLLM(api, mockk { every { useStreaming } returns streaming }, conversationInstructions = {
                instructionReads++
                savedInstructions
            })
            val activeTurn = listOf(
                LLMRequest.Message(LLMMessageRole.user, "current request"),
                LLMRequest.Message(LLMMessageRole.assistant, "", functionCall = LLMRequest.FunctionCall("work", "{}"), functionsStateId = "call"),
                LLMRequest.Message(LLMMessageRole.function, "result", name = "work", functionsStateId = "call"),
            )
            val ctx = context(listOf(LLMRequest.Message(LLMMessageRole.system, "system"),
                LLMRequest.Message(LLMMessageRole.user, "old ".repeat(10000)),
                LLMRequest.Message(LLMMessageRole.assistant, "old answer")) + activeTurn).let {
                it.copy(settings = it.settings.copy(provider = provider, contextSize = 16000))
            }
            val result = nodes.chat().execute(ctx, GraphRuntime(retryPolicy = RetryPolicy(), maxSteps = 10))
            assertEquals(2, calls.size)
            assertEquals(1, instructionReads, "Reuse the instructions snapshot when compaction rebuilds the request.")
            assertTrue(calls.first().isSummarization)
            assertTrue(calls.first().messages.none { style in it.content || it.content == "current request" })
            val request = calls.last()
            assertEquals("system\n\n$style", request.messages.first().content)
            assertEquals(activeTurn, request.messages.takeLast(activeTurn.size))
            assertTrue(request.estimatedInputTokens() < 16000 * 0.8)
            assertEquals(16000, if (provider == LlmProvider.LOCAL) request.maxTokens else request.maxTokens + request.estimatedInputTokens())
            assertTrue(result.history.none { style in it.content })
            assertEquals(activeTurn, result.history.dropLast(1).takeLast(activeTurn.size))
            assertTrue(result.history.any { "compact history" in it.content })

            calls.clear()
            nodes.chat().execute(ctx.copy(history = result.history.filter { it.role != LLMMessageRole.system }),
                GraphRuntime(retryPolicy = RetryPolicy(), maxSteps = 10))
            assertEquals(2, instructionReads, "The next conversational call reads fresh instructions.")
            assertEquals(LLMRequest.Message(LLMMessageRole.system, "updated style"), calls.single().messages.first())

            calls.clear()
            savedInstructions = style
            val tooSmall = ctx.copy(history = listOf(ctx.history.first()) + activeTurn,
                settings = ctx.settings.copy(contextSize = 1024))
            val rejected = nodes.chat().execute(tooSmall, GraphRuntime(retryPolicy = RetryPolicy(), maxSteps = 10))
            assertTrue(rejected.input is LLMResponse.Chat.Error)
            assertTrue(calls.isEmpty(), "Do not send an oversized active turn or discard its tool exchange.")
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

    private fun recordingSink(events: MutableList<AgentRuntimeEvent>) = object : AgentRuntimeEventSink {
        override suspend fun emit(event: AgentRuntimeEvent) { events += event }
    }

    private fun block(index: Int, content: String, tool: Boolean = false) = LLMResponse.Chat.Ok(
        choices = listOf(LLMResponse.Choice(
            LLMResponse.Message(content, LLMMessageRole.assistant,
                functionCall = if (tool) LLMResponse.FunctionCall("TestTool", emptyMap()) else null,
                functionsStateId = if (tool) "call" else null),
            index, null,
        )),
        created = 1, model = "test-model", usage = LLMResponse.Usage(1, 1, 2, 0),
    )

    private fun context(
        history: List<LLMRequest.Message>,
        runtimeEventSink: AgentRuntimeEventSink = AgentRuntimeEventSink.NONE,
    ): AgentContext<String> = AgentContext(
        input = "ignored",
        settings = AgentSettings(
            model = "test-model",
            provider = LlmProvider.OPENAI,
            temperature = 0.2f,
            toolsByCategory = emptyMap(),
        ),
        history = history,
        activeTools = emptyList(),
        systemPrompt = "system",
        runtimeEventSink = runtimeEventSink,
    )
}
