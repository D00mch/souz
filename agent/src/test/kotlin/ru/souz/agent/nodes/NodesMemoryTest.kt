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
import ru.souz.agent.graph.RetryPolicy
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LlmProvider
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
import kotlin.test.assertTrue

class NodesMemoryTest {
    @Test
    fun `agent module recalls scoped memory and emits its augmentation event`() = runTest {
        val memoryRuntime = RecordingMemoryRuntime(
            retrievalResult = memoryResult("Relevant memory:\n- User prefers Kotlin",
                facts = listOf(MemoryPromptFact("fact-1", "user", 0.9f))),
        )
        val di = DI {
            bindSingleton<ConversationMemoryRuntime> { memoryRuntime }
            bindSingleton<CoroutineScope> { backgroundScope }
            import(agentDiModule())
        }
        val nodesMemory: NodesMemory = di.direct.instance()
        val events = mutableListOf<AgentRuntimeEvent>()
        val context = stringContext(
            input = "hello",
            meta = ToolInvocationMeta(
                userId = "backend-user",
                conversationId = "backend-chat",
                requestId = "request-1",
            ),
        ).copy(runtimeEventSink = object : AgentRuntimeEventSink {
            override suspend fun emit(event: AgentRuntimeEvent) { events += event }
        })

        val result = nodesMemory.recall().execute(context, graphRuntime())

        val request = assertNotNull(memoryRuntime.retrievalRequest)
        assertEquals("backend-user", request.context.ownerId.value)
        assertEquals("backend-chat", request.context.conversationId?.value)
        assertEquals("backend-chat", request.context.sessionId?.value)
        assertEquals("hello", request.query)
        val memory = result.history.single(LLMRequest.Message::isInjectedMemoryContextMessage)
        assertEquals(result.history.lastIndex - 1, result.history.indexOf(memory))
        assertEquals("souz_injected_memory", memory.name)
        assertTrue(memory.content.contains("User prefers Kotlin"))
        val event = events.filterIsInstance<AgentRuntimeEvent.MemoryPromptAugmented>().single()
        assertEquals("Relevant memory:\n- User prefers Kotlin", event.addedBlock)
        assertEquals("fact-1", event.facts.single().factId)
    }

    @Test
    fun `recall replaces only structurally injected memory across turns`() = runTest {
        val prompt = "<souz_memory_context>\nuser-authored content\n</souz_memory_context>"
        val memoryRuntime = RecordingMemoryRuntime(
            retrievalResult = memoryResult("Relevant memory:\n- Previous recall"),
        )
        val nodesMemory = NodesMemory(memoryRuntime, backgroundScope)
        val ordinaryContext = LLMRequest.Message(LLMMessageRole.user, "<context>ordinary context</context>")
        val userAuthoredMemoryTags = LLMRequest.Message(LLMMessageRole.user, prompt)
        val firstTurnContext = stringContext(prompt).copy(
            history = listOf(
                "system".toSystemPromptMessage(),
                memoryMessage("Stale recall"),
                ordinaryContext,
                userAuthoredMemoryTags,
            ),
            activeExchangeStartIndex = 3,
        )
        val firstTurn = nodesMemory.recall().execute(firstTurnContext, graphRuntime())
        memoryRuntime.retrievalResult = memoryResult("Relevant memory:\n- Fresh recall")
        val nextUserMessage = LLMRequest.Message(LLMMessageRole.user, "next question")
        val nextTurnContext = NodesPlain.inputToHistory().execute(
            firstTurn.copy(input = nextUserMessage.content,
                history = firstTurn.history + LLMRequest.Message(LLMMessageRole.assistant, "first answer")),
            graphRuntime(),
        )

        val result = nodesMemory.recall().execute(nextTurnContext, graphRuntime())

        assertTrue(ordinaryContext in result.history)
        assertTrue(userAuthoredMemoryTags in result.history)
        assertFalse(result.history.any { it.content.contains("Stale recall") })
        assertFalse(result.history.any { it.content.contains("Previous recall") })
        val injectedMemory = result.history.withIndex()
            .filter { it.value.isInjectedMemoryContextMessage() }
            .single()
        assertTrue(injectedMemory.value.content.contains("Fresh recall"))
        assertEquals(result.history.lastIndex - 1, injectedMemory.index)
        assertEquals(injectedMemory.index, result.activeExchangeStartIndex)
        assertEquals(nextUserMessage, result.history.last())
    }

    @Test
    fun `disabled empty or failed recall removes stale memory and preserves client history`() = runTest {
        val clientMemory = LLMRequest.Message(LLMMessageRole.function, "Client recalled fact", name = "RunSkillCommand")
        val history = listOf("system".toSystemPromptMessage(), clientMemory, LLMRequest.Message(LLMMessageRole.user, "hello"))
        val context = stringContext("hello").copy(history = listOf(memoryMessage("stale")) + history, activeExchangeStartIndex = 3)
        listOf(
            false to RecordingMemoryRuntime(retrievalResult = memoryResult("Unwanted recall")),
            true to RecordingMemoryRuntime(),
            true to RecordingMemoryRuntime(retrievalFailure = IllegalStateException("offline")),
        ).forEach { (automaticRecall, memoryRuntime) ->
            val result = NodesMemory(memoryRuntime, backgroundScope, automaticMemoryRecall = automaticRecall)
                .recall().execute(context, graphRuntime())

            assertEquals(automaticRecall, memoryRuntime.retrievalRequest != null)
            assertEquals(history, result.history)
            assertEquals(2, result.activeExchangeStartIndex)
        }
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
    fun `finalization captures scoped turn without blocking on capture`() = runTest {
        val captureGate = CompletableDeferred<Unit>()
        val memoryRuntime = RecordingMemoryRuntime(captureGate = captureGate)
        val nodesMemory = NodesMemory(memoryRuntime, backgroundScope)
        val meta = ToolInvocationMeta(
            userId = "backend-user",
            conversationId = "conversation-1",
            requestId = "request-1",
            timeZone = "Europe/Moscow",
            attributes = mapOf(
                "userMessageId" to "user-message-1",
                "assistantMessageId" to "assistant-message-1",
            ),
        )
        val result = withTimeout(1_000) {
            nodesMemory.finalizeTurn().execute(
                completedContext(userMessage = "hello", output = "assistant response", meta = meta),
                graphRuntime(),
            )
        }
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
        assertEquals("Europe/Moscow", captured.timeZone)
        assertFalse(memoryRuntime.captureFinished.isCompleted)

        captureGate.complete(Unit)
        withTimeout(1_000) { memoryRuntime.captureFinished.await() }
    }

    @Test
    fun `capture derives evidence from the completed exchange`() = runTest {
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
        nodesMemory.finalizeTurn().execute(context, graphRuntime())
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
            .finalizeTurn()
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
    fun `capture failure is isolated and noop runtime does not dispatch`() = runTest {
        val failingRuntime = RecordingMemoryRuntime(captureFailure = IllegalStateException("capture failed"))
        val context = completedContext("hello", "answer")

        val result = NodesMemory(failingRuntime, backgroundScope)
            .finalizeTurn()
            .execute(context, graphRuntime())
        runCurrent()

        assertEquals("answer", result.input)
        assertEquals(1, failingRuntime.capturedTurns.size)

        val noopResult = NodesMemory(
            memoryRuntime = NoopConversationMemoryRuntime,
            captureScope = CoroutineScope(ThrowingDispatcher()),
        ).finalizeTurn().execute(context, graphRuntime())
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
        activeExchangeStartIndex = 1,
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
        provider = LlmProvider.OPENAI,
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
