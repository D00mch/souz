package ru.souz.llms

/** A built-in model or a host-configured model name, persisted using [alias]. */
sealed interface ChatModelSelection {
    val alias: String
    val provider: LlmProvider

    data class Named(override val alias: String) : ChatModelSelection {
        override val provider: LlmProvider get() = LlmProvider.OPENAI
    }
}
