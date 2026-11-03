package ru.souz.ui.memory

import ru.souz.llms.LLMModel
import ru.souz.memory.MemoryMaintenancePreferences
import ru.souz.memory.MemoryMaintenanceStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MemoryMaintenanceUiStateTest {
    @Test
    fun `selected model is resolved once and recomputed for copied state`() {
        val models = listOf(LLMModel.LocalQwen3_4B_Instruct_2507, LLMModel.LocalGemma4_E2B_It)
        var reads = 0
        val counted = object : AbstractList<LLMModel>() {
            override val size: Int = models.size
            override fun get(index: Int): LLMModel = models[index].also { reads++ }
        }
        val state = MemoryMaintenanceUiState(
            preferences = MemoryMaintenancePreferences(modelAlias = models.last().alias),
            availableModels = counted,
        )
        assertEquals(2, reads)
        repeat(10) { assertEquals(models.last(), state.selectedModel) }
        assertEquals(2, reads)

        val changed = state.copy(preferences = state.preferences.copy(modelAlias = models.first().alias))
        assertEquals(models.first(), changed.selectedModel)
        assertEquals(models.last(), state.selectedModel)
        assertNull(state.copy(availableModels = emptyList()).selectedModel)
        assertNull(state.copy(preferences = state.preferences.copy(modelAlias = "missing")).selectedModel)
        assertNull(state.copy(preferences = state.preferences.copy(modelAlias = null)).selectedModel)
    }

    @Test
    fun `controller model mutations cannot change an existing UI snapshot`() {
        val model = LLMModel.LocalQwen3_4B_Instruct_2507
        val models = mutableListOf(model)
        val status = MemoryMaintenanceStatus(
            preferences = MemoryMaintenancePreferences(modelAlias = model.alias),
            availableModels = models,
        )
        val state = status.toUiState()

        models.clear()

        assertEquals(listOf(model), state.availableModels)
        assertEquals(model, state.selectedModel)
        assertNull(status.toUiState().selectedModel)
    }
}
