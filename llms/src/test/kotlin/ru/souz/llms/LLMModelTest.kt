package ru.souz.llms

import kotlin.test.Test
import kotlin.test.assertEquals

class LLMModelTest {

    @Test
    fun `findLLMModel accepts current enum names aliases and legacy giga aliases`() {
        assertEquals(LLMModel.Max, findLLMModel("Max"))
        assertEquals(LLMModel.Max, findLLMModel("GigaChat-2-Max"))
        assertEquals(LLMModel.Max, findLLMModel("GigaChat-Max"))
        assertEquals(LLMModel.Pro, findLLMModel("GigaChat-Pro"))
        assertEquals(LLMModel.Lite, findLLMModel("GigaChat"))
    }
}
