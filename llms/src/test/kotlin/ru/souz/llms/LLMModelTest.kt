package ru.souz.llms

import kotlin.test.Test
import kotlin.test.assertEquals

class LLMModelTest {

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
