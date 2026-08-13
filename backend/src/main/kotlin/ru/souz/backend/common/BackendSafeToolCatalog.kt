package ru.souz.backend.common

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.giga.toGiga
import ru.souz.tool.ToolCategory
import ru.souz.tool.math.ToolCalculator

val BACKEND_SAFE_TOOL_CATEGORIES: Set<ToolCategory> = setOf(
    ToolCategory.CALCULATOR,
)

val BACKEND_SAFE_COMPILED_TOOL_NAMES: Set<String> = setOf(
    "Calculator",
)

fun backendSafeToolNames(toolCatalog: AgentToolCatalog): List<String> =
    toolCatalog.toolsByCategory
        .filterKeys { it in BACKEND_SAFE_TOOL_CATEGORIES }
        .values
        .asSequence()
        .flatMap { tools -> tools.values.asSequence() }
        .map { tool -> tool.fn.name }
        .filter { toolName -> toolName in BACKEND_SAFE_COMPILED_TOOL_NAMES }
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
        ToolCategory.entries.associateWith { category ->
            when (category) {
                ToolCategory.CALCULATOR -> listOf(toolCalculator.toGiga())
                else -> emptyList()
            }.associateBy { tool -> tool.fn.name }
        }
}
