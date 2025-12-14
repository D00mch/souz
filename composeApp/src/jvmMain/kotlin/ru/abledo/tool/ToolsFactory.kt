package ru.abledo.tool

import ru.abledo.db.DesktopInfoRepository
import ru.abledo.giga.GigaToolSetup
import ru.abledo.giga.toGiga
import ru.abledo.tool.browser.ToolBrowserHotkeys
import ru.abledo.tool.browser.ToolCreateNewBrowserTab
import ru.abledo.tool.browser.ToolFocusOnTab
import ru.abledo.tool.browser.ToolSafariInfo
import ru.abledo.db.ConfigStore
import ru.abledo.tool.application.ToolOpen
import ru.abledo.tool.application.ToolShowApps
import ru.abledo.tool.browser.ToolChromeInfo
import ru.abledo.tool.browser.ToolOpenDefaultBrowser
import ru.abledo.tool.config.ToolInstructionStore
import ru.abledo.tool.config.ToolSoundConfig
import ru.abledo.tool.config.ToolSoundConfigDiff
import ru.abledo.tool.dataAnalytics.ToolCreatePlotFromCsv
import ru.abledo.tool.desktop.*
import ru.abledo.tool.calendar.*
import ru.abledo.tool.mail.*
import ru.abledo.tool.files.*
import ru.abledo.tool.notes.ToolCreateNote
import ru.abledo.tool.notes.ToolDeleteNote
import ru.abledo.tool.notes.ToolListNotes
import ru.abledo.tool.notes.ToolOpenNote
import ru.abledo.tool.notes.ToolSearchNotes

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
                ToolCreatePlotFromCsv(ToolRunBashCommand).toGiga(),
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
                ToolCalendarListTodayEvents(ToolRunBashCommand).toGiga(),
                ToolCalendarCreateEvent(ToolRunBashCommand).toGiga(),
                ToolCalendarDeleteEvent(ToolRunBashCommand).toGiga(),
                ToolCalendarListCalendars(ToolRunBashCommand).toGiga(),
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