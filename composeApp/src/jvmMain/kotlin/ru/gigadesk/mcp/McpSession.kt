package ru.gigadesk.mcp

interface McpSession : AutoCloseable {
    suspend fun listTools(): List<McpRemoteTool>
    suspend fun callTool(
        toolName: String,
        arguments: Map<String, Any>,
    ): McpToolCallResult
}
