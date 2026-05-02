package ru.souz.llms

data class ToolInvocationMeta(
    val userId: String? = null,
    val conversationId: String? = null,
    val requestId: String? = null,
    val locale: String? = null,
    val timeZone: String? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    companion object {
        val Empty = ToolInvocationMeta()
    }
}
