package ru.souz

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import ru.souz.agent.AgentCoreTools
import ru.souz.agent.nodes.CLASSIFY_NODE_NAME
import ru.souz.agent.nodes.NodesClassification
import ru.souz.agent.nodes.NodesCommon
import ru.souz.agent.nodes.NodesErrorHandling
import ru.souz.agent.nodes.NodesLLM
import ru.souz.agent.nodes.NodesMCP
import ru.souz.agent.nodes.NodesMemory
import ru.souz.agent.nodes.NodesSkillInventory
import ru.souz.agent.nodes.NodesToolUseWithKnowledge
import ru.souz.agent.nodes.NodesSummarization
import ru.souz.agent.nodes.INJECTED_MEMORY_MESSAGE_NAME
import ru.souz.agent.nodes.SKILL_INVENTORY_NODE_NAME
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
    fun `graph recalls fresh memory before classification and follows required turn order`() = runTest {
        val nodesLLM = mockk<NodesLLM>()
        val nodesCommon = mockk<NodesCommon>()
        val nodesClassify = mockk<NodesClassification>()
        val nodesErrorHandling = mockk<NodesErrorHandling>()
        val nodesSummarization = mockk<NodesSummarization>()
        val nodesMCP = mockk<NodesMCP>()
        val nodesSkillInventory = mockk<NodesSkillInventory>()
        val nodesToolUseWithKnowledge = mockk<NodesToolUseWithKnowledge>()
        val getSkillByNameTool = testTool("GetSkillByName")
        val getKnowledgeTool = testTool("GetKnowledge")
        val searchKnowledgeTool = testTool("SearchKnowledge")
        val searchMemoryTool = testTool("SearchMemory")
        val runtimeCommandTool = testTool("RunSkillCommand")
        val coreTools = AgentCoreTools(
            getSkillByName = getSkillByNameTool,
            getSkillsByCategory = testTool("GetSkillsByCategory"),
            getSkillsNamesByCategory = testTool("GetSkillsNamesByCategory"),
            getKnowledge = getKnowledgeTool,
            searchKnowledge = searchKnowledgeTool,
            searchMemory = searchMemoryTool,
            runtimeCommand = runtimeCommandTool,
        )
        val expectedCoreTools = listOf(
            getSkillByNameTool,
            getKnowledgeTool,
            searchKnowledgeTool,
            searchMemoryTool,
            runtimeCommandTool,
        )
        val nodesMemory = NodesMemory(
            memoryRuntime = object : ConversationMemoryRuntime {
                override suspend fun retrieveMemory(
                    request: MemoryRetrievalRequest,
                ): MemoryRetrievalResult = MemoryRetrievalResult(renderedPromptBlock = "Fresh memory")

                override suspend fun captureCompletedTurn(input: CompletedTurnMemoryInput) = Unit
            },
            captureScope = backgroundScope,
        )

        every { nodesLLM.sideEffects } returns emptyFlow()
        every { nodesCommon.inputToHistory() } returns Node("Input->History") { ctx ->
            ctx.map(history = ctx.history + LLMRequest.Message(LLMMessageRole.user, ctx.input))
        }
        every { nodesClassify.node(CLASSIFY_NODE_NAME) } returns passthroughStringNode(CLASSIFY_NODE_NAME)
        every {
            nodesSkillInventory.node(
                match { tools -> tools == expectedCoreTools },
                SKILL_INVENTORY_NODE_NAME,
            )
        } returns passthroughStringNode(SKILL_INVENTORY_NODE_NAME)
        every { nodesMCP.nodeProvideMcpTools("MCP Node") } returns passthroughStringNode("MCP Node")
        every { nodesCommon.nodeAppendAdditionalData() } returns passthroughStringNode("appendActualInformation")
        every { nodesLLM.chat("LLM") } returns chatNode("LLM")
        every { nodesErrorHandling.chatErrorToFinish() } returns errorNode()
        every { nodesToolUseWithKnowledge.node(any(), any()) } returns toolUseNode()
        every { nodesSummarization.summarize() } returns summaryNode()

        val agent = GraphBasedAgent(
            logObjectMapper = restJsonMapper,
            nodesLLM = nodesLLM,
            nodesCommon = nodesCommon,
            nodesClassify = nodesClassify,
            nodesErrorHandling = nodesErrorHandling,
            nodesSummarization = nodesSummarization,
            nodesMCP = nodesMCP,
            nodesSkillInventory = nodesSkillInventory,
            nodesToolUseWithKnowledge = nodesToolUseWithKnowledge,
            nodesMemory = nodesMemory,
            coreTools = coreTools,
        )
        val expectedRun = listOf(
            "Input->History",
            "Memory recall",
            CLASSIFY_NODE_NAME,
            SKILL_INVENTORY_NODE_NAME,
            "MCP Node",
            "appendActualInformation",
            "LLM",
            "Memory-aware finalization",
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
        val executed = mutableListOf<String>()
        var historyAtClassification: List<LLMRequest.Message>? = null

        val result = agent.execute(context) { _, node, from, _ ->
            val nodeName = node.name.removePrefix("Node ").substringBefore(';')
            if (nodeName in expectedRun) executed += nodeName
            if (nodeName == CLASSIFY_NODE_NAME) historyAtClassification = from.history
        }

        assertEquals("final", result.output)
        assertEquals(expectedRun, executed)
        val classifierHistory = requireNotNull(historyAtClassification)
        assertFalse(classifierHistory.any { it.content.contains("Previous memory") })
        assertTrue(classifierHistory.any { it.content.contains("Fresh memory") })
        assertEquals(1, classifierHistory.count(LLMRequest.Message::isInjectedMemoryContextMessage))
    }

    private fun passthroughStringNode(name: String): Node<String, String> = Node(name) { it }

    private fun chatNode(name: String): Node<String, LLMResponse.Chat> = Node(name) { ctx ->
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

    private fun summaryNode(): Node<LLMResponse.Chat.Ok, String> = Node("Summary") { ctx ->
        ctx.map { "final" }
    }

    private fun toolUseNode(): Node<LLMResponse.Chat.Ok, String> = Node("toolUse") { ctx ->
        ctx.map { "tool-result" }
    }

    private fun errorNode(): Node<LLMResponse.Chat, String> = Node("Chat.Error->Finish") { ctx ->
        ctx.map { "error" }
    }

    private fun baseContext(): AgentContext<String> = AgentContext(
        input = "Hello",
        settings = AgentSettings(
            model = "gpt-5-mini",
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
