package ru.gigadesk.mcp

import com.fasterxml.jackson.databind.JsonNode

enum class McpTransport {
    STDIO,
    HTTP,
}

data class McpServerConfig(
    val name: String,
    val transport: McpTransport = McpTransport.STDIO,
    val command: String? = null,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val cwd: String? = null,
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val oauth: McpOAuthConfig = McpOAuthConfig(),
    val timeoutMillis: Long = 30_000L,
)

data class McpOAuthConfig(
    val enabled: Boolean = true,
    val clientName: String = "gigadesk",
    val clientId: String? = null,
    val clientSecret: String? = null,
    val scopes: List<String> = emptyList(),
    val audience: String? = null,
    val redirectHost: String = "127.0.0.1",
    val redirectPath: String = "/mcp/oauth/callback",
    val authTimeoutMillis: Long = 180_000L,
)

data class McpRemoteTool(
    val name: String,
    val description: String,
    val inputSchema: JsonNode?,
)

data class McpToolCallResult(
    val text: String,
    val isError: Boolean,
    val raw: JsonNode,
)

data class McpOAuthState(
    val clientId: String? = null,
    val clientSecret: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAtEpochMillis: Long? = null,
)

data class McpAuthorizationServerMetadata(
    val issuer: String? = null,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val registrationEndpoint: String? = null,
    val tokenEndpointAuthMethodsSupported: List<String> = emptyList(),
)
