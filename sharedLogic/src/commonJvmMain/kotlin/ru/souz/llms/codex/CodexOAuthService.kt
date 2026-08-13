package ru.souz.llms.codex

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

sealed interface CodexOAuthState {
    object Idle : CodexOAuthState
    data class AwaitingUserCode(val userCode: String) : CodexOAuthState
    object Polling : CodexOAuthState
    data class Success(val accountId: String) : CodexOAuthState
    data class Error(val message: String) : CodexOAuthState
}

class CodexOAuthService internal constructor(
    private val credentialStore: CodexOAuthCredentialStore,
    private val refreshCredentials: (suspend (CodexOAuthCredentials) -> CodexOAuthCredentials?)?,
    private val nowEpochSeconds: () -> Long,
    private val allowAccessTokenWithoutRefresh: Boolean = false,
    httpClient: HttpClient? = null,
) {
    constructor(credentialStore: CodexOAuthCredentialStore) : this(
        credentialStore = credentialStore,
        refreshCredentials = null,
        nowEpochSeconds = { System.currentTimeMillis() / 1000 },
        allowAccessTokenWithoutRefresh = false,
    )

    /** Backend constructor for an application-owned provider HTTP client. */
    constructor(
        credentialStore: CodexOAuthCredentialStore,
        httpClient: HttpClient,
    ) : this(
        credentialStore = credentialStore,
        refreshCredentials = null,
        nowEpochSeconds = { System.currentTimeMillis() / 1000 },
        allowAccessTokenWithoutRefresh = false,
        httpClient = httpClient,
    )

    constructor(settingsProvider: SettingsProvider) : this(
        credentialStore = SettingsProviderCodexOAuthCredentialStore(settingsProvider),
        refreshCredentials = null,
        nowEpochSeconds = { System.currentTimeMillis() / 1000 },
        allowAccessTokenWithoutRefresh = true,
    )

    private val l = LoggerFactory.getLogger(CodexOAuthService::class.java)
    private val client = httpClient ?: createHttpClient()

    private val _oauthState = MutableStateFlow<CodexOAuthState>(CodexOAuthState.Idle)
    val oauthState: StateFlow<CodexOAuthState> = _oauthState

    private val refreshMutex = Mutex()
    private var flowJob: Job? = null

    private fun createHttpClient(): HttpClient = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
        install(ContentNegotiation) {
            jackson { disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES) }
        }
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) = l.debug(message)
            }
            level = LogLevel.INFO
        }
    }

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

    /**
     * Refreshes rotating credentials before expiry. Backend stores fail closed when refresh cannot
     * produce a newer usable value; desktop may use a non-rotating access token.
     */
    suspend fun refreshTokenIfNeeded(): CodexOAuthCredentials = refreshMutex.withLock {
        val current = credentialStore.load() ?: error("Codex: not authenticated")
        if (!current.needsRefreshAt(nowEpochSeconds())) return@withLock current

        credentialStore.withRefreshLease(::refreshCredentialsWithLease)
    }

    private suspend fun refreshCredentialsWithLease(
        leasedStore: CodexOAuthCredentialStore,
    ): CodexOAuthCredentials {
        val current = leasedStore.load() ?: error("Codex: not authenticated")
        if (!current.needsRefreshAt(nowEpochSeconds())) return current
        if (current.refreshToken == null) {
            if (allowAccessTokenWithoutRefresh) return current
            error("Codex: expired credentials have no refresh token")
        }

        val refreshed = if (refreshCredentials != null) {
            refreshCredentials.invoke(current)
        } else {
            requestRefreshedCredentials(current)
        }
        if (refreshed != null && leasedStore.compareAndSet(current.version, refreshed)) {
            return refreshed
        }

        val winner = leasedStore.load()
        if (
            winner != null &&
            winner.version > current.version &&
            winner.isUsableAt(nowEpochSeconds())
        ) {
            return winner
        }
        if (allowAccessTokenWithoutRefresh) return current
        error("Codex: token refresh failed without a newer valid credential")
    }

    private suspend fun requestRefreshedCredentials(
        current: CodexOAuthCredentials,
    ): CodexOAuthCredentials? {
        val refreshToken = current.refreshToken ?: return null
        try {
            val body = buildString {
                append("grant_type=refresh_token")
                append("&refresh_token=${refreshToken.urlEncode()}")
                append("&client_id=${CLIENT_ID.urlEncode()}")
                append("&scope=openid%20profile%20email")
            }
            val response = client.post(OAUTH_TOKEN_URL) {
                timeout { requestTimeoutMillis = OAUTH_REQUEST_TIMEOUT_MILLIS }
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(body)
            }
            if (response.status.isSuccess()) {
                val refreshed = parseCredentials(
                    responseBody = response.bodyAsText(),
                    previous = current,
                ) ?: return null
                return refreshed
            } else {
                l.warn("Codex: token refresh failed: ${response.status} ${response.bodyAsText()}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            l.warn("Codex: token refresh error", e)
        }
        return null
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
        val current = credentialStore.load()
        val credentials = parseCredentials(response.bodyAsText(), current)
        if (credentials == null) {
            _oauthState.value = CodexOAuthState.Error("Token exchange returned incomplete credentials")
            return
        }
        val stored = credentialStore.compareAndSet(current?.version, credentials)
        if (!stored) {
            _oauthState.value = CodexOAuthState.Error("Codex credentials changed during token exchange")
            return
        }
        _oauthState.value = CodexOAuthState.Success(accountId = credentials.accountId.orEmpty())
    }

    private fun parseCredentials(
        responseBody: String,
        previous: CodexOAuthCredentials?,
    ): CodexOAuthCredentials? {
        val data = runCatching { restJsonMapper.readValue<Map<String, Any>>(responseBody) }.getOrNull()
            ?: return null
        val accessToken = data["access_token"] as? String ?: return null
        val refreshToken = (data["refresh_token"] as? String)
            ?.takeIf(String::isNotBlank)
            ?: previous?.refreshToken
        val expiresIn = when (val v = data["expires_in"]) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: 3600L
            else -> 3600L
        }
        val accountId = extractAccountId(accessToken)
            ?: previous?.accountId
        return CodexOAuthCredentials(
            accessToken = accessToken,
            refreshToken = refreshToken,
            accountId = accountId,
            expiresAtEpochSeconds = nowEpochSeconds() + expiresIn,
            version = (previous?.version ?: -1L) + 1L,
        )
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

    private fun CodexOAuthCredentials.isUsableAt(epochSeconds: Long): Boolean {
        val expiresAt = expiresAtEpochSeconds ?: return true
        return epochSeconds < expiresAt - REFRESH_BUFFER_SECONDS
    }

    private fun CodexOAuthCredentials.needsRefreshAt(epochSeconds: Long): Boolean {
        val expiresAt = expiresAtEpochSeconds ?: return false
        return epochSeconds >= expiresAt - REFRESH_BUFFER_SECONDS
    }

    companion object {
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        private const val ISSUER = "https://auth.openai.com"
        private const val USERCODE_URL = "$ISSUER/api/accounts/deviceauth/usercode"
        private const val TOKEN_POLL_URL = "$ISSUER/api/accounts/deviceauth/token"
        private const val OAUTH_TOKEN_URL = "$ISSUER/oauth/token"
        private const val REDIRECT_URI = "$ISSUER/deviceauth/callback"
        private const val VERIFY_URL = "https://auth.openai.com/codex/device"
        private const val OAUTH_REQUEST_TIMEOUT_MILLIS = 30_000L
        private const val MAX_POLL_ATTEMPTS = 60
        private const val REFRESH_BUFFER_SECONDS = 300L
    }
}
