package ru.souz.skilloauth.impl

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.encodeURLParameter
import ru.souz.skilloauth.SkillOAuthException

/**
 * Config for one provider's registered app. Everything provider-specific (endpoints, allowed API
 * hosts, and — at DI-wiring time in `:backend` — which env vars feed `clientId`/`clientSecret`)
 * lives outside this class; [AuthorizationCodeOAuthClient] itself has no knowledge of which
 * provider a given instance talks to.
 */
class AuthorizationCodeOAuthConfig(
    val name: String,
    val authorizeEndpoint: String,
    val tokenEndpoint: String,
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String,
    /** Hosts `callAuthorizedApi` may attach the bearer token to — an explicit per-provider
     *  allowlist, not a pattern (e.g. "any subdomain of X") an attacker-registered host could
     *  match. */
    val allowedApiHosts: Set<String>,
    /**
     * Extra fixed query parameters appended to every authorize URL, verbatim — e.g. Google only
     * reliably returns a `refresh_token` when `access_type=offline` is present, and only reissues
     * one on a repeat consent when `prompt=consent` is also present (RFC 6749 has no standard
     * knob for this; it's provider-specific behavior on top of the standard flow, not a reason to
     * need a whole separate [OAuthProviderClient] implementation).
     */
    val extraAuthorizeParams: Map<String, String>,
    val authorizationScheme: String,
)

/**
 * [OAuthProviderClient] for the standard RFC 6749 authorization-code grant — POST-form token
 * requests, space-separated scopes, `access_token`/`refresh_token`/`expires_in`/`scope` response
 * fields — the shape most OAuth2 providers implement out of the box (Yandex, GitHub, VK, generic
 * OIDC providers, etc.). A single instance, parameterized by [AuthorizationCodeOAuthConfig], can
 * back any number of registered providers; adding one is a new config entry at DI-wiring time in
 * `:backend`, not a new class. A provider needing something non-standard (PKCE, a JSON token
 * request body, differently-shaped error payloads) would need its own [OAuthProviderClient]
 * implementation instead.
 */
class AuthorizationCodeOAuthClient(
    private val config: AuthorizationCodeOAuthConfig,
    private val httpClient: HttpClient = defaultSkillOAuthHttpClient(),
) : OAuthProviderClient, AutoCloseable {
    private val mapper = jacksonObjectMapper()

    override val name: String = config.name
    override val allowedApiHosts: Set<String> = config.allowedApiHosts
    override val authorizationScheme: String = config.authorizationScheme

    override fun close() {
        runCatching { httpClient.close() }
    }

    override fun buildAuthorizeUrl(state: String, scopes: List<String>): String {
        val query = buildString {
            append("response_type=code")
            append("&client_id=${config.clientId.encodeURLParameter()}")
            append("&redirect_uri=${config.redirectUri.encodeURLParameter()}")
            append("&state=${state.encodeURLParameter()}")
            if (scopes.isNotEmpty()) {
                append("&scope=${scopes.joinToString(" ").encodeURLParameter()}")
            }
            config.extraAuthorizeParams.forEach { (key, value) ->
                append("&${key.encodeURLParameter()}=${value.encodeURLParameter()}")
            }
        }
        return "${config.authorizeEndpoint}?$query"
    }

    override suspend fun exchangeCode(code: String): OAuthTokenResult =
        httpClient.submitForm(
            url = config.tokenEndpoint,
            formParameters = Parameters.build {
                append("grant_type", "authorization_code")
                append("code", code)
                append("client_id", config.clientId)
                append("client_secret", config.clientSecret)
                append("redirect_uri", config.redirectUri)
            },
        ).bodyAsText().toTokenResult()

    override suspend fun refresh(refreshToken: String): OAuthTokenResult =
        httpClient.submitForm(
            url = config.tokenEndpoint,
            formParameters = Parameters.build {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
                append("client_id", config.clientId)
                append("client_secret", config.clientSecret)
            },
        ).bodyAsText().toTokenResult()

    private fun String.toTokenResult(): OAuthTokenResult {
        val parsed = mapper.readValue(this, OAuthTokenResponse::class.java)
        val accessToken = parsed.access_token
            ?: throw OAuthProviderErrorException(
                errorCode = parsed.error ?: "unknown_error",
                message = "OAuth token request failed: ${parsed.error ?: "unknown_error"} ${parsed.error_description.orEmpty()}",
            )
        return OAuthTokenResult(
            accessToken = accessToken,
            refreshToken = parsed.refresh_token,
            expiresInSeconds = parsed.expires_in,
            scopes = parsed.scope?.split(" ")?.filter(String::isNotBlank) ?: emptyList(),
        )
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class OAuthTokenResponse(
    val access_token: String? = null,
    val refresh_token: String? = null,
    val expires_in: Long? = null,
    val scope: String? = null,
    val error: String? = null,
    val error_description: String? = null,
)
