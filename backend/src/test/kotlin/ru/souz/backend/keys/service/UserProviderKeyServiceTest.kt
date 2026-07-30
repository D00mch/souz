package ru.souz.backend.keys.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import ru.souz.backend.testutil.repository.MemoryUserProviderKeyRepository
import ru.souz.db.AesGcmSecretCodec
import ru.souz.llms.LlmProvider

class UserProviderKeyServiceTest {
    @Test
    fun `put encrypts provider key before handing it to repository`() = runTest {
        val repository = MemoryUserProviderKeyRepository()
        val service = UserProviderKeyService(
            repository = repository,
            masterKey = "test-master-key",
        )

        val view = service.put(
            userId = "user-a",
            provider = LlmProvider.OPENAI,
            apiKey = "sk-user-a-plain-123456",
        )
        val stored = repository.get("user-a", LlmProvider.OPENAI)

        assertEquals("...3456", view.keyHint)
        assertNotNull(stored)
        assertTrue(AesGcmSecretCodec.isEncrypted(stored.encryptedApiKey))
        assertNotEquals("sk-user-a-plain-123456", stored.encryptedApiKey)
        assertEquals("sk-user-a-plain-123456", service.decrypt("user-a", LlmProvider.OPENAI))
    }
}
