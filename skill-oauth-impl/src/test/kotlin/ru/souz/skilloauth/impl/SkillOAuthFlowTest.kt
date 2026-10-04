package ru.souz.skilloauth.impl

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlinx.coroutines.test.runTest
import ru.souz.skilloauth.ApiCallReconnectRequired
import ru.souz.skilloauth.ApiCallRequest
import ru.souz.skilloauth.ApiCallResponse
import ru.souz.skilloauth.AuthorizationState
import ru.souz.skilloauth.SkillOAuthException

class SkillOAuthFlowTest {
    private val crypto = SkillOAuthTokenCrypto(Base64.getEncoder().encodeToString(ByteArray(32)))
    private val config = AuthorizationCodeOAuthConfig(
        name = "test-provider",
        authorizeEndpoint = "https://provider.example/authorize",
        tokenEndpoint = "https://provider.example/token",
        clientId = "client + &",
        clientSecret = "secret + &",
        redirectUri = "https://backend.example/oauth/callback",
        allowedApiHosts = setOf("api.provider.example"),
        extraAuthorizeParams = mapOf("access_type" to "offline"),
        authorizationScheme = "OAuth",
    )
    private val request = ApiCallRequest("GET", "https://api.provider.example/resource")

    @Test
    fun `credential writes remain durable when the datasource disables autocommit`() = runTest {
        skillOAuthTestDataSource(newSkillOAuthTestSchema("oauth_non_autocommit")).use { dataSource ->
            val transactionalDataSource = object : DataSource by dataSource {
                override fun getConnection() = dataSource.connection.apply { autoCommit = false }
            }
            val credentials = PostgresSkillOAuthCredentialRepository(transactionalDataSource)
            val now = MutableClock().instant()
            val stored = credentials.upsert(SkillOAuthCredential(
                userId = "user", provider = config.name, accessTokenEncrypted = crypto.encrypt("access"),
                refreshTokenEncrypted = null, grantedScopes = listOf("read"), expiresAt = null,
                generation = 1, createdAt = now, updatedAt = now,
            ))!!
            assertEquals(stored, credentials.find("user", config.name))
            credentials.delete("user", config.name)
            assertEquals(null, credentials.find("user", config.name))
        }
    }

    @Test
    fun `authorization callback and proactive refresh preserve the stored grant and forward the new token`() = runTest {
        for (rotateRefreshToken in listOf(false, true)) {
            val clock = MutableClock()
            val tokenRequests = mutableListOf<Map<String, String?>>()
            val authorizations = mutableListOf<String?>()
            HttpClient(MockEngine { request ->
                if (request.url.toString() == config.tokenEndpoint) {
                    val form = parseQueryString((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                    tokenRequests += form.names().associateWith { form[it] }
                    val response = if (form["grant_type"] == "authorization_code") {
                        """{"access_token":"access-1","refresh_token":"refresh-1","expires_in":120,"extra":"ignored"}"""
                    } else {
                        val refresh = if (rotateRefreshToken) "\"refresh_token\":\"refresh-2\"," else ""
                        """{"access_token":"access-2",${refresh}"expires_in":"3600","scope":" write   read "}"""
                    }
                    respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                } else {
                    authorizations += request.headers[HttpHeaders.Authorization]
                    respond("resource", HttpStatusCode.Accepted, headersOf("X-Provider", listOf("a", "b")))
                }
            }).use { httpClient ->
                skillOAuthTestDataSource(newSkillOAuthTestSchema("oauth_flow")).use { dataSource ->
                    val credentials = PostgresSkillOAuthCredentialRepository(dataSource)
                    val gateway = gateway(credentials, PostgresSkillOAuthPendingStateRepository(dataSource), httpClient, clock)
                    val authorization = assertIs<AuthorizationState.AuthorizationRequired>(
                        gateway.ensureAuthorized("user", config.name, setOf("read", "write"), false),
                    )
                    val query = Url(authorization.url).parameters
                    assertEquals(config.clientId, query["client_id"])
                    assertEquals(config.redirectUri, query["redirect_uri"])
                    assertEquals("read write", query["scope"])
                    assertEquals("offline", query["access_type"])
                    val state = query["state"]!!
                    assertEquals(32, Base64.getUrlDecoder().decode(state).size)
                    assertEquals(CallbackResult.Connected(config.name), gateway.handleCallback("code + &", state))
                    assertEquals(CallbackResult.InvalidOrExpiredState, gateway.handleCallback("code + &", state))
                    val original = credentials.find("user", config.name)!!
                    assertNotEquals("access-1", original.accessTokenEncrypted)
                    assertEquals("access-1", crypto.decrypt(original.accessTokenEncrypted))
                    assertEquals(listOf("read", "write"), original.grantedScopes)
                    assertEquals(clock.instant().plusSeconds(120), original.expiresAt)
                    assertEquals(AuthorizationState.Connected, gateway.ensureAuthorized("user", config.name, setOf("read"), false))

                    val response = assertIs<ApiCallResponse>(gateway.call("user", config.name, setOf("read"), request))
                    assertEquals(202, response.statusCode)
                    assertEquals("resource", response.body)
                    assertEquals("a, b", response.headers["X-Provider"])
                    assertEquals(1, tokenRequests.size)
                    clock.now = clock.now.plusSeconds(60)
                    assertIs<ApiCallResponse>(gateway.call("user", config.name, setOf("read"), request))

                    assertEquals<List<String?>>(listOf("OAuth access-1", "OAuth access-2"), authorizations)
                    assertEquals<List<Map<String, String?>>>(listOf(
                        mapOf("grant_type" to "authorization_code", "code" to "code + &", "client_id" to config.clientId,
                            "client_secret" to config.clientSecret, "redirect_uri" to config.redirectUri),
                        mapOf("grant_type" to "refresh_token", "refresh_token" to "refresh-1", "client_id" to config.clientId,
                            "client_secret" to config.clientSecret),
                    ), tokenRequests)
                    val refreshed = credentials.find("user", config.name)!!
                    assertEquals("access-2", crypto.decrypt(refreshed.accessTokenEncrypted))
                    assertEquals(if (rotateRefreshToken) "refresh-2" else "refresh-1", crypto.decrypt(refreshed.refreshTokenEncrypted!!))
                    assertEquals(listOf("write", "read"), refreshed.grantedScopes)
                    assertEquals(clock.instant().plusSeconds(3600), refreshed.expiresAt)
                    assertEquals(original.generation, refreshed.generation)
                    assertEquals(original.revision + 1, refreshed.revision)
                    assertEquals(original.createdAt, refreshed.createdAt)
                    assertEquals(clock.instant(), refreshed.updatedAt)
                }
            }
        }
    }

    @Test
    fun `only invalid grant clears refresh credentials and returns a reconnect link`() = runTest {
        for (error in listOf("invalid_grant", "invalid_client", "server_error", "temporarily_unavailable")) {
            val clock = MutableClock()
            HttpClient(MockEngine {
                respond("""{"error":"$error","error_description":"provider failure"}""", HttpStatusCode.BadRequest)
            }).use { httpClient ->
                skillOAuthTestDataSource(newSkillOAuthTestSchema("oauth_refresh_error")).use { dataSource ->
                    val credentials = PostgresSkillOAuthCredentialRepository(dataSource)
                    val original = credentials.upsert(SkillOAuthCredential(
                        userId = "user", provider = config.name, accessTokenEncrypted = crypto.encrypt("expired"),
                        refreshTokenEncrypted = crypto.encrypt("refresh"), grantedScopes = listOf("read", "write"),
                        expiresAt = clock.instant(), generation = 1, createdAt = clock.instant(), updatedAt = clock.instant(),
                    ))!!
                    val gateway = gateway(credentials, PostgresSkillOAuthPendingStateRepository(dataSource), httpClient, clock)
                    val message = "OAuth refresh for '${config.name}' failed ($error): OAuth token request failed: $error provider failure."
                    if (error == "invalid_grant") {
                        val outcome = assertIs<ApiCallReconnectRequired>(gateway.call("user", config.name, setOf("read"), request))
                        assertEquals("$message Open this link to reconnect, then retry: ${outcome.authorizationUrl}", outcome.message)
                        assertEquals("read write", Url(outcome.authorizationUrl).parameters["scope"])
                        assertEquals(original.copy(refreshTokenEncrypted = null, revision = original.revision + 1), credentials.find("user", config.name))
                        assertIs<AuthorizationState.AuthorizationRequired>(gateway.ensureAuthorized("user", config.name, setOf("read"), false))
                    } else {
                        assertEquals(message, assertFailsWith<SkillOAuthException> {
                            gateway.call("user", config.name, setOf("read"), request)
                        }.message)
                        assertEquals(original, credentials.find("user", config.name))
                        assertEquals(AuthorizationState.Connected, gateway.ensureAuthorized("user", config.name, setOf("read"), false))
                    }
                }
            }
        }
    }

    @Test
    fun `failed code exchange consumes the link without storing credentials`() = runTest {
        for ((body, reason) in listOf(
            """{"error":"invalid_grant","error_description":"bad code"}""" to "OAuth token request failed: invalid_grant bad code",
            "{}" to "OAuth token request failed: unknown_error ",
        )) {
            HttpClient(MockEngine { respond(body, HttpStatusCode.BadRequest) }).use { httpClient ->
                val credentials = InMemorySkillOAuthCredentialRepository()
                val gateway = gateway(credentials, InMemorySkillOAuthPendingStateRepository(), httpClient, MutableClock())
                val authorization = assertIs<AuthorizationState.AuthorizationRequired>(gateway.ensureAuthorized("user", config.name, emptySet(), false))
                val state = Url(authorization.url).parameters["state"]!!
                assertEquals(null, Url(authorization.url).parameters["scope"])
                assertEquals(CallbackResult.ExchangeFailed(reason), gateway.handleCallback("code", state))
                assertEquals(null, credentials.find("user", config.name))
                assertEquals(CallbackResult.InvalidOrExpiredState, gateway.handleCallback("code", state))
            }
        }
    }

    private fun gateway(
        credentials: SkillOAuthCredentialRepository,
        pending: SkillOAuthPendingStateRepository,
        httpClient: HttpClient,
        clock: Clock,
    ) = SkillOAuthGatewayImpl(credentials, pending, crypto,
        mapOf(config.name to AuthorizationCodeOAuthClient(config, httpClient)), httpClient, clock)

    private class MutableClock(var now: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    }
}
