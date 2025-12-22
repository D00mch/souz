package ru.gigadesk.tool

import ru.gigadesk.db.DesktopInfoRepository
import ru.gigadesk.giga.GigaToolSetup
import ru.gigadesk.giga.toGiga
import ru.gigadesk.keys.Keys
import ru.gigadesk.tool.browser.ToolBrowserHotkeys
import ru.gigadesk.tool.browser.ToolCreateNewBrowserTab
import ru.gigadesk.tool.browser.ToolFocusOnTab
import ru.gigadesk.tool.browser.ToolSafariInfo
import ru.gigadesk.db.ConfigStore
import ru.gigadesk.tool.application.ToolOpen
import ru.gigadesk.tool.application.ToolShowApps
import ru.gigadesk.tool.browser.ToolChromeInfo
import ru.gigadesk.tool.browser.ToolOpenDefaultBrowser
import ru.gigadesk.tool.config.ToolInstructionStore
import ru.gigadesk.tool.config.ToolSoundConfig
import ru.gigadesk.tool.config.ToolSoundConfigDiff
import ru.gigadesk.tool.dataAnalytics.ToolCreatePlotFromCsv
import ru.gigadesk.tool.desktop.*
import ru.gigadesk.tool.calendar.*
import ru.gigadesk.tool.mail.*
import ru.gigadesk.tool.files.*
import ru.gigadesk.tool.notes.ToolCreateNote
import ru.gigadesk.tool.notes.ToolDeleteNote
import ru.gigadesk.tool.notes.ToolListNotes
import ru.gigadesk.tool.notes.ToolOpenNote
import ru.gigadesk.tool.notes.ToolSearchNotes
import ru.gigadesk.tool.textReplace.ToolGetClipboard
import ru.gigadesk.tool.textReplace.ToolTextReplace
import ru.gigadesk.tool.textReplace.ToolTextUnderSelection

typealias FunctionName = String

class ToolsFactory(
    private val repo: DesktopInfoRepository,
    private val keys: Keys = Keys(),
) {
    val toolsByCategory: Map<ToolCategory, Map<FunctionName, GigaToolSetup>> by lazy {
        ToolCategory.entries.associateWith { category ->
            category.tools().associateBy { it.fn.name }
        }
    }

    private fun ToolCategory.tools(): List<GigaToolSetup> = when (this) {
        ToolCategory.FILES -> listOf(
            ToolReadFile.toGiga(),
            ToolListFiles.toGiga(),
            ToolFindInFiles.toGiga(),
            ToolNewFile.toGiga(),
            ToolDeleteFile.toGiga(),
            ToolModifyFile.toGiga(),
            ToolFindTextInFiles.toGiga(),
            ToolExtractText().toGiga(),
            ToolReadPdfPages().toGiga(),
            ToolOpen(ToolRunBashCommand).toGiga(),
        )

        ToolCategory.BROWSER -> listOf(
            ToolCreateNewBrowserTab(ToolRunBashCommand).toGiga(),
            ToolSafariInfo(ToolRunBashCommand).toGiga(),
            ToolBrowserHotkeys(keys).toGiga(),
            ToolFocusOnTab(ToolRunBashCommand).toGiga(),
            ToolChromeInfo(ToolRunBashCommand).toGiga(),
            ToolOpenDefaultBrowser(ToolRunBashCommand).toGiga(),
        )

        ToolCategory.CONFIG -> listOf(
            ToolSoundConfig(ConfigStore).toGiga(),
            ToolSoundConfigDiff(ConfigStore).toGiga(),
            ToolInstructionStore(ConfigStore, repo).toGiga(),
        )

        ToolCategory.NOTES -> listOf(
            ToolOpenNote(ToolRunBashCommand).toGiga(),
            ToolCreateNote(ToolRunBashCommand).toGiga(),
            ToolDeleteNote(ToolRunBashCommand).toGiga(),
            ToolListNotes(ToolRunBashCommand).toGiga(),
            ToolSearchNotes(ToolRunBashCommand).toGiga(),
        )

        ToolCategory.APPLICATIONS -> listOf(
            ToolShowApps.toGiga(),
            ToolOpen(ToolRunBashCommand).toGiga(),
        )

        ToolCategory.DATAANALYTICS -> listOf(
            ToolCreatePlotFromCsv().toGiga(),
            ToolUploadFile().toGiga(),
            ToolDownloadFile().toGiga(),
        )

        ToolCategory.CALENDAR -> listOf(
            //ToolCalendarListTodayEvents(ToolRunBashCommand).toGiga(),
            ToolCalendarCreateEvent(ToolRunBashCommand).toGiga(),
            ToolCalendarDeleteEvent(ToolRunBashCommand).toGiga(),
            ToolCalendarListCalendars(ToolRunBashCommand).toGiga(),
            ToolCalendarListEvents(ToolRunBashCommand).toGiga(),
        )

        ToolCategory.MAIL -> listOf(
            ToolMailUnreadMessagesCount(ToolRunBashCommand).toGiga(),
            ToolMailListMessages(ToolRunBashCommand).toGiga(),
            ToolMailReadMessage(ToolRunBashCommand).toGiga(),
            ToolMailReplyMessage(ToolRunBashCommand).toGiga(),
            ToolMailSendNewMessage(ToolRunBashCommand).toGiga(),
            ToolMailSearch(ToolRunBashCommand).toGiga(),
        )

        ToolCategory.TEXT_REPLACE -> {
            val clip = ToolGetClipboard()
            listOf(
                clip.toGiga(),
                ToolTextReplace(ToolRunBashCommand).toGiga(),
                ToolTextUnderSelection(ToolRunBashCommand, clip).toGiga(),
            )
        }

        ToolCategory.CHAT -> listOf()
    }
}