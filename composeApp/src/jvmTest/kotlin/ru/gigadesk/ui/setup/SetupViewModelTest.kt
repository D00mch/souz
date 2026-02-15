@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ru.gigadesk.ui.setup

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
import ru.gigadesk.audio.Say
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.giga.GigaModel
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
    fun `setup auto-proceeds when at least one key exists`() = runTest(dispatcher) {
        val settingsProvider = settingsProviderStub(
            giga = "",
            qwen = "qwen-token",
            aiTunnel = "",
            speech = "",
            onboardingCompleted = false,
        )
        val viewModel = createViewModel(settingsProvider)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.canProceed)
        assertTrue(state.shouldProceed)
        assertEquals(1, state.configuredKeysCount)
        verify { settingsProvider.needsOnboarding = true }
    }

    @Test
    fun `setup stays when there are no keys`() = runTest(dispatcher) {
        val settingsProvider = settingsProviderStub(
            giga = "",
            qwen = "",
            aiTunnel = "",
            speech = "",
            onboardingCompleted = false,
        )
        val viewModel = createViewModel(settingsProvider)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.canProceed)
        assertFalse(state.shouldProceed)
        assertEquals(0, state.configuredKeysCount)
        verify(exactly = 0) { settingsProvider.needsOnboarding = true }
    }

    @Test
    fun `proceed does not re-enable onboarding when already completed`() = runTest(dispatcher) {
        val settingsProvider = settingsProviderStub(
            giga = "giga-token",
            qwen = "",
            aiTunnel = "",
            speech = "",
            onboardingCompleted = true,
        )
        val viewModel = createViewModel(settingsProvider)

        advanceUntilIdle()
        viewModel.send(SetupEvent.Proceed)
        advanceUntilIdle()

        verify(exactly = 0) { settingsProvider.needsOnboarding = true }
    }

    @Test
    fun `setup picks qwen default model when first configured key is qwen`() = runTest(dispatcher) {
        val settingsProvider = settingsProviderStub(
            giga = "",
            qwen = "",
            aiTunnel = "",
            speech = "",
            onboardingCompleted = false,
            gigaModel = GigaModel.Max,
        )
        val viewModel = createViewModel(settingsProvider)

        advanceUntilIdle()
        viewModel.send(SetupEvent.InputQwenChatKey("qwen-token"))
        advanceUntilIdle()

        assertEquals(GigaModel.QwenMax, settingsProvider.gigaModel)
    }

    @Test
    fun `setup picks ai tunnel default model when first configured key is ai tunnel`() = runTest(dispatcher) {
        val settingsProvider = settingsProviderStub(
            giga = "",
            qwen = "",
            aiTunnel = "",
            speech = "",
            onboardingCompleted = false,
            gigaModel = GigaModel.Max,
        )
        val viewModel = createViewModel(settingsProvider)

        advanceUntilIdle()
        viewModel.send(SetupEvent.InputAiTunnelKey("ait-token"))
        advanceUntilIdle()

        assertEquals(GigaModel.AiTunnelGpt4oMini, settingsProvider.gigaModel)
    }

    @Test
    fun `setup prefers giga model when giga key appears during first setup`() = runTest(dispatcher) {
        val settingsProvider = settingsProviderStub(
            giga = "",
            qwen = "",
            aiTunnel = "",
            speech = "",
            onboardingCompleted = false,
            gigaModel = GigaModel.Max,
        )
        val viewModel = createViewModel(settingsProvider)

        advanceUntilIdle()
        viewModel.send(SetupEvent.InputQwenChatKey("qwen-token"))
        advanceUntilIdle()
        assertEquals(GigaModel.QwenMax, settingsProvider.gigaModel)

        viewModel.send(SetupEvent.InputGigaChatKey("giga-token"))
        advanceUntilIdle()
        assertEquals(GigaModel.Max, settingsProvider.gigaModel)
    }

    @Test
    fun `setup does not auto-change model when setup starts with existing keys`() = runTest(dispatcher) {
        val settingsProvider = settingsProviderStub(
            giga = "giga-token",
            qwen = "",
            aiTunnel = "",
            speech = "",
            onboardingCompleted = false,
            gigaModel = GigaModel.Pro,
        )
        val viewModel = createViewModel(settingsProvider)

        advanceUntilIdle()
        viewModel.send(SetupEvent.InputAiTunnelKey("ait-token"))
        advanceUntilIdle()

        assertEquals(GigaModel.Pro, settingsProvider.gigaModel)
    }

    private fun createViewModel(settingsProvider: SettingsProvider): SetupViewModel {
        val say = mockk<Say>(relaxed = true)
        val di = DI {
            bindSingleton<SettingsProvider> { settingsProvider }
            bindSingleton<Say> { say }
        }
        return SetupViewModel(di)
    }

    private fun settingsProviderStub(
        giga: String,
        qwen: String,
        aiTunnel: String,
        speech: String,
        onboardingCompleted: Boolean,
        gigaModel: GigaModel = GigaModel.Max,
    ): SettingsProvider {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)

        var gigaValue = giga
        var qwenValue = qwen
        var aiTunnelValue = aiTunnel
        var speechValue = speech
        var gigaModelValue = gigaModel
        var onboardingCompletedValue = onboardingCompleted
        var needsOnboardingValue = false

        every { settingsProvider.gigaChatKey } answers { gigaValue }
        every { settingsProvider.gigaChatKey = any() } answers { gigaValue = firstArg() }

        every { settingsProvider.qwenChatKey } answers { qwenValue }
        every { settingsProvider.qwenChatKey = any() } answers { qwenValue = firstArg() }

        every { settingsProvider.aiTunnelKey } answers { aiTunnelValue }
        every { settingsProvider.aiTunnelKey = any() } answers { aiTunnelValue = firstArg() }

        every { settingsProvider.saluteSpeechKey } answers { speechValue }
        every { settingsProvider.saluteSpeechKey = any() } answers { speechValue = firstArg() }

        every { settingsProvider.gigaModel } answers { gigaModelValue }
        every { settingsProvider.gigaModel = any() } answers { gigaModelValue = firstArg() }

        every { settingsProvider.onboardingCompleted } answers { onboardingCompletedValue }
        every { settingsProvider.onboardingCompleted = any() } answers { onboardingCompletedValue = firstArg() }

        every { settingsProvider.needsOnboarding } answers { needsOnboardingValue }
        every { settingsProvider.needsOnboarding = any() } answers { needsOnboardingValue = firstArg() }

        return settingsProvider
    }
}
