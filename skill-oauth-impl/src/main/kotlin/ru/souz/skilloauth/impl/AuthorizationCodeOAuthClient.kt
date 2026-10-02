package ru.souz.skilloauth.impl

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.ParametersBuilder
import io.ktor.http.encodeURLParameter

/** Provider endpoints and registered-app credentials, supplied by the host. */
class AuthorizationCodeOAuthConfig(
    val name: String,
    val authorizeEndpoint: String,
    val tokenEndpoint: String,
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String,
    /** Exact hosts allowed to receive this provider's access token. */
    val allowedApiHosts: Set<String>,
    /** Fixed authorize parameters, such as Google's offline-access and consent options. */
    val extraAuthorizeParams: Map<String, String>,
    val authorizationScheme: String,
)

/** RFC 6749 authorization-code and refresh grants using form requests and JSON responses. */
class AuthorizationCodeOAuthClient(
    private val config: AuthorizationCodeOAuthConfig,
    private val httpClient: HttpClient = defaultSkillOAuthHttpClient(),
) : OAuthProviderClient, AutoCloseable {
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

    override suspend fun exchangeCode(code: String): OAuthTokenResult = requestToken("authorization_code") {
        append("code", code)
        append("redirect_uri", config.redirectUri)
    }

    override suspend fun refresh(refreshToken: String): OAuthTokenResult = requestToken("refresh_token") {
        append("refresh_token", refreshToken)
    }

    private suspend fun requestToken(grantType: String, grantParameters: ParametersBuilder.() -> Unit): OAuthTokenResult {
        val response = httpClient.submitForm(
            url = config.tokenEndpoint,
            formParameters = Parameters.build {
                append("grant_type", grantType)
                append("client_id", config.clientId)
                append("client_secret", config.clientSecret)
                grantParameters()
            },
        )
        val parsed = tokenReader.readValue<OAuthTokenResponse>(response.bodyAsText())
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

    private companion object {
        // ObjectReader is immutable and thread-safe; all providers share its cached deserializer.
        val tokenReader = jacksonObjectMapper().readerFor(OAuthTokenResponse::class.java)
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
