package ru.souz.memory

/**
 * Redacts obvious secrets and private local data before memory is persisted.
 */
object MemorySanitizer {
    private val apiKeysRegex = Regex(
        "(?i)(api[-_]?key|secret|token|password|auth|authorization|credential)[\\s]*[:=][\\s]*[\"']?[a-zA-Z0-9_\\-\\.\\~]{10,}[\"']?"
    )
    private val authHeaderRegex = Regex(
        "(?i)Authorization:\\s*(Bearer|Basic)\\s+[a-zA-Z0-9_\\-\\.\\~\\+/=]{10,}"
    )
    private val bearerTokenRegex = Regex(
        "(?i)(bearer\\s+[a-zA-Z0-9_\\-\\.\\~\\+/=]{15,})"
    )
    private val envVarsRegex = Regex(
        "[A-Z0-9_]{3,30}=(?:[a-zA-Z0-9_\\-\\.\\~]{8,})"
    )
    private val longTokenRegex = Regex(
        "[a-zA-Z0-9_\\-\\.\\+/=]{64,}"
    )
    private val hexRegex = Regex(
        "\\b[a-fA-F0-9]{32,}\\b"
    )
    private val emailRegex = Regex(
        "[a-zA-Z0-9_\\-\\.\\+]+@[a-zA-Z0-9_\\-\\.]+\\.[a-zA-Z]{2,}"
    )
    private val filePathRegex = Regex(
        """(?:/Users/[^\s"'<>]+|/home/[^\s"'<>]+|~/[^\s"'<>]+|[A-Za-z]:\\Users\\[^\s"'<>]+)"""
    )

    fun redact(text: String): String {
        var result = text
        result = result.replace(apiKeysRegex) { match ->
            val key = match.groupValues[1]
            "$key=[redacted-secret]"
        }
        result = result.replace(authHeaderRegex, "Authorization: [redacted-auth]")
        result = result.replace(bearerTokenRegex, "[redacted-auth]")
        result = result.replace(envVarsRegex) { match ->
            val parts = match.value.split("=", limit = 2)
            parts[0] + "=[redacted-secret]"
        }
        result = result.replace(longTokenRegex, "[redacted-secret]")
        result = result.replace(hexRegex, "[redacted-secret]")
        result = result.replace(emailRegex, "[redacted-email]")
        result = result.replace(filePathRegex, "[redacted-path]")
        return result
    }

    fun looksSecret(text: String): Boolean =
        apiKeysRegex.containsMatchIn(text) ||
            authHeaderRegex.containsMatchIn(text) ||
            bearerTokenRegex.containsMatchIn(text) ||
            envVarsRegex.containsMatchIn(text) ||
            longTokenRegex.containsMatchIn(text) ||
            hexRegex.containsMatchIn(text)
}
