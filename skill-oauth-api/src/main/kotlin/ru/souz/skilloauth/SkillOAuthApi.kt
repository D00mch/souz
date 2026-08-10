package ru.souz.skilloauth

/**
 * Host-owned OAuth connection/token service consumed by skill tools.
 *
 * Implementations own the whole OAuth lifecycle (authorization, token storage,
 * refresh, and the actual authorized HTTP call) so that a raw access token never
 * has to cross into a skill's sandboxed process or the LLM's context. The
 * inbound OAuth redirect callback is deliberately not part of this interface —
 * it is triggered by the provider, not by any caller of this API — see the
 * route-installer function in `:skill-oauth-impl` instead.
 *
 * All methods are `suspend` so that a local, in-process implementation can later
 * be swapped for a thin HTTP/gRPC client talking to an extracted microservice
 * without any change to callers.
 */
interface SkillOAuthApi {
    /**
     * [requiredScopes] is the *calling skill's own* declared `oauthScopes` — [OAuthStatus.connected]
     * is only true when a credential exists AND covers every one of them. A shared `(userId,
     * provider)` credential can accumulate broader scopes than any single skill declared (see
     * [startAuthorization]); without this check a narrowly-scoped skill would silently ride on
     * another skill's broader grant.
     */
    suspend fun status(userId: String, provider: String, requiredScopes: List<String> = emptyList()): OAuthStatus

    suspend fun startAuthorization(
        userId: String,
        provider: String,
        skillId: String,
        scopes: List<String>,
    ): AuthorizationUrl

    /**
     * [requiredScopes] is enforced the same way as in [status] — the call is refused, not just
     * reported, if the shared credential doesn't cover the calling skill's own declared scopes.
     *
     * Needing to (re)connect is a routine, expected outcome of calling this — tokens expire,
     * scopes get added, refresh tokens get revoked — not a caller error, so it is modeled as a
     * normal [ApiCallOutcome] rather than a thrown exception: see [ApiCallReconnectRequired].
     * [SkillOAuthException] is still thrown for genuine caller mistakes (an unsupported provider,
     * a malformed or disallowed [ApiCallRequest.url]) and for failures that aren't a matter of
     * reconnecting (e.g. a transient network error reaching the provider during token refresh).
     */
    suspend fun callAuthorizedApi(
        userId: String,
        provider: String,
        skillId: String,
        requiredScopes: List<String>,
        request: ApiCallRequest,
    ): ApiCallOutcome
}

data class OAuthStatus(
    val connected: Boolean,
    val grantedScopes: List<String> = emptyList(),
    val missingScopes: List<String> = emptyList(),
)

data class AuthorizationUrl(
    val url: String,
)

data class ApiCallRequest(
    val method: String,
    val url: String,
    val body: String? = null,
    /**
     * Extra headers to forward with the outbound call, e.g. `Content-Type` overrides or
     * provider-specific headers. The implementation injects its own `Authorization` header with
     * the real access token, which callers can never see or override — see
     * `SkillOAuthApiImpl.callAuthorizedApi`.
     */
    val headers: Map<String, String> = emptyMap(),
)

/** Result of [SkillOAuthApi.callAuthorizedApi] — either the provider actually responded
 *  ([ApiCallResponse]), or a (re)connect is needed first ([ApiCallReconnectRequired]). */
sealed interface ApiCallOutcome

data class ApiCallResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
) : ApiCallOutcome

/**
 * No token exists yet, the stored one is unusable (expired with no way to refresh, or its refresh
 * token was confirmed invalid by the provider), or the shared credential doesn't yet cover
 * [requiredScopes] — either way, the caller needs a fresh trip through consent. [authorizationUrl]
 * is already generated (an equivalent of calling [SkillOAuthApi.startAuthorization] with the
 * calling skill's own required scopes) so the caller can relay it immediately, without a separate
 * round trip.
 */
data class ApiCallReconnectRequired(
    val authorizationUrl: String,
    val message: String,
) : ApiCallOutcome

class SkillOAuthException(message: String) : Exception(message)
