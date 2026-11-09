package ru.souz.mcp

import io.ktor.client.HttpClient
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.tool.ToolCategory
import ru.souz.tool.skills.SkillToolSchemas

data class McpServerConfig(val id: String, val description: String, val url: String)
data class LoadedMcpTool(val skillId: String, val description: String)

/** Mutable discovery is private to one owner and execution; every published catalogue is immutable. */
class McpToolCatalog(
    private val owner: String,
    servers: List<McpServerConfig>,
    private val httpClient: HttpClient,
    private val timeoutMillis: Long = 60_000,
) {
    private val serversById = servers.associateBy { it.id }
    val descriptions: Map<String, String> = serversById.mapValues { it.value.description }
    private val mutex = Mutex()
    private val sessions = linkedMapOf<String, McpHttpSession>()
    private val loaded = linkedMapOf<String, List<LLMToolSetup>>()
    private var closed = false
    var catalog = AgentToolCatalog()
        private set

    init {
        require(serversById.size == servers.size) { "MCP server IDs must be unique." }
    }

    suspend fun load(serverId: String): List<LoadedMcpTool> = mutex.withLock {
        check(!closed) { "MCP execution is closed." }
        val server = requireNotNull(serversById[serverId]) { "MCP server is unavailable." }
        val tools = loaded[serverId] ?: run {
            val session = McpHttpSession.connect(httpClient, server.url, timeoutMillis)
            try {
                val definitions = session.listTools()
                val discovered = definitions.map { definition -> tool(server, definition, session) }
                val replacement = AgentToolCatalog(mapOf(ToolCategory.APPLICATIONS to
                    (loaded.values.flatten() + discovered)))
                sessions[serverId] = session
                loaded[serverId] = discovered
                catalog = replacement
                discovered
            } catch (failure: Throwable) {
                closeSession(session)
                throw failure
            }
        }
        tools.map { LoadedMcpTool(it.fn.name, it.fn.description.take(240)) }
    }

    val discoveryTool: LLMToolSetup = object : LLMToolSetup {
        override val fn = LLMRequest.Function(
            name = LOAD_TOOL_NAME,
            description = "Load tools from an available MCP server by its exact serverId. Returns Skill IDs for GetSkillByName and RunSkillCommand in this execution. Loading does not execute the remote tools.",
            parameters = LLMRequest.Parameters("object", mapOf(
                "serverId" to LLMRequest.Property("string", "Exact server ID from the MCP server inventory."),
            ), required = listOf("serverId"), additionalProperties = false),
        )

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
            failure(fn.name, "mcp_identity_required")

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta): LLMRequest.Message {
            if (meta.userId != owner) return failure(fn.name, "mcp_identity_mismatch")
            val id = (functionCall.arguments["serverId"] as? String)?.trim()
                ?: return failure(fn.name, "mcp_invalid_server")
            return guarded(fn.name) { mapOf("serverId" to id, "tools" to load(id)) }
        }
    }

    suspend fun close() = withContext(NonCancellable) {
        mutex.withLock {
            if (closed) return@withLock
            closed = true
            sessions.values.forEach { closeSession(it) }
            sessions.clear()
            loaded.clear()
            catalog = AgentToolCatalog()
        }
    }

    private fun tool(server: McpServerConfig, definition: McpToolDefinition, session: McpHttpSession): LLMToolSetup =
        object : LLMToolSetup, SkillToolSchemas {
            override val fn = LLMRequest.Function(
                name = toolId(server.id, definition.name),
                description = "${server.id}/${definition.name}: ${definition.description}".trimEnd(),
            )
            override val skillInputSchema = definition.inputSchema
            override val skillReturnSchema = definition.outputSchema

            override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
                failure(fn.name, "mcp_identity_required")

            override suspend fun invoke(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta): LLMRequest.Message {
                if (meta.userId != owner) return failure(fn.name, "mcp_identity_mismatch")
                return guarded(fn.name) { session.callTool(definition.name, functionCall.arguments) }
            }
        }

    companion object {
        const val LOAD_TOOL_NAME = "LoadMcpTools"

        fun toolId(serverId: String, toolName: String): String {
            val encoder = Base64.getUrlEncoder().withoutPadding()
            fun encode(value: String) = encoder.encodeToString(value.toByteArray(Charsets.UTF_8))
            return "mcp.${encode(serverId)}.${encode(toolName)}"
        }
    }
}

private suspend fun guarded(name: String, operation: suspend () -> Any): LLMRequest.Message = try {
    LLMRequest.Message(LLMMessageRole.function, restJsonMapper.writeValueAsString(operation()), name = name)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: McpTimeoutException) {
    failure(name, "mcp_timeout", "MCP request timed out; the remote outcome may be unknown. Do not blindly repeat actions.")
} catch (_: Exception) {
    failure(name, "mcp_request_failed", "MCP request failed; the remote outcome may be unknown.")
}

private fun failure(name: String, code: String, message: String = "MCP capability is unavailable.") =
    LLMRequest.Message(LLMMessageRole.function,
        restJsonMapper.writeValueAsString(mapOf("error" to mapOf("code" to code, "message" to message))), name = name)

private suspend fun closeSession(session: McpHttpSession) = withContext(NonCancellable) {
    withTimeoutOrNull(5_000) { runCatching { session.close() } }
    Unit
}
