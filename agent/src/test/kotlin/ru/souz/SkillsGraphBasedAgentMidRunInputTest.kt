package ru.souz

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import ru.souz.agent.ActiveRunInput
import ru.souz.agent.graph.Node
import ru.souz.agent.nodes.ExecutedToolCall
import ru.souz.agent.nodes.NodesCommon
import ru.souz.agent.nodes.NodesErrorHandling
import ru.souz.agent.nodes.NodesLLM
import ru.souz.agent.nodes.NodesMemory
import ru.souz.agent.nodes.NodesSkillInventory
import ru.souz.agent.nodes.NodesSummarization
import ru.souz.agent.nodes.NodesToolUseWithKnowledge
import ru.souz.agent.nodes.SKILL_INVENTORY_NODE_NAME
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.restJsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillsGraphBasedAgentMidRunInputTest {
    @Test
    fun `active run readiness callback fires after mailbox opens`() = runTest {
        val ready = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val harness = Harness(chatHandler = { _, ctx ->
            firstStarted.complete(Unit)
            release.await()
            ctx.map { finalResponse("done") }
        })

        val execution = async {
            harness.agent.executeWithTrace(
                ctx = harness.context(),
                onActiveRunReady = { ready.complete(Unit) },
            )
        }
        ready.await()

        assertTrue(harness.agent.submitToActiveRun("follow-up after readiness"))
        firstStarted.await()
        release.complete(Unit)

        assertEquals("done", execution.await().output)
        assertEquals("follow-up after readiness", harness.requestHistories.single().last().content)
    }

    @Test
    fun `submissions cancel only the active LLM and drain together in FIFO order`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val replacementStarted = CompletableDeferred<Unit>()
        val releaseReplacement = CompletableDeferred<Unit>()
        val harness = Harness(chatHandler = { call, ctx ->
            when (call) {
                1 -> {
                    firstStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        firstCancelled.complete(Unit)
                    }
                }
                else -> {
                    replacementStarted.complete(Unit)
                    releaseReplacement.await()
                    ctx.map { finalResponse("replacement") }
                }
            }
        })

        val execution = async { harness.agent.executeWithTrace(harness.context()) }
        firstStarted.await()

        assertTrue(harness.agent.submitToActiveRun("first follow-up"))
        assertTrue(harness.agent.submitToActiveRun("second follow-up"))
        firstCancelled.await()
        replacementStarted.await()
        assertTrue(execution.isActive)
        assertEquals(listOf(0L, 2L), harness.streamRevisions)

        assertEquals(
            listOf("first follow-up", "second follow-up"),
            harness.requestHistories[1]
                .filter { it.role == LLMMessageRole.user }
                .takeLast(2)
                .map { it.content },
        )

        releaseReplacement.complete(Unit)
        val result = execution.await()
        assertEquals("replacement", result.output)
        assertFalse(result.context.history.any { it.content == "discarded" })
    }

    @Test
    fun `structured submission preserves history roles before execute input`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val harness = Harness(chatHandler = { call, ctx ->
            if (call == 1) {
                firstStarted.complete(Unit)
                awaitCancellation()
            } else {
                ctx.map { finalResponse("replacement") }
            }
        })

        val execution = async { harness.agent.executeWithTrace(harness.context()) }
        firstStarted.await()
        assertTrue(
            harness.agent.submitToActiveRun {
                ActiveRunInput(
                    history = listOf(
                        LLMRequest.Message(LLMMessageRole.assistant, "client handled the task")
                    ),
                    input = "next execute",
                )
            }
        )

        assertEquals("replacement", execution.await().output)
        val relevant = harness.requestHistories[1].filter {
            it.content in setOf("client handled the task", "next execute")
        }
        assertEquals(
            listOf(LLMMessageRole.assistant, LLMMessageRole.user),
            relevant.map { it.role },
        )
        assertEquals(
            listOf("client handled the task", "next execute"),
            relevant.map { it.content },
        )
    }

    @Test
    fun `submission during tools waits for results and does not cancel the tool`() = runTest {
        val toolStarted = CompletableDeferred<Unit>()
        val releaseTool = CompletableDeferred<Unit>()
        var toolCancelled = false
        val harness = Harness(
            chatHandler = { call, ctx ->
                ctx.map { if (call == 1) toolResponse() else finalResponse("after tool") }
            },
            toolHandler = {
                toolStarted.complete(Unit)
                try {
                    releaseTool.await()
                } finally {
                    toolCancelled = !currentCoroutineContext().isActive
                }
                listOf(
                    ExecutedToolCall(
                        functionCall = functionCall(),
                        message = LLMRequest.Message(
                            role = LLMMessageRole.function,
                            content = "tool-result",
                            name = "TestTool",
                            functionsStateId = "call-1",
                        ),
                    )
                )
            },
        )

        val execution = async { harness.agent.executeWithTrace(harness.context()) }
        toolStarted.await()
        assertTrue(harness.agent.submitToActiveRun("use the result differently"))
        assertFalse(toolCancelled)

        releaseTool.complete(Unit)
        val result = execution.await()
        assertEquals("after tool", result.output)
        assertFalse(toolCancelled)

        val replacementHistory = harness.requestHistories[1]
        val assistantCallIndex = replacementHistory.indexOfFirst {
            it.role == LLMMessageRole.assistant && it.functionsStateId == "call-1"
        }
        val functionResultIndex = replacementHistory.indexOfFirst {
            it.role == LLMMessageRole.function && it.content == "tool-result"
        }
        val queuedInputIndex = replacementHistory.indexOfFirst {
            it.role == LLMMessageRole.user && it.content == "use the result differently"
        }
        assertTrue(assistantCallIndex >= 0)
        assertTrue(functionResultIndex > assistantCallIndex)
        assertTrue(queuedInputIndex > functionResultIndex)
    }

    @Test
    fun `submission while LLM proposes a tool prevents stale tool execution`() = runTest {
        val proposalStarted = CompletableDeferred<Unit>()
        var toolInvocations = 0
        val harness = Harness(
            chatHandler = { call, ctx ->
                if (call == 1) {
                    proposalStarted.complete(Unit)
                    awaitCancellation()
                } else {
                    ctx.map { finalResponse("replanned") }
                }
            },
            toolHandler = {
                toolInvocations += 1
                emptyList()
            },
        )

        val execution = async { harness.agent.executeWithTrace(harness.context()) }
        proposalStarted.await()
        assertTrue(harness.agent.submitToActiveRun("do not run that tool"))
        val result = execution.await()

        assertEquals("replanned", result.output)
        assertEquals(0, toolInvocations)
        assertTrue(harness.requestHistories[1].any { it.content == "do not run that tool" })
        assertFalse(harness.requestHistories[1].any { it.functionsStateId == "call-1" })
    }

    @Test
    fun `submission while final response is provisional replans before finalization`() = runTest {
        val provisionalStarted = CompletableDeferred<Unit>()
        val harness = Harness(chatHandler = { call, ctx ->
            if (call == 1) {
                provisionalStarted.complete(Unit)
                awaitCancellation()
            } else {
                ctx.map { finalResponse("accepted") }
            }
        })

        val execution = async { harness.agent.executeWithTrace(harness.context()) }
        provisionalStarted.await()
        assertTrue(harness.agent.submitToActiveRun("one more requirement"))
        val result = execution.await()

        assertEquals("accepted", result.output)
        assertEquals(1, harness.finalizationCount)
        assertTrue(harness.requestHistories[1].any { it.content == "one more requirement" })
        assertFalse(result.context.history.any { it.content == "provisional" })
        assertTrue(result.context.history.any { it.content == "accepted" })
    }

    @Test
    fun `final sealing rejects submissions while finalization runs`() = runTest {
        val finalizationStarted = CompletableDeferred<Unit>()
        val releaseFinalization = CompletableDeferred<Unit>()
        val harness = Harness(
            chatHandler = { _, ctx -> ctx.map { finalResponse("sealed") } },
            onFinalize = {
                finalizationStarted.complete(Unit)
                releaseFinalization.await()
            },
        )

        val execution = async { harness.agent.executeWithTrace(harness.context()) }
        finalizationStarted.await()

        assertFalse(harness.agent.submitToActiveRun("too late"))
        assertTrue(execution.isActive)
        releaseFinalization.complete(Unit)

        assertEquals("sealed", execution.await().output)
        assertEquals(1, harness.finalizationCount)
    }

    @Test
    fun `whole graph cancellation closes the run and queued input does not leak`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val harness = Harness(chatHandler = { call, ctx ->
            if (call == 1) {
                firstStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    firstCancelled.complete(Unit)
                }
            } else {
                ctx.map { finalResponse("new run") }
            }
        })

        val firstExecution = async { harness.agent.executeWithTrace(harness.context("first run")) }
        firstStarted.await()
        assertTrue(harness.agent.submitToActiveRun("old queued input"))
        harness.agent.cancelActiveJob()

        assertFailsWith<CancellationException> { firstExecution.await() }
        firstCancelled.await()
        assertFalse(harness.agent.submitToActiveRun("after cancellation"))

        val secondResult = harness.agent.executeWithTrace(harness.context("second run"))
        assertEquals("new run", secondResult.output)
        assertFalse(harness.requestHistories.last().any { it.content.contains("old queued input") })
        assertTrue(harness.requestHistories.last().any { it.content == "second run" })
    }

    @Test
    fun `unrelated LLM cancellation is not converted into replanning`() = runTest {
        val harness = Harness(chatHandler = { _, _ -> throw CancellationException("provider cancelled") })

        assertFailsWith<CancellationException> {
            harness.agent.executeWithTrace(harness.context())
        }
        assertEquals(1, harness.chatCallCount)
        assertFalse(harness.agent.submitToActiveRun("not accepted"))
        assertEquals(0, harness.finalizationCount)
    }
}

private suspend fun SkillsGraphBasedAgent.submitToActiveRun(input: String): Boolean =
    submitToActiveRun { ActiveRunInput(input = input) }

private typealias ChatHandler = suspend (
    call: Int,
    context: AgentContext<String>,
) -> AgentContext<LLMResponse.Chat>

private typealias ToolHandler = suspend (
    context: AgentContext<LLMResponse.Chat.Ok>,
) -> List<ExecutedToolCall>

private class Harness(
    chatHandler: ChatHandler,
    toolHandler: ToolHandler = { emptyList() },
    onFinalize: suspend () -> Unit = {},
) {
    private val nodesLLM = mockk<NodesLLM>()
    private val nodesCommon = mockk<NodesCommon>()
    private val nodesErrorHandling = mockk<NodesErrorHandling>()
    private val nodesSummarization = mockk<NodesSummarization>()
    private val nodesMemory = mockk<NodesMemory>()
    private val nodesSkillInventory = mockk<NodesSkillInventory>()

    val requestHistories = mutableListOf<List<LLMRequest.Message>>()
    val streamRevisions = mutableListOf<Long>()
    var chatCallCount = 0
        private set
    var finalizationCount = 0
        private set

    val agent: SkillsGraphBasedAgent

    init {
        every { nodesLLM.sideEffects } returns emptyFlow()
        every { nodesCommon.inputToHistory() } returns Node("Input->History") { ctx ->
            val history = ArrayList(ctx.history).apply {
                if (isEmpty()) add(LLMRequest.Message(LLMMessageRole.system, ctx.systemPrompt))
                add(LLMRequest.Message(LLMMessageRole.user, ctx.input))
            }
            ctx.map(history = history)
        }
        every { nodesMemory.recall() } returns Node("Memory recall") { it }
        every { nodesSkillInventory.restrictToTools(any(), any()) } answers { firstArg() }
        every { nodesSkillInventory.node(any(), SKILL_INVENTORY_NODE_NAME) } returns
            Node(SKILL_INVENTORY_NODE_NAME) { it }
        every { nodesCommon.nodeAppendAdditionalData() } returns Node("appendActualInformation") { it }
        every { nodesLLM.provisionalChat("LLM request", any()) } answers {
            streamRevisions += secondArg<Long>()
            Node("LLM request") { ctx ->
                chatCallCount += 1
                requestHistories += ctx.history.toList()
                chatHandler(chatCallCount, ctx)
            }
        }
        coEvery { nodesCommon.executeFunctionCalls(any()) } coAnswers {
            toolHandler(firstArg())
        }
        every { nodesSummarization.summarize() } returns Node("Summary") { ctx ->
            ctx.map { responseContent(ctx.input) }
        }
        every { nodesMemory.finalizeTurn(any()) } returns Node("Memory-aware finalization") { ctx ->
            finalizationCount += 1
            onFinalize()
            ctx.map { responseContent(ctx.input) }
        }
        every { nodesErrorHandling.chatErrorToFinish() } returns Node("Chat.Error") { ctx ->
            ctx.map { "error" }
        }

        val nodesToolUse = NodesToolUseWithKnowledge(nodesCommon, knowledgeStore = null)
        agent = SkillsGraphBasedAgent(
            logObjectMapper = restJsonMapper,
            nodesLLM = nodesLLM,
            nodesCommon = nodesCommon,
            nodesErrorHandling = nodesErrorHandling,
            nodesSummarization = nodesSummarization,
            nodesMemory = nodesMemory,
            nodesSkillInventory = nodesSkillInventory,
            nodesToolUseWithKnowledge = nodesToolUse,
            coreTools = testCoreTools(),
        )
    }

    fun context(input: String = "initial request"): AgentContext<String> = AgentContext(
        input = input,
        settings = AgentSettings(
            model = "test-model",
            temperature = 0f,
            toolsByCategory = emptyMap(),
        ),
        history = emptyList(),
        activeTools = emptyList(),
        systemPrompt = "system",
    )
}

private fun finalResponse(content: String): LLMResponse.Chat.Ok = LLMResponse.Chat.Ok(
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
    created = 1,
    model = "test-model",
    usage = LLMResponse.Usage(1, 1, 2, 0),
)

private fun toolResponse(): LLMResponse.Chat.Ok = LLMResponse.Chat.Ok(
    choices = listOf(
        LLMResponse.Choice(
            message = LLMResponse.Message(
                content = "",
                role = LLMMessageRole.assistant,
                functionCall = functionCall(),
                functionsStateId = "call-1",
            ),
            index = 0,
            finishReason = LLMResponse.FinishReason.function_call,
        )
    ),
    created = 1,
    model = "test-model",
    usage = LLMResponse.Usage(1, 1, 2, 0),
)

private fun functionCall(): LLMResponse.FunctionCall =
    LLMResponse.FunctionCall(name = "TestTool", arguments = emptyMap())

private fun responseContent(response: LLMResponse.Chat.Ok): String =
    response.choices.lastOrNull()?.message?.content.orEmpty()
