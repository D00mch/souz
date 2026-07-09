package ru.souz.llms.codex

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.jackson.jackson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    private val client: HttpClient = defaultHttpClient(),
    private val pollDelay: suspend (Long) -> Unit = { delay(it.seconds) },
    private val maxPollAttempts: Int = MAX_POLL_ATTEMPTS,
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
            pollDelay(intervalSeconds)
            var attempts = 0
            while (attempts < maxPollAttempts) {
                attempts++
                val pollBody = try {
                    val pollResponse = client.post(TOKEN_POLL_URL) {
                        contentType(ContentType.Application.Json)
                        setBody(mapOf("device_auth_id" to deviceAuthId, "user_code" to userCode))
                    }
                    if (pollResponse.status.isSuccess()) {
                        pollResponse.bodyAsText()
                    } else {
                        null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    l.warn("Codex OAuth poll attempt $attempts failed", e)
                    if (attempts < maxPollAttempts) {
                        pollDelay(intervalSeconds)
                    }
                    continue
                }
                if (pollBody != null) {
                    val pollData = try {
                        restJsonMapper.readValue<Map<String, Any>>(pollBody)
                    } catch (e: Exception) {
                        l.warn("Codex OAuth malformed polling response", e)
                        _oauthState.value = CodexOAuthState.Error("Malformed authorization polling response")
                        return
                    }
                    val authCode = pollData["authorization_code"] as? String
                        ?: run { _oauthState.value = CodexOAuthState.Error("Missing authorization_code"); return }
                    val codeVerifier = pollData["code_verifier"] as? String
                        ?: run { _oauthState.value = CodexOAuthState.Error("Missing code_verifier"); return }
                    exchangeCodeForTokens(authCode, codeVerifier)
                    return
                }
                // Keep AwaitingUserCode state so the code stays visible while polling
                if (attempts < maxPollAttempts) {
                    pollDelay(intervalSeconds)
                }
            }
            _oauthState.value = CodexOAuthState.Error("Timed out waiting for authorization")
        } catch (e: CancellationException) {
            _oauthState.value = CodexOAuthState.Idle
            throw e
        } catch (e: Exception) {
            l.error("Codex OAuth flow error", e)
            _oauthState.value = CodexOAuthState.Error(e.codexOAuthMessage())
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
        if (needsRefresh) {
            refreshToken()
        }
        settingsProvider.codexAccessToken ?: error("Codex: token missing after refresh")
    }

    private suspend fun refreshToken() {
        val refreshToken = settingsProvider.codexRefreshToken
            ?: run { l.warn("Codex: no refresh token, skipping refresh"); return }
        try {
            val body = buildString {
                append("grant_type=refresh_token")
                append("&refresh_token=${refreshToken.urlEncode()}")
                append("&client_id=${CLIENT_ID.urlEncode()}")
                append("&scope=openid%20profile%20email")
            }
            val response = client.post(OAUTH_TOKEN_URL) {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(body)
            }
            if (response.status.isSuccess()) {
                parseAndStoreTokens(response.bodyAsText())
            } else {
                l.warn("Codex: token refresh failed: ${response.status} ${response.bodyAsText()}")
            }
        } catch (e: Exception) {
            l.warn("Codex: token refresh error", e)
        }
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
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            val text = response.bodyAsText()
            _oauthState.value = CodexOAuthState.Error("Token exchange failed: ${response.status} $text")
            return
        }
        val tokens = parseAndStoreTokens(response.bodyAsText())
            ?: run {
                _oauthState.value = CodexOAuthState.Error("Malformed token exchange response")
                return
            }
        _oauthState.value = CodexOAuthState.Success(accountId = tokens.accountId ?: "")
    }

    /** Parses token response and persists it to SettingsProvider. */
    private fun parseAndStoreTokens(responseBody: String): StoredCodexTokens? {
        val data = runCatching { restJsonMapper.readValue<Map<String, Any>>(responseBody) }.getOrNull()
            ?: return null
        val accessToken = data["access_token"] as? String ?: return null
        val refreshToken = data["refresh_token"] as? String
        val expiresIn = when (val v = data["expires_in"]) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: 3600L
            else -> 3600L
        }
        val accountId = extractAccountId(accessToken)
        settingsProvider.codexAccessToken = accessToken
        settingsProvider.codexRefreshToken = refreshToken
        settingsProvider.codexAccountId = accountId
        settingsProvider.codexExpiresAt = System.currentTimeMillis() / 1000 + expiresIn
        return StoredCodexTokens(accountId = accountId)
    }

    private data class StoredCodexTokens(val accountId: String?)

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

    private fun Throwable.codexOAuthMessage(): String =
        message?.takeIf { it.isNotBlank() }
            ?: this::class.simpleName
            ?: "Unknown error"

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

        private fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
            install(HttpTimeout) { requestTimeoutMillis = 30_000 }
            install(ContentNegotiation) {
                jackson { disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES) }
            }
            install(Logging) {
                logger = object : Logger {
                    override fun log(message: String) = LoggerFactory
                        .getLogger(CodexOAuthService::class.java)
                        .debug(message)
                }
                level = LogLevel.INFO
            }
        }
    }
}
