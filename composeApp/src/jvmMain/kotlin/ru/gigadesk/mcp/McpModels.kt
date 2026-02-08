package ru.gigadesk.mcp

import com.fasterxml.jackson.databind.JsonNode

data class McpServerConfig(
    val name: String,
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val cwd: String? = null,
    val timeoutMillis: Long = 30_000L,
)

data class McpRemoteTool(
    val name: String,
    val description: String,
    val inputSchema: JsonNode?,
)

data class McpToolCallResult(
    val text: String,
    val isError: Boolean,
    val raw: JsonNode,
)

