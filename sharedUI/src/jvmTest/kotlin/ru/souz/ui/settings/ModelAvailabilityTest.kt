package ru.souz.ui.settings

import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import ru.souz.db.SettingsProvider
import ru.souz.db.SettingsProviderImpl.Companion.REGION_EN
import ru.souz.db.SettingsProviderImpl.Companion.REGION_RU
import ru.souz.llms.LLMModel
import ru.souz.llms.LocalModelAvailability
import ru.souz.llms.LlmProvider
import ru.souz.llms.VoiceRecognitionModel
import kotlin.test.AfterTest
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelAvailabilityTest {

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `default voice recognition model skips codex and uses openai for en profile`() {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.regionProfile } returns REGION_EN
        every { settingsProvider.hasKey(ru.souz.llms.VoiceRecognitionProvider.OPENAI) } returns true

        assertEquals(
            VoiceRecognitionModel.OpenAIGpt4oTranscribe,
            defaultVoiceRecognitionModel(settingsProvider.availableVoiceRecognitionModels()),
        )
    }

    @Test
    fun `available voice recognition models include all openai transcription models`() {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.regionProfile } returns REGION_EN
        every { settingsProvider.hasKey(ru.souz.llms.VoiceRecognitionProvider.OPENAI) } returns true

        assertEquals(
            listOf(
                VoiceRecognitionModel.OpenAIGpt4oTranscribe,
                VoiceRecognitionModel.OpenAIGpt4oMiniTranscribe,
                VoiceRecognitionModel.OpenAIGptTranscribe,
                VoiceRecognitionModel.OpenAIWhisper1,
            ),
            settingsProvider.availableVoiceRecognitionModels(),
        )
    }

    @Test
    fun `available voice recognition models include local macos without api key on supported host`() {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.regionProfile } returns REGION_EN

        assertEquals(
            listOf(VoiceRecognitionModel.LocalMacOsStt),
            settingsProvider.availableVoiceRecognitionModels(
                localMacOsSpeechAvailable = true,
            ),
        )
    }

    @Test
    fun `available voice recognition models hide local macos on unsupported host`() {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.regionProfile } returns REGION_EN

        assertEquals(emptyList(), settingsProvider.availableVoiceRecognitionModels())
    }

    @Test
    fun `available llm models include custom OpenAI-compatible model only when configured`() {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.regionProfile } returns REGION_EN
        every { settingsProvider.hasKey(LlmProvider.OPENAI) } returns true
        val localModels = mockk<LocalModelAvailability>(relaxed = true)
        every { settingsProvider.openaiModel } returns ""
        assertFalse(LLMModel.OpenAICompatibleCustom in settingsProvider.availableLlmModels(localModels))

        every { settingsProvider.openaiModel } returns "provider-chat-model"
        assertTrue(LLMModel.OpenAICompatibleCustom in settingsProvider.availableLlmModels(localModels))
    }

    @Test
    fun `chat model choices depend on credentials and local support across service languages`() {
        val settings = mockk<SettingsProvider>(relaxed = true)
        val local = mockk<LocalModelAvailability>()
        every { local.availableGigaModels() } returns listOf(LLMModel.LocalQwen3_4B_Instruct_2507)
        val configuredProviders = setOf(LlmProvider.GIGA, LlmProvider.OPENAI, LlmProvider.LOCAL)
        every { settings.hasKey(any<LlmProvider>()) } answers { firstArg<LlmProvider>() in configuredProviders }

        every { settings.regionProfile } returns REGION_RU
        val models = settings.availableLlmModels(local)
        assertTrue(LLMModel.Pro in models)
        assertTrue(LLMModel.OpenAIGpt5Mini in models)
        assertTrue(LLMModel.LocalQwen3_4B_Instruct_2507 in models)
        assertFalse(LLMModel.LocalQwen35_9B in models)
        assertFalse(LLMModel.AnthropicHaiku45 in models)
        every { settings.regionProfile } returns REGION_EN
        assertEquals(models, settings.availableLlmModels(local))
    }
}
