package ru.souz.mcp

import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import ru.souz.llms.giga.gigaJsonMapper
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.EOFException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

private const val JSON_RPC_VERSION = "2.0"
private const val MCP_PROTOCOL_VERSION = "2025-06-18"

class McpStdioSession(
    private val config: McpServerConfig,
) : McpSession {
    private val l = LoggerFactory.getLogger(McpStdioSession::class.java)
    private val process: Process
    private val stdin: BufferedWriter
    private val stdout: BufferedReader
    private val requestSeq = AtomicLong(1L)
    private val ioMutex = Mutex()
    private val initMutex = Mutex()
    private val timeoutMillis = config.timeoutMillis
    private val toolsApi = McpToolProtocolApi(
        initializeIfNeeded = { initializeIfNeeded() },
        request = { method, params -> request(method, params) },
    )

    private var initialized = false

    init {
        val baseCommand = config.command?.trim().orEmpty()
        require(baseCommand.isNotBlank()) { "MCP stdio server ${config.name} has empty command" }
        val command = ArrayList<String>(1 + config.args.size).apply {
            add(baseCommand)
            addAll(config.args)
        }
        process = ProcessBuilder(command).apply {
            if (!config.cwd.isNullOrBlank()) {
                directory(java.io.File(config.cwd))
            }
            environment().putAll(config.env)
        }.start()

        stdin = process.outputStream.bufferedWriter(Charsets.UTF_8)
        stdout = process.inputStream.bufferedReader(Charsets.UTF_8)

        thread(
            name = "mcp-${config.name}-stderr",
            start = true,
            isDaemon = true,
        ) {
            process.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) l.debug("[mcp:{}] {}", config.name, line)
                }
            }
        }
    }

    suspend fun initializeIfNeeded() {
        initMutex.withLock {
            if (initialized) return
            request(
                method = "initialize",
                params = mapOf(
                    "protocolVersion" to MCP_PROTOCOL_VERSION,
                    "capabilities" to emptyMap<String, Any>(),
                    "clientInfo" to mapOf(
                        "name" to "souz",
                        "version" to "1.0.0",
                    ),
                ),
            )
            notify("notifications/initialized", emptyMap<String, Any>())
            initialized = true
        }
    }

    override suspend fun listTools(): List<McpRemoteTool> {
        return toolsApi.listTools()
    }

    override suspend fun callTool(
        toolName: String,
        arguments: Map<String, Any>,
    ): McpToolCallResult {
        return toolsApi.callTool(
            toolName = toolName,
            arguments = arguments,
        )
    }

    private suspend fun request(
        method: String,
        params: Any,
    ): JsonNode = ioMutex.withLock {
        ensureProcessAlive()
        val id = requestSeq.getAndIncrement()
        val message = linkedMapOf<String, Any>(
            "jsonrpc" to JSON_RPC_VERSION,
            "id" to id,
            "method" to method,
            "params" to params,
        )
        writeJson(message)
        readResponse(id)
    }

    private suspend fun notify(
        method: String,
        params: Any,
    ) = ioMutex.withLock {
        ensureProcessAlive()
        val message = linkedMapOf<String, Any>(
            "jsonrpc" to JSON_RPC_VERSION,
            "method" to method,
            "params" to params,
        )
        writeJson(message)
    }

    private suspend fun writeJson(payload: Any) {
        val line = gigaJsonMapper.writeValueAsString(payload)
        withContext(Dispatchers.IO) {
            stdin.write(line)
            stdin.newLine()
            stdin.flush()
        }
    }

    private suspend fun readResponse(requestId: Long): JsonNode = withTimeout(timeoutMillis) {
        lateinit var result: JsonNode
        while (true) {
            val line = withContext(Dispatchers.IO) {
                stdout.readLine()
            } ?: throw EOFException(
                "MCP server ${config.name} closed stdout while waiting for response to $requestId"
            )
            val node = runCatching { gigaJsonMapper.readTree(line) }
                .onFailure { l.warn("Unexpected error on reading response line, id: $requestId", it) }
                .getOrNull() ?: continue
            val idNode = node.path("id")
            if (idNode.isMissingNode || idNode.isNull) continue
            val matched = when {
                idNode.isNumber -> idNode.asLong() == requestId
                idNode.isTextual -> idNode.asText() == requestId.toString()
                else -> false
            }
            if (!matched) continue

            val errorNode = node.path("error")
            if (!errorNode.isMissingNode && !errorNode.isNull) {
                val message = errorNode.path("message").asText(errorNode.toString())
                throw IllegalStateException("MCP request failed ($requestId): $message")
            }
            result = node.path("result")
            break
        }
        result
    }

    private fun ensureProcessAlive() {
        if (!process.isAlive) {
            throw IllegalStateException("MCP server ${config.name} is not running")
        }
    }

    override fun close() {
        runCatching { stdin.close() }
        runCatching { stdout.close() }
        if (process.isAlive) {
            process.destroy()
            if (!process.waitFor(300, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
            }
        }
    }
}
