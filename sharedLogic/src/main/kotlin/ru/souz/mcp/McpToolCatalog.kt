package ru.souz.mcp

import io.ktor.client.HttpClient
import java.util.Base64
import java.util.Collections
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import ru.souz.agent.skills.MCP_SKILL_PREFIX
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolServerSource
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.tool.ToolCategory
import ru.souz.tool.skills.SkillDiscoveryPage
import ru.souz.tool.skills.SkillToolSchemas

data class McpServerConfig(val id: String, val description: String, val url: String) {
    companion object {
        const val MAX_SERVERS = 64
        const val MAX_DESCRIPTION_CHARS = 240
        val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
    }
}

data class LoadedMcpTool(val skillId: String, val description: String)

/** Owns sessions for one execution. Only complete listings become visible to readers. */
class McpToolCatalog(
    private val owner: String,
    servers: List<McpServerConfig>,
    private val httpClient: HttpClient,
    private val timeoutMillis: Long = McpLimits.REQUEST_TIMEOUT_MS,
) : AgentToolServerSource {
    private val configured = servers.associateBy { it.id }
    override val servers = configured.mapValues { it.value.description }
    override val discoveryToolName = LOAD_TOOL_NAME
    override val loadedTools get() = published.hints
    val catalog get() = published.catalog
    @Volatile private var published = Snapshot(AgentToolCatalog(), emptyMap())
    private val mutex = Mutex()
    private val closing = Mutex()
    private val loaded = linkedMapOf<String, LoadedServer>()
    private var closed = false

    private data class Snapshot(val catalog: AgentToolCatalog, val hints: Map<String, String>)

    private data class LoadedServer(val session: McpHttpSession, val tools: List<RemoteTool>) {
        val summaries: List<LoadedMcpTool> = Collections.unmodifiableList(tools.map {
            LoadedMcpTool(it.fn.name, it.fn.description.take(McpLimits.TOOL_HINT_CHARS))
        })
        val bytes = tools.sumOf { it.definition.retainedBytes }
    }

    init {
        require(
            configured.size == servers.size && servers.size <= McpServerConfig.MAX_SERVERS &&
                servers.all { McpServerConfig.ID_PATTERN.matches(it.id) }
        ) { "Invalid MCP server inventory." }
    }

    override suspend fun preload(serverIds: Set<String>) {
        withTimeoutOrNull(McpLimits.PRELOAD_TIMEOUT_MS.milliseconds) {
            for (id in serverIds) {
                try {
                    load(id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    logger.warn("MCP preload failed serverId={} failure={}", id, error.javaClass.simpleName)
                }
            }
        }
    }

    suspend fun load(serverId: String): List<LoadedMcpTool> = mutex.withLock {
        check(!closed) { "MCP execution is closed." }
        loaded[serverId]?.let { return@withLock it.summaries }
        val server = requireNotNull(configured[serverId]) { "MCP server is unavailable." }
        val session = McpHttpSession.connect(httpClient, server.url, timeoutMillis)
        try {
            val definitions = session.listTools(
                maxTools = McpLimits.TOOLS - loaded.values.sumOf { it.tools.size },
                maxBytes = McpLimits.CATALOG_BYTES - loaded.values.sumOf { it.bytes },
            )
            val record = LoadedServer(session, definitions.map { RemoteTool(serverId, it, session) })
            val records = loaded.values + record
            val snapshot = Snapshot(
                AgentToolCatalog(mapOf(ToolCategory.APPLICATIONS to records.flatMap { it.tools })),
                Collections.unmodifiableMap(records.flatMap { it.summaries }.associate { it.skillId to it.description }),
            )
            loaded[serverId] = record
            published = snapshot
            record.summaries
        } catch (failure: Throwable) {
            session.close()
            throw failure
        }
    }

    val discoveryTool: LLMToolSetup = object : LLMToolSetup {
        override val fn = LLMRequest.Function(
            name = LOAD_TOOL_NAME,
            description = "Load an available MCP server by exact serverId. Returns paged Skill IDs for GetSkillByName and RunSkillCommand in this execution. Loading does not execute tools; continue with nextOffset.",
            parameters = LLMRequest.Parameters(
                type = "object",
                properties = SkillDiscoveryPage.parameters + ("serverId" to LLMRequest.Property(
                    "string", "Exact server ID from inventory.",
                )),
                required = listOf("serverId"),
                additionalProperties = false,
            ),
        )

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall) = failure(fn.name, "mcp_identity_required")

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta): LLMRequest.Message {
            if (meta.userId != owner) return failure(fn.name, "mcp_identity_mismatch")
            val id = (functionCall.arguments["serverId"] as? String)?.trim()
                ?: return failure(fn.name, "mcp_invalid_server")
            if (id !in configured) return failure(fn.name, "mcp_server_unavailable")
            val page = try {
                SkillDiscoveryPage(functionCall.arguments)
            } catch (_: IllegalArgumentException) {
                return failure(fn.name, "mcp_invalid_arguments")
            }
            return guarded(fn.name, id) {
                val tools = load(id)
                val selected = page.select(tools, oversized = { it.copy(description = "") }, render = { it })
                restJsonMapper.writeValueAsString(mapOf(
                    "serverId" to id,
                    "tools" to selected,
                    "totalTools" to tools.size,
                    "nextOffset" to page.nextOffset,
                ))
            }
        }
    }

    suspend fun close() = withContext(NonCancellable) {
        closing.withLock {
            val sessions = mutex.withLock {
                closed = true
                val sessions = loaded.values.map { it.session }
                loaded.clear()
                published = Snapshot(AgentToolCatalog(), emptyMap())
                sessions
            }
            coroutineScope {
                sessions.forEach { session -> launch { session.close() } }
            }
        }
    }

    private inner class RemoteTool(
        private val serverId: String,
        val definition: McpToolDefinition,
        private val session: McpHttpSession,
    ) : LLMToolSetup, SkillToolSchemas {
        override val fn = LLMRequest.Function(
            name = toolId(serverId, definition.name),
            description = "$serverId/${definition.name}: ${definition.description}".trimEnd(),
        )
        override val skillInputSchema get() = definition.inputSchema
        override val skillReturnSchema get() = definition.outputSchema
        override suspend fun invoke(functionCall: LLMResponse.FunctionCall) = failure(fn.name, "mcp_identity_required")
        override suspend fun invoke(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta): LLMRequest.Message =
            if (meta.userId != owner) failure(fn.name, "mcp_identity_mismatch")
            else guarded(fn.name, serverId) { session.callTool(definition.name, functionCall.arguments) }
    }

    private suspend fun guarded(name: String, serverId: String, operation: suspend () -> String): LLMRequest.Message = try {
        LLMRequest.Message(LLMMessageRole.function, operation(), name = name)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        logger.warn("MCP request failed serverId={} toolName={} failure={}", serverId, name, error.javaClass.simpleName)
        failure(
            name,
            if (error is McpTimeoutException) "mcp_timeout" else "mcp_request_failed",
            "MCP request failed; the remote outcome may be unknown. Do not blindly repeat actions.",
        )
    }

    companion object {
        const val LOAD_TOOL_NAME = "LoadMcpTools"
        private val logger = LoggerFactory.getLogger(McpToolCatalog::class.java)

        fun toolId(serverId: String, toolName: String): String {
            fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
            return "$MCP_SKILL_PREFIX${encode(serverId)}.${encode(toolName)}"
        }
    }
}

private fun failure(name: String, code: String, message: String = "MCP capability is unavailable.") = LLMRequest.Message(
    LLMMessageRole.function,
    restJsonMapper.writeValueAsString(mapOf("error" to mapOf("code" to code, "message" to message))),
    name = name,
)
