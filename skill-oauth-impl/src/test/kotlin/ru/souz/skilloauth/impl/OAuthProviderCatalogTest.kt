package ru.souz.skilloauth.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OAuthProviderCatalogTest {
    @Test
    fun `loads every registered provider from oauth-providers,json`() {
        val names = OAuthProviderCatalog.entries.map { it.name }

        assertTrue("yandex" in names)
        assertTrue("google" in names)
    }

    @Test
    fun `entries without extraAuthorizeParams default to empty`() {
        val yandex = OAuthProviderCatalog.entries.single { it.name == "yandex" }

        assertEquals(emptyMap(), yandex.extraAuthorizeParams)
    }

    @Test
    fun `yandex uses the OAuth authorization scheme, not the RFC 6750 default`() {
        // Yandex's own APIs (login.yandex.ru, cloud-api.yandex.net) expect
        // `Authorization: OAuth <token>` and respond 401 to a `Bearer`-prefixed token even though
        // the token itself is valid.
        val yandex = OAuthProviderCatalog.entries.single { it.name == "yandex" }

        assertEquals("OAuth", yandex.authorizationScheme)
    }

    @Test
    fun `google requests offline access and forces the consent prompt`() {
        // Google only returns a refresh_token on the first-ever consent unless the authorize URL
        // carries access_type=offline and prompt=consent — without both, a reconnect (forceReconnect)
        // would silently come back with no refresh_token, leaving the credential unable to
        // auto-refresh once its access token expires.
        val google = OAuthProviderCatalog.entries.single { it.name == "google" }

        assertEquals("offline", google.extraAuthorizeParams["access_type"])
        assertEquals("consent", google.extraAuthorizeParams["prompt"])
        assertEquals(setOf("www.googleapis.com"), google.allowedApiHosts)
        assertEquals("Bearer", google.authorizationScheme)
    }
}
