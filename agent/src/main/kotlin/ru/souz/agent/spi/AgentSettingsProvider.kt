package ru.souz.agent.spi

import ru.souz.agent.AgentId
import ru.souz.llms.LLMModel

/**
 * Minimal settings surface the agent runtime needs from the host application.
 *
 * This keeps execution code decoupled from a host's mutable settings store.
 */
interface AgentRuntimeSettings {
    /** Returns a model-specific system prompt override if one was saved by the host. */
    fun getSystemPromptForAgentModel(agentId: AgentId, model: LLMModel): String?

    /** Default calendar name injected into additional prompt context when available. */
    val defaultCalendar: String?

    /** Active regional profile used to resolve default prompts and other behavior. */
    val regionProfile: String

    /** Currently selected agent implementation. */
    val activeAgentId: AgentId

    /** Currently selected chat model for the agent. */
    val gigaModel: LLMModel

    /** Whether the host prefers streaming LLM responses. */
    val useStreaming: Boolean

    /** Max context window to request from the model. */
    val contextSize: Int

    /** Sampling temperature for model requests. */
    val temperature: Float
}

/** Mutable host settings contract used by configuration flows. */
interface AgentSettingsProvider : AgentRuntimeSettings {
    /** Persists a model-specific system prompt override or clears it when null. */
    fun setSystemPromptForAgentModel(agentId: AgentId, model: LLMModel, prompt: String?)

    override var defaultCalendar: String?
    override var regionProfile: String
    override var activeAgentId: AgentId
    override var gigaModel: LLMModel
    override var useStreaming: Boolean
    override var contextSize: Int
    override var temperature: Float
}
