package ru.souz.ui.tools

import androidx.lifecycle.viewModelScope
import com.fasterxml.jackson.core.type.TypeReference
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.souz.llms.giga.gigaJsonMapper
import ru.souz.tool.FewShotExample
import ru.souz.tool.ToolCategory
import ru.souz.tool.ToolCategorySettings
import ru.souz.tool.ToolSettingsEntry
import ru.souz.tool.ToolsFactory
import ru.souz.tool.ToolsSettings
import ru.souz.tool.ToolsSettingsState
import ru.souz.ui.BaseViewModel
import java.util.UUID
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

private val paramsTypeRef = object : TypeReference<Map<String, Any>>() {}

class ToolDetailsViewModel(
    override val di: DI,
    private val theCategory: ToolCategory,
    private val theToolName: String,
) : BaseViewModel<ToolDetailsState, ToolDetailsEvent, ToolDetailsEffect>(), DIAware {

    private val toolsFactory: ToolsFactory by di.instance()
    private val toolsSettings: ToolsSettings by di.instance()
    private val l = LoggerFactory.getLogger(ToolDetailsViewModel::class.java)

    init {
        viewModelScope.launch { load() }
    }

    override fun initialState(): ToolDetailsState = ToolDetailsState()

    override suspend fun handleEvent(event: ToolDetailsEvent) {
        when (event) {
            is ToolDetailsEvent.UpdateDescription -> updateDescription(event.value)
            is ToolDetailsEvent.ToggleEnabled -> updateEnabled(event.enabled)
            ToolDetailsEvent.AddExample -> addExample()
            is ToolDetailsEvent.RemoveExample -> removeExample(event.id)
            is ToolDetailsEvent.UpdateExampleRequest -> updateExampleRequest(event.id, event.value)
            is ToolDetailsEvent.UpdateExampleParams -> updateExampleParams(event.id, event.value)
            ToolDetailsEvent.Save -> saveSettings()
            ToolDetailsEvent.ResetToDefault -> resetToDefaults()
        }
    }

    override suspend fun handleSideEffect(effect: ToolDetailsEffect) {
        l.debug("Effect: {}", effect)
    }

    private suspend fun load() {
        val setup = toolsFactory.toolsByCategory[theCategory]?.get(theToolName)
        if (setup == null) {
            val errorMsg = getString(Res.string.tool_details_error_missing)
            setState { copy(error = errorMsg) }
            return
        }

        val settingsState = toolsSettings.load(toolsFactory.toolsByCategory)
        val categorySettings = settingsState.categories[theCategory]
        val toolSettings = categorySettings?.settings?.get(theToolName)

        val defaultExamples = setup.fn.fewShotExamples
            ?.map { FewShotExample(it.request, it.params) }
            ?: emptyList()
        val defaultDescription = setup.fn.description

        setState {
            copy(
                category = theCategory,
                toolName = theToolName,
                description = toolSettings?.description ?: defaultDescription,
                enabled = toolSettings?.enabled ?: true,
                examples = (toolSettings?.examples ?: defaultExamples).map { it.toUi() },
                defaultExamples = defaultExamples,
                defaultDescription = defaultDescription,
                defaultEnabled = true,
                error = null,
            )
        }
    }

    private suspend fun updateDescription(value: String) {
        setState { copy(description = value) }
    }

    private suspend fun updateEnabled(enabled: Boolean) {
        setState { copy(enabled = enabled) }
    }

    private suspend fun addExample() {
        setState {
            copy(
                examples = examples + ToolExampleUi(
                    id = newExampleId(),
                    request = "",
                    paramsJson = "{}",
                ),
                error = null,
            )
        }
    }

    private suspend fun removeExample(id: String) {
        setState {
            copy(
                examples = examples.filterNot { it.id == id },
                error = null,
            )
        }
    }

    private suspend fun updateExampleRequest(id: String, value: String) {
        setState {
            copy(
                examples = examples.map { if (it.id == id) it.copy(request = value) else it },
                error = null,
            )
        }
    }

    private suspend fun updateExampleParams(id: String, value: String) {
        setState {
            copy(
                examples = examples.map { example ->
                    if (example.id == id) example.copy(paramsJson = value, paramsError = null) else example
                },
                error = null,
            )
        }
    }

    private suspend fun resetToDefaults() {
        setState {
            copy(
                description = defaultDescription,
                examples = defaultExamples.map { it.toUi() },
                enabled = defaultEnabled,
                error = null,
            )
        }
    }

    private suspend fun saveSettings() {
        val cleanedExamples = currentState.examples.map { it.copy(paramsError = null) }
        val parsedExamples = parseExamples(cleanedExamples)

        if (parsedExamples.error != null || parsedExamples.examples == null) {
            val parseError = parsedExamples.error ?: return
            setState {
                copy(
                    error = parseError.message,
                    examples = cleanedExamples.map { example ->
                        if (example.id == parseError.exampleId) example.copy(paramsError = parseError.message) else example
                    }
                )
            }
            return
        }

        setState { copy(isSaving = true, examples = cleanedExamples, error = null) }

        val settingsState = toolsSettings.load(toolsFactory.toolsByCategory)
        val updatedCategories = settingsState.categories.toMutableMap()
        val categorySettings = updatedCategories[theCategory] ?: ToolCategorySettings()
        val allowedTools = categorySettings.settings.toMutableMap()

        allowedTools[theToolName] = ToolSettingsEntry(
            enabled = currentState.enabled,
            description = currentState.description,
            examples = parsedExamples.examples,
        )

        updatedCategories[theCategory] = categorySettings.copy(settings = allowedTools)

        toolsSettings.save(ToolsSettingsState(categories = updatedCategories))
        setState { copy(isSaving = false) }
        send(ToolDetailsEffect.Saved(getString(Res.string.tool_details_saved)))
    }

    private suspend fun parseExamples(examples: List<ToolExampleUi>): ExamplesParseResult {
        val parsed = mutableListOf<FewShotExample>()
        for (example in examples) {
            val params = if (example.paramsJson.isBlank()) {
                emptyMap()
            } else {
                runCatching { gigaJsonMapper.readValue(example.paramsJson, paramsTypeRef) }
                    .getOrElse {
                        return ExamplesParseResult(
                            examples = null,
                            error = ExampleParseError(
                                exampleId = example.id,
                                message = getString(Res.string.tool_details_error_json).format(it.message ?: it.toString()),
                            )
                        )
                    }
            }
            parsed += FewShotExample(example.request, params)
        }
        return ExamplesParseResult(parsed, null)
    }

    private fun FewShotExample.toUi(): ToolExampleUi = ToolExampleUi(
        id = newExampleId(),
        request = request,
        paramsJson = gigaJsonMapper.writeValueAsString(params),
    )

    private fun newExampleId(): String = UUID.randomUUID().toString()
}

private data class ExamplesParseResult(
    val examples: List<FewShotExample>?,
    val error: ExampleParseError?,
)

private data class ExampleParseError(
    val exampleId: String,
    val message: String,
)
