package ru.souz.skilloauth

/**
 * Host-owned OAuth connection/token service consumed by skill tools.
 *
 * Grants are stored per `(userId, provider)`, shared across every skill that declares the same
 * provider for that user — there is no per-Skill security boundary here, only a per-Skill
 * *declaration* of the scopes a skill needs, used to widen the shared grant and to decide whether
 * it already covers what the calling skill requires. If a real per-Skill capability boundary is
 * ever needed, this interface is not it; that would require a service-issued, Skill-bound token
 * with its own enforceable policy, not just a wider `requiredScopes` check.
 *
 * Implementations own the whole OAuth lifecycle (authorization, token storage, refresh, and the
 * actual authorized HTTP call) so that a raw access token never has to cross into a skill's
 * sandboxed process or the LLM's context. The inbound OAuth redirect callback is deliberately not
 * part of this interface — it is triggered by the provider, not by any caller of this API — see
 * the route-installer function in `:skill-oauth-impl` instead.
 *
 * All methods are `suspend` so that a local, in-process implementation can later be swapped for a
 * thin HTTP/gRPC client talking to an extracted microservice without any change to callers.
 */
interface SkillOAuthGateway {
    /**
     * Idempotent check-and-start: returns [AuthorizationState.Connected] when the shared credential
     * already covers [requiredScopes], otherwise mints a fresh authorize link (widened to cover
     * everything ever requested for this `(userId, provider)`, not just [requiredScopes]) and
     * returns [AuthorizationState.AuthorizationRequired]. Calling this repeatedly while already
     * connected is safe — it never starts a new authorization unless [force] is set.
     *
     * [force] bypasses the "already connected" short-circuit — use only when the caller has reason
     * to believe the stored grant is stale (e.g. the user reports it stopped working), since a
     * token can be revoked on the provider's side without this service finding out until it's used.
     */
    suspend fun ensureAuthorized(
        userId: String,
        provider: String,
        requiredScopes: Set<String>,
        force: Boolean = false,
    ): AuthorizationState

    /**
     * [requiredScopes] is enforced the same way as in [ensureAuthorized] — the call is refused, not
     * just reported, if the shared credential doesn't cover them.
     *
     * Needing to (re)connect is a routine, expected outcome of calling this — tokens expire, scopes
     * get added, refresh tokens get revoked — not a caller error, so it is modeled as a normal
     * [ApiCallOutcome] rather than a thrown exception: see [ApiCallReconnectRequired].
     * [SkillOAuthException] is still thrown for genuine caller mistakes (an unsupported provider, a
     * malformed or disallowed [ApiCallRequest.url]) and for failures that aren't a matter of
     * reconnecting (e.g. a transient network error reaching the provider during token refresh).
     */
    suspend fun call(
        userId: String,
        provider: String,
        requiredScopes: Set<String>,
        request: ApiCallRequest,
    ): ApiCallOutcome
}

/** Result of [SkillOAuthGateway.ensureAuthorized]. */
sealed interface AuthorizationState {
    data object Connected : AuthorizationState
    data class AuthorizationRequired(val url: String) : AuthorizationState
}

data class ApiCallRequest(
    val method: String,
    val url: String,
    val body: String? = null,
    /**
     * Extra headers to forward with the outbound call, e.g. `Content-Type` overrides or
     * provider-specific headers. The implementation injects its own `Authorization` header with
     * the real access token, which callers can never see or override — see
     * `SkillOAuthGatewayImpl.call`.
     */
    val headers: Map<String, String> = emptyMap(),
)

/** Result of [SkillOAuthGateway.call] — either the provider actually responded
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
 * is already generated (an equivalent of calling [SkillOAuthGateway.ensureAuthorized] with the
 * calling skill's own required scopes) so the caller can relay it immediately, without a separate
 * round trip.
 */
data class ApiCallReconnectRequired(
    val authorizationUrl: String,
    val message: String,
) : ApiCallOutcome

open class SkillOAuthException(message: String) : Exception(message)
