package ru.souz.ui.settings

import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import ru.souz.db.DesktopModelCatalog
import ru.souz.db.SettingsProvider
import ru.souz.db.SettingsProviderImpl.Companion.REGION_EN
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider
import ru.souz.llms.VoiceRecognitionModel
import ru.souz.llms.local.LocalProviderAvailability
import ru.souz.service.speech.LocalMacOsSpeechHost
import kotlin.test.AfterTest
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelAvailabilityTest {

    @Test
    fun `merged chat catalog follows credentials and local capabilities in either region`() {
        for (region in listOf("ru", "en")) {
            val settings = mockk<SettingsProvider>(relaxed = true)
            every { settings.regionProfile } returns region
            every { settings.hasKey(any<LlmProvider>()) } answers {
                when (firstArg<LlmProvider>()) {
                    LlmProvider.GIGA, LlmProvider.OPENAI, LlmProvider.LOCAL -> true
                    else -> false
                }
            }
            val profile = DesktopModelCatalog()
            val models = settings.availableLlmModels(profile)
            assertTrue(LLMModel.Max in models)
            assertTrue(LLMModel.OpenAIGpt5Mini in models)
            assertEquals(LLMModel.OpenAIGpt5Mini, settings.defaultLlmModel(profile))
            assertFalse(models.any {
                it.provider == LlmProvider.LOCAL || it.provider == LlmProvider.ANTHROPIC || it.provider == LlmProvider.CODEX
            })
            every { settings.hasKey(any<LlmProvider>()) } returns false
            assertEquals(emptyList(), settings.availableLlmModels(profile))
            assertEquals(null, settings.defaultLlmModel(profile))
        }
    }

    @Test
    fun `speech models retain regional provider gating`() {
        for ((region, excluded) in listOf("ru" to ru.souz.llms.VoiceRecognitionProvider.OPENAI,
            "en" to ru.souz.llms.VoiceRecognitionProvider.AI_TUNNEL)) {
            val settings = mockk<SettingsProvider>(relaxed = true)
            every { settings.regionProfile } returns region
            every { settings.hasKey(any<ru.souz.llms.VoiceRecognitionProvider>()) } returns true
            val models = settings.availableVoiceRecognitionModels()
            assertFalse(models.any { it.provider == excluded })
        }
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `default voice recognition model uses openai for en profile`() {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.regionProfile } returns REGION_EN
        every { settingsProvider.hasKey(ru.souz.llms.VoiceRecognitionProvider.OPENAI) } returns true

        assertEquals(
            VoiceRecognitionModel.OpenAIGpt4oTranscribe,
            settingsProvider.defaultVoiceRecognitionModel(),
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
    fun `available ambient analysis models are always local only`() {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.regionProfile } returns REGION_EN
        every { settingsProvider.openaiKey } returns "openai-key"
        every { settingsProvider.qwenChatKey } returns "qwen-key"
        val localProviderAvailability = mockk<LocalProviderAvailability>(relaxed = true)
        every { localProviderAvailability.isProviderAvailable() } returns true
        every { localProviderAvailability.availableGigaModels() } returns listOf(
            LLMModel.LocalQwen3_4B_Instruct_2507,
            LLMModel.LocalGemma4_E2B_It,
        )
        every { localProviderAvailability.defaultGigaModel() } returns LLMModel.LocalQwen3_4B_Instruct_2507

        val modelCatalog = DesktopModelCatalog(localProviderAvailability)
        val models = settingsProvider.availableAmbientAnalysisModels(modelCatalog)

        assertEquals(
            listOf(
                LLMModel.LocalQwen3_4B_Instruct_2507,
                LLMModel.LocalGemma4_E2B_It,
            ),
            models,
        )
        assertEquals(listOf(LlmProvider.LOCAL, LlmProvider.LOCAL), models.map { it.provider })
    }

    @Test
    fun `available llm models include custom OpenAI-compatible model only when configured`() {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.regionProfile } returns REGION_EN
        every { settingsProvider.hasKey(LlmProvider.OPENAI) } returns true
        val modelCatalog = DesktopModelCatalog()

        every { settingsProvider.openaiModel } returns ""
        assertFalse(LLMModel.OpenAICompatibleCustom in settingsProvider.availableLlmModels(modelCatalog))
        assertEquals(LLMModel.OpenAIGpt5Mini, settingsProvider.defaultLlmModel(modelCatalog))

        every { settingsProvider.openaiModel } returns "provider-chat-model"
        assertTrue(LLMModel.OpenAICompatibleCustom in settingsProvider.availableLlmModels(modelCatalog))
        assertEquals(LLMModel.OpenAICompatibleCustom, settingsProvider.defaultLlmModel(modelCatalog))
    }
}
