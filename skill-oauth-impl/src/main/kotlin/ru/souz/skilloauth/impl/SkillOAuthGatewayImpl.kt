package ru.souz.skilloauth.impl

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import ru.souz.skilloauth.ApiCallOutcome
import ru.souz.skilloauth.ApiCallReconnectRequired
import ru.souz.skilloauth.ApiCallRequest
import ru.souz.skilloauth.ApiCallResponse
import ru.souz.skilloauth.AuthorizationState
import ru.souz.skilloauth.SkillOAuthException
import ru.souz.skilloauth.SkillOAuthGateway

/**
 * Thrown by [SkillOAuthGatewayImpl.ensureFreshAccessToken] only for failures that genuinely mean
 * the user must go through consent again (no refresh token left, or the provider confirmed the
 * grant itself is dead via `invalid_grant`). Kept as a distinct, internal subtype of
 * [SkillOAuthException] so [SkillOAuthGatewayImpl.call] can convert *only* these into
 * [ApiCallReconnectRequired] — a transient network error or a `server_error`/`invalid_client`
 * response from the provider is a plain [SkillOAuthException] instead, and propagates normally
 * rather than prompting a needless reconnect link for a problem reconnecting can't fix.
 */
internal class ReconnectRequiredException(message: String) : SkillOAuthException(message)

/**
 * Provider-agnostic: this class knows nothing about Yandex or any other specific provider. It is
 * generic over [OAuthProviderClient], resolved from [providers] by name — "yandex" only ever
 * appears as a map key supplied at DI-wiring time in `:backend`, not in any logic here. Adding a
 * second provider means adding another `providers` entry, not touching this class.
 *
 * `apiRequest.url` is a full URL supplied by the skill (a single provider can expose multiple API
 * hosts, e.g. Yandex's login.yandex.ru vs. cloud-api.yandex.net) — validated against
 * [OAuthProviderClient.allowedApiHosts] (HTTPS + host allowlist) before this class injects the
 * Authorization header and forwards the call; see [requireAllowedApiUrl].
 */
class SkillOAuthGatewayImpl(
    private val credentialRepository: SkillOAuthCredentialRepository,
    private val pendingStateRepository: SkillOAuthPendingStateRepository,
    private val crypto: SkillOAuthTokenCrypto,
    private val providers: Map<String, OAuthProviderClient>,
    private val httpClient: HttpClient = defaultSkillOAuthHttpClient(),
    private val clock: Clock = Clock.systemUTC(),
) : SkillOAuthGateway, AutoCloseable {

    override fun close() {
        runCatching { httpClient.close() }
    }

    override suspend fun ensureAuthorized(
        userId: String,
        provider: String,
        requiredScopes: Set<String>,
        force: Boolean,
    ): AuthorizationState {
        requireProviderClient(provider)
        val credential = credentialRepository.find(userId, provider)
        val granted = credential?.grantedScopes.orEmpty().toSet()
        val connected = !force &&
            credential != null &&
            isCredentialUsable(credential) &&
            requiredScopes.all { it in granted }
        if (connected) return AuthorizationState.Connected
        // Widen to the union of what's already granted plus what this caller needs, so connecting
        // a second, broader-scoped skill doesn't shrink access for skills already relying on the
        // shared (userId, provider) credential.
        val url = startAuthorization(userId, provider, granted + requiredScopes)
        return AuthorizationState.AuthorizationRequired(url)
    }

    override suspend fun call(
        userId: String,
        provider: String,
        requiredScopes: Set<String>,
        request: ApiCallRequest,
    ): ApiCallOutcome {
        val providerClient = requireProviderClient(provider)
        val credential = credentialRepository.find(userId, provider)
        if (credential == null || !isCredentialUsable(credential)) {
            return reconnectRequired(
                userId, provider, requiredScopes,
                existingGrantedScopes = credential?.grantedScopes.orEmpty().toSet(),
                reason = if (credential == null) {
                    "Not connected to '$provider'."
                } else {
                    "The OAuth connection for '$provider' has expired and cannot be refreshed."
                },
            )
        }
        val grantedScopes = credential.grantedScopes.toSet()
        val missingScopes = requiredScopes - grantedScopes
        if (missingScopes.isNotEmpty()) {
            return reconnectRequired(
                userId, provider, requiredScopes,
                existingGrantedScopes = grantedScopes,
                reason = "Requires scopes not yet granted for '$provider': $missingScopes.",
            )
        }
        requireAllowedApiUrl(providerClient, request.url)
        val accessToken = try {
            ensureFreshAccessToken(credential, providerClient)
        } catch (e: ReconnectRequiredException) {
            // Both of ensureFreshAccessToken's own reconnect-worthy failure modes (no refresh
            // token left, or a refresh token the provider just confirmed is invalid) mean the same
            // thing here: reconnecting is the only way forward. A transient network failure or a
            // provider-side/config error (server_error, invalid_client, ...) throws a plain
            // SkillOAuthException instead and is deliberately not caught here — a fresh authorize
            // link wouldn't fix either of those, so it propagates as a genuine failure.
            return reconnectRequired(
                userId, provider, requiredScopes,
                existingGrantedScopes = grantedScopes,
                reason = e.message ?: "OAuth token refresh failed.",
            )
        }
        val apiRequest = request
        val callerContentType = apiRequest.headers.entries
            .firstOrNull { (name, _) -> name.equals(HttpHeaders.ContentType, ignoreCase = true) }
            ?.value
        val response = httpClient.request(apiRequest.url) {
            method = HttpMethod.parse(apiRequest.method.uppercase())
            // Caller-supplied headers are applied first so the Authorization header we inject
            // below always wins — a skill/LLM must never be able to smuggle in its own bearer
            // token or overwrite the real one via a same-named header in `request.headers`. Content-Type
            // is excluded here and handled separately below via the `contentType()` DSL — Ktor
            // determines the actual outgoing Content-Type from the request body's own metadata,
            // not from a plain `header()` call, so setting it that way here wouldn't reliably work.
            apiRequest.headers.forEach { (name, value) ->
                if (!name.equals(HttpHeaders.Authorization, ignoreCase = true) &&
                    !name.equals(HttpHeaders.ContentType, ignoreCase = true)
                ) {
                    header(name, value)
                }
            }
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            apiRequest.body?.let {
                // Only default to JSON if the caller didn't already choose a Content-Type (e.g. a
                // provider requiring form-urlencoded or XML) — defaulting unconditionally would
                // silently override that explicit choice.
                val contentType = callerContentType?.let(ContentType::parse) ?: ContentType.Application.Json
                setBody(TextContent(it, contentType))
            }
        }
        return ApiCallResponse(
            statusCode = response.status.value,
            body = response.bodyAsText(),
            headers = response.headers.entries().associate { (name, values) -> name to values.joinToString(", ") },
        )
    }

    /**
     * Atomically widens the request to the full cumulative union ever requested for this
     * `(userId, provider)` — not just this call's own [scopes] — and supersedes any existing
     * pending state, all under one row lock. See
     * [SkillOAuthPendingStateRepository.beginAuthorization]'s doc comment for why both need to
     * happen in the same transaction. A request untouched for longer than the same TTL a pending
     * link itself lives for is treated as abandoned, not widened on.
     */
    private suspend fun startAuthorization(userId: String, provider: String, scopes: Set<String>): String {
        val providerClient = requireProviderClient(provider)
        val now = clock.instant()
        val state = generateState()
        val pending = pendingStateRepository.beginAuthorization(
            state = state,
            userId = userId,
            // The public SkillOAuthGateway contract no longer threads a real skillId through (see
            // its kdoc on why per-Skill isolation was never actually enforced) — this repository
            // column is now pure historical audit data that nothing reads, so a placeholder is
            // simpler than a schema change for a column with no remaining consumer.
            skillId = "",
            provider = provider,
            scopes = scopes.toList(),
            now = now,
            activeSince = now.minusSeconds(PENDING_STATE_TTL_SECONDS),
            expiresAt = now.plusSeconds(PENDING_STATE_TTL_SECONDS),
        )
        return providerClient.buildAuthorizeUrl(state = state, scopes = pending.requestedScopes)
    }

    /**
     * Generates a fresh authorize link (via [startAuthorization], which itself widens the request
     * further still to cover everything ever asked for this `(userId, provider)`) and packages it
     * as a normal [ApiCallOutcome], computed exactly once per [call]. There is no tool-level or
     * executor-level retry of a failed call in this codebase (unlike MCP tools, which do retry
     * once) — if one is ever added for this path, it must sit *outside* this method, since calling
     * it twice would mint (and supersede) a second link for the same turn.
     *
     * [existingGrantedScopes] — the credential's *current* grantedScopes, if any — is folded in
     * explicitly rather than relying solely on [startAuthorization]'s own widening: that widening
     * reads the durable requested-scope tracking, which has its own staleness cutoff (see
     * [SkillOAuthPendingStateRepository.beginAuthorization]) and can therefore treat an old,
     * completed grant as "abandoned" and drop it. Without this, reconnecting because one caller's
     * own token merely expired could silently narrow (and kick out) another caller's still-valid
     * grant on the same shared credential the moment the callback saves a scope-narrower token.
     */
    private suspend fun reconnectRequired(
        userId: String,
        provider: String,
        requiredScopes: Set<String>,
        existingGrantedScopes: Set<String>,
        reason: String,
    ): ApiCallReconnectRequired {
        val url = startAuthorization(userId, provider, existingGrantedScopes + requiredScopes)
        return ApiCallReconnectRequired(
            authorizationUrl = url,
            message = "$reason Open this link to reconnect, then retry: $url",
        )
    }

    /**
     * Rejects anything but an HTTPS URL on one of the provider's own declared
     * [OAuthProviderClient.allowedApiHosts]. `ApiCallRequest.url` is a model-supplied full URL — a
     * hijacked model turn (e.g. via indirect prompt injection) could otherwise redirect the bearer
     * token to an attacker-controlled or internal host.
     */
    private fun requireAllowedApiUrl(providerClient: OAuthProviderClient, rawUrl: String) {
        val url = try {
            Url(rawUrl)
        } catch (e: Exception) {
            throw SkillOAuthException("Invalid API URL: $rawUrl")
        }
        if (url.protocol != URLProtocol.HTTPS) {
            throw SkillOAuthException("Only HTTPS API URLs are allowed, got: $rawUrl")
        }
        if (url.host !in providerClient.allowedApiHosts) {
            throw SkillOAuthException(
                "Host '${url.host}' is not an allowed API host for this provider. " +
                    "Allowed hosts: ${providerClient.allowedApiHosts}"
            )
        }
    }

    /**
     * Handles the provider redirect: exchanges `code` for tokens and stores them.
     * Intentionally not part of [SkillOAuthGateway]: called only by the route installer in
     * [installSkillOAuthRoutes], never by tool/agent code, since the callback is triggered by the
     * provider's redirect rather than requested by any caller of this API.
     */
    internal suspend fun handleCallback(code: String, state: String): CallbackResult {
        val pending = pendingStateRepository.consume(state, clock.instant())
            ?: return CallbackResult.InvalidOrExpiredState
        val providerClient = providers[pending.provider]
            ?: return CallbackResult.ExchangeFailed("Unknown OAuth provider: ${pending.provider}")
        val tokenResult = try {
            providerClient.exchangeCode(code)
        } catch (e: SkillOAuthException) {
            return CallbackResult.ExchangeFailed(e.message ?: "OAuth token exchange failed.")
        }
        val now = clock.instant()
        // credentialRepository.upsert carries pending.generation through and silently no-ops if a
        // fresher authorization has since been saved for this (userId, provider) — this callback's
        // own pending state was already consumed above, so a second, unrelated authorization could
        // have started (and even finished) while this exchange was in flight. In the common case
        // that second authorization already widened its own request to include this one's scopes
        // (see startAuthorization/SkillOAuthPendingStateRepository.beginAuthorization), so nothing
        // real is lost even when this write does get discarded — the browser still reports success
        // to this callback's user regardless, since their consent with the provider genuinely
        // succeeded.
        credentialRepository.upsert(
            SkillOAuthCredential(
                userId = pending.userId,
                provider = pending.provider,
                accessTokenEncrypted = crypto.encrypt(tokenResult.accessToken),
                refreshTokenEncrypted = tokenResult.refreshToken?.let(crypto::encrypt),
                grantedScopes = tokenResult.scopes.ifEmpty { pending.requestedScopes },
                expiresAt = tokenResult.expiresInSeconds?.let { now.plusSeconds(it) },
                generation = pending.generation,
                createdAt = now,
                updatedAt = now,
            )
        )
        return CallbackResult.Connected(pending.provider)
    }

    /**
     * Whether [credential] can still yield a usable access token: either it isn't expired yet, or
     * it is but a refresh token is on file to recover with. Distinct from [ensureFreshAccessToken]'s
     * own expiry check, which additionally applies [EXPIRY_SAFETY_MARGIN_SECONDS] to decide whether
     * to *proactively* refresh — this one only asks whether recovery is possible at all, which is
     * what [ensureAuthorized] and [call] need: a credential that merely needs a refresh soon is
     * still "connected"; one that has expired with no way to refresh is not, and must not be
     * reported as connected just because a row for it exists (that would leave [ensureAuthorized]
     * permanently reporting "already connected" instead of ever issuing a fresh authorize URL).
     */
    private fun isCredentialUsable(credential: SkillOAuthCredential): Boolean {
        val expiresAt = credential.expiresAt ?: return true
        if (expiresAt.isAfter(clock.instant())) return true
        return credential.refreshTokenEncrypted != null
    }

    private suspend fun ensureFreshAccessToken(
        credential: SkillOAuthCredential,
        providerClient: OAuthProviderClient,
    ): String {
        val expiresAt = credential.expiresAt
        val refreshTokenEncrypted = credential.refreshTokenEncrypted
        // The safety margin only makes sense when a refresh is actually possible — applying it
        // regardless of whether refreshTokenEncrypted exists opened a dead zone: isCredentialUsable
        // (no margin, exact expiresAt) would still say "connected" for up to
        // EXPIRY_SAFETY_MARGIN_SECONDS while this method already threw. Without a refresh token,
        // the access token remains just as usable right up to its real expiry.
        val stillFresh = expiresAt == null || if (refreshTokenEncrypted == null) {
            expiresAt.isAfter(clock.instant())
        } else {
            expiresAt.isAfter(clock.instant().plusSeconds(EXPIRY_SAFETY_MARGIN_SECONDS))
        }
        if (stillFresh) {
            return crypto.decrypt(credential.accessTokenEncrypted)
        }
        val refreshToken = refreshTokenEncrypted
            ?: throw ReconnectRequiredException(
                "OAuth access token for '${credential.provider}' has expired and no refresh token is available."
            )
        val refreshed = try {
            providerClient.refresh(crypto.decrypt(refreshToken))
        } catch (e: OAuthProviderErrorException) {
            // Only `invalid_grant` (RFC 6749 §5.2) actually confirms the refresh token/grant itself
            // is dead. Other codes say nothing about the token's validity: `invalid_client` is our
            // own client_id/secret misconfiguration, and `server_error`/`temporarily_unavailable`
            // are meant to be retried, not treated as "reconnect required" — clearing a perfectly
            // good refresh token over one of those would force a needless full consent flow.
            if (e.errorCode == "invalid_grant") {
                // Generation-guarded, so this can't clobber a fresher credential that's since
                // replaced this one — see credentialRepository.upsert's doc comment.
                credentialRepository.upsert(credential.copy(refreshTokenEncrypted = null))
                throw ReconnectRequiredException(
                    "OAuth refresh for '${credential.provider}' failed (${e.errorCode}): ${e.message}."
                )
            }
            throw SkillOAuthException(
                "OAuth refresh for '${credential.provider}' failed (${e.errorCode}): ${e.message}."
            )
        }
        val now = clock.instant()
        // Not generation-guarded meaningfully going stale here — a refresh carries the *same*
        // generation forward (it isn't a new authorization) — but the guard still protects against
        // this refresh's write landing after a concurrent, newer authorization already replaced the
        // credential entirely: that write's higher generation wins, this one silently no-ops, and
        // the token returned below is still valid for use in *this* call regardless.
        credentialRepository.upsert(
            credential.copy(
                accessTokenEncrypted = crypto.encrypt(refreshed.accessToken),
                refreshTokenEncrypted = refreshed.refreshToken?.let(crypto::encrypt)
                    ?: credential.refreshTokenEncrypted,
                grantedScopes = refreshed.scopes.ifEmpty { credential.grantedScopes },
                expiresAt = refreshed.expiresInSeconds?.let { now.plusSeconds(it) },
                updatedAt = now,
            )
        )
        return refreshed.accessToken
    }

    private fun requireProviderClient(provider: String): OAuthProviderClient =
        providers[provider] ?: throw SkillOAuthException(
            "Unsupported OAuth provider: '$provider'. Configured providers: ${providers.keys.ifEmpty { setOf("none") }}"
        )

    private fun generateState(): String {
        val bytes = ByteArray(STATE_BYTES).also(SecureRandom()::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        const val PENDING_STATE_TTL_SECONDS = 600L
        const val EXPIRY_SAFETY_MARGIN_SECONDS = 60L
        const val STATE_BYTES = 32
    }
}

internal sealed interface CallbackResult {
    data class Connected(val provider: String) : CallbackResult
    data object InvalidOrExpiredState : CallbackResult
    data class ExchangeFailed(val reason: String) : CallbackResult
}
