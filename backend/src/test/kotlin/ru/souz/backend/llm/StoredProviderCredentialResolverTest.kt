package ru.souz.backend.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import ru.souz.backend.TestSettingsProvider
import ru.souz.backend.keys.service.UserProviderKeyService
import ru.souz.backend.testutil.repository.MemoryUserProviderKeyRepository
import ru.souz.llms.LlmProvider

class StoredProviderCredentialResolverTest {
    @Test
    fun `Codex resolves only the server managed OAuth access token`() = runTest {
        val repository = MemoryUserProviderKeyRepository()
        val keyService = UserProviderKeyService(repository, "test-master-key")
        keyService.put("user-a", LlmProvider.CODEX, "user-codex-token")
        val resolver = StoredProviderCredentialResolver(
            baseSettingsProvider = TestSettingsProvider().apply {
                codexAccessToken = "server-codex-token"
            },
            userProviderKeyService = keyService,
        )

        val credential = resolver.resolve("user-a", LlmProvider.CODEX)

        assertEquals("server-codex-token", credential?.apiKey)
        assertEquals(CredentialSource.SERVER_MANAGED, credential?.source)
    }
}
