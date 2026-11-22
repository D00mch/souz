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
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import ru.souz.db.SettingsProvider
import ru.souz.db.SettingsProviderImpl.Companion.REGION_EN
import ru.souz.db.SettingsProviderImpl.Companion.REGION_RU
import ru.souz.llms.LLMModel
import ru.souz.llms.LocalModelAvailability
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
    fun `setup offers every chat provider in either service language without saving a model`() = runTest(dispatcher) {
        val chatKeys = setOf(
            ApiKeyField.GIGA_CHAT, ApiKeyField.QWEN_CHAT, ApiKeyField.AI_TUNNEL,
            ApiKeyField.ANTHROPIC, ApiKeyField.OPENAI, ApiKeyField.CODEX,
        )
        for (language in listOf(REGION_RU, REGION_EN)) {
            val settings = settingsProviderStub()
            settings.regionProfile = language
            val viewModel = createViewModel(settings)
            advanceUntilIdle()

            assertEquals(chatKeys, viewModel.uiState.value.availableApiKeyFields - ApiKeyField.POLZA)
            viewModel.send(SetupEvent.InputOpenAiKey("openai-key"))
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.canProceed)
            assertEquals("openai-key", settings.openaiKey)
            assertEquals(language, settings.regionProfile)
            verify(exactly = 0) { settings.gigaModel = any() }
        }
    }

    private fun createViewModel(settingsProvider: SettingsProvider): SetupViewModel {
        val speechPlayer = mockk<UiSpeechPlayer>(relaxed = true)
        val localModels = mockk<LocalModelAvailability>(relaxed = true)
        val apiKeyAvailabilityUseCase = ApiKeyAvailabilityUseCase(localModels, settingsProvider)
        val di = DI {
            bindSingleton<SettingsProvider> { settingsProvider }
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
