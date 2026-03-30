package ru.souz.ui.main

import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import ru.souz.giga.GigaResponse
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.*

class ChatAgentActionFormatter {
    suspend fun format(functionCall: GigaResponse.FunctionCall): String {
        val args = functionCall.arguments
        return when (functionCall.name) {
            "ListFiles" -> format(Res.string.chat_action_list_files, pathArg(args, "path"))
            "SearchFileContent" -> format(Res.string.chat_action_search_file_content, textArg(args, "query"))
            "NewFile" -> format(Res.string.chat_action_new_file, pathArg(args, "path"))
            "DeleteFile" -> format(Res.string.chat_action_delete_file, pathArg(args, "path"))
            "EditFile" -> format(Res.string.chat_action_edit_file, pathArg(args, "path"))
            "MoveFile" -> format(
                Res.string.chat_action_move_file,
                pathArg(args, "sourcePath"),
                pathArg(args, "destinationPath"),
            )
            "ExtractTextFromFile" -> format(Res.string.chat_action_extract_text_from_file, pathArg(args, "filePath", "path"))
            "FindFilesByName" -> format(Res.string.chat_action_find_files_by_name, textArg(args, "fileName"))
            "ReadPdfPages" -> format(Res.string.chat_action_read_pdf_pages, pathArg(args, "filePath", "path"))
            "ReadFile" -> format(Res.string.chat_action_read_file, pathArg(args, "path"))
            "FindFolders" -> format(Res.string.chat_action_find_folders, textArg(args, "name"))
            "Open" -> format(Res.string.chat_action_open, displayTarget(args["target"]))
            "CreateNewBrowserTab" -> format(Res.string.chat_action_create_browser_tab, textArg(args, "url"))
            "SafariInfo" -> format(Res.string.chat_action_safari_info, browserInfoTypeLabel(textArg(args, "type")))
            "BrowserHotkeys" -> format(Res.string.chat_action_browser_hotkeys, humanize(textArg(args, "hotKey")))
            "FocusOnTab" -> format(Res.string.chat_action_focus_on_tab, textArg(args, "tab"))
            "ChromeInfo" -> format(Res.string.chat_action_chrome_info, browserInfoTypeLabel(textArg(args, "type")))
            "OpenDefaultBrowser" -> getString(Res.string.chat_action_open_default_browser)
            "RunBashCommand" -> format(Res.string.chat_action_run_bash_command, snippetArg(args, "command"))
            "WebSearch", "InternetSearch", "internetSearch" ->
                format(Res.string.chat_action_web_search, textArg(args, "query"))
            "InternetResearch", "internetResearch" ->
                format(Res.string.chat_action_internet_research, textArg(args, "query"))
            "WebImageSearch" -> format(Res.string.chat_action_web_image_search, textArg(args, "query"))
            "WebPageText" -> format(Res.string.chat_action_web_page_text, textArg(args, "url"))
            "SoundConfig" -> format(Res.string.chat_action_sound_config, textArg(args, "speed"))
            "SoundConfigDiff" -> format(Res.string.chat_action_sound_config_diff, signedNumberArg(args, "diff"))
            "InstructionStore" -> format(Res.string.chat_action_instruction_store, textArg(args, "name"))
            "OpenNote" -> format(Res.string.chat_action_open_note, textArg(args, "noteName"))
            "CreateNote" -> format(Res.string.chat_action_create_note, snippetArg(args, "noteText"))
            "DeleteNote" -> format(Res.string.chat_action_delete_note, textArg(args, "noteName"))
            "ListNotes" -> getString(Res.string.chat_action_list_notes)
            "SearchNotes" -> format(Res.string.chat_action_search_notes, textArg(args, "query"))
            "ShowApps" -> format(Res.string.chat_action_show_apps, appStateLabel(textArg(args, "state")))
            "CreatePlot" -> format(Res.string.chat_action_create_plot, pathArg(args, "path", "output"))
            "UploadFile" -> format(Res.string.chat_action_upload_file, pathArg(args, "filePath"))
            "DownloadFile" -> format(Res.string.chat_action_download_file, textArg(args, "fileId"))
            "ExcelRead" -> format(Res.string.chat_action_excel_read, pathArg(args, "path"))
            "ExcelReport" -> format(Res.string.chat_action_excel_report, pathArg(args, "path"))
            "CalendarCreateEvent" -> format(Res.string.chat_action_create_calendar_event, textArg(args, "title"))
            "CalendarDeleteEvent" -> format(Res.string.chat_action_delete_calendar_event, textArg(args, "title"))
            "CalendarListCalendars" -> format(
                Res.string.chat_action_list_calendars,
                nullableTextArg(args, "nameFilter") ?: localizedValue(Res.string.chat_action_value_all),
            )
            "CalendarListEvents" -> format(
                Res.string.chat_action_list_calendar_events,
                nullableTextArg(args, "date") ?: nullableTextArg(args, "calendarName") ?: localizedValue(Res.string.chat_action_value_today),
            )
            "MailUnreadMessagesCount" -> getString(Res.string.chat_action_mail_unread_count)
            "MailListMessages" -> getString(Res.string.chat_action_list_mail)
            "MailReadMessage" -> getString(Res.string.chat_action_read_mail)
            "MailReplyMessage" -> getString(Res.string.chat_action_reply_mail)
            "MailSendNewMessage" -> format(
                Res.string.chat_action_send_mail,
                textArg(args, "recipientName", "recipientAddress"),
            )
            "MailSearch" -> format(Res.string.chat_action_search_mail, textArg(args, "query"))
            "GetClipboard" -> getString(Res.string.chat_action_get_clipboard)
            "TextReplace" -> format(Res.string.chat_action_text_replace, snippetArg(args, "newText"))
            "TextUnderSelection" -> getString(Res.string.chat_action_text_under_selection)
            "Calculator" -> format(Res.string.chat_action_calculator, snippetArg(args, "expression"))
            "ToolTelegramReadInbox" -> getString(Res.string.chat_action_telegram_read_inbox)
            "ToolTelegramGetHistory" -> format(Res.string.chat_action_telegram_get_history, textArg(args, "chatName"))
            "ToolTelegramSetState" -> format(
                Res.string.chat_action_telegram_set_state,
                textArg(args, "chatName"),
                telegramStateActionLabel(textArg(args, "action")),
            )
            "ToolTelegramSend" -> format(Res.string.chat_action_telegram_send, textArg(args, "targetName"))
            "ToolTelegramForward" -> format(
                Res.string.chat_action_telegram_forward,
                textArg(args, "fromChat"),
                textArg(args, "toChat"),
            )
            "ToolTelegramSearch" -> format(Res.string.chat_action_telegram_search, textArg(args, "query"))
            "ToolTelegramSavedMessages" -> getString(Res.string.chat_action_telegram_saved_messages)
            "TakeScreenshot" -> format(
                Res.string.chat_action_take_screenshot,
                nullableTextArg(args, "nameSuffix")?.takeIf { it.isNotBlank() } ?: localizedValue(Res.string.chat_action_value_default),
            )
            "StartScreenRecording" -> getString(Res.string.chat_action_start_screen_recording)
            "PresentationCreate" -> format(
                Res.string.chat_action_presentation_create,
                textArg(args, "title", "filename", "outputPath"),
            )
            "PresentationRead" -> format(Res.string.chat_action_presentation_read, pathArg(args, "filePath"))
            else -> format(Res.string.chat_action_generic_tool, functionCall.name)
        }
    }
}

private suspend fun browserInfoTypeLabel(value: String): String = when (value) {
    "history" -> localizedValue(Res.string.chat_action_value_history)
    "bookmarks" -> localizedValue(Res.string.chat_action_value_bookmarks)
    "tabs" -> localizedValue(Res.string.chat_action_value_tabs)
    "currentTabUrl" -> localizedValue(Res.string.chat_action_value_current_tab_url)
    "pageText" -> localizedValue(Res.string.chat_action_value_page_text)
    else -> humanize(value)
}

private suspend fun appStateLabel(value: String): String = when (value.lowercase()) {
    "installed" -> localizedValue(Res.string.chat_action_value_installed)
    "running" -> localizedValue(Res.string.chat_action_value_running)
    else -> humanize(value)
}

private suspend fun telegramStateActionLabel(value: String): String = when (value.lowercase()) {
    "mute" -> localizedValue(Res.string.chat_action_value_mute)
    "archive" -> localizedValue(Res.string.chat_action_value_archive)
    "markread" -> localizedValue(Res.string.chat_action_value_mark_read)
    "delete" -> localizedValue(Res.string.chat_action_value_delete)
    else -> humanize(value)
}

private suspend fun localizedValue(resource: StringResource): String = getString(resource)

private suspend fun format(resource: StringResource, vararg args: String): String =
    getString(resource).format(*args)

private fun textArg(args: Map<String, Any>, vararg keys: String): String =
    nullableTextArg(args, *keys) ?: "?"

private fun nullableTextArg(args: Map<String, Any>, vararg keys: String): String? =
    keys.asSequence()
        .mapNotNull { key -> args[key].stringValueOrNull() }
        .firstOrNull()

private fun pathArg(args: Map<String, Any>, vararg keys: String): String =
    displayTarget(nullableTextArg(args, *keys))

private fun snippetArg(args: Map<String, Any>, vararg keys: String): String =
    ellipsize(nullableTextArg(args, *keys) ?: "?")

private fun signedNumberArg(args: Map<String, Any>, key: String): String {
    val value = nullableTextArg(args, key) ?: return "?"
    return if (value.startsWith("-") || value.startsWith("+")) value else "+$value"
}

private fun Any?.stringValueOrNull(): String? = when (this) {
    null -> null
    is String -> trim().takeIf { it.isNotEmpty() }
    is Number, is Boolean -> toString()
    is Collection<*> -> joinToString(", ") { it?.toString().orEmpty() }.takeIf { it.isNotBlank() }
    else -> toString().trim().takeIf { it.isNotEmpty() }
}

private fun displayTarget(rawValue: Any?): String {
    val raw = rawValue?.toString()?.trim().orEmpty()
    if (raw.isEmpty()) return "?"
    val candidate = raw
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .ifBlank { raw }
    return ellipsize(candidate)
}

private fun humanize(value: String): String =
    ellipsize(
        value
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace('_', ' ')
            .trim()
            .ifBlank { "?" }
    )

private fun ellipsize(value: String, maxLength: Int = 80): String =
    if (value.length <= maxLength) value else value.take(maxLength - 1) + "..."
