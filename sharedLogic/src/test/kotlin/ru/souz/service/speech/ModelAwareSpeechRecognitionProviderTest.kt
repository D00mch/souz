package ru.souz.service.speech

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import ru.souz.db.SettingsProvider
import ru.souz.db.SettingsProviderImpl.Companion.REGION_EN
import ru.souz.db.SettingsProviderImpl.Companion.REGION_RU
import ru.souz.llms.polza.PolzaVoiceAPI
import ru.souz.llms.VoiceRecognitionModel
import ru.souz.llms.tunnel.AiTunnelVoiceAPI
import ru.souz.llms.openai.OpenAIVoiceAPI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class ModelAwareSpeechRecognitionProviderTest {

    @Test
    fun `Polza model recognizes using the direct provider and never calls another cloud`() = runTest {
        val settings = mockk<SettingsProvider> {
            every { regionProfile } returns REGION_RU
            every { polzaApiKey } returns "pza_fixture"
            every { voiceRecognitionModel } returns VoiceRecognitionModel.PolzaGigaAm
        }
        val audio = byteArrayOf(1, 2, 3, 4)
        val api = mockk<PolzaVoiceAPI> {
            coEvery { recognize(audio) } returns "Проверка голоса"
        }
        val polza = PolzaSpeechRecognitionProvider(api, settings)
        val openAi = mockk<OpenAISpeechRecognitionProvider> {
            every { enabled } returns true
            every { hasRequiredKey } returns true
        }
        val tunnel = mockk<AiTunnelSpeechRecognitionProvider> {
            every { enabled } returns true
            every { hasRequiredKey } returns true
        }
        val provider = ModelAwareSpeechRecognitionProvider(settings, polza, openAi, tunnel, mockk())

        assertEquals("Проверка голоса", provider.recognize(audio))
        coVerify(exactly = 1) { api.recognize(audio) }
        coVerify(exactly = 0) { openAi.recognize(any()) }
        coVerify(exactly = 0) { tunnel.recognize(any()) }
    }

    @Test
    fun `openai speech provider is disabled outside en profile`() {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.regionProfile } returns REGION_RU
        every { settingsProvider.openaiKey } returns "openai-key"

        val provider = OpenAISpeechRecognitionProvider(
            openAIVoiceAPI = mockk<OpenAIVoiceAPI>(),
            settingsProvider = settingsProvider,
        )

        assertFalse(provider.enabled)
        assertFalse(provider.hasRequiredKey)
    }

    @Test
    fun `polza speech provider is disabled outside ru profile`() {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.regionProfile } returns REGION_EN
        every { settingsProvider.polzaApiKey } returns "polza-key"

        val provider = PolzaSpeechRecognitionProvider(
            polzaVoiceAPI = mockk<PolzaVoiceAPI>(),
            settingsProvider = settingsProvider,
        )

        assertFalse(provider.enabled)
        assertFalse(provider.hasRequiredKey)
    }

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

        val polzaProvider = mockk<PolzaSpeechRecognitionProvider>()
        every { polzaProvider.enabled } returns true
        every { polzaProvider.hasRequiredKey } returns true

        val openAiProvider = mockk<OpenAISpeechRecognitionProvider>()
        every { openAiProvider.enabled } returns true
        every { openAiProvider.hasRequiredKey } returns true

        val aiTunnelProvider = mockk<AiTunnelSpeechRecognitionProvider>()
        every { aiTunnelProvider.enabled } returns true
        every { aiTunnelProvider.hasRequiredKey } returns true

        coEvery { aiTunnelProvider.recognize(any()) } returns " from-ai-tunnel "

        val provider = ModelAwareSpeechRecognitionProvider(
            settingsProvider = settingsProvider,
            polzaSpeechProvider = polzaProvider,
            openAiSpeechProvider = openAiProvider,
            aiTunnelSpeechProvider = aiTunnelProvider,
            macOsSpeechProvider = mockk(),
        )

        val recognized = provider.recognize(byteArrayOf(1, 2, 3))

        assertEquals(" from-ai-tunnel ", recognized)
        coVerify(exactly = 1) { aiTunnelProvider.recognize(any()) }
        coVerify(exactly = 0) { openAiProvider.recognize(any()) }
        coVerify(exactly = 0) { polzaProvider.recognize(any()) }
    }

    @Test
    fun `disabled ai tunnel provider falls back to openai provider`() = runTest {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.voiceRecognitionModel } returns VoiceRecognitionModel.AiTunnelGpt4oTranscribe

        val polzaProvider = mockk<PolzaSpeechRecognitionProvider>()
        every { polzaProvider.enabled } returns true
        every { polzaProvider.hasRequiredKey } returns false

        val openAiProvider = mockk<OpenAISpeechRecognitionProvider>()
        every { openAiProvider.enabled } returns true
        every { openAiProvider.hasRequiredKey } returns true
        coEvery { openAiProvider.recognize(any()) } returns "openai"

        val aiTunnelProvider = mockk<AiTunnelSpeechRecognitionProvider>()
        every { aiTunnelProvider.enabled } returns false
        every { aiTunnelProvider.hasRequiredKey } returns true

        val provider = ModelAwareSpeechRecognitionProvider(
            settingsProvider = settingsProvider,
            polzaSpeechProvider = polzaProvider,
            openAiSpeechProvider = openAiProvider,
            aiTunnelSpeechProvider = aiTunnelProvider,
            macOsSpeechProvider = mockk(),
        )

        val recognized = provider.recognize(byteArrayOf(4, 5, 6))

        assertEquals("openai", recognized)
        coVerify(exactly = 1) { openAiProvider.recognize(any()) }
        coVerify(exactly = 0) { aiTunnelProvider.recognize(any()) }
    }

    @Test
    fun `local macos model uses only local provider without cloud fallback`() = runTest {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.voiceRecognitionModel } returns VoiceRecognitionModel.LocalMacOsStt

        val polzaProvider = mockk<PolzaSpeechRecognitionProvider>()
        every { polzaProvider.enabled } returns true
        every { polzaProvider.hasRequiredKey } returns true

        val openAiProvider = mockk<OpenAISpeechRecognitionProvider>()
        every { openAiProvider.enabled } returns true
        every { openAiProvider.hasRequiredKey } returns true

        val aiTunnelProvider = mockk<AiTunnelSpeechRecognitionProvider>()
        every { aiTunnelProvider.enabled } returns true
        every { aiTunnelProvider.hasRequiredKey } returns true

        val macOsProvider = mockk<MacOsSpeechRecognitionProvider>()
        every { macOsProvider.enabled } returns true
        every { macOsProvider.hasRequiredKey } returns true
        coEvery { macOsProvider.recognize(any()) } throws LocalMacOsSpeechUnavailableException("Local macOS unavailable")

        val provider = ModelAwareSpeechRecognitionProvider(
            settingsProvider = settingsProvider,
            polzaSpeechProvider = polzaProvider,
            openAiSpeechProvider = openAiProvider,
            aiTunnelSpeechProvider = aiTunnelProvider,
            macOsSpeechProvider = macOsProvider,
        )

        assertFailsWith<LocalMacOsSpeechUnavailableException> {
            provider.recognize(byteArrayOf(7, 8, 9))
        }

        coVerify(exactly = 1) { macOsProvider.recognize(any()) }
        coVerify(exactly = 0) { openAiProvider.recognize(any()) }
        coVerify(exactly = 0) { aiTunnelProvider.recognize(any()) }
        coVerify(exactly = 0) { polzaProvider.recognize(any()) }
    }
}
