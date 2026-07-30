package ru.souz.memory

enum class ExplicitMemoryIntent {
    NONE,
    REMEMBER_SIGNAL,
    DO_NOT_CAPTURE_THIS_TURN,
    FORGET_EXISTING,
    DELETE_EXISTING,
}

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

    val deletes = listOf(
        "удали из памяти",
        "полностью удали",
        "delete from memory",
        "delete this memory",
    )
    if (deletes.any { normalized.contains(it) }) return ExplicitMemoryIntent.DELETE_EXISTING

    val positives = listOf(
        "запомни, что",
        "запомни",
        "remember that",
        "don't forget",
        "do not forget",
        "from now on",
        "с этого момента",
        "не забудь",
    )
    if (positives.any { normalized.contains(it) }) return ExplicitMemoryIntent.REMEMBER_SIGNAL
    if (normalized.isExplicitForgetIntent()) return ExplicitMemoryIntent.FORGET_EXISTING
    return ExplicitMemoryIntent.NONE
}

fun buildExplicitRememberCandidate(input: MemoryCaptureInput): MemoryFactCandidate? {
    if (parseExplicitMemoryIntent(input.userMessage) != ExplicitMemoryIntent.REMEMBER_SIGNAL) return null
    val body = input.userMessage.trim()
        .removeExplicitRememberMarkers()
        .takeIf(String::isNotBlank)
        ?: return null
    return MemoryFactCandidate(
        shouldSave = true,
        kind = MemoryFactKind.SEMANTIC,
        title = body.substringBefore('\n').substringBefore('.').trim().take(96).ifBlank { "Remembered note" },
        body = body,
        requestedScope = RequestedMemoryScope.GLOBAL,
        canonicalKey = null,
        confidence = 0.75f,
        evidenceText = input.userMessage.trim().take(240),
    )
}


private fun String.removeExplicitRememberMarkers(): String {
    val normalized = trim()
    val markers = listOf(
        "remember that",
        "remember",
        "from now on",
        "с этого момента",
        "не забудь, что",
        "не забудь",
        "запомни, что",
        "запомни",
        "запиши",
        "в будущем учитывай",
        "учитывай дальше"
    )
    val lower = normalized.lowercase()
    val marker = markers.firstOrNull(lower::contains) ?: return normalized
    val start = lower.indexOf(marker)
    return normalized.removeRange(start, start + marker.length)
        .trim()
        .trimStart(':', '-', ',', ' ')
}

private fun String.isExplicitForgetIntent(): Boolean {
    val trimmed = trim()
    if (trimmed == "forget" || trimmed == "забудь") return true
    return EXPLICIT_FORGET_PATTERNS.any { it.containsMatchIn(this) }
}

private val EXPLICIT_FORGET_PATTERNS = listOf(
    Regex("""(?U)\bforget\s+(?:that|what|about)\b"""),
    Regex("""(?U)\bforget\s+(?:this|that|it|everything|all this)\b"""),
    Regex("""(?U)\bforget\s+about\s+(?:this|that|it)\b"""),
    Regex("""(?U)\bзабудь,\s*что\b"""),
    Regex("""(?U)\bзабудь\s+что\b"""),
    Regex("""(?U)\bзабудь\s+(?:это|все|всё|все это|всё это)\b"""),
    Regex("""(?U)\bзабудь\s+об\s+этом\b"""),
)
