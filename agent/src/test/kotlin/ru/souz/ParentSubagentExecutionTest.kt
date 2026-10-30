package ru.souz

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import ru.souz.agent.ActiveRunInput
import ru.souz.agent.Agent
import ru.souz.agent.AgentCoreTools
import ru.souz.agent.AgentExecutionResult
import ru.souz.agent.SubagentTool
import ru.souz.agent.graph.buildGraph
import ru.souz.agent.graph.Node
import ru.souz.agent.nodes.NodesCommon
import ru.souz.agent.nodes.NodesErrorHandling
import ru.souz.agent.nodes.NodesLLM
import ru.souz.agent.nodes.NodesMemory
import ru.souz.agent.nodes.NodesSkillInventory
import ru.souz.agent.nodes.NodesToolUseWithKnowledge
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.agent.runtime.AgentToolExecutor
import ru.souz.agent.skills.registry.SkillBundleProvider
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.agent.spi.SystemAgentRuntimeEnvironment
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.agent.state.AgentTools
import ru.souz.llms.LLMException
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.LlmProvider
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.llms.toMessage
import ru.souz.memory.NoopConversationMemoryRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ParentSubagentExecutionTest {
    @Test
    fun `parent works while two isolated children run and selectively cancels one`() = runTest {
        val started = List(2) { CompletableDeferred<Unit>() }
        val stopped = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var ownWork = 0
        val ownTool = object : LLMToolSetup {
            override val fn = testTool("RunSkillCommand").fn
            override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message {
                ownWork++
                return LLMRequest.Message(LLMMessageRole.function, "own work done", name = fn.name)
            }
        }
        var step = 0
        lateinit var first: String
        lateinit var second: String
        val harness = ParentHarness(backgroundScope, ownTool, child = { ctx ->
            assertTrue(ctx.history.isEmpty())
            assertTrue(ctx.activeTools.isEmpty())
            assertTrue(ctx.settings.tools.byName.isEmpty())
            assertSame(AgentRuntimeEventSink.NONE, ctx.runtimeEventSink)
            assertEquals("delegating-user", ctx.toolInvocationMeta.userId)
            assertEquals("parent-model", ctx.settings.model)
            val index = ctx.input.toInt()
            started[index].complete(Unit)
            if (index == 0) try { awaitCancellation() } finally { stopped.complete(Unit) }
            release.await()
            "second answer"
        }) { ctx ->
            when (step++) {
                0 -> parentResponse(call(SubagentTool.NAME, "task" to "0"), call(SubagentTool.NAME, "task" to "1"))
                1 -> {
                    started.forEach { it.await() }
                    val ids = ctx.history.filter { it.name == SubagentTool.NAME }.map { body(it)["subagentId"].asText() }
                    assertEquals(2, ids.distinct().size)
                    first = ids[0]; second = ids[1]
                    parentResponse(call(SubagentTool.STATUS, "subagentId" to first), call(SubagentTool.STATUS, "subagentId" to second), call("RunSkillCommand"))
                }
                2 -> {
                    assertEquals(1, ownWork)
                    assertEquals(listOf("running", "running"), ctx.history.filter { it.name == SubagentTool.STATUS }.map { body(it)["status"].asText() })
                    parentResponse(call(SubagentTool.CANCEL, "subagentId" to first))
                }
                3 -> {
                    assertEquals("cancelled", body(ctx.history.last())["status"].asText())
                    stopped.await()
                    release.complete(Unit)
                    parentResponse(call(SubagentTool.WAIT, "subagentId" to second))
                }
                else -> {
                    val result = body(ctx.history.last())
                    assertEquals("completed", result["status"].asText())
                    assertEquals("second answer", result["result"].asText())
                    parentResponse()
                }
            }
        }
        assertEquals("parent answer", harness.agent.execute(harness.context()).output)
        assertEquals(5, step)
        assertEquals(1, harness.boundSettings.size)
        assertTrue(harness.requests.all { it.activeTools.map { t -> t.name }.toSet().containsAll(SubagentTool.NAMES) })
    }

    @Test
    fun `parent completion failure and cancellation all stop unfinished children before returning`() = runTest {
        for (outcome in listOf("complete", "fail", "cancel")) {
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val parentReady = CompletableDeferred<Unit>()
            var step = 0
            val harness = ParentHarness(backgroundScope, child = {
                started.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            }) {
                if (step++ == 0) parentResponse(call(SubagentTool.NAME, "task" to "wait")) else {
                    started.await()
                    parentReady.complete(Unit)
                    when (outcome) {
                        "fail" -> error("parent failed")
                        "cancel" -> awaitCancellation()
                        else -> parentResponse()
                    }
                }
            }
            val execution = async {
                try { harness.agent.execute(harness.context()) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: IllegalStateException) { assertEquals("parent failed", failure.message); null }
            }
            parentReady.await()
            if (outcome == "cancel") {
                harness.agent.cancelActiveJob()
                assertFailsWith<CancellationException> { execution.await() }
            } else execution.await()
            assertTrue(stopped.isCompleted)
        }
    }

    @Test
    fun `next execution cannot access previous children even when completed`() = runTest {
        var step = 0
        var oldId = ""
        val harness = ParentHarness(backgroundScope, child = { "answer" }) { ctx ->
            when (step++) {
                0 -> parentResponse(call(SubagentTool.NAME, "task" to "first"))
                1 -> {
                    oldId = body(ctx.history.last())["subagentId"].asText()
                    parentResponse(call(SubagentTool.WAIT, "subagentId" to oldId))
                }
                2 -> { assertEquals("completed", body(ctx.history.last())["status"].asText()); parentResponse() }
                3 -> parentResponse(call(SubagentTool.STATUS, "subagentId" to oldId))
                else -> { assertEquals("subagent_not_found", body(ctx.history.last())["error"]["code"].asText()); parentResponse() }
            }
        }
        repeat(2) { harness.agent.execute(harness.context()) }
        assertEquals(5, step)
    }

    @Test
    fun `queued steering reaches parent while child remains active`() = runTest {
        val started = CompletableDeferred<Unit>()
        val parentReady = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var step = 0
        val harness = ParentHarness(backgroundScope, child = { started.complete(Unit); release.await(); "child answer" }) { ctx ->
            when (step++) {
                0 -> parentResponse(call(SubagentTool.NAME, "task" to "child"))
                1 -> { started.await(); parentReady.complete(Unit); awaitCancellation() }
                2 -> {
                    assertEquals("use the result differently", ctx.history.last().content)
                    assertFalse(release.isCompleted)
                    release.complete(Unit)
                    val id = body(ctx.history.single { it.name == SubagentTool.NAME })["subagentId"].asText()
                    parentResponse(call(SubagentTool.WAIT, "subagentId" to id))
                }
                else -> { assertEquals("child answer", body(ctx.history.last())["result"].asText()); parentResponse() }
            }
        }
        val execution = async { harness.agent.execute(harness.context()) }
        parentReady.await()
        assertTrue(harness.agent.submitToActiveRun { ActiveRunInput(input = "use the result differently") })
        assertEquals("parent answer", execution.await().output)
        assertEquals(1, harness.boundSettings.size)
    }

    @Test
    fun `graph retries require explicit opt-in`() = runTest {
        val context = ParentHarness(backgroundScope, child = { "" }) { parentResponse() }.context()
        for (input in listOf("tool input", parentResponse(), parentResponse(call(SubagentTool.NAME, "task" to "child")))) {
            for (retryable in listOf(false, true)) {
                var attempts = 0
                val failure = LLMException(LLMResponse.Chat.Error(503, "Provider failure"))
                val graph = buildGraph<Any, String> {
                    val operation = Node<Any, String>("toolUse", retryable = retryable) { attempts++; throw failure }
                    nodeInput.edgeTo(operation).edgeTo(nodeFinish)
                }
                assertSame(failure, assertFailsWith<LLMException> { graph.start(context.map<Any> { input }) })
                assertEquals(if (retryable) 2 else 1, attempts)
            }
        }
    }

    @Test
    fun `a later failed tool does not replay child side effects`() = runTest {
        val sideEffect = CompletableDeferred<Unit>()
        var executions = 0
        var laterInvocations = 0
        val failure = LLMException(LLMResponse.Chat.Error(503, "Later provider failed"))
        val laterTool = object : LLMToolSetup {
            override val fn = testTool("RunSkillCommand").fn
            override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message {
                sideEffect.await()
                laterInvocations++
                throw failure
            }
        }
        val harness = ParentHarness(backgroundScope, laterTool, child = { executions++; sideEffect.complete(Unit); "done" }) {
            parentResponse(call(SubagentTool.NAME, "task" to "write once"), call("RunSkillCommand"))
        }
        assertSame(failure, assertFailsWith<LLMException> { harness.agent.execute(harness.context()) })
        assertEquals(1, executions)
        assertEquals(1, laterInvocations)
        assertEquals(1, harness.requests.size)
    }
}

private class ParentHarness(
    captureScope: CoroutineScope,
    runtimeCommand: LLMToolSetup = testTool("RunSkillCommand"),
    child: suspend (AgentContext<String>) -> String,
    respond: suspend (AgentContext<String>) -> LLMResponse.Chat.Ok,
) {
    val boundSettings = mutableListOf<AgentSettings>()
    val requests = mutableListOf<AgentContext<String>>()
    val agent: SkillsGraphBasedAgent

    init {
        val nodesLLM = mockk<NodesLLM>()
        every { nodesLLM.sideEffects } returns emptyFlow()
        every { nodesLLM.chat(any(), any()) } answers {
            Node<String, LLMResponse.Chat>(firstArg()) { ctx ->
                requests += ctx
                val response = respond(ctx)
                ctx.map(history = ctx.history + response.choices.mapNotNull { it.toMessage() }) { response }
            }
        }
        val catalog = AgentToolCatalog()
        val filter = mockk<AgentToolsFilter> { every { applyFilter(any()) } answers { firstArg() } }
        val bundles = mockk<SkillBundleProvider> { coEvery { listSkillInventoryIds(any()) } returns emptyList() }
        val coreTools = AgentCoreTools(
            testTool("GetSkillByName"), testTool("GetSkillsByCategory"), testTool("GetSkillsNamesByCategory"),
            testTool("GetKnowledge"), testTool("SearchKnowledge"), testTool("SearchMemory"), runtimeCommand,
            subagentTools = { settings ->
                boundSettings += settings
                SubagentTool({ _ -> mockk<Agent> {
                    coEvery { execute(any(), any(), any()) } coAnswers {
                        val ctx = firstArg<AgentContext<String>>()
                        AgentExecutionResult(child(ctx), ctx)
                    }
                } }) { _, _ -> SubagentTool.Setup(settings.copy(tools = AgentTools(emptyList())), "child instructions") }.tools
            },
        )
        agent = SkillsGraphBasedAgent(
            restJsonMapper, nodesLLM, NodesCommon(SystemAgentRuntimeEnvironment), NodesErrorHandling(mockk(relaxed = true)),
            NodesMemory(NoopConversationMemoryRuntime, captureScope), NodesSkillInventory(catalog, filter, bundles),
            NodesToolUseWithKnowledge(AgentToolExecutor(), knowledgeStore = null), coreTools,
        )
    }

    fun context() = AgentContext(
        input = "delegate this task",
        settings = AgentSettings("parent-model", LlmProvider.OPENAI, 0.7f, AgentTools(emptyList()), contextSize = 16_000),
        history = emptyList(), activeTools = emptyList(), systemPrompt = "parent instructions",
        toolInvocationMeta = ToolInvocationMeta(userId = "delegating-user"),
    )
}

private fun call(name: String, vararg args: Pair<String, Any>) = LLMResponse.FunctionCall(name, mapOf(*args))
private fun body(message: LLMRequest.Message) = restJsonMapper.readTree(message.content)
private fun parentResponse(vararg calls: LLMResponse.FunctionCall) = LLMResponse.Chat.Ok(
    choices = if (calls.isEmpty()) listOf(LLMResponse.Choice(
        LLMResponse.Message("parent answer", LLMMessageRole.assistant, functionsStateId = null), 0, LLMResponse.FinishReason.stop,
    )) else calls.mapIndexed { index, call -> LLMResponse.Choice(
        LLMResponse.Message("", LLMMessageRole.assistant, functionCall = call, functionsStateId = "call-$index"), index, LLMResponse.FinishReason.function_call,
    ) },
    created = 1, model = "parent-model", usage = LLMResponse.Usage(1, 1, 2, 0),
)
