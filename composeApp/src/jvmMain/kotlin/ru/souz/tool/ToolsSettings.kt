@file:OptIn(ExperimentalAtomicApi::class)

package ru.souz.tool

import ru.souz.db.ConfigStore
import ru.souz.giga.GigaRequest
import ru.souz.giga.GigaToolSetup
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

private const val TOOLS_SETTINGS_KEY = "TOOLS_SETTINGS"

data class ToolSettingsEntry(
    val enabled: Boolean = true,
    val description: String? = null,
    val examples: List<FewShotExample>? = null,
)

data class ToolCategorySettings(
    val enabled: Boolean = true,
    val settings: Map<String, ToolSettingsEntry> = emptyMap(),
)

data class ToolsSettingsState(
    val categories: Map<ToolCategory, ToolCategorySettings> = emptyMap(),
)

class ToolsSettings(
    private val store: ConfigStore,
    private val toolsFactory: ToolsFactory
) {
    private val localState: AtomicReference<ToolsSettingsState?> = AtomicReference(null)

    fun load(
        toolsByCategory: Map<ToolCategory, Map<String, GigaToolSetup>> = toolsFactory.toolsByCategory
    ): ToolsSettingsState {
        val storedState: ToolsSettingsState? = localState.load() ?: store.get(TOOLS_SETTINGS_KEY)
        val defaultState = defaultState(toolsByCategory)
        return merge(defaultState, storedState)
    }

    fun isCategoryAllowed(category: ToolCategory): Boolean {
        val storedState: ToolsSettingsState = localState.load() ?: store.get(TOOLS_SETTINGS_KEY) ?: return true
        return storedState.categories[category]?.enabled ?: true
    }

    fun save(state: ToolsSettingsState) {
        localState.store(state)
        store.put(TOOLS_SETTINGS_KEY, state)
    }

    fun applyFilter(
        toolsByCategory: Map<ToolCategory, Map<String, GigaToolSetup>>,
    ): Map<ToolCategory, Map<String, GigaToolSetup>> {
        val savedSettings = load(toolsByCategory)
        val result = HashMap<ToolCategory, Map<String, GigaToolSetup>>(toolsByCategory.size)
        for ((category, tools) in toolsByCategory) {
            val categorySavedSettings = savedSettings.categories[category]
            if (categorySavedSettings?.enabled == false) continue

            val allowed = HashMap<String, GigaToolSetup>(tools.size)
            for ((toolName, setup) in tools) {
                val toolSettings = categorySavedSettings?.settings?.get(toolName)
                if (toolSettings?.enabled != false) {
                    allowed[toolName] = applyOverrides(setup, toolSettings)
                }
            }

            val allToolsAreDisabled = tools.isNotEmpty() && allowed.isEmpty()
            if (!allToolsAreDisabled) {
                result[category] = allowed
            }
        }

        return result
    }

    private fun defaultState(
        toolsByCategory: Map<ToolCategory, Map<String, GigaToolSetup>>,
    ): ToolsSettingsState = ToolsSettingsState(
        categories = toolsByCategory.mapValues { (_, tools) ->
            ToolCategorySettings(
                enabled = true,
                settings = tools.keys.associateWith { ToolSettingsEntry() },
            )
        },
    )

    private fun merge(
        defaults: ToolsSettingsState,
        stored: ToolsSettingsState?,
    ): ToolsSettingsState {
        if (stored == null) return defaults

        val mergedCategories: Map<ToolCategory, ToolCategorySettings> = defaults.categories.mapValues { (category, defaultCat) ->
            val savedCategory = stored.categories[category]
            val mergedTools = defaultCat.settings.mapValues { (toolName, defaultValue) ->
                val savedTool = savedCategory?.settings?.get(toolName)
                ToolSettingsEntry(
                    enabled = savedTool?.enabled ?: defaultValue.enabled,
                    description = savedTool?.description ?: defaultValue.description,
                    examples = savedTool?.examples ?: defaultValue.examples,
                )
            }
            ToolCategorySettings(
                enabled = savedCategory?.enabled ?: defaultCat.enabled,
                settings = mergedTools,
            )
        }

        return ToolsSettingsState(categories = mergedCategories)
    }

    private fun applyOverrides(
        setup: GigaToolSetup,
        savedToolSettings: ToolSettingsEntry?,
    ): GigaToolSetup {
        if (savedToolSettings == null) return setup
        val description = savedToolSettings.description ?: setup.fn.description

        if (description == setup.fn.description && savedToolSettings.examples == null) {
            return setup
        }

        val examples: List<GigaRequest.FewShotExample> = savedToolSettings.examples
            ?.map { GigaRequest.FewShotExample(it.request, it.params) }
            ?: emptyList()

        return object : GigaToolSetup by setup {
            override val fn = setup.fn.copy(
                description = description,
                fewShotExamples = examples + (setup.fn.fewShotExamples ?: emptyList()),
            )
        }
    }
}
