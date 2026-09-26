package ru.souz.agent.nodes

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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
    fun `stream retries publish only complete accepted blocks in index order`() = runTest {
        val events = mutableListOf<AgentRuntimeEvent>()
        var attempts = 0
        val api = mockk<LLMChatAPI> {
            coEvery { messageStream(any()) } answers {
                flow {
                    attempts++
                    emit(block(16, "Second block"))
                    emit(block(7, "Let"))
                    emit(block(7, " "))
                    emit(block(7, "me check"))
                    emit(block(32, "", tool = true))
                    assertTrue(events.filterIsInstance<AgentRuntimeEvent.AssistantMessage>().isEmpty())
                    if (attempts == 1) throw LLMException(LLMResponse.Chat.Error(503, "retry"))
                }
            }
        }
        val node = SteerableChatNode(NodesLLM(api, mockk { every { useStreaming } returns true }), ActiveRunInputController())
        val result = buildGraph<String, LLMResponse.Chat> {
            nodeInput.edgeTo(node).edgeTo(nodeFinish)
        }.start(context(emptyList(), recordingSink(events)))

        assertEquals(2, attempts)
        assertEquals(listOf("Let me check", "Second block"), events.filterIsInstance<AgentRuntimeEvent.AssistantMessage>().map { it.content })
        assertEquals(listOf("Let me check", "Second block", ""), result.history.map { it.content })
    }

    @Test
    fun `cancelled incomplete stream emits no assistant messages`() = runTest {
        val partial = CompletableDeferred<Unit>()
        val events = mutableListOf<AgentRuntimeEvent>()
        val api = mockk<LLMChatAPI> {
            coEvery { messageStream(any()) } returns flow {
                emit(block(0, "discarded"))
                emit(block(1, "", tool = true))
                partial.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val node = SteerableChatNode(NodesLLM(api, mockk { every { useStreaming } returns true }), ActiveRunInputController())
        val execution = async {
            node.execute(context(emptyList(), recordingSink(events)), GraphRuntime(RetryPolicy(), 10))
        }
        partial.await()
        execution.cancelAndJoin()
        assertTrue(events.filterIsInstance<AgentRuntimeEvent.AssistantMessage>().isEmpty())
    }

    private fun recordingSink(events: MutableList<AgentRuntimeEvent>) = object : AgentRuntimeEventSink {
        override suspend fun emit(event: AgentRuntimeEvent) { events += event }
    }

    private fun block(index: Int, content: String, tool: Boolean = false) = LLMResponse.Chat.Ok(
        choices = listOf(LLMResponse.Choice(
            LLMResponse.Message(
                content, LLMMessageRole.assistant,
                functionCall = if (tool) LLMResponse.FunctionCall("TestTool", emptyMap()) else null,
                functionsStateId = if (tool) "call-$index" else null,
            ),
            index, null,
        )),
        created = 1, model = "test-model", usage = LLMResponse.Usage(1, 1, 2, 0),
    )

    @Test
    fun `chat retries only LLM exceptions and respects the attempt limit`() = runTest {
        val failures = listOf(
            LLMException(LLMResponse.Chat.Error(503, "Provider failure")) to 2,
            IllegalStateException("Unexpected failure") to 1,
            CancellationException("Provider cancelled") to 1,
        )
        for ((failure, expectedAttempts) in failures) {
            var attempts = 0
            val api = mockk<LLMChatAPI> {
                coEvery { message(any()) } answers { attempts += 1; throw failure }
            }
            val nodes = NodesLLM(api, mockk { every { useStreaming } returns false })
            val graph = buildGraph<String, LLMResponse.Chat> {
                nodeInput.edgeTo(nodes.chat()).edgeTo(nodeFinish)
            }

            val thrown = assertFailsWith(failure::class) { graph.start(context(emptyList())) }
            if (failure !is CancellationException) {
                assertEquals(failure.message, thrown.message)
            }
            assertEquals(expectedAttempts, attempts)
        }
    }

    @Test
    fun `streaming chat emits runtime deltas and keeps side effects batching`() = runTest {
        val runtimeEvents = mutableListOf<AgentRuntimeEvent>()
        val chunks = listOf("Hello", " streaming", " world")
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
