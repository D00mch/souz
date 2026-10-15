package ru.souz.tool

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.db.SettingsProvider

class ToolsFactory(
    runtimeToolCatalog: AgentToolCatalog,
    llmBackedToolCatalog: LlmBackedToolCatalog,
    desktopToolCatalog: AgentToolCatalog,
    settingsProvider: SettingsProvider,
) {
    val catalog = AgentToolCatalog.compose(runtimeToolCatalog, llmBackedToolCatalog.catalog, desktopToolCatalog)
        .mapTools { _, tool ->
            if (settingsProvider.useFewShotExamples) tool else tool.withoutFewShotExamples()
        }
}
