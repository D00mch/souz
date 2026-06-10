package ru.souz.agent.spi

import ru.souz.llms.LLMToolSetup
import ru.souz.tool.ToolCategory

class StaticAgentToolCatalog(
    toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>,
) : AgentToolCatalog {
    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> =
        toolsByCategory
            .mapValues { (_, tools) -> tools.toMap() }
            .filterValues { it.isNotEmpty() }
}

class CompositeAgentToolCatalog(
    private vararg val delegates: AgentToolCatalog,
) : AgentToolCatalog {
    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> by lazy {
        val merged = LinkedHashMap<ToolCategory, LinkedHashMap<String, LLMToolSetup>>()
        val seenToolCategories = LinkedHashMap<String, ToolCategory>()

        delegates.forEach { catalog ->
            catalog.toolsByCategory.forEach { (category, tools) ->
                tools.forEach { (toolName, tool) ->
                    val previousCategory = seenToolCategories.putIfAbsent(toolName, category)
                    require(previousCategory == null) {
                        "Duplicate tool name in composite catalog: $toolName in ${previousCategory?.name} and ${category.name}"
                    }
                    merged.getOrPut(category) { LinkedHashMap() }[toolName] = tool
                }
            }
        }

        merged
            .mapValues { (_, tools) -> tools.toMap() }
            .filterValues { it.isNotEmpty() }
    }
}
