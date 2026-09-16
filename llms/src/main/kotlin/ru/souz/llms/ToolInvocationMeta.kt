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
        /** [attributes] key `AgentToolExecutor` sets to the comma-joined names of every tool the
         * calling agent could execute at dispatch time (`AgentSettings.tools.byName.keys`) — the
         * exact same set it uses to resolve `functionCall.name` itself. Skill-invocation tools
         * (`RunSkillCommand`/`CompositeCommandExecutor`) read it back to scope which tools a
         * declarative composite command step may call, so a composite step can never reach a tool
         * the calling agent (parent or a spawned child) wasn't already allowed to call directly.
         * Absent when a tool is invoked outside that dispatch path (e.g. `invoke(functionCall)`
         * with no meta, or a test calling a tool directly) — treat absence as "no tools allowed",
         * fail closed, the same convention the Skill Tool Bridge's allowlist already uses. */
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
