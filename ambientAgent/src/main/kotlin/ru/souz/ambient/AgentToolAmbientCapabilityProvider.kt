package ru.souz.ambient

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter

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
                        risk = AmbientCapabilityRisk.UNKNOWN,
                        requiresConfirmation = true,
                    )
                }
            }
    }

    private fun String.compact(limit: Int): String =
        replace(Regex("\\s+"), " ").trim().let { normalized ->
            if (normalized.length <= limit) normalized else normalized.take(limit)
        }
}
