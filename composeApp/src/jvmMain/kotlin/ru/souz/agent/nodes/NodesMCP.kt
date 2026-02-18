package ru.souz.agent.nodes

import org.slf4j.LoggerFactory
import ru.souz.agent.engine.AgentContext
import ru.souz.agent.engine.AgentSettings
import ru.souz.agent.engine.Node
import ru.souz.giga.GigaToolSetup
import ru.souz.mcp.McpClientManager
import kotlin.jvm.java

class NodesMCP(private val mcpClientManager: McpClientManager) {
    private val l = LoggerFactory.getLogger(NodesMCP::class.java)

    /**
     * Modifies [AgentContext.activeTools], [AgentSettings.tools] with available MCP tools
     */
    fun nodeProvideMcpTools(name: String): Node<String, String> = Node(name) { ctx ->
        val mcpTools: List<GigaToolSetup> = runCatching { mcpClientManager.tools() }
            .onFailure { e -> l.warn("Failed to load MCP tools", e) }
            .getOrElse { emptyList() }

        val mcpByName: Map<String, GigaToolSetup> = mcpTools.associateBy { it.fn.name }
        val updatedSettings: AgentSettings = ctx.settings.copy(tools = ctx.settings.tools + mcpByName)
        val updatedActiveTools = (ctx.activeTools + mcpTools.map { it.fn })
            .distinctBy { it.name }

        ctx.map(settings = updatedSettings, activeTools = updatedActiveTools) { it }
    }
}