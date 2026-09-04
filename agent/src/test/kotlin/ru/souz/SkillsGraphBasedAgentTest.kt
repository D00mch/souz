package ru.souz

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import ru.souz.agent.AgentCoreTools
import ru.souz.agent.graph.Node
import ru.souz.agent.nodes.NodesSkillInventory
import ru.souz.agent.nodes.NodesCommon
import ru.souz.agent.nodes.NodesErrorHandling
import ru.souz.agent.nodes.NodesLLM
import ru.souz.agent.nodes.NodesMemory
import ru.souz.agent.nodes.NodesToolUseWithKnowledge
import ru.souz.agent.nodes.NodesSummarization
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.agent.state.AgentTools
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.restJsonMapper
import ru.souz.tool.ToolCategory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class SkillsGraphBasedAgentTest {
    @Test
    fun `graph starts with only core tools and loops tool calls directly back to chat`() = runTest {
        val nodesLLM = mockk<NodesLLM>()
        val nodesCommon = mockk<NodesCommon>()
        val nodesErrorHandling = mockk<NodesErrorHandling>()
        val nodesSummarization = mockk<NodesSummarization>()
        val nodesMemory = mockk<NodesMemory>()
        val executed = mutableListOf<String>()
        var chatCount = 0

        every { nodesLLM.sideEffects } returns emptyFlow()
        every { nodesCommon.inputToHistory() } returns coreToolsPassthrough(
            name = "Input->History",
            executed = executed,
            expectedToolNames = SKILLS_CORE_TOOL_NAMES,
        )
        every { nodesMemory.recall() } returns passthrough("Memory recall", executed)
        every { nodesCommon.nodeAppendAdditionalData() } returns passthrough("appendActualInformation", executed)
        every { nodesLLM.provisionalChat("LLM request", any()) } returns Node("LLM request") { ctx ->
            executed += "LLM"
            chatCount += 1
            ctx.map { if (chatCount <= 2) toolCallResponse() else finalResponse() }
        }
        coEvery { nodesCommon.executeFunctionCalls(any()) } answers {
            executed += "toolUse"
            emptyList()
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

        val skillsAgent = agent(
            nodesLLM,
            nodesCommon,
            nodesErrorHandling,
            nodesSummarization,
            nodesMemory,
        )
        val result = skillsAgent.executeWithTrace(baseContext())

        assertEquals("final", result.output)
        assertEquals(
            listOf(
                "Input->History",
                "Memory recall",
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

        skillsAgent.executeWithTrace(result.context.copy(input = "Again"))
        assertEquals(PROVIDED_SYSTEM_PROMPT, result.context.systemPrompt)
    }

    @Test
    fun `LLM errors use existing user-facing error node`() = runTest {
        val nodesLLM = mockk<NodesLLM>()
        val nodesCommon = mockk<NodesCommon>()
        val nodesErrorHandling = mockk<NodesErrorHandling>()
        val nodesSummarization = mockk<NodesSummarization>()
        val nodesMemory = mockk<NodesMemory>()
        val executed = mutableListOf<String>()

        every { nodesLLM.sideEffects } returns emptyFlow()
        every { nodesCommon.inputToHistory() } returns passthrough("Input->History", executed)
        every { nodesMemory.recall() } returns passthrough("Memory recall", executed)
        every { nodesCommon.nodeAppendAdditionalData() } returns passthrough("appendActualInformation", executed)
        every { nodesLLM.provisionalChat("LLM request", any()) } returns Node("LLM request") { ctx ->
            executed += "LLM"
            ctx.map { LLMResponse.Chat.Error(500, "provider failed") }
        }
        every { nodesSummarization.summarize() } returns Node("Summary") { it.map { "" } }
        every { nodesMemory.finalizeTurn(any()) } returns Node("Memory-aware finalization") { it.map { "" } }
        every { nodesErrorHandling.chatErrorToFinish() } returns errorNode(executed)

        val result = agent(
            nodesLLM,
            nodesCommon,
            nodesErrorHandling,
            nodesSummarization,
            nodesMemory,
        ).executeWithTrace(baseContext())

        assertEquals("friendly error", result.output)
        assertEquals(
            listOf(
                "Input->History",
                "Memory recall",
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
    ) = SkillsGraphBasedAgent(
        logObjectMapper = restJsonMapper,
        nodesLLM = nodesLLM,
        nodesCommon = nodesCommon,
        nodesErrorHandling = nodesErrorHandling,
        nodesSummarization = nodesSummarization,
        nodesMemory = nodesMemory,
        nodesSkillInventory = NodesSkillInventory(
            toolCatalog = testCatalog(),
            toolsFilter = passThroughToolsFilter(),
            skillBundleProvider = emptySkillRegistry(),
        ),
        nodesToolUseWithKnowledge = NodesToolUseWithKnowledge(
            nodesCommon = nodesCommon,
            knowledgeStore = null,
        ),
        coreTools = testCoreTools(),
    )

    private fun passthrough(name: String, executed: MutableList<String>) = Node<String, String>(name) { ctx ->
        executed += name
        ctx
    }

    private fun coreToolsPassthrough(
        name: String,
        executed: MutableList<String>,
        expectedToolNames: List<String>,
    ) = Node<String, String>(name) { ctx ->
        executed += name
        assertEquals(expectedToolNames, ctx.activeTools.map { it.name })
        assertEquals(expectedToolNames, ctx.settings.tools.byName.keys.toList())
        assertEquals(emptyMap(), ctx.settings.tools.byCategory)
        assertEquals(emptyMap(), ctx.settings.tools.categoryByName)
        assertEquals(PROVIDED_SYSTEM_PROMPT, ctx.systemPrompt)
        if (ctx.history.isNotEmpty()) {
            assertContains(ctx.history.first().content, "<skill_inventory>")
        }
        ctx
    }

    private fun testCatalog(): AgentToolCatalog = object : AgentToolCatalog {
        override val toolsByCategory = mapOf(
            ToolCategory.FILES to mapOf("CatalogTool" to testTool("CatalogTool")),
        )
    }

    private fun emptySkillRegistry(): SkillRegistryRepository = mockk(relaxed = true) {
        coEvery { listSkills(any()) } returns emptyList()
    }

    private fun errorNode(executed: MutableList<String>) = Node<LLMResponse.Chat, String>("Chat.Error") { ctx ->
        executed += "Chat.Error"
        ctx.map { "friendly error" }
    }

    private fun passThroughToolsFilter(): AgentToolsFilter = object : AgentToolsFilter {
        override fun applyFilter(
            toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>,
        ): Map<ToolCategory, Map<String, LLMToolSetup>> = toolsByCategory
    }

    private fun toolCallResponse(): LLMResponse.Chat.Ok = LLMResponse.Chat.Ok(
        choices = listOf(
            LLMResponse.Choice(
                message = LLMResponse.Message(
                    content = "",
                    role = LLMMessageRole.assistant,
                    functionCall = LLMResponse.FunctionCall(
                        "GetSkillsByCategory",
                        mapOf("category" to "FILES"),
                    ),
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

    private fun baseContext(): AgentContext<String> {
        val catalogTool = testTool("CatalogTool")
        return AgentContext(
            input = "Hello",
            settings = AgentSettings(
                model = "test",
                temperature = 0f,
                tools = AgentTools(
                    byCategory = mapOf(
                        ToolCategory.FILES to mapOf(catalogTool.fn.name to catalogTool),
                    ),
                ),
            ),
            history = emptyList(),
            activeTools = listOf(catalogTool.fn),
            systemPrompt = PROVIDED_SYSTEM_PROMPT,
        )
    }

    private companion object {
        val SKILLS_CORE_TOOL_NAMES = listOf(
            "GetSkillByName",
            "GetSkillsByCategory",
            "GetSkillsNamesByCategory",
            "GetKnowledge",
            "SearchKnowledge",
            "SearchMemory",
            "RunSkillCommand",
        )
        val PROVIDED_SYSTEM_PROMPT = """
            ## Skill Discovery

            Available Skills are listed in the <skill_inventory> section.

            The supplied prompt continues after the inventory-section reference.
        """.trimIndent()
    }
}

internal fun testCoreTools(): AgentCoreTools = AgentCoreTools(
    getSkillByName = testTool("GetSkillByName"),
    getSkillsByCategory = testTool("GetSkillsByCategory"),
    getSkillsNamesByCategory = testTool("GetSkillsNamesByCategory"),
    getKnowledge = testTool("GetKnowledge"),
    searchKnowledge = testTool("SearchKnowledge"),
    searchMemory = testTool("SearchMemory"),
    runtimeCommand = testTool("RunSkillCommand"),
)

internal fun testTool(name: String): LLMToolSetup = object : LLMToolSetup {
    override val fn = LLMRequest.Function(name, name, LLMRequest.Parameters("object", emptyMap()))

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall) =
        LLMRequest.Message(LLMMessageRole.function, "{}", name = functionCall.name)
}
