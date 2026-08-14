package ru.souz.llms.giga

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMResponse

/** App-scoped Giga token cache shared by chat and speech clients. */
class GigaAuth(
    private val settingsProvider: SettingsProvider,
    private val client: HttpClient,
) {
    private val logger = LoggerFactory.getLogger(GigaAuth::class.java)
    private val mutex = Mutex()
    private val tokensByScope = mutableMapOf<String, CachedToken>()

    suspend fun requestToken(apiKey: String, scope: String): String = mutex.withLock {
        tokensByScope[scope]
            ?.takeIf { cached ->
                cached.apiKey == apiKey &&
                    cached.token.expiresAt.time > System.currentTimeMillis() + EXPIRY_MARGIN_MILLIS
            }
            ?.token
            ?.accessToken
            ?: requestNewToken(apiKey, scope).also { token ->
                tokensByScope[scope] = CachedToken(apiKey = apiKey, token = token)
            }.accessToken
    }

    private suspend fun requestNewToken(apiKey: String, scope: String): LLMResponse.Token {
        val response = client.submitForm(
            url = TOKEN_URL,
            formParameters = Parameters.build { append("scope", scope) },
        ) {
            header(HttpHeaders.Accept, "application/json")
            header(HttpHeaders.UserAgent, "Souz")
            header("RqUID", UUID.randomUUID().toString())
            header(HttpHeaders.Authorization, "Basic $apiKey")
            timeout { requestTimeoutMillis = settingsProvider.requestTimeoutMillis }
        }
        if (!response.status.isSuccess()) {
            logger.error("Error in requestToken: {}", response.status)
            error("Error in requestToken: ${response.status}")
        }
        return response.body()
    }

    private data class CachedToken(
        val apiKey: String,
        val token: LLMResponse.Token,
    )

    private companion object {
        const val TOKEN_URL = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth"
        const val EXPIRY_MARGIN_MILLIS = 30_000L
    }
}
