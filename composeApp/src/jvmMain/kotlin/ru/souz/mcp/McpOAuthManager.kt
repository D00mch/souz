package ru.souz.mcp

import com.sun.net.httpserver.HttpServer
import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import ru.souz.db.ConfigStore
import ru.souz.giga.gigaJsonMapper
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors

private const val OAUTH_STORE_PREFIX = "MCP_OAUTH_STATE_"

class McpOAuthManager(
    private val config: McpServerConfig,
    private val httpClient: HttpClient,
) {
    private val l = LoggerFactory.getLogger(McpOAuthManager::class.java)
    private val lock = Mutex()
    private val storageKey = "$OAUTH_STORE_PREFIX${sanitizeKey(config.name)}"
    private var state: McpOAuthState = loadState()
    private var cachedMetadata: McpAuthorizationServerMetadata? = null

    init {
        val loaded = state
        val merged = mergeConfiguredClient(loaded)
        state = merged
        if (merged != loaded) {
            persistState()
        }
    }

    suspend fun shouldAttemptAuthorization(): Boolean {
        if (!config.oauth.enabled) return false
        return lock.withLock {
            state.accessToken?.isNotBlank() == true ||
                state.refreshToken?.isNotBlank() == true ||
                state.clientId?.isNotBlank() == true ||
                config.oauth.clientId?.isNotBlank() == true
        }
    }

    suspend fun discoverOAuthConfiguration(): Boolean {
        if (!config.oauth.enabled) return false
        return lock.withLock {
            if (cachedMetadata != null) return@withLock true
            val metadata = runCatching { discoverMetadataLocked(null) }
                .onFailure { e ->
                    l.debug("MCP OAuth discovery skipped for {}: {}", config.name, e.message)
                }
                .getOrNull()
                ?: return@withLock false
            cachedMetadata = metadata
            true
        }
    }

    suspend fun accessTokenOrLogin(
        forceRefresh: Boolean = false,
        wwwAuthenticate: String? = null,
    ): String? {
        if (!config.oauth.enabled) return null
        return lock.withLock {
            if (!forceRefresh && hasValidAccessToken(state)) {
                return@withLock state.accessToken
            }

            if (!forceRefresh) {
                tryRefreshTokenLocked(wwwAuthenticate)?.let { token ->
                    return@withLock token
                }
            }

            authorizeLocked(wwwAuthenticate)
        }
    }

    suspend fun invalidateAccessToken() {
        if (!config.oauth.enabled) return
        lock.withLock {
            state = state.copy(accessToken = null, expiresAtEpochMillis = null)
            persistState()
        }
    }

    private suspend fun tryRefreshTokenLocked(wwwAuthenticate: String?): String? {
        val refreshToken = state.refreshToken?.takeIf { it.isNotBlank() } ?: return null
        val metadata = discoverMetadataLocked(wwwAuthenticate)
        val client = ensureClientRegistrationLocked(metadata, redirectUri = null)
        return runCatching {
            val tokenResponse = tokenRequest(
                tokenEndpoint = metadata.tokenEndpoint,
                params = buildMap {
                    put("grant_type", "refresh_token")
                    put("refresh_token", refreshToken)
                    put("client_id", client.clientId)
                    client.clientSecret?.takeIf { it.isNotBlank() }?.let { put("client_secret", it) }
                    put("resource", normalizedResourceUrl())
                    config.oauth.audience?.takeIf { it.isNotBlank() }?.let { put("audience", it) }
                },
            )
            val newState = updateStateFromTokenResponse(tokenResponse, previousRefreshToken = refreshToken)
            state = newState
            persistState()
            newState.accessToken
        }.getOrElse { e ->
            l.info("MCP OAuth refresh failed for {}: {}", config.name, e.message)
            null
        }
    }

    private suspend fun authorizeLocked(wwwAuthenticate: String?): String {
        val metadata = discoverMetadataLocked(wwwAuthenticate)
        val loopback = startLoopbackServer()
        return try {
            val client = ensureClientRegistrationLocked(metadata, redirectUri = loopback.redirectUri)
            val stateToken = randomUrlSafe(24)
            val codeVerifier = randomUrlSafe(64)
            val codeChallenge = codeChallengeS256(codeVerifier)
            val authorizeUrl = buildAuthorizeUrl(
                metadata = metadata,
                clientId = client.clientId,
                redirectUri = loopback.redirectUri,
                state = stateToken,
                codeChallenge = codeChallenge,
            )

            openBrowser(authorizeUrl)

            val callback = withTimeout(config.oauth.authTimeoutMillis) { loopback.callback.await() }
            if (callback.state != stateToken) {
                throw IllegalStateException("MCP OAuth failed: invalid callback state")
            }
            if (!callback.error.isNullOrBlank()) {
                throw IllegalStateException(
                    "MCP OAuth failed: ${callback.error}" +
                        callback.errorDescription?.let { " ($it)" }.orEmpty(),
                )
            }
            val code = callback.code?.trim().orEmpty()
            if (code.isBlank()) {
                throw IllegalStateException("MCP OAuth failed: no authorization code in callback")
            }

            val tokenResponse = tokenRequest(
                tokenEndpoint = metadata.tokenEndpoint,
                params = buildMap {
                    put("grant_type", "authorization_code")
                    put("code", code)
                    put("redirect_uri", loopback.redirectUri)
                    put("code_verifier", codeVerifier)
                    put("client_id", client.clientId)
                    client.clientSecret?.takeIf { it.isNotBlank() }?.let { put("client_secret", it) }
                    put("resource", normalizedResourceUrl())
                    config.oauth.audience?.takeIf { it.isNotBlank() }?.let { put("audience", it) }
                },
            )

            val newState = updateStateFromTokenResponse(
                tokenResponse = tokenResponse,
                previousRefreshToken = state.refreshToken,
            ).copy(
                clientId = client.clientId,
                clientSecret = client.clientSecret,
            )
            state = newState
            persistState()

            val accessToken = newState.accessToken?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("MCP OAuth failed: empty access token")
            accessToken
        } finally {
            loopback.stop()
        }
    }

    private suspend fun discoverMetadataLocked(wwwAuthenticate: String?): McpAuthorizationServerMetadata {
        cachedMetadata?.let { return it }

        val protectedMetadata = discoverProtectedResourceMetadata(wwwAuthenticate)
        val authServers = ArrayList<String>()
        protectedMetadata.path("authorization_servers")
            .takeIf { it.isArray }
            ?.forEach { node ->
                node.asText("").trim().takeIf { it.isNotBlank() }?.let { authServers += it }
            }
        protectedMetadata.path("authorization_server")
            .asText("")
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let { authServers += it }

        val authServer = authServers.firstOrNull()
            ?: throw IllegalStateException("MCP OAuth discovery failed: no authorization server in metadata")

        val metadata = discoverAuthorizationServerMetadata(authServer)
        cachedMetadata = metadata
        return metadata
    }

    private suspend fun discoverProtectedResourceMetadata(wwwAuthenticate: String?): JsonNode {
        val candidates = LinkedHashSet<String>()
        parseResourceMetadataUrlFromHeader(wwwAuthenticate)?.let { candidates += it }
        candidates += protectedMetadataCandidates(normalizedResourceUrl())

        val errors = ArrayList<String>()
        for (url in candidates) {
            val node = runCatching { getJson(url) }.getOrElse { e ->
                errors += "${url}: ${e.message}"
                null
            } ?: continue
            return node
        }

        throw IllegalStateException(
            "MCP OAuth discovery failed: cannot load protected resource metadata for ${config.name}" +
                errors.takeIf { it.isNotEmpty() }?.joinToString(prefix = " (", postfix = ")", separator = "; ").orEmpty(),
        )
    }

    private suspend fun discoverAuthorizationServerMetadata(authServer: String): McpAuthorizationServerMetadata {
        val candidates = authorizationServerMetadataCandidates(authServer)
        val errors = ArrayList<String>()
        for (url in candidates) {
            val node = runCatching { getJson(url) }.getOrElse { e ->
                errors += "${url}: ${e.message}"
                null
            } ?: continue

            val authorizationEndpoint = node.path("authorization_endpoint").asText("").trim()
            val tokenEndpoint = node.path("token_endpoint").asText("").trim()
            if (authorizationEndpoint.isBlank() || tokenEndpoint.isBlank()) {
                continue
            }
            val registrationEndpoint = node.path("registration_endpoint")
                .asText("")
                .trim()
                .ifBlank { null }
            val tokenMethods = node.path("token_endpoint_auth_methods_supported")
                .takeIf { it.isArray }
                ?.mapNotNull { it.asText("").trim().ifBlank { null } }
                ?: emptyList()

            return McpAuthorizationServerMetadata(
                issuer = node.path("issuer").asText("").trim().ifBlank { null },
                authorizationEndpoint = authorizationEndpoint,
                tokenEndpoint = tokenEndpoint,
                registrationEndpoint = registrationEndpoint,
                tokenEndpointAuthMethodsSupported = tokenMethods,
            )
        }

        throw IllegalStateException(
            "MCP OAuth discovery failed: cannot load authorization server metadata for ${config.name}" +
                errors.takeIf { it.isNotEmpty() }?.joinToString(prefix = " (", postfix = ")", separator = "; ").orEmpty(),
        )
    }

    private suspend fun ensureClientRegistrationLocked(
        metadata: McpAuthorizationServerMetadata,
        redirectUri: String?,
    ): OAuthClient {
        val clientId = state.clientId?.takeIf { it.isNotBlank() }
            ?: config.oauth.clientId?.takeIf { it.isNotBlank() }
        val clientSecret = state.clientSecret?.takeIf { it.isNotBlank() }
            ?: config.oauth.clientSecret?.takeIf { it.isNotBlank() }
        if (clientId != null) {
            return OAuthClient(clientId = clientId, clientSecret = clientSecret)
        }

        val registrationEndpoint = metadata.registrationEndpoint
            ?: throw IllegalStateException(
                "MCP OAuth requires client registration, but registration_endpoint is missing for ${config.name}. " +
                    "Set oauth.clientId manually in MCP config.",
            )
        val callbackUri = redirectUri ?: throw IllegalStateException("Missing redirect URI for client registration")

        val tokenMethod = when {
            metadata.tokenEndpointAuthMethodsSupported.contains("none") -> "none"
            metadata.tokenEndpointAuthMethodsSupported.isEmpty() -> "none"
            else -> metadata.tokenEndpointAuthMethodsSupported.first()
        }
        val body = linkedMapOf(
            "client_name" to config.oauth.clientName,
            "redirect_uris" to listOf(callbackUri),
            "grant_types" to listOf("authorization_code", "refresh_token"),
            "response_types" to listOf("code"),
            "token_endpoint_auth_method" to tokenMethod,
        )

        val response = httpClient.post(registrationEndpoint) {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            setBodyFromJson(body)
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                "MCP OAuth dynamic client registration failed (${response.status.value}): ${text.take(500)}",
            )
        }
        val node = runCatching { gigaJsonMapper.readTree(text) }
            .getOrElse { e -> throw IllegalStateException("Invalid registration response: ${e.message}") }
        val registeredClientId = node.path("client_id").asText("").trim()
        if (registeredClientId.isBlank()) {
            throw IllegalStateException("MCP OAuth registration failed: missing client_id")
        }
        val registeredClientSecret = node.path("client_secret").asText("").trim().ifBlank { null }
        state = state.copy(
            clientId = registeredClientId,
            clientSecret = registeredClientSecret,
        )
        persistState()
        return OAuthClient(
            clientId = registeredClientId,
            clientSecret = registeredClientSecret,
        )
    }

    private suspend fun tokenRequest(
        tokenEndpoint: String,
        params: Map<String, String>,
    ): JsonNode {
        val response = httpClient.submitForm(
            url = tokenEndpoint,
            formParameters = Parameters.build {
                params.forEach { (key, value) ->
                    append(key, value)
                }
            },
        ) {
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                "MCP OAuth token request failed (${response.status.value}): ${text.take(500)}",
            )
        }
        return runCatching { gigaJsonMapper.readTree(text) }
            .getOrElse { e -> throw IllegalStateException("Invalid token response: ${e.message}") }
    }

    private suspend fun getJson(url: String): JsonNode? {
        val response = httpClient.get(url) {
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
        }
        if (!response.status.isSuccess()) return null
        val text = response.bodyAsText()
        return runCatching { gigaJsonMapper.readTree(text) }.getOrNull()
    }

    private fun buildAuthorizeUrl(
        metadata: McpAuthorizationServerMetadata,
        clientId: String,
        redirectUri: String,
        state: String,
        codeChallenge: String,
    ): String {
        return URLBuilder(metadata.authorizationEndpoint).apply {
            parameters.append("response_type", "code")
            parameters.append("client_id", clientId)
            parameters.append("redirect_uri", redirectUri)
            parameters.append("state", state)
            parameters.append("code_challenge", codeChallenge)
            parameters.append("code_challenge_method", "S256")
            parameters.append("resource", normalizedResourceUrl())

            if (config.oauth.scopes.isNotEmpty()) {
                parameters.append("scope", config.oauth.scopes.joinToString(" "))
            }
            config.oauth.audience?.takeIf { it.isNotBlank() }?.let { parameters.append("audience", it) }
        }.buildString()
    }

    private fun startLoopbackServer(): LoopbackServer {
        val host = config.oauth.redirectHost.trim().ifBlank { "127.0.0.1" }
        val path = normalizePath(config.oauth.redirectPath)
        val callback = CompletableDeferred<OAuthCallback>()
        val threadName = "mcp-oauth-${sanitizeKey(config.name)}"
        val server = HttpServer.create(InetSocketAddress(host, 0), 0)
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, threadName).apply { isDaemon = true }
        }
        server.executor = executor
        val redirectUri = "http://$host:${server.address.port}$path"

        server.createContext(path) { exchange ->
            val callbackData = runCatching {
                val params = parseQuery(exchange.requestURI.rawQuery)
                OAuthCallback(
                    code = params["code"],
                    state = params["state"],
                    error = params["error"],
                    errorDescription = params["error_description"],
                )
            }.getOrElse { e ->
                l.warn("MCP OAuth callback parse failed for {}: {}", config.name, e.message)
                OAuthCallback(
                    code = null,
                    state = null,
                    error = "invalid_callback",
                    errorDescription = e.message ?: "Cannot parse callback params",
                )
            }

            val body = "Authorization completed. You can close this tab and return to souz."
                .toByteArray(Charsets.UTF_8)
            runCatching {
                exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { out -> out.write(body) }
            }.onFailure { e ->
                l.warn("MCP OAuth callback response failed for {}: {}", config.name, e.message)
            }

            if (!callback.isCompleted) {
                callback.complete(callbackData)
            }
            exchange.close()
        }

        server.start()

        return LoopbackServer(
            redirectUri = redirectUri,
            callback = callback,
            stop = {
                runCatching { server.stop(1) }
                runCatching { executor.shutdownNow() }
            },
        )
    }

    private fun openBrowser(url: String) {
        if (Desktop.isDesktopSupported()) {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.BROWSE)) {
                runCatching {
                    desktop.browse(URI(url))
                    return
                }.onFailure { e ->
                    l.warn("Failed to open browser for MCP OAuth {}: {}", config.name, e.message)
                }
            }
        }
        throw IllegalStateException("Cannot open browser automatically for MCP OAuth. Open this URL manually: $url")
    }

    private fun updateStateFromTokenResponse(
        tokenResponse: JsonNode,
        previousRefreshToken: String?,
    ): McpOAuthState {
        val accessToken = tokenResponse.path("access_token").asText("").trim().ifBlank { null }
            ?: throw IllegalStateException("MCP OAuth token response is missing access_token")
        val refreshToken = tokenResponse.path("refresh_token").asText("").trim().ifBlank { previousRefreshToken }
        val expiresInSec = tokenResponse.path("expires_in").takeIf { it.isNumber }?.asLong()
        val expiresAt = expiresInSec?.let { System.currentTimeMillis() + (it * 1000L) }
        return state.copy(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtEpochMillis = expiresAt,
        )
    }

    private fun hasValidAccessToken(state: McpOAuthState): Boolean {
        val token = state.accessToken?.takeIf { it.isNotBlank() } ?: return false
        val expiresAt = state.expiresAtEpochMillis ?: return token.isNotBlank()
        return expiresAt - 30_000L > System.currentTimeMillis()
    }

    private fun loadState(): McpOAuthState {
        val stored: McpOAuthState? = ConfigStore.get(storageKey)
        return stored ?: McpOAuthState()
    }

    private fun persistState() {
        ConfigStore.put(storageKey, state)
    }

    private fun mergeConfiguredClient(base: McpOAuthState): McpOAuthState {
        val configuredClientId = config.oauth.clientId?.takeIf { it.isNotBlank() }
        val configuredClientSecret = config.oauth.clientSecret?.takeIf { it.isNotBlank() }
        return base.copy(
            clientId = configuredClientId ?: base.clientId,
            clientSecret = configuredClientSecret ?: base.clientSecret,
        )
    }

    private fun normalizedResourceUrl(): String {
        val raw = config.url?.trim().orEmpty()
        val uri = URI(raw)
        return URI(uri.scheme, uri.authority, uri.path, null, null).toString()
    }

    private fun protectedMetadataCandidates(resourceUrl: String): List<String> {
        val uri = URI(resourceUrl)
        val origin = "${uri.scheme}://${uri.authority}"
        val path = uri.path.orEmpty().trimEnd('/')
        val candidates = LinkedHashSet<String>()
        if (path.isNotBlank() && path != "/") {
            candidates += "$origin/.well-known/oauth-protected-resource$path"
        }
        candidates += "$origin/.well-known/oauth-protected-resource"
        return candidates.toList()
    }

    private fun authorizationServerMetadataCandidates(authServer: String): List<String> {
        val raw = authServer.trim()
        val candidates = LinkedHashSet<String>()
        if (raw.contains("/.well-known/oauth-authorization-server")) {
            candidates += raw
            return candidates.toList()
        }

        val uri = URI(raw)
        val origin = "${uri.scheme}://${uri.authority}"
        val path = uri.path.orEmpty().trimEnd('/')
        if (path.isNotBlank() && path != "/") {
            candidates += "$origin/.well-known/oauth-authorization-server$path"
        }
        candidates += "${raw.trimEnd('/')}/.well-known/oauth-authorization-server"
        candidates += "$origin/.well-known/oauth-authorization-server"
        return candidates.toList()
    }

    private fun parseResourceMetadataUrlFromHeader(header: String?): String? {
        val value = header?.trim().orEmpty()
        if (value.isEmpty()) return null
        val quoted = Regex("""resource_metadata="([^"]+)"""").find(value)?.groupValues?.getOrNull(1)
        if (!quoted.isNullOrBlank()) return quoted
        val bare = Regex("""resource_metadata=([^,\s]+)""").find(value)?.groupValues?.getOrNull(1)
        return bare?.trim()?.trim('"')
    }

    private fun normalizePath(path: String): String {
        val trimmed = path.trim().ifBlank { "/mcp/oauth/callback" }
        return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        rawQuery
            .split('&')
            .filter { it.isNotBlank() }
            .forEach { part ->
                val idx = part.indexOf('=')
                val keyEncoded = if (idx >= 0) part.substring(0, idx) else part
                val valueEncoded = if (idx >= 0) part.substring(idx + 1) else ""
                val key = URLDecoder.decode(keyEncoded, Charsets.UTF_8)
                if (key.isBlank()) return@forEach
                result[key] = URLDecoder.decode(valueEncoded, Charsets.UTF_8)
            }
        return result
    }

    private fun codeChallengeS256(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun randomUrlSafe(lengthBytes: Int): String {
        val bytes = ByteArray(lengthBytes)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun sanitizeKey(input: String): String {
        val sanitized = input.replace(Regex("[^A-Za-z0-9_]"), "_").trim('_')
        return sanitized.ifBlank { "Server" }
    }
}

private data class OAuthCallback(
    val code: String?,
    val state: String?,
    val error: String?,
    val errorDescription: String?,
)

private data class LoopbackServer(
    val redirectUri: String,
    val callback: CompletableDeferred<OAuthCallback>,
    val stop: () -> Unit,
)

private data class OAuthClient(
    val clientId: String,
    val clientSecret: String?,
)

private fun io.ktor.client.request.HttpRequestBuilder.setBodyFromJson(payload: Any) {
    setBody(gigaJsonMapper.writeValueAsString(payload))
}

fun main() {
    val k = "${OAUTH_STORE_PREFIX}notion"
    println("the k is $k, val: ${ConfigStore.get<String>(k)}")
    ConfigStore.rm(k)
    println("now val: ${ConfigStore.get<String>(k)}")
}