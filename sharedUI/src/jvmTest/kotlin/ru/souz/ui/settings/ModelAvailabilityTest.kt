package ru.souz.ui.settings

import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import ru.souz.db.SettingsProvider
import ru.souz.db.SettingsProviderImpl.Companion.REGION_EN
import ru.souz.llms.LlmBuildProfile
import ru.souz.llms.VoiceRecognitionModel
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
}
