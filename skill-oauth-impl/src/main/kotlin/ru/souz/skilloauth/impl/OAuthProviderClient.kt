package ru.souz.skilloauth.impl

import ru.souz.skilloauth.SkillOAuthException

/**
 * Adapter for one external OAuth2 provider. [SkillOAuthGatewayImpl] is generic over this interface —
 * it knows nothing about any specific provider. A provider's name (e.g. "yandex") only ever
 * appears as the key under which its [OAuthProviderClient] is registered in the `providers` map
 * passed to [SkillOAuthGatewayImpl], and in that provider's own config/env var naming at DI-wiring
 * time in `:backend`. Most providers implement the standard authorization-code grant and can share
 * a single [AuthorizationCodeOAuthClient] configured differently per provider; adding a second
 * provider on that path means adding another `providers` map entry, not touching this interface or
 * the core service logic.
 */
interface OAuthProviderClient {
    /** Key this client is registered under in [SkillOAuthGatewayImpl]'s `providers` map — a skill
     *  manifest's declared `oauthProvider` matches this, not any internal detail of the client. */
    val name: String

    /**
     * Exact hosts [SkillOAuthGatewayImpl.call] is allowed to attach this provider's bearer
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

/**
 * Thrown when a provider's token endpoint responds with a structured `error` code (RFC 6749 §5.2)
 * instead of a token. [errorCode] is preserved so callers can distinguish `invalid_grant` — the
 * refresh/authorization grant itself is dead — from codes that say nothing about the grant's
 * validity (`invalid_client` is *our* misconfiguration; `server_error`/`temporarily_unavailable`
 * are meant to be retried, per the RFC). Treating every provider error alike would, for example,
 * make [SkillOAuthGatewayImpl.ensureFreshAccessToken] discard a perfectly good refresh token over a
 * transient provider hiccup.
 */
internal class OAuthProviderErrorException(
    val errorCode: String,
    message: String,
) : SkillOAuthException(message)
