package ru.souz.llms

/** Null means unknown capabilities: use provider defaults unless the host supplies an explicit list. */
fun supportedReasoningEfforts(provider: LlmProvider, model: String): List<String>? {
    if (provider != LlmProvider.OPENAI && provider != LlmProvider.CODEX) return emptyList()
    val id = (findLLMModel(model)?.takeIf { it.provider == provider }?.alias ?: model)
        .replace(Regex("-\\d{4}-\\d{2}-\\d{2}$"), "")
    val standard = listOf("low", "medium", "high")
    return when (id) {
        "gpt-5", "gpt-5-mini", "gpt-5-nano" -> listOf("minimal") + standard
        "gpt-5.1" -> listOf("none") + standard
        "gpt-5.2", "gpt-5.4", "gpt-5.5" -> listOf("none") + standard + "xhigh"
        "gpt-5.2-codex", "gpt-5.3-codex" -> standard + "xhigh"
        "gpt-5.6", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna", "gpt-6-sol", "gpt-6-luna" ->
            listOf("none") + standard + listOf("xhigh", "max")
        "gpt-6-astra", "gpt-6.1-sol" -> standard + listOf("xhigh", "max")
        else -> null
    }?.let { efforts ->
        if (provider == LlmProvider.CODEX && id in codexUltraModels) efforts + "ultra"
        else efforts
    }
}

private val codexUltraModels = setOf("gpt-5.6-sol", "gpt-5.6-terra", "gpt-6-sol", "gpt-6-astra", "gpt-6.1-sol")
