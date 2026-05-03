package ru.souz.backend.settings.model

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import ru.souz.llms.LLMModel

enum class ToolPermissionMode {
    ALLOW,
    ASK,
    DENY,
}

data class ToolPermission(
    val mode: ToolPermissionMode,
)

data class UserMcpServer(
    val enabled: Boolean,
    val command: String? = null,
    val url: String? = null,
)

data class UserSettings(
    val userId: String,
    val defaultModel: LLMModel? = null,
    val contextSize: Int? = null,
    val temperature: Float? = null,
    val locale: Locale? = null,
    val timeZone: ZoneId? = null,
    val systemPrompt: String? = null,
    val enabledTools: Set<String>? = null,
    val showToolEvents: Boolean? = null,
    val streamingMessages: Boolean? = null,
    val toolPermissions: Map<String, ToolPermission> = emptyMap(),
    val mcp: Map<String, UserMcpServer> = emptyMap(),
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val onboardingCompletedAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = createdAt,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 2
    }
}
