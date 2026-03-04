package ru.souz.ui.main.usecases

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import ru.souz.db.SettingsProvider
import ru.souz.giga.VoiceRecognitionModel
import ru.souz.llms.AiTunnelVoiceAPI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ModelAwareSpeechRecognitionProviderTest {

    @Test
    fun `ai tunnel speech provider is disabled outside ru edition`() {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.aiTunnelKey } returns "ait-key"

        val provider = AiTunnelSpeechRecognitionProvider(
            aiTunnelVoiceAPI = mockk<AiTunnelVoiceAPI>(),
            settingsProvider = settingsProvider,
            isRuBuildProvider = { false },
        )

        assertFalse(provider.enabled)
        assertFalse(provider.hasRequiredKey)
    }

    @Test
    fun `ai tunnel model prefers ai tunnel speech provider`() = runTest {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.voiceRecognitionModel } returns VoiceRecognitionModel.AiTunnelGpt4oTranscribe

        val saluteProvider = mockk<SaluteSpeechRecognitionProvider>()
        every { saluteProvider.enabled } returns true
        every { saluteProvider.hasRequiredKey } returns true

        val openAiProvider = mockk<OpenAISpeechRecognitionProvider>()
        every { openAiProvider.enabled } returns true
        every { openAiProvider.hasRequiredKey } returns true

        val aiTunnelProvider = mockk<AiTunnelSpeechRecognitionProvider>()
        every { aiTunnelProvider.enabled } returns true
        every { aiTunnelProvider.hasRequiredKey } returns true

        coEvery { aiTunnelProvider.recognize(any()) } returns " from-ai-tunnel "

        val provider = ModelAwareSpeechRecognitionProvider(
            settingsProvider = settingsProvider,
            saluteSpeechProvider = saluteProvider,
            openAiSpeechProvider = openAiProvider,
            aiTunnelSpeechProvider = aiTunnelProvider,
        )

        val recognized = provider.recognize(byteArrayOf(1, 2, 3))

        assertEquals(" from-ai-tunnel ", recognized)
        coVerify(exactly = 1) { aiTunnelProvider.recognize(any()) }
        coVerify(exactly = 0) { openAiProvider.recognize(any()) }
        coVerify(exactly = 0) { saluteProvider.recognize(any()) }
    }

    @Test
    fun `disabled ai tunnel provider falls back to openai provider`() = runTest {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.voiceRecognitionModel } returns VoiceRecognitionModel.AiTunnelGpt4oTranscribe

        val saluteProvider = mockk<SaluteSpeechRecognitionProvider>()
        every { saluteProvider.enabled } returns true
        every { saluteProvider.hasRequiredKey } returns false

        val openAiProvider = mockk<OpenAISpeechRecognitionProvider>()
        every { openAiProvider.enabled } returns true
        every { openAiProvider.hasRequiredKey } returns true
        coEvery { openAiProvider.recognize(any()) } returns "openai"

        val aiTunnelProvider = mockk<AiTunnelSpeechRecognitionProvider>()
        every { aiTunnelProvider.enabled } returns false
        every { aiTunnelProvider.hasRequiredKey } returns true

        val provider = ModelAwareSpeechRecognitionProvider(
            settingsProvider = settingsProvider,
            saluteSpeechProvider = saluteProvider,
            openAiSpeechProvider = openAiProvider,
            aiTunnelSpeechProvider = aiTunnelProvider,
        )

        val recognized = provider.recognize(byteArrayOf(4, 5, 6))

        assertEquals("openai", recognized)
        coVerify(exactly = 1) { openAiProvider.recognize(any()) }
        coVerify(exactly = 0) { aiTunnelProvider.recognize(any()) }
    }
}
