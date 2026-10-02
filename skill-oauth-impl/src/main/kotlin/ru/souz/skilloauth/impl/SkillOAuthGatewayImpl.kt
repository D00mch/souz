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
import io.ktor.http.content.TextContent
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

/** Only unrecoverable grants become reconnect outcomes; provider/configuration failures propagate. */
internal class ReconnectRequiredException(message: String) : SkillOAuthException(message)

/** Shared per-user/provider grants, encrypted persistence, and authorized calls to allowed HTTPS hosts. */
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
        val providerClient = requireProviderClient(provider)
        val credential = credentialRepository.find(userId, provider)
        val granted = credential?.grantedScopes.orEmpty().toSet()
        if (!force && credential != null && isCredentialUsable(credential) && granted.containsAll(requiredScopes)) {
            return AuthorizationState.Connected
        }
        return AuthorizationState.AuthorizationRequired(
            startAuthorization(userId, provider, providerClient, granted + requiredScopes, force),
        )
    }

    override suspend fun call(
        userId: String,
        provider: String,
        requiredScopes: Set<String>,
        request: ApiCallRequest,
    ): ApiCallOutcome {
        val providerClient = requireProviderClient(provider)
        val credential = credentialRepository.find(userId, provider)
        val grantedScopes = credential?.grantedScopes.orEmpty().toSet()
        suspend fun reconnect(reason: String): ApiCallReconnectRequired {
            // Include existing grants even when durable requested-scope tracking has aged out.
            val url = startAuthorization(userId, provider, providerClient, grantedScopes + requiredScopes)
            return ApiCallReconnectRequired(url, "$reason Open this link to reconnect, then retry: $url")
        }

        if (credential == null) return reconnect("Not connected to '$provider'.")
        if (!isCredentialUsable(credential)) {
            return reconnect("The OAuth connection for '$provider' has expired and cannot be refreshed.")
        }
        val missingScopes = requiredScopes - grantedScopes
        if (missingScopes.isNotEmpty()) {
            return reconnect("Requires scopes not yet granted for '$provider': $missingScopes.")
        }
        requireAllowedApiUrl(providerClient, request.url)
        val accessToken = try {
            ensureFreshAccessToken(credential, providerClient)
        } catch (e: ReconnectRequiredException) {
            return reconnect(e.message ?: "OAuth token refresh failed.")
        }
        val callerContentType = request.headers.entries
            .firstOrNull { (name, _) -> name.equals(HttpHeaders.ContentType, ignoreCase = true) }
            ?.value
        val response = httpClient.request(request.url) {
            method = HttpMethod.parse(request.method.uppercase())
            request.headers.forEach { (name, value) ->
                if (!name.equals(HttpHeaders.Authorization, ignoreCase = true) &&
                    !name.equals(HttpHeaders.ContentType, ignoreCase = true)
                ) {
                    header(name, value)
                }
            }
            header(HttpHeaders.Authorization, "${providerClient.authorizationScheme} $accessToken")
            request.body?.let {
                // Ktor takes Content-Type from body metadata; a plain header does not override it.
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

    private suspend fun startAuthorization(
        userId: String,
        provider: String,
        providerClient: OAuthProviderClient,
        scopes: Set<String>,
        force: Boolean = false,
    ): String {
        val now = clock.instant()
        val pending = pendingStateRepository.beginAuthorization(
            state = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ByteArray(STATE_BYTES).also(secureRandom::nextBytes)),
            userId = userId,
            // Retained schema audit column; the public contract shares credentials across skills.
            skillId = "",
            provider = provider,
            scopes = scopes.toList(),
            now = now,
            activeSince = now.minusSeconds(PENDING_STATE_TTL_SECONDS),
            expiresAt = now.plusSeconds(PENDING_STATE_TTL_SECONDS),
            reuseExisting = !force,
        )
        // The repository atomically reuses a covering link or widens and supersedes it.
        return providerClient.buildAuthorizeUrl(state = pending.state, scopes = pending.requestedScopes)
    }

    private fun requireAllowedApiUrl(providerClient: OAuthProviderClient, rawUrl: String) {
        val url = try {
            Url(rawUrl)
        } catch (_: Exception) {
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

    /** Provider-triggered callback, kept outside the tool-facing [SkillOAuthGateway] contract. */
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
        // A newer callback's generation wins even if this exchange completes later.
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

    /** Connection checks use actual expiry; only proactive refresh applies the safety margin. */
    private fun isCredentialUsable(credential: SkillOAuthCredential): Boolean =
        credential.expiresAt?.isAfter(clock.instant()) != false || credential.refreshTokenEncrypted != null

    private suspend fun ensureFreshAccessToken(
        credential: SkillOAuthCredential,
        providerClient: OAuthProviderClient,
    ): String {
        val expiresAt = credential.expiresAt
        val refreshTokenEncrypted = credential.refreshTokenEncrypted
        // Without a refresh token, continue using the access token until its actual expiry.
        val margin = if (refreshTokenEncrypted == null) 0L else EXPIRY_SAFETY_MARGIN_SECONDS
        if (expiresAt == null || expiresAt.isAfter(clock.instant().plusSeconds(margin))) {
            return crypto.decrypt(credential.accessTokenEncrypted)
        }
        val refreshToken = refreshTokenEncrypted
            ?: throw ReconnectRequiredException(
                "OAuth access token for '${credential.provider}' has expired and no refresh token is available."
            )
        val refreshed = try {
            providerClient.refresh(crypto.decrypt(refreshToken))
        } catch (e: OAuthProviderErrorException) {
            val reason = "OAuth refresh for '${credential.provider}' failed (${e.errorCode}): ${e.message}."
            if (e.errorCode != "invalid_grant") throw SkillOAuthException(reason)

            // Clear only a confirmed invalid grant, guarded by generation and revision.
            val stored = credentialRepository.upsert(credential.copy(refreshTokenEncrypted = null))
            if (stored == null) {
                // Another refresh or authorization won the race; retry with the stored winner.
                credentialRepository.find(credential.userId, credential.provider)?.let {
                    return ensureFreshAccessToken(it, providerClient)
                }
            }
            throw ReconnectRequiredException(reason)
        }
        val now = clock.instant()
        // A rejected stale write still leaves this call's freshly issued access token usable.
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

    private companion object {
        const val PENDING_STATE_TTL_SECONDS = 600L
        const val EXPIRY_SAFETY_MARGIN_SECONDS = 60L
        const val STATE_BYTES = 32
        val secureRandom = SecureRandom()
    }
}

internal sealed interface CallbackResult {
    data class Connected(val provider: String) : CallbackResult
    data object InvalidOrExpiredState : CallbackResult
    data class ExchangeFailed(val reason: String) : CallbackResult
}
