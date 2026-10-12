package ru.souz.backend.agent.model

import ru.souz.llms.ChatModelSelection

/** Internal request model for one chat-oriented backend agent turn. */
internal data class BackendConversationTurnRequest(
    val prompt: String,
    val model: ChatModelSelection,
    val reasoningEffort: String? = null,
    val contextSize: Int,
    val locale: String,
    val timeZone: String,
    val executionId: String? = null,
    val inputMessageSeq: Long? = null,
    val temperature: Float? = null,
    val systemPrompt: String? = null,
    val streamingMessages: Boolean? = null,
    val requestTimeoutMillis: Long? = null,
    val useFewShotExamples: Boolean? = null,
    val enabledTools: Set<String>? = null,
    val clientToolsEnabled: Boolean = false,
    val narrateSteps: Boolean = false,
)
