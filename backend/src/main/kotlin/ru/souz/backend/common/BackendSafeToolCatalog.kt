package ru.souz.backend.common

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.giga.toGiga
import ru.souz.tool.ToolCategory
import ru.souz.tool.math.ToolCalculator

private data class BackendSafeTool(
    val category: ToolCategory,
    val setup: LLMToolSetup,
)

private fun backendSafeTools(toolCalculator: ToolCalculator): List<BackendSafeTool> = listOf(
    BackendSafeTool(ToolCategory.CALCULATOR, toolCalculator.toGiga()),
)

private val backendSafeToolNamesByCategory: Map<ToolCategory, Set<String>> =
    backendSafeTools(ToolCalculator())
        .groupBy(keySelector = BackendSafeTool::category, valueTransform = { tool -> tool.setup.fn.name })
        .mapValues { (_, names) -> names.toSet() }

fun backendSafeToolNames(toolCatalog: AgentToolCatalog): List<String> =
    toolCatalog.toolsByCategory
        .asSequence()
        .flatMap { (category, tools) ->
            val safeNames = backendSafeToolNamesByCategory[category].orEmpty()
            tools.values.asSequence().filter { tool -> tool.fn.name in safeNames }
        }
        .map { tool -> tool.fn.name }
        .distinct()
        .sorted()
        .toList()

/**
 * Backend-owned compiled tools that do not read or write local files and do not launch commands.
 * Resource and PostgreSQL Skills are composed separately by the runtime.
 */
class BackendSafeToolCatalog(
    toolCalculator: ToolCalculator = ToolCalculator(),
) : AgentToolCatalog {
    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> =
        backendSafeTools(toolCalculator)
            .groupBy(keySelector = BackendSafeTool::category, valueTransform = BackendSafeTool::setup)
            .let { toolsByCategory ->
                ToolCategory.entries.associateWith { category ->
                    toolsByCategory[category].orEmpty().associateBy { tool -> tool.fn.name }
                }
            }
}
