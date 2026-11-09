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
import ru.souz.agent.spi.AgentToolServerSource
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.tool.ToolCategory
import ru.souz.tool.skills.SkillToolSchemas
import org.slf4j.LoggerFactory

data class McpServerConfig(val id: String, val description: String, val url: String)
data class LoadedMcpTool(val skillId: String, val description: String)

/** Mutable discovery is private to one owner and execution; every published catalogue is immutable. */
class McpToolCatalog(
    private val owner: String,
    servers: List<McpServerConfig>,
    private val httpClient: HttpClient,
    private val timeoutMillis: Long = 60_000,
) : AgentToolServerSource {
    private val serversById = servers.associateBy { it.id }
    override val servers: Map<String, String> = serversById.mapValues { it.value.description }
    override val discoveryToolName: String = LOAD_TOOL_NAME
    override val loadedTools: Map<String, String>
        get() = catalog.toolsByCategory.values.flatMap { it.values }.associate { it.fn.name to it.fn.description }
    private val mutex = Mutex()
    private val sessions = linkedMapOf<String, McpHttpSession>()
    private val loaded = linkedMapOf<String, List<LLMToolSetup>>()
    private var closed = false
    var catalog = AgentToolCatalog()
        private set

    init {
        require(serversById.size == servers.size) { "MCP server IDs must be unique." }
    }

    override suspend fun preload(serverIds: Set<String>) {
        for (id in serverIds) {
            try {
                load(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LoggerFactory.getLogger(McpToolCatalog::class.java).warn("MCP preload failed serverId={} failure={}",
                    id, failure.javaClass.simpleName)
            }
        }
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
                "offset" to LLMRequest.Property("integer", "Start position in the loaded tool list; default 0."),
                "limit" to LLMRequest.Property("integer", "Number of tool summaries to return; default 20, maximum 50."),
            ), required = listOf("serverId"), additionalProperties = false),
        )

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
            failure(fn.name, "mcp_identity_required")

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta): LLMRequest.Message {
            if (meta.userId != owner) return failure(fn.name, "mcp_identity_mismatch")
            val id = (functionCall.arguments["serverId"] as? String)?.trim()
                ?: return failure(fn.name, "mcp_invalid_server")
            if (id !in serversById) return failure(fn.name, "mcp_server_unavailable")
            val offset = functionCall.arguments["offset"] ?: 0
            val limit = functionCall.arguments["limit"] ?: 20
            if (offset !is Number || limit !is Number || offset.toLong() !in 0..10_000 ||
                limit.toLong() !in 1..50 || offset.toDouble() != offset.toLong().toDouble() ||
                limit.toDouble() != limit.toLong().toDouble()) return failure(fn.name, "mcp_invalid_arguments")
            return guarded(fn.name) {
                val tools = load(id)
                val start = offset.toInt()
                val page = tools.drop(start).take(limit.toInt())
                mapOf("serverId" to id, "tools" to page, "totalTools" to tools.size,
                    "nextOffset" to (start + page.size).takeIf { it < tools.size })
            }
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
