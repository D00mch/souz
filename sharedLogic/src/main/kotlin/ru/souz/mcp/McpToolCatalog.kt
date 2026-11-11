package ru.souz.mcp

import io.ktor.client.HttpClient
import java.util.Base64
import java.util.Collections
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
import ru.souz.tool.skills.DISCOVERY_ENVELOPE_BYTES
import ru.souz.tool.skills.DISCOVERY_PAGE_BYTES
import ru.souz.tool.skills.SkillToolSchemas
import ru.souz.tool.skills.discoveryPage
import ru.souz.tool.skills.discoveryPageParameters

data class McpServerConfig(val id: String, val description: String, val url: String) {
    companion object {
        const val MAX_SERVERS = 64
        const val MAX_DESCRIPTION_CHARS = 240
        val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
    }
}

data class LoadedMcpTool(val skillId: String, val description: String)

/** Mutable discovery is private to one owner and execution; every published catalogue is immutable. */
class McpToolCatalog(
    private val owner: String,
    servers: List<McpServerConfig>,
    private val httpClient: HttpClient,
    private val timeoutMillis: Long = McpLimits.REQUEST_TIMEOUT_MS,
) : AgentToolServerSource {
    private val serversById = servers.associateBy { it.id }
    override val servers: Map<String, String> = serversById.mapValues { it.value.description }
    override val discoveryToolName: String = LOAD_TOOL_NAME
    override val loadedTools: Map<String, String>
        get() = published.hints
    private val mutex = Mutex()
    private val closing = Mutex()
    private val loaded = linkedMapOf<String, LoadedServer>()
    private var closed = false
    @Volatile
    private var published = Snapshot(AgentToolCatalog(), emptyMap())
    val catalog: AgentToolCatalog get() = published.catalog

    private data class Snapshot(val catalog: AgentToolCatalog, val hints: Map<String, String>)

    private data class LoadedServer(
        val session: McpHttpSession,
        val tools: List<LLMToolSetup>,
        val summaries: List<LoadedMcpTool>,
        val retainedBytes: Long,
    )

    init {
        require(serversById.size == servers.size) { "MCP server IDs must be unique." }
        require(servers.size <= McpServerConfig.MAX_SERVERS && servers.all { McpServerConfig.ID_PATTERN.matches(it.id) }) {
            "Invalid MCP server inventory."
        }
    }

    override suspend fun preload(serverIds: Set<String>) {
        withTimeoutOrNull(McpLimits.PRELOAD_TIMEOUT_MS) {
            for (id in serverIds) {
                try {
                    load(id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    logger.warn("MCP preload failed serverId={} failure={}", id, failure.javaClass.simpleName)
                }
            }
        }
    }

    suspend fun load(serverId: String): List<LoadedMcpTool> = mutex.withLock {
        check(!closed) { "MCP execution is closed." }
        val server = requireNotNull(serversById[serverId]) { "MCP server is unavailable." }
        if (serverId !in loaded) {
            val session = McpHttpSession.connect(httpClient, server.url, timeoutMillis)
            var installed = false
            try {
                val definitions = session.listTools()
                val bytes = definitions.sumOf { it.retainedBytes }
                check(loaded.values.sumOf { it.retainedBytes } + bytes <= McpLimits.CATALOG_BYTES) {
                    "MCP execution catalogue exceeds the byte limit."
                }
                check(loaded.values.sumOf { it.tools.size } + definitions.size <= McpLimits.TOOLS) {
                    "MCP execution catalogue exceeds the tool limit."
                }
                val discovered = definitions.map { definition -> tool(server, definition, session) }
                val record = LoadedServer(
                    session = session,
                    tools = discovered,
                    summaries = Collections.unmodifiableList(discovered.map {
                        LoadedMcpTool(it.fn.name, it.fn.description.take(McpLimits.TOOL_HINT_CHARS))
                    }),
                    retainedBytes = bytes,
                )
                val records = loaded.values + record
                val replacement = Snapshot(
                    catalog = AgentToolCatalog(mapOf(ToolCategory.APPLICATIONS to records.flatMap { it.tools })),
                    hints = Collections.unmodifiableMap(records.flatMap { it.summaries }.associate { it.skillId to it.description }),
                )
                loaded[serverId] = record
                published = replacement
                installed = true
            } finally {
                if (!installed) session.close()
            }
        }
        loaded.getValue(serverId).summaries
    }

    val discoveryTool: LLMToolSetup = object : LLMToolSetup {
        override val fn = LLMRequest.Function(
            name = LOAD_TOOL_NAME,
            description = "Load tools from an available MCP server by its exact serverId. Returns Skill IDs for GetSkillByName and RunSkillCommand in this execution. Loading does not execute the remote tools.",
            parameters = LLMRequest.Parameters(
                type = "object",
                properties = mapOf(
                    "serverId" to LLMRequest.Property("string", "Exact server ID from the MCP server inventory."),
                ) + discoveryPageParameters(),
                required = listOf("serverId"),
                additionalProperties = false,
            ),
        )

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
            failure(fn.name, "mcp_identity_required")

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta): LLMRequest.Message {
            if (meta.userId != owner) return failure(fn.name, "mcp_identity_mismatch")
            val id = (functionCall.arguments["serverId"] as? String)?.trim()
                ?: return failure(fn.name, "mcp_invalid_server")
            if (id !in serversById) return failure(fn.name, "mcp_server_unavailable")
            val input = try {
                discoveryPage(functionCall.arguments)
            } catch (_: IllegalArgumentException) {
                return failure(fn.name, "mcp_invalid_arguments")
            }
            return guarded(fn.name, id) {
                val tools = load(id)
                val start = input.offset
                val page = mutableListOf<LoadedMcpTool>()
                var bytes = DISCOVERY_ENVELOPE_BYTES
                for (tool in tools.asSequence().drop(start).take(input.limit)) {
                    val size = restJsonMapper.writeValueAsBytes(tool).size + 1
                    if (bytes + size > DISCOVERY_PAGE_BYTES) break
                    page += tool
                    bytes += size
                }
                restJsonMapper.writeValueAsString(mapOf(
                    "serverId" to id,
                    "tools" to page,
                    "totalTools" to tools.size,
                    "nextOffset" to (start + page.size).takeIf { it < tools.size },
                ))
            }
        }
    }

    suspend fun close() = withContext(NonCancellable) {
        closing.withLock {
            val owned = mutex.withLock {
                if (closed) return@withContext
                closed = true
                val owned = loaded.values.map { it.session }
                loaded.clear()
                published = Snapshot(AgentToolCatalog(), emptyMap())
                owned
            }
            // The inventory admits at most 64 sessions; every close has independent short step bounds.
            coroutineScope {
                owned.forEach { session -> launch { session.close() } }
            }
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
                return guarded(fn.name, server.id) { session.callTool(definition.name, functionCall.arguments) }
            }
        }

    private suspend fun guarded(name: String, serverId: String, operation: suspend () -> String): LLMRequest.Message = try {
        LLMRequest.Message(LLMMessageRole.function, operation(), name = name)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: McpTimeoutException) {
        logger.warn("MCP request timed out serverId={} toolName={}", serverId, name)
        failure(name, "mcp_timeout", "MCP request timed out; the remote outcome may be unknown. Do not blindly repeat actions.")
    } catch (failure: Exception) {
        logger.warn("MCP request failed serverId={} toolName={} failure={}", serverId, name, failure.javaClass.simpleName)
        failure(name, "mcp_request_failed", "MCP request failed; the remote outcome may be unknown.")
    }

    companion object {
        const val LOAD_TOOL_NAME = "LoadMcpTools"
        private val logger = LoggerFactory.getLogger(McpToolCatalog::class.java)

        fun toolId(serverId: String, toolName: String): String {
            val encoder = Base64.getUrlEncoder().withoutPadding()
            fun encode(value: String) = encoder.encodeToString(value.toByteArray(Charsets.UTF_8))
            return "$MCP_SKILL_PREFIX${encode(serverId)}.${encode(toolName)}"
        }
    }
}

private fun failure(name: String, code: String, message: String = "MCP capability is unavailable.") =
    LLMRequest.Message(
        role = LLMMessageRole.function,
        content = restJsonMapper.writeValueAsString(mapOf("error" to mapOf("code" to code, "message" to message))),
        name = name,
    )
