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
import ru.souz.agent.nodes.NodesSkills
import ru.souz.agent.nodes.NodesSummarization
import ru.souz.agent.nodes.SKILLS_ACTIVATION_NODE_NAME
import ru.souz.agent.graph.Node
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.restJsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals

class GraphBasedAgentTest {
    @Test
    fun `graph executes skills activation between classification and MCP on every turn`() = runTest {
        val nodesLLM = mockk<NodesLLM>()
        val nodesCommon = mockk<NodesCommon>()
        val nodesClassify = mockk<NodesClassification>()
        val nodesErrorHandling = mockk<NodesErrorHandling>()
        val nodesSummarization = mockk<NodesSummarization>()
        val nodesMCP = mockk<NodesMCP>()
        val nodesSkills = mockk<NodesSkills>()
        val executed = mutableListOf<String>()

        every { nodesLLM.sideEffects } returns emptyFlow()
        every { nodesCommon.inputToHistory() } returns passthroughStringNode("Input->History", executed)
        every { nodesClassify.node(CLASSIFY_NODE_NAME) } returns passthroughStringNode(CLASSIFY_NODE_NAME, executed)
        every { nodesSkills.node(SKILLS_ACTIVATION_NODE_NAME) } returns passthroughStringNode(SKILLS_ACTIVATION_NODE_NAME, executed)
        every { nodesMCP.nodeProvideMcpTools("MCP Node") } returns passthroughStringNode("MCP Node", executed)
        every { nodesCommon.nodeAppendAdditionalData() } returns passthroughStringNode("appendActualInformation", executed)
        every { nodesLLM.chat("LLM") } returns chatNode("LLM", executed)
        every { nodesErrorHandling.chatErrorToFinish() } returns errorNode(executed)
        every { nodesCommon.toolUse() } returns toolUseNode(executed)
        every { nodesSummarization.summarize() } returns summaryNode(executed)

        val agent = GraphBasedAgent(
            logObjectMapper = restJsonMapper,
            nodesLLM = nodesLLM,
            nodesCommon = nodesCommon,
            nodesClassify = nodesClassify,
            nodesErrorHandling = nodesErrorHandling,
            nodesSummarization = nodesSummarization,
            nodesMCP = nodesMCP,
            nodesSkills = nodesSkills,
        )

        repeat(2) {
            val result = agent.executeWithTrace(baseContext())
            assertEquals("final", result.output)
        }

        val expectedRun = listOf(
            "Input->History",
            CLASSIFY_NODE_NAME,
            SKILLS_ACTIVATION_NODE_NAME,
            "MCP Node",
            "appendActualInformation",
            "LLM",
            "Summary",
        )
        assertEquals(expectedRun + expectedRun, executed)
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
