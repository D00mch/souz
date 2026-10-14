package ru.souz.agent.spi

import java.util.Collections
import ru.souz.llms.LLMToolSetup
import ru.souz.tool.ToolCategory

/**
 * Immutable catalog of host-constructed tools with globally unique function names.
 * Categories follow declaration order; tool order within each category follows the input.
 */
class AgentToolCatalog(
    tools: Map<ToolCategory, Collection<LLMToolSetup>> = emptyMap(),
) {
    val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> = run {
        val categoryByToolName = linkedMapOf<String, ToolCategory>()
        Collections.unmodifiableMap(
            ToolCategory.entries.associateWithTo(linkedMapOf()) { category ->
                val toolsByName = linkedMapOf<String, LLMToolSetup>()
                tools[category].orEmpty().forEach { tool ->
                    val name = tool.fn.name
                    val previousCategory = categoryByToolName.putIfAbsent(name, category)
                    require(previousCategory == null) {
                        "Duplicate tool name '$name' in categories $previousCategory and $category."
                    }
                    toolsByName[name] = tool
                }
                Collections.unmodifiableMap(toolsByName)
            }
        )
    }

    fun filter(predicate: (ToolCategory, LLMToolSetup) -> Boolean): AgentToolCatalog = AgentToolCatalog(
        toolsByCategory.mapValues { (category, tools) -> tools.values.filter { predicate(category, it) } }
    )

    fun mapTools(transform: (ToolCategory, LLMToolSetup) -> LLMToolSetup): AgentToolCatalog = AgentToolCatalog(
        toolsByCategory.mapValues { (category, tools) -> tools.values.map { transform(category, it) } }
    )

    companion object {
        fun compose(vararg catalogs: AgentToolCatalog): AgentToolCatalog = AgentToolCatalog(
            ToolCategory.entries.associateWith { category ->
                catalogs.flatMap { it.toolsByCategory.getValue(category).values }
            }
        )
    }
}
