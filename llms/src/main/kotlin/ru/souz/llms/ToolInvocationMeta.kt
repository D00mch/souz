package ru.souz.llms

object LocalUserId {
    fun default(): String =
        System.getProperty("user.name")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: FALLBACK

    private const val FALLBACK = "local-user"
}

data class ToolInvocationMeta(
    val userId: String,
    val conversationId: String? = null,
    val requestId: String? = null,
    val locale: String? = null,
    val timeZone: String? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(userId.isNotBlank()) { "ToolInvocationMeta.userId must not be blank." }
    }

    companion object {
        fun localDefault(
            conversationId: String? = null,
            requestId: String? = null,
            locale: String? = null,
            timeZone: String? = null,
            attributes: Map<String, String> = emptyMap(),
        ): ToolInvocationMeta = ToolInvocationMeta(
            userId = LocalUserId.default(),
            conversationId = conversationId,
            requestId = requestId,
            locale = locale,
            timeZone = timeZone,
            attributes = attributes,
        )
    }
}
