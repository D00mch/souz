package ru.souz.memory

enum class ExplicitMemoryIntent {
    SAVE,
    SKIP,
    NONE
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
    if (negatives.any { normalized.contains(it) }) return ExplicitMemoryIntent.SKIP

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
    if (positives.any { normalized.contains(it) }) return ExplicitMemoryIntent.SAVE
    if (normalized.isExplicitForgetIntent()) return ExplicitMemoryIntent.SKIP
    return ExplicitMemoryIntent.NONE
}

fun buildExplicitRememberCandidate(input: MemoryCaptureInput): MemoryFactCandidate? {
    if (parseExplicitMemoryIntent(input.userMessage) != ExplicitMemoryIntent.SAVE) return null
    val body = input.userMessage.trim()
        .removeExplicitRememberMarkers()
        .takeIf(String::isNotBlank)
        ?: return null
    return MemoryFactCandidate(
        shouldSave = true,
        kind = inferExplicitMemoryKind(body),
        title = body.substringBefore('\n').substringBefore('.').trim().take(96).ifBlank { "Remembered note" },
        body = body,
        scope = input.primaryScope,
        slotKey = body.toMemorySlotKeyOrNull(),
        confidence = 0.75f,
        evidenceText = input.userMessage.trim().take(240),
    )
}

private fun inferExplicitMemoryKind(text: String): MemoryFactKind {
    val normalized = text.lowercase()
    return when {
        listOf("prefer", "предпоч", "хочу", "please use", "используй").any(normalized::contains) ->
            MemoryFactKind.PREFERENCE
        listOf("always", "всегда", "rule", "правило", "policy").any(normalized::contains) ->
            MemoryFactKind.PROJECT_RULE
        listOf("procedure", "workflow", "процед", "шаг").any(normalized::contains) ->
            MemoryFactKind.PROCEDURE
        else -> MemoryFactKind.SEMANTIC
    }
}

private fun String.removeExplicitRememberMarkers(): String {
    val normalized = trim()
    val markers = listOf(
        "remember that",
        "remember",
        "from now on",
        "с этого момента",
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

private fun String.toMemorySlotKeyOrNull(): String? =
    lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .takeIf { it.length in 4..64 }

private fun String.isExplicitForgetIntent(): Boolean {
    val trimmed = trim()
    if (trimmed == "forget" || trimmed == "забудь") return true
    return EXPLICIT_FORGET_PATTERNS.any { it.containsMatchIn(this) }
}

private val EXPLICIT_FORGET_PATTERNS = listOf(
    Regex("""\bforget\s+(?:this|that|it|everything|all this)\b"""),
    Regex("""\bforget\s+about\s+(?:this|that|it)\b"""),
    Regex("""\bзабудь\s+(?:это|все|всё|все это|всё это)\b"""),
    Regex("""\bзабудь\s+об\s+этом\b"""),
)
