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
import ru.souz.agent.AgentId
import ru.souz.agent.graph.Node
import ru.souz.agent.graph.buildGraph
import ru.souz.agent.nodes.NodesClassification
import ru.souz.agent.nodes.NodesCommon
import ru.souz.agent.nodes.NodesErrorHandling
import ru.souz.agent.nodes.NodesLLM
import ru.souz.agent.nodes.NodesMCP
import ru.souz.agent.nodes.NodesMemory
import ru.souz.agent.nodes.NodesSkillInventory
import ru.souz.agent.nodes.NodesSummarization
import ru.souz.agent.nodes.NodesToolUseWithKnowledge
import ru.souz.agent.nodes.NodesPlain
import ru.souz.agent.runtime.AgentToolExecutor
import ru.souz.agent.skills.registry.SkillBundleProvider
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.agent.spi.SystemAgentRuntimeEnvironment
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMException
import ru.souz.llms.LLMRequest
import ru.souz.llms.LlmProvider
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.llms.toMessage
import ru.souz.memory.NoopConversationMemoryRuntime
import ru.souz.tool.ToolCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ParentSubagentExecutionTest {
    @Test
    fun `both parents bind original execution settings and continue after child success or failure`() = runTest {
        for (id in parentIds) {
            val harness = ParentHarness(id, backgroundScope) { settings, _ ->
                if (settings.model == "first-model") """{"result":"child answer"}"""
                else """{"error":{"code":"TURN_LIMIT","message":"Child exhausted its turns"}}"""
            }
            for (model in listOf("first-model", "override-model")) {
                val context = harness.context(model)
                val result = harness.agent.execute(context)
                val requests = harness.requests.takeLast(2)

                assertEquals("parent answer", result.output)
                assertSame(context.settings, harness.boundSettings.last())
                assertEquals(context.toolInvocationMeta, harness.invocationMetadata.last())
                assertTrue(requests.all { request ->
                    request.activeTools.single { it.name == "SpawnSubagent" }.description == model &&
                        request.settings.tools.byName.containsKey("SpawnSubagent")
                })
                val toolOutput = requests.last().history.single { it.role == LLMMessageRole.function }
                assertEquals("SpawnSubagent", toolOutput.name)
                assertEquals("spawn-call", toolOutput.functionsStateId)
                assertTrue(toolOutput.content.contains(if (model == "first-model") "child answer" else "TURN_LIMIT"))
                if (id == AgentId.SKILLS_GRAPH) {
                    assertTrue(requests.all {
                        it.activeTools.map { tool -> tool.name } == it.settings.tools.byName.keys.toList() &&
                            "CatalogTool" !in it.settings.tools.byName && it.settings.tools.byCategory.isEmpty()
                    })
                }
            }
            assertEquals(2, harness.boundSettings.size)
            assertEquals(2, harness.invocationMetadata.size)
        }
    }

    @Test
    fun `both parents suspend until child completion`() = runTest {
        for (id in parentIds) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val harness = ParentHarness(id, backgroundScope) { _, _ ->
                started.complete(Unit)
                release.await()
                """{"result":"child answer"}"""
            }
            val execution = async { harness.agent.execute(harness.context()) }
            started.await()
            assertEquals(1, harness.requests.size)
            assertFalse(execution.isCompleted)

            release.complete(Unit)
            assertEquals("parent answer", execution.await().output)
            assertEquals(2, harness.requests.size)
        }
    }

    @Test
    fun `neither parent replays a completed child when a later tool in its batch fails`() = runTest {
        for (id in parentIds) {
            var laterInvocations = 0
            val failure = LLMException(LLMResponse.Chat.Error(503, "Later tool provider failed"))
            val laterTool = object : LLMToolSetup {
                override val fn = testTool("RunSkillCommand").fn
                override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message {
                    laterInvocations += 1
                    throw failure
                }
            }
            val harness = ParentHarness(id, backgroundScope, afterSpawnTool = laterTool) { _, _ ->
                """{"result":"child side effect completed"}"""
            }

            assertSame(failure, assertFailsWith<LLMException> { harness.agent.execute(harness.context()) })
            assertEquals(1, harness.invocationMetadata.size)
            assertEquals(1, laterInvocations)
            assertEquals(1, harness.requests.size)
        }
    }

    @Test
    fun `graph retries provider failures for request and finalization inputs`() = runTest {
        val context = ParentHarness(AgentId.GRAPH, backgroundScope) { _, _ -> "" }.context()
        for (input in listOf("model request or classification", parentResponse(spawn = false))) {
            var attempts = 0
            val graph = buildGraph<Any, String> {
                val operation = Node<Any, String>("Provider operation") { ctx ->
                    if (++attempts == 1) throw LLMException(LLMResponse.Chat.Error(503, "Retryable provider failure"))
                    ctx.map { "completed" }
                }
                nodeInput.edgeTo(operation)
                operation.edgeTo(nodeFinish)
            }

            assertEquals("completed", graph.start(context.map<Any> { input }).input)
            assertEquals(2, attempts)
        }
    }

    @Test
    fun `cancelling either parent cancels the active child tool`() = runTest {
        for (id in parentIds) {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val harness = ParentHarness(id, backgroundScope) { _, _ ->
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
            val execution = async { harness.agent.execute(harness.context()) }
            started.await()
            harness.agent.cancelActiveJob()

            assertFailsWith<CancellationException> { execution.await() }
            cancelled.await()
            assertEquals(1, harness.requests.size)
        }
    }

    @Test
    fun `skills parent applies queued steering after the child result without rebinding the tool`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val harness = ParentHarness(AgentId.SKILLS_GRAPH, backgroundScope) { _, _ ->
            started.complete(Unit)
            release.await()
            """{"result":"child answer"}"""
        }
        val execution = async { harness.agent.execute(harness.context()) }
        started.await()
        assertTrue((harness.agent as SkillsGraphBasedAgent).submitToActiveRun {
            ActiveRunInput(input = "use the child result differently")
        })
        assertEquals(1, harness.requests.size)
        assertFalse(execution.isCompleted)

        release.complete(Unit)
        assertEquals("parent answer", execution.await().output)
        val history = harness.requests.last().history
        assertEquals(listOf(LLMMessageRole.function, LLMMessageRole.user), history.takeLast(2).map { it.role })
        assertEquals("use the child result differently", history.last().content)
        assertEquals(1, harness.boundSettings.size)
        assertEquals(1, harness.invocationMetadata.size)
    }

    private companion object {
        val parentIds = listOf(AgentId.GRAPH, AgentId.SKILLS_GRAPH)
    }
}

private class ParentHarness(
    id: AgentId,
    captureScope: CoroutineScope,
    afterSpawnTool: LLMToolSetup? = null,
    child: suspend (AgentSettings, ToolInvocationMeta) -> String,
) {
    val boundSettings = mutableListOf<AgentSettings>()
    val invocationMetadata = mutableListOf<ToolInvocationMeta>()
    val requests = mutableListOf<AgentContext<String>>()
    val agent: Agent
    private val catalogTools = mapOf(ToolCategory.FILES to mapOf("CatalogTool" to testTool("CatalogTool")))

    init {
        val settingsProvider = mockk<AgentSettingsProvider>(relaxed = true)
        val nodesCommon = NodesCommon(
            mockk(relaxed = true), settingsProvider, SystemAgentRuntimeEnvironment,
        )
        val nodesLLM = mockk<NodesLLM>()
        every { nodesLLM.sideEffects } returns emptyFlow()
        every { nodesLLM.chat(any(), any()) } answers {
            Node<String, LLMResponse.Chat>(firstArg()) { ctx ->
                requests += ctx
                val response = parentResponse(spawn = requests.size % 2 == 1).let { response ->
                    if (afterSpawnTool == null || response.choices.first().message.functionCall == null) response
                    else response.copy(choices = response.choices + response.choices.first().let { choice ->
                        choice.copy(message = choice.message.copy(
                            functionCall = LLMResponse.FunctionCall(afterSpawnTool.fn.name, emptyMap()),
                            functionsStateId = "later-call",
                        ), index = 1)
                    })
                }
                ctx.map(history = ctx.history + response.choices.mapNotNull { it.toMessage() }) { response }
            }
        }
        val nodesSummarization = mockk<NodesSummarization>()
        every { nodesSummarization.summarize() } returns NodesPlain.responseToString()
        val nodesMemory = NodesMemory(NoopConversationMemoryRuntime, captureScope)
        val nodesErrorHandling = NodesErrorHandling(mockk(relaxed = true))
        val nodesToolUse = NodesToolUseWithKnowledge(AgentToolExecutor(), knowledgeStore = null)
        val catalog = mockk<AgentToolCatalog> { every { toolsByCategory } returns catalogTools }
        val filter = mockk<AgentToolsFilter> { every { applyFilter(any()) } answers { firstArg() } }
        val bundles = mockk<SkillBundleProvider> { coEvery { listSkillInventoryIds(any()) } returns emptyList() }
        val inventory = NodesSkillInventory(catalog, filter, bundles)
        val coreTools = testCoreTools(runtimeCommand = afterSpawnTool ?: testTool("RunSkillCommand")) { settings ->
            boundSettings += settings
            object : LLMToolSetup {
                override val fn = LLMRequest.Function("SpawnSubagent", settings.model, LLMRequest.Parameters("object"))
                override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
                    error("Invocation metadata must be forwarded")

                override suspend fun invoke(
                    functionCall: LLMResponse.FunctionCall,
                    meta: ToolInvocationMeta,
                ): LLMRequest.Message {
                    invocationMetadata += meta
                    return LLMRequest.Message(LLMMessageRole.function, child(settings, meta), name = fn.name)
                }
            }
        }
        agent = if (id == AgentId.SKILLS_GRAPH) {
            SkillsGraphBasedAgent(
                restJsonMapper, nodesLLM, nodesCommon, nodesErrorHandling, nodesSummarization,
                nodesMemory, inventory, nodesToolUse, coreTools,
            )
        } else {
            val classification = mockk<NodesClassification> {
                every { node(any()) } returns Node("Classify") { it.map(activeTools = emptyList()) }
            }
            val mcp = mockk<NodesMCP> { every { nodeProvideMcpTools(any()) } returns Node("MCP") { it } }
            GraphBasedAgent(
                restJsonMapper, nodesLLM, nodesCommon, classification, nodesErrorHandling,
                nodesSummarization, mcp, inventory, nodesToolUse, nodesMemory, coreTools,
            )
        }
    }

    fun context(model: String = "parent-model") = AgentContext(
        input = "delegate this task",
        settings = AgentSettings(model = model, provider = LlmProvider.OPENAI, temperature = 0.7f, toolsByCategory = catalogTools, contextSize = 4096),
        history = emptyList(),
        activeTools = catalogTools.values.flatMap { tools -> tools.values.map { it.fn } },
        systemPrompt = "parent instructions",
        toolInvocationMeta = ToolInvocationMeta.localDefault().copy(userId = "delegating-user"),
    )
}

private fun parentResponse(spawn: Boolean) = LLMResponse.Chat.Ok(
    choices = listOf(
        LLMResponse.Choice(
            message = LLMResponse.Message(
                content = if (spawn) "" else "parent answer",
                role = LLMMessageRole.assistant,
                functionCall = if (spawn) LLMResponse.FunctionCall("SpawnSubagent", mapOf("task" to "child task")) else null,
                functionsStateId = if (spawn) "spawn-call" else null,
            ),
            index = 0,
            finishReason = if (spawn) LLMResponse.FinishReason.function_call else LLMResponse.FinishReason.stop,
        ),
    ),
    created = 1,
    model = "parent-model",
    usage = LLMResponse.Usage(1, 1, 2, 0),
)
