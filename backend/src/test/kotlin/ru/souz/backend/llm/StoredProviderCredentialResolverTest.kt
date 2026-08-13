package ru.souz.backend.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import ru.souz.backend.TestSettingsProvider
import ru.souz.backend.toBackendSettingsConfig
import ru.souz.backend.keys.service.UserProviderKeyService
import ru.souz.backend.testutil.repository.MemoryUserProviderKeyRepository
import ru.souz.llms.LlmProvider
import ru.souz.llms.codex.SettingsProviderCodexOAuthCredentialStore

class StoredProviderCredentialResolverTest {
    @Test
    fun `Codex resolves only the server managed OAuth access token`() = runTest {
        val repository = MemoryUserProviderKeyRepository()
        val keyService = UserProviderKeyService(repository, "test-master-key")
        keyService.put("user-a", LlmProvider.CODEX, "user-codex-token")
        val settingsProvider = TestSettingsProvider().apply {
                codexAccessToken = "server-codex-token"
                codexRefreshToken = "server-codex-refresh-token"
                codexAccountId = "server-codex-account-id"
                codexExpiresAt = 1_800_000_000L
            }
        val resolver = StoredProviderCredentialResolver(
            settingsConfig = settingsProvider.toBackendSettingsConfig(),
            userProviderKeyService = keyService,
            codexOAuthCredentialStore = SettingsProviderCodexOAuthCredentialStore(settingsProvider),
        )

        val credential = resolver.resolve("user-a", LlmProvider.CODEX)

        assertEquals("server-codex-token", credential?.apiKey)
        assertEquals(CredentialSource.SERVER_MANAGED, credential?.source)
    }

    @Test
    fun `Codex does not resolve an incomplete server managed OAuth credential`() = runTest {
        val settingsProvider = TestSettingsProvider().apply {
                codexAccessToken = "server-codex-token"
            }
        val resolver = StoredProviderCredentialResolver(
            settingsConfig = settingsProvider.toBackendSettingsConfig(),
            userProviderKeyService = UserProviderKeyService(MemoryUserProviderKeyRepository(), "test-master-key"),
            codexOAuthCredentialStore = SettingsProviderCodexOAuthCredentialStore(settingsProvider),
        )

        assertNull(resolver.resolve("user-a", LlmProvider.CODEX))
    }
}
