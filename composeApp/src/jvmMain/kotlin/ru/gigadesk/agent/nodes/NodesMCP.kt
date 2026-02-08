package ru.gigadesk.agent.nodes

import org.slf4j.LoggerFactory
import ru.gigadesk.agent.engine.Node
import ru.gigadesk.giga.GigaToolSetup
import ru.gigadesk.mcp.McpClientManager
import kotlin.jvm.java

class NodesMCP(private val mcpClientManager: McpClientManager) {
    private val l = LoggerFactory.getLogger(NodesMCP::class.java)

    /** Modifies [ru.gigadesk.agent.engine.AgentContext.activeTools] with MCP tools if active */
    fun nodeProvideMcpTools(name: String): Node<String, String> = Node(name) { ctx ->
        val mcpTools: List<GigaToolSetup> = runCatching { mcpClientManager.tools() }
            .onFailure { e -> l.warn("Failed to load MCP tools", e) }
            .getOrElse { emptyList() }

        val updatedActiveTools = (ctx.activeTools + mcpTools.map { it.fn })

        ctx.map(activeTools = updatedActiveTools) { it }
    }
}