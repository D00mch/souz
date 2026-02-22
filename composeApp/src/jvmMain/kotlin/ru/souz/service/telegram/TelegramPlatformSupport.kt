package ru.souz.service.telegram

object TelegramPlatformSupport {
    const val MIN_MACOS_MAJOR_VERSION: Int = 15
    const val UNSUPPORTED_MACOS_MESSAGE: String =
        "Telegram is unavailable on this macOS version. Minimum supported version is macOS 15."

    fun isSupported(): Boolean = unsupportedReason() == null

    fun unsupportedReason(): String? {
        if (!isMacOs()) return null

        val major = parseMacOsMajorVersion(System.getProperty("os.version")) ?: return null
        return if (major < MIN_MACOS_MAJOR_VERSION) {
            UNSUPPORTED_MACOS_MESSAGE
        } else {
            null
        }
    }

    private fun isMacOs(): Boolean =
        System.getProperty("os.name")
            ?.contains("Mac", ignoreCase = true) == true

    internal fun parseMacOsMajorVersion(rawVersion: String?): Int? {
        val value = rawVersion?.trim().orEmpty()
        if (value.isBlank()) return null
        return value.substringBefore('.').toIntOrNull()
    }
}
