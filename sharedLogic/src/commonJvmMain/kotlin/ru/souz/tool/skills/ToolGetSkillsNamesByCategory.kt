package ru.souz.tool.skills

import kotlinx.coroutines.CancellationException
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.tool.ToolCategory

/** Returns exact IDs for enabled tool-backed Skills in one compiled-tool category. */
class ToolGetSkillsNamesByCategory(
    private val toolCatalog: AgentToolCatalog,
    private val toolsFilter: AgentToolsFilter,
) : LLMToolSetup {
    data class Input(
        val category: String = "",
    )

    override val fn: LLMRequest.Function = LLMRequest.Function(
        name = NAME,
        description = "List the exact Skill IDs in one category without loading their full descriptions.",
        parameters = categoryInputParameters(),
        returnParameters = LLMRequest.Parameters(
            type = "object",
            properties = mapOf(
                "category" to LLMRequest.Property("string", "Canonical category name."),
                "skillNames" to LLMRequest.Property("array", "Exact Skill IDs in the category."),
                "error" to LLMRequest.Property("object", "A category lookup error, or null on success."),
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
            getSkillNames(input.category)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            CategorySkillNamesResponse(
                error = SkillDiscoveryError(
                    skillId = null,
                    code = "categories_unavailable",
                    message = error.message ?: "Skill categories are unavailable.",
                )
            )
        }
        return LLMRequest.Message(
            role = LLMMessageRole.function,
            content = restJsonMapper.writeValueAsString(response),
            name = functionCall.name,
        )
    }

    internal suspend fun getSkillNames(
        requestedCategory: String,
    ): CategorySkillNamesResponse {
        val categoryName = requestedCategory.trim()
        if (categoryName.isBlank()) {
            return CategorySkillNamesResponse(
                error = SkillDiscoveryError(null, "invalid_category", "Category name must not be blank.")
            )
        }
        val category = ToolCategory.entries.firstOrNull { it.name.equals(categoryName, ignoreCase = true) }
            ?: return CategorySkillNamesResponse(
                error = SkillDiscoveryError(null, "category_not_found", "Unknown Skill category: $categoryName")
            )
        val canonicalCategory = category.name

        return try {
            val filteredCatalog = toolsFilter.applyFilter(toolCatalog.toolsByCategory)
            CategorySkillNamesResponse(
                category = canonicalCategory,
                skillNames = filteredCatalog[category]
                    .orEmpty()
                    .keys
                    .sorted(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            CategorySkillNamesResponse(
                category = canonicalCategory,
                error = SkillDiscoveryError(
                    skillId = null,
                    code = "category_unavailable",
                    message = error.message ?: "Skill category is unavailable: $canonicalCategory",
                )
            )
        }
    }

    companion object {
        const val NAME = "GetSkillsNamesByCategory"
    }
}

internal data class CategorySkillNamesResponse(
    val category: String? = null,
    val skillNames: List<String> = emptyList(),
    val error: SkillDiscoveryError? = null,
)

internal fun categoryInputParameters(): LLMRequest.Parameters = LLMRequest.Parameters(
    type = "object",
    properties = mapOf(
        "category" to LLMRequest.Property(
            type = "string",
            description = "Skill category name.",
            enum = ToolCategory.entries.map { it.name },
        )
    ),
    required = listOf("category"),
)
