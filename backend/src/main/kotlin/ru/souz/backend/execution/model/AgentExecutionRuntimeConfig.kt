package ru.souz.backend.execution.model

data class AgentExecutionRuntimeConfig(
    val modelAlias: String,
    val contextSize: Int,
    val temperature: Float?,
    val locale: String,
    val timeZone: String,
    val systemPrompt: String?,
    val streamingMessages: Boolean,
    val showToolEvents: Boolean,
)
