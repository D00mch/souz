@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ru.souz.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import ru.souz.agent.graph.StepInfo
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LocalUserId
import ru.souz.llms.ToolInvocationMeta
import ru.souz.memory.CompletedTurnEvidenceKind
import ru.souz.memory.CompletedTurnMemoryInput
import ru.souz.memory.ConversationMemoryRuntime
import ru.souz.memory.NoopConversationMemoryRuntime
import ru.souz.graph.Node
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentExecutorMemoryTest {
    @Test
    fun `agent executor does not modify system prompt`() = runTest {
        val memoryRuntime = RecordingMemoryRuntime()
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
        assertFalse(memoryRuntime.captureStarted.isCompleted)
        result.captureCompletedTurn()
        runCurrent()
        val captured = withTimeout(1_000) { memoryRuntime.captureStarted.await() }

        assertEquals("assistant response", result.output)
        assertEquals("conversation-1", captured.conversationId)
        assertEquals("conversation-1", captured.context.conversationId?.value)
        assertEquals("conversation-1", captured.context.sessionId?.value)
        assertEquals(LocalUserId.default(), captured.context.ownerId.value)
        assertEquals("user-message-1", captured.userMessageId)
        assertEquals("assistant-message-1", captured.assistantMessageId)
        assertEquals("hello", captured.userMessage)
        assertEquals("assistant response", captured.assistantMessage)
        assertFalse(memoryRuntime.captureFinished.isCompleted)

        memoryRuntime.releaseCapture.complete(Unit)
        withTimeout(1_000) { memoryRuntime.captureFinished.await() }
    }

    @Test
    fun `capture uses runtime owner and conversation scope`() = runTest {
        val memoryRuntime = RecordingMemoryRuntime()

        val result = executor(memoryRuntime = memoryRuntime).execute(
            agentId = AgentId.GRAPH,
            context = baseContext(
                toolInvocationMeta = ToolInvocationMeta(
                    userId = "backend-user",
                    conversationId = "chat-42",
                    requestId = "request-42",
                )
            ),
            input = "hello",
        )
        runCurrent()
        assertFalse(memoryRuntime.captureStarted.isCompleted)
        result.captureCompletedTurn()
        runCurrent()

        val captured = withTimeout(1_000) { memoryRuntime.captureStarted.await() }
        assertEquals("backend-user", captured.context.ownerId.value)
        assertEquals("chat-42", captured.context.conversationId?.value)
        assertEquals("chat-42", captured.context.sessionId?.value)
    }

    @Test
    fun `capture includes tool output evidence from graph step deltas`() = runTest {
        val memoryRuntime = RecordingMemoryRuntime()
        val agent = CapturingAgent(
            output = "assistant response",
            stepHistoryDeltas = listOf(
                listOf(
                    LLMRequest.Message(
                        role = LLMMessageRole.assistant,
                        content = "{\"name\":\"ToolTelegramReadInbox\",\"arguments\":{\"limit\":5}}",
                        functionsStateId = "call-1",
                    ),
                    LLMRequest.Message(
                        role = LLMMessageRole.assistant,
                        content = "I will inspect the tool output and keep the next steps concise.",
                    ),
                ),
                listOf(
                    LLMRequest.Message(
                        role = LLMMessageRole.function,
                        content = "{\"items\":[\"PR 564 needs review\",\"Agent Sandbox link\"]}",
                        functionsStateId = "call-1",
                        name = "ToolTelegramReadInbox",
                    )
                ),
            ),
        )

        val result = executor(agent, memoryRuntime).execute(
            agentId = AgentId.GRAPH,
            context = baseContext(),
            input = "check messages",
        )
        result.captureCompletedTurn()
        runCurrent()

        val captured = withTimeout(1_000) { memoryRuntime.captureStarted.await() }
        assertEquals(
            listOf(
                CompletedTurnEvidenceKind.ASSISTANT_SYNTHESIS,
                CompletedTurnEvidenceKind.TOOL_OUTPUT,
            ),
            captured.evidence.map { it.kind },
        )
        assertEquals("ToolTelegramReadInbox", captured.evidence.single { it.kind == CompletedTurnEvidenceKind.TOOL_OUTPUT }.sourceName)
        assertTrue(captured.evidence.single { it.kind == CompletedTurnEvidenceKind.TOOL_OUTPUT }.text.contains("PR 564"))
        assertFalse(captured.evidence.any { it.text == "assistant response" })
        assertFalse(captured.evidence.any { it.text.contains("ToolTelegramReadInbox") && it.kind == CompletedTurnEvidenceKind.ASSISTANT_SYNTHESIS })
    }

    @Test
    fun `capture keeps repeated tool outputs and enforces total evidence budget`() = runTest {
        val memoryRuntime = RecordingMemoryRuntime()
        val repeated = LLMRequest.Message(
            role = LLMMessageRole.function,
            content = "head-${"x".repeat(6_500)}-tail",
            name = "RepeatedTool",
        )
        val agent = CapturingAgent(
            output = "done",
            stepHistoryDeltas = listOf(
                listOf(repeated),
                listOf(repeated),
                List(10) { index ->
                    LLMRequest.Message(
                        role = LLMMessageRole.function,
                        content = "tool-$index-${"y".repeat(5_900)}",
                        name = "Tool$index",
                    )
                },
            ),
        )

        val result = executor(agent, memoryRuntime).execute(AgentId.GRAPH, baseContext(), "collect evidence")
        result.captureCompletedTurn()
        runCurrent()

        val evidence = withTimeout(1_000) { memoryRuntime.captureStarted.await() }.evidence
        assertEquals(2, evidence.count { it.sourceName == "RepeatedTool" })
        assertTrue(evidence.sumOf { it.text.length } <= 24_000)
        assertTrue(evidence.all { it.text.length <= 6_000 })
        assertTrue(evidence.first().text.startsWith("head-"))
        assertTrue(evidence.first().text.endsWith("-tail"))
        assertTrue(evidence.first().text.contains("...[truncated]..."))
    }

    @Test
    fun `long final assistant answer is not duplicated as synthesis evidence`() = runTest {
        val memoryRuntime = RecordingMemoryRuntime()
        val output = "final-${"z".repeat(7_000)}-tail"
        val agent = CapturingAgent(
            output = output,
            stepHistoryDeltas = listOf(
                listOf(LLMRequest.Message(LLMMessageRole.assistant, output)),
            ),
        )

        val result = executor(agent, memoryRuntime).execute(AgentId.GRAPH, baseContext(), "answer")
        result.captureCompletedTurn()
        runCurrent()

        val captured = withTimeout(1_000) { memoryRuntime.captureStarted.await() }
        assertTrue(captured.evidence.none { it.kind == CompletedTurnEvidenceKind.ASSISTANT_SYNTHESIS })
    }

    @Test
    fun `history compaction captures summary without replaying old assistant messages`() = runTest {
        val memoryRuntime = RecordingMemoryRuntime()
        val system = LLMRequest.Message(LLMMessageRole.system, "Base system prompt")
        val oldAssistant = LLMRequest.Message(LLMMessageRole.assistant, "Old answer from earlier in the session.")
        val summary = LLMRequest.Message(LLMMessageRole.assistant, "Compacted working summary.")
        val agent = CapturingAgent(
            output = "new answer",
            stepHistoryReplacements = listOf(listOf(system, summary, oldAssistant)),
        )

        val result = executor(agent, memoryRuntime).execute(
            AgentId.GRAPH,
            baseContext().copy(history = listOf(system, oldAssistant)),
            "continue",
        )
        result.captureCompletedTurn()
        runCurrent()

        val evidence = withTimeout(1_000) { memoryRuntime.captureStarted.await() }.evidence
        assertEquals(listOf("Compacted working summary."), evidence.map { it.text })
    }

    @Test
    fun `capture failure does not fail execution`() = runTest {
        val memoryRuntime = RecordingMemoryRuntime(captureFailure = IllegalStateException("capture failed"))

        val result = executor(memoryRuntime = memoryRuntime).execute(
            agentId = AgentId.GRAPH,
            context = baseContext(),
            input = "hello",
        )
        result.captureCompletedTurn()
        runCurrent()

        assertEquals("assistant response", result.output)
        assertEquals(1, memoryRuntime.capturedTurns.size)
    }

    @Test
    fun `noop memory runtime does not launch capture job`() = runTest {
        val executor = AgentExecutor(
            agentProvider = { CapturingAgent(output = "assistant response") },
            memoryRuntime = NoopConversationMemoryRuntime,
            captureScope = CoroutineScope(ThrowingDispatcher()),
        )

        val result = executor.execute(
            agentId = AgentId.GRAPH,
            context = baseContext(),
            input = "hello",
        )

        result.captureCompletedTurn()

        assertEquals("assistant response", result.output)
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
        private val stepHistoryDeltas: List<List<LLMRequest.Message>> = emptyList(),
        private val stepHistoryReplacements: List<List<LLMRequest.Message>> = emptyList(),
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
            var current = ctx
            val transitions = if (stepHistoryReplacements.isNotEmpty()) stepHistoryReplacements else stepHistoryDeltas
            transitions.forEachIndexed { index, messages ->
                val nextHistory = if (stepHistoryReplacements.isNotEmpty()) messages else current.history + messages
                val next = current.copy(history = nextHistory)
                onStep?.invoke(
                    StepInfo(index = index, currentGraphIndex = index, graphName = "test", graphDepth = 0),
                    testNode(),
                    current.asAnyContext(),
                    next.asAnyContext(),
                )
                current = next
            }
            return AgentExecutionResult(
                output = output,
                context = current.copy(
                    input = output,
                    history = current.history + LLMRequest.Message(LLMMessageRole.assistant, output),
                ),
            )
        }

        override fun cancelActiveJob() = Unit

        @Suppress("UNCHECKED_CAST")
        private fun AgentContext<String>.asAnyContext(): AgentContext<Any?> = this as AgentContext<Any?>

        private fun testNode(): Node<Any?, Any?> = Node<Any?, Any?>("test-step") { it }
    }

    private class RecordingMemoryRuntime(
        private val captureFailure: Throwable? = null,
        private val blockCapture: Boolean = false,
    ) : ConversationMemoryRuntime {
        val capturedTurns = mutableListOf<CompletedTurnMemoryInput>()
        val captureStarted = CompletableDeferred<CompletedTurnMemoryInput>()
        val releaseCapture = CompletableDeferred<Unit>()
        val captureFinished = CompletableDeferred<Unit>()

        override suspend fun captureCompletedTurn(input: CompletedTurnMemoryInput) {
            capturedTurns += input
            captureStarted.complete(input)
            if (blockCapture) releaseCapture.await()
            captureFailure?.let { throw it }
            captureFinished.complete(Unit)
        }
    }

    private class ThrowingDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            error("No-op memory capture should not dispatch")
        }
    }
}
