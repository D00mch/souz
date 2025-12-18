package ru.gigadesk.tool

import ru.gigadesk.db.DesktopInfoRepository
import ru.gigadesk.giga.GigaToolSetup
import ru.gigadesk.giga.toGiga
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

typealias FunctionName = String

class ToolsFactory(private val repo: DesktopInfoRepository) {
    val toolsByCategory: Map<ToolCategory, Map<FunctionName, GigaToolSetup>> by lazy {
        mapOf(
            ToolCategory.FILES to listOf(
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
            ).associateBy { it.fn.name },

            ToolCategory.DATAANALYTICS to listOf(
                ToolCreatePlotFromCsv().toGiga(),
                ToolUploadFile().toGiga(),
                ToolDownloadFile().toGiga(),
            ).associateBy { it.fn.name },

            ToolCategory.BROWSER to listOf(
                ToolCreateNewBrowserTab(ToolRunBashCommand).toGiga(),
                ToolSafariInfo(ToolRunBashCommand).toGiga(),
                ToolBrowserHotkeys().toGiga(),
                ToolFocusOnTab(ToolRunBashCommand).toGiga(),
                ToolChromeInfo(ToolRunBashCommand).toGiga(),
                ToolOpenDefaultBrowser(ToolRunBashCommand).toGiga(),
            ).associateBy { it.fn.name },

            ToolCategory.CONFIG to listOf(
                ToolSoundConfig(ConfigStore).toGiga(),
                ToolSoundConfigDiff(ConfigStore).toGiga(),
                ToolInstructionStore(ConfigStore, repo).toGiga(),
            ).associateBy { it.fn.name },

            ToolCategory.NOTES to listOf(
                ToolOpenNote(ToolRunBashCommand).toGiga(),
                ToolCreateNote(ToolRunBashCommand).toGiga(),
                ToolDeleteNote(ToolRunBashCommand).toGiga(),
                ToolListNotes(ToolRunBashCommand).toGiga(),
                ToolSearchNotes(ToolRunBashCommand).toGiga(),
            ).associateBy { it.fn.name },

            ToolCategory.APPLICATIONS to listOf(
                ToolShowApps.toGiga(),
                ToolOpen(ToolRunBashCommand).toGiga(),
            ).associateBy { it.fn.name },

            ToolCategory.CALENDAR to listOf(
                //ToolCalendarListTodayEvents(ToolRunBashCommand).toGiga(),
                ToolCalendarCreateEvent(ToolRunBashCommand).toGiga(),
                ToolCalendarDeleteEvent(ToolRunBashCommand).toGiga(),
                ToolCalendarListCalendars(ToolRunBashCommand).toGiga(),
                ToolCalendarListEvents(ToolRunBashCommand).toGiga(),
            ).associateBy { it.fn.name },

            ToolCategory.MAIL to listOf(
                ToolMailUnreadMessagesCount(ToolRunBashCommand).toGiga(),
                ToolMailListMessages(ToolRunBashCommand).toGiga(),
                ToolMailReadMessage(ToolRunBashCommand).toGiga(),
                ToolMailReplyMessage(ToolRunBashCommand).toGiga(),
                ToolMailSendNewMessage(ToolRunBashCommand).toGiga(),
                ToolMailSearch(ToolRunBashCommand).toGiga(),
            ).associateBy { it.fn.name },
        )
    }
}