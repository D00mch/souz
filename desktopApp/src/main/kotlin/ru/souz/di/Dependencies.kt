package ru.souz.di

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import ru.souz.ambient.AmbientBlockAnalyzer
import ru.souz.ambient.AmbientLocalLlm
import ru.souz.ambient.AmbientTranscriptionService
import ru.souz.ambient.DefaultAmbientTranscriptionService
import ru.souz.ambient.LocalChatAmbientLocalLlm
import ru.souz.ambient.LocalLlmAmbientBlockAnalyzer
import ru.souz.ambient.PcmAudioFrameSource
import ru.souz.ambient.selectAmbientLocalModel
import ru.souz.paths.SouzPaths
import ru.souz.agent.agentDiModule
import ru.souz.agent.spi.AgentDesktopInfoRepository
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.agent.spi.DefaultBrowserProvider
import ru.souz.agent.spi.McpToolProvider
import ru.souz.agent.spi.SkillToolBindingTags
import ru.souz.service.audio.ActiveSoundRecorderImpl
import ru.souz.service.audio.ActiveRecorderPcmAudioFrameSource
import ru.souz.service.audio.ActiveSoundRecorder
import ru.souz.service.audio.InMemoryAudioRecorder
import ru.souz.service.audio.Say
import ru.souz.db.ConfigStore
import ru.souz.db.DesktopDataExtractor
import ru.souz.db.DesktopInfoRepository
import ru.souz.db.SettingsProvider
import ru.souz.db.VectorDB
import ru.souz.llms.giga.GigaVoiceAPI
import ru.souz.llms.giga.toGiga
import ru.souz.llms.LlmBuildProfile
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.LlmProvider
import ru.souz.llms.local.LocalChatAPI
import ru.souz.service.keys.Keys
import ru.souz.llms.tunnel.AiTunnelVoiceAPI
import ru.souz.llms.openai.OpenAIVoiceAPI
import ru.souz.llms.runtime.ApiClassifier
import ru.souz.runtime.sandbox.DefaultRuntimeSandboxFactory
import ru.souz.runtime.sandbox.RuntimeSandboxFactory
import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.SandboxCommandExecutor
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.service.mcp.McpClientManager
import ru.souz.service.mcp.McpConfigProvider
import ru.souz.service.observability.DesktopStructuredLogger
import ru.souz.service.observability.StructuredLoggingAgentTelemetry
import ru.souz.service.permissions.MacDesktopPermissionService
import ru.souz.service.telegram.TelegramService
import ru.souz.service.telegram.TelegramBotController
import ru.souz.tool.*
import ru.souz.tool.application.*
import ru.souz.tool.browser.*
import ru.souz.tool.calendar.*
import ru.souz.tool.config.*
import ru.souz.tool.dataAnalytics.*
import ru.souz.tool.dataAnalytics.excel.ExcelReport
import ru.souz.tool.dataAnalytics.excel.ExcelRead
import ru.souz.tool.desktop.*
import ru.souz.tool.files.*
import ru.souz.tool.mail.*
import ru.souz.tool.notes.*
import ru.souz.tool.textReplace.*
import ru.souz.tool.math.ToolCalculator
import ru.souz.ui.main.usecases.FinderPathExtractor
import ru.souz.ui.common.usecases.ApiKeyAvailabilityUseCase
import ru.souz.service.speech.AiTunnelSpeechRecognitionProvider
import ru.souz.service.speech.LiveSpeechTranscriptionProvider
import ru.souz.service.speech.MacOsSpeechAnalyzerLiveTranscriptionProvider
import ru.souz.service.speech.MacOsSpeechBridge
import ru.souz.service.speech.MacOsSpeechRecognitionProvider
import ru.souz.service.speech.ModelAwareSpeechRecognitionProvider
import ru.souz.service.speech.OpenAISpeechRecognitionProvider
import ru.souz.service.speech.SaluteSpeechRecognitionProvider
import ru.souz.service.speech.SpeechRecognitionProvider
import ru.souz.service.telegram.TelegramChatSelectionBroker
import ru.souz.service.telegram.TelegramContactSelectionBroker
import ru.souz.tool.presentation.ToolPresentationCreate
import ru.souz.tool.presentation.ToolPresentationRead
import ru.souz.tool.telegram.ToolTelegramForward
import ru.souz.tool.telegram.ToolTelegramGetHistory
import ru.souz.tool.telegram.ToolTelegramReadInbox
import ru.souz.tool.telegram.ToolTelegramSavedMessages
import ru.souz.tool.telegram.ToolTelegramSearch
import ru.souz.tool.telegram.ToolTelegramSend
import ru.souz.tool.telegram.ToolTelegramSetState
import ru.souz.tool.web.ToolInternetSearch
import ru.souz.tool.web.ToolInternetResearch
import ru.souz.tool.web.ToolWebImageSearch
import ru.souz.tool.web.ToolWebPageText
import ru.souz.tool.web.internal.WebImageDownloader
import ru.souz.tool.web.internal.WebResearchClient
import ru.souz.runtime.di.runtimeCoreDiModule
import ru.souz.runtime.di.runtimeLlmDiModule
import ru.souz.runtime.files.FilesToolUtil
import ru.souz.skills.registry.SkillStorageScope
import ru.souz.tool.skills.ToolRunSkillCommand
import ru.souz.memory.ConversationMemoryRuntime
import ru.souz.memory.DesktopConversationMemoryRuntime
import ru.souz.memory.EmbeddingClient
import ru.souz.memory.LlmEmbeddingClient
import ru.souz.memory.LlmMemoryWriter
import ru.souz.memory.MemoryCaptureService
import ru.souz.memory.MemoryRepository
import ru.souz.memory.MemoryService
import ru.souz.memory.MemoryWriter
import ru.souz.memory.SqliteMemoryRepository
import ru.souz.ui.host.CalendarListProvider
import ru.souz.ui.host.BackgroundIndexRefresher
import ru.souz.ui.host.PermissionPromptService
import ru.souz.ui.host.TelegramControlBot
import ru.souz.ui.host.TelegramUiService
import ru.souz.ui.host.UiAudioRecorder
import ru.souz.ui.host.UiSpeechPlayer

private object DiTags {
    const val MODULE_MAIN = "main"

    const val TAG_LOG = "log"
    const val TAG_API = "api"
    const val TAG_LOCAL = "local"
}

val mainDiModule = DI.Module(DiTags.MODULE_MAIN) {
    import(runtimeCoreDiModule())
    import(runtimeLlmDiModule(logObjectMapperTag = DiTags.TAG_LOG))
    import(sharedUiDiModule(), allowOverride = true)

    // utils
    bindSingleton(tag = DiTags.TAG_LOG) {
        jacksonObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(SerializationFeature.INDENT_OUTPUT)
    }
    bindSingleton { Say() }
    bindSingleton<UiSpeechPlayer>(overrides = true) { instance<Say>() }
    bindSingleton<ActiveSoundRecorder> { ActiveSoundRecorderImpl() }
    bindSingleton { InMemoryAudioRecorder(instance()) }
    bindSingleton<UiAudioRecorder>(overrides = true) { instance<InMemoryAudioRecorder>() }
    bindSingleton<PcmAudioFrameSource> { ActiveRecorderPcmAudioFrameSource(instance()) }
    bindSingleton<PermissionPromptService>(overrides = true) { MacDesktopPermissionService() }

    // DB
    bindSingleton<CoroutineScope> {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
    bindSingleton { Keys() }

    // DB
    bindSingleton { VectorDB }
    bindSingleton { LlmBuildProfile(instance(), instance()) }
    bindSingleton { DesktopInfoRepository(instance(), instance(), instance(), instance()) }
    bindSingleton<MemoryRepository> {
        SqliteMemoryRepository(instance<SouzPaths>().stateRoot.resolve("memory.db"))
    }
    bindSingleton<EmbeddingClient> { LlmEmbeddingClient(instance(), instance()) }
    bindSingleton<MemoryWriter> { LlmMemoryWriter(instance(), instance()) }
    bindSingleton { MemoryService(instance(), instance()) }
    bindSingleton { MemoryCaptureService(instance(), instance()) }
    bindSingleton<ConversationMemoryRuntime> { DesktopConversationMemoryRuntime(instance(), instance()) }
    bindSingleton<AgentDesktopInfoRepository> { instance<DesktopInfoRepository>() }
    bindSingleton<BackgroundIndexRefresher>(overrides = true) { instance<DesktopInfoRepository>() }
    bindSingleton<ToolAvailabilityPolicy> { DesktopToolAvailabilityPolicy(instance()) }
    bindSingleton { ToolsSettings(instance(), instance(), instance()) }
    bindSingleton<AgentToolsFilter> { instance<ToolsSettings>() }
    bindSingleton<RuntimeSandboxFactory> { DefaultRuntimeSandboxFactory(settingsProvider = instance()) }
    bindSingleton<RuntimeSandbox> { instance<RuntimeSandboxFactory>().create(SandboxScope.localDefault()) }
    bindSingleton<ToolInvocationRuntimeSandboxResolver> {
        ToolInvocationRuntimeSandboxResolver.fixed(instance())
    }
    bindSingleton<SandboxFileSystem> { instance<RuntimeSandbox>().fileSystem }
    bindSingleton<SandboxCommandExecutor> { instance<RuntimeSandbox>().commandExecutor }
    bindSingleton { FilesToolUtil(instance<ToolInvocationRuntimeSandboxResolver>()) }
    bindSingleton<ToolPermissionBroker> { ImmediateToolPermissionBroker(instance()) }
    bindSingleton { DeferredToolModifyPermissionBroker(instance(), instance()) }
    bindSingleton { TelegramContactSelectionBroker() }
    bindSingleton { TelegramChatSelectionBroker() }
    bindSingleton { TelegramService() }
    bindSingleton<TelegramUiService> { instance<TelegramService>() }
    bindSingleton<DefaultBrowserProvider> { DefaultBrowserProviderImpl }

    // Tools
    bindSingleton { ToolRunBashCommand }
    bindSingleton { ToolGetClipboard() }
    bindSingleton { ToolListFiles(instance()) }
    bindSingleton { ToolFindInFiles(instance()) }
    bindSingleton { ToolNewFile(instance()) }
    bindSingleton { ToolDeleteFile(instance(), instance()) }
    bindSingleton { ToolModifyFile(instance(), instance()) }
    bindSingleton { ToolMoveFile(instance(), instance()) }
    bindSingleton { ToolExtractText(instance()) }
    bindSingleton { ToolFindFilesByName(instance()) }
    bindSingleton { ToolReadPdfPages(instance()) }
    bindSingleton { ToolViewImage(filesToolUtil = instance(), visionGateway = instance()) }
    bindSingleton { ToolGenerateImage(filesToolUtil = instance(), imageGenerationGateway = instance()) }
    bindSingleton { ToolOpen(instance(), instance()) }
    bindSingleton { ToolCreateNewBrowserTab(instance()) }
    bindSingleton { ToolSafariInfo(instance()) }
    bindSingleton { ToolBrowserHotkeys(instance()) }
    bindSingleton { ToolFocusOnTab(instance()) }
    bindSingleton { ToolChromeInfo(instance()) }
    bindSingleton { ToolOpenDefaultBrowser(instance(), instance()) }
    bindSingleton { ToolSoundConfig(ConfigStore) }
    bindSingleton { ToolSoundConfigDiff(ConfigStore) }
    bindSingleton { ToolInstructionStore(ConfigStore, instance()) }
    bindSingleton { ToolOpenNote(instance()) }
    bindSingleton { ToolCreateNote(instance(), instance()) }
    bindSingleton { ToolDeleteNote(instance(), instance()) }
    bindSingleton { ToolListNotes(instance()) }
    bindSingleton { ToolSearchNotes(instance()) }
    bindSingleton { ToolShowApps(instance(), instance()) }
    bindSingleton { ToolCreatePlotFromCsv(instance()) }
    bindSingleton { ToolCalendarCreateEvent(instance()) }
    bindSingleton { ToolCalendarDeleteEvent(instance()) }
    bindSingleton { ToolCalendarListCalendars(instance()) }
    bindSingleton<CalendarListProvider>(overrides = true) {
        {
            ToolRunBashCommand.sh(CalendarAppleScriptCommands.listCalendarsCommand(""))
                .lines()
                .asSequence()
                .map { it.trim() }
                .filter { it.startsWith("- ") }
                .map { it.removePrefix("- ").trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
                .toList()
        }
    }
    bindSingleton { ToolCalendarListEvents() }
    bindSingleton { ToolMailUnreadMessagesCount(instance()) }
    bindSingleton { ToolMailListMessages(instance()) }
    bindSingleton { ToolMailReadMessage(instance()) }
    bindSingleton { ToolMailReplyMessage(instance()) }
    bindSingleton { ToolMailSendNewMessage(instance()) }
    bindSingleton { ToolMailSearch(instance()) }
    bindSingleton { ToolTextReplace(instance()) }
    bindSingleton { ToolTextUnderSelection(instance(), instance()) }
    bindSingleton { ToolFindFolders(instance()) }
    bindSingleton { ToolUploadFile(instance()) }
    bindSingleton { ToolDownloadFile(instance()) }
    bindSingleton { ToolTakeScreenshot(instance()) }
    bindSingleton { ToolStartScreenRecording(instance()) }
    bindSingleton { ToolCalculator() }
    bindSingleton { ExcelRead(instance()) }
    bindSingleton { ExcelReport(instance()) }
    bindSingleton { WebResearchClient() }
    bindSingleton { WebImageDownloader(instance()) }
    bindSingleton { ToolInternetSearch(api = instance(), settingsProvider = instance(), filesToolUtil = instance(), webResearchClient = instance()) }
    bindSingleton { ToolInternetResearch(api = instance(), settingsProvider = instance(), filesToolUtil = instance(), webResearchClient = instance()) }
    bindSingleton { ToolWebImageSearch(filesToolUtil = instance(), webResearchClient = instance(), webImageDownloader = instance()) }
    bindSingleton { ToolWebPageText(webResearchClient = instance()) }
    bindSingleton { ToolPresentationCreate(filesToolUtil = instance(), webImageDownloader = instance()) }
    bindSingleton { ToolPresentationRead(instance()) }
    bindSingleton { ToolTelegramReadInbox(instance()) }
    bindSingleton { ToolTelegramGetHistory(instance(), instance()) }
    bindSingleton { ToolTelegramSetState(instance(), instance(), instance()) }
    bindSingleton { ToolTelegramSend(instance(), instance(), instance()) }
    bindSingleton { ToolTelegramForward(instance(), instance(), instance()) }
    bindSingleton { ToolTelegramSearch(instance()) }
    bindSingleton { ToolTelegramSavedMessages(instance()) }

    bindSingleton { DesktopDataExtractor(instance(), instance()) }
    bindSingleton { DesktopStructuredLogger() }
    bindSingleton<AgentTelemetry> { StructuredLoggingAgentTelemetry() }

    // API
    bindSingleton { GigaVoiceAPI(instance(), instance()) }
    bindSingleton { OpenAIVoiceAPI(instance()) }
    bindSingleton { AiTunnelVoiceAPI(instance()) }
    bindSingleton { MacOsSpeechBridge() }
    bindSingleton<LiveSpeechTranscriptionProvider> { MacOsSpeechAnalyzerLiveTranscriptionProvider(instance()) }
    bindSingleton<AmbientTranscriptionService>(overrides = true) {
        DefaultAmbientTranscriptionService(
            liveSpeechProvider = instance(),
            batchSpeechRecognitionProvider = instance(),
            audioSource = instance(),
            scope = instance(),
        )
    }
    bindSingleton<AmbientLocalLlm> {
        val buildProfile = instance<LlmBuildProfile>()
        val settingsProvider = instance<SettingsProvider>()
        LocalChatAmbientLocalLlm(instance<LocalChatAPI>()) {
            selectAmbientLocalModel(
                configuredModel = settingsProvider.ambientAnalysisModel,
                isModelAvailable = buildProfile::isModelAvailable,
                defaultLocalModel = { buildProfile.defaultModelForProvider(LlmProvider.LOCAL) },
            )
        }
    }
    bindSingleton<AmbientBlockAnalyzer>(overrides = true) { LocalLlmAmbientBlockAnalyzer(instance()) }
    bindSingleton { SaluteSpeechRecognitionProvider(instance(), instance()) }
    bindSingleton { OpenAISpeechRecognitionProvider(instance(), instance()) }
    bindSingleton { AiTunnelSpeechRecognitionProvider(instance(), instance()) }
    bindSingleton { MacOsSpeechRecognitionProvider(instance(), instance(), liveSpeechProvider = instance()) }
    bindSingleton<SpeechRecognitionProvider> {
        ModelAwareSpeechRecognitionProvider(instance(), instance(), instance(), instance(), instance())
    }
    bindSingleton(tag = DiTags.TAG_API) { ApiClassifier(instance()) }
    bindSingleton(tag = DiTags.TAG_LOCAL) { LocalRegexClassifier }

    bindSingleton { ToolsFactory(di) }
    bindSingleton<AgentToolCatalog> { instance<ToolsFactory>() }
    bindSingleton<LLMToolSetup>(tag = SkillToolBindingTags.COMMAND_TOOL) {
        ToolRunSkillCommand(
            sandboxResolver = instance(),
            skillStorageScope = SkillStorageScope.SINGLE_USER,
        ).toGiga()
    }
    import(
        agentDiModule(
            logObjectMapperTag = DiTags.TAG_LOG,
            apiClassifierTag = DiTags.TAG_API,
            localClassifierTag = DiTags.TAG_LOCAL,
            skillCommandToolTag = SkillToolBindingTags.COMMAND_TOOL,
        )
    )
    bindSingleton { TelegramBotController(instance(), instance(), speechRecognitionProvider = instance()) }
    bindSingleton<TelegramControlBot> { instance<TelegramBotController>() }
    bindSingleton { McpConfigProvider(instance()) }
    bindSingleton { McpClientManager(instance()) }
    bindSingleton<McpToolProvider> { instance<McpClientManager>() }
}
