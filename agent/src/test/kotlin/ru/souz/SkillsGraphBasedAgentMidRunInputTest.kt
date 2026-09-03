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
import ru.souz.agent.ActiveRunMailbox
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

        val execution = async { harness.execute() }
        firstStarted.await()

        assertTrue(harness.submit("first follow-up"))
        assertTrue(harness.submit("second follow-up"))
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
    fun `replacement boundary preserves execute groups around a passive history claim`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val passiveLoadStarted = CompletableDeferred<Unit>()
        val releasePassiveLoad = CompletableDeferred<Unit>()
        val harness = Harness(chatHandler = { call, ctx ->
            if (call == 1) {
                firstStarted.complete(Unit)
                awaitCancellation()
            } else {
                ctx.map { finalResponse("replacement") }
            }
        })

        val execution = async { harness.execute() }
        firstStarted.await()
        assertTrue(harness.stageHistory {
            passiveLoadStarted.complete(Unit)
            releasePassiveLoad.await()
            listOf(LLMRequest.Message(LLMMessageRole.user, "passive history"))
        })
        assertTrue(
            harness.submit(
                ActiveRunInput(
                    history = listOf(LLMRequest.Message(LLMMessageRole.assistant, "before history")),
                    input = "before execute",
                )
            )
        )
        passiveLoadStarted.await()
        assertTrue(
            harness.submit(
                ActiveRunInput(
                    history = listOf(LLMRequest.Message(LLMMessageRole.assistant, "after history")),
                    input = "after execute",
                )
            )
        )
        releasePassiveLoad.complete(Unit)

        assertEquals("replacement", execution.await().output)
        assertOrdered(
            harness.requestHistories[1],
            "before history",
            "before execute",
            "passive history",
            "after history",
            "after execute",
        )
        assertEquals(listOf(0L, 2L), harness.streamRevisions)
    }

    @Test
    fun `passive history queued at readiness is role preserving in the first request`() = runTest {
        val history = listOf(
            LLMRequest.Message(LLMMessageRole.assistant, "client answer"),
            LLMRequest.Message(LLMMessageRole.user, "client clarification"),
        )
        val harness = Harness(chatHandler = { _, ctx -> ctx.map { finalResponse("done") } })

        val result = harness.execute(
            context = harness.context(),
            onActiveRunReady = {
                assertTrue(harness.stageHistory { history })
            },
        )

        assertEquals("done", result.output)
        assertEquals(history, harness.requestHistories.single().takeLast(2))
        assertEquals(listOf(0L), harness.streamRevisions)
    }

    @Test
    fun `passive history during final LLM neither cancels nor extends the run`() = runTest {
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        var requestCancelled = false
        var historyLoads = 0
        val harness = Harness(chatHandler = { _, ctx ->
            requestStarted.complete(Unit)
            try {
                releaseRequest.await()
            } finally {
                requestCancelled = !currentCoroutineContext().isActive
            }
            ctx.map { finalResponse("done") }
        })

        val execution = async { harness.execute() }
        requestStarted.await()
        assertTrue(harness.stageHistory {
            historyLoads += 1
            listOf(LLMRequest.Message(LLMMessageRole.assistant, "pending answer"))
        })
        assertFalse(requestCancelled)
        releaseRequest.complete(Unit)

        val result = execution.await()
        assertEquals("done", result.output)
        assertFalse(requestCancelled)
        assertEquals(0, historyLoads)
        assertEquals(1, harness.chatCallCount)
        assertEquals(listOf(0L), harness.streamRevisions)
        assertFalse(result.context.history.any { it.content == "pending answer" })
    }

    @Test
    fun `passive history before tool acceptance is inserted before the complete exchange`() = runTest {
        val proposalStarted = CompletableDeferred<Unit>()
        val releaseProposal = CompletableDeferred<Unit>()
        var proposalCancelled = false
        val harness = Harness(
            chatHandler = { call, ctx ->
                if (call == 1) {
                    proposalStarted.complete(Unit)
                    try {
                        releaseProposal.await()
                    } finally {
                        proposalCancelled = !currentCoroutineContext().isActive
                    }
                    ctx.map { toolResponse() }
                } else {
                    ctx.map { finalResponse("after tool") }
                }
            },
            toolHandler = { listOf(executedTool("tool-result")) },
        )

        val execution = async { harness.execute() }
        proposalStarted.await()
        assertTrue(harness.stageHistory {
            listOf(LLMRequest.Message(LLMMessageRole.assistant, "client answer"))
        })
        assertFalse(proposalCancelled)
        releaseProposal.complete(Unit)

        assertEquals("after tool", execution.await().output)
        assertFalse(proposalCancelled)
        assertOrdered(
            harness.requestHistories[1],
            "client answer",
            "call-1",
            "tool-result",
        )
    }

    @Test
    fun `passive history neither cancels nor splits a multi tool exchange`() = runTest {
        val toolStarted = CompletableDeferred<Unit>()
        val releaseTool = CompletableDeferred<Unit>()
        var toolCancelled = false
        val harness = Harness(
            chatHandler = { call, ctx ->
                ctx.map { if (call == 1) multiToolResponse() else finalResponse("after tools") }
            },
            toolHandler = {
                toolStarted.complete(Unit)
                try {
                    releaseTool.await()
                } finally {
                    toolCancelled = !currentCoroutineContext().isActive
                }
                listOf(
                    executedTool("first result", callId = "call-1"),
                    executedTool("second result", callId = "call-2"),
                )
            },
        )

        val execution = async { harness.execute() }
        toolStarted.await()
        assertTrue(harness.stageHistory {
            listOf(LLMRequest.Message(LLMMessageRole.assistant, "client answer"))
        })
        assertFalse(toolCancelled)
        releaseTool.complete(Unit)

        assertEquals("after tools", execution.await().output)
        assertFalse(toolCancelled)
        assertOrdered(
            harness.requestHistories[1],
            "client answer",
            "call-1",
            "call-2",
            "first result",
            "second result",
        )
    }

    @Test
    fun `tool boundary preserves execute groups around a passive history claim`() = runTest {
        val toolStarted = CompletableDeferred<Unit>()
        val releaseTool = CompletableDeferred<Unit>()
        val passiveLoadStarted = CompletableDeferred<Unit>()
        val releasePassiveLoad = CompletableDeferred<Unit>()
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
                listOf(executedTool("tool-result"))
            },
        )

        val execution = async { harness.execute() }
        toolStarted.await()
        assertTrue(
            harness.submit(
                ActiveRunInput(
                    history = listOf(LLMRequest.Message(LLMMessageRole.assistant, "before history")),
                    input = "before execute",
                )
            )
        )
        assertTrue(harness.stageHistory {
            passiveLoadStarted.complete(Unit)
            releasePassiveLoad.await()
            listOf(LLMRequest.Message(LLMMessageRole.user, "passive history"))
        })
        assertFalse(toolCancelled)
        releaseTool.complete(Unit)
        passiveLoadStarted.await()
        assertTrue(
            harness.submit(
                ActiveRunInput(
                    history = listOf(LLMRequest.Message(LLMMessageRole.assistant, "after history")),
                    input = "after execute",
                )
            )
        )
        releasePassiveLoad.complete(Unit)

        assertEquals("after tool", execution.await().output)
        assertFalse(toolCancelled)
        assertOrdered(
            harness.requestHistories[1],
            "before history",
            "passive history",
            "after history",
            "call-1",
            "tool-result",
            "before execute",
            "after execute",
        )
        assertEquals(listOf(0L, 2L), harness.streamRevisions)
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

        val execution = async { harness.execute() }
        proposalStarted.await()
        assertTrue(harness.submit("do not run that tool"))
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

        val execution = async { harness.execute() }
        provisionalStarted.await()
        assertTrue(harness.submit("one more requirement"))
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

        val execution = async { harness.execute() }
        finalizationStarted.await()

        assertFalse(harness.submit("too late"))
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

        val firstExecution = async { harness.execute(harness.context("first run")) }
        firstStarted.await()
        assertTrue(harness.submit("old queued input"))
        harness.cancel()

        assertFailsWith<CancellationException> { firstExecution.await() }
        firstCancelled.await()
        assertFalse(harness.submit("after cancellation"))

        val secondResult = harness.execute(harness.context("second run"))
        assertEquals("new run", secondResult.output)
        assertFalse(harness.requestHistories.last().any { it.content.contains("old queued input") })
        assertTrue(harness.requestHistories.last().any { it.content == "second run" })
    }

    @Test
    fun `unrelated LLM cancellation is not converted into replanning`() = runTest {
        val harness = Harness(chatHandler = { _, _ -> throw CancellationException("provider cancelled") })

        assertFailsWith<CancellationException> {
            harness.execute()
        }
        assertEquals(1, harness.chatCallCount)
        assertFalse(harness.submit("not accepted"))
        assertEquals(0, harness.finalizationCount)
    }
}

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

    private val agent: SkillsGraphBasedAgent
    private var activeMailbox: ActiveRunMailbox? = null
    private var stagedHistoryRead: (suspend () -> List<LLMRequest.Message>)? = null

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

    suspend fun execute(
        context: AgentContext<String> = context(),
        onActiveRunReady: suspend (ActiveRunMailbox) -> Unit = {},
    ) = agent.execute(
        context = context,
        loadPendingHistory = ::loadPendingHistory,
        onActiveRunReady = { mailbox ->
            activeMailbox = mailbox
            onActiveRunReady(mailbox)
        },
        onStep = null,
    )

    suspend fun submit(input: String): Boolean =
        submit(ActiveRunInput(input = input))

    suspend fun submit(input: ActiveRunInput): Boolean =
        activeMailbox?.submit { input } ?: false

    fun stageHistory(loader: suspend () -> List<LLMRequest.Message>): Boolean {
        if (activeMailbox == null) return false
        stagedHistoryRead = loader
        return true
    }

    suspend fun cancel() {
        activeMailbox?.close()
        agent.cancel()
    }

    private suspend fun loadPendingHistory(): List<LLMRequest.Message> {
        val read = stagedHistoryRead ?: return emptyList()
        stagedHistoryRead = null
        return read()
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

private fun multiToolResponse(): LLMResponse.Chat.Ok = LLMResponse.Chat.Ok(
    choices = listOf("call-1", "call-2").mapIndexed { index, callId ->
        LLMResponse.Choice(
            message = LLMResponse.Message(
                content = "",
                role = LLMMessageRole.assistant,
                functionCall = functionCall(),
                functionsStateId = callId,
            ),
            index = index,
            finishReason = LLMResponse.FinishReason.function_call,
        )
    },
    created = 1,
    model = "test-model",
    usage = LLMResponse.Usage(1, 1, 2, 0),
)

private fun functionCall(): LLMResponse.FunctionCall =
    LLMResponse.FunctionCall(name = "TestTool", arguments = emptyMap())

private fun executedTool(content: String, callId: String = "call-1"): ExecutedToolCall = ExecutedToolCall(
    functionCall = functionCall(),
    message = LLMRequest.Message(
        role = LLMMessageRole.function,
        content = content,
        name = "TestTool",
        functionsStateId = callId,
    ),
)

private fun assertOrdered(history: List<LLMRequest.Message>, vararg markers: String) {
    val indices = markers.map { marker ->
        history.indexOfFirst { it.content == marker || it.functionsStateId == marker }
    }
    assertTrue(indices.all { it >= 0 }, "Missing marker in history: ${markers.toList()} -> $history")
    assertEquals(indices.sorted(), indices, "History markers are out of order: ${markers.toList()}")
}

private fun responseContent(response: LLMResponse.Chat.Ok): String =
    response.choices.lastOrNull()?.message?.content.orEmpty()
