package ru.souz.agent.spi

import ru.souz.llms.giga.GigaToolSetup
import ru.souz.tool.ToolCategory

/**
 * Applies host-side policy to the full tool catalog.
 *
 * Typical implementations remove disabled tools, apply platform restrictions,
 * and inject user-customized descriptions/examples.
 */
interface AgentToolsFilter {
    /**
     * Returns the tool catalog visible to the current agent step.
     */
    fun applyFilter(
        toolsByCategory: Map<ToolCategory, Map<String, GigaToolSetup>>,
    ): Map<ToolCategory, Map<String, GigaToolSetup>>
}
