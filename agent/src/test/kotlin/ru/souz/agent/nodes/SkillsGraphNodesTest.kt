package ru.souz.agent.nodes

import io.mockk.*
import kotlinx.coroutines.test.runTest
import ru.souz.agent.graph.Node
import ru.souz.agent.runtime.AgentToolExecutor
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillsGraphNodesTest {
    @Test
    fun `core tools replace advertised and executable catalog tools`() = runTest {
        val catalogTool = tool("CatalogTool")
        val getSkills = tool("GetSkills")
        val getKnowledge = tool("GetKnowledge")
        val runtimeCommand = tool("RunSkillCommand")
        val nodes = SkillsGraphNodes(
            logObjectMapper = restJsonMapper,
            nodesCommon = mockk(),
            getSkillsTool = getSkills,
            getKnowledgeTool = getKnowledge,
            runtimeCommandTool = runtimeCommand,
        )
        val context = AgentContext(
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
            systemPrompt = "system",
        )

        val result = nodes.restrictToCoreTools(context)
        val coreTools = listOf(getSkills, getKnowledge, runtimeCommand)

        assertEquals(coreTools.map { it.fn }, result.activeTools)
        assertEquals(coreTools.associateBy { it.fn.name }, result.settings.tools.byName)
        assertTrue(result.settings.tools.byCategory.isEmpty())
        assertTrue(result.settings.tools.categoryByName.isEmpty())
        assertFalse(result.settings.tools.byName.containsKey(catalogTool.fn.name))

        val fabricatedCallResult = AgentToolExecutor().execute(
            settings = result.settings,
            functionCall = LLMResponse.FunctionCall(catalogTool.fn.name, emptyMap()),
        )
        assertTrue(fabricatedCallResult.content.contains("no such function CatalogTool"))
    }

    @Test
    fun `direct Skill recovery respects discovery state knowledge and call ordering`() {
        val getSkills = tool("GetSkills")
        val getKnowledge = tool("GetKnowledge")
        val runtimeCommand = tool("RunSkillCommand")
        val unknownToolResult = slot<(
            LLMResponse.FunctionCall,
            List<LLMRequest.Message>,
            List<LLMResponse.FunctionCall>,
        ) -> String>()
        val nodesCommon = mockk<NodesCommon>()
        every {
            nodesCommon.toolUseWithKnowledge(
                getKnowledge.fn.name,
                capture(unknownToolResult),
                "toolUse",
            )
        } returns Node("toolUse") { context -> context.map { "tool-result" } }
        val nodes = SkillsGraphNodes(
            logObjectMapper = restJsonMapper,
            nodesCommon = nodesCommon,
            getSkillsTool = getSkills,
            getKnowledgeTool = getKnowledge,
            runtimeCommandTool = runtimeCommand,
        )

        nodes.toolUse()

        val rejectedCall = LLMResponse.FunctionCall(
            name = "FindFilesByName",
            arguments = mapOf("fileName" to "public-note.txt"),
        )
        val inspectedHistory = listOf(
            functionResult(
                name = getSkills.fn.name,
                content =
                    """{"results":[{"skillId":"FindFilesByName","inputSchema":{"type":"object"}}],"errors":[]}""",
            )
        )
        val recovery = recovery(
            unknownToolResult.captured,
            rejectedCall,
            inspectedHistory,
            listOf(rejectedCall),
        )

        assertEquals("skill_id_called_as_function", recovery["error"]["code"].asText())
        assertEquals("RunSkillCommand", recovery["requiredNextCall"]["name"].asText())
        assertEquals("FindFilesByName", recovery["requiredNextCall"]["arguments"]["skillId"].asText())
        assertEquals(0, recovery["requiredNextCall"]["arguments"]["arguments"].size())
        assertEquals("public-note.txt", recovery["rejectedArguments"]["fileName"].asText())
        assertTrue(recovery["instruction"].asText().contains("inputSchema"))
        assertTrue(recovery["error"]["message"].asText().contains("No tool was executed"))

        val uninspectedRecovery = recovery(
            unknownToolResult.captured,
            rejectedCall,
            emptyList(),
            listOf(rejectedCall),
        )
        assertEquals("GetSkills", uninspectedRecovery["requiredNextCall"]["name"].asText())
        assertEquals(
            "FindFilesByName",
            uninspectedRecovery["requiredNextCall"]["arguments"]["skillIds"].first().asText(),
        )

        val failedDiscoveryHistory = listOf(
            functionResult(
                name = getSkills.fn.name,
                content =
                    """{"results":[],"errors":[{"skillId":"FindFilesByName","code":"skill_not_found"}]}""",
            )
        )
        val failedDiscoveryRecovery = recovery(
            unknownToolResult.captured,
            rejectedCall,
            failedDiscoveryHistory,
            listOf(rejectedCall),
        )
        assertTrue(failedDiscoveryRecovery["requiredNextCall"].isNull)
        assertTrue(failedDiscoveryRecovery["instruction"].asText().contains("unavailable"))

        val retainedDetail =
            """{"results":[{"skillId":"FindFilesByName","inputSchema":{"type":"object"}}],"errors":[]}"""
        val knowledgeHistory = listOf(
            functionResult(
                name = getKnowledge.fn.name,
                content = restJsonMapper.writeValueAsString(
                    mapOf("sourceTool" to getSkills.fn.name, "text" to retainedDetail)
                ),
            )
        )
        val knowledgeRecovery = recovery(
            unknownToolResult.captured,
            rejectedCall,
            knowledgeHistory,
            listOf(rejectedCall),
        )
        assertEquals("RunSkillCommand", knowledgeRecovery["requiredNextCall"]["name"].asText())

        val staleSuccessRecovery = recovery(
            unknownToolResult.captured,
            rejectedCall,
            inspectedHistory + failedDiscoveryHistory,
            listOf(rejectedCall),
        )
        assertTrue(staleSuccessRecovery["requiredNextCall"].isNull)

        val createCall = LLMResponse.FunctionCall(
            name = "CreateNote",
            arguments = mapOf("noteText" to "first"),
        )
        val listCall = LLMResponse.FunctionCall(name = "ListNotes", arguments = emptyMap())
        val batchHistory = listOf(
            functionResult(
                name = getSkills.fn.name,
                content =
                    """{"results":[{"skillId":"CreateNote","inputSchema":{"type":"object"}},{"skillId":"ListNotes","inputSchema":{"type":"object"}}],"errors":[]}""",
            )
        )
        val batchRecovery = recovery(
            unknownToolResult.captured,
            listCall,
            batchHistory,
            listOf(createCall, listCall),
        )
        assertEquals("CreateNote", batchRecovery["requiredNextCall"]["arguments"]["skillId"].asText())
        assertEquals(
            listOf("CreateNote", "ListNotes"),
            batchRecovery["requiredNextCalls"].map { it["arguments"]["skillId"].asText() },
        )
        assertTrue(batchRecovery["requiredNextCalls"].all { it["name"].asText() == "RunSkillCommand" })
        assertEquals(
            listOf("CreateNote", "ListNotes"),
            batchRecovery["rejectedCalls"].map { it["skillId"].asText() },
        )
        assertTrue(batchRecovery["instruction"].asText().contains("requiredNextCall first"))
    }

    private fun recovery(
        callback: (
            LLMResponse.FunctionCall,
            List<LLMRequest.Message>,
            List<LLMResponse.FunctionCall>,
        ) -> String,
        functionCall: LLMResponse.FunctionCall,
        history: List<LLMRequest.Message>,
        rejectedCalls: List<LLMResponse.FunctionCall>,
    ) = restJsonMapper.readTree(callback(functionCall, history, rejectedCalls))

    private fun functionResult(name: String, content: String) = LLMRequest.Message(
        role = LLMMessageRole.function,
        content = content,
        name = name,
    )

    private fun tool(name: String): LLMToolSetup = object : LLMToolSetup {
        override val fn = LLMRequest.Function(
            name = name,
            description = name,
            parameters = LLMRequest.Parameters("object", emptyMap()),
        )

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall) =
            functionResult(name, "{}")
    }
}
