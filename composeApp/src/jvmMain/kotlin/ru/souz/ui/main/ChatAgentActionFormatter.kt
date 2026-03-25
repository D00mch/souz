package ru.souz.ui.main

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ru.souz.tool.ToolActionDescriptor
import ru.souz.tool.ToolActionKind
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.*

@Composable
fun formatToolAction(descriptor: ToolActionDescriptor): String {
    val primary = descriptor.primary
    return when (descriptor.kind) {
        ToolActionKind.SEARCH_WEB -> format(Res.string.chat_action_search_web, primary)
        ToolActionKind.SEARCH_IMAGES -> format(Res.string.chat_action_search_images, primary)
        ToolActionKind.SEARCH_IMAGES_AND_DOWNLOAD -> format(Res.string.chat_action_search_images_download, primary)
        ToolActionKind.READ_WEB_PAGE -> format(Res.string.chat_action_read_web_page, primary)
        ToolActionKind.READ_FILE -> format(Res.string.chat_action_read_file, primary)
        ToolActionKind.EDIT_FILE -> format(Res.string.chat_action_edit_file, primary)
        ToolActionKind.CREATE_FILE -> format(Res.string.chat_action_create_file, primary)
        ToolActionKind.CREATE_FOLDER -> format(Res.string.chat_action_create_folder, primary)
        ToolActionKind.MOVE_FILE -> format(Res.string.chat_action_move_file, primary)
        ToolActionKind.DELETE_FILE -> format(Res.string.chat_action_delete_file, primary)
        ToolActionKind.DELETE_FOLDER -> format(Res.string.chat_action_delete_folder, primary)
        ToolActionKind.FIND_FILE -> format(Res.string.chat_action_find_file, primary)
        ToolActionKind.SEARCH_FILE_CONTENT -> format(Res.string.chat_action_search_file_content, primary)
        ToolActionKind.LIST_FOLDER -> format(Res.string.chat_action_list_folder, primary)
        ToolActionKind.FIND_FOLDER -> format(Res.string.chat_action_find_folder, primary)
        ToolActionKind.EXTRACT_TEXT_FROM_FILE -> format(Res.string.chat_action_extract_text_from_file, primary)
        ToolActionKind.READ_PDF -> format(Res.string.chat_action_read_pdf, primary)
        ToolActionKind.CREATE_PRESENTATION -> format(Res.string.chat_action_create_presentation, primary)
        ToolActionKind.READ_PRESENTATION -> format(Res.string.chat_action_read_presentation, primary)
        ToolActionKind.CREATE_PLOT -> format(Res.string.chat_action_create_plot, primary)
        ToolActionKind.READ_SPREADSHEET -> format(Res.string.chat_action_read_spreadsheet, primary)
        ToolActionKind.ANALYZE_SPREADSHEET -> format(Res.string.chat_action_analyze_spreadsheet, primary)
        ToolActionKind.CREATE_SPREADSHEET -> format(Res.string.chat_action_create_spreadsheet, primary)
        ToolActionKind.CREATE_CALENDAR_EVENT -> format(Res.string.chat_action_create_calendar_event, primary)
        ToolActionKind.DELETE_CALENDAR_EVENT -> format(Res.string.chat_action_delete_calendar_event, primary)
        ToolActionKind.LIST_CALENDARS -> format(Res.string.chat_action_list_calendars, primary)
        ToolActionKind.LIST_CALENDAR_EVENTS -> format(Res.string.chat_action_list_calendar_events, primary)
        ToolActionKind.SEARCH_MAIL -> format(Res.string.chat_action_search_mail, primary)
        ToolActionKind.LIST_MAIL -> stringResource(Res.string.chat_action_list_mail)
        ToolActionKind.READ_MAIL -> stringResource(Res.string.chat_action_read_mail)
        ToolActionKind.REPLY_MAIL -> stringResource(Res.string.chat_action_reply_mail)
        ToolActionKind.SEND_MAIL -> format(Res.string.chat_action_send_mail, primary)
        ToolActionKind.CREATE_NOTE -> stringResource(Res.string.chat_action_create_note)
        ToolActionKind.OPEN_NOTE -> format(Res.string.chat_action_open_note, primary)
        ToolActionKind.DELETE_NOTE -> format(Res.string.chat_action_delete_note, primary)
        ToolActionKind.SEARCH_NOTES -> format(Res.string.chat_action_search_notes, primary)
        ToolActionKind.LIST_NOTES -> stringResource(Res.string.chat_action_list_notes)
        ToolActionKind.OPEN_LINK -> stringResource(Res.string.chat_action_open_link)
        ToolActionKind.OPEN_APPLICATION -> stringResource(Res.string.chat_action_open_application)
        ToolActionKind.OPEN_FILE -> format(Res.string.chat_action_open_file, primary)
        ToolActionKind.OPEN_FOLDER -> format(Res.string.chat_action_open_folder, primary)
        ToolActionKind.SHOW_RUNNING_APPS -> stringResource(Res.string.chat_action_list_running_apps)
        ToolActionKind.SHOW_INSTALLED_APPS -> stringResource(Res.string.chat_action_list_installed_apps)
        ToolActionKind.TAKE_SCREENSHOT -> stringResource(Res.string.chat_action_take_screenshot)
        ToolActionKind.START_SCREEN_RECORDING -> stringResource(Res.string.chat_action_start_screen_recording)
        ToolActionKind.READ_TELEGRAM_INBOX -> stringResource(Res.string.chat_action_read_telegram_inbox)
        ToolActionKind.READ_TELEGRAM_CHAT -> format(Res.string.chat_action_read_telegram_chat, primary)
        ToolActionKind.SEARCH_TELEGRAM -> format(Res.string.chat_action_search_telegram, primary)
        ToolActionKind.SEARCH_TELEGRAM_CHAT -> format(Res.string.chat_action_search_telegram_chat, primary)
        ToolActionKind.SEND_TELEGRAM_MESSAGE -> format(Res.string.chat_action_send_telegram_message, primary)
        ToolActionKind.SEND_TELEGRAM_ATTACHMENT -> format(Res.string.chat_action_send_telegram_attachment, primary)
        ToolActionKind.FORWARD_TELEGRAM_MESSAGE -> stringResource(Res.string.chat_action_forward_telegram_message)
        ToolActionKind.SAVE_TELEGRAM_SAVED_MESSAGES -> stringResource(Res.string.chat_action_save_telegram_saved_messages)
        ToolActionKind.MUTE_TELEGRAM_CHAT -> format(Res.string.chat_action_mute_telegram_chat, primary)
        ToolActionKind.ARCHIVE_TELEGRAM_CHAT -> format(Res.string.chat_action_archive_telegram_chat, primary)
        ToolActionKind.MARK_READ_TELEGRAM_CHAT -> format(
            Res.string.chat_action_mark_read_telegram_chat,
            primary,
            Res.string.chat_action_mark_read_telegram_chat_generic,
        )
        ToolActionKind.DELETE_TELEGRAM_CHAT -> format(Res.string.chat_action_delete_telegram_chat, primary)
    }
}

@Composable
private fun format(
    resource: StringResource,
    value: String?,
    fallbackResource: StringResource? = null,
): String {
    if (!value.isNullOrBlank()) {
        return stringResource(resource, value)
    }

    fallbackResource?.let { return stringResource(it) }

    val template = stringResource(resource)
    val withoutIndexedArg = template.substringBefore("%1\$s", missingDelimiterValue = template)
    return withoutIndexedArg
        .substringBefore("%s", missingDelimiterValue = withoutIndexedArg)
        .trim()
        .trimEnd(':', ' ')
}
