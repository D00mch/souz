package ru.souz.di

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import ru.souz.agent.agentDiModule
import ru.souz.db.SettingsProviderImpl
import ru.souz.agent.spi.AgentDesktopInfoRepository
import ru.souz.agent.spi.AgentErrorMessages
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.agent.spi.DefaultBrowserProvider
import ru.souz.agent.spi.McpToolProvider
import ru.souz.service.audio.ActiveSoundRecorderImpl
import ru.souz.service.audio.InMemoryAudioRecorder
import ru.souz.service.audio.Say
import ru.souz.db.ConfigStore
import ru.souz.db.DesktopDataExtractor
import ru.souz.db.DesktopInfoRepository
import ru.souz.db.SettingsProvider
import ru.souz.db.VectorDB
import ru.souz.llms.ApiClassifier
import ru.souz.llms.giga.GigaAuth
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMFactory
import ru.souz.llms.giga.GigaRestChatAPI
import ru.souz.llms.giga.GigaVoiceAPI
import ru.souz.llms.LlmBuildProfile
import ru.souz.llms.SessionTokenLogging
import ru.souz.llms.TokenLogging
import ru.souz.service.keys.Keys
import ru.souz.llms.tunnel.AiTunnelChatAPI
import ru.souz.llms.tunnel.AiTunnelVoiceAPI
import ru.souz.llms.anthropic.AnthropicChatAPI
import ru.souz.llms.openai.OpenAIChatAPI
import ru.souz.llms.openai.OpenAIVoiceAPI
import ru.souz.llms.qwen.QwenChatAPI
import ru.souz.llms.local.LocalBridgeLoader
import ru.souz.llms.local.LocalChatAPI
import ru.souz.llms.local.LocalHostInfoProvider
import ru.souz.llms.local.LocalLlamaRuntime
import ru.souz.llms.local.LocalModelStore
import ru.souz.llms.local.LocalNativeBridge
import ru.souz.llms.local.LocalPromptRenderer
import ru.souz.llms.local.LocalProviderAvailability
import ru.souz.llms.local.LocalStrictJsonParser
import ru.souz.service.mcp.McpClientManager
import ru.souz.service.mcp.McpConfigProvider
import ru.souz.service.telegram.TelegramService
import ru.souz.service.telegram.TelegramBotController
import ru.souz.service.telegram.TelegramPlatformSupport
import ru.souz.service.files.FilesService
import ru.souz.service.telemetry.TelemetryOutboxRepository
import ru.souz.service.telemetry.TelemetryCryptoService
import ru.souz.service.telemetry.TelemetryRuntimeConfig
import ru.souz.service.telemetry.TelemetryService
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
import ru.souz.ui.main.usecases.MainUseCasesFactory
import ru.souz.ui.main.usecases.AiTunnelSpeechRecognitionProvider
import ru.souz.ui.main.usecases.FinderPathExtractor
import ru.souz.ui.main.usecases.ModelAwareSpeechRecognitionProvider
import ru.souz.ui.main.usecases.OpenAISpeechRecognitionProvider
import ru.souz.ui.main.usecases.SaluteSpeechRecognitionProvider
import ru.souz.ui.main.usecases.SpeechRecognitionProvider
import ru.souz.ui.common.usecases.ApiKeyAvailabilityUseCase
import ru.souz.tool.presentation.ToolPresentationCreate
import ru.souz.tool.presentation.ToolPresentationRead
import ru.souz.tool.telegram.ToolTelegramForward
import ru.souz.tool.telegram.TelegramChatSelectionBroker
import ru.souz.tool.telegram.TelegramContactSelectionBroker
import ru.souz.tool.telegram.TelegramChatSelectionApprovalSource
import ru.souz.tool.telegram.TelegramContactSelectionApprovalSource
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
import ru.souz.ui.common.ComposeAgentErrorMessages
import java.nio.file.Path

private object DiTags {
    const val MODULE_MAIN = "main"

    const val TAG_LOG = "log"
    const val TAG_API = "api"
    const val TAG_LOCAL = "local"
}

val mainDiModule = DI.Module(DiTags.MODULE_MAIN) {
    // utils
    bindSingleton(tag = DiTags.TAG_LOG) {
        jacksonObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(SerializationFeature.INDENT_OUTPUT)
    }
    bindSingleton { Say() }
    bindSingleton { InMemoryAudioRecorder(ActiveSoundRecorderImpl()) }

    // Native
    bindSingleton { Keys() }

    // DB
    bindSingleton { ConfigStore }
    bindSingleton { VectorDB }
    bindSingleton { TelegramPlatformSupport }
    bindSingleton { LocalHostInfoProvider() }
    bindSingleton { LocalModelStore() }
    bindSingleton { LocalBridgeLoader(instance()) }
    bindSingleton { LocalNativeBridge(instance()) }
    bindSingleton { LocalPromptRenderer() }
    bindSingleton { LocalStrictJsonParser() }
    bindSingleton { LocalProviderAvailability(instance(), instance(), instance()) }
    bindSingleton { SettingsProviderImpl(instance(), instance()) }
    bindSingleton<SettingsProvider> { instance<SettingsProviderImpl>() }
    bindSingleton<AgentSettingsProvider> { instance<SettingsProviderImpl>() }
    bindSingleton { LlmBuildProfile(instance(), instance()) }
    bindSingleton { ApiKeyAvailabilityUseCase(instance()) }
    bindSingleton { DesktopInfoRepository(instance(), instance(), instance(), instance()) }
    bindSingleton<AgentDesktopInfoRepository> { instance<DesktopInfoRepository>() }
    bindSingleton { ToolsSettings(instance(), instance(), instance(), instance()) }
    bindSingleton<AgentToolsFilter> { instance<ToolsSettings>() }
    bindSingleton { FilesToolUtil(instance()) }
    bindSingleton<FilesService> { instance<FilesToolUtil>() }
    bindSingleton { ToolPermissionBroker(instance()) }
    bindSingleton { TelegramContactSelectionBroker() }
    bindSingleton { TelegramChatSelectionBroker() }
    bindSingleton { TelegramContactSelectionApprovalSource(instance()) }
    bindSingleton { TelegramChatSelectionApprovalSource(instance()) }
    bindSingleton<Set<SelectionApprovalSource>> {
        setOf(
            instance<TelegramContactSelectionApprovalSource>(),
            instance<TelegramChatSelectionApprovalSource>(),
        )
    }
    bindSingleton { TelegramService(instance()) }
    bindSingleton<DefaultBrowserProvider> { DefaultBrowserProviderImpl }
    bindSingleton<AgentErrorMessages> { ComposeAgentErrorMessages() }

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
    bindSingleton { ToolCalendarListEvents() }
    bindSingleton { ToolMailUnreadMessagesCount(instance()) }
    bindSingleton { ToolMailListMessages(instance()) }
    bindSingleton { ToolMailReadMessage(instance()) }
    bindSingleton { ToolMailReplyMessage(instance()) }
    bindSingleton { ToolMailSendNewMessage(instance()) }
    bindSingleton { ToolMailSearch(instance()) }
    bindSingleton { ToolTextReplace(instance()) }
    bindSingleton { ToolTextUnderSelection(instance(), instance()) }
    bindSingleton { ToolFindFolders(instance(), instance()) }
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
    bindSingleton { ToolPresentationRead() }
    bindSingleton { ToolTelegramReadInbox(instance()) }
    bindSingleton { ToolTelegramGetHistory(instance(), instance()) }
    bindSingleton { ToolTelegramSetState(instance(), instance(), instance()) }
    bindSingleton { ToolTelegramSend(instance(), instance(), instance()) }
    bindSingleton { ToolTelegramForward(instance(), instance(), instance()) }
    bindSingleton { ToolTelegramSearch(instance()) }
    bindSingleton { ToolTelegramSavedMessages(instance()) }

    bindSingleton { DesktopDataExtractor(instance(), instance()) }
    bindSingleton {
        TelemetryOutboxRepository(
            databasePath = Path.of(FilesToolUtil.homeDirectory.absolutePath, ".local", "state", "souz", "telemetry.db"),
            objectMapper = instance(DiTags.TAG_LOG),
        )
    }
    bindSingleton { TelemetryCryptoService() }
    bindSingleton { TelemetryRuntimeConfig.production() }
    bindSingleton { TelemetryService(instance(), instance(), instance(), instance()) }
    bindSingleton<AgentTelemetry> { instance<TelemetryService>() }

    // API
    bindSingleton { GigaAuth(instance()) }
    bindSingleton<TokenLogging> {
        SessionTokenLogging(logObjectMapper = instance(DiTags.TAG_LOG))
    }
    bindSingleton<GigaRestChatAPI> {
        GigaRestChatAPI(instance(), instance(), instance())
    }
    bindSingleton<QwenChatAPI> { QwenChatAPI(instance(), instance()) }
    bindSingleton<AiTunnelChatAPI> { AiTunnelChatAPI(instance(), instance()) }
    bindSingleton<AnthropicChatAPI> { AnthropicChatAPI(instance(), instance()) }
    bindSingleton<OpenAIChatAPI> { OpenAIChatAPI(instance(), instance()) }
    bindSingleton { LocalLlamaRuntime(instance(), instance(), instance(), instance(), instance()) }
    bindSingleton<LocalChatAPI> { LocalChatAPI(instance()) }
    bindSingleton { LLMFactory(instance(), instance(), instance(), instance(), instance(), instance(), instance()) }
    bindSingleton<LLMChatAPI> { instance<LLMFactory>() }
    bindSingleton { GigaVoiceAPI(instance(), instance()) }
    bindSingleton { OpenAIVoiceAPI(instance()) }
    bindSingleton { AiTunnelVoiceAPI(instance()) }
    bindSingleton { SaluteSpeechRecognitionProvider(instance(), instance()) }
    bindSingleton { OpenAISpeechRecognitionProvider(instance(), instance()) }
    bindSingleton { AiTunnelSpeechRecognitionProvider(instance(), instance()) }
    bindSingleton<SpeechRecognitionProvider> {
        ModelAwareSpeechRecognitionProvider(instance(), instance(), instance(), instance())
    }
    bindSingleton(tag = DiTags.TAG_API) { ApiClassifier(instance()) }
    bindSingleton(tag = DiTags.TAG_LOCAL) { LocalRegexClassifier }

    bindSingleton { ToolsFactory(di) }
    bindSingleton<AgentToolCatalog> { instance<ToolsFactory>() }
    import(
        agentDiModule(
            logObjectMapperTag = DiTags.TAG_LOG,
            apiClassifierTag = DiTags.TAG_API,
            localClassifierTag = DiTags.TAG_LOCAL,
        )
    )
    bindSingleton { TelegramBotController(instance(), instance(), speechRecognitionProvider = instance()) }
    bindSingleton { FinderPathExtractor(instance()) }
    bindSingleton { MainUseCasesFactory(instance(), instance(), instance(), instance(), instance(), instance(), instance(), instance(), instance(), instance()) }
    bindSingleton { McpConfigProvider(instance()) }
    bindSingleton { McpClientManager(instance()) }
    bindSingleton<McpToolProvider> { instance<McpClientManager>() }
}
