package ru.souz.skilloauth.impl

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import ru.souz.skilloauth.ApiCallReconnectRequired
import ru.souz.skilloauth.ApiCallRequest
import ru.souz.skilloauth.ApiCallResponse
import ru.souz.skilloauth.SkillOAuthException

/**
 * Covers the parts of [SkillOAuthApiImpl] that do not require a live network call
 * (status/startAuthorization use no HTTP; `buildAuthorizeUrl` is a pure string builder).
 * Token-exchange/refresh paths (which call the real Yandex endpoints) are not covered
 * here — see the plan's noted gap around HTTP-mocked provider tests.
 */
class SkillOAuthApiImplTest {
    private val fixedClock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val testCryptoKey = java.util.Base64.getEncoder().encodeToString(ByteArray(32))
    private val testCrypto = SkillOAuthTokenCrypto(testCryptoKey)

    private fun newApi(
        credentialRepository: SkillOAuthCredentialRepository = InMemorySkillOAuthCredentialRepository(),
        pendingStateRepository: SkillOAuthPendingStateRepository = InMemorySkillOAuthPendingStateRepository(),
        httpClient: HttpClient = defaultSkillOAuthHttpClient(),
        providers: Map<String, OAuthProviderClient> = mapOf(
            "yandex" to AuthorizationCodeOAuthClient(
                config = AuthorizationCodeOAuthConfig(
                    name = "yandex",
                    authorizeEndpoint = "https://oauth.yandex.ru/authorize",
                    tokenEndpoint = "https://oauth.yandex.ru/token",
                    clientId = "client-1",
                    clientSecret = "secret-1",
                    redirectUri = "https://backend.example/oauth/callback",
                    allowedApiHosts = setOf("login.yandex.ru"),
                ),
            )
        ),
    ) = SkillOAuthApiImpl(
        credentialRepository = credentialRepository,
        pendingStateRepository = pendingStateRepository,
        crypto = SkillOAuthTokenCrypto(testCryptoKey),
        providers = providers,
        httpClient = httpClient,
        clock = fixedClock,
    )

    /** Lets [handleCallback]-driving tests control exactly what a code exchange/refresh returns,
     *  without a real HTTP call. */
    private class FakeOAuthProviderClient(
        override val name: String = "yandex",
        override val allowedApiHosts: Set<String> = setOf("login.yandex.ru"),
        private val exchangeResults: Map<String, OAuthTokenResult> = emptyMap(),
        private val refreshException: Throwable? = null,
    ) : OAuthProviderClient {
        override fun buildAuthorizeUrl(state: String, scopes: List<String>): String =
            "https://fake.example/authorize?state=$state&scope=${scopes.joinToString(" ")}"

        override suspend fun exchangeCode(code: String): OAuthTokenResult =
            exchangeResults[code] ?: throw SkillOAuthException("No fake exchange result for code '$code'.")

        override suspend fun refresh(refreshToken: String): OAuthTokenResult =
            throw refreshException ?: UnsupportedOperationException("Not used in this test.")
    }

    private suspend fun connectedCredentialRepository(
        grantedScopes: List<String> = listOf("login:info"),
    ): SkillOAuthCredentialRepository {
        val repository = InMemorySkillOAuthCredentialRepository()
        repository.upsert(
            SkillOAuthCredential(
                userId = "user-1",
                provider = "yandex",
                accessTokenEncrypted = testCrypto.encrypt("real-access-token"),
                refreshTokenEncrypted = null,
                grantedScopes = grantedScopes,
                expiresAt = null,
                generation = 1,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        )
        return repository
    }

    @Test
    fun `status reports not connected when no credential is stored`() = runTest {
        val api = newApi()

        val status = api.status(userId = "user-1", provider = "yandex")

        assertFalse(status.connected)
        assertTrue(status.grantedScopes.isEmpty())
    }

    @Test
    fun `status reports connected with granted scopes once a credential exists`() = runTest {
        val api = newApi(credentialRepository = connectedCredentialRepository(listOf("login:info")))

        val status = api.status(userId = "user-1", provider = "yandex")

        assertTrue(status.connected)
        assertEquals(listOf("login:info"), status.grantedScopes)
    }

    @Test
    fun `status reports not connected when granted scopes do not cover requiredScopes`() = runTest {
        // Regression test for a shared (userId, provider) credential accumulating scopes for one
        // skill that another, more narrowly-declared skill must not silently ride on.
        val api = newApi(credentialRepository = connectedCredentialRepository(listOf("login:info")))

        val status = api.status(userId = "user-1", provider = "yandex", requiredScopes = listOf("login:info", "iot:control"))

        assertFalse(status.connected)
        assertEquals(listOf("login:info"), status.grantedScopes)
        assertEquals(listOf("iot:control"), status.missingScopes)
    }

    @Test
    fun `status reports connected when requiredScopes are a subset of grantedScopes`() = runTest {
        val api = newApi(credentialRepository = connectedCredentialRepository(listOf("login:info", "iot:control", "iot:view")))

        val status = api.status(userId = "user-1", provider = "yandex", requiredScopes = listOf("iot:view"))

        assertTrue(status.connected)
        assertTrue(status.missingScopes.isEmpty())
    }

    @Test
    fun `startAuthorization stores a pending state and returns an authorize URL`() = runTest {
        val pendingStateRepository = InMemorySkillOAuthPendingStateRepository()
        val api = newApi(pendingStateRepository = pendingStateRepository)

        val result = api.startAuthorization(
            userId = "user-1",
            provider = "yandex",
            skillId = "skill-1",
            scopes = listOf("login:info"),
        )

        assertTrue(result.url.startsWith("https://oauth.yandex.ru/authorize?"))
        assertTrue(result.url.contains("client_id=client-1"))
        assertTrue(result.url.contains("scope=login%3Ainfo"))
    }

    @Test
    fun `startAuthorization supersedes an existing pending state for the same user and provider`() = runTest {
        // Regression test: without superseding, two overlapping ConnectOAuthProvider calls for the
        // same (userId, provider) would leave two live `state` tokens. The scope-union itself is
        // covered by beginAuthorization's own dedicated tests (Postgres repository test file); this
        // test just checks the old link is invalidated.
        val pendingStateRepository = InMemorySkillOAuthPendingStateRepository()
        val api = newApi(pendingStateRepository = pendingStateRepository)

        val first = api.startAuthorization(
            userId = "user-1",
            provider = "yandex",
            skillId = "skill-1",
            scopes = listOf("login:info"),
        )
        val second = api.startAuthorization(
            userId = "user-1",
            provider = "yandex",
            skillId = "skill-2",
            scopes = listOf("iot:control"),
        )

        val firstState = first.url.substringAfter("state=").substringBefore("&")
        assertTrue(pendingStateRepository.consume(firstState, fixedClock.instant()) == null)
        val secondScopeParam = second.url.substringAfter("scope=").substringBefore("&")
        assertEquals("login:info iot:control", java.net.URLDecoder.decode(secondScopeParam, "UTF-8"))
    }

    @Test
    fun `startAuthorization widens its request even after an earlier flow's pending state was already consumed`() = runTest {
        // The maintainer's own finding: superseding only helps while a prior flow is still
        // *pending*. Once a callback consumes its own pending state (the first thing
        // handleCallback does), a second, unrelated ConnectOAuthProvider call for the same
        // (userId, provider) has nothing left to supersede *in pending_states* — but the durable
        // requested-scope tracking survives past that, so this call still sees and widens on top
        // of the first one's request instead of only asking for its own scope.
        val pendingStateRepository = InMemorySkillOAuthPendingStateRepository()
        val api = newApi(pendingStateRepository = pendingStateRepository)

        val authA = api.startAuthorization("user-1", "yandex", "skill-1", listOf("login:info"))
        val stateA = authA.url.substringAfter("state=").substringBefore("&")
        // Simulates "callback A already consumed its own pending state, mid-exchange".
        pendingStateRepository.consume(stateA, fixedClock.instant())

        val authB = api.startAuthorization("user-1", "yandex", "skill-2", listOf("iot:control"))

        val scopeParam = authB.url.substringAfter("scope=").substringBefore("&")
        assertEquals(
            setOf("login:info", "iot:control"),
            java.net.URLDecoder.decode(scopeParam, "UTF-8").split(" ").toSet(),
        )
    }

    @Test
    fun `a stale callback cannot overwrite a fresher credential for the same user and provider`() = runTest {
        // Complements the widening test above: even if a stale callback's network exchange finishes
        // after a fresher, independent authorization has already saved its own credential, the stale
        // one's older generation must not clobber it.
        val credentialRepository = InMemorySkillOAuthCredentialRepository()
        credentialRepository.upsert(
            SkillOAuthCredential(
                userId = "user-1",
                provider = "yandex",
                accessTokenEncrypted = testCrypto.encrypt("fresh-token"),
                refreshTokenEncrypted = null,
                grantedScopes = listOf("login:info", "iot:control"),
                expiresAt = null,
                generation = 5,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        )
        val provider = FakeOAuthProviderClient(
            exchangeResults = mapOf(
                "stale-code" to OAuthTokenResult("stale-token", null, null, listOf("login:info")),
            ),
        )
        val pendingStateRepository = InMemorySkillOAuthPendingStateRepository()
        pendingStateRepository.beginAuthorization(
            state = "stale-state",
            userId = "user-1",
            skillId = "skill-1",
            provider = "yandex",
            scopes = listOf("login:info"),
            now = fixedClock.instant(),
            activeSince = fixedClock.instant().minusSeconds(600),
            expiresAt = fixedClock.instant().plusSeconds(600),
        )
        // Generation from beginAuthorization above is 1 — older than the already-saved
        // credential's generation=5, which is exactly the scenario under test.
        val api = newApi(
            credentialRepository = credentialRepository,
            pendingStateRepository = pendingStateRepository,
            providers = mapOf("yandex" to provider),
        )

        api.handleCallback(code = "stale-code", state = "stale-state")

        val stored = credentialRepository.find("user-1", "yandex")
        assertEquals("fresh-token", testCrypto.decrypt(stored!!.accessTokenEncrypted))
        assertEquals(setOf("login:info", "iot:control"), stored.grantedScopes.toSet())
    }

    @Test
    fun `status reports not connected when the access token has expired and there is no refresh token`() = runTest {
        // Regression test: a credential row existing must not be conflated with it being usable —
        // otherwise ConnectOAuthProvider would forever report "already connected" instead of ever
        // issuing a fresh authorize URL once the token is truly unrecoverable.
        val credentialRepository = InMemorySkillOAuthCredentialRepository()
        credentialRepository.upsert(
            SkillOAuthCredential(
                userId = "user-1",
                provider = "yandex",
                accessTokenEncrypted = testCrypto.encrypt("stale-token"),
                refreshTokenEncrypted = null,
                grantedScopes = listOf("login:info"),
                expiresAt = fixedClock.instant().minusSeconds(60),
                generation = 1,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        )
        val api = newApi(credentialRepository = credentialRepository)

        val status = api.status(userId = "user-1", provider = "yandex")

        assertFalse(status.connected)
    }

    @Test
    fun `status reports connected when the access token has expired but a refresh token is available`() = runTest {
        val credentialRepository = InMemorySkillOAuthCredentialRepository()
        credentialRepository.upsert(
            SkillOAuthCredential(
                userId = "user-1",
                provider = "yandex",
                accessTokenEncrypted = testCrypto.encrypt("stale-token"),
                refreshTokenEncrypted = testCrypto.encrypt("refresh-token"),
                grantedScopes = listOf("login:info"),
                expiresAt = fixedClock.instant().minusSeconds(60),
                generation = 1,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        )
        val api = newApi(credentialRepository = credentialRepository)

        val status = api.status(userId = "user-1", provider = "yandex")

        assertTrue(status.connected)
    }

    @Test
    fun `callAuthorizedApi returns reconnectRequired for an expired credential with no refresh token`() = runTest {
        // Needing to reconnect is a routine outcome, not a caller error — modeled as a normal
        // ApiCallOutcome (with a ready-to-relay authorizationUrl), not a thrown exception.
        val credentialRepository = InMemorySkillOAuthCredentialRepository()
        credentialRepository.upsert(
            SkillOAuthCredential(
                userId = "user-1",
                provider = "yandex",
                accessTokenEncrypted = testCrypto.encrypt("stale-token"),
                refreshTokenEncrypted = null,
                grantedScopes = listOf("login:info"),
                expiresAt = fixedClock.instant().minusSeconds(60),
                generation = 1,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        )
        val api = newApi(credentialRepository = credentialRepository)

        val outcome = api.callAuthorizedApi(
            userId = "user-1",
            provider = "yandex",
            skillId = "skill-1",
            requiredScopes = listOf("login:info"),
            request = ApiCallRequest(method = "GET", url = "https://login.yandex.ru/info"),
        )

        assertTrue(outcome is ApiCallReconnectRequired)
        assertTrue(outcome.authorizationUrl.startsWith("https://oauth.yandex.ru/authorize?"))
    }

    @Test
    fun `callAuthorizedApi still uses a not-yet-expired token with no refresh token, inside the refresh safety margin`() = runTest {
        // Regression test for a "dead zone": without a refresh token, the safety margin used to
        // make ensureFreshAccessToken throw up to EXPIRY_SAFETY_MARGIN_SECONDS before the token was
        // actually expired, while status()/isCredentialUsable (no margin) still reported connected
        // — a contradiction. The margin only makes sense when a refresh is actually possible.
        val mockEngine = MockEngine { respond(content = "{}", status = HttpStatusCode.OK) }
        val credentialRepository = InMemorySkillOAuthCredentialRepository()
        credentialRepository.upsert(
            SkillOAuthCredential(
                userId = "user-1",
                provider = "yandex",
                accessTokenEncrypted = testCrypto.encrypt("still-valid-token"),
                refreshTokenEncrypted = null,
                grantedScopes = listOf("login:info"),
                expiresAt = fixedClock.instant().plusSeconds(30), // inside the 60s margin, not yet expired
                generation = 1,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        )
        val api = newApi(credentialRepository = credentialRepository, httpClient = HttpClient(mockEngine))

        val outcome = api.callAuthorizedApi(
            userId = "user-1",
            provider = "yandex",
            skillId = "skill-1",
            requiredScopes = listOf("login:info"),
            request = ApiCallRequest(method = "GET", url = "https://login.yandex.ru/info"),
        )

        assertEquals(200, (outcome as ApiCallResponse).statusCode)
    }

    @Test
    fun `a confirmed invalid refresh token is cleared so status stops reporting connected`() = runTest {
        // D00mch: a stored refresh token is not necessarily a *usable* one (corrupted/revoked). On
        // a confirmed OAuth error from the provider (not a transient network failure — see
        // ensureFreshAccessToken's catch), the refresh token must be cleared so a future status()
        // check reports "not connected" instead of retrying the same broken token forever.
        val credentialRepository = InMemorySkillOAuthCredentialRepository()
        credentialRepository.upsert(
            SkillOAuthCredential(
                userId = "user-1",
                provider = "yandex",
                accessTokenEncrypted = testCrypto.encrypt("expired-token"),
                refreshTokenEncrypted = testCrypto.encrypt("revoked-refresh-token"),
                grantedScopes = listOf("login:info"),
                expiresAt = fixedClock.instant().minusSeconds(60),
                generation = 1,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        )
        val provider = FakeOAuthProviderClient(
            refreshException = OAuthProviderErrorException(errorCode = "invalid_grant", message = "Token is revoked."),
        )
        val api = newApi(credentialRepository = credentialRepository, providers = mapOf("yandex" to provider))

        val outcome = api.callAuthorizedApi(
            userId = "user-1",
            provider = "yandex",
            skillId = "skill-1",
            requiredScopes = listOf("login:info"),
            request = ApiCallRequest(method = "GET", url = "https://login.yandex.ru/info"),
        )

        assertTrue(outcome is ApiCallReconnectRequired)
        assertFalse(api.status("user-1", "yandex").connected)
    }

    @Test
    fun `a non-invalid_grant refresh error does not clear the refresh token and does not report reconnectRequired`() = runTest {
        // D00mch: toTokenResult() throws the same exception type for every OAuth error code —
        // invalid_client, invalid_scope, server_error, etc. are not evidence the refresh token
        // itself is dead, and clearing it over one of those forces a needless full reconnect that
        // wouldn't have been necessary once the transient issue (or our own config) is fixed.
        // Reporting ApiCallReconnectRequired would be equally wrong here — a fresh authorize link
        // doesn't fix a provider outage or our own client misconfiguration — so this must instead
        // propagate as a plain SkillOAuthException, distinct from ReconnectRequiredException.
        val credentialRepository = InMemorySkillOAuthCredentialRepository()
        credentialRepository.upsert(
            SkillOAuthCredential(
                userId = "user-1",
                provider = "yandex",
                accessTokenEncrypted = testCrypto.encrypt("expired-token"),
                refreshTokenEncrypted = testCrypto.encrypt("still-good-refresh-token"),
                grantedScopes = listOf("login:info"),
                expiresAt = fixedClock.instant().minusSeconds(60),
                generation = 1,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        )
        val provider = FakeOAuthProviderClient(
            refreshException = OAuthProviderErrorException(errorCode = "server_error", message = "temporary provider outage"),
        )
        val api = newApi(credentialRepository = credentialRepository, providers = mapOf("yandex" to provider))

        val thrown = assertFailsWith<SkillOAuthException> {
            api.callAuthorizedApi(
                userId = "user-1",
                provider = "yandex",
                skillId = "skill-1",
                requiredScopes = listOf("login:info"),
                request = ApiCallRequest(method = "GET", url = "https://login.yandex.ru/info"),
            )
        }

        assertFalse(thrown is ReconnectRequiredException)
        assertEquals(
            "still-good-refresh-token",
            testCrypto.decrypt(credentialRepository.find("user-1", "yandex")!!.refreshTokenEncrypted!!),
        )
    }

    @Test
    fun `reconnect preserves scopes already granted for a different skill, not just the failing skill's own requiredScopes`() = runTest {
        // D00mch: startAuthorization was called with only the failing skill's requiredScopes,
        // ignoring what the shared credential already grants. Once the durable requested-scope
        // tracking's staleness window passes, that widening can't recover the old grant either —
        // so an unrelated skill's perfectly fine access silently disappears the moment this
        // callback saves a scope-narrower token.
        val api = newApi(credentialRepository = connectedCredentialRepository(listOf("other-skill:scope")))

        val outcome = api.callAuthorizedApi(
            userId = "user-1",
            provider = "yandex",
            skillId = "skill-1",
            requiredScopes = listOf("this-skill:scope"),
            request = ApiCallRequest(method = "GET", url = "https://login.yandex.ru/info"),
        )

        assertTrue(outcome is ApiCallReconnectRequired)
        val scopeParam = outcome.authorizationUrl
            .substringAfter("scope=").substringBefore("&")
        assertEquals(
            setOf("other-skill:scope", "this-skill:scope"),
            java.net.URLDecoder.decode(scopeParam, "UTF-8").split(" ").toSet(),
        )
    }

    @Test
    fun `callAuthorizedApi returns reconnectRequired for a skill that is not connected yet`() = runTest {
        val api = newApi()

        val outcome = api.callAuthorizedApi(
            userId = "user-1",
            provider = "yandex",
            skillId = "skill-1",
            requiredScopes = emptyList(),
            request = ApiCallRequest(method = "GET", url = "https://login.yandex.ru/info"),
        )

        assertTrue(outcome is ApiCallReconnectRequired)
    }

    @Test
    fun `callAuthorizedApi returns reconnectRequired for a connected skill whose requiredScopes exceed grantedScopes`() = runTest {
        val api = newApi(credentialRepository = connectedCredentialRepository(listOf("login:info")))

        val outcome = api.callAuthorizedApi(
            userId = "user-1",
            provider = "yandex",
            skillId = "skill-1",
            requiredScopes = listOf("login:info", "iot:control"),
            request = ApiCallRequest(method = "GET", url = "https://login.yandex.ru/info"),
        )

        assertTrue(outcome is ApiCallReconnectRequired)
        // widened to include what's already granted, not just the missing scope.
        assertTrue(outcome.authorizationUrl.contains("iot%3Acontrol"))
    }

    @Test
    fun `callAuthorizedApi rejects a URL host outside the provider's allowlist`() = runTest {
        // Regression test: without this check, a hijacked model turn could redirect the real
        // bearer token to an attacker-controlled or internal host via the model-supplied `url`.
        val api = newApi(credentialRepository = connectedCredentialRepository(listOf("login:info")))

        assertFailsWith<SkillOAuthException> {
            api.callAuthorizedApi(
                userId = "user-1",
                provider = "yandex",
                skillId = "skill-1",
                requiredScopes = listOf("login:info"),
                request = ApiCallRequest(method = "GET", url = "https://attacker.example/exfil"),
            )
        }
    }

    @Test
    fun `callAuthorizedApi rejects a non-HTTPS URL even on an allowed host`() = runTest {
        val api = newApi(credentialRepository = connectedCredentialRepository(listOf("login:info")))

        assertFailsWith<SkillOAuthException> {
            api.callAuthorizedApi(
                userId = "user-1",
                provider = "yandex",
                skillId = "skill-1",
                requiredScopes = listOf("login:info"),
                request = ApiCallRequest(method = "GET", url = "http://login.yandex.ru/info"),
            )
        }
    }

    @Test
    fun `callAuthorizedApi forwards caller headers and response headers`() = runTest {
        var capturedHeaders: io.ktor.http.Headers? = null
        val mockEngine = MockEngine { request ->
            capturedHeaders = request.headers
            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = headersOf("X-Provider-Header", listOf("provider-value")),
            )
        }
        val api = newApi(
            credentialRepository = connectedCredentialRepository(listOf("login:info")),
            httpClient = HttpClient(mockEngine),
        )

        val outcome = api.callAuthorizedApi(
            userId = "user-1",
            provider = "yandex",
            skillId = "skill-1",
            requiredScopes = listOf("login:info"),
            request = ApiCallRequest(
                method = "GET",
                url = "https://login.yandex.ru/info",
                headers = mapOf("X-Custom" to "custom-value"),
            ),
        )

        assertEquals("custom-value", capturedHeaders?.get("X-Custom"))
        assertEquals("provider-value", (outcome as ApiCallResponse).headers["X-Provider-Header"])
    }

    @Test
    fun `callAuthorizedApi does not override a caller-supplied Content-Type`() = runTest {
        // Regression test: defaulting to JSON unconditionally would silently override (or
        // duplicate) a provider-required Content-Type like form-urlencoded or XML.
        var capturedContentType: String? = null
        val mockEngine = MockEngine { request ->
            capturedContentType = request.body.contentType?.toString()
            respond(content = "{}", status = HttpStatusCode.OK)
        }
        val api = newApi(
            credentialRepository = connectedCredentialRepository(listOf("login:info")),
            httpClient = HttpClient(mockEngine),
        )

        api.callAuthorizedApi(
            userId = "user-1",
            provider = "yandex",
            skillId = "skill-1",
            requiredScopes = listOf("login:info"),
            request = ApiCallRequest(
                method = "POST",
                url = "https://login.yandex.ru/info",
                body = "field=value",
                headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            ),
        )

        assertEquals("application/x-www-form-urlencoded", capturedContentType)
    }

    @Test
    fun `callAuthorizedApi never lets a caller-supplied Authorization header override the real access token`() = runTest {
        // Regression test: `headers` on ApiCallRequest is a model-supplied field; without this
        // protection a hijacked model turn could smuggle its own `Authorization` value and either
        // clobber the real bearer token in transit or (depending on server behavior) have both
        // sent, defeating the whole point of injecting the token server-side.
        var capturedAuthorization: String? = null
        val mockEngine = MockEngine { request ->
            capturedAuthorization = request.headers[HttpHeaders.Authorization]
            respond(content = "{}", status = HttpStatusCode.OK)
        }
        val api = newApi(
            credentialRepository = connectedCredentialRepository(listOf("login:info")),
            httpClient = HttpClient(mockEngine),
        )

        api.callAuthorizedApi(
            userId = "user-1",
            provider = "yandex",
            skillId = "skill-1",
            requiredScopes = listOf("login:info"),
            request = ApiCallRequest(
                method = "GET",
                url = "https://login.yandex.ru/info",
                headers = mapOf("Authorization" to "Bearer attacker-supplied-token"),
            ),
        )

        assertEquals("Bearer real-access-token", capturedAuthorization)
    }

    @Test
    fun `unsupported providers are rejected across all entry points`() = runTest {
        val api = newApi()

        assertFailsWith<SkillOAuthException> { api.status("user-1", "github") }
        assertFailsWith<SkillOAuthException> {
            api.startAuthorization("user-1", "github", "skill-1", emptyList())
        }
        assertFailsWith<SkillOAuthException> {
            api.callAuthorizedApi("user-1", "github", "skill-1", emptyList(), ApiCallRequest("GET", "https://example.com"))
        }
    }
}
