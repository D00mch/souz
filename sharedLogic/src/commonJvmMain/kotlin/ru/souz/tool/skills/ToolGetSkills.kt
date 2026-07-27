package ru.souz.tool.skills

import kotlinx.coroutines.CancellationException
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.registry.StoredSkill
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.agent.state.AgentTools
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper

/**
 * Implements [LLMToolSetup] directly because [ru.souz.tool.ToolSetup] can only return a [String],
 * and its adapter JSON-encodes that string again.
 *
 * Handle both internal tools (represented as skills) and the OpenClaw compatible skills
 *
 * This tool returns structured JSON, so using [ru.souz.tool.ToolSetup] would double-encode the response.
 */
class ToolGetSkills(
    private val toolCatalog: AgentToolCatalog,
    private val toolsFilter: AgentToolsFilter,
    private val repository: SkillRegistryRepository,
    private val legacyCommandTool: LLMToolSetup,
) : LLMToolSetup {
    data class Input(
        val skillIds: List<String> = emptyList(),
    )

    override val fn: LLMRequest.Function = LLMRequest.Function(
        name = NAME,
        description = "List short descriptions of available Skills, or load full details for specific Skill IDs. Full file-backed instructions are loaded only when requested.",
        parameters = LLMRequest.Parameters(
            type = "object",
            properties = mapOf(
                "skillIds" to LLMRequest.Property(
                    type = "array",
                    description = "Exact Skill IDs to inspect. Leave empty to list short descriptions for every available Skill.",
                )
            ),
        ),
        returnParameters = LLMRequest.Parameters(
            type = "object",
            properties = mapOf(
                "results" to LLMRequest.Property("array", "Available Skill summaries or requested Skill details."),
                "errors" to LLMRequest.Property("array", "Per-Skill discovery errors, including unavailable IDs."),
                "instruction" to LLMRequest.Property("string", "Required Skill discovery and invocation protocol."),
            ),
        ),
    )

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
        invoke(functionCall, ToolInvocationMeta.localDefault())

    override suspend fun invoke(
        functionCall: LLMResponse.FunctionCall,
        meta: ToolInvocationMeta,
    ): LLMRequest.Message {
        val response = try {
            val input = restJsonMapper.convertValue(functionCall.arguments, Input::class.java)
            if (input.skillIds.isEmpty()) {
                summaries(meta)
            } else {
                details(input.skillIds, meta)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            GetSkillsResponse(
                errors = listOf(
                    SkillError(
                        skillId = null,
                        code = "skills_unavailable",
                        message = error.message ?: "Skills are unavailable.",
                    )
                )
            )
        }
        return LLMRequest.Message(
            role = LLMMessageRole.function,
            content = restJsonMapper.writeValueAsString(response),
            name = functionCall.name,
        )
    }

    private suspend fun summaries(meta: ToolInvocationMeta): GetSkillsResponse {
        val enabledTools = enabledTools()
        val storedSkills = repository.listSkills(meta.userId)

        val results = buildList<SkillResult> {
            enabledTools.values
                .forEach { tool ->
                    add(
                        SummaryResult(
                            skillId = tool.fn.name,
                            displayName = tool.fn.name,
                            description = tool.fn.description,
                            nextCall = inspectCall(tool.fn.name),
                        )
                    )
                }
            storedSkills
                .filterNot { it.skillId.value in enabledTools }
                .forEach { add(it.toSummary()) }
        }.sortedBy(SkillResult::skillId)

        return GetSkillsResponse(results = results)
    }

    private suspend fun details(
        requestedIds: List<String>,
        meta: ToolInvocationMeta,
    ): GetSkillsResponse {
        val ids = requestedIds
            .map(String::trim)
            .distinct()
        val unfilteredTools = unfilteredTools()
        val enabledTools = enabledTools()
        val results = mutableListOf<SkillResult>()
        val errors = mutableListOf<SkillError>()

        for (id in ids) {
            try {
                when {
                    id.isBlank() -> errors += SkillError(id, "invalid_skill_id", "Skill ID must not be blank.")
                    id in enabledTools -> results += enabledTools.getValue(id).toDetail()
                    else -> {
                        val bundle = repository.loadSkillBundle(meta.userId, SkillId(id))
                        when {
                            bundle != null -> results += bundle.toDetail()
                            id in unfilteredTools -> errors += SkillError(
                                id,
                                "skill_disabled",
                                "Tool-backed Skill is disabled: $id",
                            )
                            else -> errors += SkillError(id, "skill_not_found", "Skill is unavailable: $id")
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                errors += SkillError(
                    skillId = id,
                    code = "skill_unavailable",
                    message = error.message ?: "Skill is unavailable: $id",
                )
            }
        }
        return GetSkillsResponse(results = results, errors = errors)
    }

    private fun unfilteredTools(): Map<String, LLMToolSetup> =
        AgentTools(toolCatalog.toolsByCategory).byName

    private fun enabledTools(): Map<String, LLMToolSetup> =
        AgentTools(toolsFilter.applyFilter(toolCatalog.toolsByCategory)).byName

    private fun StoredSkill.toSummary(): SummaryResult = SummaryResult(
        skillId = skillId.value,
        displayName = manifest.name,
        description = manifest.description,
        nextCall = inspectCall(skillId.value),
    )

    private fun LLMToolSetup.toDetail(): ToolSkillDetail = ToolSkillDetail(
        skillId = fn.name,
        displayName = fn.name,
        description = fn.description,
        inputSchema = fn.parameters,
        returnSchema = fn.returnParameters,
        fewShotExamples = fn.fewShotExamples.orEmpty(),
        nextCall = invokeCall(fn.name),
    )

    private fun SkillBundle.toDetail(): BundleSkillDetail = BundleSkillDetail(
        skillId = skillId.value,
        displayName = manifest.name,
        description = manifest.description,
        skillMarkdownBody = skillMarkdownBody,
        author = manifest.author,
        version = manifest.version,
        supportingFiles = files
            .map { it.normalizedPath }
            .filterNot { it == SKILL_MARKDOWN_PATH },
        inputSchema = legacyCommandTool.fn.parameters.withoutLegacyBindings(),
        returnSchema = sandboxCommandResultSchema(),
        nextCall = invokeCall(skillId.value),
    )

    private fun inspectCall(skillId: String): SkillCallTemplate = SkillCallTemplate(
        name = NAME,
        arguments = mapOf("skillIds" to listOf(skillId)),
        instruction = "Call this exact template to inspect the Skill schema.",
    )

    private fun invokeCall(skillId: String): SkillCallTemplate = SkillCallTemplate(
        name = ToolInvokeSkill.NAME,
        arguments = mapOf(
            "skillId" to skillId,
            "arguments" to emptyMap<String, Any>(),
        ),
        instruction =
            "Replace the nested arguments object with values matching the sibling inputSchema before calling; " +
                "leave it empty only when that schema has no inputs.",
    )

    private fun LLMRequest.Parameters.withoutLegacyBindings(): LLMRequest.Parameters = copy(
        properties = properties - setOf("skillId", "activeSkills"),
        required = required - setOf("skillId", "activeSkills"),
    )

    companion object {
        const val NAME = "GetSkills"
        private const val SKILL_MARKDOWN_PATH = "SKILL.md"
    }
}

private data class GetSkillsResponse(
    val results: List<SkillResult> = emptyList(),
    val errors: List<SkillError> = emptyList(),
    val instruction: String = SKILL_INVOCATION_INSTRUCTION,
)

private const val SKILL_INVOCATION_INSTRUCTION =
    "Values in results[].skillId are Skill IDs, not callable function names. " +
        "Inspect them with GetSkills(skillIds=[...]), then execute them only with " +
        "RunSkillCommand(skillId=<exact ID>, arguments=<matching input schema>). " +
        "Never put a Skill ID in the function name field."

private sealed interface SkillResult {
    val skillId: String
}

private data class SummaryResult(
    override val skillId: String,
    val displayName: String,
    val description: String,
    val nextCall: SkillCallTemplate,
) : SkillResult

private data class ToolSkillDetail(
    override val skillId: String,
    val displayName: String,
    val description: String,
    val inputSchema: LLMRequest.Parameters,
    val returnSchema: LLMRequest.Parameters?,
    val fewShotExamples: List<LLMRequest.FewShotExample>,
    val nextCall: SkillCallTemplate,
) : SkillResult

private data class BundleSkillDetail(
    override val skillId: String,
    val displayName: String,
    val description: String,
    val skillMarkdownBody: String,
    val author: String?,
    val version: String?,
    val supportingFiles: List<String>,
    val inputSchema: LLMRequest.Parameters,
    val returnSchema: LLMRequest.Parameters,
    val nextCall: SkillCallTemplate,
) : SkillResult

private data class SkillCallTemplate(
    val name: String,
    val arguments: Map<String, Any>,
    val instruction: String,
)

private data class SkillError(
    val skillId: String?,
    val code: String,
    val message: String,
)

internal fun sandboxCommandResultSchema(): LLMRequest.Parameters = LLMRequest.Parameters(
    type = "object",
    properties = mapOf(
        "exitCode" to LLMRequest.Property("number", "Process exit code, or -1 on timeout."),
        "stdout" to LLMRequest.Property("string", "Complete captured standard output."),
        "stderr" to LLMRequest.Property("string", "Complete captured standard error."),
        "timedOut" to LLMRequest.Property("boolean", "Whether the command timed out."),
    ),
    required = listOf("exitCode", "stdout", "stderr", "timedOut"),
)
