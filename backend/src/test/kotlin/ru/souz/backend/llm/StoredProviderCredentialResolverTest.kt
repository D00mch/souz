package ru.souz.backend.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import ru.souz.backend.TestSettingsProvider
import ru.souz.backend.keys.service.UserProviderKeyService
import ru.souz.backend.testutil.repository.MemoryUserProviderKeyRepository
import ru.souz.llms.LlmProvider

class StoredProviderCredentialResolverTest {
    @Test
    fun `Giga credentials are never resolved by the backend`() = runTest {
        val repository = MemoryUserProviderKeyRepository()
        val keyService = UserProviderKeyService(repository, "test-master-key")
        keyService.put("user-a", LlmProvider.GIGA, "stored-giga-key")
        val resolver = StoredProviderCredentialResolver(
            baseSettingsProvider = TestSettingsProvider().apply {
                gigaChatKey = "server-giga-key"
            },
            userProviderKeyService = keyService,
        )

        assertNull(resolver.resolve("user-a", LlmProvider.GIGA))
    }

    @Test
    fun `Codex resolves only the server managed OAuth access token`() = runTest {
        val repository = MemoryUserProviderKeyRepository()
        val keyService = UserProviderKeyService(repository, "test-master-key")
        keyService.put("user-a", LlmProvider.CODEX, "user-codex-token")
        val resolver = StoredProviderCredentialResolver(
            baseSettingsProvider = TestSettingsProvider().apply {
                codexAccessToken = "server-codex-token"
                codexRefreshToken = "server-codex-refresh-token"
                codexAccountId = "server-codex-account-id"
                codexExpiresAt = 1_800_000_000L
            },
            userProviderKeyService = keyService,
        )

        val credential = resolver.resolve("user-a", LlmProvider.CODEX)

        assertEquals("server-codex-token", credential?.apiKey)
        assertEquals(CredentialSource.SERVER_MANAGED, credential?.source)
    }

    @Test
    fun `Codex does not resolve an incomplete server managed OAuth credential`() = runTest {
        val resolver = StoredProviderCredentialResolver(
            baseSettingsProvider = TestSettingsProvider().apply {
                codexAccessToken = "server-codex-token"
            },
            userProviderKeyService = UserProviderKeyService(MemoryUserProviderKeyRepository(), "test-master-key"),
        )

        assertNull(resolver.resolve("user-a", LlmProvider.CODEX))
    }
}
