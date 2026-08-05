package ru.souz.skilloauth.impl

/**
 * Adapter for one external OAuth2 provider. [SkillOAuthApiImpl] is generic over this interface —
 * it knows nothing about any specific provider. A provider's name (e.g. "yandex") only ever
 * appears as the key under which its [OAuthProviderClient] is registered in the `providers` map
 * passed to [SkillOAuthApiImpl], and in that provider's own config/env var naming at DI-wiring
 * time in `:backend`. Most providers implement the standard authorization-code grant and can share
 * a single [AuthorizationCodeOAuthClient] configured differently per provider; adding a second
 * provider on that path means adding another `providers` map entry, not touching this interface or
 * the core service logic.
 */
interface OAuthProviderClient {
    /** Key this client is registered under in [SkillOAuthApiImpl]'s `providers` map — a skill
     *  manifest's declared `oauthProvider` matches this, not any internal detail of the client. */
    val name: String

    /**
     * Exact hosts [SkillOAuthApiImpl.callAuthorizedApi] is allowed to attach this provider's bearer
     * token to. Skills supply an arbitrary full URL (`ApiCallRequest.url`) — without this allowlist
     * a hijacked model turn (e.g. via indirect prompt injection from content the skill legitimately
     * processes) could redirect the token to an attacker-controlled or internal host.
     */
    val allowedApiHosts: Set<String>

    fun buildAuthorizeUrl(state: String, scopes: List<String>): String

    suspend fun exchangeCode(code: String): OAuthTokenResult

    suspend fun refresh(refreshToken: String): OAuthTokenResult
}

data class OAuthTokenResult(
    val accessToken: String,
    val refreshToken: String?,
    val expiresInSeconds: Long?,
    val scopes: List<String>,
)
