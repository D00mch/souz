package ru.souz

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import ru.souz.agent.nodes.CLASSIFY_NODE_NAME
import ru.souz.agent.nodes.NodesClassification
import ru.souz.agent.nodes.NodesCommon
import ru.souz.agent.nodes.NodesErrorHandling
import ru.souz.agent.nodes.NodesLLM
import ru.souz.agent.nodes.NodesMCP
import ru.souz.agent.nodes.NodesMemory
import ru.souz.agent.nodes.NodesSkills
import ru.souz.agent.nodes.NodesSummarization
import ru.souz.agent.nodes.INJECTED_MEMORY_MESSAGE_NAME
import ru.souz.agent.nodes.SKILLS_ACTIVATION_NODE_NAME
import ru.souz.agent.nodes.isInjectedMemoryContextMessage
import ru.souz.agent.graph.Node
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.restJsonMapper
import ru.souz.memory.CompletedTurnMemoryInput
import ru.souz.memory.ConversationMemoryRuntime
import ru.souz.memory.MemoryRetrievalRequest
import ru.souz.memory.MemoryRetrievalResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphBasedAgentTest {
    @Test
    fun `graph executes turn setup in required order on every turn`() = runTest {
        val nodesLLM = mockk<NodesLLM>()
        val nodesCommon = mockk<NodesCommon>()
        val nodesClassify = mockk<NodesClassification>()
        val nodesErrorHandling = mockk<NodesErrorHandling>()
        val nodesSummarization = mockk<NodesSummarization>()
        val nodesMCP = mockk<NodesMCP>()
        val nodesSkills = mockk<NodesSkills>()
        val nodesMemory = mockk<NodesMemory>()
        val executed = mutableListOf<String>()

        every { nodesLLM.sideEffects } returns emptyFlow()
        every { nodesCommon.inputToHistory() } returns passthroughStringNode("Input->History", executed)
        every { nodesClassify.node(CLASSIFY_NODE_NAME) } returns passthroughStringNode(CLASSIFY_NODE_NAME, executed)
        every { nodesSkills.node(SKILLS_ACTIVATION_NODE_NAME) } returns passthroughStringNode(SKILLS_ACTIVATION_NODE_NAME, executed)
        every { nodesMCP.nodeProvideMcpTools("MCP Node") } returns passthroughStringNode("MCP Node", executed)
        every { nodesCommon.nodeAppendAdditionalData() } returns passthroughStringNode("appendActualInformation", executed)
        every { nodesMemory.recall() } returns passthroughStringNode("Memory recall", executed)
        every { nodesLLM.chat("LLM") } returns chatNode("LLM", executed)
        every { nodesErrorHandling.chatErrorToFinish() } returns errorNode(executed)
        every { nodesCommon.toolUse() } returns toolUseNode(executed)
        val summarization = summaryNode(mutableListOf())
        every { nodesSummarization.summarize() } returns summarization
        every { nodesMemory.finalizeTurn(summarization) } returns finalizationNode(executed)

        val agent = GraphBasedAgent(
            logObjectMapper = restJsonMapper,
            nodesLLM = nodesLLM,
            nodesCommon = nodesCommon,
            nodesClassify = nodesClassify,
            nodesErrorHandling = nodesErrorHandling,
            nodesSummarization = nodesSummarization,
            nodesMCP = nodesMCP,
            nodesSkills = nodesSkills,
            nodesMemory = nodesMemory,
        )

        repeat(2) {
            val result = agent.executeWithTrace(baseContext())
            assertEquals("final", result.output)
        }

        val expectedRun = listOf(
            "Input->History",
            "Memory recall",
            CLASSIFY_NODE_NAME,
            SKILLS_ACTIVATION_NODE_NAME,
            "MCP Node",
            "appendActualInformation",
            "LLM",
            "Memory-aware finalization",
        )
        assertEquals(expectedRun + expectedRun, executed)
    }

    @Test
    fun `classifier receives fresh memory without previous turn memory`() = runTest {
        val nodesLLM = mockk<NodesLLM>()
        val nodesCommon = mockk<NodesCommon>()
        val nodesClassify = mockk<NodesClassification>()
        val nodesErrorHandling = mockk<NodesErrorHandling>()
        val nodesSummarization = mockk<NodesSummarization>()
        val nodesMCP = mockk<NodesMCP>()
        val nodesSkills = mockk<NodesSkills>()
        val nodesMemory = NodesMemory(
            memoryRuntime = object : ConversationMemoryRuntime {
                override suspend fun retrieveMemory(
                    request: MemoryRetrievalRequest,
                ): MemoryRetrievalResult = MemoryRetrievalResult(renderedPromptBlock = "Fresh memory")

                override suspend fun captureCompletedTurn(input: CompletedTurnMemoryInput) = Unit
            },
            captureScope = backgroundScope,
        )
        val classifierHistories = mutableListOf<List<LLMRequest.Message>>()
        val ignoredExecutionLog = mutableListOf<String>()

        every { nodesLLM.sideEffects } returns emptyFlow()
        every { nodesCommon.inputToHistory() } returns Node("Input->History") { ctx ->
            ctx.map(history = ctx.history + LLMRequest.Message(LLMMessageRole.user, ctx.input))
        }
        every { nodesClassify.node(CLASSIFY_NODE_NAME) } returns Node(CLASSIFY_NODE_NAME) { ctx ->
            classifierHistories += ctx.history
            ctx
        }
        every { nodesSkills.node(SKILLS_ACTIVATION_NODE_NAME) } returns passthroughStringNode(
            SKILLS_ACTIVATION_NODE_NAME,
            ignoredExecutionLog,
        )
        every { nodesMCP.nodeProvideMcpTools("MCP Node") } returns passthroughStringNode(
            "MCP Node",
            ignoredExecutionLog,
        )
        every { nodesCommon.nodeAppendAdditionalData() } returns passthroughStringNode(
            "appendActualInformation",
            ignoredExecutionLog,
        )
        every { nodesLLM.chat("LLM") } returns chatNode("LLM", ignoredExecutionLog)
        every { nodesErrorHandling.chatErrorToFinish() } returns errorNode(ignoredExecutionLog)
        every { nodesCommon.toolUse() } returns toolUseNode(ignoredExecutionLog)
        every { nodesSummarization.summarize() } returns summaryNode(ignoredExecutionLog)

        val agent = GraphBasedAgent(
            logObjectMapper = restJsonMapper,
            nodesLLM = nodesLLM,
            nodesCommon = nodesCommon,
            nodesClassify = nodesClassify,
            nodesErrorHandling = nodesErrorHandling,
            nodesSummarization = nodesSummarization,
            nodesMCP = nodesMCP,
            nodesSkills = nodesSkills,
            nodesMemory = nodesMemory,
        )
        val context = baseContext().copy(
            input = "Current question",
            history = listOf(
                LLMRequest.Message(LLMMessageRole.system, "system"),
                LLMRequest.Message(
                    role = LLMMessageRole.user,
                    content = "<souz_memory_context>\nPrevious memory\n</souz_memory_context>",
                    name = INJECTED_MEMORY_MESSAGE_NAME,
                ),
                LLMRequest.Message(LLMMessageRole.user, "Previous question"),
                LLMRequest.Message(LLMMessageRole.assistant, "Previous answer"),
            ),
        )

        agent.executeWithTrace(context)

        val historyAtClassification = classifierHistories.single()
        assertFalse(historyAtClassification.any { it.content.contains("Previous memory") })
        assertTrue(historyAtClassification.any { it.content.contains("Fresh memory") })
        assertEquals(1, historyAtClassification.count(LLMRequest.Message::isInjectedMemoryContextMessage))
    }

    private fun passthroughStringNode(
        name: String,
        executed: MutableList<String>,
    ): Node<String, String> = Node(name) { ctx ->
        executed += name
        ctx
    }

    private fun chatNode(
        name: String,
        executed: MutableList<String>,
    ): Node<String, LLMResponse.Chat> = Node(name) { ctx ->
        executed += name
        ctx.map {
            LLMResponse.Chat.Ok(
                choices = listOf(
                    LLMResponse.Choice(
                        message = LLMResponse.Message(
                            content = "assistant reply",
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
        }
    }

    private fun summaryNode(executed: MutableList<String>): Node<LLMResponse.Chat.Ok, String> = Node("Summary") { ctx ->
        executed += "Summary"
        ctx.map { "final" }
    }

    private fun finalizationNode(
        executed: MutableList<String>,
    ): Node<LLMResponse.Chat.Ok, String> = Node("Memory-aware finalization") { ctx ->
        executed += "Memory-aware finalization"
        ctx.map { "final" }
    }

    private fun toolUseNode(executed: MutableList<String>): Node<LLMResponse.Chat.Ok, String> = Node("toolUse") { ctx ->
        executed += "toolUse"
        ctx.map { "tool-result" }
    }

    private fun errorNode(executed: MutableList<String>): Node<LLMResponse.Chat, String> = Node("Chat.Error->Finish") { ctx ->
        executed += "Chat.Error->Finish"
        ctx.map { "error" }
    }

    private fun baseContext(): AgentContext<String> = AgentContext(
        input = "Hello",
        settings = AgentSettings(
            model = "gpt-5-nano",
            temperature = 0.1f,
            toolsByCategory = emptyMap(),
        ),
        history = listOf(
            LLMRequest.Message(LLMMessageRole.system, "system"),
            LLMRequest.Message(LLMMessageRole.user, "Hello"),
        ),
        activeTools = emptyList(),
        systemPrompt = "system",
    )
}
