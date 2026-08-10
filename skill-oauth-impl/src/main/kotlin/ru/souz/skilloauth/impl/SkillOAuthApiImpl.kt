package ru.souz.skilloauth.impl

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import ru.souz.skilloauth.ApiCallRequest
import ru.souz.skilloauth.ApiCallResponse
import ru.souz.skilloauth.AuthorizationUrl
import ru.souz.skilloauth.OAuthStatus
import ru.souz.skilloauth.SkillOAuthApi
import ru.souz.skilloauth.SkillOAuthException

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
class SkillOAuthApiImpl(
    private val credentialRepository: SkillOAuthCredentialRepository,
    private val pendingStateRepository: SkillOAuthPendingStateRepository,
    private val crypto: SkillOAuthTokenCrypto,
    private val providers: Map<String, OAuthProviderClient>,
    private val httpClient: HttpClient = defaultSkillOAuthHttpClient(),
    private val clock: Clock = Clock.systemUTC(),
) : SkillOAuthApi, AutoCloseable {

    override fun close() {
        runCatching { httpClient.close() }
    }

    override suspend fun status(userId: String, provider: String, requiredScopes: List<String>): OAuthStatus {
        requireProviderClient(provider)
        val credential = credentialRepository.find(userId, provider)
        val granted = credential?.grantedScopes.orEmpty()
        val missing = requiredScopes.filterNot { it in granted }
        return OAuthStatus(
            connected = credential != null && isCredentialUsable(credential) && missing.isEmpty(),
            grantedScopes = granted,
            missingScopes = missing,
        )
    }

    override suspend fun startAuthorization(
        userId: String,
        provider: String,
        skillId: String,
        scopes: List<String>,
    ): AuthorizationUrl {
        val providerClient = requireProviderClient(provider)
        val state = generateState()
        // A single atomic upsert (see [SkillOAuthPendingStateRepository.upsertSupersedingByUserAndProvider])
        // rather than a separate read-then-write: two concurrent calls for the same (userId, provider)
        // can never both "win" and leave two live pending states, and whichever link the user ends up
        // completing always asks for the full scope set requested so far. The superseded link, if
        // opened afterwards, fails cleanly as an invalid/expired state.
        val stored = pendingStateRepository.upsertSupersedingByUserAndProvider(
            SkillOAuthPendingState(
                state = state,
                userId = userId,
                skillId = skillId,
                provider = provider,
                requestedScopes = scopes,
                expiresAt = clock.instant().plusSeconds(PENDING_STATE_TTL_SECONDS),
            )
        )
        return AuthorizationUrl(providerClient.buildAuthorizeUrl(state = state, scopes = stored.requestedScopes))
    }

    override suspend fun callAuthorizedApi(
        userId: String,
        provider: String,
        skillId: String,
        requiredScopes: List<String>,
        request: ApiCallRequest,
    ): ApiCallResponse {
        val providerClient = requireProviderClient(provider)
        val credential = credentialRepository.find(userId, provider)
            ?: throw SkillOAuthException(
                "Skill '$skillId' is not connected to '$provider'. Use ConnectOAuthProvider first."
            )
        if (!isCredentialUsable(credential)) {
            throw SkillOAuthException(
                "The OAuth connection for '$provider' has expired and cannot be refreshed. " +
                    "Use ConnectOAuthProvider to reconnect."
            )
        }
        val missingScopes = requiredScopes.filterNot { it in credential.grantedScopes }
        if (missingScopes.isNotEmpty()) {
            throw SkillOAuthException(
                "Skill '$skillId' requires scopes not yet granted for '$provider': $missingScopes. " +
                    "Use ConnectOAuthProvider first."
            )
        }
        requireAllowedApiUrl(providerClient, request.url)
        val accessToken = ensureFreshAccessToken(credential, providerClient)
        val apiRequest = request
        val response = httpClient.request(apiRequest.url) {
            method = HttpMethod.parse(apiRequest.method.uppercase())
            // Caller-supplied headers are applied first so the Authorization header we inject
            // below always wins — a skill/LLM must never be able to smuggle in its own bearer
            // token or overwrite the real one via a same-named header in `request.headers`.
            apiRequest.headers.forEach { (name, value) ->
                if (!name.equals(HttpHeaders.Authorization, ignoreCase = true)) {
                    header(name, value)
                }
            }
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            apiRequest.body?.let {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(it)
            }
        }
        return ApiCallResponse(
            statusCode = response.status.value,
            body = response.bodyAsText(),
            headers = response.headers.entries().associate { (name, values) -> name to values.joinToString(", ") },
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
     * Intentionally not part of [SkillOAuthApi] — called only by the route installer in
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
        // credentialRepository.upsert merges grantedScopes with whatever's already stored for this
        // (userId, provider) rather than replacing it — needed because this callback's own pending
        // state is already consumed by the time we get here, so a second, unrelated authorization
        // for the same (userId, provider) can start and even finish while this exchange is still in
        // flight without either one erasing what the other just had the user grant.
        credentialRepository.upsert(
            SkillOAuthCredential(
                userId = pending.userId,
                provider = pending.provider,
                accessTokenEncrypted = crypto.encrypt(tokenResult.accessToken),
                refreshTokenEncrypted = tokenResult.refreshToken?.let(crypto::encrypt),
                grantedScopes = tokenResult.scopes.ifEmpty { pending.requestedScopes },
                expiresAt = tokenResult.expiresInSeconds?.let { now.plusSeconds(it) },
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
     * what [status] and [callAuthorizedApi] need: a credential that merely needs a refresh soon is
     * still "connected"; one that has expired with no way to refresh is not, and must not be
     * reported as connected just because a row for it exists (that would leave `ConnectOAuthProvider`
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
        if (expiresAt == null || expiresAt.isAfter(clock.instant().plusSeconds(EXPIRY_SAFETY_MARGIN_SECONDS))) {
            return crypto.decrypt(credential.accessTokenEncrypted)
        }
        val refreshTokenEncrypted = credential.refreshTokenEncrypted
            ?: throw SkillOAuthException(
                "OAuth access token for '${credential.provider}' has expired and no refresh token " +
                    "is available. Use ConnectOAuthProvider to reconnect."
            )
        val refreshed = providerClient.refresh(crypto.decrypt(refreshTokenEncrypted))
        val now = clock.instant()
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
