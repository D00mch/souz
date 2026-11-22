package ru.souz.llms

import kotlin.test.Test
import kotlin.test.assertEquals

class LLMModelTest {

    @Test
    fun `Qwen cloud selectors resolve exact provider IDs without replacing existing selections`() {
        val models = mapOf(
            LLMModel.QwenFlash to "qwen-flash",
            LLMModel.QwenPlus to "qwen-plus",
            LLMModel.Qwen3OpenSource to "qwen3-vl-32b-instruct",
            LLMModel.Qwen38_27B to "qwen3.8-27b",
            LLMModel.QwenMax to "qwen-max",
        )
        for ((model, id) in models) {
            assertEquals(LlmProvider.QWEN, model.provider)
            assertEquals(id, model.alias)
            for (selector in listOf(model.name, id.uppercase())) {
                assertEquals(model, findLLMModel(" $selector "))
                assertEquals(ModelResolution.Resolved(model), resolveChatModel(" $selector "))
                assertEquals(
                    ModelResolution.UnsupportedProvider(model, LlmProvider.QWEN),
                    resolveChatModel(selector, supportedProviders = setOf(LlmProvider.LOCAL)),
                )
            }
        }
    }

    @Test
    fun `findLLMModel accepts current enum names aliases and legacy aliases`() {
        assertEquals(LLMModel.Max, findLLMModel("Max"))
        assertEquals(LLMModel.Max, findLLMModel("GigaChat-2-Max"))
        assertEquals(LLMModel.Max, findLLMModel("GigaChat-Max"))
        assertEquals(LLMModel.Pro, findLLMModel("GigaChat-Pro"))
        assertEquals(LLMModel.Lite, findLLMModel("GigaChat"))
        val replacement = LLMModel.LocalQwen3_4B_Instruct_2507
        for (selector in listOf("LocalQwen3_4B_Instruct_2507", "local-qwen3-4b-instruct-2507", "local-qwen3.5-4b")) {
            assertEquals(replacement, findLLMModel(" $selector "))
        }
        assertEquals(LLMModel.LocalQwen35_9B, findLLMModel("local-qwen3.5-9b"))
    }
}
