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
        /** [attributes] key for the calling agent's own dispatchable tool names, set by
         * `AgentToolExecutor`. Read by composite skill commands to scope `tool:` steps. */
        const val ACTIVE_TOOL_NAMES_ATTRIBUTE = "souz.activeToolNames"

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
