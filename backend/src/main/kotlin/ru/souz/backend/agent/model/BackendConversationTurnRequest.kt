package ru.souz.backend.agent.model

import ru.souz.agent.spi.ModelRouting

/** Internal request model for one chat-oriented backend agent turn. */
internal data class BackendConversationTurnRequest(
    val prompt: String,
    val model: String,
    val reasoningEffort: String? = null,
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
    /** Destructive owner-wide memory tools require an authenticated backend entry point. */
    val memoryMutationAllowed: Boolean = false,
    val narrateSteps: Boolean = false,
    /** Set only for a new turn that may be routed by complexity. */
    val routing: ModelRouting? = null,
)
