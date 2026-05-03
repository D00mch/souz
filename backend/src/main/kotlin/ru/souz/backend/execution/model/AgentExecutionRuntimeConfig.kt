package ru.souz.backend.execution.model

import com.fasterxml.jackson.module.kotlin.readValue
import ru.souz.backend.settings.model.EffectiveUserSettings
import ru.souz.llms.restJsonMapper

/** Runtime-critical settings captured when an execution is created. */
data class AgentExecutionRuntimeConfig(
    val modelAlias: String,
    val contextSize: Int,
    val temperature: Float?,
    val locale: String,
    val timeZone: String,
    val systemPrompt: String?,
    val streamingMessages: Boolean,
    val showToolEvents: Boolean,
) {
    fun toMetadata(): Map<String, String> =
        mapOf(METADATA_RUNTIME_CONFIG to restJsonMapper.writeValueAsString(this))

    companion object {
        fun from(settings: EffectiveUserSettings): AgentExecutionRuntimeConfig =
            AgentExecutionRuntimeConfig(
                modelAlias = settings.defaultModel.alias,
                contextSize = settings.contextSize,
                temperature = settings.temperature,
                locale = settings.locale.toLanguageTag(),
                timeZone = settings.timeZone.id,
                systemPrompt = settings.systemPrompt,
                streamingMessages = settings.streamingMessages,
                showToolEvents = settings.showToolEvents,
            )

        fun fromExecution(execution: AgentExecution): AgentExecutionRuntimeConfig {
            execution.metadata[METADATA_RUNTIME_CONFIG]?.let { encoded ->
                runCatching { restJsonMapper.readValue<AgentExecutionRuntimeConfig>(encoded) }
                    .getOrNull()
                    ?.let { return it }
            }
            return legacyFrom(execution)
        }

        private fun legacyFrom(execution: AgentExecution): AgentExecutionRuntimeConfig =
            AgentExecutionRuntimeConfig(
                modelAlias = execution.model?.alias
                    ?: error("Execution model is missing."),
                contextSize = execution.metadata[METADATA_CONTEXT_SIZE]?.toIntOrNull()
                    ?: error("Execution contextSize is missing."),
                temperature = execution.metadata[METADATA_TEMPERATURE]?.toFloatOrNull(),
                locale = execution.metadata[METADATA_LOCALE]
                    ?: error("Execution locale is missing."),
                timeZone = execution.metadata[METADATA_TIME_ZONE]
                    ?: error("Execution timeZone is missing."),
                systemPrompt = execution.metadata[METADATA_SYSTEM_PROMPT]?.takeIf { it.isNotEmpty() },
                streamingMessages = execution.metadata[METADATA_STREAMING_MESSAGES]?.toBooleanStrictOrNull()
                    ?: false,
                showToolEvents = execution.metadata[METADATA_SHOW_TOOL_EVENTS]?.toBooleanStrictOrNull()
                    ?: false,
            )
    }
}

const val METADATA_RUNTIME_CONFIG = "runtimeConfig"

private const val METADATA_CONTEXT_SIZE = "contextSize"
private const val METADATA_TEMPERATURE = "temperature"
private const val METADATA_LOCALE = "locale"
private const val METADATA_TIME_ZONE = "timeZone"
private const val METADATA_SYSTEM_PROMPT = "systemPrompt"
private const val METADATA_STREAMING_MESSAGES = "streamingMessages"
private const val METADATA_SHOW_TOOL_EVENTS = "showToolEvents"
