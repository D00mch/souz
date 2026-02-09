package ru.gigadesk.mcp

import com.fasterxml.jackson.databind.JsonNode

/**
 * Shared MCP tool-level protocol operations built on top of transport-specific request/init callbacks.
 */
class McpToolProtocolApi(
    private val initializeIfNeeded: suspend () -> Unit,
    private val request: suspend (method: String, params: Any) -> JsonNode,
) {
    suspend fun listTools(): List<McpRemoteTool> {
        initializeIfNeeded()

        val tools = ArrayList<McpRemoteTool>()
        var cursor: String? = null
        do {
            val result = request("tools/list", cursor?.let { mapOf("cursor" to it) } ?: emptyMap<String, Any>())
            val toolsNode = result.path("tools")
            if (toolsNode.isArray) {
                toolsNode.forEach { node ->
                    val name = node.path("name").asText("").trim()
                    if (name.isBlank()) return@forEach
                    val description = node.path("description").asText("").trim()
                    val schema = node.path("inputSchema").takeIf { !it.isMissingNode && !it.isNull }
                    tools += McpRemoteTool(
                        name = name,
                        description = description,
                        inputSchema = schema,
                    )
                }
            }
            cursor = result.path("nextCursor").asText("").trim().ifBlank { null }
        } while (cursor != null)

        return tools
    }

    suspend fun callTool(
        toolName: String,
        arguments: Map<String, Any>,
    ): McpToolCallResult {
        initializeIfNeeded()
        val result = request(
            "tools/call",
            mapOf(
                "name" to toolName,
                "arguments" to arguments,
            ),
        )

        val textParts = ArrayList<String>()
        val content = result.path("content")
        if (content.isArray) {
            content.forEach { item ->
                when (item.path("type").asText("")) {
                    "text" -> {
                        val text = item.path("text").asText("").trim()
                        if (text.isNotBlank()) textParts += text
                    }

                    "resource", "image", "audio" -> textParts += item.toString()
                    else -> if (!item.isMissingNode && !item.isNull) textParts += item.toString()
                }
            }
        }

        val structured = result.path("structuredContent")
        if (textParts.isEmpty() && !structured.isMissingNode && !structured.isNull) {
            textParts += structured.toString()
        }
        if (textParts.isEmpty()) {
            textParts += result.toString()
        }

        return McpToolCallResult(
            text = textParts.joinToString("\n").trim(),
            isError = result.path("isError").asBoolean(false),
            raw = result,
        )
    }
}
