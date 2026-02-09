package ru.gigadesk.mcp

import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import ru.gigadesk.giga.*
import java.util.concurrent.ConcurrentHashMap


class McpClientManager(
    private val configProvider: McpConfigProvider,
) : AutoCloseable {
    private val l = LoggerFactory.getLogger(McpClientManager::class.java)
    private val discoveryLock = Mutex()
    private val sessions = ConcurrentHashMap<String, McpStdioSession>()
    private val discoveryState = MutableStateFlow(McpDiscoveryState())

    suspend fun tools(): List<GigaToolSetup> = ensureDiscovered().toolSetups

    suspend fun refreshTools(): List<GigaToolSetup> = discoveryLock.withLock {
        closeAllSessions()

        val configs = configProvider.loadServers()
        if (configs.isEmpty()) {
            discoveryState.value = McpDiscoveryState(loaded = true)
            l.debug("No MCP servers configured")
            return@withLock emptyList()
        }

        val registered = LinkedHashMap<String, RegisteredMcpTool>()
        val setups = ArrayList<GigaToolSetup>()
        val usedFunctionNames = LinkedHashSet<String>()

        for ((serverName, config) in configs) {
            val session = runCatching { getOrCreateSession(config) }.getOrElse { e ->
                l.warn("Failed to start MCP server {}: {}", serverName, e.message)
                continue
            }
            val tools = runCatching { session.listTools() }.getOrElse { e ->
                l.warn("Failed to list MCP tools for {}: {}", serverName, e.message)
                resetSession(serverName, session)
                continue
            }
            for (remoteTool in tools) {
                val functionName = buildFunctionName(
                    serverName = serverName,
                    toolName = remoteTool.name,
                    occupied = usedFunctionNames,
                )
                val fn = GigaRequest.Function(
                    name = functionName,
                    description = buildDescription(serverName, remoteTool),
                    parameters = schemaToParameters(remoteTool.inputSchema),
                    fewShotExamples = emptyList(),
                    returnParameters = null,
                )
                val tool = RegisteredMcpTool(
                    serverName = serverName,
                    remoteToolName = remoteTool.name,
                    functionName = functionName,
                    fn = fn,
                )
                registered[functionName] = tool
                setups += McpGigaToolSetup(this, tool)
            }
        }

        discoveryState.value = McpDiscoveryState(
            loaded = true,
            serverConfigs = configs,
            toolsByName = registered,
            toolSetups = setups,
        )
        l.info("Loaded {} MCP tools from {} server(s)", setups.size, configs.size)
        return@withLock setups
    }

    suspend fun callTool(
        functionName: String,
        arguments: Map<String, Any>,
    ): McpToolCallResult {
        val snapshot = ensureDiscovered()
        val tool = snapshot.toolsByName[functionName]
            ?: throw IllegalArgumentException("No such MCP function: $functionName")
        val config = snapshot.serverConfigs[tool.serverName]
            ?: throw IllegalStateException("No config for MCP server ${tool.serverName}")

        val firstSession = getOrCreateSession(config)
        return runCatching {
            firstSession.callTool(tool.remoteToolName, arguments)
        }.getOrElse { firstError ->
            l.warn(
                "MCP call failed via {}.{}: {}. Restarting session and retrying once.",
                tool.serverName,
                tool.remoteToolName,
                firstError.message,
            )
            resetSession(tool.serverName, firstSession)
            val restartedSession = getOrCreateSession(config)
            restartedSession.callTool(tool.remoteToolName, arguments)
        }
    }

    private suspend fun ensureDiscovered(): McpDiscoveryState {
        val snapshot = discoveryState.value
        if (snapshot.loaded) return snapshot
        refreshTools()
        return discoveryState.value
    }

    private fun getOrCreateSession(config: McpServerConfig): McpStdioSession =
        sessions.computeIfAbsent(config.name) {
            McpStdioSession(config)
        }

    /** Remove and close a session if [expected] is the same as the current one */
    private fun resetSession(serverName: String, expected: McpStdioSession? = null) {
        sessions.compute(serverName) { _, session ->
            if (session === expected) expected?.close()
            null
        }
    }

    private fun closeAllSessions() {
        val values = sessions.values.toList()
        sessions.clear()
        values.forEach { session -> runCatching { session.close() } }
    }

    override fun close() {
        closeAllSessions()
        discoveryState.value = McpDiscoveryState()
    }

    private fun buildDescription(serverName: String, tool: McpRemoteTool): String {
        val base = tool.description.ifBlank { "MCP tool ${tool.name}" }
        return "[MCP:$serverName] $base"
    }

    private fun buildFunctionName(
        serverName: String,
        toolName: String,
        occupied: MutableSet<String>,
    ): String {
        val raw = "Mcp_${sanitizeIdentifier(serverName)}_${sanitizeIdentifier(toolName)}"
        var candidate = raw
        var i = 2
        while (!occupied.add(candidate)) {
            candidate = "${raw}_$i"
            i++
        }
        return candidate
    }

    private fun sanitizeIdentifier(input: String): String {
        val sanitized = input.replace(Regex("[^A-Za-z0-9_]"), "_").trim('_')
        return sanitized.ifBlank { "Tool" }
    }

    private fun schemaToParameters(schema: JsonNode?): GigaRequest.Parameters {
        if (schema == null || !schema.isObject) {
            return GigaRequest.Parameters(type = "object", properties = emptyMap(), required = emptyList())
        }

        val propertiesNode = schema.path("properties")
        val properties = LinkedHashMap<String, GigaRequest.Property>()
        if (propertiesNode.isObject) {
            val names = propertiesNode.fieldNames()
            while (names.hasNext()) {
                val key = names.next()
                val value = propertiesNode.path(key)
                properties[key] = GigaRequest.Property(
                    type = schemaType(value),
                    description = value.path("description").asText("").ifBlank { null },
                    enum = value.path("enum").takeIf { it.isArray }?.map { it.asText() },
                )
            }
        }

        val required = schema.path("required")
            .takeIf { it.isArray }
            ?.mapNotNull { node -> node.takeIf { it.isTextual }?.asText() }
            ?: emptyList()

        return GigaRequest.Parameters(
            type = "object",
            properties = properties,
            required = required,
        )
    }

    private fun schemaType(node: JsonNode): String {
        val raw = when {
            node.path("type").isTextual -> node.path("type").asText()
            node.path("type").isArray -> node.path("type").firstOrNull { it.isTextual && it.asText() != "null" }
                ?.asText()

            node.has("properties") -> "object"
            node.has("items") -> "array"
            else -> "string"
        } ?: "string"

        return when (raw.lowercase()) {
            "integer", "number" -> "number"
            "boolean" -> "boolean"
            "array" -> "array"
            "object" -> "object"
            else -> "string"
        }
    }
}

private data class McpDiscoveryState(
    val loaded: Boolean = false,
    val serverConfigs: Map<String, McpServerConfig> = emptyMap(),
    val toolsByName: Map<String, RegisteredMcpTool> = emptyMap(),
    val toolSetups: List<GigaToolSetup> = emptyList(),
)

private data class RegisteredMcpTool(
    val serverName: String,
    val remoteToolName: String,
    val functionName: String,
    val fn: GigaRequest.Function,
)

private class McpGigaToolSetup(
    private val manager: McpClientManager,
    private val tool: RegisteredMcpTool,
) : GigaToolSetup {
    override val fn: GigaRequest.Function = tool.fn

    override suspend fun invoke(functionCall: GigaResponse.FunctionCall): GigaRequest.Message {
        return try {
            val result = manager.callTool(tool.functionName, functionCall.arguments)
            val body = mapOf(
                "result" to result.text,
                "isError" to result.isError,
                "server" to tool.serverName,
                "tool" to tool.remoteToolName,
                "raw" to result.raw,
            )
            GigaRequest.Message(
                role = GigaMessageRole.function,
                content = gigaJsonMapper.writeValueAsString(body),
                name = functionCall.name,
            )
        } catch (e: Exception) {
            e.toGigaToolMessage(functionCall.name)
        }
    }
}

