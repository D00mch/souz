@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ru.souz.ui.setup

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import ru.souz.db.SettingsProvider
import ru.souz.db.SettingsProviderImpl.Companion.REGION_EN
import ru.souz.db.SettingsProviderImpl.Companion.REGION_RU
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmBuildProfile
import ru.souz.llms.LlmProvider
import ru.souz.ui.common.usecases.ApiKeyAvailabilityUseCase
import ru.souz.ui.common.ApiKeyField
import ru.souz.ui.common.ApiKeyProvider
import ru.souz.ui.host.UiSpeechPlayer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SetupViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `setup offers Polza separately from Sber and saves its speech key`() = runTest(dispatcher) {
        val settings = settingsProviderStub()
        val viewModel = createViewModel(settings)
        advanceUntilIdle()
        assertTrue(ApiKeyField.POLZA in viewModel.uiState.value.availableApiKeyFields)
        assertTrue(ApiKeyProvider.POLZA in viewModel.uiState.value.availableApiKeyProviders)

        viewModel.send(SetupEvent.InputPolzaApiKey("pza_fixture"))
        advanceUntilIdle()

        assertEquals("pza_fixture", settings.polzaApiKey)
        assertEquals("pza_fixture", viewModel.uiState.value.polzaApiKey)
        assertEquals(1, viewModel.uiState.value.configuredKeysCount)
    }

    @Test
    fun `setup enables proceed when at least one key exists`() = runTest(dispatcher) {
        val settingsProvider = settingsProviderStub(
            qwen = "qwen-token",
        )
        val viewModel = createViewModel(settingsProvider)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.canProceed)
        assertEquals(1, state.configuredKeysCount)
        verify(exactly = 0) { settingsProvider.needsOnboarding = true }
    }

    @Test
    fun `proceed marks onboarding as needed for first-time setup`() = runTest(dispatcher) {
        val settingsProvider = settingsProviderStub(
            qwen = "qwen-token",
        )
        val viewModel = createViewModel(settingsProvider)

        advanceUntilIdle()
        viewModel.send(SetupEvent.Proceed)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showVoiceReminderDialog)
        verify(exactly = 0) { settingsProvider.needsOnboarding = true }

        viewModel.send(SetupEvent.DismissVoiceReminderDialog)
        advanceUntilIdle()
        viewModel.send(SetupEvent.Proceed)
        advanceUntilIdle()

        verify { settingsProvider.needsOnboarding = true }
    }

    @Test
    fun `setup stays when there are no keys`() = runTest(dispatcher) {
        val settingsProvider = settingsProviderStub()
        val viewModel = createViewModel(settingsProvider)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.canProceed)
        assertEquals(0, state.configuredKeysCount)
        verify(exactly = 0) { settingsProvider.needsOnboarding = true }
    }

    @Test
    fun `proceed does not re-enable onboarding when already completed`() = runTest(dispatcher) {
        val settingsProvider = settingsProviderStub(
            giga = "giga-token",
            onboardingCompleted = true,
        )
        val viewModel = createViewModel(settingsProvider)

        advanceUntilIdle()
        viewModel.send(SetupEvent.Proceed)
        advanceUntilIdle()

        verify(exactly = 0) { settingsProvider.needsOnboarding = true }
    }

    @ParameterizedTest(name = "first configured key: {0}")
    @EnumSource(LlmProvider::class, names = ["QWEN", "AI_TUNNEL", "ANTHROPIC", "OPENAI"])
    fun `setup picks the provider default model for the first key`(provider: LlmProvider) = runTest(dispatcher) {
        val (event, defaultModel) = when (provider) {
            LlmProvider.QWEN -> SetupEvent.InputQwenChatKey("token") to LLMModel.QwenMax
            LlmProvider.AI_TUNNEL -> SetupEvent.InputAiTunnelKey("token") to LLMModel.AiTunnelClaudeHaiku
            LlmProvider.ANTHROPIC -> SetupEvent.InputAnthropicKey("token") to LLMModel.AnthropicHaiku45
            LlmProvider.OPENAI -> SetupEvent.InputOpenAiKey("token") to LLMModel.OpenAIGpt5Mini
            else -> error("Unexpected provider: $provider")
        }
        val settingsProvider = settingsProviderStub()
        val viewModel = createViewModel(settingsProvider)

        advanceUntilIdle()
        viewModel.send(event)
        advanceUntilIdle()

        assertEquals(defaultModel, settingsProvider.gigaModel)
    }

    @Test
    fun `setup prefers giga model when giga key appears during first setup`() = runTest(dispatcher) {
        val settingsProvider = settingsProviderStub()
        val viewModel = createViewModel(settingsProvider)

        advanceUntilIdle()
        viewModel.send(SetupEvent.InputQwenChatKey("qwen-token"))
        advanceUntilIdle()
        assertEquals(LLMModel.QwenMax, settingsProvider.gigaModel)

        viewModel.send(SetupEvent.InputGigaChatKey("giga-token"))
        advanceUntilIdle()
        assertEquals(LLMModel.Max, settingsProvider.gigaModel)
    }

    @Test
    fun `setup does not auto-change model when setup starts with existing keys`() = runTest(dispatcher) {
        val settingsProvider = settingsProviderStub(
            giga = "giga-token",
            gigaModel = LLMModel.Pro,
        )
        val viewModel = createViewModel(settingsProvider)

        advanceUntilIdle()
        viewModel.send(SetupEvent.InputAiTunnelKey("ait-token"))
        advanceUntilIdle()

        assertEquals(LLMModel.Pro, settingsProvider.gigaModel)
    }

    @Test
    fun `setup offers both regional provider sets without a region choice`() = runTest(dispatcher) {
        for (region in listOf(REGION_RU, REGION_EN)) {
            val settings = settingsProviderStub()
            settings.regionProfile = region
            val viewModel = createViewModel(settings)
            advanceUntilIdle()

            assertTrue(setOf(ApiKeyField.GIGA_CHAT, ApiKeyField.AI_TUNNEL,
                ApiKeyField.OPENAI, ApiKeyField.ANTHROPIC, ApiKeyField.CODEX, ApiKeyField.QWEN_CHAT)
                .all { it in viewModel.uiState.value.availableApiKeyFields })
            assertFalse(viewModel.uiState.value.canProceed)
            viewModel.send(SetupEvent.InputOpenAiKey("synthetic-key"))
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.canProceed)
            assertEquals(LLMModel.OpenAIGpt5Mini, settings.gigaModel)
            assertEquals(region, settings.regionProfile)
        }
    }

    private fun createViewModel(settingsProvider: SettingsProvider): SetupViewModel {
        val speechPlayer = mockk<UiSpeechPlayer>(relaxed = true)
        val llmBuildProfile = LlmBuildProfile.desktop(settingsProvider)
        val apiKeyAvailabilityUseCase = ApiKeyAvailabilityUseCase(llmBuildProfile)
        val di = DI {
            bindSingleton<SettingsProvider> { settingsProvider }
            bindSingleton<LlmBuildProfile> { llmBuildProfile }
            bindSingleton<ApiKeyAvailabilityUseCase> { apiKeyAvailabilityUseCase }
            bindSingleton<UiSpeechPlayer> { speechPlayer }
        }
        return SetupViewModel(di)
    }

    private fun settingsProviderStub(
        giga: String = "",
        qwen: String = "",
        aiTunnel: String = "",
        anthropic: String = "",
        openai: String = "",
        speech: String = "",
        onboardingCompleted: Boolean = false,
        gigaModel: LLMModel = LLMModel.Max,
    ): SettingsProvider {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)

        var gigaValue = giga
        var qwenValue = qwen
        var aiTunnelValue = aiTunnel
        var anthropicValue = anthropic
        var openAiValue = openai
        var speechValue = speech
        var gigaModelValue = gigaModel
        var onboardingCompletedValue = onboardingCompleted
        var needsOnboardingValue = false
        var appLanguageValue = "ru"

        every { settingsProvider.gigaChatKey } answers { gigaValue }
        every { settingsProvider.gigaChatKey = any() } answers { gigaValue = firstArg() }

        every { settingsProvider.qwenChatKey } answers { qwenValue }
        every { settingsProvider.qwenChatKey = any() } answers { qwenValue = firstArg() }

        every { settingsProvider.aiTunnelKey } answers { aiTunnelValue }
        every { settingsProvider.aiTunnelKey = any() } answers { aiTunnelValue = firstArg() }

        every { settingsProvider.anthropicKey } answers { anthropicValue }
        every { settingsProvider.anthropicKey = any() } answers { anthropicValue = firstArg() }

        every { settingsProvider.openaiKey } answers { openAiValue }
        every { settingsProvider.openaiKey = any() } answers { openAiValue = firstArg() }

        every { settingsProvider.polzaApiKey } answers { speechValue }
        every { settingsProvider.polzaApiKey = any() } answers { speechValue = firstArg() }

        every { settingsProvider.gigaModel } answers { gigaModelValue }
        every { settingsProvider.gigaModel = any() } answers { gigaModelValue = firstArg() }

        every { settingsProvider.onboardingCompleted } answers { onboardingCompletedValue }
        every { settingsProvider.onboardingCompleted = any() } answers { onboardingCompletedValue = firstArg() }

        every { settingsProvider.needsOnboarding } answers { needsOnboardingValue }
        every { settingsProvider.needsOnboarding = any() } answers { needsOnboardingValue = firstArg() }
        every { settingsProvider.regionProfile } answers { appLanguageValue }
        every { settingsProvider.regionProfile = any() } answers { appLanguageValue = firstArg() }

        return settingsProvider
    }
}
