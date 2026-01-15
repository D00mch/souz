package ru.gigadesk.ui.tools

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.gigadesk.tool.ToolCategory
import ru.gigadesk.tool.ToolCategorySettings
import ru.gigadesk.tool.ToolSettingsEntry
import ru.gigadesk.tool.ToolsFactory
import ru.gigadesk.tool.ToolsSettings
import ru.gigadesk.ui.BaseViewModel
import ru.gigadesk.tool.ToolsSettingsState as StoredToolsSettingsState

class ToolsSettingsViewModel(
    override val di: DI,
) : BaseViewModel<ToolsScreenState, ToolsSettingsEvent, ToolsSettingsEffect>(), DIAware {

    private val l = LoggerFactory.getLogger(ToolsSettingsViewModel::class.java)
    private val toolsFactory: ToolsFactory by di.instance()
    private val toolsSettings: ToolsSettings by di.instance()

    init {
        viewModelScope.launch { loadSettings() }
    }

    override fun initialState(): ToolsScreenState = ToolsScreenState()

    override suspend fun handleEvent(event: ToolsSettingsEvent) {
        when (event) {
            is ToolsSettingsEvent.ToggleCategory -> updateCategory(event.category, event.enabled)
            is ToolsSettingsEvent.ToggleTool -> updateTool(event.category, event.toolName, event.enabled)
            is ToolsSettingsEvent.UpdateCategoryExpanded -> updateCategoryExpanded(event.category, event.expanded)
            is ToolsSettingsEvent.UpdateScrollPosition -> updateScrollPosition(event.position)
            ToolsSettingsEvent.SaveSettings -> saveSettings()
        }
    }

    override suspend fun handleSideEffect(effect: ToolsSettingsEffect) {
        l.debug("No side effects to handle: {}", effect)
    }

    private suspend fun loadSettings() {
        val settingsState = toolsSettings.load(toolsFactory.toolsByCategory)
        val categories = buildUiCategories(settingsState)
        setState { copy(categories = categories) }
    }

    private fun buildUiCategories(settingsState: StoredToolsSettingsState): List<ToolsCategoryUi> =
        toolsFactory.toolsByCategory.map { (category, tools) ->
            val categorySettings = settingsState.categories[category] ?: ToolCategorySettings()
            val uiTools = tools.values.map { setup ->
                val toolSettings = categorySettings.settings[setup.fn.name]
                val enabled = toolSettings?.enabled ?: true
                ToolUi(
                    name = setup.fn.name,
                    description = toolSettings?.description ?: setup.fn.description,
                    enabled = enabled,
                    descriptionOverride = toolSettings?.description,
                    examplesOverride = toolSettings?.examples,
                )
            }.sortedBy { it.name }

            ToolsCategoryUi(
                category = category,
                enabled = categorySettings.enabled,
                tools = uiTools,
            )
        }

    private suspend fun updateCategory(category: ToolCategory, enabled: Boolean) {
        setState {
            val updatedCategories = categories.map { current ->
                if (current.category == category) current.copy(enabled = enabled) else current
            }
            copy(categories = updatedCategories)
        }
    }

    private suspend fun updateTool(category: ToolCategory, toolName: String, enabled: Boolean) {
        setState {
            val updatedCategories = categories.map { current ->
                if (current.category != category) return@map current
                current.copy(
                    tools = current.tools.map { tool ->
                        if (tool.name == toolName) tool.copy(enabled = enabled) else tool
                    }
                )
            }
            copy(categories = updatedCategories)
        }
    }

    private suspend fun updateCategoryExpanded(category: ToolCategory, expanded: Boolean) {
        setState {
            val updatedExpanded = expandedByCategory.toMutableMap().apply {
                put(category, expanded)
            }
            copy(expandedByCategory = updatedExpanded)
        }
    }

    private suspend fun updateScrollPosition(position: Int) {
        setState { copy(scrollPosition = position) }
    }

    private suspend fun saveSettings() {
        setState { copy(isSaving = true) }
        val settingsState = StoredToolsSettingsState(
            categories = currentState.categories.associate { category ->
                category.category to ToolCategorySettings(
                    enabled = category.enabled,
                    settings = category.tools.associate {
                        it.name to ToolSettingsEntry(
                            enabled = it.enabled,
                            description = it.descriptionOverride,
                            examples = it.examplesOverride,
                        )
                    },
                )
            }
        )
        toolsSettings.save(settingsState)
        setState { copy(isSaving = false) }
        send(ToolsSettingsEffect.SettingsSaved("Настройки инструментов сохранены"))
    }
}
