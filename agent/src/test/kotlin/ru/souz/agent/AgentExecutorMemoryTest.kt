@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ru.souz.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.ToolInvocationMeta
import ru.souz.memory.CompletedTurnMemoryInput
import ru.souz.memory.ConversationMemoryRuntime
import ru.souz.memory.MemoryPromptAugmentationResult
import ru.souz.memory.MemoryPromptAugmentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class AgentExecutorMemoryTest {
    @Test
    fun `agent executor does not modify system prompt`() = runTest {
        val memoryRuntime = RecordingMemoryRuntime(
            renderedBlock = "Relevant memory:\n- Prefer Kotlin."
        )
        val agent = CapturingAgent(output = "assistant response")

        val result = executor(agent, memoryRuntime).execute(
            agentId = AgentId.GRAPH,
            context = baseContext(),
            input = "hello",
        )

        assertEquals("Base system prompt", agent.executedContexts.single().systemPrompt)
        assertEquals("Base system prompt", result.context.systemPrompt)
    }

    @Test
    fun `capture starts after successful execution without blocking response`() = runTest {
        val memoryRuntime = RecordingMemoryRuntime(blockCapture = true)

        val result = withTimeout(1_000) {
            executor(memoryRuntime = memoryRuntime).execute(
                agentId = AgentId.GRAPH,
                context = baseContext(
                    toolInvocationMeta = ToolInvocationMeta.localDefault(
                        conversationId = "conversation-1",
                        requestId = "request-1",
                        attributes = mapOf(
                            "userMessageId" to "user-message-1",
                            "assistantMessageId" to "assistant-message-1",
                        ),
                    )
                ),
                input = "hello",
            )
        }
        runCurrent()
        val captured = withTimeout(1_000) { memoryRuntime.captureStarted.await() }

        assertEquals("assistant response", result.output)
        assertEquals(
            CompletedTurnMemoryInput(
                conversationId = "conversation-1",
                userMessageId = "user-message-1",
                assistantMessageId = "assistant-message-1",
                userMessage = "hello",
                assistantMessage = "assistant response",
            ),
            captured,
        )
        assertFalse(memoryRuntime.captureFinished.isCompleted)

        memoryRuntime.releaseCapture.complete(Unit)
        withTimeout(1_000) { memoryRuntime.captureFinished.await() }
    }

    @Test
    fun `capture failure does not fail execution`() = runTest {
        val memoryRuntime = RecordingMemoryRuntime(captureFailure = IllegalStateException("capture failed"))

        val result = executor(memoryRuntime = memoryRuntime).execute(
            agentId = AgentId.GRAPH,
            context = baseContext(),
            input = "hello",
        )
        runCurrent()

        assertEquals("assistant response", result.output)
        assertEquals(1, memoryRuntime.capturedTurns.size)
    }

    private fun TestScope.executor(
        agent: CapturingAgent = CapturingAgent(output = "assistant response"),
        memoryRuntime: RecordingMemoryRuntime,
    ): AgentExecutor = AgentExecutor(
        agentProvider = { agent },
        memoryRuntime = memoryRuntime,
        captureScope = backgroundScope,
    )

    private fun baseContext(
        toolInvocationMeta: ToolInvocationMeta = ToolInvocationMeta.localDefault(),
    ): AgentContext<String> = AgentContext(
        input = "",
        settings = AgentSettings(
            model = "model",
            temperature = 0f,
            toolsByCategory = emptyMap(),
        ),
        history = listOf(LLMRequest.Message(LLMMessageRole.system, "Base system prompt")),
        activeTools = emptyList(),
        systemPrompt = "Base system prompt",
        toolInvocationMeta = toolInvocationMeta,
    )

    private class CapturingAgent(
        private val output: String,
        private val onExecute: () -> Unit = {},
    ) : TraceableAgent {
        val executedContexts = mutableListOf<AgentContext<String>>()

        override val sideEffects: Flow<String> = emptyFlow()

        override suspend fun execute(ctx: AgentContext<String>): String =
            executeWithTrace(ctx).output

        override suspend fun executeWithTrace(
            ctx: AgentContext<String>,
            onStep: GraphStepCallback?,
        ): AgentExecutionResult {
            onExecute()
            executedContexts += ctx
            return AgentExecutionResult(
                output = output,
                context = ctx.copy(
                    input = output,
                    history = ctx.history + LLMRequest.Message(LLMMessageRole.assistant, output),
                ),
            )
        }

        override fun cancelActiveJob() = Unit
    }

    private class RecordingMemoryRuntime(
        private val renderedBlock: String = "",
        private val retrieveFailure: Throwable? = null,
        private val captureFailure: Throwable? = null,
        private val blockCapture: Boolean = false,
        private val onRetrieve: () -> Unit = {},
    ) : ConversationMemoryRuntime {
        val capturedTurns = mutableListOf<CompletedTurnMemoryInput>()
        val captureStarted = CompletableDeferred<CompletedTurnMemoryInput>()
        val releaseCapture = CompletableDeferred<Unit>()
        val captureFinished = CompletableDeferred<Unit>()

        override suspend fun retrieveMemory(
            userMessage: String,
            conversationId: String?,
        ): MemoryPromptAugmentationResult {
            onRetrieve()
            retrieveFailure?.let { throw it }
            val facts = if (renderedBlock.isNotBlank()) {
                listOf(MemoryPromptAugmentation.Fact("fact-1", "user", 0.9f))
            } else {
                emptyList()
            }
            return MemoryPromptAugmentationResult(renderedBlock, facts)
        }

        override suspend fun captureCompletedTurn(input: CompletedTurnMemoryInput) {
            capturedTurns += input
            captureStarted.complete(input)
            if (blockCapture) releaseCapture.await()
            captureFailure?.let { throw it }
            captureFinished.complete(Unit)
        }
    }

    private class CollectingEventSink(
        private val events: MutableList<AgentRuntimeEvent>,
    ) : AgentRuntimeEventSink {
        override suspend fun emit(event: AgentRuntimeEvent) {
            events += event
        }
    }
}
