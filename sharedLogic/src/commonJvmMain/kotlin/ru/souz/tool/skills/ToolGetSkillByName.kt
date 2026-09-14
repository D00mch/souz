package ru.souz.tool.skills

import com.fasterxml.jackson.annotation.JsonInclude
import kotlinx.coroutines.CancellationException
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.registry.SkillBundleProvider
import ru.souz.agent.skills.validation.SkillApprovalGate
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
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
            lookupError(null, "skills_unavailable", error.message ?: "Skills are unavailable.")
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
            return lookupError(skillId, "invalid_skill_id", "Skill ID must not be blank.")
        }

        return try {
            val resolver = SkillResolver(toolCatalog, toolsFilter, skillBundleProvider::loadSkillBundle, approvalGate)
            when (val resolved = resolver.resolve(SkillId(skillId), meta.userId)) {
                is SkillResolution.Compiled -> SkillLookupResponse(skill = resolved.tool.toDetail())
                is SkillResolution.Bundle -> SkillLookupResponse(
                    skill = resolved.bundle.toDetail(),
                    executionSchema = fileSkillExecutionSchema(),
                )
                is SkillResolution.Error -> lookupError(skillId, resolved.code, resolved.message)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            lookupError(skillId, "skill_unavailable", error.message ?: "Skill is unavailable: $skillId")
        }
    }

    private fun lookupError(skillId: String?, code: String, message: String) =
        SkillLookupResponse(error = SkillDiscoveryError(skillId, code, message))

    private fun LLMToolSetup.toDetail(): ToolSkillDetail = ToolSkillDetail(
        skillId = fn.name,
        name = fn.name,
        description = fn.description,
        inputSchema = fn.parameters,
        returnSchema = fn.returnParameters,
        fewShotExamples = fn.fewShotExamples.orEmpty(),
    )

    companion object {
        const val NAME = "GetSkillByName"
    }
}

private val fileSkillInputSchema = toolInputParameters<SkillCommandExecutor.Args>()

internal fun fileSkillExecutionSchema(): SkillExecutionSchema = SkillExecutionSchema(
    inputSchema = fileSkillInputSchema,
    returnSchema = sandboxCommandResultSchema(),
)

internal fun SkillBundle.toDetail(): SkillDetail = BundleSkillDetail(
    skillId = skillId.value,
    name = manifest.name,
    description = manifest.description,
    skillMarkdownBody = skillMarkdownBody,
    supportingFiles = files.map { it.normalizedPath }.filterNot { it == "SKILL.md" },
)

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
