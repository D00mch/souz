package ru.souz.tool

import java.io.File
import java.net.URI

data class ToolActionDescriptor(
    val kind: ToolActionKind,
    val primary: String? = null,
    val secondary: String? = null,
)

enum class ToolActionKind {
    SEARCH_WEB,
    SEARCH_IMAGES,
    SEARCH_IMAGES_AND_DOWNLOAD,
    READ_WEB_PAGE,
    READ_FILE,
    EDIT_FILE,
    CREATE_FILE,
    CREATE_FOLDER,
    MOVE_FILE,
    DELETE_FILE,
    DELETE_FOLDER,
    FIND_FILE,
    SEARCH_FILE_CONTENT,
    LIST_FOLDER,
    FIND_FOLDER,
    EXTRACT_TEXT_FROM_FILE,
    READ_PDF,
    CREATE_PRESENTATION,
    READ_PRESENTATION,
    CREATE_PLOT,
    READ_SPREADSHEET,
    ANALYZE_SPREADSHEET,
    CREATE_SPREADSHEET,
    CREATE_CALENDAR_EVENT,
    DELETE_CALENDAR_EVENT,
    LIST_CALENDARS,
    LIST_CALENDAR_EVENTS,
    SEARCH_MAIL,
    LIST_MAIL,
    READ_MAIL,
    REPLY_MAIL,
    SEND_MAIL,
    CREATE_NOTE,
    OPEN_NOTE,
    DELETE_NOTE,
    SEARCH_NOTES,
    LIST_NOTES,
    OPEN_LINK,
    OPEN_APPLICATION,
    OPEN_FILE,
    OPEN_FOLDER,
    SHOW_RUNNING_APPS,
    SHOW_INSTALLED_APPS,
    TAKE_SCREENSHOT,
    START_SCREEN_RECORDING,
    READ_TELEGRAM_INBOX,
    READ_TELEGRAM_CHAT,
    SEARCH_TELEGRAM,
    SEARCH_TELEGRAM_CHAT,
    SEND_TELEGRAM_MESSAGE,
    SEND_TELEGRAM_ATTACHMENT,
    FORWARD_TELEGRAM_MESSAGE,
    SAVE_TELEGRAM_SAVED_MESSAGES,
    MUTE_TELEGRAM_CHAT,
    ARCHIVE_TELEGRAM_CHAT,
    MARK_READ_TELEGRAM_CHAT,
    DELETE_TELEGRAM_CHAT,
}

interface ToolActionListener {
    suspend fun onToolStarted(actionId: String, descriptor: ToolActionDescriptor)
    suspend fun onToolFinished(actionId: String, success: Boolean)
}

object ToolActionValueFormatter {
    fun compactText(value: String?, maxLength: Int = 72): String? {
        val normalized = value
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return if (normalized.length <= maxLength) normalized else normalized.take(maxLength - 1).trimEnd() + "..."
    }

    fun fileName(path: String?): String? {
        val normalized = normalizedPath(path) ?: return null
        return compactText(File(normalized).name.ifBlank { normalized })
    }

    fun folderName(path: String?): String? {
        val normalized = normalizedPath(path) ?: return null
        return compactText(File(normalized).name.ifBlank { normalized })
    }

    fun host(url: String?): String? {
        val normalized = compactText(url, maxLength = 120) ?: return null
        val host = runCatching { URI(normalized).host }.getOrNull()
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }
        return compactText(host ?: normalized)
    }

    fun appTarget(target: String?): String? {
        val normalized = compactText(target, maxLength = 120) ?: return null
        return when {
            normalized.startsWith("http://") || normalized.startsWith("https://") -> host(normalized)
            normalized.contains('/') || normalized.contains('\\') -> {
                val name = File(normalized.trimEnd('/', '\\')).nameWithoutExtension
                compactText(name.ifBlank { File(normalized).name.ifBlank { normalized } })
            }
            normalized.contains('.') -> compactText(normalized.substringAfterLast('.'))
            else -> compactText(normalized)
        }
    }

    private fun normalizedPath(path: String?): String? =
        path
            ?.trim()
            ?.trimEnd('/', '\\')
            ?.takeIf { it.isNotEmpty() }
}
