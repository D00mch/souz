package ru.souz.tool.skills

import kotlinx.coroutines.CancellationException
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.restJsonMapper
import ru.souz.tool.ToolCategory

/** Both category views select and render one filtered catalogue snapshot. */
internal class CategoryDiscovery(
    private val catalogProvider: () -> AgentToolCatalog,
    private val toolsFilter: AgentToolsFilter,
    private val details: Boolean,
) : LLMToolSetup {
    override val fn = LLMRequest.Function(
        name = if (details) ToolGetSkillsByCategory.NAME else ToolGetSkillsNamesByCategory.NAME,
        description = if (details) {
            "Page Skill descriptions and schemas in a category. Default 20, maximum 16 KiB; continue with nextOffset. Inspect oversized Skills with GetSkillByName."
        } else {
            "Page exact Skill IDs in a category. Default 20; continue with nextOffset."
        },
        parameters = LLMRequest.Parameters(
            type = "object",
            properties = SkillDiscoveryPage.parameters + ("category" to LLMRequest.Property(
                "string", "Skill category name.", enum = ToolCategory.entries.map { it.name },
            )),
            required = listOf("category"),
        ),
        returnParameters = LLMRequest.Parameters(
            type = "object",
            properties = mapOf(
                "category" to LLMRequest.Property("string", "Canonical category name."),
                "totalSkills" to LLMRequest.Property("integer", "Number of entries in the category."),
                "nextOffset" to LLMRequest.Property("integer", "Next page position, or null when complete."),
                (if (details) "skills" else "skillNames") to LLMRequest.Property("array", "Selected Skills."),
                (if (details) "errors" else "error") to LLMRequest.Property(
                    if (details) "array" else "object", "Discovery errors.",
                ),
            ),
        ),
    )

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message {
        var category: ToolCategory? = null
        var total = 0
        var next: Int? = null
        val found = mutableListOf<SkillLookupResponse>()
        try {
            val page = SkillDiscoveryPage(functionCall.arguments)
            val name = (functionCall.arguments["category"] as? String).orEmpty().trim()
            category = ToolCategory.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            if (category == null) {
                found += SkillLookupResponse(error = SkillDiscoveryError(
                    null, if (name.isEmpty()) "invalid_category" else "category_not_found",
                    if (name.isEmpty()) "Category name must not be blank." else "Unknown Skill category: $name",
                ))
            } else {
                val tools = toolsFilter.applyFilter(catalogProvider().toolsByCategory)[category]
                    .orEmpty().toSortedMap().values.toList()
                total = tools.size
                found += page.select(tools, oversized = {
                    SkillLookupResponse(error = SkillDiscoveryError(
                        it.fn.name, "skill_detail_too_large", "Inspect this Skill with GetSkillByName.",
                    ))
                }) { tool ->
                    try {
                        SkillLookupResponse(skill = if (details) tool.toDetail() else SkillName(tool.fn.name))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        SkillLookupResponse(error = SkillDiscoveryError(tool.fn.name, "skill_unavailable", error.message.orEmpty()))
                    }
                }
                next = page.nextOffset
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            found += SkillLookupResponse(error = SkillDiscoveryError(
                null, if (category == null) "categories_unavailable" else "category_unavailable", error.message.orEmpty(),
            ))
        }
        val response = mapOf(
            "category" to category?.name,
            "totalSkills" to total,
            "nextOffset" to next,
        ) + if (details) {
            mapOf("skills" to found.mapNotNull { it.skill }, "errors" to found.mapNotNull { it.error })
        } else {
            mapOf("skillNames" to found.mapNotNull { it.skill?.skillId }, "error" to found.firstNotNullOfOrNull { it.error })
        }
        return LLMRequest.Message(LLMMessageRole.function, restJsonMapper.writeValueAsString(response), name = functionCall.name)
    }
}

private data class SkillName(override val skillId: String) : SkillDetail
