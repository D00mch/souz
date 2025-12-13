@file:OptIn(ExperimentalAtomicApi::class)

package ru.abledo.tool

import ru.abledo.db.ConfigStore
import ru.abledo.giga.GigaToolSetup
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

private const val TOOLS_SETTINGS_KEY = "TOOLS_SETTINGS"

data class ToolCategorySettings(
    val enabled: Boolean = true,
    val allowedTools: Map<String, Boolean> = emptyMap(),
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
                if (categorySavedSettings?.allowedTools?.get(toolName) != false) {
                    allowed[toolName] = setup
                }
            }

            if (allowed.isNotEmpty()) {
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
                allowedTools = tools.keys.associateWith { true },
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
            val mergedTools = defaultCat.allowedTools.mapValues { (toolName, defaultValue) ->
                savedCategory?.allowedTools?.get(toolName) ?: defaultValue
            }
            ToolCategorySettings(
                enabled = savedCategory?.enabled ?: defaultCat.enabled,
                allowedTools = mergedTools,
            )
        }

        return ToolsSettingsState(categories = mergedCategories)
    }
}
