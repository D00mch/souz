package ru.souz.ui.settings

import io.mockk.every
import io.mockk.mockk
import ru.souz.db.SettingsProvider
import ru.souz.db.SettingsProviderImpl.Companion.REGION_EN
import ru.souz.llms.LlmBuildProfile
import ru.souz.llms.VoiceRecognitionModel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelAvailabilityTest {
    private val originalOsName = System.getProperty("os.name")
    private val originalOsArch = System.getProperty("os.arch")

    @AfterTest
    fun tearDown() {
        if (originalOsName == null) {
            System.clearProperty("os.name")
        } else {
            System.setProperty("os.name", originalOsName)
        }
        if (originalOsArch == null) {
            System.clearProperty("os.arch")
        } else {
            System.setProperty("os.arch", originalOsArch)
        }
    }

    @Test
    fun `available voice recognition models include local macos model without keys on macos`() {
        System.setProperty("os.name", "Mac OS X")
        System.setProperty("os.arch", "aarch64")

        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.regionProfile } returns REGION_EN
        every { settingsProvider.saluteSpeechKey } returns ""
        every { settingsProvider.aiTunnelKey } returns ""
        every { settingsProvider.openaiKey } returns ""

        val llmBuildProfile = LlmBuildProfile(settingsProvider)

        val models = settingsProvider.availableVoiceRecognitionModels(llmBuildProfile)

        assertTrue(VoiceRecognitionModel.LocalMacOsStt in models)
    }

    @Test
    fun `default voice recognition model does not prefer local macos model when cloud model is available`() {
        System.setProperty("os.name", "Mac OS X")
        System.setProperty("os.arch", "aarch64")

        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.regionProfile } returns REGION_EN
        every { settingsProvider.saluteSpeechKey } returns ""
        every { settingsProvider.aiTunnelKey } returns ""
        every { settingsProvider.openaiKey } returns "openai-key"

        val llmBuildProfile = LlmBuildProfile(settingsProvider)

        val defaultModel = settingsProvider.defaultVoiceRecognitionModel(llmBuildProfile)

        assertEquals(VoiceRecognitionModel.OpenAIGpt4oTranscribe, defaultModel)
    }

    @Test
    fun `available voice recognition models exclude local macos model on unsupported mac arch`() {
        System.setProperty("os.name", "Mac OS X")
        System.setProperty("os.arch", "sparc")

        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.regionProfile } returns REGION_EN
        every { settingsProvider.saluteSpeechKey } returns ""
        every { settingsProvider.aiTunnelKey } returns ""
        every { settingsProvider.openaiKey } returns ""

        val llmBuildProfile = LlmBuildProfile(settingsProvider)

        val models = settingsProvider.availableVoiceRecognitionModels(llmBuildProfile)

        assertFalse(VoiceRecognitionModel.LocalMacOsStt in models)
    }
}
