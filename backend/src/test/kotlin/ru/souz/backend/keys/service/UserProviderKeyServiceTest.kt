package ru.souz.backend.keys.service

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import ru.souz.backend.storage.filesystem.FilesystemStorageLayout
import ru.souz.backend.storage.filesystem.FilesystemUserProviderKeyRepository
import ru.souz.db.AesGcmSecretCodec
import ru.souz.llms.LlmProvider

class UserProviderKeyServiceTest {
    @Test
    fun `put keeps encryption in service when repository is filesystem backed`() = runTest {
        val dataDir = Files.createTempDirectory("user-provider-key-service")
        val repository = FilesystemUserProviderKeyRepository(dataDir = dataDir)
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
        val storedFile = Files.readString(FilesystemStorageLayout(dataDir).providerKeysFile("user-a"))

        assertEquals("...3456", view.keyHint)
        assertNotNull(stored)
        assertTrue(AesGcmSecretCodec.isEncrypted(stored.encryptedApiKey))
        assertNotEquals("sk-user-a-plain-123456", stored.encryptedApiKey)
        assertEquals("sk-user-a-plain-123456", service.decrypt("user-a", LlmProvider.OPENAI))
        assertFalse(storedFile.contains("sk-user-a-plain-123456"))
        assertTrue(storedFile.contains("\"encryptedApiKey\""))
    }
}
