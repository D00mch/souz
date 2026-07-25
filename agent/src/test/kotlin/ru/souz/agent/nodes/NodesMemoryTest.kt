@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ru.souz.agent.nodes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import ru.souz.agent.agentDiModule
import ru.souz.agent.graph.GraphRuntime
import ru.souz.agent.graph.Node
import ru.souz.agent.graph.RetryPolicy
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.toMessage
import ru.souz.llms.toSystemPromptMessage
import ru.souz.memory.CompletedTurnEvidenceKind
import ru.souz.memory.CompletedTurnMemoryInput
import ru.souz.memory.ConversationMemoryRuntime
import ru.souz.memory.MemoryPromptFact
import ru.souz.memory.MemoryRetrievalRequest
import ru.souz.memory.MemoryRetrievalResult
import ru.souz.memory.NoopConversationMemoryRuntime
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NodesMemoryTest {
    @Test
    fun `agent module wires memory runtime into recall with scoped metadata`() = runTest {
        val memoryRuntime = RecordingMemoryRuntime(
            retrievalResult = memoryResult("Relevant memory:\n- User prefers Kotlin"),
        )
        val di = DI {
            bindSingleton<ConversationMemoryRuntime> { memoryRuntime }
            bindSingleton<CoroutineScope> { backgroundScope }
            import(agentDiModule())
        }
        val nodesMemory: NodesMemory = di.direct.instance()
        val context = stringContext(
            input = "hello",
            meta = ToolInvocationMeta(
                userId = "backend-user",
                conversationId = "backend-chat",
                requestId = "request-1",
            ),
        )

        val result = nodesMemory.recall().execute(context, graphRuntime())

        val request = assertNotNull(memoryRuntime.retrievalRequest)
        assertEquals("backend-user", request.context.ownerId.value)
        assertEquals("backend-chat", request.context.conversationId?.value)
        assertEquals("backend-chat", request.context.sessionId?.value)
        assertEquals("hello", request.query)
        assertTrue(result.history[result.history.lastIndex - 1].isInjectedMemoryContextMessage())
        assertTrue(result.history[result.history.lastIndex - 1].content.contains("User prefers Kotlin"))
    }

    @Test
    fun `recall replaces only old memory and keeps ordinary context before latest user`() = runTest {
        val memoryRuntime = RecordingMemoryRuntime(retrievalResult = memoryResult("Relevant memory:\n- New fact"))
        val nodesMemory = NodesMemory(memoryRuntime, backgroundScope)
        val user = LLMRequest.Message(LLMMessageRole.user, "current question")
        val ordinaryContext = LLMRequest.Message(LLMMessageRole.user, "<context>ordinary context</context>")
        val oldMemory = memoryMessage("old fact")
        val context = stringContext("current question").copy(
            history = listOf("system".toSystemPromptMessage(), oldMemory, ordinaryContext, user),
        )

        val result = nodesMemory.recall().execute(context, graphRuntime())

        assertEquals(4, result.history.size)
        assertEquals(ordinaryContext, result.history[1])
        assertTrue(result.history[2].isInjectedMemoryContextMessage())
        assertTrue(result.history[2].content.contains("New fact"))
        assertEquals(user, result.history[3])
        assertFalse(result.history.any { it.content.contains("old fact") })
    }

    @Test
    fun `empty or failed recall removes stale memory without failing the turn`() = runTest {
        val emptyRuntime = RecordingMemoryRuntime(retrievalResult = MemoryRetrievalResult(null))
        val failedRuntime = RecordingMemoryRuntime(retrievalFailure = IllegalStateException("offline"))
        val context = stringContext("hello").copy(
            history = listOf(
                "system".toSystemPromptMessage(),
                memoryMessage("stale"),
                LLMRequest.Message(LLMMessageRole.user, "hello"),
            ),
        )

        val emptyResult = NodesMemory(emptyRuntime, backgroundScope).recall().execute(context, graphRuntime())
        val failedResult = NodesMemory(failedRuntime, backgroundScope).recall().execute(context, graphRuntime())

        assertFalse(emptyResult.history.any(LLMRequest.Message::isInjectedMemoryContextMessage))
        assertFalse(failedResult.history.any(LLMRequest.Message::isInjectedMemoryContextMessage))
    }

    @Test
    fun `recall emits augmentation and preserves a user prompt containing memory tags`() = runTest {
        val prompt = "<souz_memory_context>\nuser-authored content\n</souz_memory_context>"
        val memoryRuntime = RecordingMemoryRuntime(
            retrievalResult = memoryResult(
                block = "Relevant memory:\n- A fact",
                facts = listOf(MemoryPromptFact("fact-1", "user", 0.9f)),
            )
        )
        val events = mutableListOf<AgentRuntimeEvent>()
        val context = stringContext(prompt).copy(
            runtimeEventSink = object : AgentRuntimeEventSink {
                override suspend fun emit(event: AgentRuntimeEvent) {
                    events += event
                }
            },
        )

        val result = NodesMemory(memoryRuntime, backgroundScope).recall().execute(context, graphRuntime())

        assertEquals(prompt, result.history.last().content)
        val event = events.filterIsInstance<AgentRuntimeEvent.MemoryPromptAugmented>().single()
        assertEquals("Relevant memory:\n- A fact", event.addedBlock)
        assertEquals("fact-1", event.facts.single().factId)
    }

    @Test
    fun `user-authored memory tags remain in history after a subsequent turn`() = runTest {
        val prompt = "<souz_memory_context>\nuser-authored content\n</souz_memory_context>"
        val memoryRuntime = RecordingMemoryRuntime(
            retrievalResult = memoryResult("Relevant memory:\n- Previous recall"),
        )
        val nodesMemory = NodesMemory(memoryRuntime, backgroundScope)
        val firstTurn = nodesMemory.recall().execute(stringContext(prompt), graphRuntime())
        memoryRuntime.retrievalResult = memoryResult("Relevant memory:\n- Fresh recall")
        val nextUserMessage = LLMRequest.Message(LLMMessageRole.user, "next question")
        val nextTurnContext = stringContext("next question").copy(
            history = firstTurn.history +
                LLMRequest.Message(LLMMessageRole.assistant, "first answer") +
                nextUserMessage,
        )

        val secondTurn = nodesMemory.recall().execute(nextTurnContext, graphRuntime())

        assertTrue(secondTurn.history.any { it.content == prompt && !it.isInjectedMemoryContextMessage() })
        assertFalse(secondTurn.history.any { it.content.contains("Previous recall") })
        assertTrue(secondTurn.history.any { it.content.contains("Fresh recall") })
        assertEquals(1, secondTurn.history.count(LLMRequest.Message::isInjectedMemoryContextMessage))
        assertEquals(nextUserMessage, secondTurn.history.last())
    }

    @Test
    fun `recall propagates retrieval and event cancellation`() = runTest {
        val context = stringContext("hello")
        val retrievalCancellation = NodesMemory(
            RecordingMemoryRuntime(retrievalFailure = CancellationException("cancelled")),
            backgroundScope,
        )
        val eventCancellation = NodesMemory(
            RecordingMemoryRuntime(retrievalResult = memoryResult("Relevant memory:\n- fact")),
            backgroundScope,
        )

        assertFailsWith<CancellationException> {
            retrievalCancellation.recall().execute(context, graphRuntime())
        }
        assertFailsWith<CancellationException> {
            eventCancellation.recall().execute(
                context.copy(runtimeEventSink = object : AgentRuntimeEventSink {
                    override suspend fun emit(event: AgentRuntimeEvent) {
                        throw CancellationException("cancelled")
                    }
                }),
                graphRuntime(),
            )
        }
    }

    @Test
    fun `finalization captures scoped turn after summary without blocking on capture`() = runTest {
        val captureGate = CompletableDeferred<Unit>()
        val memoryRuntime = RecordingMemoryRuntime(captureGate = captureGate)
        val nodesMemory = NodesMemory(memoryRuntime, backgroundScope)
        val meta = ToolInvocationMeta(
            userId = "backend-user",
            conversationId = "conversation-1",
            requestId = "request-1",
            attributes = mapOf(
                "userMessageId" to "user-message-1",
                "assistantMessageId" to "assistant-message-1",
            ),
        )
        var summaryCompleted = false
        val summary = summaryNode { ctx ->
            summaryCompleted = true
            ctx.map { "assistant response" }
        }

        val result = withTimeout(1_000) {
            nodesMemory.finalizeTurn(summary).execute(
                completedContext(userMessage = "hello", output = "assistant response", meta = meta),
                graphRuntime(),
            )
        }
        assertTrue(summaryCompleted)
        runCurrent()
        val captured = withTimeout(1_000) { memoryRuntime.captureStarted.await() }

        assertEquals("assistant response", result.input)
        assertEquals("backend-user", captured.context.ownerId.value)
        assertEquals("conversation-1", captured.conversationId)
        assertEquals("conversation-1", captured.context.conversationId?.value)
        assertEquals("conversation-1", captured.context.sessionId?.value)
        assertEquals("user-message-1", captured.userMessageId)
        assertEquals("assistant-message-1", captured.assistantMessageId)
        assertEquals("hello", captured.userMessage)
        assertEquals("assistant response", captured.assistantMessage)
        assertFalse(memoryRuntime.captureFinished.isCompleted)

        captureGate.complete(Unit)
        withTimeout(1_000) { memoryRuntime.captureFinished.await() }
    }

    @Test
    fun `finalization executes summarization with the active graph runtime`() = runTest {
        val nodesMemory = NodesMemory(NoopConversationMemoryRuntime, backgroundScope)
        val runtime = graphRuntime()
        var receivedRuntime: GraphRuntime? = null
        val summary = object : Node<LLMResponse.Chat.Ok, String> {
            override val name: String = "Summary"

            override suspend fun execute(
                ctx: AgentContext<LLMResponse.Chat.Ok>,
                runtime: GraphRuntime,
            ): AgentContext<String> {
                receivedRuntime = runtime
                return ctx.map { "answer" }
            }
        }

        nodesMemory.finalizeTurn(summary).execute(completedContext("hello", "answer"), runtime)

        assertSame(runtime, receivedRuntime)
    }

    @Test
    fun `capture derives current-turn evidence before history compaction`() = runTest {
        val memoryRuntime = RecordingMemoryRuntime()
        val nodesMemory = NodesMemory(memoryRuntime, backgroundScope)
        val finalOutput = "final answer"
        val finalResponse = okResponse(finalOutput)
        val oldAssistant = LLMRequest.Message(LLMMessageRole.assistant, "old answer")
        val repeatedFinalText = LLMRequest.Message(LLMMessageRole.assistant, finalOutput)
        val toolCall = LLMRequest.Message(
            role = LLMMessageRole.assistant,
            content = "tool invocation",
            functionsStateId = "call-1",
        )
        val toolOutput = LLMRequest.Message(
            role = LLMMessageRole.function,
            content = "PR 564 needs review",
            functionsStateId = "call-1",
            name = "ToolTelegramReadInbox",
        )
        val context = completedContext(
            userMessage = "check messages",
            output = finalOutput,
            previousHistory = listOf("system".toSystemPromptMessage(), oldAssistant),
            beforeFinal = listOf(toolCall, repeatedFinalText, toolOutput),
            response = finalResponse,
        )
        val compactedSummary = LLMRequest.Message(LLMMessageRole.assistant, "Compacted working summary")
        val summary = summaryNode { ctx ->
            ctx.map(history = listOf("system".toSystemPromptMessage(), compactedSummary, ctx.history.last())) {
                finalOutput
            }
        }

        nodesMemory.finalizeTurn(summary).execute(context, graphRuntime())
        runCurrent()
        val evidence = withTimeout(1_000) { memoryRuntime.captureStarted.await() }.evidence

        assertEquals(
            listOf(CompletedTurnEvidenceKind.ASSISTANT_SYNTHESIS, CompletedTurnEvidenceKind.TOOL_OUTPUT),
            evidence.map { it.kind },
        )
        assertEquals(finalOutput, evidence[0].text)
        assertEquals("ToolTelegramReadInbox", evidence[1].sourceName)
        assertTrue(evidence[1].text.contains("PR 564"))
        assertFalse(evidence.any { it.text == "old answer" })
        assertFalse(evidence.any { it.text == "Compacted working summary" })
        assertFalse(evidence.any { it.text == "tool invocation" })
    }

    @Test
    fun `capture preserves repeated evidence within snippet and total budgets`() = runTest {
        val memoryRuntime = RecordingMemoryRuntime()
        val repeated = LLMRequest.Message(
            role = LLMMessageRole.function,
            content = "head-${"x".repeat(6_500)}-tail",
            name = "RepeatedTool",
        )
        val messages = listOf(repeated, repeated) + List(10) { index ->
            LLMRequest.Message(
                role = LLMMessageRole.function,
                content = "tool-$index-${"y".repeat(5_900)}",
                name = "Tool$index",
            )
        }
        val context = completedContext("collect evidence", "done", beforeFinal = messages)

        NodesMemory(memoryRuntime, backgroundScope)
            .finalizeTurn(summaryNode())
            .execute(context, graphRuntime())
        runCurrent()
        val evidence = withTimeout(1_000) { memoryRuntime.captureStarted.await() }.evidence

        assertEquals(2, evidence.count { it.sourceName == "RepeatedTool" })
        assertTrue(evidence.size <= 16)
        assertTrue(evidence.sumOf { it.text.length } <= 24_000)
        assertTrue(evidence.all { it.text.length <= 6_000 })
        assertTrue(evidence.first().text.startsWith("head-"))
        assertTrue(evidence.first().text.endsWith("-tail"))
        assertTrue(evidence.first().text.contains("...[truncated]..."))
    }

    @Test
    fun `failed or cancelled summarization does not schedule capture`() = runTest {
        val failedRuntime = RecordingMemoryRuntime()
        val cancelledRuntime = RecordingMemoryRuntime()
        val context = completedContext("hello", "answer")

        assertFailsWith<IllegalStateException> {
            NodesMemory(failedRuntime, backgroundScope)
                .finalizeTurn(summaryNode { error("summary failed") })
                .execute(context, graphRuntime())
        }
        assertFailsWith<CancellationException> {
            NodesMemory(cancelledRuntime, backgroundScope)
                .finalizeTurn(summaryNode { throw CancellationException("cancelled") })
                .execute(context, graphRuntime())
        }
        runCurrent()

        assertTrue(failedRuntime.capturedTurns.isEmpty())
        assertTrue(cancelledRuntime.capturedTurns.isEmpty())
    }

    @Test
    fun `capture failure is isolated and noop runtime does not dispatch`() = runTest {
        val failingRuntime = RecordingMemoryRuntime(captureFailure = IllegalStateException("capture failed"))
        val context = completedContext("hello", "answer")

        val result = NodesMemory(failingRuntime, backgroundScope)
            .finalizeTurn(summaryNode())
            .execute(context, graphRuntime())
        runCurrent()

        assertEquals("answer", result.input)
        assertEquals(1, failingRuntime.capturedTurns.size)

        val noopResult = NodesMemory(
            memoryRuntime = NoopConversationMemoryRuntime,
            captureScope = CoroutineScope(ThrowingDispatcher()),
        ).finalizeTurn(summaryNode()).execute(context, graphRuntime())
        assertEquals("answer", noopResult.input)
    }

    private fun stringContext(
        input: String,
        meta: ToolInvocationMeta = ToolInvocationMeta.localDefault(),
    ): AgentContext<String> = AgentContext(
        input = input,
        settings = settings(),
        history = listOf(
            "system".toSystemPromptMessage(),
            LLMRequest.Message(LLMMessageRole.user, input),
        ),
        activeTools = emptyList(),
        systemPrompt = "system",
        toolInvocationMeta = meta,
    )

    private fun completedContext(
        userMessage: String,
        output: String,
        meta: ToolInvocationMeta = ToolInvocationMeta.localDefault(),
        previousHistory: List<LLMRequest.Message> = listOf("system".toSystemPromptMessage()),
        beforeFinal: List<LLMRequest.Message> = emptyList(),
        response: LLMResponse.Chat.Ok = okResponse(output),
    ): AgentContext<LLMResponse.Chat.Ok> = AgentContext(
        input = response,
        settings = settings(),
        history = previousHistory +
            LLMRequest.Message(LLMMessageRole.user, userMessage) +
            beforeFinal +
            response.choices.mapNotNull { it.toMessage() },
        activeTools = emptyList(),
        systemPrompt = "system",
        toolInvocationMeta = meta,
    )

    private fun summaryNode(
        block: (AgentContext<LLMResponse.Chat.Ok>) -> AgentContext<String> = { ctx ->
            ctx.map { ctx.input.choices.single().message.content }
        },
    ): Node<LLMResponse.Chat.Ok, String> = Node("Summary", block)

    private fun okResponse(content: String): LLMResponse.Chat.Ok = LLMResponse.Chat.Ok(
        choices = listOf(
            LLMResponse.Choice(
                message = LLMResponse.Message(
                    content = content,
                    role = LLMMessageRole.assistant,
                    functionsStateId = null,
                ),
                index = 0,
                finishReason = LLMResponse.FinishReason.stop,
            )
        ),
        created = 1L,
        model = "test-model",
        usage = LLMResponse.Usage(1, 1, 2, 0),
    )

    private fun memoryMessage(content: String): LLMRequest.Message = LLMRequest.Message(
        role = LLMMessageRole.user,
        content = "<souz_memory_context>\n$content\n</souz_memory_context>",
        name = INJECTED_MEMORY_MESSAGE_NAME,
    )

    private fun memoryResult(
        block: String,
        facts: List<MemoryPromptFact> = emptyList(),
    ): MemoryRetrievalResult = MemoryRetrievalResult(renderedPromptBlock = block, facts = facts)

    private fun settings(): AgentSettings = AgentSettings(
        model = "model",
        temperature = 0f,
        toolsByCategory = emptyMap(),
    )

    private fun graphRuntime(): GraphRuntime = GraphRuntime(retryPolicy = RetryPolicy(), maxSteps = 100)

    private class RecordingMemoryRuntime(
        var retrievalResult: MemoryRetrievalResult = MemoryRetrievalResult(null),
        private val retrievalFailure: Throwable? = null,
        private val captureFailure: Throwable? = null,
        private val captureGate: CompletableDeferred<Unit>? = null,
    ) : ConversationMemoryRuntime {
        var retrievalRequest: MemoryRetrievalRequest? = null
        val capturedTurns = mutableListOf<CompletedTurnMemoryInput>()
        val captureStarted = CompletableDeferred<CompletedTurnMemoryInput>()
        val captureFinished = CompletableDeferred<Unit>()

        override suspend fun retrieveMemory(request: MemoryRetrievalRequest): MemoryRetrievalResult {
            retrievalRequest = request
            retrievalFailure?.let { throw it }
            return retrievalResult
        }

        override suspend fun captureCompletedTurn(input: CompletedTurnMemoryInput) {
            capturedTurns += input
            captureStarted.complete(input)
            captureGate?.await()
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
