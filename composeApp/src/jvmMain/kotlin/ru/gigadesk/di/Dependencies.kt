package ru.gigadesk.di

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ru.gigadesk.db.SettingsProviderImpl
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import ru.gigadesk.agent.GraphBasedAgent
import ru.gigadesk.agent.nodes.NodesErrorHandling
import ru.gigadesk.agent.nodes.NodesCommon
import ru.gigadesk.agent.nodes.NodesLLM
import ru.gigadesk.agent.nodes.NodesSummarization
import ru.gigadesk.agent.nodes.NodesClassification
import ru.gigadesk.agent.session.GraphSessionRepository
import ru.gigadesk.agent.session.GraphSessionService
import ru.gigadesk.server.AgentNode
import ru.gigadesk.server.GraphAgentNode
import ru.gigadesk.audio.Say
import ru.gigadesk.db.ConfigStore
import ru.gigadesk.db.DesktopDataExtractor
import ru.gigadesk.db.DesktopInfoRepository
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.db.VectorDB
import ru.gigadesk.giga.ApiClassifier
import ru.gigadesk.giga.GigaAuth
import ru.gigadesk.giga.GigaChatAPI
import ru.gigadesk.giga.GigaGRPCChatApi
import ru.gigadesk.giga.GigaRestChatAPI
import ru.gigadesk.giga.GigaVoiceAPI
import ru.gigadesk.keys.Keys
import ru.gigadesk.tool.*
import ru.gigadesk.tool.application.*
import ru.gigadesk.tool.browser.*
import ru.gigadesk.tool.calendar.*
import ru.gigadesk.tool.config.*
import ru.gigadesk.tool.dataAnalytics.*
import ru.gigadesk.tool.dataAnalytics.excel.ExcelCreate
import ru.gigadesk.tool.dataAnalytics.excel.ExcelJoin
import ru.gigadesk.tool.dataAnalytics.excel.ExcelRead
import ru.gigadesk.tool.dataAnalytics.excel.ExcelTransform
import ru.gigadesk.tool.dataAnalytics.excel.ExcelWrite
import ru.gigadesk.tool.desktop.*
import ru.gigadesk.tool.files.*
import ru.gigadesk.tool.mail.*
import ru.gigadesk.tool.notes.*
import ru.gigadesk.tool.textReplace.*

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

    // Native
    bindSingleton { Keys() }

    // DB
    bindSingleton { ConfigStore }
    bindSingleton { VectorDB }
    bindSingleton<SettingsProvider> { SettingsProviderImpl(instance()) }
    bindSingleton { DesktopInfoRepository(instance(), instance(), instance()) }
    bindSingleton { ToolsSettings(instance(), instance()) }
    bindSingleton { FilesToolUtil(instance()) }

    // Tools
    bindSingleton { ToolGetClipboard() }
    bindSingleton { ToolListFiles(instance()) }
    bindSingleton { ToolFindInFiles(instance()) }
    bindSingleton { ToolNewFile(instance()) }
    bindSingleton { ToolDeleteFile(instance()) }
    bindSingleton { ToolModifyFile(instance()) }
    bindSingleton { ToolMoveFile(instance()) }
    bindSingleton { ToolExtractText(instance()) }
    bindSingleton { ToolFindFilesByName(instance()) }
    bindSingleton { ToolReadPdfPages(instance()) }
    bindSingleton { ToolOpen(ToolRunBashCommand, instance()) }
    bindSingleton { ToolCreateNewBrowserTab(ToolRunBashCommand) }
    bindSingleton { ToolSafariInfo(ToolRunBashCommand) }
    bindSingleton { ToolBrowserHotkeys(instance()) }
    bindSingleton { ToolFocusOnTab(ToolRunBashCommand) }
    bindSingleton { ToolChromeInfo(ToolRunBashCommand) }
    bindSingleton { ToolOpenDefaultBrowser(ToolRunBashCommand, instance()) }
    bindSingleton { ToolSoundConfig(ConfigStore) }
    bindSingleton { ToolSoundConfigDiff(ConfigStore) }
    bindSingleton { ToolInstructionStore(ConfigStore, instance()) }
    bindSingleton { ToolOpenNote(ToolRunBashCommand) }
    bindSingleton { ToolCreateNote(ToolRunBashCommand) }
    bindSingleton { ToolDeleteNote(ToolRunBashCommand) }
    bindSingleton { ToolListNotes(ToolRunBashCommand) }
    bindSingleton { ToolSearchNotes(ToolRunBashCommand) }
    bindSingleton { ToolShowApps(instance(), ToolRunBashCommand) }
    bindSingleton { ToolCreatePlotFromCsv(instance()) }
    bindSingleton { ToolCalendarCreateEvent(ToolRunBashCommand) }
    bindSingleton { ToolCalendarDeleteEvent(ToolRunBashCommand) }
    bindSingleton { ToolCalendarListCalendars(ToolRunBashCommand) }
    bindSingleton { ToolCalendarListEvents(ToolRunBashCommand) }
    bindSingleton { ToolMailUnreadMessagesCount(ToolRunBashCommand) }
    bindSingleton { ToolMailListMessages(ToolRunBashCommand) }
    bindSingleton { ToolMailReadMessage(ToolRunBashCommand) }
    bindSingleton { ToolMailReplyMessage(ToolRunBashCommand) }
    bindSingleton { ToolMailSendNewMessage(ToolRunBashCommand) }
    bindSingleton { ToolMailSearch(ToolRunBashCommand) }
    bindSingleton { ToolTextReplace(ToolRunBashCommand) }
    bindSingleton { ToolTextUnderSelection(ToolRunBashCommand, instance()) }
    bindSingleton { ToolFindFolders(ToolRunBashCommand, instance()) }
    bindSingleton { ToolUploadFile(instance()) }
    bindSingleton { ToolDownloadFile(instance()) }

    // Excel tools
    bindSingleton { ExcelRead(instance()) }
    bindSingleton { ExcelWrite(instance()) }
    bindSingleton { ExcelJoin(instance()) }
    bindSingleton { ExcelTransform(instance()) }
    bindSingleton { ExcelCreate(instance()) }

    bindSingleton { GraphSessionRepository() }
    bindSingleton { GraphSessionService(instance(), instance(DiTags.TAG_LOG)) }
    bindSingleton { DesktopDataExtractor(instance(), instance()) }

    // API
    bindSingleton { GigaAuth(instance()) }
    bindSingleton<GigaGRPCChatApi> {
        GigaGRPCChatApi(instance(), instance())
    }
    bindSingleton<GigaRestChatAPI> {
        GigaRestChatAPI(instance(), instance(), instance(DiTags.TAG_LOG))
    }
    bindSingleton<GigaChatAPI> { instance<GigaRestChatAPI>() }
    bindSingleton { GigaVoiceAPI(instance(), instance()) }
    bindSingleton(tag = DiTags.TAG_API) { ApiClassifier(instance()) }
    bindSingleton(tag = DiTags.TAG_LOCAL) { LocalRegexClassifier }

    // LLM
    bindSingleton { NodesErrorHandling() }
    bindSingleton { NodesCommon(instance(), instance()) }
    bindSingleton { NodesLLM(instance(), instance(), instance()) }
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
    bindSingleton { GraphBasedAgent(di, instance(DiTags.TAG_LOG)) }
    
    // Server
    bindSingleton<AgentNode> { GraphAgentNode(instance()) }
}
