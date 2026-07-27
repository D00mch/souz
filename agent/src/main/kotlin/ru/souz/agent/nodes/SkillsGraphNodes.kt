package ru.souz.agent.nodes

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import ru.souz.agent.graph.Node
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentTools
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup

/** Nodes and context preparation that belong only to the skills-oriented graph. */
internal class SkillsGraphNodes(
    private val logObjectMapper: ObjectMapper,
    private val nodesCommon: NodesCommon,
    getSkillsTool: LLMToolSetup,
    getKnowledgeTool: LLMToolSetup,
    runtimeCommandTool: LLMToolSetup,
) {
    private val getSkillsToolName = getSkillsTool.fn.name
    private val getKnowledgeToolName = getKnowledgeTool.fn.name
    private val runSkillCommandToolName = runtimeCommandTool.fn.name
    private val coreTools = listOf(getSkillsTool, getKnowledgeTool, runtimeCommandTool)
    private val coreFunctions = coreTools.map { it.fn }
    private val coreToolRegistry = AgentTools(
        byCategory = emptyMap(),
        byName = coreTools.associateBy { it.fn.name },
    )

    fun <T> restrictToCoreTools(context: AgentContext<T>): AgentContext<T> = context.copy(
        settings = context.settings.copy(tools = coreToolRegistry),
        activeTools = coreFunctions,
    )

    fun toolUse(name: String = "toolUse"): Node<LLMResponse.Chat.Ok, String> =
        nodesCommon.toolUseWithKnowledge(
            getKnowledgeToolName = getKnowledgeToolName,
            unknownToolResult = ::rejectedDirectSkillCall,
            name = name,
        )

    private fun rejectedDirectSkillCall(
        functionCall: LLMResponse.FunctionCall,
        history: List<LLMRequest.Message>,
        rejectedCalls: List<LLMResponse.FunctionCall>,
    ): String {
        val callsByState = rejectedCalls.map { rejectedCall ->
            rejectedCall to history.skillInspectionState(rejectedCall.name)
        }
        val unknownSkillIds = callsByState
            .filter { it.second == SkillInspectionState.UNKNOWN }
            .map { it.first.name }
            .distinct()
        val requiredNextCalls = buildList {
            if (unknownSkillIds.isNotEmpty()) {
                add(
                    linkedMapOf(
                        "name" to getSkillsToolName,
                        "arguments" to linkedMapOf("skillIds" to unknownSkillIds),
                    )
                )
            }
            callsByState
                .filter { it.second == SkillInspectionState.AVAILABLE }
                .forEach { (rejectedCall, _) ->
                    add(
                        linkedMapOf(
                            "name" to runSkillCommandToolName,
                            "arguments" to linkedMapOf(
                                "skillId" to rejectedCall.name,
                                "arguments" to emptyMap<String, Any>(),
                            ),
                        )
                    )
                }
        }
        val instruction = when {
            requiredNextCalls.isEmpty() ->
                "Discovery reported that these Skills are unavailable. Do not retry their Skill IDs or repeat " +
                    "$getSkillsToolName for them; choose another available Skill or explain that they cannot be used."
            requiredNextCalls.size == 1 && unknownSkillIds.isNotEmpty() ->
                "Call requiredNextCall exactly to inspect the Skill schema, then invoke it through " +
                    "$runSkillCommandToolName."
            requiredNextCalls.size == 1 ->
                "Call requiredNextCall through $runSkillCommandToolName. Fill its nested arguments from the " +
                    "most recent GetSkills inputSchema; use rejectedCalls only as candidate values and correct " +
                    "their names and types. Use an empty object only when the inputSchema has no inputs."
            else ->
                "Call requiredNextCall first. Then execute every remaining entry in requiredNextCalls in order. " +
                    "Do not skip or reorder any entry. For each $runSkillCommandToolName entry, fill nested " +
                    "arguments from that Skill's GetSkills inputSchema; use rejectedCalls only as candidate " +
                    "values and correct names and types."
        }
        return logObjectMapper.writeValueAsString(
            linkedMapOf(
                "error" to linkedMapOf(
                    "code" to "skill_id_called_as_function",
                    "message" to "${functionCall.name} is not an active function. No tool was executed.",
                ),
                "requiredNextCall" to requiredNextCalls.firstOrNull(),
                "requiredNextCalls" to requiredNextCalls,
                "rejectedArguments" to functionCall.arguments,
                "rejectedCalls" to rejectedCalls.map { rejectedCall ->
                    linkedMapOf(
                        "skillId" to rejectedCall.name,
                        "arguments" to rejectedCall.arguments,
                    )
                },
                "instruction" to instruction,
            )
        )
    }

    private fun List<LLMRequest.Message>.skillInspectionState(skillId: String): SkillInspectionState {
        asReversed().forEach { message ->
            val response = message.skillDiscoveryResponse() ?: return@forEach
            val detail = response["results"]?.firstOrNull { result ->
                result["skillId"]?.asText() == skillId && result.has("inputSchema")
            }
            if (detail != null) return SkillInspectionState.AVAILABLE

            val error = response["errors"]?.firstOrNull { it["skillId"]?.asText() == skillId }
            if (error != null) return SkillInspectionState.UNAVAILABLE
        }
        return SkillInspectionState.UNKNOWN
    }

    private fun LLMRequest.Message.skillDiscoveryResponse(): JsonNode? {
        if (role != LLMMessageRole.function) return null
        val response = runCatching { logObjectMapper.readTree(content) }.getOrNull() ?: return null
        return when (name) {
            getSkillsToolName -> response
            getKnowledgeToolName -> response
                .takeIf { it["sourceTool"]?.asText() == getSkillsToolName }
                ?.get("text")
                ?.takeIf(JsonNode::isTextual)
                ?.asText()
                ?.let { nested -> runCatching { logObjectMapper.readTree(nested) }.getOrNull() }
            else -> null
        }
    }

    private enum class SkillInspectionState {
        UNKNOWN,
        AVAILABLE,
        UNAVAILABLE,
    }
}
