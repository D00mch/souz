package ru.souz.ambient

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.tool.ToolCategory
import java.util.Locale

class AgentToolAmbientCapabilityProvider(
    private val toolCatalog: AgentToolCatalog,
    private val toolsFilter: AgentToolsFilter,
) : AmbientCapabilityProvider {
    override suspend fun capabilities(): List<AmbientCapability> {
        val visibleTools = toolsFilter.applyFilter(toolCatalog.toolsByCategory)
        return visibleTools
            .toSortedMap(compareBy { it.name })
            .flatMap { (category, tools) ->
                tools.toSortedMap().values.map { setup ->
                    val fn = setup.fn
                    AmbientCapability(
                        id = "tool:${category.name}:${fn.name}",
                        kind = AmbientCapabilityKind.TOOL,
                        category = category.name,
                        name = fn.name,
                        description = fn.description.compact(240),
                        examples = fn.fewShotExamples
                            .orEmpty()
                            .take(2)
                            .map { it.request.compact(160) },
                        risk = mapRisk(category, fn.name, fn.description),
                        requiresConfirmation = true,
                    )
                }
            }
    }

    private fun mapRisk(category: ToolCategory, name: String, description: String): AmbientCapabilityRisk {
        val haystack = "${category.name} $name $description"
            .lowercase(Locale.ROOT)
            .replace(Regex("[_\\-]+"), " ")

        if (HIGH_RISK.any { it in haystack }) return AmbientCapabilityRisk.HIGH
        if (category == ToolCategory.CALCULATOR) return AmbientCapabilityRisk.LOW
        if (MEDIUM_RISK.any { it in haystack }) return AmbientCapabilityRisk.MEDIUM
        if (LOW_RISK.any { it in haystack }) return AmbientCapabilityRisk.LOW

        return when (category) {
            ToolCategory.CALCULATOR -> AmbientCapabilityRisk.LOW
            ToolCategory.WEB_SEARCH,
            ToolCategory.DATA_ANALYTICS -> AmbientCapabilityRisk.LOW
            else -> AmbientCapabilityRisk.UNKNOWN
        }
    }

    private fun String.compact(limit: Int): String =
        replace(Regex("\\s+"), " ").trim().let { normalized ->
            if (normalized.length <= limit) normalized else normalized.take(limit)
        }

    private companion object {
        val LOW_RISK = listOf(
            "read",
            "list",
            "get",
            "search",
            "status",
            "calculate",
            "calculator",
            "info",
            "find",
            "extract",
            "open path",
        )

        val MEDIUM_RISK = listOf(
            "create",
            "update",
            "open app",
            "open browser",
            "browser tab",
            "create note",
            "create event",
            "draft",
            "move file",
            "copy file",
        )

        val HIGH_RISK = listOf(
            "delete",
            "remove",
            "modify",
            "write file",
            "overwrite",
            "send mail",
            "send message",
            "telegram send",
            "forward",
            "shell",
            "bash",
            "command",
            "screen recording",
            "record screen",
            "memory write",
            "calendar delete",
            "destructive",
            "irreversible",
            "click",
            "submit",
        )
    }
}
