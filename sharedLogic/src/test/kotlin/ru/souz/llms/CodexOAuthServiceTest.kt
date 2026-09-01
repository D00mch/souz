package ru.souz.llms

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import ru.souz.db.SettingsProvider
import ru.souz.llms.codex.CodexOAuthService
import ru.souz.llms.http.providerHttpClientDefaults
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CodexOAuthServiceTest {

    private val client = HttpClient(
        MockEngine { request ->
            val body = queued ?: error("no queued token response")
            queued = null
            respond(
                content = body.second,
                status = body.first,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        },
    ) { providerHttpClientDefaults() }

    private var queued: Pair<HttpStatusCode, String>? = null

    @AfterTest
    fun tearDown() = client.close()

    @Test
    fun `refresh rotates the refresh token and keeps account id on a jwt parse miss`() = runTest {
        val settings = FakeCodexSettings().apply {
            codexAccessToken = "old-access"
            codexRefreshToken = "old-refresh"
            codexAccountId = "account-1"
            codexExpiresAt = nowSeconds() - 10
        }
        queued = HttpStatusCode.OK to
            """{"access_token":"new-access-not-a-jwt","refresh_token":"new-refresh","expires_in":3600}"""

        val token = CodexOAuthService(settings, client).refreshTokenIfNeeded()

        assertEquals("new-access-not-a-jwt", token)
        assertEquals("new-refresh", settings.codexRefreshToken)
        assertEquals("account-1", settings.codexAccountId)
        assertEquals(true, (settings.codexExpiresAt ?: 0) > nowSeconds())
    }

    @Test
    fun `a reused refresh token disconnects every credential field`() = runTest {
        val settings = FakeCodexSettings().apply {
            codexAccessToken = "old-access"
            codexRefreshToken = "burned-refresh"
            codexAccountId = "account-1"
            codexExpiresAt = nowSeconds() - 10
        }
        queued = HttpStatusCode.BadRequest to
            """{"error":"invalid_grant","error_description":"refresh_token_reused"}"""

        assertFailsWith<IllegalStateException> {
            CodexOAuthService(settings, client).refreshTokenIfNeeded()
        }

        assertNull(settings.codexAccessToken)
        assertNull(settings.codexRefreshToken)
        assertNull(settings.codexAccountId)
        assertNull(settings.codexExpiresAt)
    }

    private fun nowSeconds() = System.currentTimeMillis() / 1000

    private class FakeCodexSettings(
        delegate: SettingsProvider = mockk(relaxed = true),
    ) : SettingsProvider by delegate {
        override var codexAccessToken: String? = null
        override var codexRefreshToken: String? = null
        override var codexAccountId: String? = null
        override var codexExpiresAt: Long? = null
    }
}
