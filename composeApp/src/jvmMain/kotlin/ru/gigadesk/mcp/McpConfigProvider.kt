package ru.gigadesk.mcp

import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.giga.objectMapper
import java.io.File

class McpConfigProvider(
    private val settingsProvider: SettingsProvider,
) {
    private val l = LoggerFactory.getLogger(McpConfigProvider::class.java)

    fun loadServers(): Map<String, McpServerConfig> {
        val source = loadRawConfig() ?: return emptyMap()
        val root = runCatching { objectMapper.readTree(source) }.getOrElse { e ->
            l.warn("Failed to parse MCP config JSON: {}", e.message)
            return emptyMap()
        }
        val serversNode = when {
            root.has("mcpServers") -> root.path("mcpServers")
            else -> root
        }
        if (!serversNode.isObject) {
            l.warn("MCP config must be a JSON object (or have mcpServers object)")
            return emptyMap()
        }

        val result = LinkedHashMap<String, McpServerConfig>()
        val names = serversNode.fieldNames()
        while (names.hasNext()) {
            val name = names.next()
            parseServer(name, serversNode.path(name))?.let { result[name] = it }
        }
        return result
    }

    private fun loadRawConfig(): String? {
        val inline = settingsProvider.mcpServersJson?.trim().orEmpty()
        if (inline.isNotEmpty()) return inline

        val filePath = settingsProvider.mcpServersFile?.trim().orEmpty()
        if (filePath.isEmpty()) return null

        val expandedPath = if (filePath.startsWith("~/")) {
            filePath.replaceFirst("~", System.getProperty("user.home"))
        } else {
            filePath
        }
        val file = File(expandedPath)
        if (!file.exists()) {
            l.warn("MCP config file does not exist: {}", file.absolutePath)
            return null
        }
        return runCatching { file.readText() }.getOrElse { e ->
            l.warn("Failed to read MCP config file {}: {}", file.absolutePath, e.message)
            null
        }
    }

    private fun parseServer(name: String, node: JsonNode): McpServerConfig? {
        if (!node.isObject) return null
        val enabled = node.path("enabled").let { !it.isBoolean || it.asBoolean() }
        if (!enabled) return null

        val command = node.path("command").asText("").trim()
        if (command.isBlank()) {
            l.warn("Skipping MCP server {}: fissing command", name)
            return null
        }

        val args = parseArgs(node.path("args"))
        val env = parseEnv(node.path("env"))
        val cwd = node.path("cwd").asText("").trim().ifBlank { null }
        val timeoutMillis = node.path("timeoutMillis").asLong(30_000L).coerceAtLeast(1_000L)

        return McpServerConfig(
            name = name,
            command = command,
            args = args,
            env = env,
            cwd = cwd,
            timeoutMillis = timeoutMillis,
        )
    }

    private fun parseArgs(node: JsonNode): List<String> {
        if (node.isArray) {
            return node.mapNotNull { item ->
                when {
                    item.isTextual -> item.asText()
                    item.isNumber || item.isBoolean -> item.asText()
                    else -> null
                }
            }
        }
        if (node.isTextual) return listOf(node.asText())
        return emptyList()
    }

    private fun parseEnv(node: JsonNode): Map<String, String> {
        if (!node.isObject) return emptyMap()
        val env = LinkedHashMap<String, String>()
        val names = node.fieldNames()
        while (names.hasNext()) {
            val key = names.next()
            val value = node.path(key)
            env[key] = if (value.isTextual) value.asText() else value.toString()
        }
        return env
    }
}
