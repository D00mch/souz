package ru.souz.service.telegram

internal data class BotFatherMessageSnapshot(
    val id: Long,
    val text: String?,
    val isOutgoing: Boolean,
)

internal object BotFatherReplyParser {
    private val tokenRegex = Regex("""\d{8,10}:[a-zA-Z0-9_-]{35,}""")
    private val botUsernameRegex = Regex("""@([a-z0-9_]{5,}bot)\b""")

    private fun freshIncomingTexts(messages: List<BotFatherMessageSnapshot>, minMessageIdExclusive: Long): Sequence<String> {
        return messages.asSequence()
            .filter { !it.isOutgoing && it.id > minMessageIdExclusive }
            .mapNotNull { it.text?.lowercase() }
    }

    fun extractToken(messages: List<BotFatherMessageSnapshot>, minMessageIdExclusive: Long): String? {
        return messages.asSequence()
            .filter { !it.isOutgoing && it.id > minMessageIdExclusive }
            .mapNotNull { tokenRegex.find(it.text.orEmpty())?.value }
            .firstOrNull()
    }

    fun isDeleteConfirmed(
        messages: List<BotFatherMessageSnapshot>,
        minMessageIdExclusive: Long,
        username: String,
    ): Boolean {
        val normalizedUsername = username.trim().removePrefix("@").lowercase()
        return freshIncomingTexts(messages, minMessageIdExclusive)
            .any { text ->
                val mentionsBot = normalizedUsername.isBlank() || text.contains(normalizedUsername)
                val looksLikeSuccess =
                    text.contains("deleted") ||
                        text.contains("deactivated") ||
                        text.contains("done")
                val genericSuccess = text.contains("bot is gone")
                (mentionsBot && looksLikeSuccess) || genericSuccess
            }
    }

    fun requiresDeleteConfirmationText(
        messages: List<BotFatherMessageSnapshot>,
        minMessageIdExclusive: Long,
    ): Boolean {
        return freshIncomingTexts(messages, minMessageIdExclusive)
            .any { it.contains("yes, i am totally sure.") }
    }

    fun hasNoBots(
        messages: List<BotFatherMessageSnapshot>,
        minMessageIdExclusive: Long,
    ): Boolean {
        return freshIncomingTexts(messages, minMessageIdExclusive).any { text ->
            text.contains("don't have any bots yet") ||
                text.contains("you don't have any bots") ||
                text.contains("not among your bots")
        }
    }

    fun listedBotUsernames(
        messages: List<BotFatherMessageSnapshot>,
        minMessageIdExclusive: Long,
    ): Set<String> {
        return freshIncomingTexts(messages, minMessageIdExclusive)
            .flatMap { text -> botUsernameRegex.findAll(text).map { it.groupValues[1] } }
            .toSet()
    }

    fun isWaitingForName(messages: List<BotFatherMessageSnapshot>, minMessageIdExclusive: Long): Boolean {
        return freshIncomingTexts(messages, minMessageIdExclusive)
            .any { it.contains("how are we going to call it?") || it.contains("choose a name for your bot") }
    }

    fun isWaitingForUsername(messages: List<BotFatherMessageSnapshot>, minMessageIdExclusive: Long): Boolean {
        return freshIncomingTexts(messages, minMessageIdExclusive)
            .any { it.contains("choose a username for your bot") }
    }
}
