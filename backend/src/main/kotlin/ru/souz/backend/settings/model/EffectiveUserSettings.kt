package ru.souz.backend.settings.model

import java.time.ZoneId
import java.util.Locale

data class EffectiveUserSettings(
    val userId: String,
    val defaultModel: String,
    val reasoningEffort: String? = null,
    val temperature: Float,
    val locale: Locale,
    val timeZone: ZoneId,
    val systemPrompt: String?,
    val personalityPrompt: String? = null,
    val personalityRevision: Long = 1,
    val enabledTools: Set<String>,
    val disabledPlugins: Set<String> = emptySet(),
    val showToolEvents: Boolean,
    val streamingMessages: Boolean,
    val narrateSteps: Boolean = false,
    val interfaceLanguage: String,
    val requestTimeoutMillis: Long,
    val useFewShotExamples: Boolean,
    val toolPermissions: Map<String, ToolPermission>,
    val mcp: Map<String, UserMcpServer>,
)
