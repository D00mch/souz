package ru.gigadesk.tool

import org.kodein.di.DI
import org.kodein.di.instance
import ru.gigadesk.giga.GigaToolSetup
import ru.gigadesk.giga.toGiga
import ru.gigadesk.tool.application.ToolOpen
import ru.gigadesk.tool.application.ToolShowApps
import ru.gigadesk.tool.browser.ToolBrowserHotkeys
import ru.gigadesk.tool.browser.ToolChromeInfo
import ru.gigadesk.tool.browser.ToolCreateNewBrowserTab
import ru.gigadesk.tool.browser.ToolFocusOnTab
import ru.gigadesk.tool.browser.ToolOpenDefaultBrowser
import ru.gigadesk.tool.browser.ToolSafariInfo
import ru.gigadesk.tool.calendar.ToolCalendarCreateEvent
import ru.gigadesk.tool.calendar.ToolCalendarDeleteEvent
import ru.gigadesk.tool.calendar.ToolCalendarListCalendars
import ru.gigadesk.tool.calendar.ToolCalendarListEvents
import ru.gigadesk.tool.config.ToolInstructionStore
import ru.gigadesk.tool.config.ToolSoundConfig
import ru.gigadesk.tool.config.ToolSoundConfigDiff
import ru.gigadesk.tool.dataAnalytics.ToolCreatePlotFromCsv
import ru.gigadesk.tool.desktop.ToolDownloadFile
import ru.gigadesk.tool.desktop.ToolUploadFile
import ru.gigadesk.tool.files.ToolDeleteFile
import ru.gigadesk.tool.files.ToolExtractText
import ru.gigadesk.tool.files.ToolFindFilesByName
import ru.gigadesk.tool.files.ToolFindInFiles
import ru.gigadesk.tool.files.ToolListFiles
import ru.gigadesk.tool.files.ToolModifyFile
import ru.gigadesk.tool.files.ToolMoveFile
import ru.gigadesk.tool.files.ToolNewFile
import ru.gigadesk.tool.files.ToolReadFile
import ru.gigadesk.tool.files.ToolReadPdfPages
import ru.gigadesk.tool.mail.ToolMailListMessages
import ru.gigadesk.tool.mail.ToolMailReadMessage
import ru.gigadesk.tool.mail.ToolMailReplyMessage
import ru.gigadesk.tool.mail.ToolMailSearch
import ru.gigadesk.tool.mail.ToolMailSendNewMessage
import ru.gigadesk.tool.mail.ToolMailUnreadMessagesCount
import ru.gigadesk.tool.notes.ToolCreateNote
import ru.gigadesk.tool.notes.ToolDeleteNote
import ru.gigadesk.tool.notes.ToolListNotes
import ru.gigadesk.tool.notes.ToolOpenNote
import ru.gigadesk.tool.notes.ToolSearchNotes
import ru.gigadesk.tool.textReplace.ToolGetClipboard
import ru.gigadesk.tool.textReplace.ToolTextReplace
import ru.gigadesk.tool.textReplace.ToolTextUnderSelection

typealias FunctionName = String

class ToolsFactory(di: DI) {

    private val toolReadFile: ToolReadFile by di.instance()
    private val toolListFiles: ToolListFiles by di.instance()
    private val toolFindInFiles: ToolFindInFiles by di.instance()
    private val toolNewFile: ToolNewFile by di.instance()
    private val toolDeleteFile: ToolDeleteFile by di.instance()
    private val toolModifyFile: ToolModifyFile by di.instance()
    private val toolMoveFile: ToolMoveFile by di.instance()
    private val toolExtractText: ToolExtractText by di.instance()
    private val toolFindFilesByName: ToolFindFilesByName by di.instance()
    private val toolReadPdfPages: ToolReadPdfPages by di.instance()
    private val toolOpen: ToolOpen by di.instance()
    private val toolCreateNewBrowserTab: ToolCreateNewBrowserTab by di.instance()
    private val toolSafariInfo: ToolSafariInfo by di.instance()
    private val toolBrowserHotkeys: ToolBrowserHotkeys by di.instance()
    private val toolFocusOnTab: ToolFocusOnTab by di.instance()
    private val toolChromeInfo: ToolChromeInfo by di.instance()
    private val toolOpenDefaultBrowser: ToolOpenDefaultBrowser by di.instance()
    private val toolSoundConfig: ToolSoundConfig by di.instance()
    private val toolSoundConfigDiff: ToolSoundConfigDiff by di.instance()
    private val toolInstructionStore: ToolInstructionStore by di.instance()
    private val toolOpenNote: ToolOpenNote by di.instance()
    private val toolCreateNote: ToolCreateNote by di.instance()
    private val toolDeleteNote: ToolDeleteNote by di.instance()
    private val toolListNotes: ToolListNotes by di.instance()
    private val toolSearchNotes: ToolSearchNotes by di.instance()
    private val toolShowApps: ToolShowApps by di.instance()
    private val toolCreatePlotFromCsv: ToolCreatePlotFromCsv by di.instance()
    private val toolUploadFile: ToolUploadFile by di.instance()
    private val toolDownloadFile: ToolDownloadFile by di.instance()
    private val toolCalendarCreateEvent: ToolCalendarCreateEvent by di.instance()
    private val toolCalendarDeleteEvent: ToolCalendarDeleteEvent by di.instance()
    private val toolCalendarListCalendars: ToolCalendarListCalendars by di.instance()
    private val toolCalendarListEvents: ToolCalendarListEvents by di.instance()
    private val toolMailUnreadMessagesCount: ToolMailUnreadMessagesCount by di.instance()
    private val toolMailListMessages: ToolMailListMessages by di.instance()
    private val toolMailReadMessage: ToolMailReadMessage by di.instance()
    private val toolMailReplyMessage: ToolMailReplyMessage by di.instance()
    private val toolMailSendNewMessage: ToolMailSendNewMessage by di.instance()
    private val toolMailSearch: ToolMailSearch by di.instance()
    private val toolGetClipboard: ToolGetClipboard by di.instance()
    private val toolTextReplace: ToolTextReplace by di.instance()
    private val toolTextUnderSelection: ToolTextUnderSelection by di.instance()

    val toolsByCategory: Map<ToolCategory, Map<FunctionName, GigaToolSetup>> by lazy {
        ToolCategory.entries.associateWith { category ->
            category.tools().associateBy { it.fn.name }
        }
    }

    private fun ToolCategory.tools(): List<GigaToolSetup> = when (this) {
        ToolCategory.FILES -> listOf(
            toolReadFile.toGiga(),
            toolListFiles.toGiga(),
            toolFindInFiles.toGiga(),
            toolNewFile.toGiga(),
            toolDeleteFile.toGiga(),
            toolModifyFile.toGiga(),
            toolMoveFile.toGiga(),
            toolExtractText.toGiga(),
            toolFindFilesByName.toGiga(),
            toolReadPdfPages.toGiga(),
            toolOpen.toGiga(),
        )

        ToolCategory.BROWSER -> listOf(
            toolCreateNewBrowserTab.toGiga(),
            toolSafariInfo.toGiga(),
            toolBrowserHotkeys.toGiga(),
            toolFocusOnTab.toGiga(),
            toolChromeInfo.toGiga(),
            toolOpenDefaultBrowser.toGiga(),
        )

        ToolCategory.CONFIG -> listOf(
            toolSoundConfig.toGiga(),
            toolSoundConfigDiff.toGiga(),
            toolInstructionStore.toGiga(),
        )

        ToolCategory.NOTES -> listOf(
            toolOpenNote.toGiga(),
            toolCreateNote.toGiga(),
            toolDeleteNote.toGiga(),
            toolListNotes.toGiga(),
            toolSearchNotes.toGiga(),
        )

        ToolCategory.APPLICATIONS -> listOf(
            toolShowApps.toGiga(),
            toolOpen.toGiga(),
        )

        ToolCategory.DATAANALYTICS -> listOf(
            toolCreatePlotFromCsv.toGiga(),
            toolUploadFile.toGiga(),
            toolDownloadFile.toGiga(),
        )

        ToolCategory.CALENDAR -> listOf(
            toolCalendarCreateEvent.toGiga(),
            toolCalendarDeleteEvent.toGiga(),
            toolCalendarListCalendars.toGiga(),
            toolCalendarListEvents.toGiga(),
        )

        ToolCategory.MAIL -> listOf(
            toolMailUnreadMessagesCount.toGiga(),
            toolMailListMessages.toGiga(),
            toolMailReadMessage.toGiga(),
            toolMailReplyMessage.toGiga(),
            toolMailSendNewMessage.toGiga(),
            toolMailSearch.toGiga(),
        )

        ToolCategory.TEXT_REPLACE -> listOf(
            toolGetClipboard.toGiga(),
            toolTextReplace.toGiga(),
            toolTextUnderSelection.toGiga(),
        )

        ToolCategory.CHAT -> listOf()
    }
}
