package ru.souz.skilloauth.impl

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

data class OAuthProviderCatalogEntry(
    val name: String,
    val authorizeEndpoint: String,
    val tokenEndpoint: String,
    val allowedApiHosts: Set<String>,
    val extraAuthorizeParams: Map<String, String> = emptyMap(),
    /** See [OAuthProviderClient.authorizationScheme]; defaults to the RFC 6750-standard `Bearer`. */
    val authorizationScheme: String = "Bearer",
)

/**
 * Built-in registry of providers that implement the standard RFC 6749 authorization-code grant
 * (see [AuthorizationCodeOAuthClient]) — endpoints and allowed API hosts only, never secrets.
 * Client id/secret/redirect-uri stay in env vars (see `BackendAppConfig`), keyed by [entries]'
 * `name`. Adding a standard-flow provider means adding an entry to `oauth-providers.json` and
 * setting its `<NAME>_OAUTH_CLIENT_ID`/`_CLIENT_SECRET`/`_REDIRECT_URI` env vars — no code change.
 */
object OAuthProviderCatalog {
    val entries: List<OAuthProviderCatalogEntry> by lazy {
        val resource = requireNotNull(javaClass.getResourceAsStream("/oauth-providers.json")) {
            "oauth-providers.json resource not found on the classpath."
        }
        resource.use { jacksonObjectMapper().readValue(it) }
    }
}
