package ru.souz.llms.codex

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.jackson.jackson
import io.ktor.utils.io.ByteChannel
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import ru.souz.db.SettingsProvider
import java.io.IOException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class CodexOAuthServiceTest {

    @Test
    fun `poll network failure is retried and later success stores tokens`() = runTest {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        val accessToken = accessToken()
        var pollRequests = 0
        val service = CodexOAuthService(
            settingsProvider = settingsProvider,
            client = mockCodexClient { path ->
                when {
                    path.endsWith("/api/accounts/deviceauth/usercode") ->
                        jsonResponse("""{"device_auth_id":"device-1","user_code":"ABCD-EFGH","interval":1}""")

                    path.endsWith("/api/accounts/deviceauth/token") -> {
                        pollRequests++
                        if (pollRequests == 1) throw IOException()
                        jsonResponse("""{"authorization_code":"auth-code","code_verifier":"verifier"}""")
                    }

                    path.endsWith("/oauth/token") ->
                        jsonResponse(
                            """{"access_token":"$accessToken","refresh_token":"refresh-token","expires_in":3600}"""
                        )

                    else -> error("Unexpected request path: $path")
                }
            },
            pollDelay = {},
        )

        val emittedStates = mutableListOf<CodexOAuthState>()
        val collectJob = backgroundScope.launch {
            service.oauthState.collect { emittedStates.add(it) }
        }
        yield()

        service.startDeviceFlow()
        collectJob.cancel()

        assertIs<CodexOAuthState.Success>(service.oauthState.value)
        assertTrue(
            emittedStates.none { it is CodexOAuthState.Error && it.message == "Unknown error" },
            "Unexpected Unknown error state in $emittedStates",
        )
        assertEquals(2, pollRequests)
        verify { settingsProvider.codexAccessToken = accessToken }
        verify { settingsProvider.codexRefreshToken = "refresh-token" }
        verify { settingsProvider.codexAccountId = "acct-test" }
        verify { settingsProvider.codexExpiresAt = any() }
    }

    @Test
    fun `repeated poll network failures time out instead of unknown error`() = runTest {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        var pollRequests = 0
        val service = CodexOAuthService(
            settingsProvider = settingsProvider,
            client = mockCodexClient { path ->
                when {
                    path.endsWith("/api/accounts/deviceauth/usercode") ->
                        jsonResponse("""{"device_auth_id":"device-1","user_code":"ABCD-EFGH","interval":1}""")

                    path.endsWith("/api/accounts/deviceauth/token") -> {
                        pollRequests++
                        throw IOException()
                    }

                    else -> error("Unexpected request path: $path")
                }
            },
            pollDelay = {},
            maxPollAttempts = 3,
        )

        service.startDeviceFlow()

        val error = assertIs<CodexOAuthState.Error>(service.oauthState.value)
        assertEquals("Timed out waiting for authorization", error.message)
        assertEquals(3, pollRequests)
    }

    @Test
    fun `poll body read failure is retried and later success stores tokens`() = runTest {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        val accessToken = accessToken()
        var pollRequests = 0
        val service = CodexOAuthService(
            settingsProvider = settingsProvider,
            client = mockCodexClient { path ->
                when {
                    path.endsWith("/api/accounts/deviceauth/usercode") ->
                        jsonResponse("""{"device_auth_id":"device-1","user_code":"ABCD-EFGH","interval":1}""")

                    path.endsWith("/api/accounts/deviceauth/token") -> {
                        pollRequests++
                        if (pollRequests == 1) failingBodyResponse()
                        else jsonResponse("""{"authorization_code":"auth-code","code_verifier":"verifier"}""")
                    }

                    path.endsWith("/oauth/token") ->
                        jsonResponse(
                            """{"access_token":"$accessToken","refresh_token":"refresh-token","expires_in":3600}"""
                        )

                    else -> error("Unexpected request path: $path")
                }
            },
            pollDelay = {},
        )

        service.startDeviceFlow()

        assertIs<CodexOAuthState.Success>(service.oauthState.value)
        assertEquals(2, pollRequests)
        verify { settingsProvider.codexAccessToken = accessToken }
        verify { settingsProvider.codexRefreshToken = "refresh-token" }
        verify { settingsProvider.codexAccountId = "acct-test" }
        verify { settingsProvider.codexExpiresAt = any() }
    }

    @Test
    fun `malformed poll success response enters error state`() = runTest {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        var pollRequests = 0
        val service = CodexOAuthService(
            settingsProvider = settingsProvider,
            client = mockCodexClient { path ->
                when {
                    path.endsWith("/api/accounts/deviceauth/usercode") ->
                        jsonResponse("""{"device_auth_id":"device-1","user_code":"ABCD-EFGH","interval":1}""")

                    path.endsWith("/api/accounts/deviceauth/token") -> {
                        pollRequests++
                        jsonResponse("not-json")
                    }

                    else -> error("Unexpected request path: $path")
                }
            },
            pollDelay = {},
        )

        service.startDeviceFlow()

        val error = assertIs<CodexOAuthState.Error>(service.oauthState.value)
        assertEquals("Malformed authorization polling response", error.message)
        assertEquals(1, pollRequests)
        verify(exactly = 0) { settingsProvider.codexAccessToken = any() }
    }

    @Test
    fun `malformed token exchange response enters error state`() = runTest {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        val service = CodexOAuthService(
            settingsProvider = settingsProvider,
            client = mockCodexClient { path ->
                when {
                    path.endsWith("/api/accounts/deviceauth/usercode") ->
                        jsonResponse("""{"device_auth_id":"device-1","user_code":"ABCD-EFGH","interval":1}""")

                    path.endsWith("/api/accounts/deviceauth/token") ->
                        jsonResponse("""{"authorization_code":"auth-code","code_verifier":"verifier"}""")

                    path.endsWith("/oauth/token") ->
                        jsonResponse("""{"refresh_token":"refresh-token","expires_in":3600}""")

                    else -> error("Unexpected request path: $path")
                }
            },
            pollDelay = {},
        )

        service.startDeviceFlow()

        val error = assertIs<CodexOAuthState.Error>(service.oauthState.value)
        assertEquals("Malformed token exchange response", error.message)
        verify(exactly = 0) { settingsProvider.codexAccessToken = any() }
    }

    @Test
    fun `cancel flow resets state to idle`() = runBlocking {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        val service = CodexOAuthService(
            settingsProvider = settingsProvider,
            client = mockCodexClient { path ->
                when {
                    path.endsWith("/api/accounts/deviceauth/usercode") ->
                        jsonResponse("""{"device_auth_id":"device-1","user_code":"ABCD-EFGH","interval":1}""")

                    else -> error("Unexpected request path: $path")
                }
            },
            pollDelay = { awaitCancellation() },
        )

        service.launchFlow(this)
        val awaitingUserCode = withTimeout(5.seconds) {
            service.oauthState.first { it is CodexOAuthState.AwaitingUserCode }
        }
        assertIs<CodexOAuthState.AwaitingUserCode>(awaitingUserCode)

        service.cancelFlow()

        assertTrue(service.oauthState.value is CodexOAuthState.Idle)
    }

    private fun mockCodexClient(handler: MockRequestHandleScope.(String) -> HttpResponseData): HttpClient =
        HttpClient(MockEngine) {
            install(ContentNegotiation) {
                jackson()
            }
            engine {
                addHandler { request -> handler(request.url.encodedPath) }
            }
        }

    private fun MockRequestHandleScope.jsonResponse(content: String) =
        respond(
            content = content,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )

    private fun MockRequestHandleScope.failingBodyResponse(): HttpResponseData {
        val channel = ByteChannel()
        channel.cancel(IOException())
        return respond(
            content = channel,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }

    private fun accessToken(accountId: String = "acct-test"): String {
        val payload = """{"chatgpt_account_id":"$accountId"}"""
        val encodedPayload = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(payload.toByteArray())
        return "header.$encodedPayload.signature"
    }
}
