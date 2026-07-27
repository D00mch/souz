package ru.souz.llms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LLMModelTest {
    @Test
    fun `codex model aliases include GPT 5_5 and GPT 5_6 family`() {
        val models = listOf(
            LLMModel.CodexGpt55,
            LLMModel.CodexGpt56Luna,
            LLMModel.CodexGpt56Terra,
            LLMModel.CodexGpt56Sol,
        )

        assertEquals(
            listOf("gpt-5.5", "gpt-5.6-luna", "gpt-5.6-terra", "gpt-5.6-sol"),
            models.map(LLMModel::alias),
        )
        assertEquals(setOf(LlmProvider.CODEX), models.mapTo(mutableSetOf(), LLMModel::provider))

        val buildProfile = LlmBuildProfile(
            settingsProvider = object : LlmBuildProfileSettings {
                override val regionProfile = "en"
            },
        )
        assertTrue(buildProfile.availableModels.containsAll(models))
    }
}
