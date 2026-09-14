package ru.souz.tool.skills

import kotlinx.coroutines.CancellationException
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.validation.SkillApprovalGate
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import kotlin.jvm.java

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
    private val loadBundle: suspend (userId: String, skillId: SkillId) -> SkillBundle?,
    private val commandExecutor: SkillCommandExecutor,
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
    ): LLMRequest.Message {
        return try {
            val input = restJsonMapper.convertValue(functionCall.arguments, Input::class.java)
            val skillId = input.skillId.trim()
            if (skillId.isEmpty()) {
                return errorMessage(functionCall.name, "invalid_skill_id", "Skill ID must not be blank.")
            }
            when (val resolved = resolver().resolve(SkillId(skillId), meta.userId)) {
                is SkillResolution.Compiled -> resolved.tool.invoke(
                    LLMResponse.FunctionCall(resolved.tool.fn.name, input.arguments),
                    meta = meta,
                ).copy(name = functionCall.name)
                is SkillResolution.Bundle -> {
                    val arguments = restJsonMapper.convertValue(input.arguments, SkillCommandExecutor.Args::class.java)
                    val result = commandExecutor.execute(resolved.bundle, resolved.bundleHash, arguments, meta)
                    resultMessage(functionCall.name, result)
                }
                is SkillResolution.Error -> errorMessage(functionCall.name, resolved.code, resolved.message)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            errorMessage(functionCall.name, "skill_invocation_failed", error.message ?: "Skill invocation failed.")
        }
    }

    /** Returns the enabled compiled tool delegated to by this Skill ID without loading Skill storage. */
    fun delegatedToolName(skillId: String): String? =
        skillId.trim().takeIf { it.isNotEmpty() }?.let { resolver().enabledTools.byName[it]?.fn?.name }

    private fun resolver() = SkillResolver(toolCatalog, toolsFilter, loadBundle, approvalGate)

    private fun errorMessage(functionName: String, code: String, message: String) =
        resultMessage(functionName, mapOf("error" to mapOf("code" to code, "message" to message)))

    private fun resultMessage(functionName: String, result: Any) = LLMRequest.Message(
        role = LLMMessageRole.function,
        content = restJsonMapper.writeValueAsString(result),
        name = functionName,
    )

    companion object {
        const val NAME = "RunSkillCommand"
    }
}
