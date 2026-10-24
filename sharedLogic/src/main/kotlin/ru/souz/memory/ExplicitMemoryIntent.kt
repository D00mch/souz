package ru.souz.memory

enum class ExplicitMemoryIntent {
    NONE,
    REMEMBER_SIGNAL,
    DO_NOT_CAPTURE_THIS_TURN,
    FORGET_EXISTING,
    DELETE_EXISTING,
}

private val EXPLICIT_REMEMBER_MARKERS = listOf(
    "запомни, что",
    "запомни",
    "remember that",
    "don't forget that",
    "don't forget",
    "do not forget that",
    "do not forget",
    "from now on",
    "с этого момента",
    "не забудь, что",
    "не забудь",
)

fun parseExplicitMemoryIntent(text: String): ExplicitMemoryIntent {
    val normalized = text.lowercase()
    val negatives = listOf(
        "не запоминай",
        "не нужно запоминать",
        "don't remember",
        "do not remember",
        "don't save",
        "do not save",
    )
    if (negatives.any { normalized.contains(it) }) return ExplicitMemoryIntent.DO_NOT_CAPTURE_THIS_TURN

    val command = text.explicitCommandText().lowercase()

    val deletes = listOf(
        "удали из памяти",
        "полностью удали",
        "delete from memory",
        "delete this memory",
    )
    if (deletes.any(command::startsWith)) return ExplicitMemoryIntent.DELETE_EXISTING

    if (EXPLICIT_REMEMBER_MARKERS.any(command::startsWith)) return ExplicitMemoryIntent.REMEMBER_SIGNAL
    if (command.isExplicitForgetIntent()) return ExplicitMemoryIntent.FORGET_EXISTING
    return ExplicitMemoryIntent.NONE
}


private fun String.isExplicitForgetIntent(): Boolean {
    val trimmed = trim()
    if (trimmed == "forget" || trimmed == "забудь") return true
    return EXPLICIT_FORGET_PATTERNS.any { it.containsMatchIn(this) }
}

private fun String.explicitCommandText(): String =
    trimStart().replaceFirst(POLITE_COMMAND_PREFIX, "")

private val POLITE_COMMAND_PREFIX = Regex(
    """(?i)^(?:please[,\s]+|(?:can you|could you|would you)\s+|пожалуйста[,\s]+|(?:можешь|можете)\s+)+"""
)

private val EXPLICIT_FORGET_PATTERNS = listOf(
    Regex("""(?U)^forget\s+(?:that|what|about)\b"""),
    Regex("""(?U)^forget\s+(?:this|that|it|everything|all this)\b"""),
    Regex("""(?U)^forget\s+about\s+(?:this|that|it)\b"""),
    Regex("""(?U)^забудь,\s*что\b"""),
    Regex("""(?U)^забудь\s+что\b"""),
    Regex("""(?U)^забудь\s+(?:это|все|всё|все это|всё это)\b"""),
    Regex("""(?U)^забудь\s+об\s+этом\b"""),
)
