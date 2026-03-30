package ru.souz.agent.spi

import ru.souz.llms.LLMToolSetup
import ru.souz.tool.ToolCategory

/**
 * Exposes the complete host-defined tool catalog to the agent.
 *
 * The host owns tool construction and registration, while the agent consumes a
 * ready-to-use grouped view.
 */
interface AgentToolCatalog {
    /** Tools grouped by category, before per-session filtering is applied. */
    val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>
}
