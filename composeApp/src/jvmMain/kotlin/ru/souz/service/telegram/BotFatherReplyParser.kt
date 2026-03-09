package ru.souz.service.telegram

internal data class BotFatherMessageSnapshot(
    val id: Long,
    val text: String?,
    val isOutgoing: Boolean,
)

internal object BotFatherReplyParser {
    private val tokenRegex = Regex("""\d{8,10}:[a-zA-Z0-9_-]{35,}""")
    private val botUsernameRegex = Regex("""@([a-z0-9_]{5,}bot)\b""")

    private fun latestIncomingTextRaw(messages: List<BotFatherMessageSnapshot>): String? {
        return messages.asSequence()
            .filter { !it.isOutgoing }
            .mapNotNull { it.text }
            .firstOrNull() // TDLib returns newest messages first
    }

    private fun latestIncomingText(messages: List<BotFatherMessageSnapshot>): String? {
        return latestIncomingTextRaw(messages)?.lowercase()
    }

    fun extractToken(messages: List<BotFatherMessageSnapshot>): String? {
        val text = latestIncomingTextRaw(messages) ?: return null
        return tokenRegex.find(text)?.value
    }

    fun isDeleteConfirmed(
        messages: List<BotFatherMessageSnapshot>,
        username: String,
    ): Boolean {
        val normalizedUsername = username.trim().removePrefix("@").lowercase()
        val text = latestIncomingText(messages) ?: return false
        val mentionsBot = normalizedUsername.isBlank() || text.contains(normalizedUsername)
        val looksLikeSuccess =
            text.contains("deleted") ||
                text.contains("deactivated") ||
                text.contains("done")
        val genericSuccess = text.contains("bot is gone")
        return (mentionsBot && looksLikeSuccess) || genericSuccess
    }

    fun requiresDeleteConfirmationText(
        messages: List<BotFatherMessageSnapshot>,
    ): Boolean {
        val text = latestIncomingText(messages) ?: return false
        return text.contains("yes, i am totally sure.")
    }

    fun hasNoBots(
        messages: List<BotFatherMessageSnapshot>,
    ): Boolean {
        val text = latestIncomingText(messages) ?: return false
        return text.contains("don't have any bots yet") ||
            text.contains("you don't have any bots") ||
            text.contains("not among your bots") ||
            text.contains("you have currently no bots")
    }

    fun listedBotUsernames(
        messages: List<BotFatherMessageSnapshot>,
    ): Set<String> {
        val text = latestIncomingText(messages) ?: return emptySet()
        return botUsernameRegex.findAll(text).map { it.groupValues[1] }.toSet()
    }

    fun isWaitingForName(messages: List<BotFatherMessageSnapshot>): Boolean {
        val text = latestIncomingText(messages) ?: return false
        return text.contains("how are we going to call it?") || text.contains("choose a name for your bot")
    }

    fun isWaitingForUsername(messages: List<BotFatherMessageSnapshot>): Boolean {
        val text = latestIncomingText(messages) ?: return false
        return text.contains("choose a username for your bot")
    }

    fun isWaitingForProfilePhoto(messages: List<BotFatherMessageSnapshot>): Boolean {
        val text = latestIncomingText(messages) ?: return false
        val asksForProfilePhoto = text.contains("send me") && text.contains("profile photo")
        val asksForPhoto = text.contains("send me") && text.contains("photo for the bot")
        return asksForProfilePhoto || asksForPhoto
    }
}
