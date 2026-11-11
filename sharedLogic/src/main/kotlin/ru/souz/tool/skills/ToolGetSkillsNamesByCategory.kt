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
    private val catalogProvider: () -> AgentToolCatalog,
    private val toolsFilter: AgentToolsFilter,
) : LLMToolSetup {
    override val fn: LLMRequest.Function = LLMRequest.Function(
        name = NAME,
        description = "Page exact Skill IDs in one category without loading descriptions. Defaults to 20; use nextOffset to continue.",
        parameters = categoryInputParameters(),
        returnParameters = LLMRequest.Parameters(
            type = "object",
            properties = mapOf(
                "category" to LLMRequest.Property("string", "Canonical category name."),
                "skillNames" to LLMRequest.Property("array", "Exact Skill IDs in the category."),
                "error" to LLMRequest.Property("object", "A category lookup error, or null on success."),
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
            val all = getSkillNames(functionCall.arguments["category"] as? String ?: "")
            val page = all.skillNames.drop(input.offset).take(input.limit)
            all.copy(
                skillNames = page,
                totalSkills = all.skillNames.size,
                nextOffset = (input.offset + page.size).takeIf { it < all.skillNames.size },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: InvalidSkillCategory) {
            CategorySkillNamesResponse(error = error.error)
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

    private fun getSkillNames(requestedCategory: String): CategorySkillNamesResponse {
        val category = parseSkillCategory(requestedCategory)
        val canonicalCategory = category.name

        return try {
            val filteredCatalog = toolsFilter.applyFilter(catalogProvider().toolsByCategory)
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

internal fun parseSkillCategory(requestedCategory: String): ToolCategory {
    val name = requestedCategory.trim()
    return ToolCategory.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        ?: throw InvalidSkillCategory(if (name.isBlank()) {
            SkillDiscoveryError(null, "invalid_category", "Category name must not be blank.")
        } else {
            SkillDiscoveryError(null, "category_not_found", "Unknown Skill category: $name")
        })
}

internal class InvalidSkillCategory(val error: SkillDiscoveryError) : IllegalArgumentException(error.message)

internal data class CategorySkillNamesResponse(
    val category: String? = null,
    val skillNames: List<String> = emptyList(),
    val error: SkillDiscoveryError? = null,
    val totalSkills: Int = skillNames.size,
    val nextOffset: Int? = null,
)

internal fun categoryInputParameters(): LLMRequest.Parameters = LLMRequest.Parameters(
    type = "object",
    properties = mapOf(
        "category" to LLMRequest.Property(
            type = "string",
            description = "Skill category name.",
            enum = ToolCategory.entries.map { it.name },
        ),
    ) + discoveryPageParameters(),
    required = listOf("category"),
)
