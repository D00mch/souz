package ru.souz.backend.settings.service

import java.net.URI
import ru.souz.backend.http.invalidV1Request
import ru.souz.backend.settings.model.UserMcpServer
import ru.souz.mcp.McpNetworkPolicy

/** The HTTP settings surface replaces the map; legacy command fields are never executed. */
internal fun validatedMcpServers(servers: Map<String, UserMcpServer>): Map<String, UserMcpServer> {
    if (servers.size > 64) throw invalidV1Request("mcp must contain at most 64 servers.")
    val networkPolicy = McpNetworkPolicy.fromEnvironment()
    return servers.mapValues { (id, server) ->
        if (!Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}").matches(id)) {
            throw invalidV1Request("MCP server IDs must contain 1 to 64 letters, digits, underscores or hyphens.")
        }
        val description = server.description.replace(Regex("\\s+"), " ").trim()
        if (description.isBlank() || description.length > 240) {
            throw invalidV1Request("MCP server descriptions must contain 1 to 240 characters.")
        }
        val url = server.url?.trim().orEmpty()
        val uri = runCatching { URI(url) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        if (uri == null || (scheme != "https" && scheme != "http") || uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null || uri.rawFragment != null) {
            throw invalidV1Request("MCP server URLs must be HTTP(S) URLs without user information or fragments.")
        }
        try {
            networkPolicy.validateUrl(url)
        } catch (_: IllegalArgumentException) {
            throw invalidV1Request("MCP URL is invalid or its destination requires an operator allowlist.")
        } catch (_: java.net.UnknownHostException) {
            throw invalidV1Request("MCP destination is not public.")
        }
        server.copy(command = null, description = description, url = url)
    }
}
