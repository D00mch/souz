package ru.souz.di

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ru.souz.db.SettingsProviderImpl
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import ru.souz.agent.runtime.AgentToolExecutor
import ru.souz.agent.AgentFacade
import ru.souz.agent.impl.GraphBasedAgent
import ru.souz.agent.impl.LuaGraphBasedAgent
import ru.souz.agent.SystemPromptResolver
import ru.souz.agent.runtime.LuaRuntime
import ru.souz.agent.nodes.NodesErrorHandling
import ru.souz.agent.nodes.NodesCommon
import ru.souz.agent.nodes.NodesLLM
import ru.souz.agent.nodes.NodesLua
import ru.souz.agent.nodes.NodesSummarization
import ru.souz.agent.nodes.NodesClassification
import ru.souz.agent.nodes.NodesMCP
import ru.souz.agent.session.GraphSessionRepository
import ru.souz.agent.session.GraphSessionService
import ru.souz.audio.ActiveSoundRecorderImpl
import ru.souz.audio.InMemoryAudioRecorder
import ru.souz.audio.Say
import ru.souz.db.ConfigStore
import ru.souz.db.DesktopDataExtractor
import ru.souz.db.DesktopInfoRepository
import ru.souz.db.SettingsProvider
import ru.souz.db.VectorDB
import ru.souz.giga.ApiClassifier
import ru.souz.giga.GigaAuth
import ru.souz.giga.GigaChatAPI
import ru.souz.giga.LLMFactory
import ru.souz.giga.GigaRestChatAPI
import ru.souz.giga.GigaVoiceAPI
import ru.souz.giga.LlmBuildProfile
import ru.souz.giga.SessionTokenLogging
import ru.souz.giga.TokenLogging
import ru.souz.keys.Keys
import ru.souz.llms.AiTunnelChatAPI
import ru.souz.llms.AiTunnelVoiceAPI
import ru.souz.llms.AnthropicChatAPI
import ru.souz.llms.OpenAIChatAPI
import ru.souz.llms.OpenAIVoiceAPI
import ru.souz.llms.QwenChatAPI
import ru.souz.mcp.McpClientManager
import ru.souz.mcp.McpConfigProvider
import ru.souz.service.telegram.TelegramService
import ru.souz.service.telegram.TelegramBotController
import ru.souz.service.telegram.TelegramPlatformSupport
import ru.souz.service.files.FilesService
import ru.souz.telemetry.TelemetryOutboxRepository
import ru.souz.telemetry.TelemetryCryptoService
import ru.souz.telemetry.TelemetryRuntimeConfig
import ru.souz.telemetry.TelemetryService
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
import ru.souz.tool.web.ToolWebImageSearch
import ru.souz.tool.web.ToolWebPageText
import ru.souz.tool.web.ToolWebSearch
import ru.souz.tool.web.internal.WebImageDownloader
import ru.souz.tool.web.internal.WebResearchClient
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
    bindSingleton { SettingsProviderImpl(instance()) }
    bindSingleton<SettingsProvider> { instance<SettingsProviderImpl>() }
    bindSingleton { LlmBuildProfile(instance()) }
    bindSingleton { ApiKeyAvailabilityUseCase(instance()) }
    bindSingleton { DesktopInfoRepository(instance(), instance(), instance(), instance()) }
    bindSingleton { ToolsSettings(instance(), instance(), instance(), instance()) }
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
    bindSingleton { ToolInternetSearch(api = instance(), settingsProvider = instance(), webResearchClient = instance(), filesToolUtil = instance()) }
    bindSingleton { ToolWebSearch(webResearchClient = instance()) }
    bindSingleton { ToolWebImageSearch(webResearchClient = instance(), webImageDownloader = instance()) }
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

    bindSingleton { GraphSessionRepository() }
    bindSingleton { GraphSessionService(instance(), instance(DiTags.TAG_LOG)) }
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
    bindSingleton { AgentToolExecutor(instance()) }

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
    bindSingleton { LLMFactory(instance(), instance(), instance(), instance(), instance(), instance()) }
    bindSingleton<GigaChatAPI> { instance<LLMFactory>() }
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

    // LLM
    bindSingleton { NodesErrorHandling() }
    bindSingleton { NodesCommon(instance(), instance(), instance()) }
    bindSingleton { NodesLLM(instance(), instance()) }
    bindSingleton { LuaRuntime(instance()) }
    bindSingleton { NodesLua(instance(), instance()) }
    bindSingleton { NodesMCP(instance()) }
    bindSingleton { NodesSummarization(instance(), instance()) }
    bindSingleton {
        NodesClassification(
            instance(),
            instance(DiTags.TAG_LOG),
            apiClassifier = instance(DiTags.TAG_API),
            localClassifier = instance(DiTags.TAG_LOCAL),
            instance(),
            instance(),
        )
    }
    bindSingleton { ToolsFactory(di) }
    bindSingleton { SystemPromptResolver() }
    bindSingleton { GraphBasedAgent(di, instance(DiTags.TAG_LOG)) }
    bindSingleton { LuaGraphBasedAgent(di, instance(DiTags.TAG_LOG)) }
    bindSingleton { AgentFacade(instance(), instance(), instance(), instance(), instance(), instance()) }
    bindSingleton { TelegramBotController(instance(), instance(), speechRecognitionProvider = instance()) }
    bindSingleton { FinderPathExtractor(instance()) }
    bindSingleton { MainUseCasesFactory(instance(), instance(), instance(), instance(), instance(), instance(), instance(), instance(), instance(), instance()) }
    bindSingleton { McpConfigProvider(instance()) }
    bindSingleton { McpClientManager(instance()) }

}
