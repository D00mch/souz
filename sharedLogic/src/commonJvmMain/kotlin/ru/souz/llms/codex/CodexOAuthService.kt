package ru.souz.llms.codex

import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import ru.souz.db.SettingsProvider
import ru.souz.llms.restJsonMapper
import java.util.Base64
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

sealed interface CodexOAuthState {
    object Idle : CodexOAuthState
    data class AwaitingUserCode(val userCode: String) : CodexOAuthState
    object Polling : CodexOAuthState
    data class Success(val accountId: String) : CodexOAuthState
    data class Error(val message: String) : CodexOAuthState
}

class CodexOAuthService(
    private val settingsProvider: SettingsProvider,
    private val client: HttpClient,
) {

    private val l = LoggerFactory.getLogger(CodexOAuthService::class.java)

    private val _oauthState = MutableStateFlow<CodexOAuthState>(CodexOAuthState.Idle)
    val oauthState: StateFlow<CodexOAuthState> = _oauthState

    private val refreshMutex = Mutex()
    private var flowJob: Job? = null

    suspend fun startDeviceFlow() {
        _oauthState.value = CodexOAuthState.Idle
        try {
            // Step 1: request user code
            val userCodeResponse = client.post(USERCODE_URL) {
                timeout { requestTimeoutMillis = OAUTH_REQUEST_TIMEOUT_MILLIS }
                contentType(ContentType.Application.Json)
                setBody(mapOf("client_id" to CLIENT_ID))
            }
            if (!userCodeResponse.status.isSuccess()) {
                val body = userCodeResponse.bodyAsText()
                _oauthState.value = CodexOAuthState.Error("Failed to start device flow: ${userCodeResponse.status} $body")
                return
            }
            val userCodeBody = userCodeResponse.bodyAsText()
            val userCodeData = restJsonMapper.readValue<Map<String, Any>>(userCodeBody)
            val deviceAuthId = userCodeData["device_auth_id"] as? String
                ?: run { _oauthState.value = CodexOAuthState.Error("Missing device_auth_id"); return }
            val userCode = userCodeData["user_code"] as? String
                ?: run { _oauthState.value = CodexOAuthState.Error("Missing user_code"); return }
            val intervalRaw = userCodeData["interval"]
            val intervalSeconds = when (intervalRaw) {
                is Number -> intervalRaw.toLong()
                is String -> intervalRaw.toLongOrNull() ?: 5L
                else -> 5L
            }
            _oauthState.value = CodexOAuthState.AwaitingUserCode(userCode = userCode)

            // Step 2 & 3: poll until authorized
            delay(intervalSeconds.seconds)
            var attempts = 0
            while (attempts < MAX_POLL_ATTEMPTS) {
                attempts++
                val pollResponse = client.post(TOKEN_POLL_URL) {
                    timeout { requestTimeoutMillis = OAUTH_REQUEST_TIMEOUT_MILLIS }
                    contentType(ContentType.Application.Json)
                    setBody(mapOf("device_auth_id" to deviceAuthId, "user_code" to userCode))
                }
                if (pollResponse.status.isSuccess()) {
                    val pollBody = pollResponse.bodyAsText()
                    val pollData = restJsonMapper.readValue<Map<String, Any>>(pollBody)
                    val authCode = pollData["authorization_code"] as? String
                        ?: run { _oauthState.value = CodexOAuthState.Error("Missing authorization_code"); return }
                    val codeVerifier = pollData["code_verifier"] as? String
                        ?: run { _oauthState.value = CodexOAuthState.Error("Missing code_verifier"); return }
                    exchangeCodeForTokens(authCode, codeVerifier)
                    return
                }
                // Keep AwaitingUserCode state so the code stays visible while polling
                delay(intervalSeconds.seconds)
            }
            _oauthState.value = CodexOAuthState.Error("Timed out waiting for authorization")
        } catch (e: CancellationException) {
            _oauthState.value = CodexOAuthState.Idle
            throw e
        } catch (e: Exception) {
            l.error("Codex OAuth flow error", e)
            _oauthState.value = CodexOAuthState.Error(e.message ?: "Unknown error")
        }
    }

    fun cancelFlow() {
        flowJob?.cancel()
        flowJob = null
        _oauthState.value = CodexOAuthState.Idle
    }

    fun launchFlow(scope: kotlinx.coroutines.CoroutineScope) {
        flowJob?.cancel()
        flowJob = scope.launch { startDeviceFlow() }
    }

    /** Returns a valid access token, refreshing if needed. Thread-safe. */
    suspend fun refreshTokenIfNeeded(): String = refreshMutex.withLock {
        val expiresAt = settingsProvider.codexExpiresAt
        val accessToken = settingsProvider.codexAccessToken
        if (accessToken.isNullOrBlank()) error("Codex: not authenticated")
        val needsRefresh = expiresAt != null &&
            System.currentTimeMillis() / 1000 >= expiresAt - REFRESH_BUFFER_SECONDS
        if (needsRefresh) refreshToken() else accessToken
    }

    private suspend fun refreshToken(): String {
        val refreshToken = settingsProvider.codexRefreshToken
            ?: error("Codex: refresh token is missing")
        val body = "grant_type=refresh_token&refresh_token=${refreshToken.urlEncode()}" +
            "&client_id=${CLIENT_ID.urlEncode()}&scope=openid%20profile%20email"
        val response = try {
            client.post(OAUTH_TOKEN_URL) {
                timeout { requestTimeoutMillis = OAUTH_REQUEST_TIMEOUT_MILLIS }
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Transient (network/timeout): stored credentials stay intact, next call retries.
            throw IllegalStateException("Codex: token refresh failed", e)
        }
        val text = response.bodyAsText()
        if (response.status.isSuccess()) {
            return parseAndStoreTokens(text, fresh = false) ?: error("Codex: token refresh failed")
        }
        // A reused/expired refresh token is terminal: clear the stored credential (null writes a
        // tombstone) so it reads as disconnected everywhere instead of retrying forever.
        if ("refresh_token_reused" in text || "invalid_grant" in text) {
            settingsProvider.codexAccessToken = null
            settingsProvider.codexRefreshToken = null
            settingsProvider.codexAccountId = null
            settingsProvider.codexExpiresAt = null
            _oauthState.value = CodexOAuthState.Error("Codex: re-authentication required")
        }
        error("Codex: token refresh failed: ${response.status} $text")
    }

    private suspend fun exchangeCodeForTokens(authorizationCode: String, codeVerifier: String) {
        val body = buildString {
            append("grant_type=authorization_code")
            append("&code=${authorizationCode.urlEncode()}")
            append("&redirect_uri=${REDIRECT_URI.urlEncode()}")
            append("&client_id=${CLIENT_ID.urlEncode()}")
            append("&code_verifier=${codeVerifier.urlEncode()}")
        }
        val response = client.post(OAUTH_TOKEN_URL) {
            timeout { requestTimeoutMillis = OAUTH_REQUEST_TIMEOUT_MILLIS }
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            val text = response.bodyAsText()
            _oauthState.value = CodexOAuthState.Error("Token exchange failed: ${response.status} $text")
            return
        }
        if (parseAndStoreTokens(response.bodyAsText(), fresh = true) == null) {
            _oauthState.value = CodexOAuthState.Error("Token exchange failed: could not read account from token")
            return
        }
        _oauthState.value = CodexOAuthState.Success(accountId = settingsProvider.codexAccountId ?: "")
    }

    /**
     * Parses a token response, persists it, returns the access token. A [fresh] device-flow
     * exchange fails when the account id can't be extracted; a refresh keeps the known one.
     */
    private fun parseAndStoreTokens(responseBody: String, fresh: Boolean): String? {
        val data = runCatching { restJsonMapper.readValue<Map<String, Any>>(responseBody) }.getOrNull()
            ?: return null
        val accessToken = data["access_token"] as? String ?: return null
        // A refresh response MAY omit refresh_token (RFC 6749 §6) — keep the current one then.
        val rotatedRefreshToken = (data["refresh_token"] as? String)?.takeIf { it.isNotBlank() }
        val expiresIn = when (val v = data["expires_in"]) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: 3600L
            else -> 3600L
        }
        val accountId = extractAccountId(accessToken)
        if (fresh && accountId == null) return null
        // The provider rotates and immediately invalidates the old refresh token, so persist the
        // rotated one FIRST: if a later write (or the process) dies, we're left holding a usable
        // refresh token rather than a fresh access token paired with an already-burned one.
        if (rotatedRefreshToken != null && rotatedRefreshToken != settingsProvider.codexRefreshToken) {
            settingsProvider.codexRefreshToken = rotatedRefreshToken
            l.info("Codex: refresh token rotated and persisted")
        }
        settingsProvider.codexAccessToken = accessToken
        // On a fresh login accountId is non-null here; on a refresh, keep the stored one on a miss.
        if (accountId != null) settingsProvider.codexAccountId = accountId
        settingsProvider.codexExpiresAt = System.currentTimeMillis() / 1000 + expiresIn
        return accessToken
    }

    private fun extractAccountId(jwt: String): String? {
        return try {
            val payload = jwt.split(".").getOrNull(1) ?: return null
            val decoded = Base64.getUrlDecoder().decode(payload.padEnd((payload.length + 3) / 4 * 4, '='))
            val claims = restJsonMapper.readValue<Map<String, Any>>(decoded)
            claims["chatgpt_account_id"] as? String
                ?: (claims["https://api.openai.com/auth"] as? Map<*, *>)
                    ?.get("chatgpt_account_id") as? String
                ?: claims["https://api.openai.com/auth.chatgpt_account_id"] as? String
        } catch (e: Exception) {
            l.warn("Codex: could not extract account_id from JWT", e)
            null
        }
    }

    private fun String.urlEncode(): String =
        java.net.URLEncoder.encode(this, "UTF-8")

    companion object {
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        private const val ISSUER = "https://auth.openai.com"
        private const val USERCODE_URL = "$ISSUER/api/accounts/deviceauth/usercode"
        private const val TOKEN_POLL_URL = "$ISSUER/api/accounts/deviceauth/token"
        private const val OAUTH_TOKEN_URL = "$ISSUER/oauth/token"
        private const val REDIRECT_URI = "$ISSUER/deviceauth/callback"
        private const val VERIFY_URL = "https://auth.openai.com/codex/device"
        private const val MAX_POLL_ATTEMPTS = 60
        private const val REFRESH_BUFFER_SECONDS = 300L
        private const val OAUTH_REQUEST_TIMEOUT_MILLIS = 30_000L
    }
}
