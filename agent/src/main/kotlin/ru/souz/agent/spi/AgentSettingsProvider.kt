package ru.souz.agent.spi

import ru.souz.agent.AgentId
import ru.souz.agent.runtime.DEFAULT_SUBAGENT_TASK_TIMEOUT
import ru.souz.llms.LLMModel
import kotlin.time.Duration

/**
 * Minimal settings surface the agent runtime needs from the host application.
 *
 * This keeps the agent module decoupled from the full application settings
 * store while still allowing it to read and persist the values that affect
 * agent behavior.
 */
interface AgentSettingsProvider {
    /** Host-wide cooperative deadline for each child, independent of provider request timeouts. */
    val subagentTaskTimeout: Duration get() = DEFAULT_SUBAGENT_TASK_TIMEOUT

    /** Returns a model-specific system prompt override if one was saved by the host. */
    fun getSystemPromptForAgentModel(agentId: AgentId, model: LLMModel): String?

    /** Persists a model-specific system prompt override or clears it when null. */
    fun setSystemPromptForAgentModel(agentId: AgentId, model: LLMModel, prompt: String?)

    /** Default calendar name injected into additional prompt context when available. */
    var defaultCalendar: String?

    /** Active regional profile used to resolve default prompts and other behavior. */
    var regionProfile: String

    /** Currently selected agent implementation. */
    var activeAgentId: AgentId

    /** Currently selected chat model for the agent. */
    var gigaModel: LLMModel

    /** Host-resolved provider request ID, including custom deployment selections. */
    fun executionModelId(model: LLMModel): String = model.alias

    /** Whether the host prefers streaming LLM responses. */
    var useStreaming: Boolean

    /** Max context window to request from the model. */
    var contextSize: Int

    /** Sampling temperature for model requests. */
    var temperature: Float
}
