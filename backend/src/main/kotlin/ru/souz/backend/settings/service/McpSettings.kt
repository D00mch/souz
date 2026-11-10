package ru.souz.backend.settings.service

import java.net.UnknownHostException
import ru.souz.backend.http.invalidV1Request
import ru.souz.backend.settings.model.UserMcpServer
import ru.souz.mcp.McpNetworkPolicy
import ru.souz.mcp.McpServerConfig

/** The HTTP settings surface replaces the map; legacy command fields are never executed. */
internal fun validatedMcpServers(servers: Map<String, UserMcpServer>): Map<String, UserMcpServer> {
    if (servers.size > McpServerConfig.MAX_SERVERS) {
        throw invalidV1Request("mcp must contain at most ${McpServerConfig.MAX_SERVERS} servers.")
    }
    val networkPolicy = McpNetworkPolicy.fromEnvironment()
    return servers.mapValues { (id, server) ->
        if (!McpServerConfig.ID_PATTERN.matches(id)) {
            throw invalidV1Request("MCP server IDs must contain 1 to 64 letters, digits, underscores or hyphens.")
        }
        val description = server.description.replace(WHITESPACE, " ").trim()
        if (description.isBlank() || description.length > McpServerConfig.MAX_DESCRIPTION_CHARS) {
            throw invalidV1Request("MCP server descriptions must contain 1 to ${McpServerConfig.MAX_DESCRIPTION_CHARS} characters.")
        }
        val url = server.url?.trim().orEmpty()
        try {
            networkPolicy.validateUrl(url)
        } catch (_: IllegalArgumentException) {
            throw invalidV1Request("MCP URL is invalid or its destination requires an operator allowlist.")
        } catch (_: UnknownHostException) {
            throw invalidV1Request("MCP destination is not public.")
        }
        server.copy(command = null, description = description, url = url)
    }
}

private val WHITESPACE = Regex("\\s+")
