package ru.souz.tool.skills

import com.fasterxml.jackson.annotation.JsonInclude
import kotlinx.coroutines.CancellationException
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.registry.SkillBundleProvider
import ru.souz.agent.skills.validation.SkillApprovalGate
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.agent.state.AgentTools
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.giga.toolInputParameters
import ru.souz.llms.restJsonMapper

/**
 * Returns structured JSON directly because [ru.souz.tool.ToolSetup] would encode it as a JSON string.
 * Compiled tools take precedence over stored bundles with the same ID.
 */
class ToolGetSkillByName(
    private val toolCatalog: AgentToolCatalog,
    private val toolsFilter: AgentToolsFilter,
    private val skillBundleProvider: SkillBundleProvider,
    private val approvalGate: SkillApprovalGate? = null,
) : LLMToolSetup {
    private val fileSkillInputSchema = toolInputParameters<SkillCommandArguments>()

    data class Input(
        val skillId: String = "",
    )

    override val fn: LLMRequest.Function = LLMRequest.Function(
        name = NAME,
        description = "Load the full description and schemas for one exact Skill ID. File-backed Skill instructions are loaded only when requested.",
        parameters = LLMRequest.Parameters(
            type = "object",
            properties = mapOf(
                "skillId" to LLMRequest.Property(
                    type = "string",
                    description = "Exact Skill ID to inspect.",
                )
            ),
            required = listOf("skillId"),
        ),
        returnParameters = LLMRequest.Parameters(
            type = "object",
            properties = mapOf(
                "skill" to LLMRequest.Property("object", "The full Skill description, or null on error."),
                "executionSchema" to LLMRequest.Property("object", "Shared input and return schema for file-backed Skills. Tool-backed Skills keep individual schemas on the Skill entry."),
                "error" to LLMRequest.Property("object", "A lookup error, or null on success."),
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
            getSkill(input.skillId, meta)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            SkillLookupResponse(
                error = SkillDiscoveryError(
                    skillId = null,
                    code = "skills_unavailable",
                    message = error.message ?: "Skills are unavailable.",
                )
            )
        }
        return LLMRequest.Message(
            role = LLMMessageRole.function,
            content = restJsonMapper.writeValueAsString(response),
            name = functionCall.name,
        )
    }

    internal suspend fun getSkill(
        requestedId: String,
        meta: ToolInvocationMeta,
    ): SkillLookupResponse {
        val skillId = requestedId.trim()
        if (skillId.isBlank()) {
            return SkillLookupResponse(
                error = SkillDiscoveryError(skillId, "invalid_skill_id", "Skill ID must not be blank.")
            )
        }

        return try {
            val unfilteredTools = AgentTools(toolCatalog.toolsByCategory).byName
            val enabledTools = AgentTools(toolsFilter.applyFilter(toolCatalog.toolsByCategory)).byName
            when {
                skillId in enabledTools -> SkillLookupResponse(skill = enabledTools.getValue(skillId).toDetail())
                else -> {
                    val parsedSkillId = SkillId(skillId)
                    val bundle = skillBundleProvider.loadSkillBundle(meta.userId, parsedSkillId)
                    when {
                        bundle != null -> approvedBundleResponse(
                            userId = meta.userId,
                            skillId = parsedSkillId,
                            bundle = bundle,
                        )
                        skillId in unfilteredTools -> SkillLookupResponse(
                            error = SkillDiscoveryError(
                                skillId,
                                "skill_disabled",
                                "Tool-backed Skill is disabled: $skillId",
                            )
                        )
                        else -> SkillLookupResponse(
                            error = SkillDiscoveryError(
                                skillId,
                                "skill_not_found",
                                "Skill is unavailable: $skillId",
                            )
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            SkillLookupResponse(
                error = SkillDiscoveryError(
                    skillId = skillId,
                    code = "skill_unavailable",
                    message = error.message ?: "Skill is unavailable: $skillId",
                )
            )
        }
    }

    private suspend fun approvedBundleResponse(
        userId: String,
        skillId: SkillId,
        bundle: SkillBundle,
    ): SkillLookupResponse {
        val gate = approvalGate
            ?: return SkillLookupResponse(
                skill = bundle.toDetail(),
                executionSchema = fileSkillExecutionSchema(),
            )

        return when (
            val approval = gate.ensureApproved(
                SkillApprovalGate.Input(
                    userId = userId,
                    skillId = skillId,
                    bundle = bundle,
                )
            )
        ) {
            is SkillApprovalGate.Result.Approved -> SkillLookupResponse(
                skill = approval.bundle.toDetail(),
                executionSchema = fileSkillExecutionSchema(),
            )

            is SkillApprovalGate.Result.Rejected -> SkillLookupResponse(
                error = SkillDiscoveryError(
                    skillId = skillId.value,
                    code = "skill_validation_rejected",
                    message = approval.reason,
                )
            )
        }
    }

    private fun LLMToolSetup.toDetail(): ToolSkillDetail = ToolSkillDetail(
        skillId = fn.name,
        name = fn.name,
        description = fn.description,
        inputSchema = fn.parameters,
        returnSchema = fn.returnParameters,
        fewShotExamples = fn.fewShotExamples.orEmpty(),
    )

    private fun fileSkillExecutionSchema(): SkillExecutionSchema = SkillExecutionSchema(
        inputSchema = fileSkillInputSchema,
        returnSchema = sandboxCommandResultSchema(),
    )

    private fun SkillBundle.toDetail(): BundleSkillDetail = BundleSkillDetail(
        skillId = skillId.value,
        name = manifest.name,
        description = manifest.description,
        skillMarkdownBody = skillMarkdownBody,
        supportingFiles = files
            .map { it.normalizedPath }
            .filterNot { it == SKILL_MARKDOWN_PATH },
    )

    companion object {
        const val NAME = "GetSkillByName"
        private const val SKILL_MARKDOWN_PATH = "SKILL.md"
    }
}

internal data class SkillLookupResponse(
    val skill: SkillDetail? = null,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val executionSchema: SkillExecutionSchema? = null,
    val error: SkillDiscoveryError? = null,
)

internal sealed interface SkillDetail {
    val skillId: String
}

internal data class SkillExecutionSchema(
    val inputSchema: LLMRequest.Parameters,
    val returnSchema: LLMRequest.Parameters,
)

private data class ToolSkillDetail(
    override val skillId: String,
    val name: String,
    val description: String,
    val inputSchema: LLMRequest.Parameters,
    val returnSchema: LLMRequest.Parameters?,
    val fewShotExamples: List<LLMRequest.FewShotExample>,
) : SkillDetail

private data class BundleSkillDetail(
    override val skillId: String,
    val name: String,
    val description: String,
    val skillMarkdownBody: String,
    val supportingFiles: List<String>,
) : SkillDetail

internal data class SkillDiscoveryError(
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
