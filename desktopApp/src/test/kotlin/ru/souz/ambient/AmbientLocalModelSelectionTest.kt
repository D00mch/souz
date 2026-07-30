package ru.souz.ambient

import ru.souz.llms.LLMModel
import kotlin.test.Test
import kotlin.test.assertEquals

class AmbientLocalModelSelectionTest {

    @Test
    fun `selected available local model is used`() {
        val selected = selectAmbientLocalModel(
            configuredModel = LLMModel.LocalGemma4_E2B_It,
            isModelAvailable = { it == LLMModel.LocalGemma4_E2B_It },
            defaultLocalModel = { LLMModel.LocalQwen3_4B_Instruct_2507 },
        )

        assertEquals(LLMModel.LocalGemma4_E2B_It, selected)
    }

    @Test
    fun `non local selected model falls back to default local model`() {
        val selected = selectAmbientLocalModel(
            configuredModel = LLMModel.Max,
            isModelAvailable = { it == LLMModel.LocalQwen3_4B_Instruct_2507 },
            defaultLocalModel = { LLMModel.LocalQwen3_4B_Instruct_2507 },
        )

        assertEquals(LLMModel.LocalQwen3_4B_Instruct_2507, selected)
    }
}
