@file:OptIn(ExperimentalAtomicApi::class)

package ru.souz.tool

import ru.souz.agent.spi.AgentToolFilterContext
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.db.ConfigStore
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMToolSetup
import ru.souz.service.telegram.TelegramAuthStep
import ru.souz.service.telegram.TelegramPlatformSupport
import ru.souz.service.telegram.TelegramService
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
    private val toolsFactory: ToolsFactory,
    private val telegramService: TelegramService,
    private val telegramPlatformSupport: TelegramPlatformSupport,
) : AgentToolsFilter {
    private val localState: AtomicReference<ToolsSettingsState?> = AtomicReference(null)

    fun load(
        toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> = toolsFactory.toolsByCategory
    ): ToolsSettingsState {
        val storedState: ToolsSettingsState? = localState.load() ?: store.get(TOOLS_SETTINGS_KEY)
        val defaultState = defaultState(toolsByCategory)
        return enforcePlatformRestrictions(merge(defaultState, storedState))
    }

    fun isCategoryAllowed(category: ToolCategory): Boolean {
        if (isCategoryForceDisabled(category)) return false
        val storedState: ToolsSettingsState = localState.load() ?: store.get(TOOLS_SETTINGS_KEY) ?: return true
        return storedState.categories[category]?.enabled ?: true
    }

    fun save(state: ToolsSettingsState) {
        val normalizedState = enforcePlatformRestrictions(state)
        localState.store(normalizedState)
        store.put(TOOLS_SETTINGS_KEY, normalizedState)
    }

    override fun applyFilter(
        toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>,
        context: AgentToolFilterContext,
    ): Map<ToolCategory, Map<String, LLMToolSetup>> {
        val savedSettings = load(toolsByCategory)
        val result = HashMap<ToolCategory, Map<String, LLMToolSetup>>(toolsByCategory.size)
        for ((category, tools) in toolsByCategory) {
            if (isCategoryForceDisabled(category)) continue
            val categorySavedSettings = savedSettings.categories[category]
            if (categorySavedSettings?.enabled == false) continue

            val allowed = HashMap<String, LLMToolSetup>(tools.size)
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
        toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>,
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

        val mergedCategories: Map<ToolCategory, ToolCategorySettings> =
            defaults.categories.mapValues { (category, defaultCat) ->
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

    private fun enforcePlatformRestrictions(state: ToolsSettingsState): ToolsSettingsState {
        if (telegramPlatformSupport.isSupported()) return state

        val telegramSettings = state.categories[ToolCategory.TELEGRAM]
        val disabledTelegramSettings =
            telegramSettings?.settings?.mapValues { (_, tool) -> tool.copy(enabled = false) } ?: emptyMap()

        return state.copy(
            categories = state.categories + (ToolCategory.TELEGRAM to ToolCategorySettings(
                enabled = false,
                settings = disabledTelegramSettings,
            ))
        )
    }

    private fun isCategoryForceDisabled(category: ToolCategory): Boolean =
        when (category) {
            ToolCategory.TELEGRAM -> {
                !telegramPlatformSupport.isSupported() ||
                    telegramService.authState.value.step != TelegramAuthStep.READY
            }

            else -> false
        }

    private fun applyOverrides(
        setup: LLMToolSetup,
        savedToolSettings: ToolSettingsEntry?,
    ): LLMToolSetup {
        if (savedToolSettings == null) return setup
        val description = savedToolSettings.description ?: setup.fn.description

        if (description == setup.fn.description && savedToolSettings.examples == null) {
            return setup
        }

        val examples: List<LLMRequest.FewShotExample> =
            savedToolSettings.examples?.map { LLMRequest.FewShotExample(it.request, it.params) } ?: emptyList()

        return object : LLMToolSetup by setup {
            override val fn = setup.fn.copy(
                description = description,
                fewShotExamples = examples + (setup.fn.fewShotExamples ?: emptyList()),
            )
        }
    }
}
