package agent

import io.ktor.client.HttpClient
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import ru.souz.db.SettingsProvider
import ru.souz.llms.codex.CodexOAuthService
import ru.souz.llms.restJsonMapper
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScenarioCodexAuthTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `cached credentials are used without refreshing or changing underlying settings or login`() = runTest {
        val token = jwt(Long.MAX_VALUE)
        val source = login(token)
        val authFile = directory.resolve("auth.json").apply { writeText(source) }
        val baseSettings = mockk<SettingsProvider>()
        val client = mockk<HttpClient>()

        val settings = scenarioCodexSettings(baseSettings, authFile, now)
        assertEquals(token, settings.codexAccessToken)
        assertEquals("fixture-account", settings.codexAccountId)
        assertNull(settings.codexRefreshToken)
        assertEquals(Long.MAX_VALUE, settings.codexExpiresAt)

        val oauth = CodexOAuthService(settings, client)
        repeat(2) { assertEquals(token, oauth.refreshTokenIfNeeded()) }
        settings.codexAccessToken = null
        settings.codexAccountId = null
        settings.codexRefreshToken = "in-memory-only"
        settings.codexExpiresAt = now.epochSecond

        assertEquals(source, authFile.readText())
        verify { baseSettings wasNot Called }
        verify { client wasNot Called }
    }

    @Test
    fun `credentials that expire after loading cannot be reused or refreshed`() = runTest {
        val token = jwt(Long.MAX_VALUE)
        val source = login(token)
        val authFile = writeLogin(source)
        val baseSettings = mockk<SettingsProvider>()
        val client = mockk<HttpClient>()
        val settings = scenarioCodexSettings(baseSettings, authFile, now)
        val oauth = CodexOAuthService(settings, client)

        assertEquals(token, oauth.refreshTokenIfNeeded())
        settings.codexExpiresAt = 0L
        repeat(2) {
            val failure = assertFailsWith<IllegalStateException> { oauth.refreshTokenIfNeeded() }
            assertEquals("Codex: refresh token is missing", failure.message)
            assertSafeFailure(failure, source, token, "private-refresh-fixture")
        }

        assertEquals(source, authFile.readText())
        verify { baseSettings wasNot Called }
        verify { client wasNot Called }
    }

    @Test
    fun `malformed login diagnostics omit the source and parser exception`() {
        val source = """{"tokens":{"access_token":"sensitive-json-fixture","refresh_token":"private-refresh-fixture"""
        val failure = assertFailsWith<IllegalStateException> {
            scenarioCodexSettings(mockk(), writeLogin(source), now)
        }
        assertTrue(failure.message.orEmpty().contains("Cannot read Codex login"))
        assertSafeFailure(failure, source, "sensitive-json-fixture", "private-refresh-fixture")
    }

    @Test
    fun `malformed token diagnostics omit the token and decoded payload`() {
        val payload = "sensitive-jwt-fixture"
        for (token in listOf(payload, "header.${encode(payload)}.signature")) {
            val source = login(token)
            val failure = assertFailsWith<IllegalStateException> {
                scenarioCodexSettings(mockk(), writeLogin(source), now)
            }
            assertTrue(failure.message.orEmpty().contains("invalid access-token expiry"))
            assertSafeFailure(failure, source, token, payload)
        }
    }

    @Test
    fun `expired and soon expiring access tokens are rejected without exposing credentials`() {
        for (expiry in listOf(now.epochSecond - 1, now.epochSecond + 300)) {
            val token = jwt(expiry)
            val source = login(token)
            val failure = assertFailsWith<IllegalArgumentException> {
                scenarioCodexSettings(mockk(), writeLogin(source), now)
            }
            assertTrue(failure.message.orEmpty().contains("expired or expires soon"))
            assertSafeFailure(failure, source, token)
        }
    }

    private fun writeLogin(source: String): Path = directory.resolve("auth.json").apply { writeText(source) }

    private fun assertSafeFailure(failure: Throwable, vararg sensitive: String) {
        val diagnostic = failure.stackTraceToString()
        sensitive.forEach { assertFalse(diagnostic.contains(it), "Diagnostic contains fixture credentials") }
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }

    private fun login(token: String): String = restJsonMapper.writeValueAsString(mapOf(
        "tokens" to mapOf("access_token" to token, "account_id" to "fixture-account", "refresh_token" to "private-refresh-fixture"),
    ))

    private fun jwt(expiry: Long): String = "${encode("""{"alg":"none"}""")}.${encode("""{"exp":$expiry}""")}.fixture"

    private fun encode(text: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

    private val now = Instant.parse("2026-06-01T10:00:00Z")
}
