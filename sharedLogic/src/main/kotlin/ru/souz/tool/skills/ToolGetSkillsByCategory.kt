package ru.souz.tool.skills

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper

/** Bounded category discovery resolves one immutable catalogue and enabled-tool index per call. */
class ToolGetSkillsByCategory(
    private val getSkillByName: ToolGetSkillByName,
    private val getSkillsNamesByCategory: ToolGetSkillsNamesByCategory,
) : LLMToolSetup {
    override val fn: LLMRequest.Function = LLMRequest.Function(
        name = NAME,
        description = "Page descriptions and schemas in one category. Defaults to 20 with a 16 KiB result budget; continue with nextOffset. Inspect oversized entries with GetSkillByName.",
        parameters = categoryInputParameters(),
        returnParameters = LLMRequest.Parameters(
            type = "object",
            properties = mapOf(
                "category" to LLMRequest.Property("string", "Canonical category name."),
                "skills" to LLMRequest.Property("array", "Full descriptions of Skills in the category."),
                "errors" to LLMRequest.Property("array", "Per-Skill or category lookup errors."),
            ) + categoryPageResultProperties(),
        ),
    )

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
        invoke(functionCall, ToolInvocationMeta.localDefault())

    override suspend fun invoke(
        functionCall: LLMResponse.FunctionCall,
        meta: ToolInvocationMeta,
    ): LLMRequest.Message {
        val response = try {
            val input = discoveryPage(functionCall.arguments)
            getSkills(functionCall.arguments["category"] as? String ?: "", input, meta)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            SkillsByCategoryResponse(
                errors = listOf(
                    SkillDiscoveryError(
                        skillId = null,
                        code = "categories_unavailable",
                        message = error.message ?: "Skill categories are unavailable.",
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

    private suspend fun getSkills(
        requestedCategory: String,
        input: DiscoveryPage,
        meta: ToolInvocationMeta,
    ): SkillsByCategoryResponse {
        val resolver = getSkillByName.resolver()
        val names = getSkillsNamesByCategory.getSkillNames(requestedCategory, resolver.enabledTools.byCategory)
        names.error?.let { error ->
            return SkillsByCategoryResponse(category = names.category, errors = listOf(error))
        }

        val skills = mutableListOf<SkillDetail>()
        val errors = mutableListOf<SkillDiscoveryError>()
        var position = input.offset.coerceAtMost(names.skillNames.size)
        var bytes = DISCOVERY_ENVELOPE_BYTES
        while (position < names.skillNames.size && skills.size + errors.size < input.limit) {
            currentCoroutineContext().ensureActive()
            val skillId = names.skillNames[position]
            val lookup = getSkillByName.getSkill(skillId, meta, resolver)
            val size = restJsonMapper.writeValueAsBytes(lookup).size + 1
            if (bytes + size > DISCOVERY_PAGE_BYTES) {
                if (skills.isEmpty() && errors.isEmpty()) {
                    errors += SkillDiscoveryError(skillId, "skill_detail_too_large", "Inspect this Skill with GetSkillByName.")
                    position++
                }
                break
            }
            lookup.skill?.let(skills::add)
            lookup.error?.let(errors::add)
            bytes += size
            position++
        }
        return SkillsByCategoryResponse(
            category = names.category,
            skills = skills,
            errors = errors,
            totalSkills = names.skillNames.size,
            nextOffset = position.takeIf { it < names.skillNames.size },
        )
    }

    companion object {
        const val NAME = "GetSkillsByCategory"
    }
}

private data class SkillsByCategoryResponse(
    val category: String? = null,
    val skills: List<SkillDetail> = emptyList(),
    val errors: List<SkillDiscoveryError> = emptyList(),
    val totalSkills: Int = 0,
    val nextOffset: Int? = null,
)
