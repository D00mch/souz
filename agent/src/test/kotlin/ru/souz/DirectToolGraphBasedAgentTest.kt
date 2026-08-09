package ru.souz

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import ru.souz.agent.graph.Node
import ru.souz.agent.nodes.CLASSIFY_NODE_NAME
import ru.souz.agent.nodes.NodesClassification
import ru.souz.agent.nodes.NodesCommon
import ru.souz.agent.nodes.NodesErrorHandling
import ru.souz.agent.nodes.NodesLLM
import ru.souz.agent.nodes.NodesMCP
import ru.souz.agent.nodes.NodesMemory
import ru.souz.agent.nodes.NodesSummarization
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.restJsonMapper
import ru.souz.tool.ToolCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectToolGraphBasedAgentTest {
    @Test
    fun `graph classifies direct tools and keeps configured tools available through the tool loop`() = runTest {
        val harness = DirectToolHarness(
            chatHandler = { call, ctx ->
                ctx.map { if (call == 1) directToolResponse() else directFinalResponse("done") }
            },
        )

        val result = harness.agent.executeWithTrace(harness.context())

        assertEquals("done", result.output)
        assertEquals(
            listOf(
                "Input->History",
                "Memory recall",
                CLASSIFY_NODE_NAME,
                "MCP Node",
                "appendActualInformation",
                "LLM",
                "toolUse",
                "LLM",
                "Memory-aware finalization",
            ),
            harness.executed,
        )
        assertEquals(
            listOf(
                listOf(CLASSIFIED_TOOL_NAME, ALWAYS_AVAILABLE_TOOL_NAME),
                listOf(CLASSIFIED_TOOL_NAME, ALWAYS_AVAILABLE_TOOL_NAME),
            ),
            harness.advertisedToolNames,
        )
    }

    @Test
    fun `mid-run input replans without losing always available direct tools`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val harness = DirectToolHarness(
            chatHandler = { call, ctx ->
                if (call == 1) {
                    firstStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        firstCancelled.complete(Unit)
                    }
                } else {
                    ctx.map { directFinalResponse("replanned") }
                }
            },
        )

        val execution = async { harness.agent.executeWithTrace(harness.context()) }
        firstStarted.await()

        assertTrue(harness.agent.submitToActiveRun("one more requirement"))
        firstCancelled.await()

        val result = execution.await()
        assertEquals("replanned", result.output)
        assertEquals(
            listOf(
                listOf(CLASSIFIED_TOOL_NAME, ALWAYS_AVAILABLE_TOOL_NAME),
                listOf(CLASSIFIED_TOOL_NAME, ALWAYS_AVAILABLE_TOOL_NAME),
            ),
            harness.advertisedToolNames,
        )
        assertTrue(harness.requestHistories.last().any { message -> message.content == "one more requirement" })
        assertFalse(harness.agent.submitToActiveRun("too late"))
    }
}

private typealias DirectChatHandler = suspend (
    call: Int,
    context: AgentContext<String>,
) -> AgentContext<LLMResponse.Chat>

private class DirectToolHarness(
    private val chatHandler: DirectChatHandler,
) {
    private val nodesLLM = mockk<NodesLLM>()
    private val nodesCommon = mockk<NodesCommon>()
    private val nodesClassify = mockk<NodesClassification>()
    private val nodesErrorHandling = mockk<NodesErrorHandling>()
    private val nodesSummarization = mockk<NodesSummarization>()
    private val nodesMCP = mockk<NodesMCP>()
    private val nodesMemory = mockk<NodesMemory>()
    private val classifiedTool = directTestTool(CLASSIFIED_TOOL_NAME)
    private val alwaysAvailableTool = directTestTool(ALWAYS_AVAILABLE_TOOL_NAME)
    private val ordinaryTool = directTestTool(ORDINARY_TOOL_NAME)

    val executed = mutableListOf<String>()
    val advertisedToolNames = mutableListOf<List<String>>()
    val requestHistories = mutableListOf<List<LLMRequest.Message>>()
    val agent: DirectToolGraphBasedAgent

    private var chatCallCount = 0

    init {
        every { nodesLLM.sideEffects } returns emptyFlow()
        every { nodesCommon.inputToHistory() } returns stringNode("Input->History") { ctx ->
            val history = ArrayList(ctx.history).apply {
                if (isEmpty()) add(LLMRequest.Message(LLMMessageRole.system, ctx.systemPrompt))
                add(LLMRequest.Message(LLMMessageRole.user, ctx.input))
            }
            ctx.map(history = history)
        }
        every { nodesMemory.recall() } returns stringNode("Memory recall") { it }
        every { nodesClassify.node(CLASSIFY_NODE_NAME) } returns stringNode(CLASSIFY_NODE_NAME) { ctx ->
            ctx.map(activeTools = listOf(classifiedTool.fn))
        }
        every { nodesMCP.nodeProvideMcpTools("MCP Node") } returns stringNode("MCP Node") { ctx ->
            assertEquals(
                listOf(CLASSIFIED_TOOL_NAME, ALWAYS_AVAILABLE_TOOL_NAME),
                ctx.activeTools.map { function -> function.name },
            )
            ctx
        }
        every { nodesCommon.nodeAppendAdditionalData() } returns
            stringNode("appendActualInformation") { it }
        every { nodesLLM.provisionalChat("LLM request", any()) } answers {
            Node("LLM request") { ctx ->
                executed += "LLM"
                chatCallCount += 1
                advertisedToolNames += ctx.activeTools.map { function -> function.name }
                requestHistories += ctx.history.toList()
                chatHandler(chatCallCount, ctx)
            }
        }
        every { nodesCommon.toolUse() } returns Node("toolUse") { ctx ->
            executed += "toolUse"
            ctx.map(
                history = ctx.history + LLMRequest.Message(
                    role = LLMMessageRole.function,
                    content = "tool result",
                    name = ALWAYS_AVAILABLE_TOOL_NAME,
                    functionsStateId = "call-1",
                )
            ) { "tool result" }
        }
        every { nodesSummarization.summarize() } returns Node("Summary") { ctx ->
            ctx.map { directResponseContent(ctx.input) }
        }
        every { nodesMemory.finalizeTurn(any()) } returns Node("Memory-aware finalization") { ctx ->
            executed += "Memory-aware finalization"
            ctx.map { directResponseContent(ctx.input) }
        }
        every { nodesErrorHandling.chatErrorToFinish() } returns Node("Chat.Error") { ctx ->
            ctx.map { "error" }
        }

        agent = DirectToolGraphBasedAgent(
            logObjectMapper = restJsonMapper,
            nodesLLM = nodesLLM,
            nodesCommon = nodesCommon,
            nodesClassify = nodesClassify,
            nodesErrorHandling = nodesErrorHandling,
            nodesSummarization = nodesSummarization,
            nodesMCP = nodesMCP,
            nodesMemory = nodesMemory,
            alwaysAvailableToolNames = setOf(ALWAYS_AVAILABLE_TOOL_NAME, "MissingTool"),
        )
    }

    fun context(): AgentContext<String> = AgentContext(
        input = "initial request",
        settings = AgentSettings(
            model = "test-model",
            temperature = 0f,
            toolsByCategory = mapOf(
                ToolCategory.FILES to linkedMapOf(
                    CLASSIFIED_TOOL_NAME to classifiedTool,
                    ALWAYS_AVAILABLE_TOOL_NAME to alwaysAvailableTool,
                    ORDINARY_TOOL_NAME to ordinaryTool,
                )
            ),
        ),
        history = emptyList(),
        activeTools = listOf(classifiedTool.fn, alwaysAvailableTool.fn, ordinaryTool.fn),
        systemPrompt = "system",
    )

    private fun stringNode(
        name: String,
        block: suspend (AgentContext<String>) -> AgentContext<String>,
    ): Node<String, String> = Node(name) { ctx ->
        executed += name
        block(ctx)
    }
}

private fun directToolResponse(): LLMResponse.Chat.Ok = LLMResponse.Chat.Ok(
    choices = listOf(
        LLMResponse.Choice(
            message = LLMResponse.Message(
                content = "",
                role = LLMMessageRole.assistant,
                functionCall = LLMResponse.FunctionCall(ALWAYS_AVAILABLE_TOOL_NAME, emptyMap()),
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

private fun directFinalResponse(content: String): LLMResponse.Chat.Ok = LLMResponse.Chat.Ok(
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

private fun directResponseContent(response: LLMResponse.Chat.Ok): String =
    response.choices.lastOrNull()?.message?.content.orEmpty()

private fun directTestTool(name: String): LLMToolSetup = object : LLMToolSetup {
    override val fn = LLMRequest.Function(
        name = name,
        description = name,
        parameters = LLMRequest.Parameters("object", emptyMap()),
    )

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
        LLMRequest.Message(LLMMessageRole.function, "{}", name = name)
}

private const val CLASSIFIED_TOOL_NAME = "ClassifiedTool"
private const val ALWAYS_AVAILABLE_TOOL_NAME = "AlwaysAvailableTool"
private const val ORDINARY_TOOL_NAME = "OrdinaryTool"
