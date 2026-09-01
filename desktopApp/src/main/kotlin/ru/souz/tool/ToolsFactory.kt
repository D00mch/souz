package ru.souz.tool

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMToolSetup

class ToolsFactory(
    runtimeToolCatalog: AgentToolCatalog,
    llmBackedToolCatalog: LlmBackedToolCatalog,
    desktopToolCatalog: AgentToolCatalog,
    settingsProvider: SettingsProvider,
) : AgentToolCatalog {
    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>

    init {
        val composed = composeToolCatalogs(runtimeToolCatalog, llmBackedToolCatalog, desktopToolCatalog)
        toolsByCategory = immutableToolCatalogSnapshot(
            ToolCategory.entries.associateWith { category ->
                composed.toolsByCategory.getValue(category).mapValues { (_, tool) ->
                    if (settingsProvider.useFewShotExamples) tool else tool.withoutFewShotExamples()
                }
            }
        ).toolsByCategory
    }
}
