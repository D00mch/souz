package ru.souz.backend.settings.model

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

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
    val defaultModel: String? = null,
    val reasoningEffort: String? = null,
    val temperature: Float? = null,
    val locale: Locale? = null,
    val timeZone: ZoneId? = null,
    val systemPrompt: String? = null,
    val personalityPrompt: String? = null,
    val personalityRevision: Long = 1,
    /**
     * Null enables every tool the backend advertises, including tools added later; a selection of
     * every advertised tool is stored as null. A set enables exactly those tools; empty enables none.
     */
    val enabledTools: Set<String>? = null,
    val showToolEvents: Boolean? = null,
    val streamingMessages: Boolean? = null,
    val narrateSteps: Boolean? = null,
    val intermediateMessages: Boolean? = null,
    val interfaceLanguage: String? = null,
    val requestTimeoutMillis: Long? = null,
    val useFewShotExamples: Boolean? = null,
    val toolPermissions: Map<String, ToolPermission> = emptyMap(),
    val mcp: Map<String, UserMcpServer> = emptyMap(),
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val onboardingCompletedAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = createdAt,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 5
    }
}
