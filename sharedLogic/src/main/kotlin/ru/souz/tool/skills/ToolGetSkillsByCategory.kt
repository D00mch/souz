package ru.souz.tool.skills

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper

/** Bounded category discovery reads enabled tools directly from one immutable catalogue per call. */
class ToolGetSkillsByCategory(
    private val catalogProvider: () -> AgentToolCatalog,
    private val toolsFilter: AgentToolsFilter,
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
            getSkills(functionCall.arguments["category"] as? String ?: "", input)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: InvalidSkillCategory) {
            SkillsByCategoryResponse(errors = listOf(error.error))
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
    ): SkillsByCategoryResponse {
        val category = parseSkillCategory(requestedCategory)
        val tools = toolsFilter.applyFilter(catalogProvider().toolsByCategory)[category].orEmpty().entries.sortedBy { it.key }

        val skills = mutableListOf<SkillDetail>()
        val errors = mutableListOf<SkillDiscoveryError>()
        var position = input.offset.coerceAtMost(tools.size)
        var bytes = DISCOVERY_ENVELOPE_BYTES
        while (position < tools.size && skills.size + errors.size < input.limit) {
            currentCoroutineContext().ensureActive()
            val (skillId, tool) = tools[position]
            val lookup = try {
                SkillLookupResponse(skill = tool.toDetail())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                SkillLookupResponse(error = SkillDiscoveryError(
                    skillId, "skill_unavailable", error.message ?: "Skill is unavailable: $skillId",
                ))
            }
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
            category = category.name,
            skills = skills,
            errors = errors,
            totalSkills = tools.size,
            nextOffset = position.takeIf { it < tools.size },
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
