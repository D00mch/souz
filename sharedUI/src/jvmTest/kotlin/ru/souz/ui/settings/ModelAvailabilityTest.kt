package ru.souz.ui.settings

import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import ru.souz.db.SettingsProvider
import ru.souz.db.SettingsProviderImpl.Companion.REGION_EN
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmBuildProfile
import ru.souz.llms.LlmProvider
import ru.souz.llms.VoiceRecognitionModel
import ru.souz.llms.local.LocalProviderAvailability
import ru.souz.service.speech.LocalMacOsSpeechHost
import kotlin.test.AfterTest
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
        every { settingsProvider.openaiKey } returns "openai-key"

        val llmBuildProfile = LlmBuildProfile(settingsProvider)

        assertEquals(
            VoiceRecognitionModel.OpenAIGpt4oTranscribe,
            settingsProvider.defaultVoiceRecognitionModel(llmBuildProfile),
        )
    }

    @Test
    fun `available voice recognition models include local macos without api key on supported host`() {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.regionProfile } returns REGION_EN

        val llmBuildProfile = LlmBuildProfile(settingsProvider)

        assertEquals(
            listOf(VoiceRecognitionModel.LocalMacOsStt),
            settingsProvider.availableVoiceRecognitionModels(
                llmBuildProfile = llmBuildProfile,
                localMacOsSpeechAvailable = true,
            ),
        )
    }

    @Test
    fun `available voice recognition models hide local macos on unsupported host`() {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.regionProfile } returns REGION_EN

        val llmBuildProfile = LlmBuildProfile(settingsProvider)

        assertEquals(emptyList(), settingsProvider.availableVoiceRecognitionModels(llmBuildProfile))
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

        val llmBuildProfile = LlmBuildProfile(settingsProvider, localProviderAvailability)
        val models = settingsProvider.availableAmbientAnalysisModels(llmBuildProfile)

        assertEquals(
            listOf(
                LLMModel.LocalQwen3_4B_Instruct_2507,
                LLMModel.LocalGemma4_E2B_It,
            ),
            models,
        )
        assertEquals(listOf(LlmProvider.LOCAL, LlmProvider.LOCAL), models.map { it.provider })
    }
}
