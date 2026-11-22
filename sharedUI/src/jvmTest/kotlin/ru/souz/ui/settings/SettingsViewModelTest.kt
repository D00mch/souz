@file:OptIn(ExperimentalCoroutinesApi::class)

package ru.souz.ui.settings

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import io.mockk.every
import io.mockk.just
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.job
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import ru.souz.agent.AgentId
import ru.souz.agent.session.GraphSession
import ru.souz.agent.session.GraphSessionRepository
import ru.souz.agent.AgentFacade
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.db.SettingsProvider
import ru.souz.db.SettingsProviderImpl.Companion.REGION_EN
import ru.souz.db.SettingsProviderImpl.Companion.REGION_RU
import ru.souz.llms.DEFAULT_MAX_TOKENS
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMModel
import ru.souz.llms.LocalModelAvailability
import ru.souz.llms.LlmProvider
import ru.souz.llms.VoiceRecognitionModel
import ru.souz.llms.VoiceRecognitionProvider
import ru.souz.llms.local.LocalEmbeddingProfiles
import ru.souz.llms.local.LocalLlamaRuntime
import ru.souz.llms.local.LocalModelProfiles
import ru.souz.llms.local.LocalModelStore
import ru.souz.llms.local.LocalProviderAvailability
import ru.souz.service.telegram.TelegramAuthState
import ru.souz.service.telegram.TelegramAuthStep
import ru.souz.ui.ThemeMode
import ru.souz.ui.common.LocalModelDownloadStateUi
import ru.souz.ui.common.usecases.ApiKeyAvailabilityUseCase
import ru.souz.ui.common.ApiKeyField
import ru.souz.ui.host.CalendarListProvider
import ru.souz.ui.host.BackgroundIndexRefresher
import ru.souz.ui.host.DesktopLocalModelUiHost
import ru.souz.ui.host.DesktopTelegramSettingsHost
import ru.souz.ui.host.ExternalLinkOpener
import ru.souz.ui.host.InMemorySettingsHostPreferences
import ru.souz.ui.host.LocalModelUiHost
import ru.souz.ui.host.LocalCacheCleaner
import ru.souz.ui.host.NoopPrivacyPolicyOpener
import ru.souz.ui.host.NoopSupportLogService
import ru.souz.ui.host.PrivacyPolicyOpener
import ru.souz.ui.host.SettingsHostPreferences
import ru.souz.ui.host.SupportLogService
import ru.souz.ui.host.TelegramControlBot
import ru.souz.ui.host.TelegramSettingsHost
import ru.souz.ui.host.TelegramUiService
import ru.souz.ui.host.UiSpeechPlayer
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val viewModelStore = ViewModelStore()
    private val viewModelJobs = mutableListOf<Job>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkStatic("org.jetbrains.compose.resources.StringResourcesKt")
        coEvery { org.jetbrains.compose.resources.getString(any()) } answers { firstArg<Any>().toString() }
    }

    @AfterTest
    fun tearDown() {
        try {
            runTest(dispatcher) { clearViewModels() }
        } finally {
            Dispatchers.resetMain()
            unmockkAll()
        }
    }

    private fun trackViewModel(viewModel: SettingsViewModel): SettingsViewModel = viewModel.also {
        viewModelJobs += it.viewModelScope.coroutineContext.job
        viewModelStore.put("settings-${viewModelJobs.size}", it)
    }

    private suspend fun clearViewModels() {
        // IO work must finish cancellation before resource and service mocks are removed.
        viewModelStore.clear()
        viewModelJobs.joinAll()
    }

    @Test
    fun `route refresh normalizes unavailable llm embeddings and voice models without eager init reads`() = runTest(dispatcher) {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.regionProfile } returns REGION_RU
        every { settingsProvider.regionProfile = any() } just runs
        val localProviderAvailability = mockk<LocalProviderAvailability>(relaxed = true)
        every { localProviderAvailability.isProviderAvailable() } returns true
        every { localProviderAvailability.availableGigaModels() } returns listOf(LLMModel.LocalQwen3_4B_Instruct_2507)
        every { localProviderAvailability.defaultGigaModel() } returns LLMModel.LocalQwen3_4B_Instruct_2507
        val apiKeyAvailabilityUseCase = ApiKeyAvailabilityUseCase(localProviderAvailability, settingsProvider)

        val supportsPolza = settingsProvider.regionProfile == REGION_RU
        every { settingsProvider.hasKey(any<LlmProvider>()) } answers {
            firstArg<LlmProvider>() == LlmProvider.QWEN
        }
        every { settingsProvider.hasKey(any<VoiceRecognitionProvider>()) } answers {
            val provider = firstArg<VoiceRecognitionProvider>()
            if (supportsPolza) {
                provider == VoiceRecognitionProvider.POLZA
            } else {
                provider == VoiceRecognitionProvider.OPENAI
            }
        }
        val configuredVoiceRecognitionModel = if (supportsPolza) {
            VoiceRecognitionModel.OpenAIGpt4oTranscribe
        } else {
            VoiceRecognitionModel.PolzaGigaAm
        }

        val configuredModel = LLMModel.Pro

        var embeddingsModelValue = EmbeddingsModel.GigaEmbeddings
        var voiceRecognitionModelValue = configuredVoiceRecognitionModel

        every { settingsProvider.gigaChatKey } returns ""
        every { settingsProvider.qwenChatKey } returns "qwen-key"
        every { settingsProvider.aiTunnelKey } returns ""
        every { settingsProvider.anthropicKey } returns ""
        every { settingsProvider.openaiKey } returns if (supportsPolza) "" else "openai-key"
        every { settingsProvider.polzaApiKey } returns if (supportsPolza) "polza-key" else ""
        every { settingsProvider.gigaModel } returns configuredModel
        var ambientAnalysisModelValue = LLMModel.Max
        every { settingsProvider.ambientAnalysisModel } answers { ambientAnalysisModelValue }
        every { settingsProvider.ambientAnalysisModel = any() } answers {
            ambientAnalysisModelValue = firstArg()
        }
        every { settingsProvider.embeddingsModel } answers { embeddingsModelValue }
        every { settingsProvider.embeddingsModel = any() } answers { embeddingsModelValue = firstArg() }
        every { settingsProvider.voiceRecognitionModel } answers { voiceRecognitionModelValue }
        every {
            settingsProvider.voiceRecognitionModel = any()
        } answers { voiceRecognitionModelValue = firstArg() }

        every { settingsProvider.getSystemPromptForAgentModel(any(), any()) } returns null
        every { settingsProvider.supportEmail } returns null
        every { settingsProvider.mcpServersJson } returns null
        every { settingsProvider.defaultCalendar } returns null
        every { settingsProvider.useFewShotExamples } returns false
        every { settingsProvider.useStreaming } returns false
        every { settingsProvider.notificationSoundEnabled } returns true
        every { settingsProvider.safeModeEnabled } returns true
        every { settingsProvider.requestTimeoutMillis } returns 40_000L
        every { settingsProvider.contextSize } returns DEFAULT_MAX_TOKENS
        every { settingsProvider.temperature } returns 0.7f

        val agentFacade = mockk<AgentFacade>(relaxed = true)
        every { agentFacade.setModel(any()) } answers {
            val model = firstArg<LLMModel>()
            "prompt-for-${model.alias}"
        }
        every { agentFacade.activeAgentId } returns MutableStateFlow(ru.souz.agent.AgentId.GRAPH)
        every { agentFacade.availableAgents } returns listOf(ru.souz.agent.AgentId.GRAPH)

        val chatApi = mockk<LLMChatAPI>(relaxed = true)
        val telegramService = mockk<TelegramUiService>(relaxed = true)
        every { telegramService.isSupported() } returns true
        every { telegramService.authState } returns MutableStateFlow(TelegramAuthState(step = TelegramAuthStep.WAIT_PHONE))
        val telegramControlBot = mockk<TelegramControlBot>(relaxed = true)
        var telegramHostCreated = false
        val localModelStore = mockk<LocalModelStore>(relaxed = true)
        val localLlamaRuntime = mockk<LocalLlamaRuntime>(relaxed = true)
        val desktopInfoRepository = mockk<BackgroundIndexRefresher>(relaxed = true)
        coEvery { desktopInfoRepository.rebuildIndexNow() } returns Unit
        var calendarListProviderCalls = 0

        val di = DI {
            bindSingleton<SettingsProvider> { settingsProvider }
            bindSingleton<BackgroundIndexRefresher> { desktopInfoRepository }
            bindSingleton<LocalModelAvailability> { localProviderAvailability }
            bindSingleton { localModelStore }
            bindSingleton { localLlamaRuntime }
            bindSingleton<ApiKeyAvailabilityUseCase> { apiKeyAvailabilityUseCase }
            bindSingleton<LLMChatAPI> { chatApi }
            bindSingleton<AgentFacade> { agentFacade }
            bindSingleton<TelegramUiService> { telegramService }
            bindSingleton<TelegramControlBot> { telegramControlBot }
            bindSingleton<LocalModelUiHost> {
                DesktopLocalModelUiHost(localModelStore, localLlamaRuntime, desktopInfoRepository)
            }
            bindSingleton<TelegramSettingsHost> {
                telegramHostCreated = true
                DesktopTelegramSettingsHost(telegramService, telegramControlBot)
            }
            bindSingleton<SupportLogService> { NoopSupportLogService }
            bindSingleton<PrivacyPolicyOpener> { NoopPrivacyPolicyOpener }
            bindSingleton<SettingsHostPreferences> { InMemorySettingsHostPreferences() }
            bindSingleton<ExternalLinkOpener> { ExternalLinkOpener { Result.success(Unit) } }
            bindSingleton<CalendarListProvider> {
                {
                    calendarListProviderCalls += 1
                    listOf("Work")
                }
            }
            bindSingleton<UiSpeechPlayer> { mockk(relaxed = true) }
        }

        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = trackViewModel(SettingsViewModel(di, settingsDispatcher = dispatcher))
        assertEquals(false, telegramHostCreated)
        advanceUntilIdle()
        assertTrue(telegramHostCreated)
        assertEquals(TelegramAuthStepUi.PHONE, viewModel.uiState.value.telegramAuthStep)

        viewModel.handleEvent(SettingsEvent.RefreshFromProvider)
        advanceUntilIdle()
        val expectedLlmModel = LLMModel.QwenMax
        val expectedAmbientAnalysisModel = LLMModel.LocalQwen3_4B_Instruct_2507
        val expectedEmbeddingsModel = EmbeddingsModel.QwenEmbeddings
        val expectedVoiceRecognitionModel = VoiceRecognitionModel.PolzaGigaAm

        val state = viewModel.uiState.value
        assertEquals(expectedLlmModel, state.gigaModel)
        assertEquals(expectedAmbientAnalysisModel, state.ambientAnalysisModel)
        assertEquals(expectedAmbientAnalysisModel, ambientAnalysisModelValue)
        assertTrue(state.availableAmbientAnalysisModels.all { it.provider == LlmProvider.LOCAL })
        assertEquals(expectedEmbeddingsModel, state.embeddingsModel)
        assertEquals(expectedEmbeddingsModel, embeddingsModelValue)
        assertEquals(expectedVoiceRecognitionModel, state.voiceRecognitionModel)
        assertEquals(expectedVoiceRecognitionModel, voiceRecognitionModelValue)
        assertEquals("prompt-for-${expectedLlmModel.alias}", state.systemPrompt)
        assertIs<ApiKeyFieldState.StoredHidden>(state.apiKeyFields.getValue(ApiKeyField.QWEN_CHAT))
        assertEquals(0, calendarListProviderCalls)

        verify(exactly = 0) { settingsProvider.gigaChatKey }
        verify(exactly = 0) { settingsProvider.qwenChatKey }
        verify(exactly = 0) { settingsProvider.aiTunnelKey }
        verify(exactly = 0) { settingsProvider.anthropicKey }
        verify(exactly = 0) { settingsProvider.openaiKey }
        verify(exactly = 0) { settingsProvider.polzaApiKey }

        verify(exactly = 1) { agentFacade.setModel(expectedLlmModel) }
        coVerify(exactly = 1) { desktopInfoRepository.rebuildIndexNow() }

        viewModel.handleEvent(SettingsEvent.ToggleApiKeyVisibility(ApiKeyField.QWEN_CHAT))
        advanceUntilIdle()

        assertEquals(
            ApiKeyFieldState.Editable(value = "qwen-key", revealed = true),
            viewModel.uiState.value.apiKeyFields.getValue(ApiKeyField.QWEN_CHAT),
        )
        verify(exactly = 1) { settingsProvider.qwenChatKey }

        viewModel.handleEvent(SettingsEvent.InputApiKey(ApiKeyField.QWEN_CHAT, "updated-qwen-key"))
        every { settingsProvider.mcpServersJson = "invalid" } throws IllegalStateException("save failed")
        viewModel.handleEvent(SettingsEvent.InputMcpServersJson("invalid"))
        viewModel.handleEvent(SettingsEvent.GoToMain)
        advanceUntilIdle()

        assertIs<ApiKeyFieldState.StoredHidden>(
            viewModel.uiState.value.apiKeyFields.getValue(ApiKeyField.QWEN_CHAT),
        )
        verify(exactly = 1) { settingsProvider.qwenChatKey = "updated-qwen-key" }
        verify(exactly = 1) { agentFacade.setModel(expectedLlmModel) }
        viewModel.handleEvent(SettingsEvent.RefreshFromProvider)

        every { settingsProvider.qwenChatKey } throws IllegalStateException("decrypt failed")
        viewModel.handleEvent(SettingsEvent.ToggleApiKeyVisibility(ApiKeyField.QWEN_CHAT))
        advanceUntilIdle()

        assertEquals(
            ApiKeyFieldState.Editable(value = "", revealed = true),
            viewModel.uiState.value.apiKeyFields.getValue(ApiKeyField.QWEN_CHAT),
        )
        verify(exactly = 2) { settingsProvider.qwenChatKey }
    }

    @Test
    fun `interface language change preserves active settings section`() = runTest(dispatcher) {
        val settingsHostPreferences = InMemorySettingsHostPreferences()
        val viewModel = trackViewModel(SettingsViewModel(
            di = DI {
                bindSingleton<TelegramSettingsHost> { ru.souz.ui.host.NoopTelegramSettingsHost }
                bindSingleton<SettingsHostPreferences> { settingsHostPreferences }
            },
            settingsDispatcher = dispatcher,
        ))

        viewModel.handleEvent(SettingsEvent.SelectSettingsSection(SettingsSection.GENERAL))
        viewModel.handleEvent(SettingsEvent.InputUseEnglishInterface(true))

        assertEquals(SettingsSection.GENERAL, viewModel.uiState.value.activeSection)
        assertTrue(viewModel.uiState.value.useEnglishInterface)
        assertTrue(settingsHostPreferences.useEnglishInterface)
    }

    @Test
    fun `chat choices and selected model survive both desktop language changes`() = runTest(dispatcher) {
        var serviceLanguage = REGION_RU
        val fixture = createLocalModelSettingsFixture(
            gigaModel = LLMModel.OpenAIGpt52,
            configureSettingsProvider = { settings ->
                every { settings.regionProfile } answers { serviceLanguage }
                every { settings.regionProfile = any() } answers { serviceLanguage = firstArg() }
                every { settings.hasKey(LlmProvider.GIGA) } returns true
                every { settings.hasKey(LlmProvider.OPENAI) } returns true
            },
        )
        val viewModel = fixture.viewModel
        viewModel.handleEvent(SettingsEvent.RefreshFromProvider)
        advanceUntilIdle()

        val models = viewModel.uiState.value.availableLlmModels
        assertTrue(LLMModel.Pro in models)
        assertTrue(LLMModel.OpenAIGpt52 in models)
        viewModel.handleEvent(SettingsEvent.InputUseEnglishVersion(true))
        advanceUntilIdle()
        assertEquals(REGION_EN, serviceLanguage)
        assertEquals(models, viewModel.uiState.value.availableLlmModels)
        assertEquals(LLMModel.OpenAIGpt52, viewModel.uiState.value.gigaModel)

        viewModel.handleEvent(SettingsEvent.InputUseEnglishInterface(true))
        assertEquals(models, viewModel.uiState.value.availableLlmModels)
        assertEquals(LLMModel.OpenAIGpt52, viewModel.uiState.value.gigaModel)
    }

    @Test
    fun `theme selection updates observable preferences`() = runTest(dispatcher) {
        val settingsHostPreferences = InMemorySettingsHostPreferences()
        val viewModel = createViewModel(
            telegramHost = ru.souz.ui.host.NoopTelegramSettingsHost,
            preferences = settingsHostPreferences,
        )

        viewModel.handleEvent(SettingsEvent.SelectThemeMode(ThemeMode.LIGHT))
        advanceUntilIdle()

        assertEquals(ThemeMode.LIGHT, viewModel.uiState.value.themeMode)
        assertEquals(ThemeMode.LIGHT, settingsHostPreferences.themeMode.value)

        settingsHostPreferences.setThemeMode(ThemeMode.DARK)
        advanceUntilIdle()

        assertEquals(ThemeMode.DARK, viewModel.uiState.value.themeMode)
    }

    @Test
    fun `telegram auth is single-flight without blocking settings events`() = runTest(dispatcher) {
        val authState = MutableStateFlow(
            ru.souz.ui.host.TelegramHostAuthState(
                step = ru.souz.ui.host.TelegramHostAuthStep.PHONE,
            )
        )
        val finishOperation = CompletableDeferred<Unit>()
        val telegramHost = mockk<TelegramSettingsHost>(relaxed = true)
        every { telegramHost.isSupported() } returns true
        every { telegramHost.authState } returns authState
        coEvery { telegramHost.submitPhoneNumber(any()) } coAnswers {
            finishOperation.await()
        }
        val preferences = InMemorySettingsHostPreferences()
        val viewModel = createViewModel(telegramHost, preferences)
        advanceUntilIdle()

        viewModel.handleEvent(SettingsEvent.InputTelegramPhone("+79990000000"))
        viewModel.send(SettingsEvent.SubmitTelegramPhone)
        runCurrent()

        viewModel.send(SettingsEvent.SubmitTelegramPhone)
        viewModel.send(SettingsEvent.SelectThemeMode(ThemeMode.LIGHT))
        runCurrent()

        try {
            coVerify(exactly = 1) { telegramHost.submitPhoneNumber("+79990000000") }
            assertEquals(ThemeMode.LIGHT, preferences.themeMode.value)
        } finally {
            finishOperation.complete(Unit)
            advanceUntilIdle()
        }
    }

    @Test
    fun `telegram bot operation exposes final state`() = runTest(dispatcher) {
        val telegramHost = mockk<TelegramSettingsHost>(relaxed = true)
        every { telegramHost.isSupported() } returns true
        every { telegramHost.authState } returns MutableStateFlow(
            ru.souz.ui.host.TelegramHostAuthState(
                step = ru.souz.ui.host.TelegramHostAuthStep.PHONE,
            )
        )
        val finishBotOperation = CompletableDeferred<Unit>()
        coEvery { telegramHost.createControlBot(forceNew = true) } coAnswers {
            finishBotOperation.await()
        }
        val viewModel = createViewModel(telegramHost)
        advanceUntilIdle()

        viewModel.send(SettingsEvent.CreateControlBot)
        runCurrent()

        assertTrue(viewModel.uiState.value.telegramOperationBusy)
        viewModel.send(SettingsEvent.CreateControlBot)
        runCurrent()
        coVerify(exactly = 1) { telegramHost.createControlBot(forceNew = true) }

        finishBotOperation.complete(Unit)
        advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.telegramOperationBusy)
        assertTrue(viewModel.uiState.value.isTelegramBotActive)
    }

    @Test
    fun `telegram secondary auth actions are handled by view model`() = runTest(dispatcher) {
        val telegramHost = mockk<TelegramSettingsHost>(relaxed = true)
        every { telegramHost.isSupported() } returns true
        every { telegramHost.authState } returns MutableStateFlow(
            ru.souz.ui.host.TelegramHostAuthState(
                step = ru.souz.ui.host.TelegramHostAuthStep.CODE,
            )
        )
        val viewModel = createViewModel(telegramHost)
        advanceUntilIdle()

        viewModel.handleEvent(SettingsEvent.InputTelegramPhone("+7 (999) 000-00-00"))
        viewModel.handleEvent(SettingsEvent.RequestTelegramCodeAgain)
        advanceUntilIdle()

        coVerify(exactly = 1) { telegramHost.requestCodeAgain("+79990000000") }
        assertNotNull(viewModel.uiState.value.telegramAuthInfo)

        viewModel.handleEvent(SettingsEvent.RestartTelegramAuth)
        advanceUntilIdle()

        coVerify(exactly = 1) { telegramHost.cancelAuth() }
        assertEquals("", viewModel.uiState.value.telegramCodeInput)
        assertEquals("", viewModel.uiState.value.telegramPasswordInput)
    }

    @Test
    fun `cache cleanup requires confirmation and cancel does no work`() = runTest(dispatcher) {
        val cleaner = mockk<LocalCacheCleaner>(relaxed = true)
        val viewModel = cacheCleanupViewModel(cleaner)

        viewModel.handleEvent(SettingsEvent.ConfirmClearLocalCaches)
        viewModel.handleEvent(SettingsEvent.ClearLocalCaches)
        assertTrue(viewModel.uiState.value.showClearLocalCachesConfirmation)
        viewModel.handleEvent(SettingsEvent.CancelClearLocalCaches)
        viewModel.handleEvent(SettingsEvent.ConfirmClearLocalCaches)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showClearLocalCachesConfirmation)
        coVerify(exactly = 0) { cleaner.clear() }
    }

    @Test
    fun `confirmed cleanup runs once while busy and publishes success`() = runTest(dispatcher) {
        val completion = CompletableDeferred<Unit>()
        val cleaner = mockk<LocalCacheCleaner>()
        coEvery { cleaner.clear() } coAnswers { completion.await() }
        val viewModel = cacheCleanupViewModel(cleaner)

        viewModel.handleEvent(SettingsEvent.ClearLocalCaches)
        viewModel.handleEvent(SettingsEvent.ConfirmClearLocalCaches)
        runCurrent()
        assertTrue(viewModel.uiState.value.isClearingLocalCaches)
        assertFalse(viewModel.uiState.value.showClearLocalCachesConfirmation)
        viewModel.handleEvent(SettingsEvent.ClearLocalCaches)
        viewModel.handleEvent(SettingsEvent.ConfirmClearLocalCaches)
        completion.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { cleaner.clear() }
        assertFalse(viewModel.uiState.value.isClearingLocalCaches)
        assertNotNull(viewModel.uiState.value.clearLocalCachesMessage)
    }

    @Test
    fun `failed cleanup reports failure and allows retry`() = runTest(dispatcher) {
        val cleaner = mockk<LocalCacheCleaner>()
        coEvery { cleaner.clear() } throws java.io.IOException("synthetic failure")
        val viewModel = cacheCleanupViewModel(cleaner)
        viewModel.handleEvent(SettingsEvent.ClearLocalCaches)
        viewModel.handleEvent(SettingsEvent.ConfirmClearLocalCaches)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isClearingLocalCaches)
        val failureMessage = viewModel.uiState.value.clearLocalCachesMessage
        assertNotNull(failureMessage)
        coEvery { cleaner.clear() } returns Unit
        viewModel.handleEvent(SettingsEvent.ClearLocalCaches)
        assertNull(viewModel.uiState.value.clearLocalCachesMessage)
        viewModel.handleEvent(SettingsEvent.ConfirmClearLocalCaches)
        advanceUntilIdle()

        coVerify(exactly = 2) { cleaner.clear() }
        assertFalse(viewModel.uiState.value.isClearingLocalCaches)
        assertNotNull(viewModel.uiState.value.clearLocalCachesMessage)
        assertTrue(viewModel.uiState.value.clearLocalCachesMessage != failureMessage)
    }

    @Test
    fun `graph navigation loads on settings dispatcher and refreshes sessions on reentry`() = runTest(dispatcher) {
        val first = GraphSession("first", 1L, initialInput = "Synthetic first session", steps = emptyList())
        val second = first.copy(id = "second", startTime = 2L)
        val repository = mockk<GraphSessionRepository>()
        val reads = mutableListOf<String>()
        var sessions = listOf(first)
        every { repository.loadAll() } answers {
            assertEquals("settings-session-test", Thread.currentThread().name)
            reads += "all"
            sessions
        }
        every { repository.loadById("first") } answers {
            assertEquals("settings-session-test", Thread.currentThread().name)
            reads += "first"
            first
        }
        every { repository.loadById("missing") } returns null

        Executors.newSingleThreadExecutor { task -> Thread(task, "settings-session-test") }
            .asCoroutineDispatcher().use { settingsIo ->
                val viewModel = createViewModel(sessionRepository = repository, settingsIoDispatcher = settingsIo)
                viewModel.handleEvent(SettingsEvent.OpenGraphSessions)
                assertEquals(listOf(first), viewModel.uiState.value.graphSessions)
                assertEquals(SettingsSubScreen.SESSIONS, viewModel.uiState.value.currentScreen)

                viewModel.handleEvent(SettingsEvent.OpenGraphVisualization(first.id))
                assertEquals(first, viewModel.uiState.value.selectedSession)
                assertEquals(SettingsSubScreen.VISUALIZATION, viewModel.uiState.value.currentScreen)
                sessions = listOf(second, first)
                viewModel.handleEvent(SettingsEvent.EscapePressed)
                assertEquals(SettingsSubScreen.SESSIONS, viewModel.uiState.value.currentScreen)
                assertNull(viewModel.uiState.value.selectedSession)
                assertEquals(sessions, viewModel.uiState.value.graphSessions)
                assertEquals(listOf("all", "first", "all"), reads)

                viewModel.handleEvent(SettingsEvent.OpenGraphVisualization("missing"))
                assertEquals(SettingsSubScreen.VISUALIZATION, viewModel.uiState.value.currentScreen)
                assertNull(viewModel.uiState.value.selectedSession)
                viewModel.handleEvent(SettingsEvent.BackToSessions)
                viewModel.handleEvent(SettingsEvent.EscapePressed)
                assertEquals(SettingsSubScreen.MAIN, viewModel.uiState.value.currentScreen)
                sessions = emptyList()
                viewModel.handleEvent(SettingsEvent.OpenGraphSessions)
                assertTrue(viewModel.uiState.value.graphSessions.isEmpty())
                verify(exactly = 4) { repository.loadAll() }
                verify(exactly = 1) { repository.loadById("first") }
                verify(exactly = 1) { repository.loadById("missing") }
            }
    }

    @Test
    fun `escape closes main settings and flushes a pending setting before emitting close`() = runTest(dispatcher) {
        val provider = mockk<SettingsProvider>(relaxed = true)
        val viewModel = createViewModel(settingsProvider = provider)
        val effects = mutableListOf<SettingsEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.effects.collect { effect ->
                verify(exactly = 1) { provider.supportEmail = "synthetic@example.invalid" }
                effects += effect
            }
        }
        runCurrent()
        viewModel.handleEvent(SettingsEvent.InputSupportEmail("synthetic@example.invalid"))
        viewModel.handleEvent(SettingsEvent.EscapePressed)

        assertEquals(listOf<SettingsEffect>(SettingsEffect.CloseScreen), effects)
        assertEquals("synthetic@example.invalid", viewModel.uiState.value.supportEmail)
    }

    @Test
    fun `escape returns folders and telegram to settings without closing`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        val effects = mutableListOf<SettingsEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.toList(effects) }
        for (event in listOf(SettingsEvent.OpenFoldersManagement, SettingsEvent.OpenTelegramSettings)) {
            viewModel.handleEvent(event)
            viewModel.handleEvent(SettingsEvent.EscapePressed)
            assertEquals(SettingsSubScreen.MAIN, viewModel.uiState.value.currentScreen)
        }
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `escape dismisses telegram bot deletion before leaving telegram`() = runTest(dispatcher) {
        val telegramHost = mockk<TelegramSettingsHost>(relaxed = true)
        every { telegramHost.authState } returns MutableStateFlow(ru.souz.ui.host.TelegramHostAuthState())
        coEvery { telegramHost.fetchActiveBotUsernameFromBotFather() } returns "synthetic_bot"
        val viewModel = createViewModel(telegramHost)
        viewModel.handleEvent(SettingsEvent.OpenTelegramSettings)
        viewModel.handleEvent(SettingsEvent.DisconnectTelegramBot)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showBotDeleteConfirmation)

        viewModel.handleEvent(SettingsEvent.EscapePressed)
        assertFalse(viewModel.uiState.value.showBotDeleteConfirmation)
        assertEquals(SettingsSubScreen.TELEGRAM, viewModel.uiState.value.currentScreen)
        coVerify(exactly = 0) { telegramHost.deleteControlBot(any()) }
        viewModel.handleEvent(SettingsEvent.EscapePressed)
        assertEquals(SettingsSubScreen.MAIN, viewModel.uiState.value.currentScreen)
    }

    @Test
    fun `escape dismisses agent switch without changing the active agent`() = runTest(dispatcher) {
        val fixture = createLocalModelSettingsFixture()
        val viewModel = fixture.viewModel
        val target = AgentId.entries.first { it != viewModel.uiState.value.activeAgentId }
        viewModel.handleEvent(SettingsEvent.SelectAgent(target))
        assertTrue(viewModel.uiState.value.showAgentSwitchConfirmation)

        viewModel.handleEvent(SettingsEvent.EscapePressed)
        assertFalse(viewModel.uiState.value.showAgentSwitchConfirmation)
        assertNull(viewModel.uiState.value.pendingAgentId)
        assertEquals(SettingsSubScreen.MAIN, viewModel.uiState.value.currentScreen)
        coVerify(exactly = 0) { fixture.agentFacade.setActiveAgent(any()) }
    }

    @Test
    fun `escape cancels local model prompt and progress before dismissing agent switch`() = runTest(dispatcher) {
        val localHost = mockk<LocalModelUiHost>(relaxed = true)
        coEvery { localHost.downloadPromptFor(any()) } returns null
        val fixture = createLocalModelSettingsFixture(localModelUiHost = localHost)
        val viewModel = fixture.viewModel
        viewModel.handleEvent(SettingsEvent.RefreshFromProvider)
        val model = LLMModel.LocalQwen3_4B_Instruct_2507
        val prompt = ru.souz.ui.common.LocalModelDownloadPromptUi(model, "synthetic", "Synthetic model", emptyList())
        coEvery { localHost.downloadPromptFor(model) } returns prompt
        coEvery { localHost.startDownload(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            arg<suspend (LocalModelDownloadStateUi?) -> Unit>(4)(LocalModelDownloadStateUi(prompt))
            null
        }
        coEvery { localHost.cancelDownload(any(), any(), any(), any()) } coAnswers {
            arg<suspend () -> Unit>(2)()
            null
        }
        val target = AgentId.entries.first { it != viewModel.uiState.value.activeAgentId }
        for (withProgress in listOf(false, true)) {
            viewModel.handleEvent(SettingsEvent.SelectAgent(target))
            viewModel.handleEvent(SettingsEvent.SelectModel(model))
            assertEquals(prompt, viewModel.uiState.value.localModelDownloadPrompt)
            if (withProgress) {
                viewModel.handleEvent(SettingsEvent.ConfirmLocalModelDownload)
                assertNotNull(viewModel.uiState.value.localModelDownloadState)
            }
            viewModel.handleEvent(SettingsEvent.EscapePressed)
            assertNull(viewModel.uiState.value.localModelDownloadPrompt)
            assertNull(viewModel.uiState.value.localModelDownloadState)
            assertTrue(viewModel.uiState.value.showAgentSwitchConfirmation)
            assertEquals(SettingsSubScreen.MAIN, viewModel.uiState.value.currentScreen)
            viewModel.handleEvent(SettingsEvent.EscapePressed)
            assertFalse(viewModel.uiState.value.showAgentSwitchConfirmation)
        }
        coVerify(exactly = 1) { localHost.cancelDownload(any(), false, any(), any()) }
        coVerify(exactly = 1) { localHost.cancelDownload(any(), true, any(), any()) }
        coVerify(exactly = 0) { fixture.agentFacade.setActiveAgent(any()) }
        verify(exactly = 0) { fixture.agentFacade.setModel(model) }
    }

    private fun cacheCleanupViewModel(cleaner: LocalCacheCleaner) = trackViewModel(SettingsViewModel(
        di = DI {
            bindSingleton<TelegramSettingsHost> { ru.souz.ui.host.NoopTelegramSettingsHost }
            bindSingleton<SettingsHostPreferences> { InMemorySettingsHostPreferences() }
            bindSingleton<LocalCacheCleaner> { cleaner }
        },
        settingsDispatcher = dispatcher,
    ))

    private fun createViewModel(
        telegramHost: TelegramSettingsHost = ru.souz.ui.host.NoopTelegramSettingsHost,
        preferences: SettingsHostPreferences = InMemorySettingsHostPreferences(),
        sessionRepository: GraphSessionRepository = mockk(relaxed = true),
        settingsProvider: SettingsProvider = mockk(relaxed = true),
        settingsIoDispatcher: CoroutineDispatcher = dispatcher,
    ) = trackViewModel(SettingsViewModel(
        di = DI {
            bindSingleton<TelegramSettingsHost> { telegramHost }
            bindSingleton<SettingsHostPreferences> { preferences }
            bindSingleton<GraphSessionRepository> { sessionRepository }
            bindSingleton<SettingsProvider> { settingsProvider }
        },
        settingsDispatcher = settingsIoDispatcher,
    ))

    private data class LocalModelSettingsFixture(
        val agentFacade: AgentFacade,
        val viewModel: SettingsViewModel,
    )

    private fun createLocalModelSettingsFixture(
        gigaModel: LLMModel = LLMModel.QwenMax,
        embeddingsModel: EmbeddingsModel = EmbeddingsModel.GigaEmbeddings,
        availableGigaModels: List<LLMModel> = listOf(LLMModel.LocalQwen3_4B_Instruct_2507),
        configureSettingsProvider: (SettingsProvider) -> Unit = {},
        configureLocalModelStore: (LocalModelStore) -> Unit = {},
        configureAgentFacade: (AgentFacade) -> Unit = {},
        localModelUiHost: LocalModelUiHost? = null,
    ): LocalModelSettingsFixture {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.regionProfile } returns REGION_RU
        every { settingsProvider.regionProfile = any() } just runs
        every { settingsProvider.qwenChatKey } returns "qwen-key"
        every { settingsProvider.hasKey(LlmProvider.QWEN) } returns true
        every { settingsProvider.hasKey(LlmProvider.LOCAL) } returns true
        every { settingsProvider.gigaModel } returns gigaModel
        every { settingsProvider.ambientAnalysisModel } returns LLMModel.LocalQwen3_4B_Instruct_2507
        every { settingsProvider.embeddingsModel } returns embeddingsModel
        every { settingsProvider.voiceRecognitionModel } returns VoiceRecognitionModel.PolzaGigaAm
        every { settingsProvider.getSystemPromptForAgentModel(any(), any()) } returns null
        every { settingsProvider.supportEmail } returns null
        every { settingsProvider.mcpServersJson } returns null
        every { settingsProvider.defaultCalendar } returns null
        every { settingsProvider.useFewShotExamples } returns false
        every { settingsProvider.useStreaming } returns false
        every { settingsProvider.notificationSoundEnabled } returns true
        every { settingsProvider.safeModeEnabled } returns true
        every { settingsProvider.requestTimeoutMillis } returns 40_000L
        every { settingsProvider.contextSize } returns DEFAULT_MAX_TOKENS
        every { settingsProvider.temperature } returns 0.7f
        configureSettingsProvider(settingsProvider)

        val localProviderAvailability = mockk<LocalProviderAvailability>(relaxed = true)
        every { localProviderAvailability.isProviderAvailable() } returns true
        every { localProviderAvailability.availableGigaModels() } returns availableGigaModels
        every { localProviderAvailability.defaultGigaModel() } returns LLMModel.LocalQwen3_4B_Instruct_2507
        val apiKeyAvailabilityUseCase = ApiKeyAvailabilityUseCase(localProviderAvailability, settingsProvider)

        val localModelStore = mockk<LocalModelStore>(relaxed = true)
        configureLocalModelStore(localModelStore)
        val localLlamaRuntime = mockk<LocalLlamaRuntime>(relaxed = true)
        val desktopInfoRepository = mockk<BackgroundIndexRefresher>(relaxed = true)
        coEvery { desktopInfoRepository.rebuildIndexNow() } returns Unit

        val agentFacade = mockk<AgentFacade>(relaxed = true)
        every { agentFacade.setModel(any()) } answers { "prompt-for-${firstArg<LLMModel>().alias}" }
        every { agentFacade.activeAgentId } returns MutableStateFlow(ru.souz.agent.AgentId.GRAPH)
        every { agentFacade.availableAgents } returns listOf(ru.souz.agent.AgentId.GRAPH)
        configureAgentFacade(agentFacade)

        val telegramService = mockk<TelegramUiService>(relaxed = true)
        every { telegramService.isSupported() } returns true
        every { telegramService.authState } returns MutableStateFlow(TelegramAuthState(step = TelegramAuthStep.WAIT_PHONE))
        val telegramControlBot = mockk<TelegramControlBot>(relaxed = true)
        val di = DI {
            bindSingleton<SettingsProvider> { settingsProvider }
            bindSingleton<BackgroundIndexRefresher> { desktopInfoRepository }
            bindSingleton<LocalModelAvailability> { localProviderAvailability }
            bindSingleton { localModelStore }
            bindSingleton { localLlamaRuntime }
            bindSingleton<ApiKeyAvailabilityUseCase> { apiKeyAvailabilityUseCase }
            bindSingleton<LLMChatAPI> { mockk(relaxed = true) }
            bindSingleton<AgentFacade> { agentFacade }
            bindSingleton<TelegramUiService> { telegramService }
            bindSingleton<TelegramControlBot> { telegramControlBot }
            bindSingleton<LocalModelUiHost> {
                localModelUiHost ?: DesktopLocalModelUiHost(localModelStore, localLlamaRuntime, desktopInfoRepository)
            }
            bindSingleton<TelegramSettingsHost> { DesktopTelegramSettingsHost(telegramService, telegramControlBot) }
            bindSingleton<SupportLogService> { NoopSupportLogService }
            bindSingleton<PrivacyPolicyOpener> { NoopPrivacyPolicyOpener }
            bindSingleton<SettingsHostPreferences> { InMemorySettingsHostPreferences() }
            bindSingleton<ExternalLinkOpener> { ExternalLinkOpener { Result.success(Unit) } }
            bindSingleton<CalendarListProvider> { { emptyList() } }
            bindSingleton<UiSpeechPlayer> { mockk(relaxed = true) }
        }
        return LocalModelSettingsFixture(agentFacade, trackViewModel(SettingsViewModel(di, settingsDispatcher = dispatcher)))
    }

    @Test
    fun `saving and clearing Polza key refreshes selectable voice models`() = runTest(dispatcher) {
        var key: String? = null
        val fixture = createLocalModelSettingsFixture(configureSettingsProvider = { settings ->
            every { settings.polzaApiKey } answers { key }
            every { settings.polzaApiKey = any() } answers { key = firstArg() }
            every { settings.hasKey(VoiceRecognitionProvider.POLZA) } answers { !key.isNullOrBlank() }
        })
        val viewModel = fixture.viewModel
        viewModel.handleEvent(SettingsEvent.RefreshFromProvider)
        advanceUntilIdle()
        assertFalse(VoiceRecognitionModel.PolzaGigaAm in viewModel.uiState.value.availableVoiceRecognitionModels)

        viewModel.handleEvent(SettingsEvent.ToggleApiKeyVisibility(ApiKeyField.POLZA))
        advanceUntilIdle()
        viewModel.handleEvent(SettingsEvent.InputApiKey(ApiKeyField.POLZA, "pza_fixture"))
        advanceUntilIdle()
        assertEquals("pza_fixture", key)
        assertTrue(VoiceRecognitionModel.PolzaGigaAm in viewModel.uiState.value.availableVoiceRecognitionModels)

        viewModel.handleEvent(SettingsEvent.InputApiKey(ApiKeyField.POLZA, ""))
        advanceUntilIdle()
        assertNull(key)
        assertFalse(VoiceRecognitionModel.PolzaGigaAm in viewModel.uiState.value.availableVoiceRecognitionModels)
    }

    @Test
    fun `selecting ambient analysis model updates separate local setting without changing agent model`() = runTest(dispatcher) {
        var ambientAnalysisModelValue = LLMModel.LocalQwen3_4B_Instruct_2507
        val fixture = createLocalModelSettingsFixture(
            availableGigaModels = listOf(
            LLMModel.LocalQwen3_4B_Instruct_2507,
            LLMModel.LocalGemma4_E2B_It,
            ),
            configureSettingsProvider = { settingsProvider ->
                every { settingsProvider.ambientAnalysisModel } answers { ambientAnalysisModelValue }
                every { settingsProvider.ambientAnalysisModel = any() } answers {
                    ambientAnalysisModelValue = firstArg()
                }
            },
            configureLocalModelStore = { localModelStore ->
                every { localModelStore.isPresent(any()) } returns true
            },
        )
        val viewModel = fixture.viewModel
        advanceUntilIdle()
        viewModel.handleEvent(SettingsEvent.RefreshFromProvider)
        advanceUntilIdle()
        viewModel.handleEvent(SettingsEvent.SelectAmbientAnalysisModel(LLMModel.LocalGemma4_E2B_It))
        advanceUntilIdle()

        assertEquals(LLMModel.LocalGemma4_E2B_It, ambientAnalysisModelValue)
        assertEquals(LLMModel.LocalGemma4_E2B_It, viewModel.uiState.value.ambientAnalysisModel)
        verify(exactly = 1) { fixture.agentFacade.setModel(LLMModel.QwenMax) }
    }

    @Test
    fun `selecting missing local model opens download prompt instead of switching immediately`() = runTest(dispatcher) {
        val localProfile = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507
        val fixture = createLocalModelSettingsFixture(
            configureLocalModelStore = { localModelStore ->
                every { localModelStore.isPresent(localProfile) } returns false
                every { localModelStore.modelPath(localProfile) } returns
                    File(System.getProperty("java.io.tmpdir"), localProfile.ggufFilename).toPath()
            },
        )
        val viewModel = fixture.viewModel
        advanceUntilIdle()
        viewModel.handleEvent(SettingsEvent.RefreshFromProvider)
        advanceUntilIdle()
        viewModel.handleEvent(SettingsEvent.SelectModel(LLMModel.LocalQwen3_4B_Instruct_2507))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(LLMModel.LocalQwen3_4B_Instruct_2507, state.localModelDownloadPrompt?.model)
        assertEquals(
            listOf(localProfile.id, LocalEmbeddingProfiles.default().id),
            state.localModelDownloadPrompt?.downloads?.map { it.id },
        )
        assertNull(state.localModelDownloadState)
        verify(exactly = 1) { fixture.agentFacade.setModel(LLMModel.QwenMax) }
    }

    @Test
    fun `route refresh opens download prompt when persisted local model misses linked embeddings`() = runTest(dispatcher) {
        val fixture = createLocalModelSettingsFixture(
            gigaModel = LLMModel.LocalQwen3_4B_Instruct_2507,
            embeddingsModel = LocalEmbeddingProfiles.default().embeddingsModel,
            configureLocalModelStore = { localModelStore ->
                every { localModelStore.isPresent(LocalModelProfiles.QWEN3_4B_INSTRUCT_2507) } returns true
                every { localModelStore.isPresent(LocalEmbeddingProfiles.default()) } returns false
                every { localModelStore.modelPath(LocalEmbeddingProfiles.default()) } returns
                    File(System.getProperty("java.io.tmpdir"), LocalEmbeddingProfiles.default().ggufFilename).toPath()
            },
            configureAgentFacade = { agentFacade ->
                every { agentFacade.currentContext } returns MutableStateFlow(
                    AgentContext(
                        input = "",
                        settings = AgentSettings(
                            model = LLMModel.LocalQwen3_4B_Instruct_2507.alias,
                            provider = LLMModel.LocalQwen3_4B_Instruct_2507.provider,
                            temperature = 0f,
                            toolsByCategory = emptyMap(),
                        ),
                        history = emptyList(),
                        activeTools = emptyList(),
                        systemPrompt = "current-prompt",
                    )
                )
            },
        )
        val viewModel = fixture.viewModel
        advanceUntilIdle()
        viewModel.handleEvent(SettingsEvent.RefreshFromProvider)
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertEquals(LLMModel.LocalQwen3_4B_Instruct_2507, state.localModelDownloadPrompt?.model)
        assertEquals(listOf(LocalEmbeddingProfiles.default().id), state.localModelDownloadPrompt?.downloads?.map { it.id })
        verify(exactly = 0) { fixture.agentFacade.setModel(any()) }
    }
}
