package ru.souz.tool.skills

import kotlinx.coroutines.CancellationException
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper

/** Loads every available Skill in a category through [ToolGetSkillByName]. */
class ToolGetSkillsByCategory(
    private val getSkillByName: ToolGetSkillByName,
    private val getSkillsNamesByCategory: ToolGetSkillsNamesByCategory,
) : LLMToolSetup {
    data class Input(
        val category: String = "",
    )

    override val fn: LLMRequest.Function = LLMRequest.Function(
        name = NAME,
        description = "Load the full descriptions and schemas for every available Skill in one category. Prefer this when the user task clearly belongs to a category.",
        parameters = categoryInputParameters(),
        returnParameters = LLMRequest.Parameters(
            type = "object",
            properties = mapOf(
                "category" to LLMRequest.Property("string", "Canonical category name."),
                "skills" to LLMRequest.Property("array", "Full descriptions of Skills in the category."),
                "errors" to LLMRequest.Property("array", "Per-Skill or category lookup errors."),
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
            getSkills(input.category, meta)
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
        meta: ToolInvocationMeta,
    ): SkillsByCategoryResponse {
        val names = getSkillsNamesByCategory.getSkillNames(requestedCategory)
        names.error?.let { error ->
            return SkillsByCategoryResponse(category = names.category, errors = listOf(error))
        }

        val skills = mutableListOf<SkillDetail>()
        val errors = mutableListOf<SkillDiscoveryError>()
        for (skillId in names.skillNames) {
            val lookup = getSkillByName.getSkill(skillId, meta)
            lookup.skill?.let(skills::add)
            lookup.error?.let(errors::add)
        }
        return SkillsByCategoryResponse(
            category = names.category,
            skills = skills,
            errors = errors,
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
)
