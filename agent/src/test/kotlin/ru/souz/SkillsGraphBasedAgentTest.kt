package ru.souz

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import ru.souz.agent.graph.Node
import ru.souz.agent.nodes.NodesCommon
import ru.souz.agent.nodes.NodesErrorHandling
import ru.souz.agent.nodes.NodesLLM
import ru.souz.agent.nodes.NodesMemory
import ru.souz.agent.nodes.NodesSummarization
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.restJsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals

class SkillsGraphBasedAgentTest {
    @Test
    fun `graph installs core tools once and loops tool calls directly back to chat`() = runTest {
        val nodesLLM = mockk<NodesLLM>()
        val nodesCommon = mockk<NodesCommon>()
        val nodesErrorHandling = mockk<NodesErrorHandling>()
        val nodesSummarization = mockk<NodesSummarization>()
        val nodesMemory = mockk<NodesMemory>()
        val getSkills = tool("GetSkills")
        val getKnowledge = tool("GetKnowledge")
        val runtimeCommand = tool("RunSkillCommand")
        val executed = mutableListOf<String>()
        var chatCount = 0

        every { nodesLLM.sideEffects } returns emptyFlow()
        every { nodesCommon.inputToHistory() } returns passthrough("Input->History", executed)
        every { nodesMemory.recall() } returns passthrough("Memory recall", executed)
        every {
            nodesCommon.installCoreTools(listOf(getSkills, getKnowledge, runtimeCommand))
        } returns passthrough("Install core tools", executed)
        every { nodesCommon.nodeAppendAdditionalData() } returns passthrough("appendActualInformation", executed)
        every { nodesLLM.chat("LLM") } returns Node("LLM") { ctx ->
            executed += "LLM"
            chatCount += 1
            ctx.map { if (chatCount <= 2) toolCallResponse() else finalResponse() }
        }
        every {
            nodesCommon.toolUseWithKnowledge(getKnowledge.fn.name)
        } returns Node("toolUse") { ctx ->
            executed += "toolUse"
            ctx.map { "tool-result" }
        }
        every { nodesSummarization.summarize() } returns Node("Summary") { ctx ->
            executed += "Summary"
            ctx.map { "final" }
        }
        every { nodesMemory.finalizeTurn(any()) } returns Node("Memory-aware finalization") { ctx ->
            executed += "Memory-aware finalization"
            ctx.map { "final" }
        }
        every { nodesErrorHandling.chatErrorToFinish() } returns errorNode(executed)

        val result = agent(
            nodesLLM,
            nodesCommon,
            nodesErrorHandling,
            nodesSummarization,
            nodesMemory,
            getSkills,
            getKnowledge,
            runtimeCommand,
        ).executeWithTrace(baseContext())

        assertEquals("final", result.output)
        assertEquals(
            listOf(
                "Input->History",
                "Memory recall",
                "Install core tools",
                "appendActualInformation",
                "LLM",
                "toolUse",
                "LLM",
                "toolUse",
                "LLM",
                "Memory-aware finalization",
            ),
            executed,
        )
    }

    @Test
    fun `LLM errors use existing user-facing error node`() = runTest {
        val nodesLLM = mockk<NodesLLM>()
        val nodesCommon = mockk<NodesCommon>()
        val nodesErrorHandling = mockk<NodesErrorHandling>()
        val nodesSummarization = mockk<NodesSummarization>()
        val nodesMemory = mockk<NodesMemory>()
        val getSkills = tool("GetSkills")
        val getKnowledge = tool("GetKnowledge")
        val runtimeCommand = tool("RunSkillCommand")
        val executed = mutableListOf<String>()

        every { nodesLLM.sideEffects } returns emptyFlow()
        every { nodesCommon.inputToHistory() } returns passthrough("Input->History", executed)
        every { nodesMemory.recall() } returns passthrough("Memory recall", executed)
        every {
            nodesCommon.installCoreTools(listOf(getSkills, getKnowledge, runtimeCommand))
        } returns passthrough("Install core tools", executed)
        every { nodesCommon.nodeAppendAdditionalData() } returns passthrough("appendActualInformation", executed)
        every { nodesLLM.chat("LLM") } returns Node("LLM") { ctx ->
            executed += "LLM"
            ctx.map { LLMResponse.Chat.Error(500, "provider failed") }
        }
        every { nodesCommon.toolUseWithKnowledge(getKnowledge.fn.name) } returns Node("toolUse") { it.map { "" } }
        every { nodesSummarization.summarize() } returns Node("Summary") { it.map { "" } }
        every { nodesMemory.finalizeTurn(any()) } returns Node("Memory-aware finalization") { it.map { "" } }
        every { nodesErrorHandling.chatErrorToFinish() } returns errorNode(executed)

        val result = agent(
            nodesLLM,
            nodesCommon,
            nodesErrorHandling,
            nodesSummarization,
            nodesMemory,
            getSkills,
            getKnowledge,
            runtimeCommand,
        ).executeWithTrace(baseContext())

        assertEquals("friendly error", result.output)
        assertEquals(
            listOf(
                "Input->History",
                "Memory recall",
                "Install core tools",
                "appendActualInformation",
                "LLM",
                "Chat.Error",
            ),
            executed,
        )
    }

    private fun agent(
        nodesLLM: NodesLLM,
        nodesCommon: NodesCommon,
        nodesErrorHandling: NodesErrorHandling,
        nodesSummarization: NodesSummarization,
        nodesMemory: NodesMemory,
        getSkills: LLMToolSetup,
        getKnowledge: LLMToolSetup,
        runtimeCommand: LLMToolSetup,
    ) = SkillsGraphBasedAgent(
        logObjectMapper = restJsonMapper,
        nodesLLM = nodesLLM,
        nodesCommon = nodesCommon,
        nodesErrorHandling = nodesErrorHandling,
        nodesSummarization = nodesSummarization,
        nodesMemory = nodesMemory,
        getSkillsTool = getSkills,
        getKnowledgeTool = getKnowledge,
        runtimeCommandTool = runtimeCommand,
    )

    private fun passthrough(name: String, executed: MutableList<String>) = Node<String, String>(name) { ctx ->
        executed += name
        ctx
    }

    private fun errorNode(executed: MutableList<String>) = Node<LLMResponse.Chat, String>("Chat.Error") { ctx ->
        executed += "Chat.Error"
        ctx.map { "friendly error" }
    }

    private fun tool(name: String): LLMToolSetup = object : LLMToolSetup {
        override val fn = LLMRequest.Function(
            name = name,
            description = name,
            parameters = LLMRequest.Parameters("object", emptyMap()),
        )

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall) =
            LLMRequest.Message(LLMMessageRole.function, "{}", name = name)
    }

    private fun toolCallResponse(): LLMResponse.Chat.Ok = LLMResponse.Chat.Ok(
        choices = listOf(
            LLMResponse.Choice(
                message = LLMResponse.Message(
                    content = "",
                    role = LLMMessageRole.assistant,
                    functionCall = LLMResponse.FunctionCall("GetSkills", emptyMap()),
                    functionsStateId = "call-1",
                ),
                index = 0,
                finishReason = LLMResponse.FinishReason.function_call,
            )
        ),
        created = 1,
        model = "test",
        usage = LLMResponse.Usage(1, 1, 2, 0),
    )

    private fun finalResponse(): LLMResponse.Chat.Ok = LLMResponse.Chat.Ok(
        choices = listOf(
            LLMResponse.Choice(
                message = LLMResponse.Message(
                    content = "done",
                    role = LLMMessageRole.assistant,
                    functionsStateId = null,
                ),
                index = 0,
                finishReason = LLMResponse.FinishReason.stop,
            )
        ),
        created = 2,
        model = "test",
        usage = LLMResponse.Usage(1, 1, 2, 0),
    )

    private fun baseContext() = AgentContext(
        input = "Hello",
        settings = AgentSettings(model = "test", temperature = 0f, toolsByCategory = emptyMap()),
        history = emptyList(),
        activeTools = emptyList(),
        systemPrompt = "system",
    )
}
