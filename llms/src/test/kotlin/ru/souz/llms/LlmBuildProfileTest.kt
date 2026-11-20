package ru.souz.llms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlmBuildProfileTest {
    private class Settings(override var regionProfile: String) : LlmBuildProfileSettings

    @Test
    fun `desktop has the same complete catalog for both saved regions`() {
        val settings = Settings("ru")
        val profile = LlmBuildProfile.desktop(settings)
        val expected = LLMModel.entries.filter { it.provider != LlmProvider.LOCAL && it != LLMModel.OpenAICompatibleCustom }
        assertEquals(expected, profile.availableModels)
        assertEquals(LlmProvider.entries.filter { it != LlmProvider.LOCAL }.toSet(), profile.availableProviders)
        settings.regionProfile = "en"
        assertEquals(expected, profile.availableModels)
        for (model in expected) {
            assertEquals(model, profile.normalizeModel(model))
            assertEquals(model, profile.normalizeConfiguredModel(model, null) { error("Unexpected fallback: $model") })
        }
    }

    @Test
    fun `desktop keeps legacy default priorities but considers credentials from both regions before local`() {
        for ((region, legacyDefault) in listOf("ru" to LLMModel.Max, "en" to LLMModel.CodexGpt54)) {
            val settings = Settings(region)
            val profile = LlmBuildProfile.desktop(settings, localModels)
            assertEquals(legacyDefault, profile.defaultModel)
            val legacy = LlmBuildProfile.providerPrioritiesForLanguage(region).filter { it != LlmProvider.LOCAL }
            assertEquals(legacy, profile.providerPriorities().take(legacy.size))
            assertEquals(LlmProvider.LOCAL, profile.providerPriorities().last())
            for (provider in LlmProvider.entries.filter { it != LlmProvider.LOCAL }) {
                assertEquals(profile.defaultModelForProvider(provider), profile.defaultConfiguredModel { it == provider })
            }
            assertEquals(LLMModel.LocalQwen3_4B_Instruct_2507, profile.defaultConfiguredModel { false })
        }
    }

    @Test
    fun `desktop excludes unsupported local models and unconfigured custom models`() {
        val profile = LlmBuildProfile.desktop(Settings("ru"), localModels)
        assertTrue(LLMModel.LocalQwen3_4B_Instruct_2507 in profile.availableModels)
        assertFalse(LLMModel.LocalGemma4_E2B_It in profile.availableModels)
        assertEquals(LLMModel.QwenMax, profile.normalizeConfiguredModel(LLMModel.LocalGemma4_E2B_It, null) { LLMModel.QwenMax })
        assertEquals(LLMModel.QwenMax, profile.normalizeConfiguredModel(LLMModel.OpenAICompatibleCustom, " ") { LLMModel.QwenMax })
        assertEquals(LLMModel.OpenAICompatibleCustom,
            profile.normalizeConfiguredModel(LLMModel.OpenAICompatibleCustom, "fixture-model") { error("Unexpected fallback") })
    }

    @Test
    fun `regional callers retain their catalogs defaults and speech policy`() {
        for (region in listOf("ru", "en")) {
            val settings = Settings(region)
            val profile = LlmBuildProfile(settings)
            val defaults = LlmBuildProfile.defaultsForLanguage(region)
            assertEquals(defaults.keys - LlmProvider.LOCAL, profile.availableProviders)
            assertEquals(defaults.values.first(), profile.defaultModel)
            assertEquals(LlmBuildProfile.providerPrioritiesForLanguage(region), profile.providerPriorities())
            assertEquals(region == "ru", LlmBuildProfile.desktop(settings).supportsPolzaSpeechRecognition)
        }
    }

    private val localModels = object : LocalModelAvailability {
        override fun availableGigaModels() = listOf(LLMModel.LocalQwen3_4B_Instruct_2507)
        override fun defaultGigaModel() = LLMModel.LocalQwen3_4B_Instruct_2507
        override fun isProviderAvailable() = true
    }
}
