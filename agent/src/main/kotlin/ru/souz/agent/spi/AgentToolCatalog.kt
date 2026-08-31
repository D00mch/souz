package ru.souz.agent.spi

import ru.souz.llms.LLMToolSetup
import ru.souz.tool.ToolCategory

/**
 * Exposes the host-defined tool catalog to the agent.
 *
 * The host owns tool construction and registration, while the agent consumes a
 * ready-to-use grouped view.
 */
interface AgentToolCatalog {
    /** Host-provided tools in this catalog, grouped by category. */
    val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>
}
