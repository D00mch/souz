package ru.souz.backend.common

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.tool.ToolCategory
import ru.souz.tool.files.ToolGenerateImage
import ru.souz.tool.files.ToolViewImage
import ru.souz.tool.web.ToolInternetResearch
import ru.souz.tool.web.ToolInternetSearch

val BACKEND_SAFE_TOOL_CATEGORIES: Set<ToolCategory> = setOf(
    ToolCategory.FILES,
    ToolCategory.IMAGE,
    ToolCategory.IMAGE_GENERATION,
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

/** Names exposed by the backend, including tools whose LLM dependency is bound per execution. */
data class BackendAvailableToolNames(
    val values: Set<String>,
) {
    companion object {
        fun fromProcessCatalog(toolCatalog: AgentToolCatalog): BackendAvailableToolNames =
            BackendAvailableToolNames(
                values = buildSet {
                    addAll(backendSafeToolNames(toolCatalog))
                    add(ToolInternetSearch.NAME)
                    add(ToolInternetResearch.NAME)
                    add(ToolViewImage.NAME)
                    add(ToolGenerateImage.NAME)
                }
            )
    }
}
