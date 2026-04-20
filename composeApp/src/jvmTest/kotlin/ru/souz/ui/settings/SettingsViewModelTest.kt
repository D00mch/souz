@file:OptIn(ExperimentalCoroutinesApi::class)

package ru.souz.ui.settings

import io.mockk.every
import io.mockk.just
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import ru.souz.agent.AgentFacade
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.service.audio.Say
import ru.souz.db.DesktopInfoRepository
import ru.souz.db.SettingsProvider
import ru.souz.db.SettingsProviderImpl.Companion.REGION_RU
import ru.souz.llms.DEFAULT_MAX_TOKENS
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmBuildProfile
import ru.souz.llms.LlmProvider
import ru.souz.llms.VoiceRecognitionModel
import ru.souz.llms.local.LocalEmbeddingProfiles
import ru.souz.llms.local.LocalLlamaRuntime
import ru.souz.llms.local.LocalModelProfiles
import ru.souz.llms.local.LocalModelStore
import ru.souz.llms.local.LocalProviderAvailability
import ru.souz.service.telegram.TelegramAuthState
import ru.souz.service.telegram.TelegramAuthStep
import ru.souz.service.telegram.TelegramBotController
import ru.souz.service.telegram.TelegramPlatformSupport
import ru.souz.service.telegram.TelegramService
import ru.souz.tool.ToolRunBashCommand
import ru.souz.ui.common.usecases.ApiKeyAvailabilityUseCase
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkStatic("org.jetbrains.compose.resources.StringResourcesKt")
        coEvery { org.jetbrains.compose.resources.getString(any()) } answers { firstArg<Any>().toString() }
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `init normalizes unavailable llm, embeddings, and voice models to available providers`() = runTest(dispatcher) {
        mockkObject(ToolRunBashCommand)
        every { ToolRunBashCommand.sh(any()) } returns ""

        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.regionProfile } returns REGION_RU
        every { settingsProvider.regionProfile = any() } just runs
        val llmBuildProfile = LlmBuildProfile(settingsProvider)
        val apiKeyAvailabilityUseCase = ApiKeyAvailabilityUseCase(llmBuildProfile)

        val supportsSalute = llmBuildProfile.supportsSaluteSpeechRecognition
        val configuredVoiceRecognitionModel = if (supportsSalute) {
            VoiceRecognitionModel.OpenAIGpt4oTranscribe
        } else {
            VoiceRecognitionModel.SaluteSpeech
        }

        val configuredModel = llmBuildProfile.availableModels.first {
            it.provider != LlmProvider.QWEN && it.provider != LlmProvider.OPENAI
        }

        var embeddingsModelValue = EmbeddingsModel.GigaEmbeddings
        var voiceRecognitionModelValue = configuredVoiceRecognitionModel

        every { settingsProvider.gigaChatKey } returns ""
        every { settingsProvider.qwenChatKey } returns "qwen-key"
        every { settingsProvider.aiTunnelKey } returns ""
        every { settingsProvider.anthropicKey } returns ""
        every { settingsProvider.openaiKey } returns if (supportsSalute) "" else "openai-key"
        every { settingsProvider.saluteSpeechKey } returns if (supportsSalute) "salute-key" else ""
        every { settingsProvider.gigaModel } returns configuredModel
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
        every { agentFacade.activeAgentId } returns MutableStateFlow(ru.souz.agent.AgentId.LUA_GRAPH)
        every { agentFacade.availableAgents } returns listOf(ru.souz.agent.AgentId.LUA_GRAPH, ru.souz.agent.AgentId.GRAPH)

        val chatApi = mockk<LLMChatAPI>(relaxed = true)
        val telegramService = mockk<TelegramService>(relaxed = true)
        every { telegramService.authState } returns MutableStateFlow(TelegramAuthState(step = TelegramAuthStep.WAIT_PHONE))
        val localModelStore = mockk<LocalModelStore>(relaxed = true)
        val localLlamaRuntime = mockk<LocalLlamaRuntime>(relaxed = true)
        val desktopInfoRepository = mockk<DesktopInfoRepository>(relaxed = true)
        coEvery { desktopInfoRepository.rebuildIndexNow() } returns Unit

        val di = DI {
            bindSingleton<SettingsProvider> { settingsProvider }
            bindSingleton { desktopInfoRepository }
            bindSingleton<LlmBuildProfile> { llmBuildProfile }
            bindSingleton { localModelStore }
            bindSingleton { localLlamaRuntime }
            bindSingleton<ApiKeyAvailabilityUseCase> { apiKeyAvailabilityUseCase }
            bindSingleton<LLMChatAPI> { chatApi }
            bindSingleton<AgentFacade> { agentFacade }
            bindSingleton<TelegramPlatformSupport> { TelegramPlatformSupport }
            bindSingleton<TelegramService> { telegramService }
            bindSingleton<TelegramBotController> { mockk(relaxed = true) }
            bindSingleton<Say> { mockk(relaxed = true) }
        }

        val viewModel = SettingsViewModel(di)
        advanceUntilIdle()

        val expectedLlmModel = settingsProvider.defaultLlmModel(llmBuildProfile)
        assertNotNull(expectedLlmModel, "Expected at least one available llm model")
        val expectedEmbeddingsModel = settingsProvider.defaultEmbeddingsModel(llmBuildProfile)
        assertNotNull(expectedEmbeddingsModel, "Expected at least one available embeddings model")
        val expectedVoiceRecognitionModel = settingsProvider.defaultVoiceRecognitionModel(llmBuildProfile)
        assertNotNull(expectedVoiceRecognitionModel, "Expected at least one available voice recognition model")

        val state = viewModel.uiState.value
        assertEquals(expectedLlmModel, state.gigaModel)
        assertEquals(expectedEmbeddingsModel, state.embeddingsModel)
        assertEquals(expectedEmbeddingsModel, embeddingsModelValue)
        assertEquals(expectedVoiceRecognitionModel, state.voiceRecognitionModel)
        assertEquals(expectedVoiceRecognitionModel, voiceRecognitionModelValue)
        assertEquals("prompt-for-${expectedLlmModel.alias}", state.systemPrompt)

        verify(exactly = 1) { agentFacade.setModel(expectedLlmModel) }
        coVerify(exactly = 1) { desktopInfoRepository.rebuildIndexNow() }
    }

    @Test
    fun `selecting missing local model opens download prompt instead of switching immediately`() = runTest(dispatcher) {
        mockkObject(ToolRunBashCommand)
        every { ToolRunBashCommand.sh(any()) } returns ""

        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.regionProfile } returns REGION_RU
        every { settingsProvider.regionProfile = any() } just runs
        every { settingsProvider.qwenChatKey } returns "qwen-key"
        every { settingsProvider.gigaModel } returns LLMModel.QwenMax
        every { settingsProvider.embeddingsModel } returns EmbeddingsModel.GigaEmbeddings
        every { settingsProvider.voiceRecognitionModel } returns VoiceRecognitionModel.SaluteSpeech
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

        val localProviderAvailability = mockk<LocalProviderAvailability>(relaxed = true)
        every { localProviderAvailability.isProviderAvailable() } returns true
        every { localProviderAvailability.availableGigaModels() } returns listOf(LLMModel.LocalQwen3_4B_Instruct_2507)
        every { localProviderAvailability.defaultGigaModel() } returns LLMModel.LocalQwen3_4B_Instruct_2507
        val llmBuildProfile = LlmBuildProfile(settingsProvider, localProviderAvailability)
        val apiKeyAvailabilityUseCase = ApiKeyAvailabilityUseCase(llmBuildProfile)

        val localModelStore = mockk<LocalModelStore>(relaxed = true)
        val localLlamaRuntime = mockk<LocalLlamaRuntime>(relaxed = true)
        val localProfile = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507
        every { localModelStore.isPresent(localProfile) } returns false
        every { localModelStore.modelPath(localProfile) } returns File(System.getProperty("java.io.tmpdir"), localProfile.ggufFilename).toPath()

        val agentFacade = mockk<AgentFacade>(relaxed = true)
        every { agentFacade.setModel(any()) } answers { "prompt-for-${firstArg<LLMModel>().alias}" }
        every { agentFacade.activeAgentId } returns MutableStateFlow(ru.souz.agent.AgentId.LUA_GRAPH)
        every { agentFacade.availableAgents } returns listOf(ru.souz.agent.AgentId.LUA_GRAPH, ru.souz.agent.AgentId.GRAPH)

        val chatApi = mockk<LLMChatAPI>(relaxed = true)
        val telegramService = mockk<TelegramService>(relaxed = true)
        every { telegramService.authState } returns MutableStateFlow(TelegramAuthState(step = TelegramAuthStep.WAIT_PHONE))
        val desktopInfoRepository = mockk<DesktopInfoRepository>(relaxed = true)
        coEvery { desktopInfoRepository.rebuildIndexNow() } returns Unit

        val di = DI {
            bindSingleton<SettingsProvider> { settingsProvider }
            bindSingleton { desktopInfoRepository }
            bindSingleton<LlmBuildProfile> { llmBuildProfile }
            bindSingleton { localModelStore }
            bindSingleton { localLlamaRuntime }
            bindSingleton<ApiKeyAvailabilityUseCase> { apiKeyAvailabilityUseCase }
            bindSingleton<LLMChatAPI> { chatApi }
            bindSingleton<AgentFacade> { agentFacade }
            bindSingleton<TelegramPlatformSupport> { TelegramPlatformSupport }
            bindSingleton<TelegramService> { telegramService }
            bindSingleton<TelegramBotController> { mockk(relaxed = true) }
            bindSingleton<Say> { mockk(relaxed = true) }
        }

        val viewModel = SettingsViewModel(di)
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
        verify(exactly = 1) { agentFacade.setModel(LLMModel.QwenMax) }
    }

    @Test
    fun `init opens download prompt when persisted local model misses linked embeddings`() = runTest(dispatcher) {
        mockkObject(ToolRunBashCommand)
        every { ToolRunBashCommand.sh(any()) } returns ""

        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.regionProfile } returns REGION_RU
        every { settingsProvider.regionProfile = any() } just runs
        every { settingsProvider.qwenChatKey } returns "qwen-key"
        every { settingsProvider.gigaModel } returns LLMModel.LocalQwen3_4B_Instruct_2507
        every { settingsProvider.embeddingsModel } returns LocalEmbeddingProfiles.default().embeddingsModel
        every { settingsProvider.voiceRecognitionModel } returns VoiceRecognitionModel.SaluteSpeech
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

        val localProviderAvailability = mockk<LocalProviderAvailability>(relaxed = true)
        every { localProviderAvailability.isProviderAvailable() } returns true
        every { localProviderAvailability.availableGigaModels() } returns listOf(LLMModel.LocalQwen3_4B_Instruct_2507)
        every { localProviderAvailability.defaultGigaModel() } returns LLMModel.LocalQwen3_4B_Instruct_2507
        val llmBuildProfile = LlmBuildProfile(settingsProvider, localProviderAvailability)
        val apiKeyAvailabilityUseCase = ApiKeyAvailabilityUseCase(llmBuildProfile)

        val localModelStore = mockk<LocalModelStore>(relaxed = true)
        val localLlamaRuntime = mockk<LocalLlamaRuntime>(relaxed = true)
        every { localModelStore.isPresent(LocalModelProfiles.QWEN3_4B_INSTRUCT_2507) } returns true
        every { localModelStore.isPresent(LocalEmbeddingProfiles.default()) } returns false
        every { localModelStore.modelPath(LocalEmbeddingProfiles.default()) } returns
            File(System.getProperty("java.io.tmpdir"), LocalEmbeddingProfiles.default().ggufFilename).toPath()

        val agentFacade = mockk<AgentFacade>(relaxed = true)
        every { agentFacade.setModel(any()) } answers { "prompt-for-${firstArg<LLMModel>().alias}" }
        every { agentFacade.currentContext } returns MutableStateFlow(
            AgentContext(
                input = "",
                settings = AgentSettings(
                    model = LLMModel.LocalQwen3_4B_Instruct_2507.alias,
                    temperature = 0f,
                    toolsByCategory = emptyMap(),
                ),
                history = emptyList(),
                activeTools = emptyList(),
                systemPrompt = "current-prompt",
            )
        )
        every { agentFacade.activeAgentId } returns MutableStateFlow(ru.souz.agent.AgentId.LUA_GRAPH)
        every { agentFacade.availableAgents } returns listOf(ru.souz.agent.AgentId.LUA_GRAPH, ru.souz.agent.AgentId.GRAPH)

        val chatApi = mockk<LLMChatAPI>(relaxed = true)
        val telegramService = mockk<TelegramService>(relaxed = true)
        every { telegramService.authState } returns MutableStateFlow(TelegramAuthState(step = TelegramAuthStep.WAIT_PHONE))
        val desktopInfoRepository = mockk<DesktopInfoRepository>(relaxed = true)
        coEvery { desktopInfoRepository.rebuildIndexNow() } returns Unit

        val di = DI {
            bindSingleton<SettingsProvider> { settingsProvider }
            bindSingleton { desktopInfoRepository }
            bindSingleton<LlmBuildProfile> { llmBuildProfile }
            bindSingleton { localModelStore }
            bindSingleton { localLlamaRuntime }
            bindSingleton<ApiKeyAvailabilityUseCase> { apiKeyAvailabilityUseCase }
            bindSingleton<LLMChatAPI> { chatApi }
            bindSingleton<AgentFacade> { agentFacade }
            bindSingleton<TelegramPlatformSupport> { TelegramPlatformSupport }
            bindSingleton<TelegramService> { telegramService }
            bindSingleton<TelegramBotController> { mockk(relaxed = true) }
            bindSingleton<Say> { mockk(relaxed = true) }
        }

        val viewModel = SettingsViewModel(di)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(LLMModel.LocalQwen3_4B_Instruct_2507, state.localModelDownloadPrompt?.model)
        assertEquals(listOf(LocalEmbeddingProfiles.default().id), state.localModelDownloadPrompt?.downloads?.map { it.id })
        verify(exactly = 0) { agentFacade.setModel(any()) }
    }
}
