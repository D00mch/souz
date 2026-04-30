package ru.souz.backend.settings.model

import java.time.ZoneId
import java.util.Locale
import ru.souz.llms.LLMModel

data class EffectiveUserSettings(
    val userId: String,
    val defaultModel: LLMModel,
    val contextSize: Int,
    val temperature: Float,
    val locale: Locale,
    val timeZone: ZoneId,
    val systemPrompt: String?,
    val enabledTools: Set<String>,
    val showToolEvents: Boolean,
    val streamingMessages: Boolean,
    val toolPermissions: Map<String, ToolPermission>,
    val mcp: Map<String, UserMcpServer>,
)
