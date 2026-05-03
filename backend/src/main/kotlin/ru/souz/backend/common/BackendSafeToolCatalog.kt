package ru.souz.backend.common

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.tool.ToolCategory

val BACKEND_SAFE_TOOL_CATEGORIES: Set<ToolCategory> = setOf(
    ToolCategory.FILES,
    ToolCategory.WEB_SEARCH,
    ToolCategory.CONFIG,
    ToolCategory.DATA_ANALYTICS,
    ToolCategory.CALCULATOR,
)

fun backendSafeToolNames(toolCatalog: AgentToolCatalog): List<String> =
    toolCatalog.toolsByCategory
        .filterKeys { it in BACKEND_SAFE_TOOL_CATEGORIES }
        .values
        .asSequence()
        .flatMap { tools -> tools.values.asSequence() }
        .map { tool -> tool.fn.name }
        .distinct()
        .sorted()
        .toList()
