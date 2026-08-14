package ru.souz.skilloauth.impl

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthorizationCodeOAuthClientTest {
    private fun newClient(extraAuthorizeParams: Map<String, String> = emptyMap()) = AuthorizationCodeOAuthClient(
        AuthorizationCodeOAuthConfig(
            name = "test-provider",
            authorizeEndpoint = "https://provider.example/authorize",
            tokenEndpoint = "https://provider.example/token",
            clientId = "client-1",
            clientSecret = "secret-1",
            redirectUri = "https://backend.example/oauth/callback",
            allowedApiHosts = setOf("api.provider.example"),
            extraAuthorizeParams = extraAuthorizeParams,
        ),
    )

    @Test
    fun `buildAuthorizeUrl appends extraAuthorizeParams verbatim`() {
        val client = newClient(extraAuthorizeParams = mapOf("access_type" to "offline", "prompt" to "consent"))

        val url = client.buildAuthorizeUrl(state = "state-1", scopes = listOf("scope-a"))

        assertTrue(url.contains("access_type=offline"), url)
        assertTrue(url.contains("prompt=consent"), url)
    }

    @Test
    fun `buildAuthorizeUrl adds nothing extra when extraAuthorizeParams is empty`() {
        val client = newClient()

        val url = client.buildAuthorizeUrl(state = "state-1", scopes = listOf("scope-a"))

        assertFalse(url.contains("access_type"))
        assertFalse(url.contains("prompt"))
    }
}
