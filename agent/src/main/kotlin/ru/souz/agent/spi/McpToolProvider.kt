package ru.souz.agent.spi

import ru.souz.llms.LLMToolSetup

/**
 * Supplies dynamic MCP-backed tools to the agent runtime.
 *
 * The host remains responsible for server discovery, authentication, and tool
 * adapter creation; the agent only asks for the currently available tools.
 */
interface McpToolProvider {
    /** Returns the MCP tools that are currently discoverable and callable. */
    suspend fun tools(): List<LLMToolSetup>
}
