package ru.souz.tool.skills

import kotlinx.coroutines.CancellationException
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundleHasher
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
import ru.souz.llms.restJsonMapper

/**
 * Routes a generic Skill invocation to either a compiled tool or a file-backed Skill.
 *
 * Implements [LLMToolSetup] directly to preserve delegated messages and attachments and to return
 * structured command results without the additional String serialization performed by
 * [ru.souz.llms.giga.toGiga].
 */
class ToolInvokeSkill(
    private val toolCatalog: AgentToolCatalog,
    private val toolsFilter: AgentToolsFilter,
    private val skillBundleProvider: SkillBundleProvider,
    private val commandTool: ToolRunSkillCommand,
    private val approvalGate: SkillApprovalGate? = null,
) : LLMToolSetup {
    data class Input(
        val skillId: String,
        val arguments: Map<String, Any> = emptyMap(),
    )

    override val fn: LLMRequest.Function = LLMRequest.Function(
        name = NAME,
        description = "Invoke one available Skill. Inspect its details with GetSkillByName or GetSkillsByCategory first, then pass arguments matching the returned input schema.",
        parameters = LLMRequest.Parameters(
            type = "object",
            properties = mapOf(
                "skillId" to LLMRequest.Property("string", "Exact unqualified Skill ID returned by a Skill discovery tool."),
                "arguments" to LLMRequest.Property("object", "Arguments matching the input schema returned by a Skill discovery tool."),
            ),
            required = listOf("skillId"),
        ),
    )

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
        invoke(functionCall, ToolInvocationMeta.localDefault())

    override suspend fun invoke(
        functionCall: LLMResponse.FunctionCall,
        meta: ToolInvocationMeta,
    ): LLMRequest.Message = try {
        val input = restJsonMapper.convertValue(functionCall.arguments, Input::class.java)
        invokeSkill(input, functionCall.name, meta)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        errorMessage(
            functionName = functionCall.name,
            code = "skill_invocation_failed",
            message = error.message ?: "Skill invocation failed.",
        )
    }

    /** Returns the enabled compiled tool delegated to by this Skill ID without loading Skill storage. */
    fun delegatedToolName(skillId: String): String? =
        skillId.trim().takeIf { it.isNotEmpty() }?.let { enabledTools()[it]?.fn?.name }

    private suspend fun invokeSkill(
        input: Input,
        outerFunctionName: String,
        meta: ToolInvocationMeta,
    ): LLMRequest.Message {
        val skillId = input.skillId.trim()
        if (skillId.isEmpty()) {
            return errorMessage(outerFunctionName, "invalid_skill_id", "Skill ID must not be blank.")
        }

        val unfilteredTools = unfilteredTools()
        val enabledTools = enabledTools()

        val enabledTool = enabledTools[skillId]
        if (enabledTool != null) {
            return enabledTool.invoke(
                LLMResponse.FunctionCall(
                    name = enabledTool.fn.name,
                    arguments = input.arguments,
                ),
                meta,
            ).copy(name = outerFunctionName)
        }

        val bundle = skillBundleProvider.loadSkillBundle(meta.userId, SkillId(skillId))
        if (bundle != null) {
            val approval = approvalGate?.ensureApproved(
                SkillApprovalGate.Input(
                    userId = meta.userId,
                    skillId = SkillId(skillId),
                    bundle = bundle,
                )
            )
            if (approval is SkillApprovalGate.Result.Rejected) {
                return errorMessage(
                    outerFunctionName,
                    "skill_validation_rejected",
                    approval.reason,
                )
            }
            val bundleHash = when (approval) {
                is SkillApprovalGate.Result.Approved -> approval.bundleHash
                null -> SkillBundleHasher.hash(bundle)
                is SkillApprovalGate.Result.Rejected -> error("Rejected approval must return before execution.")
            }
            val rawInput = restJsonMapper.convertValue(
                input.arguments + ("skillId" to skillId) - "activeSkills",
                ToolRunSkillCommand.Input::class.java,
            )
            val commandInput = rawInput.copy(
                skillId = skillId,
                activeSkills = listOf(
                    ToolRunSkillCommand.ActiveSkillInput(
                        skillId = skillId,
                        bundleHash = bundleHash,
                        supportingFiles = bundle.files
                            .map { it.normalizedPath }
                            .filterNot { it == SKILL_MARKDOWN_PATH },
                    )
                ),
            )
            val result = commandTool.executeCommand(commandInput, meta)
            return LLMRequest.Message(
                role = LLMMessageRole.function,
                content = restJsonMapper.writeValueAsString(result),
                name = outerFunctionName,
            )
        }

        if (skillId in unfilteredTools) {
            return errorMessage(
                outerFunctionName,
                "skill_disabled",
                "Tool-backed Skill is disabled: $skillId",
            )
        }
        return errorMessage(outerFunctionName, "skill_not_found", "Skill is unavailable: $skillId")
    }

    private fun unfilteredTools(): Map<String, LLMToolSetup> =
        AgentTools(toolCatalog.toolsByCategory).byName

    private fun enabledTools(): Map<String, LLMToolSetup> =
        AgentTools(toolsFilter.applyFilter(toolCatalog.toolsByCategory)).byName

    private fun errorMessage(
        functionName: String,
        code: String,
        message: String,
    ): LLMRequest.Message = LLMRequest.Message(
        role = LLMMessageRole.function,
        content = restJsonMapper.writeValueAsString(
            mapOf("error" to mapOf("code" to code, "message" to message))
        ),
        name = functionName,
    )

    companion object {
        const val NAME = "RunSkillCommand"
        private const val SKILL_MARKDOWN_PATH = "SKILL.md"
    }
}
